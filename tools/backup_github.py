#!/usr/bin/env python3
"""Snapshot only Stack source into a separate GitHub source checkout.

Includes tracked and nonignored source, including unfinished edits. Never stages
or commits the shared parent repository. Refuses credentials, library archives,
SQLite files, symbolic links, build outputs and repositories outside Stack.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DENIED_PARTS = {'.git', '.aws', '.codex', '.agents', '.android', '.gradle',
                'node_modules', 'target', 'build', 'Reference',
                'reference', 'refrence', 'Refrence', '__pycache__'}
DENIED_SUFFIXES = {'.apk', '.apkm', '.xapk', '.dex', '.smali', '.jks', '.keystore',
                   '.pem', '.key', '.p12', '.pfx', '.db', '.sqlite', '.sqlite3',
                   '.stackbackup', '.stackexchange', '.stackbundle', '.part'}
SECRET = re.compile(rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----)')


def git(*args, cwd=ROOT):
    return subprocess.check_output(['git', *args], cwd=cwd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mirror', required=True, type=Path, help='Separate owned source backup checkout')
    parser.add_argument('--repository', default='nchiker/stack')
    parser.add_argument('--push', action='store_true')
    parser.add_argument('--public', action='store_true', help='Confirm that the destination repository is public')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        parser.error('Expected owner/repository')
    mirror = args.mirror.resolve()
    if mirror == ROOT or ROOT in mirror.parents or mirror in ROOT.parents:
        parser.error('Choose a separate source backup checkout outside the project')
    if mirror.exists() and not (mirror / '.git').is_dir():
        parser.error('Refusing an existing directory without an owned Git checkout')
    listing = git('ls-files', '-z', '--cached', '--others', '--exclude-standard').split(b'\0')
    files = {}
    excluded = []
    for raw in listing:
        if not raw:
            continue
        name = raw.decode('utf-8')
        relative = Path(name)
        source = ROOT / relative
        if '..' in relative.parts or relative.is_absolute():
            parser.error('Unexpected source path')
        if not source.exists():  # Uncommitted deletions remain deleted in backup.
            continue
        if (any(part in DENIED_PARTS for part in relative.parts)
                or relative.parts[:2] == ('desktop', 'runtime')
                or source.suffix.lower() in DENIED_SUFFIXES
                or source.name in {'local.properties', 'hosts.yml', 'credentials', '.env'}
                or source.name.startswith('.env.')
                or source.name.endswith(('-wal', '-shm'))):
            excluded.append(name)
            continue
        if source.is_symlink() or not source.is_file():
            parser.error(f'Review unsupported source entry: {name}')
        data = source.read_bytes()
        if SECRET.search(data):
            parser.error(f'Credential-like content requires review: {name}')
        if len(data) > 50 * 1024 * 1024:
            parser.error(f'Review oversized source file: {name}')
        files[name] = hashlib.sha256(data).hexdigest()
    if not files or 'app/src/main/AndroidManifest.xml' not in files or 'desktop/Cargo.toml' not in files:
        parser.error('Expected complete Android and desktop source trees')
    created = not mirror.exists()
    if created:
        mirror.mkdir(parents=True, mode=0o700)
        subprocess.run(['git', 'init', '-b', 'main', str(mirror)], check=True, stdout=subprocess.DEVNULL)
        git('config', 'user.name', git('log', '-1', '--format=%an').decode().strip(), cwd=mirror)
        git('config', 'user.email', git('log', '-1', '--format=%ae').decode().strip(), cwd=mirror)
    else:
        # Only a prior snapshot created by this tool may be updated/deleted.
        marker = mirror / 'STACK_SOURCE_BACKUP.json'
        if not marker.is_file() or json.loads(marker.read_text()).get('format') != 'org.stackdocs.source-backup':
            parser.error('Existing checkout is not an owned Stack source backup')
    for old in mirror.iterdir():
        if old.name == '.git':
            continue
        if old.is_dir() and not old.is_symlink():
            shutil.rmtree(old)
        else:
            old.unlink()
    for name in files:
        destination = mirror / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / name, destination)
    receipt = {'format': 'org.stackdocs.source-backup', 'version': 1,
               'source_base_commit': git('rev-parse', 'HEAD').decode().strip(),
               'qualification': 'Source checkpoint including unfinished edits; not a verified release or personal document backup',
               'files_sha256': files, 'excluded_paths': sorted(excluded)}
    (mirror / 'STACK_SOURCE_BACKUP.json').write_text(json.dumps(receipt, indent=2) + '\n')
    git('add', '--all', cwd=mirror)
    changed = subprocess.run(['git', 'diff', '--cached', '--quiet'], cwd=mirror).returncode
    if changed:
        subprocess.run(['git', 'commit', '-m', 'Back up Stack phone and desktop source checkpoint'], cwd=mirror, check=True, stdout=subprocess.DEVNULL)
    url = f'https://github.com/{args.repository}.git'
    remotes = git('remote', cwd=mirror).decode().split()
    if 'origin' in remotes:
        if git('remote', 'get-url', 'origin', cwd=mirror).decode().strip() != url:
            parser.error('Backup origin differs from the requested repository')
    else:
        git('remote', 'add', 'origin', url, cwd=mirror)
    if args.push:
        info = json.loads(subprocess.check_output(['gh', 'repo', 'view', args.repository, '--json', 'visibility,nameWithOwner'], text=True))
        if info['nameWithOwner'].lower() != args.repository.lower():
            parser.error('Source backup destination differs from the requested repository')
        # Publishing to a public repository must be a deliberate choice.
        if info['visibility'] != 'PRIVATE' and not args.public:
            parser.error(f'{args.repository} is {info["visibility"]}; pass --public to publish to it')
        subprocess.run(['git', '-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential', 'push', '--set-upstream', 'origin', 'main'], cwd=mirror, check=True)
    print(json.dumps({'files': len(files), 'excluded': len(excluded), 'commit': git('rev-parse', 'HEAD', cwd=mirror).decode().strip(), 'mirror': str(mirror), 'repository': args.repository, 'pushed': args.push}))


if __name__ == '__main__':
    main()
