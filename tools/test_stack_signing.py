#!/usr/bin/env python3
"""Exercise real Gradle signing guards without changing the managed credentials."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--only', action='append', help='Run only a named changed/missing check; reusable previous results remain valid')
    args = parser.parse_args()
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    scratch = Path.home() / '.local/share/stack-signing-validation' / secrets.token_hex(8)
    scratch.mkdir(parents=True, mode=0o700)
    config_path = Path.home() / '.config/stack-signing/development.properties'
    credentials = dict(line.split('=', 1) for line in config_path.read_text().splitlines() if '=' in line)
    base = [str(ROOT / 'gradlew'), '--offline', '--no-daemon', '--console=plain']
    cases = []

    def check(name, properties=None, flags=None, expected=None, task=':app:verifyStackSigning'):
        if args.only and name not in args.only:
            return
        command = base + [task] + (flags if flags is not None else ['-PstackChecks', '-PstackChecksNamespace=r6'])
        if properties is not None:
            path = scratch / (name + '.properties')
            path.write_text(''.join(f'{k}={v}\n' for k, v in properties.items()))
            path.chmod(0o600)
            command.append('-PstackSigningConfig=' + str(path))
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
        output = result.stdout + result.stderr
        # Test logs must never contain actual credentials, including on failures.
        for field in ['storePassword', 'keyPassword']:
            secret = credentials[field]
            if secret in output:
                raise AssertionError('Signing credentials appeared in Gradle output')
        (args.output / (name + '.log')).write_text(output)
        success = result.returncode == 0 if expected is None else result.returncode != 0 and expected in output
        cases.append({'name': name, 'passed': success, 'exit_code': result.returncode,
                      'expected': 'success' if expected is None else expected})
        if not success:
            raise RuntimeError('Unexpected signing guard result: ' + name)

    try:
        check('managed-key')
        check('backup-restored-key', {**credentials, 'storeFile': credentials['backupFile'], 'backupFile': credentials['storeFile']})
        check('original-package-managed-key', flags=[])
        check('recovery-variant-retired', flags=['-PstackRecovery'], expected='Stack Recovery is retired')
        check('missing-key', {**credentials, 'storeFile': str(scratch / 'absent.p12')}, expected='missing or symlinked')
        check('missing-backup', {**credentials, 'backupFile': str(scratch / 'absent-backup.p12')}, expected='missing or symlinked')
        check('wrong-password', {**credentials, 'storePassword': 'deliberately-incorrect'}, expected='could not be verified')
        check('temporary-key', {**credentials, 'storeFile': '/tmp/stack-dev/android-user/debug.keystore'}, expected='outside temporary')
        check('repository-secret', {**credentials, 'storeFile': str(ROOT / 'gradle/signing-policy.json')}, expected='outside the source repository')
        wrong = scratch / 'wrong.p12'
        shutil.copyfile(Path.home() / '.android/debug.keystore', wrong)
        wrong.chmod(0o600)
        wrong_backup = scratch / 'wrong-backup.p12'
        shutil.copyfile(wrong, wrong_backup)
        wrong_backup.chmod(0o600)
        check('wrong-certificate', {**credentials, 'storeFile': str(wrong), 'backupFile': str(wrong_backup),
              'storePassword': 'android', 'keyPassword': 'android'}, expected='certificate differs')
        wrong.chmod(0o644)
        check('public-key-file', {**credentials, 'storeFile': str(wrong), 'backupFile': str(wrong_backup)}, expected='must be private')
        check('same-file-backup', {**credentials, 'backupFile': credentials['storeFile']}, expected='independent file')
        check('unsigned-debug-refused', flags=['-PstackUnsignedRelease'], task=':app:assembleDebug', expected='only an explicitly unsigned release')
        if args.only and {case['name'] for case in cases} != set(args.only):
            raise ValueError('Unknown check requested; no passing report written')
        report = {'status': 'passed', 'cases': cases, 'credentials_logged': False,
                  'managed_credentials_changed': False, 'private_scratch_removed': True}
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps({'status': 'passed', 'cases': len(cases), 'report': str(args.output / 'report.json')}))
    finally:
        shutil.rmtree(scratch)


if __name__ == '__main__':
    main()
