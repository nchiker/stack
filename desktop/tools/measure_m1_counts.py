#!/usr/bin/env python3
"""Measure the archive's 1,000-document/10,000-page limits with authored image bytes."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cli', type=Path)
    parser.add_argument('fixtures', type=Path)
    parser.add_argument('new_output', type=Path)
    args = parser.parse_args()
    args.new_output.mkdir()
    root = args.new_output.resolve()
    assert str(root).startswith('/tmp/')
    source = json.loads((args.fixtures / 'manifest-v3.json').read_text())
    graph = {key: [] if isinstance(value, list) else copy.deepcopy(value) for key, value in source.items()}
    graph['decision_evidence'] = {}
    graph['stacks'] = source['stacks']
    original_path = next(file['path'] for file in source['files'] if '/00000000-0000-0000-0000-000000000003/' in file['path'])
    original = (args.fixtures / original_path).read_bytes()
    sha = hashlib.sha256(original).hexdigest()
    page_template = source['pages'][-1]
    for n in range(1000):
        identity = str(uuid.UUID(int=10000 + n))
        doc = copy.deepcopy(source['documents'][2])
        doc.update(id=identity, title=f'Synthetic count {n:04}', page_count=10, initial_page_count=10, mime='image/jpeg')
        graph['documents'].append(doc)
        digest = hashlib.sha256()

        def add(value):
            value = str(value)
            digest.update(f'{len(value.encode("utf-16-le")) // 2}:'.encode())
            digest.update(value.encode())

        add(identity)
        for position in range(10):
            page = copy.deepcopy(page_template)
            page.update(id=str(uuid.UUID(int=100000 + n * 10 + position)), document_id=identity, position=position,
                        source_file='source', source_page=0, ocr_text='maxcountlatersentinel 🦀' if position == 9 else 'synthetic initial page')
            graph['pages'].append(page)
            for field in ['id', 'position', 'content_revision', 'text_revision', 'ocr_config', 'text_config', 'ocr_text']:
                add(page[field])
        graph['decision_evidence'][identity] = digest.hexdigest()
        suggestion = copy.deepcopy(source['suggestions'][2])
        suggestion['document_id'] = identity
        graph['suggestions'].append(suggestion)
        graph['files'].append({'path': f'originals/{identity}/source', 'length': len(original), 'sha256': sha})
    metadata = json.dumps(graph, ensure_ascii=False, separators=(',', ':')).encode()
    assert len(metadata) < 32 * 1024 * 1024
    archive = root / 'maximum-counts.stackbackup'
    with archive.open('xb') as output:
        output.write(b'STACKBK\x01' + struct.pack('>i', len(metadata)) + hashlib.sha256(metadata).digest() + metadata)
        for file in graph['files']:
            path = file['path'].encode()
            output.write(struct.pack('>H', len(path)) + path + struct.pack('>q', len(original)) + original)
    cli = str(args.cli.resolve())
    library = root / 'library'
    for label, command in [
        ('restore', ['archive', library, 'restore', archive]),
        ('search-with-reopen', ['library', library, 'search', 'maxcountlatersentinel'])]:
        with (root / f'{label}.json').open('w') as output:
            subprocess.run(['/usr/bin/time', '-v', '-o', str(root / f'{label}.time'), cli, *map(str, command)], stdout=output, check=True)
    status = json.loads(subprocess.check_output([cli, 'library', str(library), 'status'], text=True))
    hits = json.loads((root / 'search-with-reopen.json').read_text())
    assert (status['documents'], status['pages'], len(hits)) == (1000, 10000, 1000)
    assert all(hit['page_id'] == str(uuid.UUID(int=100000 + (int(uuid.UUID(hit['document_id'])) - 10000) * 10 + 9)) for hit in hits)
    report = {'documents': status['documents'], 'pages': status['pages'], 'later_page_results': len(hits),
              'manifest_bytes': len(metadata), 'archive_bytes': archive.stat().st_size,
              'scope': 'Debug CLI; search timing includes process launch, reopen, integrity checks and persisted FTS queries. No GUI responsiveness or full byte-limit performance claim.'}
    (root / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
