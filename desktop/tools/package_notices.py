"""Offline notice inventory and complete vendored Rust/source archive."""
import gzip
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

PREFIXES = ('LICENSE', 'COPYING', 'NOTICE', 'COPYRIGHT')


def package(root, output, metadata):
    notices = output / 'notices'
    notices.mkdir()
    shutil.copy2(root.parent / 'LICENSE', notices / 'Stack-Apache-2.0.txt')
    upstream = root / 'notices/upstream'
    provenance = json.loads((upstream / 'PROVENANCE.json').read_text())
    for entry in provenance:
        data = (upstream / entry['local']).read_bytes()
        if hashlib.sha256(data).hexdigest() != entry['sha256']:
            raise ValueError('Prepared upstream notice changed: ' + entry['local'])
    shutil.copytree(upstream, notices / 'upstream')
    packages = {p['id']: p for p in metadata['packages']}
    nodes = {p['id']: p for p in metadata['resolve']['nodes']}
    todo = [p['id'] for p in packages.values() if p['name'] in ('stack-desktop', 'stack-cli', 'stack-transport')]
    reachable = set()
    while todo:
        key = todo.pop()
        if key in reachable:
            continue
        reachable.add(key)
        todo.extend(d['pkg'] for d in nodes[key]['deps'] if any(kind['kind'] != 'dev' for kind in d['dep_kinds']))
    inventory = []
    for key in sorted(reachable):
        dependency = packages[key]
        base = Path(dependency['manifest_path']).parent
        identity = f"{dependency['name']}-{dependency['version']}-{hashlib.sha256(key.encode()).hexdigest()[:8]}"
        target = notices / identity
        target.mkdir()
        for parent in [base, *base.parents]:
            candidates = [p for p in parent.iterdir() if p.is_file() and p.name.upper().startswith(PREFIXES)]
            if candidates:
                for file in candidates:
                    shutil.copy2(file, target / file.name)
                break
            if (parent / '.git').exists() or parent == root.parent or ('registry/src' in str(base) and parent == base):
                break
        supplements = [e for e in provenance if e['name'] == dependency['name'] and e['version'] == dependency['version']]
        for entry in supplements:
            shutil.copy2(upstream / entry['local'], target / entry['local'])
        if not any(target.iterdir()):
            raise ValueError('Missing prepared dependency notice: ' + identity)
        inventory.append({'name': dependency['name'], 'version': dependency['version'],
                          'license': dependency['license'], 'source': dependency['source'],
                          'authors': dependency['authors'], 'notices': identity,
                          'supplements': [e['local'] for e in supplements]})
    (notices / 'dependencies.json').write_text(json.dumps(inventory, indent=2) + '\n')
    (notices / 'README.txt').write_text(
        'This conservative dependency inventory includes build-time dependencies and some cfg-disabled platform code.\n'
        'Complete Rust and application desktop source is in SOURCE.tar.gz, including embedded attribution.\n'
        'Upstream notice URLs/revisions/hashes are in upstream/PROVENANCE.json.\n'
        'Some published crates omit standalone license files: declarations retain upstream authors and standard SPDX supplements are labeled.\n'
        'Native PDFium/OCR notices and engine provenance are in runtime/.\n'
        'To inspect/rebuild Rust source: extract SOURCE.tar.gz, cd source/desktop, cargo build --offline --locked --release.\n'
        'The recorded Rust toolchain and documented Ubuntu native development dependencies are required; runtimes are supplied separately in this package.\n')
    # Cargo supplies registry and git replacement configuration for the exact lock.
    # All dependency source is included, preserving MPL and embedded notices too.
    with tempfile.TemporaryDirectory(prefix='stack-package-source-', dir='/tmp') as temporary:
        vendor = Path(temporary) / 'vendor'
        config = subprocess.check_output(['cargo', 'vendor', '--offline', '--locked', '--respect-source-config', '--versioned-dirs',
                                          '--manifest-path', str(root / 'Cargo.toml'), str(vendor)], text=True)
        config = config.replace(str(vendor), 'vendor')
        archive = output / 'SOURCE.tar.gz'
        with archive.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed, tarfile.open(fileobj=compressed, mode='w|') as tar:
            def add(file, name):
                if file.is_symlink():
                    raise ValueError('Source symlink requires explicit review: ' + str(file))
                info = tar.gettarinfo(str(file), name)
                info.uid = info.gid = info.mtime = 0
                info.uname = info.gname = ''
                with file.open('rb') as content:
                    tar.addfile(info, content)
            for file in sorted(vendor.rglob('*')):
                if file.is_file():
                    add(file, 'source/desktop/vendor/' + str(file.relative_to(vendor)))
            listing = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root).split(b'\0')
            for name in sorted(set(listing)):
                if not name:
                    continue
                file = root / name.decode()
                if file.is_file() and not file.is_symlink():
                    add(file, 'source/desktop/' + name.decode())
            add(root.parent / 'LICENSE', 'source/desktop/LICENSE')
            payload = config.encode()
            info = tarfile.TarInfo('source/desktop/.cargo/config.toml')
            info.size = len(payload)
            info.mode = 0o644
            tar.addfile(info, io.BytesIO(payload))
    return len(inventory)
