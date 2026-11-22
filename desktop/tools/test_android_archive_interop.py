#!/usr/bin/env python3
"""Dev-only archive interop bed: desktop <-> disposable Pixel 4a Checks install.

Not part of the product. Synthetic data and the public synthetic TEST_KEY only.
Never installs, uninstalls, clears, or starts activities. All guards run before
any other adb command. Pure standard library.

  python3 -I desktop/tools/test_android_archive_interop.py --adb /path/adb --adb-port 5037 \
      --output /tmp/interop-run-1 [--step make --step ...] [--dry-run]
  python3 -I desktop/tools/test_android_archive_interop.py --desktop-only --output /tmp/interop-run-2   # no adb at all
  python3 -I desktop/tools/test_android_archive_interop.py --selftest
"""
import argparse
import glob
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import uuid
from collections import namedtuple

ALLOWED_SERIAL = '0A121JEC203530'
DENIED_SERIAL = '5A101JEA313018'
PACKAGE = 'org.stackdocs.app.checks.linkcontract'
INSTRUMENT = PACKAGE + '.test/org.stackdocs.app.ScannerChecks'
TEST_KEY_HEX = bytes(range(1, 33)).hex()  # public synthetic key, never a real one
MARKER = '.stack-interop-scratch'
ALLOWED_ROOTS = [Path('/tmp')]
HEX64 = re.compile(r'[0-9a-fA-F]{64}')
STEPS = ['make', 'desktop-to-phone', 'phone-to-desktop', 'loop']
REPLICA = re.compile(r'interop-[a-z0-9-]+')
PHONE_PATH = re.compile(r'files/checks/interop-[a-z0-9-]+/files/(inbox\.stackbackup|outbox\.stackbackup|outbox-plain\.stackbackup)')
ADB_VERBS = {'get-serialno', 'shell', 'exec-out'}
FORBIDDEN_SHELL = re.compile(r'(^|\s)(install|uninstall|install-multiple|pm\s+clear|am\s+start\S*|monkey)(\s|$)')

Result = namedtuple('Result', 'rc out err')


class Refused(Exception):
    pass


def real_runner(argv, input=None, env=None, cwd=None, timeout=300):
    p = subprocess.run(argv, input=input, env=env, cwd=cwd, capture_output=True, timeout=timeout)
    return Result(p.returncode, p.stdout, p.stderr)


# ---------------------------------------------------------------- guards

def check_config(serial, env, argv_values, package, output, adb_port, home=None):
    """Pure checks, run before anything touches adb or the disk."""
    if serial != ALLOWED_SERIAL:
        raise Refused('serial is not the allowed disposable device')
    if env.get('ANDROID_SERIAL', ALLOWED_SERIAL) != ALLOWED_SERIAL:
        raise Refused('ANDROID_SERIAL is set to a different device')
    if any(DENIED_SERIAL in str(v) for v in list(argv_values) + list(env.values())):
        raise Refused('the denied device serial appears in the arguments')
    if not isinstance(adb_port, int) or not 1 <= adb_port <= 65535:
        raise Refused('an explicit --adb-port is required')
    if package != PACKAGE:
        raise Refused('package must be exactly ' + PACKAGE)
    check_output_dir(output, home)


def check_desktop_config(env, argv_values, output, home=None):
    """--desktop-only: no device at all; the denied serial is still refused anywhere it appears."""
    if any(DENIED_SERIAL in str(v) for v in list(argv_values) + list(env.values())):
        raise Refused('the denied device serial appears in the arguments')
    check_output_dir(output, home)


def check_output_dir(output, home=None):
    out = Path(os.path.abspath(output))
    real = Path(os.path.realpath(out.parent)) / out.name
    home = Path(home or os.path.expanduser('~')).resolve()
    if real.is_relative_to(home / '.local' / 'share'):
        raise Refused('output must not be under ~/.local/share')
    if not any(real != root and real.is_relative_to(root) for root in ALLOWED_ROOTS):
        raise Refused('output must be a fresh directory under /tmp')
    if os.path.lexists(real):
        raise Refused('output directory already exists; choose a fresh one')
    if not real.parent.is_dir():
        raise Refused('output parent directory must exist')
    return real


