#!/usr/bin/env python3
"""Read-only, idle-app ADB recovery snapshot. Never starts/stops/installs an app.

Copies files/databases/shared_prefs/no_backup, including SQLite WAL/SHM. Requires
authorized run-as access. Refuses a running app, changing source or existing output.
This is a private raw recovery snapshot, not a portable .stackbackup or Keystore export.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import sqlite3
import subprocess
import tarfile
import tempfile


def sha_file(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--adb-port', type=int, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='org.stackdocs.app')
    parser.add_argument('--apksigner', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New private directory outside /tmp')
    args = parser.parse_args()
    os.umask(0o077)
    if not re.fullmatch(r'org\.stackdocs\.app(?:\.[a-z0-9_]+)*', args.package):
        parser.error('Expected an explicit Stack package')
    output = args.output.expanduser().resolve()
    if any(output.is_relative_to(Path(p)) for p in ['/tmp', '/var/tmp', '/dev/shm']):
        parser.error('Choose persistent private backup storage')
    if output.exists():
        parser.error('Existing output preserved; choose a new directory')
    adb = [str(args.adb), '-P', str(args.adb_port), '-s', args.serial]

    def shell(command):
        return subprocess.check_output(adb + ['shell', command], stderr=subprocess.PIPE).decode().replace('\r\n', '\n')

    def idle():
        processes = shell('ps -A -o NAME').splitlines()
        if any(p.strip() == args.package or p.strip().startswith(args.package + ':') for p in processes):
            raise RuntimeError('App is running; snapshot refused without stopping it')

    def as_app(command):
        return shell('run-as ' + shlex.quote(args.package) + ' sh -c ' + shlex.quote(command))

    idle()
    expected_root = '/data/user/0/' + args.package
    if as_app('pwd').strip() != expected_root:
        raise RuntimeError('Unexpected app data root or run-as unavailable')
    dirs = as_app('for d in files databases shared_prefs no_backup; do if [ -d "$d" ]; then echo "$d"; fi; done').splitlines()
    if not dirs or set(dirs) - {'files', 'databases', 'shared_prefs', 'no_backup'}:
        raise RuntimeError('No supported app data directories found')
    names = ' '.join(dirs)
    size_kib = sum(int(line.split()[0]) for line in as_app('du -sk ' + names).splitlines())
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if shutil.disk_usage(output.parent).free < size_kib * 4096 + 128 * 1024 * 1024:
        raise RuntimeError('Insufficient local space for snapshot and verification')
    output.mkdir(mode=0o700)
    report = {'status': 'incomplete', 'package': args.package, 'device': args.serial,
              'directories': dirs, 'source_stopped_or_changed_by_tool': False}
    try:
        # Keep filename/hash inventory private; output only aggregate results.
        inventory_command = 'find ' + names + ' -type f -exec sha256sum {} \;'
        before = as_app(inventory_command)
        with (output / 'app-data.tar').open('xb') as archive:
            subprocess.run(adb + ['exec-out', 'run-as', args.package, 'tar', '-cf', '-', *dirs],
                           stdout=archive, stderr=subprocess.PIPE, check=True)
            archive.flush()
            os.fsync(archive.fileno())
        after = as_app(inventory_command)
        idle()
        if sorted(before.splitlines()) != sorted(after.splitlines()):
            raise RuntimeError('Source changed during copy; incomplete snapshot retained for inspection')
        expected = {}
        for line in before.splitlines():
            if not re.fullmatch(r'[0-9a-f]{64}  .+', line):
                raise RuntimeError('Unsupported filename/hash encoding')
            expected[line[66:]] = line[:64]
        extracted = output / 'app-data'
        extracted.mkdir(mode=0o700)
        with tarfile.open(output / 'app-data.tar', 'r:') as archive:
            for member in archive:
                path = PurePosixPath(member.name)
                if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] not in dirs:
                    raise RuntimeError('Unsafe recovery archive member')
                destination = extracted.joinpath(*path.parts)
                if member.isdir():
                    destination.mkdir(mode=0o700, parents=True, exist_ok=True)
                elif member.isfile():
                    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                    with archive.extractfile(member) as source, destination.open('xb') as target:
                        shutil.copyfileobj(source, target)
                else:
                    raise RuntimeError('Symlink or unsupported member in app snapshot')
        actual = {str(p.relative_to(extracted)): sha_file(p)
                  for p in extracted.rglob('*') if p.is_file()}
        if actual != expected:
            raise RuntimeError('Copied files differ from device inventory')
        (output / 'files.sha256.json').write_text(json.dumps(actual, indent=2) + '\n')
        databases = []
        if not (extracted / 'databases/library.db').is_file():
            raise RuntimeError('Required library.db missing; initialized library recovery not verified')
        for database in (extracted / 'databases').glob('*.db'):
            # Validate a separate copy with WAL present; leave the captured bytes untouched.
            with tempfile.TemporaryDirectory(prefix='.sqlite-verify-', dir=output) as work:
                for suffix in ['', '-wal', '-shm', '-journal']:
                    source = database.with_name(database.name + suffix)
                    if source.is_file():
                        shutil.copyfile(source, Path(work) / source.name)
                connection = sqlite3.connect((Path(work) / database.name).as_uri() + '?mode=ro', uri=True)
                try:
                    if connection.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
                        raise RuntimeError('Captured SQLite database failed integrity check')
                    if connection.execute('PRAGMA foreign_key_check').fetchall():
                        raise RuntimeError('Captured SQLite database has foreign-key violations')
                    databases.append({'file': database.name, 'integrity': 'ok',
                                      'wal_copied': database.with_name(database.name + '-wal').is_file(),
                                      'shm_copied': database.with_name(database.name + '-shm').is_file(),
                                      'rollback_journal_copied': database.with_name(database.name + '-journal').is_file(),
                                      'user_version': connection.execute('PRAGMA user_version').fetchone()[0]})
                finally:
                    connection.close()
        apk_paths = shell('pm path ' + args.package).splitlines()
        apks = output / 'installed-apks'
        apks.mkdir(mode=0o700)
        certificates = []
        for i, line in enumerate(apk_paths):
            if not line.startswith('package:/'):
                raise RuntimeError('Unexpected installed APK location')
            apk = apks / f'{i}.apk'
            subprocess.run(adb + ['pull', line[8:], str(apk)], capture_output=True, check=True)
            apk.chmod(0o600)
            verify = subprocess.check_output([str(args.apksigner), 'verify', '--print-certs', str(apk)], stderr=subprocess.PIPE).decode()
            match = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', verify)
            if not match:
                raise RuntimeError('Installed APK signer could not be verified')
            certificates.append({'file': apk.name, 'sha256': sha_file(apk),
                                 'certificate_sha256': match.group(1)})
        idle()
        if sorted(as_app(inventory_command).splitlines()) != sorted(before.splitlines()):
            raise RuntimeError('Source changed during verification; complete snapshot not claimed')
        report.update(status='verified_raw_snapshot', files=len(actual), databases=databases,
                      archive_sha256=sha_file(output / 'app-data.tar'),
                      installed_apks=certificates, copied_files_equal_device=True,
                      sqlite_sidecars_copied_if_present=True, device_restore_performed=False,
                      qualification='Private raw snapshot, not portable restore proof; Android Keystore keys and device grants are not exported')
        print(json.dumps({'status': report['status'], 'files': len(actual), 'databases': len(databases), 'output': str(output)}))
    finally:
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
