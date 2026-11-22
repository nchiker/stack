#!/usr/bin/env python3
"""Host-JDK check of the unchanged production BackupEnvelope.java against desktop archives.

Compiles app/.../BackupEnvelope.java byte-for-byte plus tools/java/EnvelopeHost.java, then:
 (a) Java decrypts desktop-encrypted.stackbackup (recovery slot, TEST_KEY): STACKBK magic, format 4, link count,
     and `archive_interop check` accepts the decrypted inner archive;
 (b) Java encrypts desktop-plain-v4.stackbackup key-only; `archive_interop check` accepts the result;
 (c) a flipped key, a truncated copy and a flipped body byte are refused by Java.
Only the public synthetic TEST_KEY is used. It travels on stdin only, never in argv, files or output.
Usage: test_archive_envelope_host.py [--selftest]
"""
import glob
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TEST_KEY = bytes(range(1, 33)).hex()  # public synthetic key, never a real one
ENVELOPE = ROOT / 'app/src/main/java/org/stackdocs/app/BackupEnvelope.java'
HOST = Path(__file__).with_name('java') / 'EnvelopeHost.java'
GEN_BIN = ROOT / 'desktop/target/debug/examples/archive_interop'
MAGIC = b'STACKBK\x01'


def safe_scratch(path):
    p = Path(path)
    return p.is_absolute() and p.parent == Path('/tmp') and '..' not in p.parts and p.name.startswith('stack-envelope-')


def flip_key(key):
    return key[:-1] + ('0' if key[-1] != '0' else '1')


def leaks(data, key=TEST_KEY):
    """True when `data` (bytes or str) holds the key in hex form, in either case or grouped by dashes/spaces."""
    text = data.decode('latin-1') if isinstance(data, bytes) else data
    squeezed = ''.join(c for c in text.lower() if c not in ' -\n\t')
    return key in squeezed


def scan_tree(root):
    return [str(p) for p in Path(root).rglob('*') if p.is_file() and p.name != 'expected.json' and leaks(p.read_bytes())] \
        + [str(p) for p in Path(root).rglob('*') if p.is_file() and p.name == 'expected.json' and leaks(p.read_bytes())]


def no_key_in_argv(argv):
    if any(TEST_KEY in a.lower().replace('-', '') for a in map(str, argv)):
        raise AssertionError('key in argv')


def run(argv, key=None, env=None, cwd=None):
    no_key_in_argv(argv)
    p = subprocess.run([str(a) for a in argv], input=(key + '\n').encode() if key is not None else None,
                       capture_output=True, env=env, cwd=cwd, timeout=600)
    out = p.stdout + p.stderr
    if leaks(out):
        raise AssertionError('key text in subprocess output')
    return p.returncode, out.decode('utf-8', 'replace')


def parse_inner(data):
    """Format 3/4 rules: magic, int32 manifest length (<=32 MiB), SHA-256, manifest, file table, no trailing bytes."""
    if data[:8] != MAGIC:
        raise AssertionError('inner stream lacks STACKBK magic')
    length = struct.unpack('>i', data[8:12])[0]
    if not 2 <= length <= 32 * 1024 * 1024:
        raise AssertionError('manifest length')
    digest, manifest = data[12:44], data[44:44 + length]
    if hashlib.sha256(manifest).digest() != digest:
        raise AssertionError('manifest hash')
    value = json.loads(manifest.decode('utf-8'))
    pos = 44 + length
    for entry in value['files']:
        size = struct.unpack('>H', data[pos:pos + 2])[0]
        path = data[pos + 2:pos + 2 + size].decode('utf-8')
        count = struct.unpack('>q', data[pos + 2 + size:pos + 10 + size])[0]
        pos += 10 + size
        body = data[pos:pos + count]
        if path != entry['path'] or count != entry['length'] or len(body) != count \
                or hashlib.sha256(body).hexdigest() != entry['sha256']:
            raise AssertionError('file entry ' + path)
        pos += count
    if pos != len(data):
        raise AssertionError('trailing bytes')
    return value


