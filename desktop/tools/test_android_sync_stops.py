#!/usr/bin/env python3
"""Kill only a separate Checks app at real nonempty-adoption commit boundaries."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid


BOUNDARIES = ('originals_installed', 'transaction', 'rows_written', 'committed', 'receipt')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--adb-port', required=True, type=int)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--cli', required=True, type=Path)
    parser.add_argument('--fixtures', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--boundary', choices=BOUNDARIES)
    args = parser.parse_args()
    if not re.fullmatch(r'org\.stackdocs\.app\.checks\.[a-z][a-z0-9_]{0,31}', args.package):
        parser.error('A separate disposable Checks namespace is required')
    output = args.output.resolve()
    if not output.is_relative_to('/tmp'):
        parser.error('Choose a fresh disposable /tmp output')
    assert json.loads((args.fixtures / 'synthetic-fixture.json').read_text()) == {
        'fixture': 'stack-m1', 'version': 1, 'license': 'CC0-1.0'}
    output.mkdir(mode=0o700)
    adb = [str(args.adb.resolve()), '-P', str(args.adb_port), '-s', args.serial]
    instrument = f'{args.package}.test/org.stackdocs.app.ScannerChecks'
    cli = str(args.cli.resolve())
    transcripts, cases = [], []

    def shell(*parts, data=None):
        return subprocess.check_output([*adb, 'shell', '-T', shlex.join(parts)], input=data, timeout=120)

    def run(*parts):
        return json.loads(subprocess.check_output([cli, *map(str, parts)], text=True, timeout=120))

    def read(relative):
        return subprocess.check_output([*adb, 'exec-out', 'run-as', args.package, 'cat', relative], timeout=60)

    def put(name, file, data):
        relative = f'files/checks/{name}/files/{file}'
        shell('run-as', args.package, 'sh', '-c', f'cat > {shlex.quote(relative)}', data=data)
        assert read(relative) == data

    def invocation(name, phase, **values):
        command = ['am', 'instrument', '-w', '-r', '-e', 'syncPhase', phase, '-e', 'replicaName', name]
        for key, value in values.items():
            command += ['-e', key, str(value)]
        return [*adb, 'shell', '-T', shlex.join([*command, instrument])]

    def phone(name, phase, **values):
        text = subprocess.check_output(invocation(name, phase, **values), text=True, timeout=120)
        transcripts.append({'replica': name, 'phase': phase, 'output': text})
        (output / 'instrumentation.json').write_text(json.dumps(transcripts, indent=2) + '\n')
        assert 'FAIL after' not in text and 'INSTRUMENTATION_CODE: -1' in text, text
        match = re.search(r'PASS \d+: sync ' + re.escape(phase) + r': (\{[^\n]+\})', text)
        assert match, text
        return json.loads(match.group(1))

    for boundary in ([args.boundary] if args.boundary else BOUNDARIES):
        name = 'sync-stop-' + uuid.uuid4().hex[:12]
        seeded = phone(name, 'seed-local')
        identity = seeded['replica']
        library = output / boundary
        run('archive', library, 'restore', args.fixtures.resolve() / 'format-3.stackbackup')
        started = run('sync', library, 'start', identity)
        baseline = output / f'{boundary}.stackexchange'
        run('sync', library, 'export', identity, baseline)
        put(name, 'inbox.stackexchange', baseline.read_bytes())
        review = phone(name, 'review-adoption')
        mapped = review['mappings']['documents'][seeded['document']]
        process = subprocess.Popen(invocation(name, 'stop-import', boundary=boundary, reviewed='true'),
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        killed = False
        try:
            deadline = time.monotonic() + 30
            while True:
                marker = subprocess.run([*adb, 'exec-out', 'run-as', args.package, 'cat',
                                         f'files/checks/{name}/files/stop-ready'], capture_output=True, timeout=5)
                if marker.returncode == 0 and marker.stdout.decode() == boundary:
                    break
                assert process.poll() is None, process.communicate()
                assert time.monotonic() < deadline, f'Did not reach {boundary}'
                time.sleep(0.1)
            # This package is asserted above to be a separate Checks app. Its
            # marker is durable before the actual Android process is stopped.
            subprocess.run([*adb, 'shell', 'am', 'force-stop', args.package], check=True,
                           capture_output=True, timeout=15)
            killed = True
            stdout, stderr = process.communicate(timeout=15)
            (output / f'{boundary}-stopped.log').write_text(stdout + '\n' + stderr)
        finally:
            if process.poll() is None:
                process.kill();process.communicate()
        assert killed
        after = phone(name, 'inspect')
        committed = boundary in ('committed', 'receipt')
        assert after['adopted'] == committed
        assert len(after['documents']) == (6 if committed else 1)
        old = read(f'files/checks/{name}/files/documents/{seeded["document"]}/source')
        assert hashlib.sha256(old).hexdigest() == seeded['original_sha256']
        if committed:
            assert any(doc['id'] == mapped and doc['sha256'] == seeded['original_sha256']
                       for doc in after['documents'])
            phone(name, 'import')
        else:
            assert after['documents'][0]['id'] == seeded['document']
            assert after['documents'][0]['title'] == 'Android original atlas'
            phone(name, 'adopt-reviewed')
        resumed = phone(name, 'inspect')
        assert len(resumed['documents']) == 6 and len(resumed['files']) == 8
        assert any(doc['id'] == mapped and doc['sha256'] == seeded['original_sha256'] for doc in resumed['documents'])
        phone(name, 'import')
        assert phone(name, 'inspect') == resumed, 'Replay changed completed adoption'
        phone(name, 'export', peer=started['replica'])
        forwarded = output / f'{boundary}-forwarded.stackexchange'
        forwarded.write_bytes(read(f'files/checks/{name}/files/outbox.stackexchange'))
        run('sync', library, 'import', forwarded)
        rust = run('library', library, 'manifest')
        assert {doc['id']: doc['title'] for doc in resumed['documents']} == {
            doc['id']: doc['title'] for doc in rust['documents']}
        assert sorted(resumed['files'], key=lambda file: file['path']) == sorted(rust['files'], key=lambda file: file['path'])
        cases.append({'boundary': boundary, 'passed': True, 'actual_process_stop': True,
                      'committed_before_stop': committed, 'replica': name,
                      'resumed_documents': 6, 'originals': 8})
        (output / 'progress.json').write_text(json.dumps(cases, indent=2) + '\n')
    root = Path(__file__).resolve().parents[2]
    sources = list((root / 'app/src/main/java/org/stackdocs/app').glob('Sync*.java'))
    sources += [Path(__file__).resolve(), root / 'app/src/androidTest/java/org/stackdocs/app/SyncDeviceChecks.java']
    report = {'status': 'passed', 'android_runtime': True, 'package': args.package, 'cases': cases,
              'source_sha256': {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources}}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
