package org.stackdocs.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Read-only fingerprint, deliberately excludes recognized text and queue state. No contents leave the phone. */
final class PreservationChecks {
    static String fingerprint(Context context) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        int[] counts=new int[4]; int table=0;
        StringBuilder processing=new StringBuilder();
        MessageDigest p1=MessageDigest.getInstance("SHA-256"),ocr=MessageDigest.getInstance("SHA-256"),order=MessageDigest.getInstance("SHA-256");
        try (SQLiteDatabase db=SQLiteDatabase.openDatabase(context.getDatabasePath("library.db").getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            String[] queries={
                "SELECT id,title,mime,added_at,page_count,title_user_edited,initial_page_count,source_scan_id FROM documents ORDER BY id",
                "SELECT id,document_id,position,source_page,rotation,crop,enhancement,source_file,flatten FROM pages ORDER BY id",
                "SELECT id,name,color,visible FROM stacks ORDER BY id",
                "SELECT document_id,stack_id FROM document_stacks ORDER BY document_id,stack_id"};
            for (String query:queries) {
                hash.update(query.getBytes(StandardCharsets.UTF_8));
                try (Cursor row=db.rawQuery(query,null)) {
                    while(row.moveToNext()) {
                        counts[table]++;
                        for(int col=0;col<row.getColumnCount();col++) {
                            String value=row.isNull(col) ? "<null>" : row.getString(col);
                            hash.update((value.length()+":"+value).getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
                table++;
            }
            try(Cursor rows=db.rawQuery("SELECT id FROM stacks ORDER BY "+(db.getVersion()>=8?"sort_order,rowid":"rowid"),null)) {
                int position=0;while(rows.moveToNext()){String value=(position++)+":"+rows.getString(0);order.update((value.length()+":"+value).getBytes(StandardCharsets.UTF_8));}
            }
            if(db.getVersion()>=5) {
                String[] decisions={"SELECT id,title_origin,filing_origin,title_decision,filing_decision FROM documents ORDER BY id",
                        "SELECT document_id,evidence,field,choice,value FROM suggestion_choices ORDER BY document_id,evidence,field"};
                for(String query:decisions) {
                    hash.update(query.getBytes(StandardCharsets.UTF_8));
                    try(Cursor row=db.rawQuery(query,null)){while(row.moveToNext())for(int col=0;col<row.getColumnCount();col++){String value=row.isNull(col)?"<null>":row.getString(col);hash.update((value.length()+":"+value).getBytes(StandardCharsets.UTF_8));}}
                }
            }
            String[] p1Queries={"SELECT id,notes,notes_revision,metadata_revision FROM documents ORDER BY id",
                    "SELECT document_id,field_id,kind,label,value,qualifier,origin,state,evidence,revision FROM details ORDER BY document_id,field_id",
                    "SELECT id,name FROM tags ORDER BY id", "SELECT document_id,tag_id,field_id FROM document_tags ORDER BY document_id,field_id",
                    "SELECT document_id,field_id,value,base_revision FROM editor_drafts ORDER BY document_id,field_id",
                    "SELECT source,digest,state,document_id FROM photo_history ORDER BY source",
                    "SELECT id,kind,state,created_at FROM operations ORDER BY id",
                    "SELECT operation_id,position,source,document_id,state,error FROM operation_items ORDER BY operation_id,position",
                    "SELECT id FROM restore_commits ORDER BY id"};
            for(int index=0;index<p1Queries.length;index++){
                String query=p1Queries[index];p1.update(query.getBytes(StandardCharsets.UTF_8));
                if(db.getVersion()<7&&index>0&&!query.contains("restore_commits"))continue;
                if(query.contains("restore_commits")&&db.getVersion()<6)continue;
                try(Cursor rows=db.rawQuery(db.getVersion()<7&&index==0?"SELECT id,'' AS notes,0 AS notes_revision,0 AS metadata_revision FROM documents ORDER BY id":query,null)){
                    while(rows.moveToNext())for(int col=0;col<rows.getColumnCount();col++){String value=rows.isNull(col)?"<null>":rows.getString(col);p1.update((value.length()+":"+value).getBytes(StandardCharsets.UTF_8));}
                }
            }
            if(db.getVersion()>=4) {
                try(Cursor row=db.rawQuery("SELECT id,ocr_text,ocr_engine,language,content_revision,text_revision,ocr_config,text_config FROM pages ORDER BY id",null)) {
                    while(row.moveToNext())for(int col=0;col<row.getColumnCount();col++){String value=row.isNull(col)?"<null>":row.getString(col);ocr.update((value.length()+":"+value).getBytes(StandardCharsets.UTF_8));}
                }
                try(Cursor row=db.rawQuery("SELECT ocr_state,count(*) FROM pages GROUP BY ocr_state ORDER BY ocr_state",null)) {
                    while(row.moveToNext()) processing.append(' ').append(row.getString(0)).append('=').append(row.getInt(1));
                }
            }
        }
        StringBuilder hex=new StringBuilder(); for(byte b:hash.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return "Preservation docs="+counts[0]+" pages="+counts[1]+" stacks="+counts[2]+" memberships="+counts[3]+" metadata_sha256="+hex+" p1_authoritative_drafts_sha256="+hex(p1.digest())+" stack_order_sha256="+hex(order.digest())+" saved_ocr_sha256="+hex(ocr.digest())+" originals_draft_recovery_sha256="+files(context)+" photos_opt_in="+context.getSharedPreferences("local_policy",Context.MODE_PRIVATE).getBoolean("photos",false)+" lock_enabled="+context.getSharedPreferences("local_policy",Context.MODE_PRIVATE).getBoolean("app_lock",false)+" notifications_opt_in="+context.getSharedPreferences("local_policy",Context.MODE_PRIVATE).getBoolean("import_notifications",false)+processing;
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String files(Context context)throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String root:new String[]{"documents","scan_draft","consolidation_recovery"})fingerprintFiles(context.getFilesDir(),new java.io.File(context.getFilesDir(),root),hash);
        StringBuilder out=new StringBuilder();for(byte b:hash.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static void fingerprintFiles(java.io.File base,java.io.File file,MessageDigest hash)throws Exception {
        if(file.isDirectory()){java.io.File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Private directory unavailable");java.util.Arrays.sort(children,java.util.Comparator.comparing(java.io.File::getName));for(java.io.File child:children)fingerprintFiles(base,child,hash);}
        else if(file.isFile()) {
            String path=base.toURI().relativize(file.toURI()).getPath();hash.update((path.length()+":"+path+":"+file.length()+":").getBytes(StandardCharsets.UTF_8));
            try(java.io.InputStream input=new java.io.FileInputStream(file)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)hash.update(buffer,0,count);}
        }
    }
}
