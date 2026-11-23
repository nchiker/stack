#!/usr/bin/env python3
"""Build the native Debian package stack-docs_<version>_amd64.deb from an already built release workspace.

Offline. Does not install anything, publish anything, build with cargo, or touch a library or a key store.
Build first:  cargo build --release --offline --locked -p stack-desktop -p stack-cli -p stack-transport
The version is the newest release in the metainfo. The program finds its bundled PDF/OCR runtime relative to itself
(<prefix>/bin -> <prefix>/lib/stack-docs/runtime), so the package needs no environment variable and no first-run step.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
sys.path.insert(0, str(Path(__file__).resolve().parent))
from package_notices import package as package_notices

ROOT = Path(__file__).resolve().parents[1]
APP_ID = 'org.stackdocs.desktop'
PACKAGE = 'stack-docs'
LIB = f'usr/lib/{PACKAGE}'
BINARIES = ('stack-desktop', 'stack-cli', 'stack-transport')
# Libraries the graphics/UI toolkit opens at run time (so the linker scan cannot see them).
RUNTIME_DEPENDS = ['libwayland-client0', 'libegl1', 'libvulkan1', 'libx11-6', 'libx11-xcb1', 'libxkbcommon0']
RECOMMENDS = ['xdg-desktop-portal', 'xdg-desktop-portal-cosmic | xdg-desktop-portal-gtk', 'gnome-keyring | libsecret-1-0']
# The 138 MB source archive is too big for GitHub Pages: it is built next to the deb and published as a release asset.
SOURCE_URL = 'https://github.com/nchiker/stack/releases/tag/desktop-v{version}'
KEYRING = '/usr/share/keyrings/stack-docs-archive-keyring.gpg'
SOURCES_FILE = '/etc/apt/sources.list.d/stack-docs.sources'
SOURCES_TEXT = f'''Types: deb
URIs: https://nchiker.github.io/stack-flatpak/apt/
Suites: stable
Components: main
Architectures: amd64
Signed-By: {KEYRING}
'''
MAINTAINER = 'Stack contributors <https://github.com/nchiker/stack>'


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def version_from_metainfo():
    release = ET.parse(ROOT / f'packaging/{APP_ID}.metainfo.xml').find('releases/release')
    if release is None or not re.fullmatch(r'\d+\.\d+\.\d+', release.get('version', '')):
        raise SystemExit('List a numeric release in the metainfo first')
    return release.get('version')


def copy(src, dst, mode):
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
    dst.chmod(mode)


def normalise(tree):
    """Dirs 755; files 644, or 755 when they were executable or are shared libraries."""
    for path in [tree, *tree.rglob('*')]:
        if path.is_symlink():
            continue
        if path.is_dir():
            path.chmod(0o755)
        elif path.stat().st_mode & 0o111 or '.so' in path.name:
            path.chmod(0o755)
        else:
            path.chmod(0o644)


def shlibdeps(files, lib_dirs):
    """Depends from dpkg-shlibdeps (resolved against this Ubuntu 24.04 build host's package database)."""
    with tempfile.TemporaryDirectory() as work:
        (Path(work) / 'debian').mkdir()
        (Path(work) / 'debian/control').write_text(f'Source: {PACKAGE}\n\nPackage: {PACKAGE}\nArchitecture: amd64\n')
        command = ['dpkg-shlibdeps', '-O', '--ignore-missing-info']
        command += [f'-l{d}' for d in lib_dirs]
        command += [f'-e{f}' for f in files]
        result = subprocess.run(command, cwd=work, capture_output=True, text=True, check=True)
    line = [l for l in result.stdout.splitlines() if l.startswith('shlibs:Depends=')]
    if not line:
        raise SystemExit('dpkg-shlibdeps gave no Depends: ' + result.stderr)
    return [d.strip() for d in line[0].split('=', 1)[1].split(',') if d.strip()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path, help='fresh directory for the build')
    parser.add_argument('--profile', choices=('release',), default='release')
    args = parser.parse_args()
    output = args.output.absolute()
    if output.exists():
        parser.error('Choose a fresh output directory')
    target = ROOT / 'target' / args.profile
    for name in BINARIES:
        if not (target / name).is_file():
            parser.error(f'Build first (cargo build --release --offline --locked -p stack-desktop -p stack-cli -p stack-transport): {target / name}')
    newest = max(p.stat().st_mtime for p in ROOT.rglob('*') if p.is_file() and p.suffix in ('.rs', '.toml', '.lock')
                 and not any(part in ('target', 'runtime') for part in p.relative_to(ROOT).parts))
    for name in BINARIES:
        if (target / name).stat().st_mtime < newest:
            parser.error(f'Rebuild first: {name} is older than the source')
    for engine in ('pdfium/lib/libpdfium.so', 'ocr/ENGINE.json'):
        if not (ROOT / 'runtime' / engine).is_file():
            parser.error('Prepare the pinned PDFium and OCR runtimes first')
    version = version_from_metainfo()
    output.mkdir(parents=True, mode=0o700)
    tree = output / 'tree'
    for name in ('stack-desktop', 'stack-cli'):
        copy(target / name, tree / 'usr/bin' / name, 0o755)
    copy(target / 'stack-transport', tree / LIB / 'stack-transport', 0o755)
    shutil.copytree(ROOT / 'runtime', tree / LIB / 'runtime', ignore=shutil.ignore_patterns('*.tgz', '*.zip'))
    # The program refuses an OCR library whose hash is not the pinned one; check what is shipped before shipping it.
    pins = json.loads((ROOT / 'runtime/ocr/ENGINE.json').read_text())['files']
    for rel, digest in pins.items():
        if sha256(tree / LIB / 'runtime/ocr' / rel) != digest:
            raise SystemExit(f'Bundled OCR file does not match ENGINE.json: {rel}')
    # Desktop entry and metainfo: the installed program is stack-desktop (the Flatpak wrapper is called stack).
    desktop = (ROOT / f'packaging/{APP_ID}.desktop').read_text().replace('Exec=stack\n', 'Exec=stack-desktop\n')
    if 'Exec=stack-desktop\n' not in desktop:
        raise SystemExit('Desktop entry has no Exec=stack line to rewrite')
    metainfo = (ROOT / f'packaging/{APP_ID}.metainfo.xml').read_text().replace('<binary>stack</binary>', '<binary>stack-desktop</binary>')
    for path, text in ((tree / f'usr/share/applications/{APP_ID}.desktop', desktop),
                       (tree / f'usr/share/metainfo/{APP_ID}.metainfo.xml', metainfo)):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    copy(ROOT / f'assets/{APP_ID}.svg', tree / f'usr/share/icons/hicolor/scalable/apps/{APP_ID}.svg', 0o644)
    doc = tree / f'usr/share/doc/{PACKAGE}'
    doc.mkdir(parents=True)
    metadata = json.loads(subprocess.check_output(
        ['cargo', 'metadata', '--manifest-path', str(ROOT / 'Cargo.toml'), '--locked', '--offline', '--format-version', '1',
         '--filter-platform', 'x86_64-unknown-linux-gnu'], text=True, env={**os.environ}))
    package_notices(ROOT, doc, metadata, archive_dir=output, source_url=SOURCE_URL.format(version=version))
    (doc / 'copyright').write_text(
        'Stack is licensed under the Apache License 2.0 (notices/Stack-Apache-2.0.txt).\n'
        'Bundled third-party components (PDFium, Tesseract, Leptonica, Rust crates) and their licenses are listed under\n'
        f'/usr/share/doc/{PACKAGE}/notices/ and /usr/lib/{PACKAGE}/runtime/*/notices.\n')
    # Self-configuring updates: the package carries the PUBLIC signing key and adds the apt source on install (Chrome-style), so
    # one downloaded package is the whole first install and the system updater offers every later version.
    keyring = subprocess.check_output(['gpg', '--dearmor'], input=(ROOT / 'packaging/stack-apt-public.asc').read_bytes())
    (tree / KEYRING[1:]).parent.mkdir(parents=True, exist_ok=True)
    (tree / KEYRING[1:]).write_bytes(keyring)
    normalise(tree)
    # Dependencies: the linker scan of the programs and bundled engines, plus the toolkit's run-time libraries.
    lib_dirs = [tree / LIB / 'runtime/ocr/lib', tree / LIB / 'runtime/pdfium/lib']
    scanned = [tree / 'usr/bin/stack-desktop', tree / 'usr/bin/stack-cli', tree / LIB / 'stack-transport',
               tree / LIB / 'runtime/ocr/lib/libtesseract.so.5', tree / LIB / 'runtime/ocr/lib/liblept.so.5',
               tree / LIB / 'runtime/pdfium/lib/libpdfium.so']
    depends = shlibdeps(scanned, lib_dirs)
    for name in RUNTIME_DEPENDS:
        if not any(d.split(' ')[0] == name for d in depends):
            depends.append(name)
    files = sorted(p for p in tree.rglob('*') if p.is_file())
    size_kb = sum(p.stat().st_size for p in files) // 1024 + 1
    debian = tree / 'DEBIAN'
    debian.mkdir()
    (debian / 'control').write_text(
        f'Package: {PACKAGE}\nVersion: {version}\nSection: utils\nPriority: optional\nArchitecture: amd64\n'
        f'Maintainer: {MAINTAINER}\nInstalled-Size: {size_kb}\nDepends: {", ".join(depends)}\n'
        f'Recommends: {", ".join(RECOMMENDS)}\nHomepage: https://github.com/nchiker/stack\n'
        'Description: Stack document library for the COSMIC desktop\n'
        ' Import documents, organize them into stacks, search printed English text\n'
        ' offline and keep saved changes in sync with an approved nearby Android phone.\n'
        ' PDF preview and offline text reading are bundled; nothing is downloaded.\n')
    postinst = (
        '#!/bin/sh\nset -e\n'
        f'SRC={SOURCES_FILE}\n'
        '# Add the update source unless an earlier package (stack-docs-apt-source) or the user already has one.\n'
        'if [ "$1" = configure ] && [ ! -e "$SRC" ] && ! grep -rqs "nchiker.github.io/stack-flatpak/apt" /etc/apt/sources.list.d /etc/apt/sources.list 2>/dev/null; then\n'
        '  cat > "$SRC" <<EOF\n' + SOURCES_TEXT + 'EOF\n'
        '  chmod 644 "$SRC"\n'
        'fi\nexit 0\n')
    postrm = (
        '#!/bin/sh\nset -e\n'
        f'# Remove the update source with the program (not on upgrade), unless stack-docs-apt-source still owns it. The keyring file belongs to the\n'
        f'# package, so dpkg handles it; nothing here touches it on remove.\n'
        f'if {{ [ "$1" = remove ] || [ "$1" = purge ]; }} && [ ! -e /var/lib/dpkg/info/stack-docs-apt-source.list ]; then rm -f {SOURCES_FILE}; fi\nexit 0\n')
    for name, text in (('postinst', postinst), ('postrm', postrm)):
        (debian / name).write_text(text)
        (debian / name).chmod(0o755)
    (debian / 'md5sums').write_text(''.join(f'{hashlib.md5(p.read_bytes()).hexdigest()}  {p.relative_to(tree)}\n' for p in files))
    deb = output / f'{PACKAGE}_{version}_amd64.deb'
    subprocess.run(['dpkg-deb', '--root-owner-group', '-Zxz', '--build', str(tree), str(deb)], check=True, stdout=subprocess.DEVNULL)
    shutil.rmtree(tree)
    (output / 'SHA256SUMS').write_text(f'{sha256(deb)}  {deb.name}\n')
    print(deb)


if __name__ == '__main__':
    main()
