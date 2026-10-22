#!/usr/bin/env python3
"""Explicitly adopt a reviewed development/original signing key into durable storage.

Never called automatically by Gradle. Never replaces an existing credential or backup.
Passwords are read from an environment variable and generated locally, never logged.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]


def run_keytool(keytool, arguments, env):
    result = subprocess.run([str(keytool), *arguments], env=env, capture_output=True)
    if result.returncode:
        raise RuntimeError('keytool failed; no credentials or private material logged')
    return result.stdout


def durable(path):
    path = path.expanduser().resolve()
    if any(path.is_relative_to(Path(root)) for root in ['/tmp', '/var/tmp', '/dev/shm']):
        raise ValueError('Signing destinations must be persistent, outside temporary directories')
    if path == ROOT or ROOT in path.parents:
        raise ValueError('Keep private signing credentials outside the source repository')
    return path


def private_directory(path):
    if path.is_symlink():
        raise ValueError('Refusing a symlinked signing directory')
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.stat().st_mode & 0o077:
        raise ValueError('Existing signing directory must have mode700')


def write_new(path, data):
    with path.open('xb') as stream:
        os.chmod(path, 0o600)
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', choices=['development', 'canonical'], required=True)
    parser.add_argument('--source-keystore', type=Path, required=True)
    parser.add_argument('--source-alias', default='androiddebugkey')
    parser.add_argument('--source-password-env', required=True)
    parser.add_argument('--keytool', type=Path, default=Path('keytool'))
    args = parser.parse_args()
    os.umask(0o077)
    policy = json.loads((ROOT / 'gradle/signing-policy.json').read_text())[args.profile]
    source_password = os.environ.get(args.source_password_env)
    if not source_password:
        parser.error('Set the source-password environment variable locally')
    env = dict(os.environ, STACK_SOURCE_PASSWORD=source_password)
    source = args.source_keystore.resolve(strict=True)
    cert = run_keytool(args.keytool, ['-exportcert', '-keystore', str(source), '-alias', args.source_alias,
                                    '-storepass:env', 'STACK_SOURCE_PASSWORD'], env)
    if hashlib.sha256(cert).hexdigest() != policy['certificate_sha256']:
        parser.error('Source certificate does not match the reviewed policy; no files created')
    primary_dir = durable(Path.home() / '.local/share/stack-signing')
    config_dir = durable(Path.home() / '.config/stack-signing')
    backup_root = durable(Path.home() / '.local/share/stack-signing-backups')
    for path in [primary_dir, config_dir, backup_root]:
        private_directory(path)
    primary = primary_dir / (args.profile + '.p12')
    config = config_dir / (args.profile + '.properties')
    if primary.exists() or config.exists():
        parser.error('Existing signing configuration/key preserved; initialization will not overwrite it')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + secrets.token_hex(4)
    backup_dir = backup_root / stamp
    recovery_dir = config_dir / ('recovery-' + stamp)
    private_directory(backup_dir)
    private_directory(recovery_dir)
    password = secrets.token_urlsafe(48)
    env['STACK_DESTINATION_PASSWORD'] = password
    # A local working directory keeps all private key copies outside /tmp.
    with tempfile.TemporaryDirectory(prefix='.adopt-', dir=primary_dir) as work:
        staged = Path(work) / 'adopted.p12'
        run_keytool(args.keytool, ['-importkeystore', '-noprompt', '-srckeystore', str(source),
                    '-srcalias', args.source_alias, '-srcstorepass:env', 'STACK_SOURCE_PASSWORD',
                    '-srckeypass:env', 'STACK_SOURCE_PASSWORD', '-destkeystore', str(staged),
                    '-deststoretype', 'PKCS12', '-destalias', policy['key_alias'],
                    '-deststorepass:env', 'STACK_DESTINATION_PASSWORD',
                    '-destkeypass:env', 'STACK_DESTINATION_PASSWORD'], env)
        data = staged.read_bytes()
        backup = backup_dir / primary.name
        write_new(backup, data)
        # Verify a restored independent encrypted copy can actually use the private key.
        restored = Path(work) / 'restored.p12'
        shutil.copyfile(backup, restored)
        restored_cert = run_keytool(args.keytool, ['-exportcert', '-keystore', str(restored),
                       '-storepass:env', 'STACK_DESTINATION_PASSWORD', '-alias', policy['key_alias']], env)
        run_keytool(args.keytool, ['-certreq', '-keystore', str(restored), '-alias', policy['key_alias'],
                    '-storepass:env', 'STACK_DESTINATION_PASSWORD', '-keypass:env', 'STACK_DESTINATION_PASSWORD'], env)
        if restored_cert != cert:
            raise RuntimeError('Restored backup certificate differs; primary configuration not created')
        # Preserve recovery credentials before publishing a usable primary config.
        properties = (f'storeFile={primary}\nbackupFile={backup}\n'
                      f'storePassword={password}\nkeyPassword={password}\n').encode()
        write_new(recovery_dir / config.name, properties)
        write_new(primary, data)
        write_new(config, properties)
    receipt = {'profile': args.profile, 'certificate_sha256': policy['certificate_sha256'],
               'primary': str(primary), 'configuration': str(config), 'encrypted_backup': str(backup),
               'private_recovery_configuration': str(recovery_dir / config.name),
               'keystore_sha256': hashlib.sha256(data).hexdigest(),
               'backup_restored_and_private_key_used': True,
               'qualification': 'Encrypted PKCS12 backup and private credentials are on this computer; no off-machine/disaster backup claimed'}
    write_new(backup_dir / 'receipt.json', (json.dumps(receipt, indent=2) + '\n').encode())
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