def make_scratch(output):
    output = Path(output)
    output.mkdir(mode=0o700)
    (output / MARKER).write_text('stack interop scratch\n')
    work = output / 'work'
    for name in ['home', 'data', 'config', 'cache', 'libs']:
        (work / name).mkdir(parents=True, mode=0o700)
    return work


def remove_scratch(output):
    """Delete the work dirs, only when the marker is present and the path is under /tmp."""
    output = Path(os.path.realpath(output))
    if not (output / MARKER).exists() or not output.is_relative_to('/tmp'):
        raise Refused('refusing to delete a directory without the scratch marker')
    shutil.rmtree(output / 'work', ignore_errors=True)


def guard_cli_argv(argv):
    for item in map(str, argv):
        low = item.lower()
        if low.startswith(('--saved-key', '--key-file', '--passphrase')):
            raise Refused('key-bearing option refused: ' + item.split('=')[0])
        if HEX64.search(item):
            raise Refused('a 64-hex string in argv is refused')
        if 'keyvault' in low:
            raise Refused('keyvault is never run by this harness')


def scan_output(text, known=()):
    """Fail on the test key or any 64-hex string that is not a known data hash."""
    low = text.lower()
    if TEST_KEY_HEX in low:
        raise Refused('key material appeared in captured output')
    allowed = {h.lower() for h in known} - {TEST_KEY_HEX}
    for match in HEX64.finditer(low):
        if match.group(0) not in allowed:
            raise Refused('unexpected 64-hex string in captured output')


def forbidden_shell(parts):
    text = ' '.join(parts)
    if FORBIDDEN_SHELL.search(text):
        raise Refused('forbidden adb shell command')
    tokens = list(parts)
    if tokens and tokens[0] == 'am' and (len(tokens) < 2 or tokens[1] != 'instrument'):
        raise Refused('only am instrument is allowed')


# ---------------------------------------------------------------- adb

class Adb:
    def __init__(self, runner, adb, port, serial=ALLOWED_SERIAL, package=PACKAGE):
        if serial != ALLOWED_SERIAL or package != PACKAGE:
            raise Refused('wrong serial or package')
        if not isinstance(port, int):
            raise Refused('an explicit --adb-port is required')
        self.runner, self.package = runner, package
        self.base = [str(adb), '-P', str(port), '-s', serial]

    def _run(self, tail, data=None, timeout=180):
        if not tail or tail[0] not in ADB_VERBS:
            raise Refused('adb verb not allowed: ' + str(tail[:1]))
        argv = self.base + list(tail)
        if any(DENIED_SERIAL in a for a in argv) or argv[3:5] != ['-s', ALLOWED_SERIAL]:
            raise Refused('device selection guard failed')
        return self.runner(argv, input=data, env=None, cwd=None, timeout=timeout)

    def shell(self, *parts, data=None, check=True):
        forbidden_shell(parts)
        r = self._run(['shell', '-T', shlex.join(parts)], data, )
        if check and r.rc != 0:
            raise RuntimeError('adb shell failed: ' + ' '.join(parts[:3]))
        return r

    def read(self, relative):
        if not PHONE_PATH.fullmatch(relative):
            raise Refused('phone path outside the interop replica files')
        r = self._run(['exec-out', 'run-as', self.package, 'cat', relative], timeout=120)
        if r.rc != 0:
            raise RuntimeError('could not read ' + relative)
        return r.out

    def put(self, relative, contents):
        if not PHONE_PATH.fullmatch(relative):
            raise Refused('phone path outside the interop replica files')
        self.shell('run-as', self.package, 'sh', '-c', f'cat > {shlex.quote(relative)}', data=contents)
        if self.read(relative) != contents:
            raise RuntimeError('read-back mismatch for ' + relative)

    def mkdir(self, replica):
        if not REPLICA.fullmatch(replica):
            raise Refused('bad replica name')
        self.shell('run-as', self.package, 'mkdir', '-p', f'files/checks/{replica}/files')

    def preflight(self):
        def text(*parts):
            return self.shell(*parts).out.decode(errors='replace').strip()
        if self._run(['get-serialno']).out.decode().strip() != ALLOWED_SERIAL:
            raise Refused('get-serialno does not match the allowed device')
        if text('getprop', 'ro.product.device') != 'sunfish':
            raise Refused('device is not sunfish')
        if text('getprop', 'ro.product.model') != 'Pixel 4a':
            raise Refused('model is not Pixel 4a')
        listing = text('pm', 'list', 'instrumentation')
        if f'instrumentation:{INSTRUMENT} (target={PACKAGE})' not in listing:
            raise Refused('the checks instrumentation is not installed')
        for line in text('ps', '-A', '-o', 'ARGS').splitlines():
            if re.search(r'\binstrument\b', line) and 'ps -A' not in line:
                raise Refused('another instrumentation is running')

    def phase(self, phase, replica):
        if phase not in ('read', 'write', 'cleanup') or not REPLICA.fullmatch(replica):
            raise Refused('bad phase or replica')
        cmd = ['am', 'instrument', '-w', '-r', '-e', 'interopPhase', phase, '-e', 'replicaName', replica,
               '-e', 'disposableDevice', ALLOWED_SERIAL, INSTRUMENT]
        text = self.shell(*cmd, check=False).out.decode(errors='replace')
        if TEST_KEY_HEX in text.lower():
            raise Refused('key material in instrumentation output')
        if 'FAIL' in text or 'INSTRUMENTATION_CODE: -1' not in text:
            raise RuntimeError(f'phone {phase} failed: ' + text[-400:])
        match = re.search(r'PASS \d+: interop-' + phase + r' (\{.*\})', text)
        if not match:
            raise RuntimeError(f'phone {phase} printed no result')
        return json.loads(match.group(1))

    def leftover(self):
        r = self.shell('run-as', self.package, 'ls', 'files/checks', check=False)
        if r.rc != 0:
            return []  # directory absent
        return [n for n in r.out.decode().split() if n.startswith('interop-')]


