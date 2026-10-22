#!/usr/bin/env python3
"""Bundle a verified desktop delivery using an already installed Flatpak runtime.

No app installation, runtime download, or library access is performed. Build
outputs must be new. The resulting bundle can be installed with flatpak install.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP_ID = 'org.stackdocs.desktop'
PERMISSIONS = [
    '--share=network', '--share=ipc', '--socket=wayland',
    '--socket=fallback-x11', '--device=dri',
    '--filesystem=xdg-data/org.stackdocs.desktop:create',
    '--filesystem=xdg-config/org.stackdocs.desktop:create',
    '--filesystem=xdg-config/cosmic:ro',
]


def sha256(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--runtime-version', default='25.08')
    parser.add_argument('--branch', default='candidate')
    parser.add_argument('--version', default='0.1.0-rc1')
    parser.add_argument('--jpeg-library', type=Path,
                        default=Path('/usr/lib/x86_64-linux-gnu/libjpeg.so.8'))
    parser.add_argument('--jpeg-license', type=Path,
                        default=Path('/usr/share/doc/libjpeg-turbo8/copyright'))
    args = parser.parse_args()
    if platform.machine() != 'x86_64':
        parser.error('This prepared binary delivery requires x86_64')
    for value in (args.runtime_version, args.branch, args.version):
        if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9._-]*', value):
            parser.error('Invalid version or branch')
    source = args.package.resolve(strict=True)
    output = args.output.absolute()
    if output.exists():
        parser.error('Choose a fresh output directory')
    receipt = json.loads((source / 'INSTALL.json').read_text())
    if receipt.get('format') != 'org.stackdocs.desktop.install':
        parser.error('Expected a prepared Stack desktop delivery')
    payload_roots = ('bin', 'runtime', 'notices')
    payload_entries = [path for name in payload_roots
                       for path in (source / name).rglob('*')]
    if any(path.is_symlink() for path in payload_entries):
        parser.error('Prepared payload must not contain symbolic links')
    actual_payload = {str(path.relative_to(source)) for path in payload_entries if path.is_file()}
    expected_payload = {name for name in receipt['files'] if Path(name).parts[0] in payload_roots}
    if actual_payload != expected_payload:
        parser.error(f'Prepared payload file set changed: {sorted(actual_payload ^ expected_payload)}')
    actual_sources = {
        str(path.relative_to(ROOT)) for path in ROOT.rglob('*')
        if path.is_file() and path.suffix in ('.rs', '.toml', '.lock', '.py')
        and not any(part in ('target', 'runtime') for part in path.relative_to(ROOT).parts)
        and path.resolve() != Path(__file__).resolve()
    }
    expected_sources = set(receipt['source_sha256']) - {str(Path(__file__).resolve().relative_to(ROOT))}
    if actual_sources != expected_sources:
        parser.error(f'Prepared source file set changed: {sorted(actual_sources ^ expected_sources)}')
    for relative, expected in receipt['files'].items():
        path = source / relative
        if not path.is_file() or path.is_symlink() or not path.resolve().is_relative_to(source) or sha256(path) != expected:
            parser.error(f'Prepared payload checksum failed: {relative}')
    for relative, expected in receipt['source_sha256'].items():
        path = ROOT / relative
        if not path.is_file() or sha256(path) != expected:
            parser.error(f'Prepared source is outdated: {relative}')
    for path in (args.jpeg_library, args.jpeg_license):
        if not path.is_file():
            parser.error(f'Missing OCR JPEG dependency or its license: {path}')
    # Fail before creating output if either installed build ref is absent.
    for runtime in ('org.freedesktop.Platform', 'org.freedesktop.Sdk'):
        subprocess.check_output(['flatpak', '--user', 'info',
                                 f'{runtime}//{args.runtime_version}'], text=True)
    output.mkdir(parents=True)
    build = output / 'build'
    run('flatpak', 'build-init', '--arch=x86_64', build, APP_ID,
        'org.freedesktop.Sdk', 'org.freedesktop.Platform', args.runtime_version)
    files = build / 'files'
    payload = files / 'lib/stack'
    payload.mkdir(parents=True)
    for name in payload_roots:
        shutil.copytree(source / name, payload / name)
    shutil.copy2(source / 'INSTALL.json', payload / 'INSTALL.json')
    shutil.copy2(args.jpeg_library, files / 'lib/libjpeg.so.8')
    shutil.copy2(args.jpeg_license, payload / 'notices/libjpeg-turbo8-copyright.txt')
    for source_path, target in (
        (ROOT / 'packaging/stack', files / 'bin/stack'),
        (ROOT / f'packaging/{APP_ID}.desktop', files / f'share/applications/{APP_ID}.desktop'),
        (source / f'assets/{APP_ID}.svg', files / f'share/icons/hicolor/scalable/apps/{APP_ID}.svg'),
    ):
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_path, target)
    (files / 'bin/stack').chmod(0o755)
    metainfo = ET.parse(ROOT / f'packaging/{APP_ID}.metainfo.xml')
    releases = ET.SubElement(metainfo.getroot(), 'releases')
    ET.SubElement(releases, 'release', {
        'version': args.version, 'type': 'development',
        'date': datetime.now(timezone.utc).date().isoformat(),
    })
    metadata_path = files / f'share/metainfo/{APP_ID}.metainfo.xml'
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metainfo.write(metadata_path, encoding='utf-8', xml_declaration=True)
    run('desktop-file-validate', files / f'share/applications/{APP_ID}.desktop')
    run('flatpak', 'build-finish', '--command=stack', *PERMISSIONS, build)
    repo = output / 'repo'
    run('flatpak', 'build-export', '--arch=x86_64', repo, build, args.branch)
    bundle = output / f'Stack-{args.version}-x86_64.flatpak'
    run('flatpak', 'build-bundle', '--arch=x86_64',
        '--runtime-repo=https://flathub.org/repo/flathub.flatpakrepo',
        repo, bundle, APP_ID, args.branch)
    result = {
        'format': 'org.stackdocs.flatpak.delivery', 'version': 1,
        'app_id': APP_ID, 'branch': args.branch, 'release': args.version,
        'architecture': 'x86_64',
        'runtime': f'org.freedesktop.Platform/x86_64/{args.runtime_version}',
        'prepared_install_receipt_sha256': sha256(source / 'INSTALL.json'),
        'source_base': receipt['source_base'], 'source_profile': receipt['profile'],
        'permissions': PERMISSIONS,
        'library': 'Existing host XDG data/org.stackdocs.desktop; existing library lock is retained',
        'jpeg_library_sha256': sha256(args.jpeg_library),
        'jpeg_license_sha256': sha256(args.jpeg_license),
        'bundle': bundle.name, 'bundle_sha256': sha256(bundle),
        'packaging_source_sha256': {
            str(path.relative_to(ROOT)): sha256(path)
            for path in [Path(__file__).resolve(), *sorted((ROOT / 'packaging').glob('*'))]
            if path.is_file()
        },
        'validation': 'Payload/source checksums and Flatpak export passed; runtime checks recorded separately',
    }
    (output / 'DELIVERY.json').write_text(json.dumps(result, indent=2) + '\n')
    (output / 'SHA256SUMS').write_text(f'{result["bundle_sha256"]}  {bundle.name}\n')
    print(bundle)


if __name__ == '__main__':
    main()
