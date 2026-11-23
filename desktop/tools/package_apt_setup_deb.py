#!/usr/bin/env python3
"""Build the tiny first-install package stack-docs-apt-source_<version>_all.deb.

Installing it (one double-click) adds Stack's apt repository and its signing key, so the system updater offers
stack-docs updates. It depends on nothing and runs no scripts. Offline; installs and publishes nothing.

    python3 desktop/tools/package_apt_setup_deb.py [--keys ~/APK-Keys/stack-apt] [--output desktop/target/deb]
Only the PUBLIC key is read. Publish the result with publish_apt_repo.py --deb.
"""
import argparse
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'stack-docs-apt-source'
VERSION = '1.0'
BASE_URL = 'https://nchiker.github.io/stack-flatpak/apt/'
KEYRING = '/usr/share/keyrings/stack-apt.gpg'
SOURCES = f'''Types: deb
URIs: {BASE_URL}
Suites: stable
Components: main
Architectures: amd64
Signed-By: {KEYRING}
'''


def build(public_key, output):
    keyring = subprocess.check_output(['gpg', '--dearmor'], input=Path(public_key).read_bytes())
    output.mkdir(parents=True, exist_ok=True)
    deb = output / f'{PACKAGE}_{VERSION}_all.deb'
    with tempfile.TemporaryDirectory(prefix='stack-apt-setup-') as work:
        tree = Path(work) / 'tree'
        for rel, data in (('etc/apt/sources.list.d/stack-docs.sources', SOURCES.encode()), (KEYRING[1:], keyring)):
            target = tree / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            target.chmod(0o644)
        for d in tree.rglob('*'):
            if d.is_dir():
                d.chmod(0o755)
        tree.chmod(0o755)
        (tree / 'DEBIAN').mkdir(mode=0o755)
        (tree / 'DEBIAN/control').write_text(
            f'Package: {PACKAGE}\nVersion: {VERSION}\nSection: misc\nPriority: optional\nArchitecture: all\n'
            'Maintainer: Stack contributors <https://github.com/nchiker/stack>\n'
            'Description: Stack apt repository and signing key\n'
            ' Adds the signed Stack package repository so that the system updater\n'
            ' offers updates for stack-docs.\n')
        subprocess.run(['dpkg-deb', '--root-owner-group', '-Zxz', '--build', str(tree), str(deb)], check=True,
                       stdout=subprocess.DEVNULL)
    return deb


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--keys', type=Path, default=Path.home() / 'APK-Keys/stack-apt')
    parser.add_argument('--output', type=Path, default=ROOT / 'target/deb')
    args = parser.parse_args()
    print(f'Built {build(args.keys / "apt-signing-public.asc", args.output)}')


if __name__ == '__main__':
    main()
