#!/usr/bin/env python3
"""Run every automatable Stack check and print a short PASS/FAIL summary.

Start it with no arguments:  python3 desktop/tools/run_all_checks.py
(or double-click "Run Stack checks.desktop" in the repo root).  Optional: --large adds the slow size-and-limits step.

Dev-only tool, standard library only. It reuses the existing checks and never installs anything on the phone.
Synthetic public test key only; logs are scrubbed of key-like text and kept in a fresh folder under /tmp.
"""
import datetime
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))
import test_android_archive_interop as interop  # reuse its guards and Adb class unchanged  # noqa: E402

ADB = Path.home() / '.local/share/stack-android-sdk/platform-tools/adb'
ADB_PORT = 5037
HEX64 = re.compile(r'[0-9a-fA-F]{64}')
results = []  # (status, name, reason)
logdir = None


def scrub(text):
    for secret in (interop.TEST_KEY_HEX, *interop.PASSPHRASE_FORMS):
        text = text.replace(secret, '[removed]')
    return HEX64.sub('[hex64]', text)


def save_log(name, text):
    (logdir / (name + '.log')).write_text(scrub(text))


_begun = {}


def begin(name):
    """Tell the user right away which step is running (the first steps take a minute or more)."""
    _begun[name] = time.time()
    print(f'Running {name} ...', flush=True)


def report(status, name, reason=''):
    results.append((status, name, reason))
    took = f' ({time.time() - _begun[name]:.0f}s)' if name in _begun else ''
    print(f'{status} {name}' + (f': {reason}' if reason else '') + took, flush=True)


def last_line(text):
    lines = [l.strip() for l in scrub(text).splitlines() if l.strip()]
    return (lines[-1] if lines else 'no output')[:160]


