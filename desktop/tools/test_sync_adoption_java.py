#!/usr/bin/env python3
"""Production Java reviewed nonempty adoption, projected and adopted by Rust."""
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
        parser.error('Fresh disposable /tmp output required')
    assert json.loads((args.fixtures / 'synthetic-fixture.json').read_text()) == {
        'fixture': 'stack-m1', 'version': 1, 'license': 'CC0-1.0'}
    output.mkdir(mode=0o700)
    root = Path(__file__).resolve().parents[2]
    sources = [root / 'app/src/main/java/org/stackdocs/app' / file for file in
               ('ArchiveJson.java', 'SyncProtocol.java', 'SyncGraph.java', 'SyncAdoption.java')]
    sources.append(root / 'desktop/tools/java/SyncAdoptionHost.java')
    compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run([*compiler, '-encoding', 'UTF-8', '-cp', str(args.archive_classes),
                    '-d', str(output), *map(str, sources)], check=True)
    host = ['java', '-cp', f'{output}:{args.archive_classes.resolve()}', 'org.stackdocs.app.SyncAdoptionHost']
    cli = str(args.cli.resolve())

    def run(*parts):
        return json.loads(subprocess.check_output([cli, *map(str, parts)], text=True))

    library = output / 'source'
    run('archive', library, 'restore', args.fixtures.resolve() / 'format-3.stackbackup')
    replica = str(uuid.uuid4())
    started = run('sync', library, 'start', replica)
    baseline = output / 'baseline.stackexchange'
    run('sync', library, 'export', replica, baseline)
    raw = baseline.read_bytes()
    length = struct.unpack('>I', raw[8:12])[0]
    header = json.loads(raw[12:12 + length])
    header_path = output / 'header.json'
    header_path.write_text(json.dumps(header))
    local = run('library', library, 'manifest')
    local['documents'][0].update(title='Independent local title', title_origin='manual', title_user_edited=1)
    local['documents'][0]['title_decision'] += 1
    local['documents'][0]['notes'] = 'Local exact notes / \U0001f4c4\nSecond line'
    local['documents'][0]['notes_revision'] += 1
    local_path = output / 'local.json'
    local_path.write_text(json.dumps(local))

    def java(mode, review=None, local_file=local_path, source_file=header_path, check=True):
        command = [*host, mode, str(source_file), str(local_file), replica]
        if review is not None:
            command.append(str(review))
        process = subprocess.run(command, capture_output=True, text=True)
        if check:
            assert process.returncode == 0, process.stderr
            return json.loads(process.stdout)
        return process.returncode

    proposed = java('review')
    assert proposed == java('review'), 'Proposal identities must be deterministic'
    cases = []

    def normalized(graph):
        graph = copy.deepcopy(graph)
        graph['created_at'] = 0
        for key, rows in graph.items():
            if isinstance(rows, list) and key != 'stacks':
                rows.sort(key=lambda row: json.dumps(row, sort_keys=True))
        return graph

    for mode in ('separate', 'matched'):
        review = copy.deepcopy(proposed)
        # Organization names retain their saved spelling. The authored tags
        # describe identical organizations, so explicitly match those identities.
        for old in review['mappings']['tags']:
            review['mappings']['tags'][old] = old
        if mode == 'matched':
            for mapping in review['mappings'].values():
                for old in mapping:
                    mapping[old] = old
            for names in review['catalog_names'].values():
                for item in names.values():
                    item['chosen'] = item['original']
        review_path = output / f'{mode}-review.json'
        review_path.write_text(json.dumps(review))
        adopted = java('adopt', review_path)
        assert len(adopted['graph']['documents']) == len(local['documents']) * (2 if mode == 'separate' else 1)
        if mode == 'matched':
            assert any(key.endswith('/title') for key in adopted['conflicts'])
            assert any(key.endswith('/notes') for key in adopted['conflicts'])
        else:
            assert not adopted['conflicts']
        forwarded = copy.deepcopy(header)
        forwarded.update(sender=replica, operations=adopted['operations'], acknowledged=adopted['frontier'])
        encoded = json.dumps(forwarded, separators=(',', ':')).encode()
        envelope = output / f'{mode}.stackexchange'
        envelope.write_bytes(raw[:8] + struct.pack('>I', len(encoded)) + encoded + raw[12 + length:])
        destination = output / f'rust-{mode}'
        target = run('sync', destination, 'status')['replica']
        run('sync', destination, 'adopt', envelope, started['library'], started['replica'], replica, target)
        graph = run('library', destination, 'manifest')
        assert normalized(graph) == normalized(adopted['graph']), 'Java/Rust nonempty adoption projection differs'
        assert run('sync', destination, 'status')['conflicts'] == adopted['conflicts']
        cases.append({'name': mode, 'passed': True, 'documents': len(graph['documents'])})

    malformed = [('unmatched-organizations', copy.deepcopy(proposed))]
    for field in ('version', 'library', 'sender', 'source_sha256', 'local_sha256', 'local_counts', 'source_counts'):
        candidate = copy.deepcopy(proposed)
        candidate[field] = None
        malformed.append((f'stale-{field}', candidate))
    candidate = copy.deepcopy(proposed)
    candidate['mappings']['documents'].pop(next(iter(candidate['mappings']['documents'])))
    malformed.append(('missing-mapping', candidate))
    candidate = copy.deepcopy(proposed)
    ids = list(candidate['mappings']['documents'])
    candidate['mappings']['documents'][ids[1]] = candidate['mappings']['documents'][ids[0]]
    malformed.append(('duplicate-mapping', candidate))
    candidate = copy.deepcopy(proposed)
    page = local['pages'][0]
    candidate['mappings']['pages'][page['id']] = page['id']
    malformed.append(('matched-page-wrong-owner', candidate))
    for name, candidate in malformed:
        path = output / f'{name}.json'
        path.write_text(json.dumps(candidate))
        assert java('adopt', path, check=False) != 0, f'{name} accepted'
        cases.append({'name': name, 'passed': True})
    report = {'status': 'passed', 'cases': cases, 'android_runtime': False,
              'source_sha256': {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                                for path in sources}}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
