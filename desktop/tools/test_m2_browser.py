#!/usr/bin/env python3
"""Persistent synthetic COSMIC browser; message injection, not pointer/keyboard proof."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def saved(root):
    # Only the disposable, authored library created below is inspected.
    with sqlite3.connect(f"file:{root / 'library.sqlite3'}?mode=ro", uri=True) as db:
        result = {table: db.execute(sql).fetchall() for table, sql in {
            'documents': 'SELECT id,payload,revision,incarnation FROM documents ORDER BY id',
            'objects': 'SELECT kind,key,payload,active FROM objects ORDER BY kind,key',
            'journal': 'SELECT sequence,sha256 FROM journal ORDER BY sequence',
        }.items()}
    result['originals'] = {str(p.relative_to(root)): digest(p)
                           for p in sorted((root / 'originals').glob('*/*')) if p.name != '.owner'}
    return result


def prepare_focus(desktop, output, env):
    """Build only from the toolkit already pinned/built by Cargo; no new dependency."""
    libraries = list((desktop / 'target/debug/deps').glob('libcosmic_client_toolkit-*.rlib'))
    if len(libraries) != 1:
        raise RuntimeError('Expected one pinned COSMIC toolkit rlib; rebuild in a clean target directory')
    binary = output / 'focus-synthetic'
    subprocess.run(['rustc', '+stable', '--edition=2024', desktop / 'tools/focus_synthetic.rs',
                    '-L', f'dependency={desktop / "target/debug/deps"}',
                    '--extern', f'cosmic_client_toolkit={libraries[0]}', '-o', binary],
                   check=True, env=env)
    env['STACK_SYNTHETIC_FOCUS_HELPER'] = str(binary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    output = args.output.absolute()
    if not output.is_relative_to('/tmp'):
        parser.error('Use a new disposable /tmp output directory')
    output.mkdir(mode=0o700)
    desktop = Path(__file__).resolve().parents[1]
    prepare_focus(desktop, output, env := dict(os.environ))
    binary = desktop / 'target/debug/stack-desktop'
    cli = desktop / 'target/debug/stack-cli'
    env['STACK_PDFIUM_LIBRARY'] = str(desktop / 'runtime/pdfium/lib/libpdfium.so')
    for name in ('DATA', 'CONFIG', 'CACHE', 'STATE'):
        folder = output / 'xdg' / name.lower()
        folder.mkdir(parents=True)
        env[f'XDG_{name}_HOME'] = str(folder)
    fixtures = output / 'render-fixtures'
    archives = output / 'archive-fixtures'
    library = output / 'library'
    def run(command, log, timeout=60):
        with (output / log).open('w') as stream:
            subprocess.run(list(map(str, command)), env=env, stdout=stream,
                           stderr=subprocess.STDOUT, check=True, timeout=timeout)
    run([cli, 'fixtures', fixtures], 'fixtures.log')
    run([cli, 'm1-fixtures', fixtures, archives], 'archives.log')
    run([cli, 'archive', library, 'restore', archives / 'format-3.stackbackup'], 'seed.log')
    before = saved(library)
    report = {'synthetic_only': True, 'input': 'authored M0/M1 fixture version 1',
              'interaction': 'application message injection; native own-window captures',
              'binary_sha256': digest(binary), 'cases': []}
    for name, theme, size, scale in [('first', 'light', '1080x780', '1'),
                                      ('reopen', 'dark', '680x680', '1')]:
        capture = output / name
        run([binary, '--library', library, '--browser-smoke-fixtures', fixtures,
             '--smoke-output', capture, '--theme', theme, '--size', size, '--text-scale', scale], name + '.log')
        images = sorted(capture.glob('*.png'))
        assert len(images) == 4, 'Native persistent captures did not complete'
        for image, doc_number, page_index, physical in zip(images, [1, 1, 3, 2], [0, 1, 0, 1], [0, 1, 0, 0]):
            trace = json.loads(image.with_suffix('.json').read_text())
            document = trace['document']
            assert document['token']['id'] == f'00000000-0000-0000-0000-{doc_number:012x}'
            assert trace['saved_page_index'] == page_index
            assert document['pages'][page_index]['source_page'] == physical
            assert document['pages'][page_index]['id']

        assert saved(library) == before, 'Browsing/reopen changed saved rows, IDs, order, history or bytes'
        report['cases'].append({'name': name, 'exit_status': 0,
                                'saved_rows_ids_order_journal_hashes_unchanged': True,
                                'captures': {p.name: digest(p) for p in images}})
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(f'PASS {name}', flush=True)

    empty = output / 'empty-library'
    run([cli, 'library', empty, 'status'], 'empty-seed.log')
    invalid = output / 'foreign-library'
    invalid.mkdir()
    (invalid / 'keep').write_bytes(b'authored synthetic unrelated bytes')
    for case, root in [('empty', empty), ('busy', library), ('error', invalid)]:
        lock = None
        if case == 'busy':
            lock = (library / 'writer.lock').open('r+')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            run([binary, '--library', root, '--browser-smoke-fixtures', fixtures,
                 '--browser-smoke-case', case, '--smoke-output', output / case], case + '.log')
        finally:
            if lock is not None:
                lock.close()
        assert (output / case / '01-library-state.png').is_file()
        assert saved(library) == before
        assert (invalid / 'keep').read_bytes() == b'authored synthetic unrelated bytes'
        report['cases'].append({'name': case, 'exit_status': 0, 'native_state_capture': True})
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(f'PASS {case}', flush=True)


if __name__ == '__main__':
    main()
