#!/usr/bin/env python3
"""Prepare disposable native desktop/sync review fixtures; never open a window."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess


PROJECT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def prepare(package, out):
    receipt = json.loads((package / 'INSTALL.json').read_text())
    # Use the frozen, audited delivery rather than whichever target/debug build
    # happens to exist. Refuse an incomplete or modified payload before use.
    for name, expected in receipt['files'].items():
        path = (package / name).resolve()
        if not path.is_relative_to(package) or digest(path) != expected:
            raise ValueError(f'Package payload mismatch: {name}')
    out.mkdir(mode=0o700)  # An existing review, including edits, is never reset.
    env = dict(os.environ)
    for key in ('DATA', 'CONFIG', 'CACHE', 'STATE'):
        folder = out / key.lower()
        folder.mkdir(mode=0o700)
        env[f'XDG_{key}_HOME'] = str(folder)
    env.update(
        STACK_PDFIUM_LIBRARY=str(package / 'runtime/pdfium/lib/libpdfium.so'),
        STACK_TESSERACT_LIBRARY=str(package / 'runtime/ocr/lib/libtesseract.so.5'),
        STACK_TESSDATA_DIR=str(package / 'runtime/ocr/tessdata'),
        LD_LIBRARY_PATH=str(package / 'runtime/ocr/lib'),
        OMP_THREAD_LIMIT='1',
    )
    (out / 'exports').mkdir(mode=0o700)
    (out / 'logs').mkdir(mode=0o700)
    commands = []

    def cli(*args, as_json=True):
        command = [str(package / 'bin/stack-cli'), *map(str, args)]
        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=120)
        number = len(commands)
        commands.append({'command': command, 'exit_code': result.returncode})
        (out / 'logs' / f'{number:03}.stdout').write_text(result.stdout)
        (out / 'logs' / f'{number:03}.stderr').write_text(result.stderr)
        (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        result.check_returncode()
        return json.loads(result.stdout) if as_json else result.stdout

    def save(name, value):
        (out / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')

    def launcher(name, library):
        assignments = ' '.join(shlex.quote(f'{key}={env[key]}') for key in env
                               if key.startswith('XDG_') and key.endswith('_HOME'))
        path = out / name
        path.write_text('#!/bin/sh\nexec env -u WAYLAND_DEBUG ' + assignments + ' ' +
                        shlex.quote(str(package / 'stack')) + ' --library ' +
                        shlex.quote(str(library)) + ' "$@"\n')
        path.chmod(0o700)

    sources, archives = out / 'sources', out / 'archives'
    cli('fixtures', sources, as_json=False)
    cli('m1-fixtures', sources, archives)
    archive = archives / 'format-3.stackbackup'
    library, many = out / 'library', out / 'paging-library'
    cli('archive', library, 'restore', archive)
    for _ in range(12):
        cli('archive', many, 'restore', archive)
    assert cli('library', library, 'status')['documents'] == 5
    assert cli('library', many, 'status')['documents'] == 60
    launcher('run', library)
    launcher('run-many', many)

    # Dedicated disposable reset/recovery library; do not erase the browser review
    # or a retained phone library to observe the new warnings.
    reset_library, phone_library = out / 'reset-library', out / 'phone-library'
    cli('archive', reset_library, 'restore', archive)
    cli('library', phone_library, 'status')
    launcher('run-reset', reset_library)
    launcher('run-phone', phone_library)

    # Nonempty adoption remains unapplied for the human review. Only these two
    # authored PDFs have deliberately known fixture ancestry for an explicit match.
    source, receiver = out / 'adoption-source', out / 'adoption-receiver'
    cli('archive', source, 'restore', archive)
    cli('library', receiver, 'import', sources / 'two-pages.pdf', 'Local review PDF')
    receiver_id = cli('sync', receiver, 'status')['replica']
    source_status = cli('sync', source, 'start', receiver_id)
    baseline = out / 'adoption-baseline.stackexchange'
    cli('sync', source, 'export', receiver_id, baseline)
    plan = cli('sync', receiver, 'review-adoption', baseline)
    save('adoption-review.json', plan)
    launcher('run-adoption-source', source)
    launcher('run-adoption', receiver)

    # Two adopted replicas with >40 alternatives exercise actual conflict paging.
    # Identity generation/enrollment and network listening remain deliberate UI
    # actions for the reviewer. No credentials or sockets are created here.
    a, b = out / 'paired-a', out / 'paired-b'
    for n in range(21):
        cli('library', a, 'import', sources / 'corners.png', f'Conflict review {n + 1:02}')
    a_status = cli('sync', a, 'status')
    b_id = cli('sync', b, 'status')['replica']
    a_status = cli('sync', a, 'start', b_id)
    pair_baseline = out / 'paired-baseline.stackexchange'
    cli('sync', a, 'export', b_id, pair_baseline)
    cli('sync', b, 'adopt', pair_baseline, a_status['library'], a_status['replica'], b_id)
    documents = cli('library', a, 'manifest')['documents']
    for n, document in enumerate(documents):
        for root, side in ((a, 'A'), (b, 'B')):
            cli('library', root, 'rename', document['id'], f'{side} choice {n + 1:02} 🦀')
    exact_notes = 'Exact notes 🦀\n00024.500000 GBP\n' + 'Full selected value; ' * 30
    cli('library', a, 'notes', documents[0]['id'], exact_notes)
    cli('library', b, 'notes', documents[0]['id'], 'Other offline notes\n0000123')
    for origin, target, peer, name in ((a, b, b_id, 'a-to-b'),
                                        (b, a, a_status['replica'], 'b-to-a')):
        exchange = out / f'{name}.stackexchange'
        cli('sync', origin, 'export', peer, exchange)
        cli('sync', target, 'import', exchange)
    statuses = [cli('sync', root, 'status') for root in (a, b)]
    for status in statuses:
        assert sum(map(len, status['conflicts'].values())) > 40
        assert status['pending_gaps'] == 0
        assert status['projection_valid'] is True
    assert statuses[0]['conflicts'] == statuses[1]['conflicts']
    launcher('run-peer-a', a)
    launcher('run-peer-b', b)
    save('review.json', {
        'synthetic_only': True, 'prepared_without_windows_or_network': True,
        'package': str(package), 'package_receipt_sha256': digest(package / 'INSTALL.json'),
        'payload_hashes_verified': len(receipt['files']),
        'browser_documents': 5, 'paging_documents': 60,
        'reset_documents': 5, 'phone_documents': 0,
        'adoption_source_replica': source_status['replica'],
        'adoption_receiver_replica': receiver_id,
        'adoption_applied': False,
        'paired_replicas': [s['replica'] for s in statuses],
        'conflict_cells': len(statuses[0]['conflicts']),
        'conflict_alternatives': sum(map(len, statuses[0]['conflicts'].values())),
        'expected_long_notes': exact_notes,
        'pairing_initialized': False, 'commands': len(commands),
    })
    save('paired-status.json', statuses)
    print(f'Prepared {out}; user launchers: run, run-many, run-adoption, run-adoption-source, run-peer-a, run-peer-b, run-reset, run-phone')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', type=Path, default=PROJECT / '.artifacts/stack-automatic-sync-resume-20261021/desktop')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    if not any(out.is_relative_to(root) and out != root
               for root in (Path('/tmp'), PROJECT / '.artifacts')):
        parser.error('Use a fresh disposable /tmp or Stack/.artifacts directory')
    os.umask(0o077)
    prepare(args.package.resolve(), out)


if __name__ == '__main__':
    main()
