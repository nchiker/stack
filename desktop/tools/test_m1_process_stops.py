#!/usr/bin/env python3
"""Actually SIGKILL the debug CLI at synthetic restore/export durability boundaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cli', type=Path)
    parser.add_argument('fixtures', type=Path)
    parser.add_argument('new_output', type=Path)
    args = parser.parse_args()
    cli = args.cli.resolve()
    fixtures = args.fixtures.resolve()
    marker = json.loads((fixtures / 'synthetic-fixture.json').read_text())
    assert marker == {'fixture': 'stack-m1', 'version': 1, 'license': 'CC0-1.0'}
    args.new_output.mkdir()
    output = args.new_output.resolve()
    assert str(output).startswith('/tmp/')
    archive = fixtures / 'format-3.stackbackup'

    def command(*parts):
        return subprocess.check_output([str(cli), *map(str, parts)], stderr=subprocess.STDOUT, text=True)

    def graph(root):
        value = json.loads(command('library', root, 'manifest'))
        value['created_at'] = 0
        return value

    cases = []
    checkpoints = ['staged', 'journal', *[f'original-{n}' for n in range(1, 6)], 'db_transaction', 'db_committed', 'cleanup',
                   'export_target', 'export_written', 'export_published']
    for checkpoint in checkpoints:
        root = output / checkpoint
        command('archive', root, 'restore', archive)
        (root / '.stack-m1-disposable').write_text('stack-m1-synthetic-only\n')
        baseline = graph(root)
        originals = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in (root / 'originals').rglob('*') if p.is_file()}
        kind = 'export' if checkpoint.startswith('export_') else 'restore'
        destination = output / f'{checkpoint}.stackbackup'
        ready = output / f'{checkpoint}.ready'
        with (output / f'{checkpoint}.log').open('w') as log:
            process = subprocess.Popen([str(cli), 'm1-interrupt', str(root), kind,
                                        str(destination if kind == 'export' else archive), checkpoint, str(ready)],
                                       stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 40
            while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(.02)
            if not ready.exists():
                process.kill()
                process.wait()
                raise AssertionError(f'{checkpoint} not reached; see {log.name}')
            assert ready.read_text() == checkpoint
            process.kill()
            assert process.wait(timeout=10) == -9
        status = json.loads(command('library', root, 'status'))  # Reopen performs recovery.
        committed = checkpoint in ['db_committed', 'cleanup']
        assert status['documents'] == (10 if committed else 5), (checkpoint, status)
        assert status['journal_events'] == (3 if committed else 2)
        current = graph(root)
        if not committed:
            assert current == baseline, checkpoint
        else:
            for table in ['documents', 'pages', 'memberships', 'suggestions', 'choices', 'details', 'tags', 'document_tags', 'files', 'stacks']:
                for row in baseline[table]:
                    assert row in current[table], (checkpoint, table)
        for path, digest in originals.items():
            assert hashlib.sha256((root / path).read_bytes()).hexdigest() == digest
        assert not list((root / 'operations').iterdir()), checkpoint
        assert not list(output.glob('.stack-export-*.part')), checkpoint
        if kind == 'export':
            assert destination.exists() == (checkpoint == 'export_published')
            if destination.exists():
                command('archive', output / 'published-check', 'validate', destination)
        cases.append({'checkpoint': checkpoint, 'termination': 'SIGKILL', 'recovered_documents': status['documents'],
                      'journal_events': status['journal_events'], 'preexisting_originals_unchanged': True,
                      'no_abandoned_operation_or_partial_export': True})
        print(f'{checkpoint}: passed', flush=True)
    report = {'fixture': 'stack-m1-v1', 'archive_sha256': hashlib.sha256(archive.read_bytes()).hexdigest(), 'scope': 'Actual process termination, not power-loss simulation',
              'pdfium_library_sha256': hashlib.sha256(Path(os.environ['STACK_PDFIUM_LIBRARY']).read_bytes()).hexdigest(),
              'cases': cases}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
