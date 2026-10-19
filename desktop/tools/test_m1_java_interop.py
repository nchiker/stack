#!/usr/bin/env python3
"""Synthetic Java production writer/validator ↔ Rust repository golden checks."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import struct
import subprocess


def canonical(value):
    value = copy.deepcopy(value)
    value['created_at'] = 0
    for key, rows in value.items():
        if isinstance(rows, list) and key != 'stacks':
            rows.sort(key=lambda row: json.dumps(row, sort_keys=True, ensure_ascii=False))
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cli', type=Path)
    parser.add_argument('java_classes', type=Path)
    parser.add_argument('fixtures', type=Path)
    parser.add_argument('new_output', type=Path)
    args = parser.parse_args()
    args.new_output.mkdir()
    root = args.new_output.resolve()
    fixtures = args.fixtures.resolve()
    cli = str(args.cli.resolve())
    java = ['java', '-cp', str(args.java_classes.resolve()), 'org.stackdocs.app.ArchiveHost']

    def run(command):
        return subprocess.check_output(list(map(str, command)), stderr=subprocess.STDOUT, text=True)

    expected = json.loads((fixtures / 'manifest-v3.json').read_text())
    cases = []
    for version in [1, 2, 3]:
        java_archive = root / f'java-{version}.stackbackup'
        run([*java, 'write', fixtures, version, java_archive])
        library = root / f'rust-from-java-{version}'
        run([cli, 'archive', library, 'restore', java_archive])
        graph = json.loads(run([cli, 'library', library, 'manifest']))
        old_expected = copy.deepcopy(expected)
        if version == 1:
            for doc in old_expected['documents']:
                doc.update(notes='', notes_revision=0, metadata_revision=0)
            for key in ['details', 'tags', 'document_tags']:
                old_expected[key] = []
        assert canonical(graph) == canonical(old_expected), f'Java {version} → Rust graph differs'
        exported = root / f'rust-{version}.stackbackup'
        run([cli, 'archive', library, 'export', exported])
        accepted = root / f'java-read-rust-{version}'
        run([*java, 'read', exported, accepted])
        java_graph = json.loads((accepted / 'manifest-v3.json').read_text())
        assert canonical(java_graph) == canonical(old_expected), f'Rust → Java {version} graph differs'
        evidence = json.loads((accepted / 'evidence.json').read_text())
        assert evidence == graph['decision_evidence'], 'Java/Rust UTF-16 evidence digest differs'
        second = root / f'rust-again-{version}'
        run([cli, 'archive', second, 'restore', accepted / 'java-rewritten.stackbackup'])
        again = json.loads(run([cli, 'library', second, 'manifest']))
        assert canonical(again) == canonical(old_expected)
        # The independent Rust envelope also goes through the real Java validator/writer.
        direct = root / f'java-direct-{version}'
        run([*java, 'read', fixtures / f'format-{version}.stackbackup', direct])
        assert canonical(json.loads((direct / 'manifest-v3.json').read_text())) == canonical(old_expected)
        for file in expected['files']:
            for parent in [library, accepted, second, direct]:
                assert hashlib.sha256((parent / file['path']).read_bytes()).hexdigest() == file['sha256']
        cases.append({'source_version': version, 'java_rust_java_rust_graph_equal': True,
                      'rust_java_graph_equal': True, 'java_rust_evidence_equal': True, 'all_original_hashes_equal': True})

    # Differential parser semantics use original bytes and an identical altered manifest.
    def archive(value, output, raw=None):
        metadata = raw if raw is not None else json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()
        with output.open('xb') as stream:
            stream.write(b'STACKBK\x01' + struct.pack('>i', len(metadata)) + hashlib.sha256(metadata).digest() + metadata)
            for entry in value['files']:
                path = entry['path'].encode()
                stream.write(struct.pack('>H', len(path)) + path + struct.pack('>q', entry['length']))
                stream.write((fixtures / entry['path']).read_bytes())

    differences = []
    mutations = [
        ('future', ['version'], 4, False), ('wrong-schema', ['schema'], 7, False),
        ('float-integer', ['documents', 0, 'page_count'], 2.0, False),
        ('bool-integer', ['documents', 0, 'page_count'], True, False),
        ('utf16-limit', ['documents', 0, 'notes'], '🦀' * 10001, False),
        ('amount-exponent', ['details', 0, 'value'], '1e2', False),
        ('amount-extra-precision', ['details', 0, 'value'], '0.1234567', False),
        ('currency-unknown', ['details', 0, 'qualifier'], 'unknown', True),
        ('currency-lowercase', ['details', 0, 'qualifier'], 'usd', False),
        ('currency-invalid', ['details', 0, 'qualifier'], 'ZZZ', False),
        ('invalid-date', ['details', 4, 'value'], '2023-02-29', False),
        ('julian-date', ['details', 4, 'value'], '1500-02-29', True),
        ('cutover-gap', ['details', 4, 'value'], '1582-10-10', False),
        ('year-zero', ['details', 2, 'value'], '0000', False),
        ('turkish-case', ['stacks', 1, 'name'], 'invoices', False),
        ('bad-crop', ['pages', 0, 'crop'], '[0,0,0,1,1,1,1,0]', False),
        ('string-crop', ['pages', 0, 'crop'], '["0","0","1","0","1","1","0","1"]', True),
        ('unknown-origin', ['details', 0, 'origin'], 'automatic', False),
        ('organization-mismatch', ['details', 8, 'value'], 'Other', False),
        ('missing-org-relation', ['document_tags'], [], False),
        ('historical-evidence', ['details', 0, 'evidence'], '{"page_id":"historical-opaque"}', True),
    ]
    for label, keys, replacement, accepted in mutations:
        graph = copy.deepcopy(expected)
        target = graph
        for key in keys[:-1]:
            target = target[key]
        target[keys[-1]] = replacement
        input_path = root / f'case-{label}.stackbackup'
        archive(graph, input_path)
        rust = subprocess.run([cli, 'archive', str(root / f'case-rust-{label}'), 'validate', str(input_path)], capture_output=True)
        host = subprocess.run([*java, 'read', str(input_path), str(root / f'case-java-{label}')], capture_output=True)
        (root / f'case-{label}.log').write_bytes(b'Rust:\n' + rust.stderr + b'\nJava:\n' + host.stderr)
        assert (rust.returncode == 0) == accepted, (label, rust.stderr)
        assert (host.returncode == 0) == accepted, (label, host.stderr)
        differences.append({'case': label, 'both_accepted': accepted})
    currencies = run([*java, 'currencies']).split()
    rust_currencies = Path(__file__).parents[1] / 'crates/stack-archive/src/currency_codes.txt'
    assert currencies == rust_currencies.read_text().split()
    report = {'fixture': 'stack-m1-v1', 'fixture_archive_sha256': hashlib.sha256((fixtures / 'format-3.stackbackup').read_bytes()).hexdigest(),
              'roundtrips': cases, 'differential_cases': differences, 'currency_codes_compared': len(currencies),
              'scope': 'Host production Java metadata validator/writer and digest; Android source decoding, SQLite restore and device runtime excluded.'}
    (root / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
