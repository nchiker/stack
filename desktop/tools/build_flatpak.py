#!/usr/bin/env python3
"""Build the Stack Flatpak from source with packaging/org.stackdocs.desktop.yml.

Stages the vendored source archive, dependency notices, the pinned PDFium and OCR
engines and the OCR engine's libjpeg8 next to a copy of the manifest, then runs
flatpak-builder offline with the installed Freedesktop SDK and Rust extension.
Does not install the app or open a library. The output directory must be new.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
from package_notices import package as package_notices

ROOT = Path(__file__).resolve().parents[1]
APP_ID = 'org.stackdocs.desktop'


def sha256(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--branch', default='stable')
    parser.add_argument('--jpeg-library', type=Path,
                        default=Path('/usr/lib/x86_64-linux-gnu/libjpeg.so.8'))
    parser.add_argument('--jpeg-license', type=Path,
                        default=Path('/usr/share/doc/libjpeg-turbo8/copyright'))
    args = parser.parse_args()
    output = args.output.absolute()
    if output.exists():
        parser.error('Choose a fresh output directory')
    for engine in ('pdfium/lib/libpdfium.so', 'ocr/ENGINE.json'):
        if not (ROOT / 'runtime' / engine).is_file():
            parser.error('Prepare the pinned PDFium and OCR runtimes first')
    for path in (args.jpeg_library, args.jpeg_license):
        if not path.is_file():
            parser.error(f'Missing OCR JPEG dependency or its license: {path}')
    # The newest listed release is the version being built.
    release = ET.parse(ROOT / f'packaging/{APP_ID}.metainfo.xml').find('releases/release')
    if release is None:
        parser.error('List this release in the metainfo first')
    version = release.get('version')
    stage = output / 'stage'
    stage.mkdir(parents=True)
    metadata = json.loads(subprocess.check_output(
        ['cargo', 'metadata', '--manifest-path', str(ROOT / 'Cargo.toml'), '--locked', '--offline',
         '--format-version', '1', '--filter-platform', 'x86_64-unknown-linux-gnu'], text=True))
    package_notices(ROOT, stage, metadata)
    shutil.copytree(ROOT / 'runtime', stage / 'runtime', ignore=shutil.ignore_patterns('*.tgz', '*.zip'))
    shutil.copy2(args.jpeg_library, stage / 'libjpeg.so.8')
    shutil.copy2(args.jpeg_license, stage / 'libjpeg-turbo8-copyright.txt')
    manifest = stage / f'{APP_ID}.yml'
    shutil.copy2(ROOT / f'packaging/{APP_ID}.yml', manifest)
    repo = output / 'repo'
    subprocess.run(['flatpak-builder', '--user', '--disable-download', '--disable-rofiles-fuse',
                    '--force-clean', f'--default-branch={args.branch}', f'--repo={repo}',
                    '--state-dir', str(output / 'state'), str(output / 'build'), str(manifest)],
                   check=True)
    bundle = output / f'Stack-{version}-x86_64.flatpak'
    subprocess.run(['flatpak', 'build-bundle', '--arch=x86_64',
                    '--runtime-repo=https://flathub.org/repo/flathub.flatpakrepo',
                    str(repo), str(bundle), APP_ID, args.branch], check=True)
    (output / 'SHA256SUMS').write_text(f'{sha256(bundle)}  {bundle.name}\n')
    shutil.rmtree(output / 'build')
    print(bundle)


if __name__ == '__main__':
    main()
