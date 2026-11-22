"""Guard and flow tests for test_android_archive_interop.py using fake adb/commands only.

Run: python3 -I -m unittest discover -s desktop/tools -p 'test_android_archive_interop_test.py'
No real adb, device or cargo is ever run.
"""
import atexit
import json
import os
from pathlib import Path
import shlex
import shutil
import tempfile
import unittest

import test_android_archive_interop as h

A = h.ALLOWED_SERIAL
FIXED_HEX = 'ab' * 32


_MADE = []


@atexit.register
def _remove_test_dirs():
    for base in _MADE:
        shutil.rmtree(base, ignore_errors=True)


def fresh_output():
    base = tempfile.mkdtemp(prefix='interop-test-', dir='/tmp')
    _MADE.append(base)
    return Path(base) / 'out'


class FakeWorld:
    """In-memory adb + desktop. Records every argv."""
    def __init__(self, fail_read=False, device='sunfish', model='Pixel 4a', serial=A, instr=True, running=False, free_kb=9_000_000):
        self.calls, self.files, self.dirs = [], {}, set()
        self.fail_read, self.device, self.model, self.serial = fail_read, device, model, serial
        self.instr, self.running = instr, running
        self.cleaned, self.desktop_envs, self.desktop_stdin = [], [], []
        self.write_json = None
        self.free_kb, self.am_options, self.last_restore_links = free_kb, [], 1

    def __call__(self, argv, input=None, env=None, cwd=None, timeout=0):
        self.calls.append(list(argv))
        if argv[0] == 'ADB':
            return self.adb(argv, input)
        return self.desktop(argv, input, env)

    def ok(self, text='', rc=0):
        return h.Result(rc, text.encode() if isinstance(text, str) else text, b'')

    def adb(self, argv, data):
        assert argv[1:5] == ['-P', '5037', '-s', A], argv
        rest = argv[5:]
        if rest[0] == 'get-serialno':
            return self.ok(self.serial + '\n')
        if rest[0] == 'exec-out':
            return self.ok(self.files[rest[-1]]) if rest[-1] in self.files else self.ok('', 1)
        cmd = shlex.split(rest[2])
        if cmd[0] == 'df':
            return self.ok(f'Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-5 100000000 1 {self.free_kb} 5% /data\n')
        if cmd[0] == 'getprop':
            return self.ok(self.device if cmd[1].endswith('device') else self.model)
        if cmd[:3] == ['pm', 'list', 'instrumentation']:
            return self.ok(f'instrumentation:{h.INSTRUMENT} (target={h.PACKAGE})\n' if self.instr else '')
        if cmd[0] == 'ps':
            return self.ok('system_server\nam instrument -w x\n' if self.running else 'init\n')
        if cmd[0] == 'am':
            e = dict(zip(cmd[5::3], cmd[6::3]))
            phase, name = e['interopPhase'], e['replicaName']
            if phase == 'cleanup':
                self.cleaned.append(name)
                self.dirs.discard(name)
                return self.ok('INSTRUMENTATION_CODE: -1\nPASS 1: interop-cleanup {"removed":true}\n')
            if phase == 'read' and self.fail_read:
                return self.ok('FAIL after 1\n')
            self.am_options.append({k: v for k, v in e.items() if k.startswith('interop') and k != 'interopPhase'})
            if phase == 'read':
                body = self.brief_json() if e.get('interopBrief') else self.read_json(name)
                return self.ok('PASS 1: interop-read ' + json.dumps(body) + '\nINSTRUMENTATION_CODE: -1\n')
            self.files[f'files/checks/{name}/files/outbox.stackbackup'] = b'E' * 10
            self.files[f'files/checks/{name}/files/outbox-plain.stackbackup'] = b'P' * 7
            if e.get('interopPassphrase'):
                self.files[f'files/checks/{name}/files/outbox-passphrase.stackbackup'] = b'Q' * 12
            self.write_json = {'documents': 2, 'document_titles': ['a', 'b'], 'encrypted_length': 10, 'plain_length': 7,
                               **({'passphrase_length': 12} if e.get('interopPassphrase') else {}),
                               'links': [{'link_id': 'L1', 'owner': 'o', 'title': 't', 'hash': '1' * 64, 'text': 'x',
                                          'has_preview': True, 'preview_sha256': FIXED_HEX}]}
            return self.ok('PASS 1: interop-write ' + json.dumps(self.write_json) + '\nINSTRUMENTATION_CODE: -1\n')
        if cmd[0] == 'run-as':
            if cmd[2] == 'mkdir':
                self.dirs.add(cmd[-1].split('/')[2])
                return self.ok()
            if cmd[2] == 'ls':
                return self.ok('\n'.join(sorted(self.dirs)))
            if cmd[2] == 'sh':
                self.files[cmd[-1][len('cat > '):].strip("'")] = data
                return self.ok()
        raise AssertionError(argv)

    def read_json(self, name):
        if self.files.get(f'files/checks/{name}/files/inbox.stackbackup') == b'R' and self.write_json:
            w = self.write_json
            return {'documents': w['documents'], 'document_titles': w['document_titles'], 'links': [
                {k: l[k] for k in ('link_id', 'owner', 'title', 'has_preview', 'preview_sha256')} for l in w['links']]}
        content = self.files.get(f'files/checks/{name}/files/inbox.stackbackup')
        enc = content in (b'ENC', b'ENCNL')
        negative = {'truncated': 'Damaged' if enc else 'EOFException', 'truncated_restored': 0}
        if enc:
            negative['wrong_key'] = 'NoKey'
        if content in (b'NL', b'ENCNL'):  # a library with zero links
            return {'encrypted': enc, 'documents': 1, 'document_titles': ['d'], 'links_added': 0, 'links_skipped': 0,
                    'reimport_links_skipped': 0, 'links': [], 'negative': negative}
        return {'encrypted': enc, 'documents': 1, 'document_titles': ['d'], 'links_added': 1, 'links_skipped': 0,
                'reimport_links_skipped': 1,
                'links': [{'link_id': 'L1', 'owner': 'o', 'title': 't', 'has_preview': True, 'preview_sha256': FIXED_HEX}],
                'negative': negative}

    def brief_json(self):
        return {'encrypted': True, 'documents': 1, 'document_titles': ['d'], 'links_added': 1, 'links_skipped': 0,
                'reimport_links_skipped': 1, 'brief': True, 'read_ms': 1234, 'link_titles_digest': h.titles_digest(['t']),
                'previews': [{'link_id': 'BIG', 'preview_sha256': FIXED_HEX, 'bytes': 100}]}

    def desktop(self, argv, data, env):
        self.desktop_envs.append(env)
        self.desktop_stdin.append(data)
        if argv[0] == h.TIME_BIN:  # /usr/bin/time -v: run the inner command, add a time report to stderr
            assert argv[1] == '-v'
            inner = self.desktop(argv[2:], data, env)
            report = ('\tCommand being timed: "x"\n\tElapsed (wall clock) time (h:mm:ss or m:ss): 1:02.50\n'
                      '\tMaximum resident set size (kbytes): 204800\n').encode()
            return h.Result(inner.rc, inner.out, inner.err + (b'\n' if inner.err else b'') + report)
        cmd = argv[1] if len(argv) > 1 else ''
        if argv[0].endswith('archive_interop'):
            if cmd == 'make-large':
                large = Path(argv[2])
                large.mkdir()
                (large / 'large-lib').mkdir()
                for n in ['refuse-links-1001.stackbackup', 'refuse-preview-over.stackbackup']:
                    (large / n).write_bytes(b'x')
                (large / 'expected-large.json').write_text(json.dumps({
                    'documents': {'count': 1, 'titles': ['d']}, 'preview_pages': 2,
                    'links': [{'owner': 'o', 'link_id': 'BIG', 'title': 't', 'text_marker': 'm', 'has_preview': True,
                               'preview_sha256': FIXED_HEX, 'preview_pages': 1}],
                    'large': {'big_link_id': 'BIG', 'big_preview_bytes': 100, 'big_preview_sha256': FIXED_HEX,
                              'limit_links': 1, 'limit_preview_bytes': 200}}))
                return self.ok('{"ok":true,"times_ms":{"documents_ms":5}}')
            if cmd == 'export':
                Path(argv[3]).write_bytes(b'ENC' if '--encrypted' in argv else b'x')
                return self.ok('{"ok":true,"export_ms":42,"bytes":3}')
            if cmd == 'check' and 'refuse' in argv[2]:
                return h.Result(1, b'', b'archive_interop: refused for a reason')
            if cmd == 'make':
                gen = Path(argv[2])
                gen.mkdir()
                for n in ['desktop-plain-v4.stackbackup', 'desktop-plain.stackbackup', 'desktop-encrypted.stackbackup']:
                    (gen / n).write_bytes(b'ENC' if 'encrypted' in n else b'x')
                for n in h.NOLINKS:
                    (gen / n).write_bytes(b'ENCNL' if 'encrypted' in n else b'NL')
                (gen / h.EXPECTED_NOLINKS).write_text(json.dumps({'documents': {'count': 1, 'titles': ['d']}, 'links': [], 'preview_pages': 2}))
                (gen / 'expected.json').write_text(json.dumps({'documents': {'count': 1, 'titles': ['d']}, 'links': [
                    {'owner': 'o', 'link_id': 'L1', 'title': 't', 'text_marker': 'm', 'has_preview': True,
                     'preview_sha256': FIXED_HEX}], 'preview_pages': 2}))
                return self.ok('{"ok":true}')
            if cmd == 'reexport':
                Path(argv[3]).write_bytes(b'R')
                return self.ok('{"ok":true}')
            if cmd == 'check':
                links = 0 if 'nolinks' in argv[2] else len(json.loads(Path(argv[3]).read_text())['links']) if argv[3].endswith('expected-large.json') else 1
                return self.ok(json.dumps({'ok': True, 'documents': 1, 'links': links, 'mismatches': []}))
        if cmd == 'archive':
            action, path = argv[3], argv[4]
            encrypted = 'encrypted' in path or path.endswith('outbox.stackbackup') or 'passphrase' in path
            nolinks = 'nolinks' in path
            good = {(h.TEST_KEY_HEX + '\n').encode()}
            if 'outbox-passphrase' in path:  # two slots: the key and the passphrase in either Unicode form
                good |= {(f + '\n').encode() for f in h.PASSPHRASE_FORMS}
            if action == 'review' and encrypted and data is None:
                return h.Result(1, b'', b'Error: No key for this backup: it is encrypted.')
            if action == 'review' and encrypted and data not in good:
                return h.Result(1, b'', b'Error: Wrong passphrase or recovery key, or damaged.')
            docs = 2 if 'outbox' in path else 1
            if action == 'review':
                return self.ok(json.dumps({'format': 3 if nolinks and not encrypted else 4, 'encrypted': encrypted, 'documents': docs,
                                           'links_imported': 0 if nolinks else 1,
                                           'links_skipped': 0, 'warnings': [] if encrypted else ['Not encrypted, so it cannot be checked for tampering']}))
            if action == 'restore':
                self.last_restore_links = 0 if nolinks else 1
                return self.ok(json.dumps({'documents_added': docs, 'links_added': self.last_restore_links, 'links_skipped': 0, 'encrypted': encrypted}))
        return self.ok(json.dumps({'documents': 1, 'links': self.last_restore_links}))