# ---------------------------------------------------------------- desktop

class Desktop:
    def __init__(self, runner, repo, work, gen_dir, known, pdfium=None):
        self.runner, self.repo, self.work, self.known = runner, Path(repo), Path(work), known
        real_home = Path(os.path.expanduser('~'))
        self.cargo_home = str(real_home / '.cache' / 'stack-cargo-home')
        self.rustup = real_home / '.rustup'
        self.gen_dir = gen_dir
        self.pdfium = pdfium
        self.cargo = [shutil.which('cargo') or 'cargo', 'build', '--offline', '-p', 'stack-cli']
        self.generator_bin = str(self.repo / 'desktop' / 'target' / 'debug' / 'examples' / 'archive_interop')
        self.cli_bin = str(self.repo / 'desktop' / 'target' / 'debug' / 'stack-cli')

    def env(self, extra=None):
        env = {'PATH': os.environ.get('PATH', '/usr/bin:/bin'), 'LANG': 'C.UTF-8',
               'HOME': str(self.work / 'home'), 'XDG_DATA_HOME': str(self.work / 'data'),
               'XDG_CONFIG_HOME': str(self.work / 'config'), 'XDG_CACHE_HOME': str(self.work / 'cache'),
               'DBUS_SESSION_BUS_ADDRESS': 'unix:path=/nonexistent', 'CARGO_HOME': self.cargo_home}
        if self.rustup.is_dir():
            env['RUSTUP_HOME'] = str(self.rustup)
        env.update(extra or {})
        return env

    def run(self, argv, key=None, extra_env=None, expect_fail=False, hash_keys=()):
        """One subprocess. `key` is the stdin text (the public test key, or a deliberately wrong one); never an argument.
        Returns the combined output; with expect_fail the command must exit non-zero."""
        guard_cli_argv(argv)
        data = (key + '\n').encode() if key else None
        r = self.runner(list(argv), input=data, env=self.env(extra_env), cwd=str(self.repo / 'desktop'), timeout=900)
        out, err = r.out.decode(errors='replace'), r.err.decode(errors='replace')
        known = set(self.known)
        if hash_keys:  # data hashes the command itself reports (the restore receipt's source_hash), never key material
            try:
                known |= {str(v).lower() for k, v in json.loads(out).items() if k in hash_keys}
            except (ValueError, AttributeError):
                pass
        scan_output(out, known)
        scan_output(err, known)
        if expect_fail:
            if r.rc == 0:
                raise RuntimeError('expected a failure but the command succeeded: ' + ' '.join(map(str, argv[:4])))
        elif r.rc != 0:
            raise RuntimeError(f'desktop command failed ({" ".join(map(str, argv[:5]))}): ' + (out + err)[-400:])
        return out + err if expect_fail else out

    def build(self):
        """Both binaries are built before anything runs; the run itself uses them directly."""
        self.run([*self.cargo, '--example', 'archive_interop'])
        self.run(list(self.cargo))

    def generator(self, *args):
        """archive_interop make|check|reexport. It finds the envelope itself and uses the built-in test key."""
        extra = {'STACK_PDFIUM_LIBRARY': str(self.pdfium)} if self.pdfium else None
        return self.run([self.generator_bin, *map(str, args)], extra_env=extra)

    def cli(self, *args, key=None, pdfium=True, expect_fail=False, hash_keys=()):
        """stack-cli; `key` (stdin text) adds --key-stdin. PDFium is set only for this one subprocess."""
        flag = ['--key-stdin'] if key else []
        extra = {'STACK_PDFIUM_LIBRARY': str(self.pdfium)} if (pdfium and self.pdfium) else None
        return self.run([self.cli_bin, *map(str, args), *flag], key=key, extra_env=extra, expect_fail=expect_fail, hash_keys=hash_keys)


