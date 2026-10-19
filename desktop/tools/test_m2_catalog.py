#!/usr/bin/env python3
"""Native synthetic search/catalog scenario; message injection, not physical input."""
import argparse
import json
import os
from pathlib import Path
import sqlite3
import subprocess
from test_m2_browser import digest, saved, prepare_focus


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.absolute()
    if not output.is_relative_to('/tmp'):
        parser.error('Use a fresh disposable /tmp output')
    os.umask(0o077)
    output.mkdir(mode=0o700)
    desktop = Path(__file__).resolve().parents[1]
    cli, binary = [desktop / f'target/debug/{name}' for name in ('stack-cli', 'stack-desktop')]
    env = dict(os.environ)
    prepare_focus(desktop, output, env)
    env['STACK_PDFIUM_LIBRARY'] = str(desktop / 'runtime/pdfium/lib/libpdfium.so')
    for name in ('DATA', 'CONFIG', 'CACHE', 'STATE'):
        folder = output / 'xdg' / name.lower()
        folder.mkdir(parents=True)
        env[f'XDG_{name}_HOME'] = str(folder)
    def run(command, name):
        with (output / (name + '.log')).open('w') as log:
            subprocess.run(list(map(str, command)), env=env, stdout=log,
                           stderr=subprocess.STDOUT, check=True, timeout=90)
    render, archive, library = [output / name for name in ('render', 'archive', 'library')]
    run([cli, 'fixtures', render], 'fixtures')
    run([cli, 'm1-fixtures', render, archive], 'archives')
    run([cli, 'archive', library, 'restore', archive / 'format-3.stackbackup'], 'seed')
    before = saved(library)
    with sqlite3.connect(f"file:{library / 'library.sqlite3'}?mode=ro", uri=True) as db:
        inactive_before = db.execute("SELECT count(*) FROM objects WHERE kind='stacks' AND active=0").fetchone()[0]
    run([binary, '--library', library, '--browser-smoke-fixtures', render,
         '--browser-smoke-case', 'catalog', '--smoke-output', output / 'catalog'], 'catalog')
    trace = json.loads((output / 'catalog/01-search-later-page.json').read_text())
    assert trace['query'] == 'laterpagesentinel' and trace['sort_name']
    assert trace['document']['token']['id'].endswith('000000000001')
    assert trace['saved_page_index'] == 1
    assert trace['document']['pages'][1]['id'].endswith('000000000065')
    assert trace['document']['pages'][1]['source_page'] == 1
    catalog = json.loads((output / 'catalog/02-saved-catalog.json').read_text())['stacks']
    assert len(catalog) == 3
    assert [s['id'] for s in catalog] == ['custom-first', 'receipts', 'custom-last']
    assert catalog[2]['color'] == -15584170 and not catalog[2]['visible']
    after = saved(library)
    assert before['documents'] == after['documents']
    assert before['originals'] == after['originals']
    document_objects = lambda snapshot: [r for r in snapshot['objects'] if r[0] != 'stacks']
    assert document_objects(before) == document_objects(after)
    assert len(after['journal']) == len(before['journal']) + 8
    with sqlite3.connect(f"file:{library / 'library.sqlite3'}?mode=ro", uri=True) as db:
        kinds = [json.loads(row[0])['kind'] for row in db.execute('SELECT payload FROM journal ORDER BY sequence')][-8:]
        assert kinds == ['stack.create', 'stack.rename', 'stack.color', 'stack.visibility',
                         'stack.order', 'stack.delete', 'stack.color', 'stack.visibility']
        assert db.execute("SELECT count(*) FROM objects WHERE kind='stacks' AND active=0").fetchone()[0] == inactive_before + 1
    prefs = output / 'xdg/config/org.stackdocs.desktop/view.json'
    assert json.loads(prefs.read_text())['sort'] == 'name'
    run([binary, '--library', library, '--browser-smoke-fixtures', render,
         '--browser-smoke-case', 'catalog-reopen', '--smoke-output', output / 'reopen',
         '--theme', 'dark', '--size', '680x680'], 'reopen')
    assert saved(library) == after
    reopen = json.loads((output / 'reopen/01-saved-pdf.json').read_text())
    assert reopen['sort_name']
    assert reopen['stacks'] == catalog
    report = {'synthetic_only': True, 'interaction': 'application message injection; native window',
              'binary_sha256': digest(binary), 'later_page_search': True,
              'journaled_actions': kinds, 'document_rows_original_hashes_unchanged': True,
              'catalog_and_sort_survive_restart': True, 'native_captures': 6,
              'limits': ['not keyboard/pointer or accessibility acceptance']}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS native later-page search, eight journaled catalog actions and restart')


if __name__ == '__main__':
    main()
