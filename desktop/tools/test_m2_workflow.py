#!/usr/bin/env python3
"""Authored integrated native workflow; injected messages/picker results, no physical input."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import struct
import subprocess
from test_m2_browser import digest, prepare_focus, saved
from test_m1_java_interop import canonical


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-classes', type=Path, required=True)
    parser.add_argument('--theme', choices=('light', 'dark'), default='light')
    parser.add_argument('--size', default='1080x780')
    parser.add_argument('--text-scale', choices=('1', '2'), default='1')
    args = parser.parse_args()
    output = args.output.absolute()
    if not output.is_relative_to('/tmp'):
        parser.error('Use a fresh disposable /tmp output')
    os.umask(0o077)
    output.mkdir(mode=0o700)
    desktop = Path(__file__).resolve().parents[1]
    cli, binary = [desktop / f'target/debug/{name}' for name in ('stack-cli', 'stack-desktop')]
    env = dict(os.environ)
    env['STACK_PDFIUM_LIBRARY'] = str(desktop / 'runtime/pdfium/lib/libpdfium.so')
    prepare_focus(desktop, output, env)
    for name in ('DATA', 'CONFIG', 'CACHE', 'STATE'):
        folder = output / 'xdg' / name.lower()
        folder.mkdir(parents=True)
        env[f'XDG_{name}_HOME'] = str(folder)
    report = {'synthetic_only': True, 'interaction': 'application message/picker-result injection',
              'native_dialogs_or_physical_input': False, 'binary_sha256': digest(binary), 'status': 'running'}

    def run(command, name, timeout=180):
        with (output / f'{name}.log').open('w') as log:
            subprocess.run(list(map(str, command)), env=env, stdout=log,
                           stderr=subprocess.STDOUT, check=True, timeout=timeout)
    try:
        render, archive, library = [output / name for name in ('render', 'archive', 'library')]
        run([cli, 'fixtures', render], 'fixtures')
        run([cli, 'm1-fixtures', render, archive], 'archives')
        run([cli, 'archive', library, 'restore', archive / 'format-3.stackbackup'], 'seed')
        before = saved(library)
        run([binary, '--library', library, '--browser-smoke-fixtures', render,
             '--browser-smoke-case', 'workflow', '--smoke-output', output / 'workflow',
             '--theme', args.theme, '--size', args.size, '--text-scale', args.text_scale], 'workflow')
        dimensions = lambda path: struct.unpack('>II', path.read_bytes()[16:24])
        expected_size = tuple(map(int, args.size.split('x')))
        assert all(dimensions(path) == expected_size for path in (output / 'workflow').glob('*.png'))
        traces = [json.loads(p.read_text()) for p in sorted((output / 'workflow').glob('*.json'))]
        assert len(traces) == 4
        review, editor, later, exported = traces
        assert '5 documents to add' in review['transfer_status']
        assert editor['document']['title'] == 'Edited synthetic title'
        assert editor['document']['notes'] == 'M2 authored notes\n0000123; café 🦀\n'
        assert set(editor['document']['memberships']) == {'custom-first', 'custom-last', 'receipts'}
        total = next(d for d in editor['document']['details'] if d['field_id'] == 'total')
        assert (total['value'], total['qualifier']) == ('-00024.500100', 'unknown')
        assert '$24.50' in total['evidence']
        assert later['query'] == 'laterpagesentinel' and later['saved_page_index'] == 1
        page = exported['document']['pages'][0]
        assert len(exported['document']['pages']) == 1 and page['source_page'] == 1 and page['rotation'] == 90
        assert (output / 'workflow/original.pdf').read_bytes() == (render / 'two-pages.pdf').read_bytes()
        after = saved(library)
        assert all(after['originals'][path] == value for path, value in before['originals'].items())
        assert len(after['documents']) == 9 and len(after['journal']) == len(before['journal']) + 11
        with sqlite3.connect(f"file:{library / 'library.sqlite3'}?mode=ro", uri=True) as db:
            kinds = [json.loads(row[0])['kind'] for row in db.execute('SELECT payload FROM journal ORDER BY sequence')][-11:]
            assert kinds[:4] == ['archive.restore'] * 4
        run([cli, 'library', library, 'manifest'], 'final-manifest')
        graph = json.loads((output / 'final-manifest.log').read_text())
        portable = output / 'workflow/library.stackbackup'
        with portable.open('rb') as stream:
            assert stream.read(8) == b'STACKBK\x01'
            size = struct.unpack('>i', stream.read(4))[0]
            checksum, metadata = stream.read(32), stream.read(size)
            assert hashlib.sha256(metadata).digest() == checksum
            assert canonical(json.loads(metadata)) == canonical(graph)
        run(['java', '-cp', args.java_classes.absolute(), 'org.stackdocs.app.ArchiveHost',
             'read', portable, output / 'java-read'], 'java-read')
        java_graph = json.loads((output / 'java-read/manifest-v3.json').read_text())
        assert canonical(java_graph) == canonical(graph)
        run([binary, '--library', library, '--browser-smoke-fixtures', render,
             '--browser-smoke-case', 'workflow-reopen', '--browser-smoke-expected', output / 'final-manifest.log',
             '--smoke-output', output / 'reopen', '--theme', 'dark', '--size', '680x680',
             '--text-scale', args.text_scale], 'reopen')
        assert dimensions(output / 'reopen/01-reopened-workflow.png') == (680, 680)
        assert saved(library) == after
        reopened = json.loads((output / 'reopen/01-reopened-workflow.json').read_text())
        assert reopened['document'] == exported['document']
        report.update(status='passed', native_captures=5, imported_via_handlers=4,
                      theme=args.theme, size=list(expected_size), requested_ui_scale=args.text_scale,
                      journaled_mutations=11, originals_retained=True,
                      java_export_graph_equal=True, restart_exact_document_equal=True,
                      limits=['native portal interaction, keyboard/pointer, layout inspection and accessibility are separate gates',
                              'full native scaling wired through Iced; human visual/interface review deferred'])
    except Exception as error:
        report.update(status='failed', error=str(error))
        raise
    finally:
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