def find_pdfium(repo):
    found = sorted(glob.glob(str(Path(repo) / '.artifacts/*/stage/runtime/pdfium/lib/libpdfium.so')))
    if not found:
        raise RuntimeError('no staged libpdfium.so under .artifacts')
    return found[-1]


# ---------------------------------------------------------------- comparisons

def file_sha(path):
    """SHA-256 of a data file. The restore receipt prints it as source_hash, so it is a known data hash, not a key."""
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def collect_hashes(*objs):
    found = set()
    def walk(o):
        if isinstance(o, dict):
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
        elif isinstance(o, str):
            found.update(m.group(0).lower() for m in HEX64.finditer(o))
    for o in objs:
        walk(o)
    found.discard(TEST_KEY_HEX)
    return found


def phone_read_want(expected, name):
    """What the phone's interop-read json must say about a desktop-made file, from expected.json."""
    encrypted = name == 'desktop-encrypted.stackbackup'
    links = expected['links']
    negative = {'truncated_restored': 0, 'truncated_refused': True}
    if encrypted:
        negative.update(wrong_key='NoKey', truncated='Damaged')
    return {'encrypted': encrypted, 'documents': expected['documents']['count'],
            'document_titles': expected['documents']['titles'], 'links_added': len(links), 'links_skipped': 0,
            'reimport_links_skipped': len(links),
            'links': [{k: l[k] for k in ('owner', 'link_id', 'title', 'has_preview', 'preview_sha256')} for l in links],
            'negative': negative}


def compare_read(got, want, label):
    """Compare a phone `read` json with an expectation; only keys present in `want` are checked."""
    problems = []
    for key in ('encrypted', 'documents', 'links_added', 'links_skipped', 'reimport_links_skipped'):
        if key in want and got.get(key) != want[key]:
            problems.append(f'{key}: {got.get(key)!r} != {want[key]!r}')
    if 'document_titles' in want and sorted(got.get('document_titles', [])) != sorted(want['document_titles']):
        problems.append('document_titles differ')
    if 'links' in want:
        have = {l['link_id']: l for l in got.get('links', [])}
        if set(have) != {l['link_id'] for l in want['links']} or len(have) != len(got.get('links', [])):
            problems.append('link ids differ')
        for l in want['links']:
            g = have.get(l['link_id'], {})
            for key in ('title', 'has_preview', 'preview_sha256', 'owner'):
                if key in l and g.get(key) != l[key]:
                    problems.append(f'link {key} differs')
    seen = got.get('negative') or {}
    for key, value in (want.get('negative') or {}).items():
        if key == 'truncated_refused':
            if bool(seen.get('truncated')) != value:
                problems.append('negative check truncated_refused differs')
        elif seen.get(key) != value:
            problems.append(f'negative check {key} differs')
    if problems:
        raise AssertionError(f'{label}: ' + '; '.join(sorted(set(problems))))


