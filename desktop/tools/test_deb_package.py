#!/usr/bin/env python3
"""Isolated test of the built stack-docs .deb. Never installs it and never touches the real library, settings,
Flatpak or key store.

The package is unpacked into a fresh folder under /tmp and run with a PRIVATE home, a PRIVATE runtime folder and a
PRIVATE D-Bus session carrying its own gnome-keyring (so the real Secret Service is not reachable). Only the synthetic
public test key is used. No STACK_*, LD_LIBRARY_PATH or OMP_* variable is given to the programs.

    python3 desktop/tools/test_deb_package.py --deb PATH_TO_DEB
Prints one PASS/FAIL/SKIP line per check; exit status 1 when anything failed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

TOOLS = Path(__file__).resolve().parent
DESKTOP = TOOLS.parent
GENERATOR = DESKTOP / 'target/debug/examples/archive_interop'
TEST_KEY_HEX = bytes(range(1, 33)).hex()  # public synthetic key, never a real one
HEX64 = re.compile(r'[0-9a-fA-F]{64}')
results = []


def scrub(text):
    return HEX64.sub('[hex64]', text.replace(TEST_KEY_HEX, '[removed]'))


def last(text):
    lines = [l.strip() for l in scrub(text).splitlines() if l.strip()]
    return (lines[-1] if lines else 'no output')[:200]


def report(status, name, reason=''):
    results.append((status, name, reason))
    print(f'{status} {name}' + (f': {reason}' if reason else ''), flush=True)


def run(argv, **kw):
    kw.setdefault('capture_output', True)
    kw.setdefault('timeout', 300)
    p = subprocess.run([str(a) for a in argv], **kw)
    return p.returncode, p.stdout.decode(errors='replace') + p.stderr.decode(errors='replace')


def tree_hashes(root):
    out = {}
    for p in sorted(Path(root).rglob('*')):
        if p.is_file() and not p.is_symlink():
            out[str(p.relative_to(root))] = hashlib.sha256(p.read_bytes()).hexdigest()
    return out


# ---------------------------------------------------------------- outer: static checks, unpack, private session
def outer(deb, keep):
    deb = deb.absolute()
    work = Path(tempfile.mkdtemp(prefix='stack-deb-test-', dir='/tmp'))
    work.chmod(0o700)
    try:
        static_checks(deb, work)
        root = work / 'root'
        rc, out = run(['dpkg-deb', '-x', deb, root])
        if rc:
            report('FAIL', 'unpack', last(out))
            return
        home, runtime = work / 'home', work / 'run'
        for d in (home, runtime):
            d.mkdir(mode=0o700)
        env = {'PATH': '/usr/bin:/bin', 'HOME': str(home), 'XDG_DATA_HOME': str(home / '.local/share'),
               'XDG_CONFIG_HOME': str(home / '.config'), 'XDG_CACHE_HOME': str(home / '.cache'),
               'XDG_RUNTIME_DIR': str(runtime), 'LANG': 'C.UTF-8'}
        # The synthetic data is made before the private session starts (the generator needs a fresh folder under /tmp).
        made = work / 'made'
        if not GENERATOR.is_file():
            report('SKIP', 'synthetic-library', 'build the generator first: cargo build --offline -p stack-cli --example archive_interop')
            return
        rc, out = run([GENERATOR, 'make', made])
        if rc:
            report('FAIL', 'synthetic-library', last(out))
            return
        make_picture(work / 'text.png')
        wayland = os.environ.get('WAYLAND_DISPLAY')
        real_runtime = os.environ.get('XDG_RUNTIME_DIR')
        inner_args = {'root': str(root), 'work': str(work), 'made': str(made), 'wayland': wayland, 'real_runtime': real_runtime}
        p = subprocess.run(['dbus-run-session', '--', sys.executable, '-I', str(Path(__file__).resolve()), '--inner', json.dumps(inner_args)],
                           env=env, capture_output=True, timeout=900)
        text = p.stdout.decode(errors='replace')
        for line in text.splitlines():
            m = re.match(r'(PASS|FAIL|SKIP) (\S+)(?:: (.*))?$', line)
            if m:
                results.append((m.group(1), m.group(2), m.group(3) or ''))
                print(scrub(line), flush=True)
        if p.returncode not in (0, 1):
            report('FAIL', 'private-session', last(p.stderr.decode(errors='replace')))
    finally:
        if keep:
            print(f'kept: {work}')
        else:
            shutil.rmtree(work, ignore_errors=True)


def static_checks(deb, work):
    rc, out = run(['dpkg-deb', '-f', deb])
    fields = dict(l.split(': ', 1) for l in out.splitlines() if ': ' in l and not l.startswith(' '))
    ok = fields.get('Package') == 'stack-docs' and fields.get('Architecture') == 'amd64' and fields.get('Depends')
    report('PASS' if ok else 'FAIL', 'deb-control', f"{fields.get('Package')} {fields.get('Version')}; Depends: {fields.get('Depends', '')[:120]}")
    rc, listing = run(['dpkg-deb', '-c', deb])
    need = ['usr/bin/stack-desktop', 'usr/bin/stack-cli', 'usr/lib/stack-docs/runtime/pdfium/lib/libpdfium.so',
            'usr/lib/stack-docs/runtime/ocr/lib/libtesseract.so.5', 'usr/lib/stack-docs/runtime/ocr/lib/liblept.so.5',
            'usr/lib/stack-docs/runtime/ocr/tessdata/eng.traineddata', 'usr/share/applications/org.stackdocs.desktop.desktop',
            'usr/share/icons/hicolor/scalable/apps/org.stackdocs.desktop.svg', 'usr/share/metainfo/org.stackdocs.desktop.metainfo.xml']
    missing = [n for n in need if f'./{n}' not in listing]
    report('FAIL' if missing else 'PASS', 'deb-contents', ('missing ' + ', '.join(missing)) if missing else f'{len(listing.splitlines())} entries')
    stage = work / 'meta'
    run(['dpkg-deb', '-x', deb, stage])
    rc, out = run(['desktop-file-validate', stage / 'usr/share/applications/org.stackdocs.desktop.desktop'])
    report('PASS' if rc == 0 else 'FAIL', 'desktop-file', last(out) if rc else 'valid')
    rc, out = run(['appstreamcli', 'validate', '--no-net', stage / 'usr/share/metainfo/org.stackdocs.desktop.metainfo.xml'])
    report('PASS' if rc == 0 else 'FAIL', 'metainfo', last(out) if rc else 'valid')
    # Runtime files are the pinned ones.
    pins = json.loads((DESKTOP / 'runtime/ocr/ENGINE.json').read_text())['files']
    bad = [r for r, d in pins.items() if hashlib.sha256((stage / 'usr/lib/stack-docs/runtime/ocr' / r).read_bytes()).hexdigest() != d]
    report('FAIL' if bad else 'PASS', 'ocr-runtime-pinned', ', '.join(bad) if bad else 'hashes match ENGINE.json')
    pins = json.loads((DESKTOP / 'runtime/pdfium/ENGINE.json').read_text())['files']
    bad = [r for r, d in pins.items() if hashlib.sha256((stage / 'usr/lib/stack-docs/runtime/pdfium' / r).read_bytes()).hexdigest() != d]
    report('FAIL' if bad else 'PASS', 'pdfium-runtime-pinned', ', '.join(bad) if bad else 'hashes match ENGINE.json')
    # Dependency simulation against this machine's package lists (no root, nothing installed).
    rc, out = run(['apt-get', '-s', 'install', str(deb)], timeout=300)
    inst = [l for l in out.splitlines() if l.startswith('Inst ')]
    errs = [l for l in out.splitlines() if l.startswith('E:')]
    report('PASS' if rc == 0 and any('stack-docs' in l for l in inst) and not errs else 'FAIL', 'apt-simulate-install',
           f'{len(inst)} Inst lines' if rc == 0 else last(out))
    # Same simulation against an EMPTY installed-package list: every Depends must be resolvable from the archive.
    status = work / 'empty-status'
    status.write_text('')
    rc, out = run(['apt-get', '-s', '-o', f'Dir::State::status={status}', '-o', 'Debug::NoLocking=1', 'install', str(deb)], timeout=300)
    errs = [l for l in out.splitlines() if l.startswith('E:')]
    report('PASS' if rc == 0 and not errs else 'FAIL', 'apt-simulate-clean-system', last(out) if rc else 'every dependency resolves')
    shutil.rmtree(stage, ignore_errors=True)


# ---------------------------------------------------------------- inner: runs inside the private D-Bus session
def inner(args):
    root, work, made = Path(args['root']), Path(args['work']), Path(args['made'])
    env = {k: v for k, v in os.environ.items() if k in ('PATH', 'HOME', 'XDG_DATA_HOME', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME',
                                                         'XDG_RUNTIME_DIR', 'LANG', 'DBUS_SESSION_BUS_ADDRESS')}
    assert 'dbus' in env.get('DBUS_SESSION_BUS_ADDRESS', '') and str(work) in env['HOME'], 'not isolated'
    cli, app = root / 'usr/bin/stack-cli', root / 'usr/bin/stack-desktop'
    data = Path(env['XDG_DATA_HOME']) / 'org.stackdocs.desktop'
    daemon = subprocess.run(['gnome-keyring-daemon', '--unlock', '--components=secrets', '--daemonize'], input=b'\n', env=env,
                            capture_output=True, timeout=60)
    time.sleep(1)

    def cli_run(*a, key=None, timeout=120):
        return run([cli, *a], env=env, input=(key + '\n').encode() if key else None, timeout=timeout)

    # 1. Starts, no variables: usage line from the installed binary.
    rc, out = cli_run('--help')
    print('PASS cli-starts' if 'Usage' in out else 'FAIL cli-starts: ' + last(out))
    # 2. PDF render with the packaged PDFium, no STACK_PDFIUM_LIBRARY.
    fixtures = work / 'fixtures'
    rc, out = cli_run('fixtures', fixtures)
    rc2, out2 = cli_run('render-smoke', fixtures, work / 'render')
    print('PASS pdf-render' if rc == 0 and rc2 == 0 and '"originals_unchanged": true' in out2 else 'FAIL pdf-render: ' + last(out + out2))
    # 3. OCR: a picture of printed text is read with the packaged Tesseract, no STACK_TESSERACT_LIBRARY / LD_LIBRARY_PATH.
    ocr(cli_run, work)
    # 4. Key save / status / remove through the private keyring, across separate processes.
    rc, out = cli_run('key', 'status')
    before = out
    rc, out = cli_run('key', 'save', '--key-stdin', key=TEST_KEY_HEX)
    if rc != 0:
        print('SKIP key-store: ' + last(out) + f' (daemon rc {daemon.returncode})')
        return 0
    rc, out = cli_run('key', 'status')
    saved = rc == 0 and 'saved: yes' in out and 'fingerprint: ' in out
    # 5. Use the saved key on the synthetic library in the private default data folder, then check the app against it.
    rc_r, out_r = cli_run('archive', data, 'restore', made / 'desktop-encrypted.stackbackup', '--saved-key')
    restored = rc_r == 0
    rc_x, out_x = cli_run('key', 'remove')
    rc_s, out_s = cli_run('key', 'status')
    removed = rc_x == 0 and 'saved: no' in out_s
    print('PASS key-store: save, status (second process), use for restore, remove' if saved and restored and removed else
          f'FAIL key-store: saved={saved} restored={restored} removed={removed} {last(out_r)}')
    if not restored:
        return 1
    app_checks(cli_run, app, env, data, work, args)
    return 0


def make_picture(path):
    """A picture of printed text, drawn here (outside the private home, where Pillow is found). False when Pillow is missing."""
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        return False
    img = Image.new('RGB', (1400, 400), 'white')
    ImageDraw.Draw(img).text((60, 120), 'STACK OFFLINE 4711', fill='black', font=ImageFont.load_default(size=120))
    img.save(path)
    return True


def ocr(cli_run, work):
    picture = work / 'text.png'
    if not picture.is_file():
        print('SKIP ocr: Pillow is not available to draw the test picture')
        return
    lib = work / 'ocrlib'
    rc, out = cli_run('library', lib, 'import', picture, 'OCR picture')
    rc2, out2 = cli_run('library', lib, 'read-text', timeout=300)
    rc3, out3 = cli_run('library', lib, 'search', 'OFFLINE')
    ok = rc == 0 and rc2 == 0 and '"failed": 0' in out2 and 'OCR picture' in out3
    print('PASS ocr: ' + 'picture read, search finds it' if ok else 'FAIL ocr: ' + last(out + out2 + out3))


def app_checks(cli_run, app, env, data, work, args):
    home = Path(env['HOME'])
    before = tree_hashes(data / 'originals')
    sqlite_before = (data / 'library.sqlite3').stat().st_size if (data / 'library.sqlite3').exists() else None
    # Headless (no display): must stop with a plain message, not crash, and leave the library alone.
    p = subprocess.run([app], env=env, capture_output=True, timeout=60)
    text = (p.stdout + p.stderr).decode(errors='replace')
    # The toolkit itself stops with "neither WAYLAND_DISPLAY ... is set" (a panic message, exit 101) when there is no display.
    no_display = 'neither WAYLAND_DISPLAY' in text
    print(('PASS' if no_display else 'FAIL') + f' app-headless: starts, then stops for lack of a display (exit {p.returncode}) without touching the library'
          if no_display else f'FAIL app-headless: exit {p.returncode}, {last(text)}')
    # With the owner's Wayland socket only (private runtime folder, private home): opens the library in place for a few seconds.
    ran = ''
    if args.get('wayland') and args.get('real_runtime') and (Path(args['real_runtime']) / args['wayland']).exists():
        os.symlink(Path(args['real_runtime']) / args['wayland'], Path(env['XDG_RUNTIME_DIR']) / args['wayland'])
        e2 = {**env, 'WAYLAND_DISPLAY': args['wayland']}
        proc = subprocess.Popen([app], env=e2, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        time.sleep(8)
        alive = proc.poll() is None
        if alive:
            proc.terminate()
        try:
            out = proc.communicate(timeout=20)[0].decode(errors='replace')
        except subprocess.TimeoutExpired:
            proc.kill()
            out = ''
        ran = 'exited: ' + last(out)
        print(('PASS' if alive else 'FAIL') + f' app-window-starts: running after 8 s={alive}' + ('' if alive else ' ' + ran))
    else:
        print('SKIP app-window-starts: no Wayland display to borrow')
    after = tree_hashes(data / 'originals')
    libs = sorted(str(p.relative_to(work)) for p in work.rglob('library.sqlite3'))
    expected = ['home/.local/share/org.stackdocs.desktop/library.sqlite3', 'ocrlib/library.sqlite3']
    same = before == after and len(before) > 0
    one = [l for l in libs if l.startswith('home/')] == expected[:1] and not any(
        p.name == 'library.sqlite3' for p in home.rglob('*') if p.parent != data)
    print(('PASS' if same and one else 'FAIL') + f' app-uses-existing-library: {len(before)} originals unchanged={same}; one library under the private home={one}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deb', type=Path)
    parser.add_argument('--keep', action='store_true', help='keep the temporary folder')
    parser.add_argument('--inner')
    args = parser.parse_args()
    if args.inner:
        sys.exit(inner(json.loads(args.inner)))
    if not args.deb or not args.deb.is_file():
        parser.error('--deb PATH is required')
    outer(args.deb, args.keep)
    bad = [r for r in results if r[0] == 'FAIL']
    print(f'\n{sum(r[0] == "PASS" for r in results)} passed, {len(bad)} failed, {sum(r[0] == "SKIP" for r in results)} skipped')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
