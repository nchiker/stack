#!/usr/bin/env python3
"""Prepare authored disposable M2 review data and a user-run native launcher. Opens no windows."""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.absolute()
    if not out.is_relative_to('/tmp'):
        parser.error('Use a fresh disposable /tmp root')
    os.umask(0o077)
    out.mkdir(mode=0o700)
    desktop = Path(__file__).resolve().parents[1]
    cli = desktop / 'target/debug/stack-cli'
    commands = [
        [cli, 'fixtures', out / 'sources'],
        [cli, 'm1-fixtures', out / 'sources', out / 'archives'],
        [cli, 'archive', out / 'library', 'restore', out / 'archives/format-3.stackbackup'],
        [cli, 'library', out / 'library', 'status'],
    ]
    commands += [[cli, 'archive', out / 'paging-library', 'restore', out / 'archives/format-3.stackbackup'] for _ in range(12)]
    env = dict(os.environ, STACK_PDFIUM_LIBRARY=str(desktop / 'runtime/pdfium/lib/libpdfium.so'))
    for key in ('DATA', 'CONFIG', 'CACHE', 'STATE'):
        folder = out / key.lower()
        folder.mkdir(mode=0o700)
        env[f'XDG_{key}_HOME'] = str(folder)
    (out / 'exports').mkdir(mode=0o700)
    for n, command in enumerate(commands):
        with (out / f'prepare-{n}.log').open('w') as log:
            subprocess.run(list(map(str, command)), env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=60)
    status = json.loads((out / 'prepare-3.log').read_text())
    assert status['documents'] == 5
    assignments = [f'{key}={shlex.quote(env[key])}' for key in env if key.startswith('XDG_') and key.endswith('_HOME')]
    assignments.append('STACK_PDFIUM_LIBRARY=' + shlex.quote(env['STACK_PDFIUM_LIBRARY']))
    for name, library in [('run', 'library'), ('run-many', 'paging-library')]:
        launcher = out / name
        launcher.write_text('#!/bin/sh\nexec env -u WAYLAND_DEBUG ' + ' '.join(assignments) + ' ' +
                            shlex.quote(str(desktop / 'target/debug/stack-desktop')) + ' --library ' + shlex.quote(str(out / library)) + ' "$@"\n')
        launcher.chmod(0o700)
    (out / 'review.json').write_text(json.dumps({'synthetic_only': True, 'prepared_without_windows': True,
                                               'initial_documents': 5, 'launcher': str(out / 'run'),
                                               'paging_documents': 60, 'paging_launcher': str(out / 'run-many'),
                                               'sources': str(out / 'sources'), 'archives': str(out / 'archives'),
                                               'exports': str(out / 'exports')}, indent=2) + '\n')
    print(f'Prepared synthetic libraries (five and sixty documents). When ready, run:\n{out}/run')


if __name__ == '__main__':
    main()