# ---------------------------------------------------------------- flow

ARCHIVES = ['desktop-plain-v4.stackbackup', 'desktop-plain.stackbackup', 'desktop-encrypted.stackbackup']
DESKTOP_STEPS = ['build', 'make', 'reexport', 'cli', 'negative']


def flip_key_char(key):
    """The public test key with its first character changed (still 64 valid hex digits)."""
    return ('1' if key[0] != '1' else '2') + key[1:]


class Harness:
    def __init__(self, adb, repo, output, steps, runner=real_runner):
        """`adb` is None for --desktop-only: then no adb command can ever be built."""
        self.adb, self.repo, self.output, self.steps, self.runner = adb, Path(repo), Path(output), steps, runner
        self.replicas, self.report, self.known = [], {'steps': {}}, set()
        self.work = None
        self.state = {}

    def new_replica(self):
        name = 'interop-' + uuid.uuid4().hex[:12]
        self.replicas.append(name)  # recorded first so cleanup always covers it
        self.adb.mkdir(name)
        return name

    def gen_dir(self):
        return self.work / 'gen'

    def run(self):
        ok = True
        try:
            if self.adb is not None:
                self.adb.preflight()
            self.work = make_scratch(self.output)
            self.desktop = Desktop(self.runner, self.repo, self.work, self.gen_dir(), self.known, find_pdfium(self.repo))
            order = DESKTOP_STEPS if self.adb is None else ['build'] + STEPS
            for step in order:
                if step in self.steps or step == 'build':
                    try:
                        getattr(self, 'step_' + step.replace('-', '_'))()
                        self.report['steps'][step] = 'passed'
                    except Exception as e:  # noqa: BLE001 - report and stop
                        self.report['steps'][step] = 'FAILED: ' + str(e)[:300]
                        ok = False
                        break
        except Exception as e:  # noqa: BLE001
            self.report['error'] = str(e)[:300]
            ok = False
        finally:
            ok = self.finish() and ok
        return ok

    def finish(self):
        clean = True
        if self.adb is not None:
            for name in self.replicas:
                try:
                    self.adb.phase('cleanup', name)
                except Exception as e:  # noqa: BLE001
                    clean = False
                    self.report.setdefault('cleanup_errors', []).append(str(e)[:200])
            try:
                left = self.adb.leftover() if self.replicas else []
                self.report['leftover_replicas'] = len(left)
                clean = clean and not left
            except Exception as e:  # noqa: BLE001
                clean = False
                self.report.setdefault('cleanup_errors', []).append(str(e)[:200])
        try:
            if self.work is not None:
                remove_scratch(self.output)
                clean = clean and not self.work.exists()
            if self.output.exists():
                text = json.dumps(self.report, indent=2) + '\n'
                scan_output(text)
                (self.output / 'report.json').write_text(text)
        except Exception as e:  # noqa: BLE001
            clean = False
            self.report.setdefault('cleanup_errors', []).append(str(e)[:200])
        self.report['cleanup_ok'] = clean
        return clean

    def require(self, key, step):
        if key not in self.state:
            raise RuntimeError(f'step {step} needs earlier step output ({key}); run the earlier step in the same invocation')

    def step_build(self):
        self.desktop.build()

    def check_all(self, gen, expected_path):
        for n in ARCHIVES:
            out = json.loads(self.desktop.generator('check', gen / n, expected_path))
            if not out.get('ok'):
                raise AssertionError(f'{n}: check reported mismatches {out.get("mismatches")}')

    # (a)
    def step_make(self):
        gen = self.gen_dir()
        self.desktop.generator('make', gen)
        for n in ARCHIVES + ['expected.json']:
            if not (gen / n).is_file():
                raise RuntimeError('generator did not produce ' + n)
        expected = json.loads((gen / 'expected.json').read_text())
        self.known |= collect_hashes(expected)
        self.known |= {file_sha(gen / n) for n in ARCHIVES}
        self.state['expected'] = expected
        self.check_all(gen, gen / 'expected.json')

    # desktop-only: reexport then check the re-exported file against the same expected.json
    def step_reexport(self):
        self.require('expected', 'reexport')
        gen = self.gen_dir()
        out = gen / 'desktop-reexport.stackbackup'
        self.desktop.generator('reexport', gen / 'desktop-encrypted.stackbackup', out)
        self.known.add(file_sha(out))
        result = json.loads(self.desktop.generator('check', out, gen / 'expected.json'))
        if not result.get('ok'):
            raise AssertionError('reexported file differs: ' + str(result.get('mismatches')))
        self.report['reexport'] = {'documents': result['documents'], 'links': result['links']}

    # desktop-only: the real stack-cli, a scratch library per command, key on stdin only
    def step_cli(self):
        self.require('expected', 'cli')
        gen, expected = self.gen_dir(), self.state['expected']
        enc = gen / 'desktop-encrypted.stackbackup'
        want_docs, want_links = expected['documents']['count'], len(expected['links'])
        review = self.cli_json('review', enc, key=TEST_KEY_HEX)
        if review.get('encrypted') is not True or review.get('documents') != want_docs or review.get('links_imported') != want_links:
            raise AssertionError('review of the encrypted backup differs: ' + json.dumps(review)[:300])
        lib = self.new_lib('restore')
        restored = json.loads(self.desktop.cli('archive', lib, 'restore', enc, key=TEST_KEY_HEX, hash_keys=('source_hash',)))
        if restored.get('documents_added') != want_docs or restored.get('links_added') != want_links or restored.get('encrypted') is not True:
            raise AssertionError('restore of the encrypted backup differs: ' + json.dumps(restored)[:300])
        status = json.loads(self.desktop.cli('library', lib, 'status', pdfium=False))
        if status.get('documents') != want_docs or status.get('links') != want_links:
            raise AssertionError('restored library status differs: ' + json.dumps(status)[:300])
        self.report['cli'] = {'review': {k: review.get(k) for k in ('format', 'encrypted', 'documents', 'links_imported', 'links_skipped')},
                              'restore': {k: restored.get(k) for k in ('documents_added', 'links_added', 'links_skipped', 'encrypted')},
                              'status': {k: status.get(k) for k in ('documents', 'pages', 'links')}}

    def new_lib(self, label):
        self.state['libs'] = self.state.get('libs', 0) + 1
        lib = self.work / 'libs' / f'{self.state["libs"]:02d}-{label}'
        lib.mkdir(mode=0o700)
        return lib

    def cli_json(self, action, path, key=None):
        return json.loads(self.desktop.cli('archive', self.new_lib(action), action, path, key=key))

    def step_negative(self):
        self.require('expected', 'negative')
        gen = self.gen_dir()
        enc, plain = gen / 'desktop-encrypted.stackbackup', gen / 'desktop-plain.stackbackup'
        found = {}
        out = self.desktop.cli('archive', self.new_lib('wrongkey'), 'review', enc, key=flip_key_char(TEST_KEY_HEX), expect_fail=True)
        # stack-cli prints the human message (Display); only the friendly sentence is accepted.
        if 'Wrong passphrase or recovery key, or damaged.' not in out or 'WrongKey' in out:
            raise AssertionError('a flipped key character did not give the wrong-key message: ' + out[-200:])
        found['wrong_key'] = out.strip().splitlines()[-1][:120]
        out = self.desktop.cli('archive', self.new_lib('nokey'), 'review', enc, expect_fail=True)
        if 'No key for this backup' not in out:
            raise AssertionError('no key did not give the no-key message: ' + out[-200:])
        found['no_key'] = out.strip().splitlines()[-1][:120]
        review = json.loads(self.desktop.cli('archive', self.new_lib('plainreview'), 'review', plain))
        if review.get('encrypted') is not False or not any('Not encrypted' in w for w in review.get('warnings', [])):
            raise AssertionError('review of a plain backup does not say it is not encrypted: ' + json.dumps(review)[:300])
        found['plain'] = 'not encrypted'
        self.report['negative'] = found

    # (b)
    def step_desktop_to_phone(self):
        self.require('expected', 'desktop-to-phone')
        gen, expected = self.gen_dir(), self.state['expected']
        for name in ['desktop-encrypted.stackbackup', 'desktop-plain.stackbackup']:
            replica = self.new_replica()
            contents = (gen / name).read_bytes()
            self.adb.put(f'files/checks/{replica}/files/inbox.stackbackup', contents)
            got = self.adb.phase('read', replica)
            self.known |= collect_hashes(got)
            compare_read(got, phone_read_want(expected, name), name)
            self.report.setdefault('desktop_to_phone', []).append(name)

    # (c)
    def step_phone_to_desktop(self):
        gen = self.gen_dir()
        replica = self.new_replica()
        wrote = self.adb.phase('write', replica)
        self.known |= collect_hashes(wrote)
        base = f'files/checks/{replica}/files/'
        files = {}
        for key, name in (('encrypted_length', 'outbox.stackbackup'), ('plain_length', 'outbox-plain.stackbackup')):
            data = self.adb.read(base + name)
            if len(data) != wrote[key]:
                raise AssertionError(f'{name} length {len(data)} != phone length {wrote[key]}')
            path = gen / ('phone-' + name)
            path.write_bytes(data)
            files[name] = path
            self.known.add(file_sha(path))
        want = {'documents': wrote['document_titles'], 'links': wrote['links']}
        phone_json = gen / 'phone.json'
        phone_json.write_text(json.dumps(want))
        for n, (name, path) in enumerate(files.items()):
            enc = name == 'outbox.stackbackup'
            result = json.loads(self.desktop.generator('check', path, '--phone-json', phone_json))
            if not result.get('ok'):
                raise AssertionError(f'{name}: {result.get("mismatches")}')
            lib = self.new_lib(f'phone-restore-{n}')
            self.desktop.cli('archive', lib, 'restore', path, key=TEST_KEY_HEX if enc else None, hash_keys=('source_hash',))  # separate process per PDFium step
        self.state.update(phone_files=files, phone_want=wrote)

    # (d)
    def step_loop(self):
        self.require('phone_files', 'loop')
        gen = self.gen_dir()
        out = gen / 'loop-reexport.stackbackup'
        self.desktop.generator('reexport', self.state['phone_files']['outbox.stackbackup'], out)
        self.known.add(file_sha(out))
        fresh = self.new_replica()
        self.adb.put(f'files/checks/{fresh}/files/inbox.stackbackup', out.read_bytes())
        got = self.adb.phase('read', fresh)
        self.known |= collect_hashes(got)
        wrote = self.state['phone_want']
        compare_read(got, {'documents': wrote['documents'], 'document_titles': wrote['document_titles'], 'links': [
            {k: l[k] for k in ('link_id', 'owner', 'title', 'has_preview', 'preview_sha256')} for l in wrote['links']]},
            'loop fresh')
        # The phone's read phase requires an empty library, so re-reading into the original
        # (non-empty) replica cannot be driven through it. Recorded, not faked.
        self.report['loop_original_replica'] = 'not run: interop read phase requires a fresh library'


