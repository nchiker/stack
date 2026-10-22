#!/usr/bin/env python3
"""Actual reviewed Android/Rust adoption; only fresh synthetic Checks roots."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import shlex
import struct
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--adb-port', required=True, type=int)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--cli', required=True, type=Path)
    parser.add_argument('--fixtures', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--matched-only', action='store_true')
    parser.add_argument('--resume-name')
    parser.add_argument('--resume-library', type=Path)
    args = parser.parse_args()
    if bool(args.resume_name) != bool(args.resume_library) or args.resume_name and not args.matched_only:
        parser.error('Resume requires --matched-only, --resume-name and --resume-library together')
    if args.resume_name and (not re.fullmatch(r'sync-adopt-[a-f0-9]+', args.resume_name)
                             or not args.resume_library.resolve().is_relative_to('/tmp')):
        parser.error('Only synthetic adoption roots may resume')
    if not re.fullmatch(r'org\.stackdocs\.app\.checks\.[a-z][a-z0-9_]{0,31}', args.package):
        parser.error('Separate Checks namespace required')
    output = args.output.resolve()
    if not output.is_relative_to('/tmp'):
        parser.error('Fresh disposable /tmp output required')
    assert json.loads((args.fixtures / 'synthetic-fixture.json').read_text()) == {
        'fixture': 'stack-m1', 'version': 1, 'license': 'CC0-1.0'}
    output.mkdir(mode=0o700)
    adb = [str(args.adb.resolve()), '-P', str(args.adb_port), '-s', args.serial]
    cli = str(args.cli.resolve())
    instrument = f'{args.package}.test/org.stackdocs.app.ScannerChecks'
    transcripts, checks = [], []

    def shell(*parts, data=None):
        return subprocess.check_output([*adb, 'shell', '-T', shlex.join(parts)], input=data, timeout=180)

    def run(*parts):
        return json.loads(subprocess.check_output([cli, *map(str, parts)], text=True, timeout=180))

    def read(relative):
        return subprocess.check_output([*adb, 'exec-out', 'run-as', args.package, 'cat', relative], timeout=60)

    def put(replica, name, data):
        relative = f'files/checks/{replica}/files/{name}'
        shell('run-as', args.package, 'sh', '-c', f'cat > {shlex.quote(relative)}', data=data)
        assert read(relative) == data

    def phone(replica, phase, **values):
        command = ['am', 'instrument', '-w', '-r', '-e', 'syncPhase', phase, '-e', 'replicaName', replica]
        for key, value in values.items():
            command += ['-e', key, str(value)]
        text = shell(*command, instrument).decode()
        transcripts.append({'replica': replica, 'phase': phase, 'output': text})
        (output / 'instrumentation.json').write_text(json.dumps(transcripts, indent=2) + '\n')
        assert 'FAIL after' not in text and 'INSTRUMENTATION_CODE: -1' in text, text
        match = re.search(r'PASS \d+: sync ' + re.escape(phase) + r': (\{[^\n]+\})', text)
        assert match, text
        return json.loads(match.group(1))

    def passed(label):
        checks.append(label)
        (output / 'progress.json').write_text(json.dumps(checks, indent=2) + '\n')

    def setup(label):
        name = 'sync-adopt-' + uuid.uuid4().hex[:12]
        identity = phone(name, 'identity')['replica']
        library = output / label
        run('archive', library, 'restore', args.fixtures.resolve() / 'format-3.stackbackup')
        started = run('sync', library, 'start', identity)
        baseline = output / f'{label}.stackexchange'
        run('sync', library, 'export', identity, baseline)
        put(name, 'inbox.stackexchange', baseline.read_bytes())
        return name, library, started, baseline

    def separate_checks():
        name, library, state, baseline = setup('separate')
        local = phone(name, 'seed-local')
        review = phone(name, 'review-adoption')
        proposed = copy.deepcopy(review)
        del review['mappings']['documents'][local['document']]
        put(name, 'review.json', json.dumps(review).encode())
        assert phone(name, 'reject-review')['rejected']
        passed('Incomplete identity mapping is rejected before Android adoption')
        put(name, 'review.json', json.dumps(proposed).encode())
        raw = baseline.read_bytes()
        length = struct.unpack('>I', raw[8:12])[0]
        changed = json.loads(raw[12:12 + length])
        changed['acknowledged'] = {state['replica']: 0}
        encoded = json.dumps(changed, separators=(',', ':')).encode()
        put(name, 'inbox.stackexchange', raw[:8] + struct.pack('>I', len(encoded)) + encoded + raw[12 + length:])
        assert phone(name, 'reject-review')['rejected']
        put(name, 'inbox.stackexchange', raw)
        passed('Semantically replaced valid exchange is rejected after source review')
        phone(name, 'rename', document=local['document'], title='Local title edited after review')
        assert phone(name, 'reject-review')['rejected']
        passed('Local edit invalidates adoption review without losing the edit')
        review = phone(name, 'review-adoption')
        assert not phone(name, 'adopt-reviewed')['conflicts']
        inspected = phone(name, 'inspect')
        mapped = review['mappings']['documents'][local['document']]
        saved = next(doc for doc in inspected['documents'] if doc['id'] == mapped)
        assert saved['title'] == 'Local title edited after review' and saved['sha256'] == local['original_sha256']
        assert len(inspected['documents']) == 6 and len(inspected['files']) == 8
        old_original = read(f'files/checks/{name}/files/documents/{local["document"]}/source')
        assert hashlib.sha256(old_original).hexdigest() == local['original_sha256']
        phone(name, 'export', peer=state['replica'])
        forwarded = output / 'separate-forwarded.stackexchange'
        forwarded.write_bytes(read(f'files/checks/{name}/files/outbox.stackexchange'))
        run('sync', library, 'import', forwarded)
        rust = run('library', library, 'manifest')
        assert {doc['id']: doc['title'] for doc in inspected['documents']} == {
            doc['id']: doc['title'] for doc in rust['documents']}
        assert sorted(inspected['files'], key=lambda file: file['path']) == sorted(rust['files'], key=lambda file: file['path'])
        assert not run('sync', library, 'status')['conflicts']
        passed('Nonempty Android library keeps six distinct documents, all originals and the later edit; Rust converges')

        return [name]

    replicas = [] if args.matched_only else separate_checks()
    if args.resume_name:
        matched, library = args.resume_name, args.resume_library.resolve()
        identity = phone(matched, 'identity')['replica']
        state = run('sync', library, 'status')
        assert {doc['id'] for doc in run('library', library, 'manifest')['documents']} == {
            f'00000000-0000-0000-0000-{number:012d}' for number in range(1, 6)}
        baseline = output / 'matched-resume.stackexchange'
        run('sync', library, 'export', identity, baseline)
        put(matched, 'inbox.stackexchange', baseline.read_bytes())
    else:
        matched, library, state, baseline = setup('matched')
        put(matched, 'authored.stackbackup', (args.fixtures / 'format-3.stackbackup').read_bytes())
        phone(matched, 'restore-local')
    replicas.append(matched)
    assert phone(matched, 'inspect')['max_suggestion_generation'] <= 9_007_199_254_740_991
    passed('Restored suggestion queue generations remain within the exact sync integer range')
    doc = run('library', library, 'manifest')['documents'][0]
    phone(matched, 'rename', document=doc['id'], title='Explicit matched Android title')
    review = phone(matched, 'review-adoption')
    assert phone(matched, 'reject-review')['rejected']
    passed('Unmatched equal organization names are rejected before nonempty adoption commits')
    for mapping in review['mappings'].values():
        for identity in mapping:
            mapping[identity] = identity
    for catalog in review['catalog_names'].values():
        for item in catalog.values():
            item['chosen'] = item['original']
    put(matched, 'review.json', json.dumps(review).encode())
    phone(matched, 'adopt-reviewed')
    inspected = phone(matched, 'inspect')
    key = 'documents/' + json.dumps([doc['id']], separators=(',', ':')) + '/title'
    assert {item['value']['title'] for item in inspected['conflicts'][key]} == {
        doc['title'], 'Explicit matched Android title'}
    assert len(inspected['documents']) == 5 and len(inspected['files']) == 7
    phone(matched, 'export', peer=state['replica'])
    forwarded = output / 'matched-forwarded.stackexchange'
    forwarded.write_bytes(read(f'files/checks/{matched}/files/outbox.stackexchange'))
    run('sync', library, 'import', forwarded)
    assert run('sync', library, 'status')['conflicts'] == inspected['conflicts']
    rust = run('library', library, 'manifest')
    assert sorted(inspected['files'], key=lambda file: file['path']) == sorted(rust['files'], key=lambda file: file['path'])
    passed('Explicit document/page/catalog/tag/scan matches retain concurrent titles for identical Android/Rust conflict review')
    root = Path(__file__).resolve().parents[2]
    sources = list((root / 'app/src/main/java/org/stackdocs/app').glob('Sync*.java'))
    sources += [Path(__file__).resolve(), root / 'app/src/androidTest/java/org/stackdocs/app/SyncDeviceChecks.java']
    report = {'status': 'passed', 'android_runtime': True, 'checks': checks,
              'package': args.package, 'replicas': replicas,
              'source_sha256': {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources}}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
