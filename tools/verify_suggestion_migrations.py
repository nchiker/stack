#!/usr/bin/env python3
"""Execute actual Java schema SQL against host SQLite, before upgrading a populated phone.
Android device checks remain necessary; stubs only record statements, not database behavior.
Historical source is read from Git. All row values are synthetic.
"""
import base64, pathlib, re, sqlite3, subprocess, tempfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCE='app/src/main/java/org/stackdocs/app/LibraryDatabase.java'
HISTORY={1:'8a2e8db',2:'71048e6',3:'8615b8e',4:'00483fc',5:'4afbefa',6:'1c4d795',7:'1bd1bf1'}
STUBS={
'android/content/Context.java':'package android.content; public class Context {}',
'android/content/ContentValues.java':'''package android.content; public class ContentValues extends java.util.LinkedHashMap<String,Object> { public void put(String k,int v){super.put(k,v);} public void put(String k,String v){super.put(k,v);} }''',
'android/graphics/Color.java':'package android.graphics; public class Color { public static int parseColor(String s){return 123;} }',
'android/database/sqlite/SQLiteOpenHelper.java':'''package android.database.sqlite; public abstract class SQLiteOpenHelper { public SQLiteOpenHelper(android.content.Context c,String s,Object o,int v){} public void setWriteAheadLoggingEnabled(boolean b){} public void onConfigure(SQLiteDatabase d){} public abstract void onCreate(SQLiteDatabase d); public abstract void onUpgrade(SQLiteDatabase d,int a,int b); }''',
'android/database/sqlite/SQLiteDatabase.java':'''package android.database.sqlite; public class SQLiteDatabase { public void setForeignKeyConstraintsEnabled(boolean b){} public void execSQL(String sql){System.out.println(java.util.Base64.getEncoder().encodeToString(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));} public long insertOrThrow(String t,Object n,android.content.ContentValues v){String cols=String.join(",",v.keySet()); java.util.List<String> vals=new java.util.ArrayList<>(); for(Object x:v.values()) vals.add("'"+x.toString().replace("'","''")+"'"); execSQL("INSERT INTO "+t+"("+cols+") VALUES("+String.join(",",vals)+")"); return 1;} }''',
'org/stackdocs/app/Probe.java':'''package org.stackdocs.app; public class Probe { public static void main(String[] a){LibraryDatabase h=new LibraryDatabase(new android.content.Context()); android.database.sqlite.SQLiteDatabase d=new android.database.sqlite.SQLiteDatabase(); if(a[0].equals("create"))h.onCreate(d);else h.onUpgrade(d,Integer.parseInt(a[0]),8);} }'''
}
with tempfile.TemporaryDirectory(prefix='stack-schema-') as tmp:
    root=pathlib.Path(tmp)
    for path,text in STUBS.items():
        target=root/path; target.parent.mkdir(parents=True,exist_ok=True); target.write_text(text)
    model=(ROOT/'app/src/main/java/org/stackdocs/app/OcrModel.java').read_text()
    constants='\n'.join(re.findall(r'    static final String (?:SHA256|CONFIG) = .*?;',model))
    (root/'org/stackdocs/app/OcrModel.java').write_text('package org.stackdocs.app; class OcrModel { '+constants+' }')
    def statements(source,argument):
        (root/'org/stackdocs/app/LibraryDatabase.java').write_text(source)
        subprocess.run(['javac','-d',str(root/'classes'),*[str(p) for p in root.rglob('*.java')]],check=True,capture_output=True)
        output=subprocess.check_output(['java','-cp',str(root/'classes'),'org.stackdocs.app.Probe',argument],text=True)
        return [base64.b64decode(line).decode() for line in output.splitlines()]
    current=(ROOT/SOURCE).read_text()
    for version,commit in HISTORY.items():
        source=subprocess.check_output(['git','show',f'{commit}:Stack/{SOURCE}'],cwd=ROOT,text=True)
        db=sqlite3.connect(':memory:'); db.execute('PRAGMA foreign_keys=ON')
        for sql in statements(source,'create'): db.execute(sql)
        if version==1:
            db.execute("INSERT INTO documents(id,title,mime,added_at,page_count,title_user_edited) VALUES('specimen','Manual name','image/jpeg',123,1,1)")
        else:
            db.execute("INSERT INTO documents(id,title,mime,added_at,page_count,title_user_edited,initial_page_count) VALUES('specimen','Manual name','image/jpeg',123,1,1,1)")
        db.execute("INSERT INTO pages(id,document_id,position,source_page,rotation,crop,enhancement,ocr_text) VALUES('page','specimen',0,0,90,'0.1','bright','synthetic preserved text')")
        db.execute("INSERT INTO document_stacks VALUES('specimen','bills')")
        db.execute("INSERT INTO search_index VALUES('specimen','Manual name','synthetic preserved text')")
        # Compare every legacy field except intentionally reset OCR queue state.
        columns={t:[r[1] for r in db.execute(f'PRAGMA table_info({t})') if r[1] not in ('ocr_state','ocr_attempts','ocr_claim','ocr_error')] for t in ('documents','pages','stacks','document_stacks')}
        before={t:db.execute(f'SELECT {",".join(cols)} FROM {t} ORDER BY 1').fetchall() for t,cols in columns.items()}
        for sql in statements(current,str(version)): db.execute(sql)
        for t,cols in columns.items(): assert before[t]==db.execute(f'SELECT {",".join(cols)} FROM {t} ORDER BY 1').fetchall(),(version,t)
        assert db.execute('SELECT title_origin,filing_origin FROM documents').fetchone()==(('legacy','legacy') if version<5 else ('default','default'))
        assert db.execute('SELECT count(*) FROM suggestions').fetchone()==(1,)
        assert db.execute('SELECT notes,notes_revision,metadata_revision FROM documents').fetchone()==('',0,0)
        assert db.execute('SELECT count(*) FROM details').fetchone()==(0,)
        assert db.execute('SELECT count(*) FROM metadata_work').fetchone()==(1,)
        assert not db.execute('PRAGMA foreign_key_check').fetchall()
        print(f'PASS actual Java v{version} → v8 SQL; all legacy values preserved, proposals additive, foreign keys valid')
