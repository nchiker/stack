#!/usr/bin/env python3
"""Offline, fail-closed consolidation of stopped Stack library snapshots.

Inputs and output contain private user data. Keep them outside Git with private
permissions. This prepares and verifies a payload; it never installs or removes
an app. Package removal is a separate step after device read-back verification.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3


def database(snapshot):
    source = sqlite3.connect((snapshot / 'databases/library.db').resolve().as_uri() + '?mode=ro', uri=True)
    db = sqlite3.connect(':memory:')
    source.backup(db)
    source.close()
    db.row_factory = sqlite3.Row
    version = db.execute('PRAGMA user_version').fetchone()[0]
    if version not in (1, 2, 3):
        raise ValueError('Unsupported library schema')
    if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
        raise ValueError('Input database integrity check failed')
    if version == 1:
        db.execute("ALTER TABLE pages ADD COLUMN source_file TEXT NOT NULL DEFAULT 'source'")
        db.execute('ALTER TABLE documents ADD COLUMN initial_page_count INTEGER NOT NULL DEFAULT 0')
        db.execute('UPDATE documents SET initial_page_count=page_count')
        db.execute('ALTER TABLE documents ADD COLUMN source_scan_id TEXT')
        db.execute('CREATE UNIQUE INDEX scan_source ON documents(source_scan_id)')
    if version < 3:
        db.execute('ALTER TABLE pages ADD COLUMN flatten INTEGER NOT NULL DEFAULT 0')
    db.execute('PRAGMA user_version=3')
    db.commit()
    return db


def rows(db, table):
    return [dict(row) for row in db.execute('SELECT * FROM ' + table)]


def insert(db, table, row):
    columns = ','.join(row)
    placeholders = ','.join('?' for _ in row)
    db.execute(f'INSERT INTO {table}({columns}) VALUES({placeholders})', list(row.values()))


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def active_draft(snapshot):
    folder = snapshot / 'files/scan_draft'
    # AtomicFile restores .bak first when it exists.
    manifest = folder / 'draft.json.bak'
    if not manifest.exists():
        manifest = folder / 'draft.json'
    return manifest.exists() and bool(json.loads(manifest.read_text())['pages'])


def originals(snapshot):
    root = snapshot / 'files/documents'
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('Symlink in original files')
        if path.is_file():
            result[path.relative_to(root).as_posix()] = digest(path)
    return result


def validate_sources(snapshot, db):
    root = (snapshot / 'files/documents').resolve()
    for row in db.execute('SELECT document_id,source_file FROM pages'):
        path = root / row['document_id'] / row['source_file']
        if not path.resolve().is_relative_to(root) or not path.is_file():
            raise ValueError('Missing or unsafe page source')


def consolidate(regular, preview, output):
    regular, preview, output = map(lambda p: Path(p).resolve(), (regular, preview, output))
    if output.exists() or any(output == p or output.is_relative_to(p) or p.is_relative_to(output) for p in (regular, preview)):
        raise ValueError('Output must be a new directory outside both inputs')
    left, right = database(regular), database(preview)
    for snapshot, db in ((regular, left), (preview, right)):
        validate_sources(snapshot, db)
    for table in ('documents', 'pages'):
        if {r['id'] for r in rows(left, table)} & {r['id'] for r in rows(right, table)}:
            raise ValueError('Conflicting document/page IDs; resolve before consolidation')
    source_scan_ids = {r['source_scan_id'] for r in rows(left, 'documents') if r['source_scan_id']}
    if source_scan_ids & {r['source_scan_id'] for r in rows(right, 'documents') if r['source_scan_id']}:
        raise ValueError('Conflicting scan identities')
    stacks = {r['id']: r for r in rows(left, 'stacks')}
    for row in rows(right, 'stacks'):
        if row['id'] in stacks:
            if row != stacks[row['id']]:
                raise ValueError('Shared stack settings differ; resolve before consolidation')
        elif left.execute('SELECT 1 FROM stacks WHERE name=? COLLATE NOCASE', (row['name'],)).fetchone():
            raise ValueError('Conflicting stack names')
    if active_draft(regular) and active_draft(preview):
        raise ValueError('Both libraries have unfinished scans; preserve them separately before proceeding')
    expected_files = originals(regular)
    for path, sha in originals(preview).items():
        if path in expected_files:
            raise ValueError('Conflicting original file paths')
        expected_files[path] = sha
    expected = {table: rows(left, table) + rows(right, table) for table in ('documents', 'pages', 'document_stacks', 'search_index')}
    for row in rows(right, 'stacks'):
        if row['id'] not in stacks:
            insert(left, 'stacks', row)
    for table in ('documents', 'pages', 'document_stacks', 'search_index'):
        for row in rows(right, table):
            insert(left, table, row)
    left.commit()
    if left.execute('PRAGMA integrity_check').fetchone()[0] != 'ok' or left.execute('PRAGMA foreign_key_check').fetchall():
        raise ValueError('Merged database integrity check failed')
    for table, values in expected.items():
        canonical = lambda records: sorted(json.dumps(r, sort_keys=True) for r in records)
        if canonical(rows(left, table)) != canonical(values):
            raise ValueError('Merged metadata differs from source rows')
    output.mkdir(mode=0o700)
    shutil.copytree(regular / 'files', output / 'files', ignore=shutil.ignore_patterns('checks'))
    # Keep the whole original directories, including sources of removed pages.
    for source in (preview / 'files/documents').iterdir():
        target = output / 'files/documents' / source.name
        if target.exists():
            raise ValueError('Conflicting original directory')
        if source.is_dir():
            shutil.copytree(source, target)
        else:
            shutil.copy2(source, target)
    recovery = output / 'files/consolidation_recovery'
    for source in (preview / 'files').iterdir():
        if source.name in ('documents', 'checks'):
            continue
        if source.name == 'scan_draft' and active_draft(preview):
            destination = output / 'files/scan_draft'
            if destination.exists():
                shutil.move(str(destination), str(output / 'files/previous_inactive_scan_draft'))
            shutil.copytree(source, destination)
        else:
            recovery.mkdir(exist_ok=True)
            destination = recovery / ('preview-' + source.name)
            if source.is_dir():
                shutil.copytree(source, destination)
            else:
                shutil.copy2(source, destination)
    (output / 'databases').mkdir()
    with sqlite3.connect(output / 'databases/library.db') as target:
        left.backup(target)
    if originals(output) != expected_files:
        raise ValueError('Original file hash verification failed')
    validate_sources(output, left)
    report = {'schema': 3, 'documents': len(expected['documents']), 'pages': len(expected['pages']),
              'stacks': len(rows(left, 'stacks')), 'original_files': len(expected_files),
              'source_rows_preserved': True, 'original_hashes_verified': True}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    left.close(); right.close()
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('regular', type=Path)
    parser.add_argument('preview', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    print(json.dumps(consolidate(args.regular, args.preview, args.output), sort_keys=True))
