#!/usr/bin/env python3
"""Read-only APK/device signing preflight. Does not install, stop, or remove apps."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--adb-port', type=int, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--apksigner', type=Path, required=True)
    parser.add_argument('--aapt2', type=Path, required=True)
    args = parser.parse_args()
    adb = [str(args.adb), '-P', str(args.adb_port), '-s', args.serial]

    def info(apk):
        badging = subprocess.check_output([str(args.aapt2), 'dump', 'badging', str(apk)], stderr=subprocess.PIPE).decode()
        package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)'", badging)
        min_sdk = re.search(r"(?:minSdkVersion|sdkVersion):'([0-9]+)'", badging)
        if not package or not re.fullmatch(r'org\.stackdocs\.app(?:\.[a-z0-9_]+)*', package.group(1)):
            raise RuntimeError('Not a supported Stack APK identity')
        verify = subprocess.check_output([str(args.apksigner), 'verify', '--print-certs', str(apk)], stderr=subprocess.PIPE).decode()
        signers = sorted(re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})', verify))
        if not signers:
            raise RuntimeError('Unsigned or unverified APK; installation is not safe')
        if not min_sdk:
            raise RuntimeError('APK minimum SDK not established')
        return {'package': package.group(1), 'version_code': int(package.group(2)),
                'min_sdk': int(min_sdk.group(1)), 'signer_sha256': signers}

    candidate = info(args.apk)
    api = subprocess.check_output(adb + ['shell', 'getprop', 'ro.build.version.sdk'], stderr=subprocess.PIPE).decode().strip()
    if not api.isdecimal():
        raise RuntimeError('Device API level not established')
    result = subprocess.run(adb + ['shell', 'pm', 'path', candidate['package']], capture_output=True, text=True)
    paths = [p[len('package:'):].strip() for p in result.stdout.splitlines() if p.startswith('package:')]
    report = {'candidate': candidate, 'device': args.serial, 'device_api': int(api), 'install_performed': False,
              'candidate_sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest()}
    if not paths:
        # A failed ADB/pm command must never look like an absent package.
        # Android pm path returns1/empty for an absent package. Confirm absence
        # independently with a successful, well-formed package-list query.
        listing = subprocess.run(adb + ['shell', 'pm', 'list', 'packages', candidate['package']], capture_output=True, text=True)
        listed = [p.strip() for p in listing.stdout.splitlines() if p.strip()]
        if (result.returncode not in (0, 1) or result.stderr.strip() or result.stdout.strip()
                or listing.returncode or listing.stderr.strip()
                or any(not re.fullmatch(r'package:[A-Za-z0-9_.]+', p) for p in listed)
                or 'package:' + candidate['package'] in listed):
            raise RuntimeError('Package query failed; absence not established')
        subprocess.run(adb + ['shell', 'true'], capture_output=True, check=True)
        report.update(status='fresh_separate_install', consequence='New package sandbox; existing apps/data are not imported or shared')
    else:
        with tempfile.TemporaryDirectory(prefix='stack-apk-preflight-') as work:
            base = next((p for p in paths if p.endswith('/base.apk')), paths[0])
            installed_path = Path(work) / 'installed.apk'
            subprocess.run(adb + ['pull', base, str(installed_path)], capture_output=True, check=True)
            installed = info(installed_path)
        report['installed'] = installed
        if installed['package'] != candidate['package'] or installed['signer_sha256'] != candidate['signer_sha256']:
            report.update(status='blocked_signer_mismatch', consequence='Cannot update this installation; current app/data preserved')
        elif candidate['version_code'] < installed['version_code']:
            report.update(status='blocked_downgrade', consequence='Version downgrade refused; current app/data preserved')
        else:
            report.update(status='signing_and_version_compatible', consequence='Signing/version prerequisites met; ABI, device policies, runtime and migrations still require qualification')
    if candidate['min_sdk'] > int(api):
        report.update(status='blocked_min_sdk', consequence='APK minimum SDK exceeds device API; no installation performed')
    print(json.dumps(report, indent=2))
    if report['status'].startswith('blocked_'):
        raise SystemExit(2)


if __name__ == '__main__':
    main()