# ---------------------------------------------------------------- cli

def parse_args(argv):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--adb', type=Path)
    p.add_argument('--adb-port', type=int)
    p.add_argument('--serial', default=ALLOWED_SERIAL)
    p.add_argument('--package', default=PACKAGE)
    p.add_argument('--output', type=Path)
    p.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument('--step', action='append', choices=STEPS + ['all'])
    p.add_argument('--dry-run', action='store_true')
    p.add_argument('--selftest', action='store_true')
    p.add_argument('--desktop-only', action='store_true', help='no phone: build, make, check, reexport, stack-cli review/restore and negatives')
    a = p.parse_args(argv)
    if not a.selftest:
        required = ('output',) if a.desktop_only else ('adb', 'adb_port', 'output')
        for name in required:
            if getattr(a, name) is None:
                p.error('--' + name.replace('_', '-') + ' is required')
        if a.desktop_only and (a.adb is not None or a.adb_port is not None or a.step):
            p.error('--desktop-only takes no --adb, --adb-port or --step')
    if a.desktop_only:
        a.steps = DESKTOP_STEPS
    else:
        a.steps = STEPS if not a.step or 'all' in a.step else a.step
    return a


def plan(a):
    env = '[env HOME,XDG_*_HOME=scratch DBUS_SESSION_BUS_ADDRESS=unix:path=/nonexistent CARGO_HOME=~/.cache/stack-cargo-home]'
    gen_bin, cli_bin = 'desktop/target/debug/examples/archive_interop', 'desktop/target/debug/stack-cli'
    pdf = '[STACK_PDFIUM_LIBRARY=.artifacts/*/stage/runtime/pdfium/lib/libpdfium.so, this subprocess only]'
    lines = []
    if not a.desktop_only:
        adb = [str(a.adb), '-P', str(a.adb_port), '-s', ALLOWED_SERIAL]
        lines += ['adb: ' + shlex.join(adb + ['get-serialno']),
                  'adb: shell getprop ro.product.device / ro.product.model; pm list instrumentation; ps -A -o ARGS']
    r = 'interop-<random>'
    lines.append(f'mkdir fresh {a.output} (+ {MARKER}); work dirs home data config cache libs; gen is created by make')
    lines += [f'{env} cargo build --offline -p stack-cli --example archive_interop; cargo build --offline -p stack-cli']
    if 'make' in a.steps:
        lines += [f'{env} {pdf} {gen_bin} make <gen>',
                  f'{env} {pdf} {gen_bin} check <gen>/FILE <gen>/expected.json  (3 files; no key argument)']
    if a.desktop_only:
        lines += [f'{env} {pdf} {gen_bin} reexport <gen>/desktop-encrypted.stackbackup <gen>/desktop-reexport.stackbackup; check it',
                  f'{env} {pdf} {cli_bin} archive <lib> review|restore <gen>/desktop-encrypted.stackbackup --key-stdin (test key on stdin)',
                  f'{env} {pdf} {cli_bin} archive <lib> review: flipped key (stdin) -> wrong key; no key -> No key for this backup; plain file -> not encrypted',
                  'no adb command of any kind']
    if 'desktop-to-phone' in a.steps:
        lines += [f'adb run-as mkdir -p files/checks/{r}/files; push inbox.stackbackup via run-as cat; read back; compare',
                  f'adb shell am instrument -w -r -e interopPhase read -e replicaName {r} -e disposableDevice {ALLOWED_SERIAL} {INSTRUMENT}',
                  '(for desktop-encrypted and desktop-plain; compare PASS json with expected.json)']
    if 'phone-to-desktop' in a.steps:
        lines += [f'adb am instrument ... -e interopPhase write -e replicaName {r}',
                  'adb exec-out run-as cat outbox.stackbackup, outbox-plain.stackbackup',
                  f'{env} {pdf} {gen_bin} check <phone file> --phone-json <gen>/phone.json',
                  f'{env} {pdf} {cli_bin} archive <lib> restore <file> [--key-stdin]']
    if 'loop' in a.steps:
        lines += [f'{env} {pdf} {gen_bin} reexport <phone encrypted> <out>',
                  f'adb push + am instrument read into a fresh {r}; compare with write json']
    if not a.desktop_only:
        lines += ['finally: am instrument cleanup for every replica; delete work dirs; run-as ls files/checks must list no interop-*']
    lines += [f'report: {a.output}/report.json (no keys)']
    return lines


def selftest():
    import importlib
    import unittest
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    module = importlib.import_module('test_android_archive_interop_test')
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(module.GuardTests)
    return unittest.TextTestRunner(verbosity=1).run(suite).wasSuccessful()


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    a = parse_args(argv)
    if a.selftest:
        return 0 if selftest() else 1
    try:
        if a.desktop_only:
            check_desktop_config(os.environ, argv, a.output)
        else:
            check_config(a.serial, os.environ, argv, a.package, a.output, a.adb_port)
            if not a.adb.is_file() or not os.access(a.adb, os.X_OK):
                raise Refused('--adb must be an executable file')
    except Refused as e:
        print('REFUSED:', e, file=sys.stderr)
        return 2
    if a.dry_run:
        print('\n'.join(plan(a)))
        return 0
    adb = None if a.desktop_only else Adb(real_runner, a.adb.resolve(), a.adb_port)
    h = Harness(adb, a.repo, a.output.resolve(), a.steps)
    try:
        ok = h.run()
    except Refused as e:
        print('REFUSED:', e, file=sys.stderr)
        return 2
    print(json.dumps(h.report, indent=2))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