def adb_for(world):
    return h.Adb(lambda argv, **k: world(['ADB'] + argv[1:], **k), '/fake/adb', 5037)


def harness(world, steps=h.STEPS, out=None, adb=True):
    out = out or fresh_output()
    adb = adb_for(world) if adb else None
    hs = h.Harness(adb, '/home/x/repo', out, steps, runner=lambda argv, **k: world(argv, **k))
    return hs, out


class GuardTests(unittest.TestCase):
    def cfg(self, **over):
        a = dict(serial=A, env={}, argv_values=[], package=h.PACKAGE, output=fresh_output(), adb_port=5037)
        a.update(over)
        return h.check_config(**a)

    def test_ok(self):
        self.cfg()

    def test_wrong_serial(self):
        with self.assertRaises(h.Refused):
            self.cfg(serial='SOMETHING')

    def test_android_serial_env(self):
        with self.assertRaises(h.Refused):
            self.cfg(env={'ANDROID_SERIAL': 'other'})
        self.cfg(env={'ANDROID_SERIAL': A})

    def test_denied_serial_anywhere(self):
        with self.assertRaises(h.Refused):
            self.cfg(argv_values=['--step', 'x' + h.DENIED_SERIAL])
        with self.assertRaises(h.Refused):
            self.cfg(env={'FOO': h.DENIED_SERIAL})

    def test_adb_and_port_required(self):
        with self.assertRaises(SystemExit):
            h.parse_args(['--adb-port', '5037', '--output', '/tmp/x'])
        with self.assertRaises(SystemExit):
            h.parse_args(['--adb', '/a', '--output', '/tmp/x'])
        with self.assertRaises(h.Refused):
            self.cfg(adb_port=None)
        h.parse_args(['--selftest'])

    def test_package_exact(self):
        for bad in ['org.stackdocs.app', h.PACKAGE + 'x', 'org.stackdocs.app.checks.sync']:
            with self.assertRaises(h.Refused):
                self.cfg(package=bad)
        with self.assertRaises(h.Refused):
            h.Adb(lambda *a, **k: None, 'adb', 5037, package='org.stackdocs.app')
        with self.assertRaises(h.Refused):
            h.Adb(lambda *a, **k: None, 'adb', 5037, serial=h.DENIED_SERIAL)

    def test_always_dash_s(self):
        w = FakeWorld()
        adb = adb_for(w)
        adb.preflight()
        adb.mkdir('interop-abc')
        self.assertTrue(w.calls)
        for c in w.calls:
            self.assertEqual(c[3:5], ['-s', A])

    def test_preflight_refusals_run_nothing_after(self):
        for kw in [dict(serial='ZZZ'), dict(device='flame'), dict(model='Pixel 5'), dict(instr=False), dict(running=True)]:
            w = FakeWorld(**kw)
            with self.assertRaises(h.Refused, msg=str(kw)):
                adb_for(w).preflight()
            self.assertFalse(any('mkdir' in ' '.join(c) or 'cat >' in ' '.join(c) for c in w.calls))
        w = FakeWorld(serial='ZZZ')
        with self.assertRaises(h.Refused):
            adb_for(w).preflight()
        self.assertEqual(len(w.calls), 1)  # stopped at get-serialno

    def test_forbidden_commands(self):
        adb = adb_for(FakeWorld())
        for parts in [('pm', 'install', 'x.apk'), ('pm', 'uninstall', 'p'), ('pm', 'clear', h.PACKAGE),
                      ('am', 'start', '-n', 'x/y'), ('am', 'force-stop', 'p'), ('install', 'x')]:
            with self.assertRaises(h.Refused, msg=str(parts)):
                adb.shell(*parts)
        for verb in ['install', 'uninstall', 'push', 'devices', 'kill-server']:
            with self.assertRaises(h.Refused):
                adb._run([verb, 'x'])

    def test_phone_path_restricted(self):
        adb = adb_for(FakeWorld())
        for bad in ['files/checks/interop-a/files/../../x', 'databases/x', 'files/checks/real/files/inbox.stackbackup']:
            with self.assertRaises(h.Refused):
                adb.read(bad)
            with self.assertRaises(h.Refused):
                adb.put(bad, b'')
        with self.assertRaises(h.Refused):
            adb.phase('read', 'prod-replica')
        with self.assertRaises(h.Refused):
            adb.mkdir('../x')

    def test_output_dirs(self):
        out = fresh_output()
        self.assertEqual(h.check_output_dir(out), out.resolve())
        out.parent.joinpath('exists').mkdir()
        with self.assertRaises(h.Refused):
            h.check_output_dir(out.parent / 'exists')
        with self.assertRaises(h.Refused):
            h.check_output_dir('/var/tmp/fresh-x')
        with self.assertRaises(h.Refused):
            h.check_output_dir('/tmp')
        with self.assertRaises(h.Refused):
            h.check_output_dir(Path.home() / '.local/share/fresh-x')
        with self.assertRaises(h.Refused):  # a fake home under /tmp, ~/.local/share rule
            h.check_output_dir('/tmp/fakehome/.local/share/x', home='/tmp/fakehome')

    def test_scratch_marker_and_safe_delete(self):
        out = fresh_output()
        work = h.make_scratch(out)
        self.assertTrue((out / h.MARKER).exists() and work.is_dir())
        h.remove_scratch(out)
        self.assertFalse(work.exists())
        other = Path(tempfile.mkdtemp(dir='/tmp'))
        with self.assertRaises(h.Refused):
            h.remove_scratch(other)

    def test_cli_argv_guard(self):
        for bad in [['x', '--saved-key'], ['--saved-key=abc'], ['--key-file', 'f'], ['--passphrase'], ['--passphrase-file=x'],
                    ['archive', h.TEST_KEY_HEX], ['x', 'a' * 64], ['keyvault', 'list'], ['/bin/stack-keyvault']]:
            with self.assertRaises(h.Refused, msg=str(bad)):
                h.guard_cli_argv(bad)
        h.guard_cli_argv(['archive', '/tmp/x', 'restore', 'f.stackbackup', '--key-stdin'])

    def test_passphrase_guards(self):
        h.guard_cli_argv(['archive', '/tmp/x', 'restore', 'f.stackbackup', '--passphrase-stdin'])
        for bad in [['x', '--passphrase-file', 'f'], ['--passphrase=abc'], ['--passphrase'], ['x', h.TEST_PASSPHRASE],
                    ['x', h.TEST_PASSPHRASE_NFC]]:
            with self.assertRaises(h.Refused, msg=str(bad)):
                h.guard_cli_argv(bad)
        self.assertNotEqual(h.TEST_PASSPHRASE, h.TEST_PASSPHRASE_NFC)
        self.assertFalse(h.TEST_PASSPHRASE.isascii())
        for form in h.PASSPHRASE_FORMS:
            with self.assertRaises(h.Refused):
                h.scan_output('stderr said ' + form)

    def test_passphrase_only_on_stdin(self):
        w = FakeWorld()
        d = h.Desktop(lambda argv, **k: w(argv, **k), '/repo', '/tmp/work', '/tmp/work/gen', set(), '/p/libpdfium.so')
        d.cli('archive', '/tmp/lib', 'restore', '/tmp/outbox-passphrase.stackbackup', passphrase=h.TEST_PASSPHRASE_NFC)
        self.assertEqual(w.desktop_stdin[0], (h.TEST_PASSPHRASE_NFC + '\n').encode('utf-8'))
        self.assertIn('--passphrase-stdin', w.calls[0])
        self.assertFalse(any(h.TEST_PASSPHRASE_NFC in a or h.TEST_PASSPHRASE in a for a in w.calls[0]))
        with self.assertRaises(h.Refused):
            d.cli('archive', '/tmp/lib', 'restore', 'f', key=h.TEST_KEY_HEX, passphrase=h.TEST_PASSPHRASE)

    def test_phase_options_whitelist(self):
        w = FakeWorld()
        adb = adb_for(w)
        adb.preflight()
        adb.mkdir('interop-aaa')
        for bad in [{'interopPassphrase': 'false'}, {'interopX': 'true'}, {'interopPhase': 'write'}]:
            with self.assertRaises(h.Refused, msg=str(bad)):
                adb.phase('write', 'interop-aaa', options=bad)
        adb.phase('write', 'interop-aaa', options={'interopPassphrase': 'true'})
        self.assertEqual(w.am_options[-1], {'interopPassphrase': 'true'})
        self.assertIn('files/checks/interop-aaa/files/outbox-passphrase.stackbackup', w.files)
        self.assertEqual(adb.read('files/checks/interop-aaa/files/outbox-passphrase.stackbackup'), b'Q' * 12)

    def test_time_v_and_df_parsing(self):
        report = ('x\n\tCommand being timed: "a b"\n\tElapsed (wall clock) time (h:mm:ss or m:ss): 1:02.50\n'
                  '\tMaximum resident set size (kbytes): 204800\n')
        self.assertEqual(h.parse_time_v(report), {'wall_s': 62.5, 'peak_mib': 200.0})
        self.assertEqual(h.parse_time_v('1:01:01.00'.join(['\tElapsed (wall clock) time (h:mm:ss or m:ss): ', '']))['wall_s'], 3661.0)
        self.assertEqual(h.strip_time_v(report), 'x')
        self.assertEqual(h.parse_df_available_kb(['Filesystem 1K-blocks Used Available Use% Mounted on', '/dev/x 100 1 77 5% /data']), 77)
        with self.assertRaises(RuntimeError):
            h.parse_df_available_kb(['only one line'])
        with self.assertRaises(RuntimeError):
            h.parse_df_available_kb(['hdr', 'a b c d'])
        self.assertTrue(h.large_free_space_ok(2 * 1024 * 1024))
        self.assertFalse(h.large_free_space_ok(2 * 1024 * 1024 - 1))

    def test_titles_digest_is_order_independent(self):
        a = ['Caf\u00e9', '\U0001F4C4 x', '\u56db\u534a', 'plain']
        self.assertEqual(h.titles_digest(a), h.titles_digest(list(reversed(a))))
        self.assertEqual(len(h.titles_digest(a)), 64)
        self.assertNotEqual(h.titles_digest(a), h.titles_digest(a[:-1]))
        self.assertEqual(h.titles_digest([]), '0' * 64)

    def test_large_flag_and_plan(self):
        a = h.parse_args(['--desktop-only', '--large', '--output', '/tmp/x'])
        self.assertEqual(a.steps, h.DESKTOP_STEPS + ['large'])
        self.assertEqual(h.DESKTOP_STEPS, ['build', 'make', 'reexport', 'cli', 'negative'])  # not mutated
        b = h.parse_args(['--adb', '/x/adb', '--adb-port', '5037', '--output', '/tmp/y', '--large'])
        self.assertEqual(b.steps, h.STEPS + ['large'])
        text = '\n'.join(h.plan(b))
        self.assertIn('df -k /data', text)
        self.assertIn('interopBrief', text)
        self.assertNotIn('large', h.parse_args(['--desktop-only', '--output', '/tmp/x']).steps)

    def test_compare_large_read(self):
        links = [{'link_id': 'A', 'title': 'Caf\u00e9', 'has_preview': False, 'preview_sha256': None},
                 {'link_id': 'BIG', 'title': '\U0001F980', 'has_preview': True, 'preview_sha256': FIXED_HEX}]
        expected = {'documents': {'count': 2, 'titles': ['a', 'b']}, 'links': links,
                    'large': {'big_link_id': 'BIG', 'big_preview_bytes': 100}}
        good = {'encrypted': True, 'documents': 2, 'document_titles': ['b', 'a'], 'links_added': 2, 'links_skipped': 0,
                'reimport_links_skipped': 2, 'link_titles_digest': h.titles_digest(['Caf\u00e9', '\U0001F980']),
                'previews': [{'link_id': 'BIG', 'preview_sha256': FIXED_HEX, 'bytes': 100}]}
        h.compare_large_read(good, expected)
        for key, value in [('links_added', 1), ('link_titles_digest', '0' * 64), ('encrypted', False), ('document_titles', ['a']),
                           ('previews', [{'link_id': 'BIG', 'preview_sha256': 'cd' * 32, 'bytes': 100}]),
                           ('previews', [{'link_id': 'BIG', 'preview_sha256': FIXED_HEX, 'bytes': 99}])]:
            with self.assertRaises(AssertionError, msg=key):
                h.compare_large_read(dict(good, **{key: value}), expected)

    def test_key_only_on_stdin_and_env(self):
        w = FakeWorld()
        d = h.Desktop(lambda argv, **k: w(argv, **k), '/repo', '/tmp/work', '/tmp/work/gen', set(), '/p/libpdfium.so')
        d.cli('archive', '/tmp/lib', 'restore', '/tmp/f', key=h.TEST_KEY_HEX)
        d.cli('archive', '/tmp/lib', 'restore', '/tmp/f', pdfium=False)
        self.assertEqual(w.desktop_stdin[0], (h.TEST_KEY_HEX + '\n').encode())
        self.assertIsNone(w.desktop_stdin[1])
        for argv in w.calls:
            self.assertNotIn(h.TEST_KEY_HEX, ' '.join(argv))
            self.assertIn('--key-stdin', argv) if argv is w.calls[0] else self.assertNotIn('--key-stdin', argv)
        env = w.desktop_envs[0]
        for k in ['HOME', 'XDG_DATA_HOME', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME']:
            self.assertTrue(env[k].startswith('/tmp/work'))
        self.assertEqual(env['DBUS_SESSION_BUS_ADDRESS'], 'unix:path=/nonexistent')
        self.assertEqual(env['STACK_PDFIUM_LIBRARY'], '/p/libpdfium.so')
        self.assertNotIn('STACK_PDFIUM_LIBRARY', w.desktop_envs[1])
        self.assertNotIn('ANDROID_SERIAL', env)

    def test_flip_key_char(self):
        flipped = h.flip_key_char(h.TEST_KEY_HEX)
        self.assertNotEqual(flipped, h.TEST_KEY_HEX)
        self.assertEqual(len(flipped), 64)
        self.assertEqual(flipped[1:], h.TEST_KEY_HEX[1:])

    def test_desktop_only_args(self):
        a = h.parse_args(['--desktop-only', '--output', '/tmp/x'])
        self.assertEqual(a.steps, h.DESKTOP_STEPS)
        for bad in (['--desktop-only'], ['--desktop-only', '--output', '/tmp/x', '--adb-port', '5037'],
                    ['--desktop-only', '--output', '/tmp/x', '--step', 'make']):
            with self.assertRaises(SystemExit):
                h.parse_args(bad)
        h.check_desktop_config({}, [], fresh_output())
        with self.assertRaises(h.Refused):
            h.check_desktop_config({}, ['x' + h.DENIED_SERIAL], fresh_output())
        with self.assertRaises(h.Refused):
            h.check_desktop_config({}, [], '/var/tmp/fresh-x')

    def test_output_scan(self):
        h.scan_output('no secrets here')
        h.scan_output('hash ' + FIXED_HEX, known={FIXED_HEX})
        with self.assertRaises(h.Refused):
            h.scan_output('hash ' + FIXED_HEX)
        with self.assertRaises(h.Refused):
            h.scan_output(h.TEST_KEY_HEX.upper(), known={h.TEST_KEY_HEX})
        w = FakeWorld()
        w.desktop = lambda argv, d, e: h.Result(0, b'', ('k=' + FIXED_HEX).encode())
        d = h.Desktop(lambda argv, **k: w.desktop(argv, k['input'], k['env']), '/r', '/tmp/w', '/tmp/w/g', set())
        with self.assertRaises(h.Refused):
            d.cli('status')

    def test_dry_run_plan_has_no_side_effects(self):
        out = fresh_output()
        a = h.parse_args(['--adb', '/x/adb', '--adb-port', '5037', '--output', str(out), '--dry-run'])
        text = '\n'.join(h.plan(a))
        self.assertIn('-s ' + A, text)
        self.assertNotIn(h.TEST_KEY_HEX, text)
        self.assertFalse(out.exists())


class FlowTests(unittest.TestCase):
    def setUp(self):
        self._find = h.find_pdfium
        h.find_pdfium = lambda repo: '/fake/libpdfium.so'

    def tearDown(self):
        h.find_pdfium = self._find

    def test_full_flow_and_cleanup(self):
        w = FakeWorld()
        hs, out = harness(w)
        self.assertTrue(hs.run(), hs.report)
        self.assertEqual(set(hs.report['steps'].values()), {'passed'})
        self.assertEqual(sorted(w.cleaned), sorted(hs.replicas))
        self.assertEqual(len(hs.replicas), 6)  # four desktop files, the phone write, the loop's fresh replica
        for c in w.calls:
            if c[0] != 'ADB':
                self.assertNotIn('--key-stdin', c[:2])
        self.assertFalse((out / 'work').exists())
        report = (out / 'report.json').read_text()
        self.assertNotIn(h.TEST_KEY_HEX, report)
        self.assertEqual(w.dirs, set())
        # PDFium only on the generator and stack-cli subprocesses, never on cargo builds
        builds = [e for c, e in zip([c for c in w.calls if c[0] != 'ADB'], w.desktop_envs) if 'build' in c]
        self.assertEqual(len(builds), 2)
        self.assertFalse(any('STACK_PDFIUM_LIBRARY' in e for e in builds))

    def test_passphrase_flow_and_nolinks_reports(self):
        w = FakeWorld()
        hs, out = harness(w)
        self.assertTrue(hs.run(), hs.report)
        self.assertEqual(hs.report['passphrase'], {'recovery_key': 'opened', 'passphrase_nfd': 'opened', 'passphrase_nfc': 'opened',
                                                  'wrong_passphrase': 'refused'})
        self.assertIn({'interopPassphrase': 'true'}, w.am_options)
        stdin = [d for d in w.desktop_stdin if d]
        for form in h.PASSPHRASE_FORMS:
            self.assertIn((form + '\n').encode(), stdin)
        for c in w.calls:
            self.assertFalse(any(f in str(a) for f in h.PASSPHRASE_FORMS for a in c))
        report = (out / 'report.json').read_text()
        self.assertFalse(any(f in report for f in h.PASSPHRASE_FORMS))

    def test_nolinks_cli_reports(self):
        w = FakeWorld()
        hs, _ = harness(w, steps=h.DESKTOP_STEPS, adb=False)
        self.assertTrue(hs.run(), hs.report)
        self.assertEqual(hs.report['cli_nolinks']['desktop-nolinks-plain.stackbackup']['format'], 3)
        self.assertEqual(hs.report['cli_nolinks']['desktop-nolinks-encrypted.stackbackup']['format'], 4)
        self.assertEqual({v['links'] for v in hs.report['cli_nolinks'].values()}, {0})

    def test_wrong_passphrase_accepted_is_a_failure(self):
        w = FakeWorld()
        orig = w.desktop
        def accept_all(argv, data, env):
            if len(argv) > 3 and argv[1] == 'archive' and argv[3] == 'review' and data == (h.TEST_PASSPHRASE + '!\n').encode():
                return h.Result(0, b'{"format":4,"encrypted":true,"documents":2,"links_imported":1}', b'')
            return orig(argv, data, env)
        w.desktop = accept_all
        hs, _ = harness(w, steps=['make', 'phone-to-desktop'])
        self.assertFalse(hs.run())
        self.assertIn('FAILED', hs.report['steps']['phone-to-desktop'])

    def test_nolinks_phone_read_must_report_zero_links(self):
        w = FakeWorld()
        orig = w.read_json
        def wrong(name):
            body = orig(name)
            if w.files.get(f'files/checks/{name}/files/inbox.stackbackup') in (b'NL', b'ENCNL'):
                body = dict(body, links_added=1)
            return body
        w.read_json = wrong
        hs, _ = harness(w, steps=['make', 'desktop-to-phone'])
        self.assertFalse(hs.run())
        self.assertIn('links_added', hs.report['steps']['desktop-to-phone'])

    def test_nolinks_desktop_check_must_find_zero_links(self):
        w = FakeWorld()
        orig = w.desktop
        def bad(argv, data, env):
            if argv[0].endswith('archive_interop') and argv[1] == 'check' and 'nolinks-plain' in argv[2]:
                return h.Result(0, b'{"ok":true,"documents":1,"links":2,"mismatches":[]}', b'')
            return orig(argv, data, env)
        w.desktop = bad
        hs, _ = harness(w, steps=h.DESKTOP_STEPS, adb=False)
        self.assertFalse(hs.run())
        self.assertIn('FAILED', hs.report['steps']['make'])

    def test_large_desktop_only(self):
        w = FakeWorld()
        hs, out = harness(w, steps=h.DESKTOP_STEPS + ['large'], adb=False)
        self.assertTrue(hs.run(), hs.report)
        large = hs.report['large']
        self.assertEqual(large['export_encrypted']['peak_mib'], 200.0)
        self.assertEqual(large['check_plain']['wall_s'], 62.5)
        self.assertEqual(large['cli_restore_encrypted']['peak_mib'], 200.0)
        self.assertEqual(len(large['refused']), 2)
        self.assertFalse(any(c[0] == 'ADB' for c in w.calls))
        self.assertFalse((out / 'work').exists())

    def test_large_on_phone_pushes_and_bounds_time(self):
        w = FakeWorld()
        hs, _ = harness(w, steps=['make', 'large'])
        self.assertTrue(hs.run(), hs.report)
        self.assertEqual(hs.report['large']['phone_read_ms'], 1234)
        self.assertIn({'interopBrief': 'true'}, w.am_options)
        self.assertEqual(w.cleaned, hs.replicas)

    def test_large_refused_when_phone_is_short_of_space(self):
        w = FakeWorld(free_kb=2 * 1024 * 1024 - 1)
        hs, _ = harness(w, steps=['make', 'large'])
        self.assertFalse(hs.run())
        self.assertIn('2097152', hs.report['steps']['large'])
        self.assertEqual(hs.replicas, [])  # nothing was pushed
        self.assertFalse(any(c[0] == 'ADB' and 'cat >' in ' '.join(c) for c in w.calls))

    def test_large_phone_read_over_the_bound_fails(self):
        w = FakeWorld()
        w.brief_json = lambda: dict(FakeWorld.brief_json(w), read_ms=h.LARGE_PHONE_READ_LIMIT_MS + 1)
        hs, _ = harness(w, steps=['make', 'large'])
        self.assertFalse(hs.run())
        self.assertIn('limit', hs.report['steps']['large'])

    def test_large_refusal_variant_accepted_is_a_failure(self):
        w = FakeWorld()
        orig = w.desktop
        def accept(argv, data, env):
            if argv[0].endswith('archive_interop') and argv[1] == 'check' and 'refuse-links' in argv[2]:
                return h.Result(0, b'{"ok":true}', b'')
            return orig(argv, data, env)
        w.desktop = accept
        hs, _ = harness(w, steps=h.DESKTOP_STEPS + ['large'], adb=False)
        self.assertFalse(hs.run())
        self.assertIn('FAILED', hs.report['steps']['large'])

    def test_desktop_only_never_calls_adb(self):
        w = FakeWorld()
        hs, out = harness(w, steps=h.DESKTOP_STEPS, adb=False)
        self.assertTrue(hs.run(), hs.report)
        self.assertEqual(set(hs.report['steps']), set(h.DESKTOP_STEPS))
        self.assertEqual(set(hs.report['steps'].values()), {'passed'})
        self.assertFalse(any(c[0] == 'ADB' or 'adb' in Path(c[0]).name for c in w.calls))
        self.assertIn('Wrong passphrase or recovery key, or damaged.', hs.report['negative']['wrong_key'])
        self.assertIn('No key for this backup', hs.report['negative']['no_key'])
        self.assertFalse((out / 'work').exists())
        self.assertNotIn(h.TEST_KEY_HEX, (out / 'report.json').read_text())

    def test_desktop_only_wrong_key_message_must_match(self):
        w = FakeWorld()
        orig = w.desktop
        def bad(argv, data, env):
            if len(argv) > 3 and argv[1] == 'archive' and argv[3] == 'review' and data and data != (h.TEST_KEY_HEX + '\n').encode():
                return h.Result(1, b'', b'Error: something else')
            return orig(argv, data, env)
        w.desktop = bad
        hs, _ = harness(w, steps=h.DESKTOP_STEPS, adb=False)
        self.assertFalse(hs.run())
        self.assertIn('FAILED', hs.report['steps']['negative'])

    def test_desktop_only_debug_form_is_refused(self):
        w = FakeWorld()
        orig = w.desktop
        def debug_form(argv, data, env):
            if len(argv) > 3 and argv[1] == 'archive' and argv[3] == 'review' and data and data != (h.TEST_KEY_HEX + '\n').encode():
                return h.Result(1, b'', b'Error: Archive(Envelope(WrongKey))')
            return orig(argv, data, env)
        w.desktop = debug_form
        hs, _ = harness(w, steps=h.DESKTOP_STEPS, adb=False)
        self.assertFalse(hs.run())
        self.assertIn('FAILED', hs.report['steps']['negative'])

    def test_cleanup_after_failure(self):
        w = FakeWorld(fail_read=True)
        hs, out = harness(w, steps=['make', 'desktop-to-phone'])
        self.assertFalse(hs.run())
        self.assertIn('FAILED', hs.report['steps']['desktop-to-phone'])
        self.assertEqual(w.cleaned, hs.replicas)
        self.assertTrue(hs.replicas)
        self.assertFalse((out / 'work').exists())

    def test_preflight_failure_touches_nothing(self):
        w = FakeWorld(device='flame')
        hs, out = harness(w)
        self.assertFalse(hs.run())
        self.assertFalse(out.exists())
        self.assertEqual(w.cleaned, [])

    def test_mismatch_detected(self):
        w = FakeWorld()
        orig = w.read_json
        w.read_json = lambda n: dict(orig(n), documents=9)
        hs, _ = harness(w, steps=['make', 'desktop-to-phone'])
        self.assertFalse(hs.run())

    def test_step_needs_prerequisite(self):
        w = FakeWorld()
        hs, _ = harness(w, steps=['loop'])
        self.assertFalse(hs.run())
        self.assertEqual(w.cleaned, [])


if __name__ == '__main__':
    unittest.main()
