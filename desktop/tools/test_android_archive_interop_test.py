"""Guard and flow tests for test_android_archive_interop.py using fake adb/commands only.

Run: python3 -I -m unittest discover -s desktop/tools -p 'test_android_archive_interop_test.py'
No real adb, device or cargo is ever run.
"""
import json
import os
from pathlib import Path
import shlex
import tempfile
import unittest

import test_android_archive_interop as h

A = h.ALLOWED_SERIAL
FIXED_HEX = 'ab' * 32


def fresh_output():
    base = tempfile.mkdtemp(prefix='interop-test-', dir='/tmp')
    return Path(base) / 'out'


class FakeWorld:
    """In-memory adb + desktop. Records every argv."""
    def __init__(self, fail_read=False, device='sunfish', model='Pixel 4a', serial=A, instr=True, running=False):
        self.calls, self.files, self.dirs = [], {}, set()
        self.fail_read, self.device, self.model, self.serial = fail_read, device, model, serial
        self.instr, self.running = instr, running
        self.cleaned, self.desktop_envs, self.desktop_stdin = [], [], []
        self.write_json = None

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
            if phase == 'read':
                return self.ok('PASS 1: interop-read ' + json.dumps(self.read_json(name)) + '\nINSTRUMENTATION_CODE: -1\n')
            self.files[f'files/checks/{name}/files/outbox.stackbackup'] = b'E' * 10
            self.files[f'files/checks/{name}/files/outbox-plain.stackbackup'] = b'P' * 7
            self.write_json = {'documents': 2, 'document_titles': ['a', 'b'], 'encrypted_length': 10, 'plain_length': 7,
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
        enc = self.files.get(f'files/checks/{name}/files/inbox.stackbackup') == b'ENC'
        negative = {'truncated': 'Damaged' if enc else 'EOFException', 'truncated_restored': 0}
        if enc:
            negative['wrong_key'] = 'NoKey'
        return {'encrypted': enc, 'documents': 1, 'document_titles': ['d'], 'links_added': 1, 'links_skipped': 0,
                'reimport_links_skipped': 1,
                'links': [{'link_id': 'L1', 'owner': 'o', 'title': 't', 'has_preview': True, 'preview_sha256': FIXED_HEX}],
                'negative': negative}

    def desktop(self, argv, data, env):
        self.desktop_envs.append(env)
        self.desktop_stdin.append(data)
        cmd = argv[1] if len(argv) > 1 else ''
        if argv[0].endswith('archive_interop'):
            if cmd == 'make':
                gen = Path(argv[2])
                gen.mkdir()
                for n in ['desktop-plain-v4.stackbackup', 'desktop-plain.stackbackup', 'desktop-encrypted.stackbackup']:
                    (gen / n).write_bytes(b'ENC' if 'encrypted' in n else b'x')
                (gen / 'expected.json').write_text(json.dumps({'documents': {'count': 1, 'titles': ['d']}, 'links': [
                    {'owner': 'o', 'link_id': 'L1', 'title': 't', 'text_marker': 'm', 'has_preview': True,
                     'preview_sha256': FIXED_HEX}], 'preview_pages': 2}))
                return self.ok('{"ok":true}')
            if cmd == 'reexport':
                Path(argv[3]).write_bytes(b'R')
                return self.ok('{"ok":true}')
            if cmd == 'check':
                return self.ok('{"ok":true,"documents":1,"links":1,"mismatches":[]}')
        if cmd == 'archive':
            action, path = argv[3], argv[4]
            encrypted = 'encrypted' in path or path.endswith('outbox.stackbackup')
            if action == 'review' and encrypted and data is None:
                return h.Result(1, b'', b'Error: No key for this backup: it is encrypted.')
            if action == 'review' and encrypted and data != (h.TEST_KEY_HEX + '\n').encode():
                return h.Result(1, b'', b'Error: Wrong passphrase or recovery key, or damaged.')
            if action == 'review':
                return self.ok(json.dumps({'format': 4, 'encrypted': encrypted, 'documents': 1, 'links_imported': 1,
                                           'links_skipped': 0, 'warnings': [] if encrypted else ['Not encrypted, so it cannot be checked for tampering']}))
            if action == 'restore':
                return self.ok(json.dumps({'documents_added': 1, 'links_added': 1, 'links_skipped': 0, 'encrypted': encrypted}))
        return self.ok('{"documents":1,"links":1}')


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
        self.assertEqual(len(hs.replicas), 4)
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
