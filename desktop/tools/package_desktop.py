#!/usr/bin/env python3
"""Prepare an offline, persistent-XDG desktop installation in a fresh directory.

Does not install, launch an app, download assets, or touch a library. Run the
included install.py only when deliberately installing this prepared build.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--profile', choices=('debug', 'release'), default='debug')
    args = parser.parse_args()
    output = args.output.absolute()
    if output.exists():
        parser.error('Choose a fresh output directory')
    for source in [ROOT / 'target' / args.profile / name for name in ('stack-desktop', 'stack-cli')]:
        if not source.is_file():
            parser.error(f'Build the workspace first: {source}')
    for engine in ('pdfium/lib/libpdfium.so', 'ocr/ENGINE.json'):
        if not (ROOT / 'runtime' / engine).is_file():
            parser.error('Prepare the pinned PDFium and OCR runtimes first')
    output.mkdir(parents=True, mode=0o700)
    for name in ('stack-desktop', 'stack-cli'):
        target = output / 'bin' / name
        target.parent.mkdir(exist_ok=True)
        shutil.copy2(ROOT / 'target' / args.profile / name, target)
    shutil.copytree(ROOT / 'runtime', output / 'runtime', ignore=shutil.ignore_patterns('*.tgz', '*.zip'))
    shutil.copytree(ROOT / 'assets', output / 'assets')
    shutil.copy2(ROOT / 'tools/install_desktop.py', output / 'install.py')
    notices = output / 'notices'
    notices.mkdir()
    shutil.copy2(ROOT.parent / 'LICENSE', notices / 'Stack-Apache-2.0.txt')
    metadata = json.loads(subprocess.check_output(['cargo', 'metadata', '--manifest-path', str(ROOT / 'Cargo.toml'), '--locked', '--offline', '--format-version', '1', '--filter-platform', 'x86_64-unknown-linux-gnu'], text=True))
    inventory = []
    for package in metadata['packages']:
        base = Path(package['manifest_path']).parent
        target = notices / f"{package['name']}-{package['version']}"
        target.mkdir(exist_ok=True)
        for file in base.iterdir():
            if file.is_file() and file.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE', 'COPYRIGHT')):
                shutil.copy2(file, target / file.name)
        if not any(target.iterdir()):
            # Workspace crates commonly keep their license at the workspace root.
            for parent in base.parents:
                candidates = [p for p in parent.iterdir() if p.is_file() and p.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE', 'COPYRIGHT'))]
                if candidates:
                    for file in candidates:
                        shutil.copy2(file, target / file.name)
                    break
                if parent == Path('/tmp/stack-desktop-dev/cargo-home') or parent == ROOT.parent:
                    break
        inventory.append({'name': package['name'], 'version': package['version'], 'license': package['license'], 'source': package['source']})
    (notices / 'dependencies.json').write_text(json.dumps(inventory, indent=2) + '\n')
    wrapper = '''#!/bin/sh
set -eu
stack_install_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export STACK_PDFIUM_LIBRARY="$stack_install_root/runtime/pdfium/lib/libpdfium.so"
export STACK_TESSERACT_LIBRARY="$stack_install_root/runtime/ocr/lib/libtesseract.so.5"
export STACK_TESSDATA_DIR="$stack_install_root/runtime/ocr/tessdata"
export LD_LIBRARY_PATH="$stack_install_root/runtime/ocr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export OMP_THREAD_LIMIT=1
exec "$stack_install_root/bin/stack-desktop" "$@"
'''
    (output / 'stack').write_text(wrapper)
    (output / 'stack').chmod(0o755)
    sources = [p for p in ROOT.rglob('*') if p.is_file() and p.suffix in ('.rs', '.toml', '.lock', '.py') and not any(part in ('target', 'runtime') for part in p.relative_to(ROOT).parts)]
    receipt = {'format': 'org.stackdocs.desktop.install', 'version': 1, 'profile': args.profile,
               'source_base': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
               'platform': 'Ubuntu 24.04 x86-64; COSMIC/Wayland; system Wayland/xkbcommon and OCR dependency ABI required',
               'native_user_acceptance': 'pending',
               'source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
               'files': {str(p.relative_to(output)): hashlib.sha256(p.read_bytes()).hexdigest() for p in output.rglob('*') if p.is_file()}}
    (output / 'INSTALL.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(output)


if __name__ == '__main__':
    main()
