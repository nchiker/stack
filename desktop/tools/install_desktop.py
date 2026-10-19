#!/usr/bin/env python3
"""Install/update or uninstall prepared Stack files; libraries are never removed."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import os


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prefix', type=Path, default=Path.home() / '.local')
    parser.add_argument('--uninstall', action='store_true')
    args = parser.parse_args()
    prefix = args.prefix.absolute()
    target = prefix / 'lib/stack-desktop'
    launcher = prefix / 'share/applications/org.stackdocs.desktop.desktop'
    icon = prefix / 'share/icons/hicolor/scalable/apps/org.stackdocs.desktop.svg'
    command = prefix / 'bin/stack'
    if target.is_symlink():
        parser.error('Installation directory must not be a symbolic link')
    if args.uninstall:
        if not (target / 'INSTALL.json').is_file():
            parser.error('No owned Stack installation at this prefix')
        receipt = json.loads((target / 'INSTALL.json').read_text())
        if receipt.get('format') != 'org.stackdocs.desktop.install':
            parser.error('Unknown installation marker')
        shutil.rmtree(target)
        for path in (launcher, icon, command):
            if path.exists() or path.is_symlink():
                path.unlink()
        print('Stack application files removed. Your XDG library and preferences remain.')
        return
    source = Path(__file__).resolve().parent
    receipt = json.loads((source / 'INSTALL.json').read_text())
    if receipt.get('format') != 'org.stackdocs.desktop.install':
        parser.error('Run this script from a prepared Stack package')
    for relative, expected in receipt['files'].items():
        path = source / relative
        if path.is_symlink() or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            parser.error(f'Prepared file checksum failed: {relative}')
    backup = prefix / 'lib/stack-desktop.previous'
    if target.exists():
        if not (target / 'INSTALL.json').is_file():
            parser.error('Refusing to replace an unrelated directory')
        previous = json.loads((target / 'INSTALL.json').read_text())
        if previous.get('format') != 'org.stackdocs.desktop.install':
            parser.error('Refusing to replace an unrecognized installation')
        if backup.exists():
            parser.error('Retain/review the existing stack-desktop.previous before updating again')
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.parent / f'.stack-install-{os.getpid()}'
    shutil.copytree(source, temporary)
    if target.exists():
        target.rename(backup)
    try:
        temporary.rename(target)
    except OSError:
        if backup.exists() and not target.exists():
            backup.rename(target)
        raise
    for path in (launcher, icon, command):
        path.parent.mkdir(parents=True, exist_ok=True)
    command.write_text('#!/bin/sh\nexec ' + __import__('shlex').quote(str(target / 'stack')) + ' "$@"\n')
    command.chmod(0o755)
    # Desktop Entry escaping is not shell escaping. Quote the executable path.
    executable = str(target / 'stack').replace('\\', '\\\\').replace('"', '\\"').replace('`', '\\`').replace('$', '\\$')
    launcher.write_text(f'[Desktop Entry]\nType=Application\nName=Stack\nComment=Offline document library\nExec="{executable}"\nIcon=org.stackdocs.desktop\nTerminal=false\nCategories=Office;\nStartupWMClass=org.stackdocs.desktop\n')
    shutil.copy2(source / 'assets/org.stackdocs.desktop.svg', icon)
    print(f'Installed: {command}\nLaunch: {command}\nUninstall: python3 {target / "install.py"} --prefix {prefix} --uninstall')


if __name__ == '__main__':
    main()
