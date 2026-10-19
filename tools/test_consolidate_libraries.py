"""Synthetic preservation checks for the one-time, offline library consolidation."""
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from consolidate_libraries import consolidate, originals


class ConsolidationChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.regular = self.fixture('regular', 1)
        self.preview = self.fixture('preview', 3)
        self.output = self.root / 'merged'

    def tearDown(self):
        self.temp.cleanup()

    def fixture(self, name, version):
        root = self.root / name
        (root / 'databases').mkdir(parents=True)
        source = root / 'files/documents' / name / 'source'
        source.parent.mkdir(parents=True); source.write_bytes(('original-' + name).encode())
        (root / 'files/scan_draft').mkdir()
        with sqlite3.connect(root / 'databases/library.db') as db:
            db.executescript('''
                CREATE TABLE documents(id TEXT PRIMARY KEY,title TEXT,mime TEXT,added_at INTEGER,page_count INTEGER,title_user_edited INTEGER);
                CREATE TABLE pages(id TEXT PRIMARY KEY,document_id TEXT REFERENCES documents(id),position INTEGER,source_page INTEGER,rotation INTEGER,crop TEXT,enhancement TEXT,ocr_text TEXT,ocr_state TEXT,ocr_engine TEXT,language TEXT);
                CREATE TABLE stacks(id TEXT PRIMARY KEY,name TEXT COLLATE NOCASE UNIQUE,color INTEGER,visible INTEGER);
                CREATE TABLE document_stacks(document_id TEXT REFERENCES documents(id),stack_id TEXT REFERENCES stacks(id),PRIMARY KEY(document_id,stack_id));
                CREATE VIRTUAL TABLE search_index USING fts4(document_id,title,body,notindexed=document_id);
            ''')
            db.execute('INSERT INTO documents VALUES(?,?,?,?,?,?)', (name, name + ' title', 'image/png', 123, 1, 1))
            db.execute('INSERT INTO pages VALUES(?,?,?,?,?,?,?,?,?,?,?)', (name + '-page', name, 0, 0, 90, '[0.1,0.1,0.9,0.1,0.9,0.9,0.1,0.9]', 'bright', 'retained text', 'succeeded', 'fixture', 'eng'))
            db.execute("INSERT INTO stacks VALUES('shared','Shared',123,1)")
            db.execute("INSERT INTO document_stacks VALUES(?,'shared')", (name,))
            db.execute('INSERT INTO search_index VALUES(?,?,?)', (name, name + ' title', 'retained text'))
            if version >= 2:
                db.execute("ALTER TABLE pages ADD COLUMN source_file TEXT NOT NULL DEFAULT 'source'")
                db.execute('ALTER TABLE documents ADD COLUMN initial_page_count INTEGER NOT NULL DEFAULT 0')
                db.execute('UPDATE documents SET initial_page_count=page_count')
                db.execute('ALTER TABLE documents ADD COLUMN source_scan_id TEXT')
                db.execute('CREATE UNIQUE INDEX scan_source ON documents(source_scan_id)')
            if version >= 3:
                db.execute('ALTER TABLE pages ADD COLUMN flatten INTEGER NOT NULL DEFAULT 0')
                db.execute('UPDATE pages SET flatten=1')
            db.execute('PRAGMA user_version=' + str(version))
        return root

    def test_union_retains_edits_text_removed_sources_and_inputs(self):
        extra = self.regular / 'files/documents/regular/removed-source.jpg'; extra.write_bytes(b'removed but retained')
        before = originals(self.regular), originals(self.preview)
        report = consolidate(self.regular, self.preview, self.output)
        self.assertEqual((2, 2, 1, 3), tuple(report[k] for k in ('documents', 'pages', 'stacks', 'original_files')))
        self.assertEqual(before, (originals(self.regular), originals(self.preview)))
        with sqlite3.connect(self.output / 'databases/library.db') as db:
            self.assertEqual((3,), db.execute('PRAGMA user_version').fetchone())
            self.assertEqual([(90, 'bright', 1, 'retained text'), (90, 'bright', 0, 'retained text')], db.execute('SELECT rotation,enhancement,flatten,ocr_text FROM pages ORDER BY id').fetchall())
            self.assertEqual((2,), db.execute("SELECT count(*) FROM search_index WHERE search_index MATCH 'retained'").fetchone())
        with sqlite3.connect(self.regular / 'databases/library.db') as db:
            self.assertEqual((1,), db.execute('PRAGMA user_version').fetchone())

    def test_schema_two_supported(self):
        regular = self.fixture('version-two', 2)
        consolidate(regular, self.preview, self.output)
        with sqlite3.connect(self.output / 'databases/library.db') as db:
            self.assertEqual((0,), db.execute("SELECT flatten FROM pages WHERE document_id='version-two'").fetchone())

    def test_conflicting_stack_blocks_without_output(self):
        with sqlite3.connect(self.preview / 'databases/library.db') as db:
            db.execute('UPDATE stacks SET visible=0')
        with self.assertRaises(ValueError):
            consolidate(self.regular, self.preview, self.output)
        self.assertFalse(self.output.exists())

    def test_colliding_identity_blocks_without_output(self):
        with self.assertRaises(ValueError):
            consolidate(self.regular, self.regular, self.output)
        self.assertFalse(self.output.exists())

    def test_two_unfinished_scans_block_without_output(self):
        for root in (self.regular, self.preview):
            (root / 'files/scan_draft/draft.json').write_text(json.dumps({'pages': [{'id': 'fixture'}]}))
        with self.assertRaises(ValueError):
            consolidate(self.regular, self.preview, self.output)
        self.assertFalse(self.output.exists())

    def test_preview_draft_and_custom_stack_preserved(self):
        manifest = self.preview / 'files/scan_draft/draft.json'
        manifest.write_text(json.dumps({'id': 'draft', 'version': 1, 'pages': [{'id': 'page', 'flatten': True}]}))
        image = manifest.parent / 'page.jpg'; image.write_bytes(b'original unfinished page')
        with sqlite3.connect(self.preview / 'databases/library.db') as db:
            db.execute("INSERT INTO stacks VALUES('custom','Custom',456,0)")
            db.execute("INSERT INTO document_stacks VALUES('preview','custom')")
        consolidate(self.regular, self.preview, self.output)
        self.assertEqual(manifest.read_bytes(), (self.output / 'files/scan_draft/draft.json').read_bytes())
        self.assertEqual(image.read_bytes(), (self.output / 'files/scan_draft/page.jpg').read_bytes())
        with sqlite3.connect(self.output / 'databases/library.db') as db:
            self.assertEqual((2,), db.execute('SELECT count(*) FROM stacks').fetchone())
            self.assertEqual((3,), db.execute('SELECT count(*) FROM document_stacks').fetchone())


if __name__ == '__main__':
    unittest.main()