def run_step(name, argv, cwd=REPO, env=None, timeout=3600):
    """Run one existing check; PASS when it exits 0."""
    begin(name)
    try:
        p = subprocess.run([str(a) for a in argv], cwd=cwd, env=env, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        report('FAIL', name, f'timed out after {timeout // 60} minutes (see log)')
        return False
    text = p.stdout.decode(errors='replace') + p.stderr.decode(errors='replace')
    save_log(name, text)
    if p.returncode == 0:
        report('PASS', name)
        return True
    report('FAIL', name, last_line(text) + f' (exit {p.returncode})')
    return False


def phone_state():
    """Return (adb or None, reason). The adb object is the harness's guarded one."""
    if not ADB.is_file():
        return None, 'adb not found'
    adb = interop.Adb(interop.real_runner, ADB, ADB_PORT)
    try:
        serial = adb._run(['get-serialno'], timeout=20).out.decode().strip()
    except Exception:
        return None, 'test phone not connected'
    if serial != interop.ALLOWED_SERIAL:
        return None, 'test phone not connected'
    try:
        adb.preflight()  # model check, instrumentation installed, no other instrumentation running
    except interop.Refused as e:
        return None, str(e)
    window = adb.shell('dumpsys', 'window', check=False, timeout=60).out.decode(errors='replace')
    if re.search(r'isKeyguardShowing=true|mDreamingLockscreen=true', window):
        return None, 'phone is locked: unlock it and run again'
    return adb, ''


def installed_older_than_apk(adb):
    out = adb.shell('dumpsys', 'package', interop.PACKAGE, check=False, timeout=60).out.decode(errors='replace')
    m = re.search(r'lastUpdateTime=(\d{4}-\d\d-\d\d \d\d:\d\d)', out)
    apks = [REPO / 'app/build/outputs/apk/debug/app-debug.apk', REPO / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    apks = [a for a in apks if a.exists()]
    if not m or not apks:
        return False
    installed = datetime.datetime.strptime(m.group(1), '%Y-%m-%d %H:%M')
    built = datetime.datetime.fromtimestamp(max(a.stat().st_mtime for a in apks))
    return built > installed + datetime.timedelta(minutes=1)


def phone_suite(adb, name, option):
    begin(name)
    cmd = ['am', 'instrument', '-w', '-r', '-e', option, 'true', interop.INSTRUMENT]
    try:
        text = adb.shell(*cmd, check=False, timeout=1800).out.decode(errors='replace')
    except Exception as e:  # timeouts and adb failures
        report('FAIL', name, f'phone did not answer ({type(e).__name__})')
        return
    save_log(name, text)
    if interop.TEST_KEY_HEX in text.lower():
        report('FAIL', name, 'key-like text in phone output (log scrubbed)')
        return
    passes = len(re.findall(r'\bPASS\b', text))
    bad = [l.strip() for l in text.splitlines() if re.search(r'\bFAIL\b', l)]
    if bad:
        report('FAIL', name, scrub(bad[0])[:140])
    elif 'INSTRUMENTATION_CODE: -1' not in text:
        report('FAIL', name, last_line(text))
    else:
        report('PASS', name, f'{passes} checks' if passes else '')


FLATPAK_TEST_ID = 'org.stackdocs.desktop.test'  # the TEST Flatpak only; never the real org.stackdocs.desktop
FLATPAK_CLI = '/app/lib/stack/bin/stack-cli'


def flatpak_run(*args, key=None, timeout=60):
    """One `flatpak run` of stack-cli in the test Flatpak. `key` (the public test key) goes on stdin only."""
    argv = ['flatpak', 'run', '--user', '--env=STACK_PDFIUM_LIBRARY=/app/lib/stack/runtime/pdfium/lib/libpdfium.so', f'--command={FLATPAK_CLI}', FLATPAK_TEST_ID, *map(str, args)]
    try:
        p = subprocess.run(argv, input=(key + '\n').encode() if key else None, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        # No answer from the secret portal (for example a desktop with no Secret portal backend).
        subprocess.run(['flatpak', 'kill', FLATPAK_TEST_ID], capture_output=True, timeout=60)
        return 124, 'timed out waiting for the secret store'
    return p.returncode, p.stdout.decode(errors='replace') + p.stderr.decode(errors='replace')


def flatpak_key_store(cargo_env):
    """Save/use/remove the synthetic test key in the TEST Flatpak's own (portal) key store, across separate runs.
    SKIP when the Flatpak or the secret store is not available in this session."""
    name = 'flatpak-key-store'
    begin(name)
    try:
        installed = subprocess.run(['flatpak', 'info', '--user', FLATPAK_TEST_ID], capture_output=True, timeout=60).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        installed = False
    if not installed:
        report('SKIP', name, f'the test Flatpak {FLATPAK_TEST_ID} is not installed for this user')
        return
    # The manifest already grants this app's own data folder, so the synthetic backup goes in a temporary folder
    # there (no extra permission, nothing on the command line). It is removed at the end.
    data = Path(os.environ.get('XDG_DATA_HOME') or Path.home() / '.local/share') / FLATPAK_TEST_ID
    work = data / f'key-store-check-{os.getpid()}'
    log = []
    saved = False
    made = None
    try:
        work.mkdir(parents=True, mode=0o700)
        bins = REPO / 'desktop/target/debug'
        for cmd in (['cargo', 'build', '--offline', '-p', 'stack-cli', '--example', 'archive_interop'],):
            b = subprocess.run(cmd, cwd=REPO / 'desktop', env=cargo_env, capture_output=True, timeout=3600)
            if b.returncode:
                raise RuntimeError('could not build the test-data generator')
        gen = [str(bins / 'examples/archive_interop')]
        made = Path(tempfile.mkdtemp(prefix='stack-key-check-', dir='/tmp')) / 'made'  # the generator wants a fresh folder under /tmp
        subprocess.run([*gen, 'make', str(made)], check=True, capture_output=True, timeout=600)
        backup, expected = work / 'in.stackbackup', made / 'expected.json'
        shutil.copy2(made / 'desktop-encrypted.stackbackup', backup)  # into the folder the Flatpak can read
        # Make sure the check starts clean, then save the key from stdin.
        rc, out = flatpak_run('key', 'remove')
        log.append(out)
        if rc == 0:
            rc, out = flatpak_run('key', 'save', '--key-stdin', key=interop.TEST_KEY_HEX)
            log.append(out)
        if rc != 0:
            if 'No key store is available' in out or rc == 124:
                report('SKIP', name, 'no secret store in this session (needs a logged-in desktop session): ' + last_line(out))
            else:
                report('FAIL', name, 'key save: ' + last_line(out))
            return
        saved = True
        rc, out = flatpak_run('key', 'status')  # a separate flatpak run: it survived the first process ending
        log.append(out)
        if rc != 0 or 'saved: yes' not in out or 'fingerprint: ' not in out:
            report('FAIL', name, 'key status after save: ' + last_line(out))
            return
        lib = work / 'lib'
        lib.mkdir()
        rc, out = flatpak_run('archive', lib, 'review', backup, '--saved-key')
        log.append(out)
        if rc != 0 or '"encrypted": true' not in out:
            report('FAIL', name, 'review with the saved key: ' + last_line(out))
            return
        rc, out = flatpak_run('archive', lib, 'restore', backup, '--saved-key')
        log.append(out)
        if rc != 0:
            report('FAIL', name, 'restore with the saved key: ' + last_line(out))
            return
        exported = work / 'exported.stackbackup'
        rc, out = flatpak_run('archive', lib, 'export', exported, '--saved-key')
        log.append(out)
        if rc != 0 or 'Archive published (encrypted' not in out:
            report('FAIL', name, 'export with the saved key: ' + last_line(out))
            return
        c = subprocess.run([*gen, 'check', str(exported), str(expected)], capture_output=True, timeout=600)
        log.append(c.stdout.decode(errors='replace') + c.stderr.decode(errors='replace'))
        if c.returncode != 0:
            report('FAIL', name, 'archive_interop does not accept the export: ' + last_line(log[-1]))
            return
        report('PASS', name, 'saved, persisted across runs, used for review/restore/export, removed')
    except (OSError, RuntimeError, subprocess.SubprocessError) as e:
        report('FAIL', name, f'{type(e).__name__}: {e}')
    finally:
        if saved:
            rc, out = flatpak_run('key', 'remove')
            log.append(out)
            rc2, out2 = flatpak_run('key', 'status')
            log.append(out2)
            if rc != 0 or rc2 != 0 or 'saved: no' not in out2:
                report('FAIL', name + '-cleanup', 'the test key may still be saved in the test Flatpak: ' + last_line(out2))
        shutil.rmtree(work, ignore_errors=True)
        if made is not None:
            shutil.rmtree(made.parent, ignore_errors=True)
        if log:
            save_log(name, '\n'.join(log))


def main():
    global logdir
    large = '--large' in sys.argv[1:]
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    logdir = Path('/tmp') / f'stack-checks-{stamp}'
    logdir.mkdir(mode=0o700)
    started = time.time()
    print('Stack checks - this takes about 2 minutes, please wait.', flush=True)
    print('Full logs: ' + str(logdir) + '\n', flush=True)
    cargo_env = {**os.environ, 'CARGO_HOME': str(Path.home() / '.cache/stack-cargo-home')}
    py = sys.executable

    run_step('desktop-unit-tests', ['cargo', 'test', '--offline', '-p', 'stack-archive', '-p', 'stack-core', '-p', 'stack-cli', '-p', 'stack-desktop'],
             cwd=REPO / 'desktop', env=cargo_env)
    run_step('safety-guards-selftest', [py, '-I', TOOLS / 'test_android_archive_interop.py', '--selftest'])
    run_step('java-vs-rust', [py, '-I', TOOLS / 'test_archive_envelope_host.py'])
    desk = [py, '-I', TOOLS / 'test_android_archive_interop.py', '--desktop-only', '--output', logdir / 'interop-desktop']
    run_step('desktop-interop', desk + (['--large'] if large else []))

    flatpak_key_store(cargo_env)

    adb, why = phone_state()
    if adb is None:
        report('SKIP', 'phone-checks', why)
        print('\nPhone checks skipped: ' + why, flush=True)
    else:
        if installed_older_than_apk(adb):
            print('NOTE: the test build on the phone is older than the built APK. This tool never installs; '
                  'install the new build yourself if you want the new code tested.', flush=True)
        full = [py, '-I', TOOLS / 'test_android_archive_interop.py', '--adb', ADB, '--adb-port', ADB_PORT,
                '--output', logdir / 'interop-phone'] + (['--large'] if large else [])
        run_step('phone-interop', full, timeout=3600 if not large else 7200)
        for name, option in (('phone-link-contract', 'linkContract'), ('phone-archive', 'archiveOnly'), ('phone-features', 'features'), ('phone-link-sync', 'linkSync')):
            phone_suite(adb, name, option)

    counts = {s: sum(1 for r in results if r[0] == s) for s in ('PASS', 'FAIL', 'SKIP')}
    minutes = (time.time() - started) / 60
    print(f"\nSUMMARY: {counts['PASS']} passed, {counts['FAIL']} failed, {counts['SKIP']} skipped ({minutes:.0f} min). "
          + ('ALL GOOD.' if not counts['FAIL'] else 'SOMETHING FAILED: see the FAIL lines above.'), flush=True)
    print('Logs: ' + str(logdir))
    return 1 if counts['FAIL'] else 0


if __name__ == '__main__':
    sys.exit(main())
