#!/usr/bin/env python3
"""Production Java graph parity with actual Rust exchange cells and bad partials.

Requires the existing java_archive_host classes and authored M1 fixtures. No
Android device or personal library is opened. A pending malformed operation must
be rejected before inbox persistence, even when a complete projection is absent.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cli', required=True, type=Path)
    parser.add_argument('--archive-classes', required=True, type=Path)
    parser.add_argument('--fixtures', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to('/tmp'):
        parser.error('Choose a fresh disposable /tmp output')
    marker = json.loads((args.fixtures / 'synthetic-fixture.json').read_text())
    if marker != {'fixture': 'stack-m1', 'version': 1, 'license': 'CC0-1.0'}:
        parser.error('Authored M1 fixtures required')
    output.mkdir(mode=0o700)
    root = Path(__file__).resolve().parents[2]
    sources = [root / 'app/src/main/java/org/stackdocs/app' / name
               for name in ('ArchiveJson.java', 'SyncProtocol.java', 'SyncGraph.java')]
    sources.append(root / 'desktop/tools/java/SyncGraphHost.java')
    compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run([*compiler, '-encoding', 'UTF-8', '-cp', str(args.archive_classes),
                    '-d', str(output), *map(str, sources)], check=True)
    host = ['java', '-cp', f'{output}:{args.archive_classes.resolve()}', 'org.stackdocs.app.SyncGraphHost']
    cli = str(args.cli.resolve())

    def command(*parts):
        return json.loads(subprocess.check_output([cli, *map(str, parts)], text=True))

    def java(mode, path):
        return json.loads(subprocess.check_output([*host, mode, str(path)], text=True))

    library = output / 'source'
    command('archive', library, 'restore', args.fixtures.resolve() / 'format-3.stackbackup')
    peer = str(uuid.uuid4())
    initial = command('sync', library, 'start', peer)
    baseline = output / 'baseline.stackexchange'
    command('sync', library, 'export', peer, baseline)
    raw = baseline.read_bytes()
    assert raw[:8] == b'STACKEX\x01'
    length = struct.unpack('>I', raw[8:12])[0]
    header = json.loads(raw[12:12 + length])
    cells = header['operations'][0]['changes']
    graph = command('library', library, 'manifest')
    graph_path = output / 'manifest.json'
    graph_path.write_text(json.dumps(graph))
    assert java('cells', graph_path) == cells, 'Java/Rust graph cell projection differs'
    cells_path = output / 'cells.json'
    cells_path.write_text(json.dumps(cells))
    projected = java('project', cells_path)

    def normalized(value):
        value = copy.deepcopy(value)
        value['created_at'] = 0
        for key, rows in value.items():
            if isinstance(rows, list) and key != 'stacks':
                rows.sort(key=lambda row: json.dumps(row, sort_keys=True))
        return value

    assert normalized(projected) == normalized(graph), 'Java graph round trip loses portable values/evidence'
    page = next(key for key in cells if key.startswith('pages/') and key.endswith('/appearance'))
    notes = next(key for key in cells if key.startswith('documents/') and key.endswith('/notes'))
    visible = next(key for key in cells if key.startswith('stacks/') and key.endswith('/visible'))
    detail = next(key for key, value in cells.items()
                  if key.startswith('details/') and value['kind'] == 'amount')
    vectors = [('valid', None, None, None, True)]
    vectors += [(name, key, field, value, False) for name, key, field, value in [
        ('crop-object', page, 'crop', {}), ('rotation-45', page, 'rotation', 45),
        ('flatten-boolean', page, 'flatten', True), ('bad-enhancement', page, 'enhancement', 'unsupported'),
        ('nonconvex-crop', page, 'crop', '[0,0,0,1,1,1,1,0]'),
        ('notes-number', notes, 'notes', 7), ('notes-overlong', notes, 'notes', 'x' * 20001),
        ('visible-boolean', visible, 'visible', True), ('detail-exponent', detail, 'value', '1e2'),
        ('detail-unknown-state', detail, 'state', 'unexpected'),
    ]]
    cases = []
    for name, key, field, value, expected in vectors:
        changes = copy.deepcopy(cells) if key is None else {key: copy.deepcopy(cells[key])}
        if key is not None:
            changes[key][field] = value
        candidate = output / f'{name}.json'
        candidate.write_text(json.dumps(changes))
        accepted = java('validate', candidate)['accepted']
        # Gap sequence3 deliberately cannot project yet; structure must still be
        # rejected before either platform durably retains a pending inbox value.
        mutated = copy.deepcopy(header)
        operation = copy.deepcopy(header['operations'][0])
        operation['id']['sequence'] = 3
        operation['context'] = {operation['id']['replica']: 2}
        operation['changes'] = changes
        mutated['operations'] = [header['operations'][0], operation]
        metadata = json.dumps(mutated, separators=(',', ':')).encode()
        envelope = output / f'{name}.stackexchange'
        envelope.write_bytes(raw[:8] + struct.pack('>I', len(metadata)) + metadata + raw[12 + length:])
        target = output / f'destination-{name}'
        target_id = command('sync', target, 'status')['replica']
        command('sync', target, 'adopt', baseline, initial['library'], header['sender'], target_id)
        before = command('sync', target, 'status')
        process = subprocess.run([cli, 'sync', str(target), 'import', str(envelope)],
                                 capture_output=True, text=True)
        rust_accepted = process.returncode == 0
        after = command('sync', target, 'status')
        durable_ok = after['pending_gaps'] == 1 if expected else after == before
        cases.append({'name': name, 'java_accepted': accepted, 'expected_java_accepted': expected,
                      'rust_accepted': rust_accepted, 'expected_rust_accepted': expected,
                      'durable_state_correct': durable_ok,
                      'passed': accepted == expected and rust_accepted == expected and durable_ok})
    report = {'status': 'passed' if all(case['passed'] for case in cases) else 'failed',
              'graph_roundtrip_equal': True, 'cells_equal': True, 'cases': cases,
              'android_runtime': False,
              'source_sha256': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
    if report['status'] != 'passed':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
