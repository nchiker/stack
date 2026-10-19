package org.stackdocs.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Read-only row digests: prove existing values survive an authorized additive default. No values/IDs leave the device. */
final class PreservationProjection {
    static JSONObject read(Context context)throws Exception {
        JSONObject tables=new JSONObject(),result=new JSONObject().put("tables",tables),revisions=new JSONObject(),automatic=new JSONObject();JSONObject links=new JSONObject(),tags=new JSONObject();
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(context.getDatabasePath("library.db").getPath(),null,SQLiteDatabase.OPEN_READONLY)){
            try(Cursor c=db.rawQuery("SELECT count(*) FROM metadata_work WHERE status IN ('pending','running') OR rule<>?",new String[]{"local-details-6"})){c.moveToFirst();result.put("metadata_work_active",c.getInt(0));}
            JSONObject ocrRows=new JSONObject(),pending=new JSONObject();
            try(Cursor c=db.rawQuery("SELECT id,ocr_text,ocr_engine,language,content_revision,text_revision,ocr_config,text_config,ocr_state FROM pages ORDER BY id",null)) {
                while(c.moveToNext()){String[] values=new String[8];for(int n=0;n<8;n++)values[n]=c.isNull(n)?"<null>":c.getString(n);String id=key("pages",c.getString(0));ocrRows.put(id,key("ocr",values));
                    if(c.getString(8).equals("pending")||c.getString(8).equals("running"))pending.put(id,true);
                }
            }
            result.put("saved_ocr_rows",ocrRows).put("pending_ocr_rows",pending);
            rows(db,tables,"documents","SELECT id,notes,notes_revision FROM documents ORDER BY id",1);
            rows(db,tables,"details","SELECT document_id,field_id,kind,label,value,qualifier,origin,state,evidence,revision FROM details ORDER BY document_id,field_id",2);
            rows(db,tables,"tags","SELECT id,name FROM tags ORDER BY id",1);
            rows(db,tables,"document_tags","SELECT document_id,tag_id,field_id FROM document_tags ORDER BY document_id,field_id",3);
            rows(db,tables,"editor_drafts","SELECT document_id,field_id,value,base_revision FROM editor_drafts ORDER BY document_id,field_id",2);
            rows(db,tables,"photo_history","SELECT source,digest,state,document_id FROM photo_history ORDER BY source",1);
            rows(db,tables,"operations","SELECT id,kind,state,created_at FROM operations ORDER BY id",1);
            rows(db,tables,"operation_items","SELECT operation_id,position,source,document_id,state,error FROM operation_items ORDER BY operation_id,position",2);
            rows(db,tables,"restore_commits","SELECT id FROM restore_commits ORDER BY id",1);
            try(Cursor c=db.rawQuery("SELECT id,metadata_revision FROM documents",null)){while(c.moveToNext())revisions.put(key("documents",c.getString(0)),c.getLong(1));}
            try(Cursor c=db.rawQuery("SELECT document_id,field_id,evidence FROM details WHERE origin='accepted' AND state='saved'",null)){while(c.moveToNext()){
                if(!new JSONObject(c.getString(2)).optString("acceptance_actor").equals("automatic"))continue;
                String doc=c.getString(0),field=c.getString(1);automatic.put(key("details",doc,field),key("documents",doc));
                try(Cursor link=db.rawQuery("SELECT tag_id FROM document_tags WHERE document_id=? AND field_id=?",new String[]{doc,field})){while(link.moveToNext()){String detail=key("details",doc,field),tag=key("tags",link.getString(0));links.put(key("document_tags",doc,link.getString(0),field),detail);if(!tags.has(tag))tags.put(tag,new JSONArray());tags.getJSONArray(tag).put(detail);}}
            }}
        }
        return result.put("revisions",revisions).put("automatic_rows",automatic).put("automatic_relations",links).put("automatic_tags",tags);
    }
    private static void rows(SQLiteDatabase db,JSONObject tables,String table,String query,int keys)throws Exception {
        JSONObject rows=new JSONObject();try(Cursor c=db.rawQuery(query,null)){while(c.moveToNext()){String[] values=new String[c.getColumnCount()];for(int i=0;i<values.length;i++)values[i]=c.isNull(i)?"<null>":c.getString(i);rows.put(key(table,java.util.Arrays.copyOf(values,keys)),key(table,values));}}tables.put(table,rows);
    }
    private static String key(String table,String... values)throws Exception {MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(table.getBytes(StandardCharsets.UTF_8));for(String value:values)hash.update((value.length()+":"+value).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