def find_javac():
    found = shutil.which('javac')
    if found:
        return found
    hits = sorted(glob.glob(str(Path.home() / '.local/share/stack-jdk/*/bin/javac')))
    if not hits:
        raise RuntimeError('no javac')
    return hits[-1]


def find_pdfium():
    hits = sorted(glob.glob(str(ROOT / '.artifacts/*/stage/runtime/pdfium/lib/libpdfium.so')))
    if not hits:
        raise RuntimeError('no staged libpdfium.so under .artifacts')
    return hits[-1]


def main():
    env = {'PATH': os.environ.get('PATH', '/usr/bin:/bin'), 'LANG': 'C.UTF-8', 'CARGO_HOME': str(Path.home() / '.cache/stack-cargo-home')}
    scratch = Path(tempfile.mkdtemp(prefix='stack-envelope-', dir='/tmp'))
    assert safe_scratch(scratch)
    ok = False
    try:
        rc, out = run(['cargo', 'build', '--offline', '-p', 'stack-cli', '--example', 'archive_interop'], cwd=ROOT / 'desktop', env={**os.environ, **env})
        assert rc == 0, out[-500:]
        gen = scratch / 'gen'
        rc, out = run([GEN_BIN, 'make', gen], env={**env, 'STACK_PDFIUM_LIBRARY': find_pdfium()})
        assert rc == 0, out[-500:]
        src, classes = scratch / 'src/org/stackdocs/app', scratch / 'classes'
        src.mkdir(parents=True)
        classes.mkdir()
        shutil.copyfile(ENVELOPE, src / 'BackupEnvelope.java')
        assert (src / 'BackupEnvelope.java').read_bytes() == ENVELOPE.read_bytes()
        shutil.copyfile(HOST, src / 'EnvelopeHost.java')
        javac = find_javac()
        java = str(Path(javac).with_name('java')) if Path(javac).with_name('java').exists() else 'java'
        rc, out = run([javac, '-encoding', 'UTF-8', '-d', classes, src / 'BackupEnvelope.java', src / 'EnvelopeHost.java'])
        assert rc == 0, out[-500:]
        host = lambda mode, a, b, key=TEST_KEY: run([java, '-cp', classes, 'org.stackdocs.app.EnvelopeHost', mode, a, b], key=key)
        expected = json.loads((gen / 'expected.json').read_text())
        want_links = len(expected['links'])
        report = {}

        # (a) Java decrypts the desktop envelope
        enc = gen / 'desktop-encrypted.stackbackup'
        assert enc.read_bytes()[:8] == b'STACKEN\x01', 'desktop file is not an envelope'
        inner = scratch / 'inner.stackbackup'
        rc, out = host('decrypt', enc, inner)
        assert rc == 0, 'Java refused the desktop envelope: ' + out
        manifest = parse_inner(inner.read_bytes())
        assert manifest['version'] == 4, manifest['version']
        assert len(manifest['links']) == want_links, len(manifest['links'])
        rc, out = run([GEN_BIN, 'check', inner, gen / 'expected.json'], env={**env, 'STACK_PDFIUM_LIBRARY': find_pdfium()})
        assert rc == 0, 'decrypted inner archive differs from expected: ' + out
        report['a_java_decrypts_desktop'] = {'format': manifest['version'], 'links': len(manifest['links']), 'inner_bytes': inner.stat().st_size}

        # (b) Java encrypts a desktop v4 archive key-only; Rust must read it
        plain = gen / 'desktop-plain-v4.stackbackup'
        jenc = scratch / 'java-encrypted.stackbackup'
        rc, out = host('encrypt', plain, jenc)
        assert rc == 0, out
        assert jenc.read_bytes()[:8] == b'STACKEN\x01'
        rc, out = run([GEN_BIN, 'check', jenc, gen / 'expected.json'], env={**env, 'STACK_PDFIUM_LIBRARY': find_pdfium()})
        assert rc == 0, 'Rust refused the Java-encrypted archive: ' + out
        back = scratch / 'java-roundtrip.stackbackup'
        rc, out = host('decrypt', jenc, back)
        assert rc == 0 and back.read_bytes() == plain.read_bytes(), 'Java round trip differs'
        report['b_rust_reads_java'] = {'ok': True, 'bytes': jenc.stat().st_size}

        # (c) refusals
        refused = {}
        rc, out = host('decrypt', enc, scratch / 'x1', key=flip_key(TEST_KEY))
        assert rc != 0 and not (scratch / 'x1').exists(), 'flipped key accepted'
        refused['flipped_key'] = out.strip()
        data = enc.read_bytes()
        for name, mutated in [('truncated', data[:-7]), ('truncated_header', data[:40]),
                              ('flipped_body_byte', data[:200] + bytes([data[200] ^ 1]) + data[201:])]:
            (scratch / (name + '.bin')).write_bytes(mutated)
            rc, out = host('decrypt', scratch / (name + '.bin'), scratch / (name + '.out'))
            assert rc != 0 and not (scratch / (name + '.out')).exists(), name + ' accepted'
            refused[name] = out.strip()
        report['c_refused'] = refused

        leaked = scan_tree(scratch)
        assert not leaked, 'key text found in ' + ', '.join(leaked)
        print(json.dumps(report, indent=2))
        ok = True
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
        assert not scratch.exists()
    return 0 if ok else 1


