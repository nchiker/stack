#!/usr/bin/env python3
"""Build the metapackage stack-suite_<version>_all.deb (Depends: stack-docs, amipro-linux).

Installing it pulls in Stack and Ami Pro Linux from the Stack apt repository. It ships no program files, only a short
note under /usr/share/doc/stack-suite/. Versioned on its own (1.0), reproducible (fixed timestamps, same bytes every
build). Offline; installs and publishes nothing.

    python3 desktop/tools/package_suite_deb.py [--output desktop/target/deb]
Publish the result with publish_apt_repo.py --deb.
"""
import argparse
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'stack-suite'
VERSION = '1.0'
EPOCH = 1735689600  # 2025-01-01, fixed so the package is reproducible
README = '''stack-suite
===========

This is a metapackage. It contains no program files; it only depends on:

  stack-docs     Stack, the document library and search
  amipro-linux   Ami Pro Linux

Both come from the Stack apt repository (install stack-docs-apt-source once to add it).
Removing stack-suite does not remove them; use "apt autoremove" if you want that.
'''


def build(output):
    output.mkdir(parents=True, exist_ok=True)
    deb = output / f'{PACKAGE}_{VERSION}_all.deb'
    with tempfile.TemporaryDirectory(prefix='stack-suite-') as work:
        tree = Path(work) / 'tree'
        doc = tree / f'usr/share/doc/{PACKAGE}/README.Debian'
        doc.parent.mkdir(parents=True)
        doc.write_text(README)
        doc.chmod(0o644)
        (tree / 'DEBIAN').mkdir(mode=0o755)
        (tree / 'DEBIAN/control').write_text(
            f'Package: {PACKAGE}\nVersion: {VERSION}\nSection: metapackages\nPriority: optional\nArchitecture: all\n'
            'Depends: stack-docs, amipro-linux\n'
            'Maintainer: Stack contributors <https://github.com/nchiker/stack>\n'
            'Description: Stack and Ami Pro Linux together\n'
            ' Metapackage that installs stack-docs and amipro-linux.\n')
        for p in [tree, *tree.rglob('*')]:
            if p.is_dir():
                p.chmod(0o755)
            os.utime(p, (EPOCH, EPOCH), follow_symlinks=False)
        subprocess.run(['dpkg-deb', '--root-owner-group', '-Zxz', '--build', str(tree), str(deb)], check=True,
                       stdout=subprocess.DEVNULL, env=dict(os.environ, SOURCE_DATE_EPOCH=str(EPOCH)))
    return deb


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--output', type=Path, default=ROOT / 'target/deb')
    args = parser.parse_args()
    print(f'Built {build(args.output)}')


if __name__ == '__main__':
    main()
