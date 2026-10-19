#!/usr/bin/env python3
"""Native authored editor saves, injected stale token, subset/deletion and reopen."""
import argparse
import json
import os
from pathlib import Path
import sqlite3
import subprocess
from test_m2_browser import digest, saved, prepare_focus

TITLE = 'Edited synthetic title'
NOTES = 'M2 authored notes\n0000123; café 🦀\n'


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
                           stderr=subprocess.STDOUT, check=True, timeout=120)
    render, archive, library = [output / name for name in ('render', 'archive', 'library')]
    run([cli, 'fixtures', render], 'fixtures')
    run([cli, 'm1-fixtures', render, archive], 'archives')
    run([cli, 'archive', library, 'restore', archive / 'format-3.stackbackup'], 'seed')
    before = saved(library)
    run([binary, '--library', library, '--browser-smoke-fixtures', render,
         '--browser-smoke-case', 'editor', '--smoke-output', output / 'editor'], 'editor')
    traces = [json.loads(p.read_text()) for p in sorted((output / 'editor').glob('*.json'))]
    assert len(traces) == 5
    invalid, stale, edited, subset, after_delete = traces
    assert invalid['draft']['title'] == 'x' * 201
    assert 'Could not save' in invalid['draft']['status']
    assert invalid['document']['title'] != invalid['draft']['title']
    assert stale['draft']['title'] == TITLE and 'changed' in stale['draft']['status']
    assert edited['document']['title'] == TITLE and edited['document']['notes'] == NOTES
    assert edited['document']['memberships'] == []
    details = {d['field_id']: d for d in edited['document']['details']}
    assert details['total']['value'] == '-00024.500100' and details['total']['qualifier'] == 'unknown'
    assert details['total']['origin'] == 'manual' and '$24.50' in details['total']['evidence']
    assert details['account']['state'] == 'removed' and details['account']['value'] == '0000123'
    assert details['year']['value'] == '2024' and details['month']['value'] == '2024-02'
    assert details['old']['state'] == 'removed' and details['rejected']['state'] == 'rejected'
    for trace in (subset, after_delete):
        assert len(trace['document']['pages']) == 1
        assert trace['document']['pages'][0]['source_page'] == 1
        assert trace['document']['pages'][0]['position'] == 0
        assert trace['document']['pages'][0]['id'].endswith('000000000065')
    after = saved(library)
    assert before['originals'] == after['originals']
    assert len(after['journal']) == len(before['journal']) + 10
    with sqlite3.connect(f"file:{library / 'library.sqlite3'}?mode=ro", uri=True) as db:
        kinds = [json.loads(r[0])['kind'] for r in db.execute('SELECT payload FROM journal ORDER BY sequence')][-10:]
        assert kinds == ['document.rename', 'document.notes', 'document.filing', 'document.filing',
                         'document.detail.save', 'document.detail.remove', 'document.page.appearance',
                         'document.page.order', 'document.page.order', 'document.delete']
        assert db.execute('SELECT count(*) FROM documents WHERE active=1').fetchone()[0] == 4
        assert db.execute("SELECT count(*) FROM tombstones WHERE kind='document'").fetchone()[0] == 1
        retained = json.loads(db.execute("SELECT payload FROM journal WHERE json_extract(payload,'$.kind')='document.page.appearance'").fetchone()[0])
        assert retained  # The saved rotation is journaled before later page removal.
    run([binary, '--library', library, '--browser-smoke-fixtures', render,
         '--browser-smoke-case', 'editor-reopen', '--smoke-output', output / 'reopen',
         '--theme', 'dark', '--size', '680x680'], 'reopen')
    assert saved(library) == after
    reopened = json.loads((output / 'reopen/01-reopened-editor.json').read_text())['document']
    assert reopened == after_delete['document']
    report = {'synthetic_only': True, 'interaction': 'application message injection; stale token deliberately injected',
              'binary_sha256': digest(binary), 'invalid_stale_drafts_retained': True,
              'notes_exact_multiline_unicode_trailing_newline': True, 'exact_amount_and_source_preserved': True,
              'multiple_then_empty_filing_journaled': True, 'saved_pdf_subset_uses_physical_page_1': True,
              'deletion_retains_originals': True, 'journaled_actions': kinds,
              'restart_exact_saved_document_equal': True, 'native_captures': 6,
              'limits': ['not physical keyboard/pointer or accessibility acceptance']}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS native editor drafts/exact values, ten journaled saves, subset/delete and restart')


if __name__ == '__main__':
    main()
