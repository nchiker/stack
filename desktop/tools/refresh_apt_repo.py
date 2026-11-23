#!/usr/bin/env python3
"""One-command refresh of Stack's apt repository: Ami Pro Linux, the suite, Stack and the first-install package.

    python3 desktop/tools/refresh_apt_repo.py          # dry run: builds into a temp site checkout, pushes nothing
    python3 desktop/tools/refresh_apt_repo.py --push   # same, into the real site checkout, then commit and push

Steps: (1) fetch and verify the latest Ami Pro Linux release (sync_amipro_linux.py); (2) build stack-suite
(package_suite_deb.py) when desktop/target/deb has none; (3) publish those plus every stack-docs and
stack-docs-apt-source .deb found in desktop/target/deb with publish_apt_repo.py (signing key from ~/APK-Keys/stack-apt).
Build stack-docs first (package_deb.py) when a new Stack version should go out. Only --push publishes anything.
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DEB_DIR = TOOLS.parent / 'target/deb'
sys.path.insert(0, str(TOOLS))
import package_suite_deb
import sync_amipro_linux


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--push', action='store_true', help='publish for real (default is a dry run)')
    parser.add_argument('--deb-dir', type=Path, default=DEB_DIR)
    parser.add_argument('--keys', type=Path, default=None)
    parser.add_argument('--site', type=Path, default=None, help='site checkout (default: real one with --push, temp otherwise)')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='stack-refresh-') as tmp:
        amipro = sync_amipro_linux.sync(Path(tmp) / 'amipro')
        suite = args.deb_dir / f'{package_suite_deb.PACKAGE}_{package_suite_deb.VERSION}_all.deb'
        if not suite.is_file():
            package_suite_deb.build(args.deb_dir)
        debs = [amipro, suite, *sorted(args.deb_dir.glob('stack-docs_*_amd64.deb')),
                *sorted(args.deb_dir.glob('stack-docs-apt-source_*_all.deb'))]
        command = [sys.executable, '-I', str(TOOLS / 'publish_apt_repo.py')]
        for deb in debs:
            command += ['--deb', str(deb)]
        if args.keys:
            command += ['--keys', str(args.keys)]
        if args.push:
            if args.site:
                command += ['--site', str(args.site)]
            command.append('--push')
        else:
            command += ['--site', str(args.site or Path(tmp) / 'site')]
        print('Publishing:', ', '.join(d.name for d in debs), '(push)' if args.push else '(dry run)')
        return subprocess.run(command).returncode


if __name__ == '__main__':
    sys.exit(main())
