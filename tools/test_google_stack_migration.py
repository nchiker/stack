#!/usr/bin/env python3
"""Synthetic local migration regressions; never opens a real app backup/library."""
import hashlib,io,json,pathlib,sqlite3,struct,subprocess,tarfile,tempfile,unittest,zlib
from PIL import Image
import migrate_google_stack as migration

class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='stack-migration-synthetic-');self.root=pathlib.Path(self.temp.name)
        subprocess.run(['python3',str(pathlib.Path(__file__).with_name('make_p1_handson_fixtures.py')),str(self.root/'pdf')],check=True,stdout=subprocess.DEVNULL)
        image=self.root/'page.jpeg';Image.new('RGB',(400,600),'white').save(image,'JPEG')
        self.db=self.root/'metadata.sqlite';c=sqlite3.connect(self.db)
        c.executescript('''CREATE TABLE docs_table(doc_id TEXT,doc_name TEXT,doc_scan_date TEXT,doc_version INTEGER,file_path TEXT,note TEXT,name_status INTEGER,name_strategy INTEGER,need_to_file INTEGER);
CREATE TABLE pages_table(page_id TEXT,image_path TEXT,rotation INTEGER,color_enhancement INTEGER,top_left_x INTEGER,top_left_y INTEGER,top_right_x INTEGER,top_right_y INTEGER,bottom_right_x INTEGER,bottom_right_y INTEGER,bottom_left_x INTEGER,bottom_left_y INTEGER,crop_edited INTEGER,enhancement_edited INTEGER,page_source INTEGER);
CREATE TABLE doc_pages_table(page_id TEXT,doc_id TEXT,tag_type INTEGER);
CREATE TABLE tags_table(tag_id TEXT,tag_name TEXT,tag_group INTEGER);
CREATE TABLE stacks_table(stack_id TEXT,color INTEGER,show_on_home INTEGER);
CREATE TABLE doc_tags_table(tag_id TEXT,doc_id TEXT,tag_type INTEGER);
CREATE TABLE kv_table(doc_id TEXT,key TEXT,value TEXT,value_type INTEGER,location BLOB,timestamp BLOB,deleted INTEGER);
CREATE TABLE page_files_table(page_id TEXT,page_version INTEGER,file_uuid TEXT);''')
        c.execute('INSERT INTO docs_table VALUES(?,?,?,?,?,?,?,?,?)',('d1',' Synthetic receipt ','2026-10-01 12:00:00',1,'/data/user/0/com.area120.paperwork/files/receipt.pdf','Synthetic notes quartz',1,0,0))
        c.execute('INSERT INTO pages_table VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',('p1','/data/user/0/com.area120.paperwork/files/page.jpeg',90,2,1,2,399,2,399,599,1,599,1,1,0))
        c.execute("INSERT INTO doc_pages_table VALUES('p1','d1',0)")
        c.executemany('INSERT INTO tags_table VALUES(?,?,?)',[('bills','Bills',2),('custom','Synthetic category',3),('aux','Synthetic auxiliary label',1)])
        c.executemany('INSERT INTO stacks_table VALUES(?,?,?)',[('bills',0,1),('custom',0,0)])
        c.executemany('INSERT INTO doc_tags_table VALUES(?,?,?)',[('custom','d1',1),('bills','d1',3),('aux','d1',2)])
        c.executemany('INSERT INTO kv_table VALUES(?,?,?,?,?,?,?)',[('d1','Amount','$24.50',0,b'opaque-location',b'opaque-time',0),('d1','Removed date','2020-01-01',1,b'',b'',1),('d1','Empty field','',0,b'',b'',0)])
        c.execute("INSERT INTO page_files_table VALUES('p1',1,'opaque-version-id')");c.commit();c.close()
        self.media={'f/receipt.pdf':self.root/'pdf/P1-Receipt.pdf','f/page.jpeg':image}
    def tearDown(self):self.temp.cleanup()
    def convert(self):return migration.convert(self.db,self.media,'America/New_York',1,3)
    def test_authoritative_information_and_exclusions(self):
        graph,files,report=self.convert();self.assertEqual(report['memberships'],1);self.assertEqual(report['retained_jpeg_files'],1);self.assertEqual(report['excluded_relations'],1)
        self.assertEqual(graph['documents'][0]['title'],'Synthetic receipt');self.assertEqual(graph['documents'][0]['notes'],'Synthetic notes quartz');self.assertEqual(graph['documents'][0]['filing_origin'],'manual')
        fields={d['label']:d for d in graph['details']};self.assertEqual(fields['Amount']['value'],'$24.50');self.assertEqual(fields['Amount']['kind'],'text');self.assertEqual(json.loads(fields['Amount']['evidence'])['source_value_type'],0)
        self.assertEqual(fields['Removed date']['state'],'removed');self.assertEqual(fields['Excluded stack']['state'],'removed');self.assertEqual(fields['Excluded stack']['value'],'Bills');self.assertEqual(json.loads(fields['Empty field']['evidence'])['source_value'],'')
        source=json.loads(fields['Original scan date']['evidence']);self.assertEqual(source['source_title'],' Synthetic receipt ');self.assertEqual(len(source['unmapped_auxiliary_relations']),1)
    def test_portable_bytes_and_manifest(self):
        graph,files,_=self.convert();out=self.root/'synthetic.stackbackup';migration.write_archive(graph,files,out)
        with out.open('rb') as data:
            self.assertEqual(data.read(8),b'STACKBK\x01');size=struct.unpack('>i',data.read(4))[0];digest=data.read(32);manifest=data.read(size);self.assertEqual(hashlib.sha256(manifest).digest(),digest);self.assertEqual(json.loads(manifest)['version'],2)
            for entry in graph['files']:
                length=struct.unpack('>H',data.read(2))[0];self.assertEqual(data.read(length).decode(),entry['path']);count=struct.unpack('>q',data.read(8))[0];raw=data.read(count);self.assertEqual(hashlib.sha256(raw).hexdigest(),entry['sha256'])
            self.assertEqual(data.read(),b'')
        with self.assertRaises(migration.MigrationError):migration.write_archive(graph,files,out)
    def test_missing_pdf_and_original_fail(self):
        del self.media['f/receipt.pdf']
        with self.assertRaises(migration.MigrationError):self.convert()
        self.media['f/receipt.pdf']=self.root/'pdf/P1-Receipt.pdf';del self.media['f/page.jpeg']
        with self.assertRaises(migration.MigrationError):self.convert()
    def test_unknown_filing_state_fails(self):
        with sqlite3.connect(self.db) as c:c.execute("UPDATE doc_tags_table SET tag_type=9 WHERE tag_id='custom'")
        with self.assertRaises(migration.MigrationError):self.convert()
    def test_page_order_and_count_fail(self):
        with sqlite3.connect(self.db) as c:c.execute('UPDATE doc_pages_table SET tag_type=2')
        with self.assertRaises(migration.MigrationError):self.convert()
        with sqlite3.connect(self.db) as c:c.execute('UPDATE doc_pages_table SET tag_type=0')
        self.media['f/receipt.pdf']=self.root/'pdf/P1-Invoice.pdf'
        with self.assertRaises(migration.MigrationError):self.convert()
    def make_backup(self):
        tar=io.BytesIO()
        with tarfile.open(fileobj=tar,mode='w') as out:
            for path,name in [(self.db,'db/metadata'),(self.media['f/receipt.pdf'],'f/receipt.pdf'),(self.media['f/page.jpeg'],'f/page.jpeg')]:out.add(path,arcname='apps/com.area120.paperwork/'+name)
            raw=b'SYNTHETIC ACCOUNT STATE MUST NOT ENTER MIGRATION';info=tarfile.TarInfo('apps/com.area120.paperwork/sp/account.xml');info.size=len(raw);out.addfile(info,io.BytesIO(raw))
        target=self.root/'synthetic.ab';target.write_bytes(b'ANDROID BACKUP\n5\n1\nnone\n'+zlib.compress(tar.getvalue()));return target
    def test_backup_extraction_is_private_and_selective(self):
        path,media=migration.unpack(self.make_backup(),self.root/'work');graph,_,_=migration.convert(path,media,'America/New_York',1,3);self.assertEqual(len(graph['documents']),1)
        for file in (self.root/'work').iterdir():self.assertNotIn(b'SYNTHETIC ACCOUNT STATE',file.read_bytes())
    def test_unknown_encrypted_and_truncated_backups_fail(self):
        for header in (b'ANDROID BACKUP\n99\n0\nnone\n',b'ANDROID BACKUP\n5\n1\nAES-256\n',b'not a backup'):
            p=self.root/'bad.ab';p.write_bytes(header)
            with self.assertRaises(migration.MigrationError):migration.unpack(p,self.root/'work')
    def test_limit_and_no_silent_name_truncation(self):
        with sqlite3.connect(self.db) as c:c.execute('UPDATE docs_table SET doc_name=?',('X'*201,))
        with self.assertRaises(migration.MigrationError):self.convert()

def fixture(destination):
    case=MigrationTests();case.setUp()
    try:
        graph,files,_=case.convert();migration.write_archive(graph,files,pathlib.Path(destination))
    finally:case.tearDown()
if __name__=='__main__':
    import sys
    if len(sys.argv)==3 and sys.argv[1]=='--fixture':fixture(sys.argv[2])
    else:unittest.main()