class GuardTests(unittest.TestCase):
    def test_scratch_must_be_fresh_under_tmp(self):
        self.assertTrue(safe_scratch('/tmp/stack-envelope-abc'))
        for bad in ['/tmp', '/home/x/stack-envelope-a', '/tmp/other', '/tmp/a/stack-envelope-a', 'stack-envelope-a', '/tmp/../tmp/stack-envelope-a']:
            self.assertFalse(safe_scratch(bad), bad)

    def test_key_detection(self):
        self.assertTrue(leaks('x ' + TEST_KEY + ' y'))
        self.assertTrue(leaks(TEST_KEY.upper().encode()))
        self.assertTrue(leaks('-'.join(TEST_KEY[i:i + 8] for i in range(0, 64, 8))))
        self.assertFalse(leaks(flip_key(TEST_KEY)))
        self.assertNotEqual(flip_key(TEST_KEY), TEST_KEY)

    def test_argv_guard(self):
        with self.assertRaises(AssertionError):
            no_key_in_argv(['java', '--key', TEST_KEY])
        no_key_in_argv(['java', 'decrypt', 'a', 'b'])

    def test_scan_tree_finds_leaks(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / 'a').write_text('clean')
            self.assertEqual(scan_tree(d), [])
            (Path(d) / 'b').write_text(TEST_KEY)
            self.assertEqual(len(scan_tree(d)), 1)

    def test_parse_inner_rules(self):
        manifest = json.dumps({'version': 4, 'files': [], 'links': []}).encode()
        good = MAGIC + struct.pack('>i', len(manifest)) + hashlib.sha256(manifest).digest() + manifest
        self.assertEqual(parse_inner(good)['version'], 4)
        for bad in [b'X' + good[1:], good + b'!', good[:12] + b'\0' * 32 + good[44:]]:
            with self.assertRaises(AssertionError):
                parse_inner(bad)

    def test_host_never_reads_key_from_argv(self):
        source = HOST.read_text()
        self.assertIn('System.in', source)
        self.assertNotIn('args[3]', source)


if __name__ == '__main__':
    if sys.argv[1:] == ['--selftest']:
        sys.exit(0 if unittest.TextTestRunner(verbosity=1).run(unittest.defaultTestLoader.loadTestsFromTestCase(GuardTests)).wasSuccessful() else 1)
    sys.exit(main())
