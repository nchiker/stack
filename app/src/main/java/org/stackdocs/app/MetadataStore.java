package org.stackdocs.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.*;
import java.util.function.Supplier;

/** Authoritative P1 state. All calls are serialized on repository IO; analysis is separate. */
final class MetadataStore {
    static final int NOTES_LIMIT=20000;
    final Supplier<SQLiteDatabase> database;
    final SuggestionStore suggestions;
    MetadataStore(Supplier<SQLiteDatabase> database,SuggestionStore suggestions){this.database=database;this.suggestions=suggestions;}
    static final class Snapshot {
        final SuggestionJob text;final long revision;final String input,claim;
        Snapshot(SuggestionJob text,long revision,String claim){this.text=text;this.revision=revision;this.claim=claim;input=text.input+":"+revision+":"+MetadataRules.VERSION;}
    }
    static final class View {
        final Snapshot snapshot;final String notes,status;final long notesRevision;
        final List<DetailValue> saved;final List<DetailValue> offered;final List<DetailValue> dismissed;
        View(Snapshot snapshot,String notes,long notesRevision,String status,List<DetailValue> saved,List<DetailValue> offered,List<DetailValue> dismissed){this.snapshot=snapshot;this.notes=notes;this.notesRevision=notesRevision;this.status=status;this.saved=saved;this.offered=offered;this.dismissed=dismissed;}
    }
    Snapshot snapshot(String id,String claim) {
        SuggestionJob text=suggestions.snapshot(id,"",0);if(text==null)return null;
        String incarnation=claim;if(incarnation.isEmpty())try(Cursor work=database.get().rawQuery("SELECT claim FROM metadata_work WHERE document_id=?",new String[]{id})){if(work.moveToFirst())incarnation=work.getString(0);}
        try(Cursor row=database.get().rawQuery("SELECT metadata_revision FROM documents WHERE id=?",new String[]{id})) {row.moveToFirst();return new Snapshot(text,row.getLong(0),incarnation);}
    }
    void recover() {
        database.get().execSQL("UPDATE metadata_work SET status=CASE WHEN attempts>=3 THEN 'failed' ELSE 'pending' END,claim='' WHERE status='running'");
        database.get().execSQL("UPDATE metadata_work SET status='pending',claim='',attempts=0 WHERE rule<>?",new Object[]{MetadataRules.VERSION});
    }
    Snapshot claim() {
        SQLiteDatabase db=database.get();List<String> ids=new ArrayList<>();
        try(Cursor rows=db.rawQuery("SELECT document_id FROM metadata_work WHERE status='pending' AND attempts<3 ORDER BY document_id",null)){while(rows.moveToNext())ids.add(rows.getString(0));}
        for(String id:ids) {
            Snapshot snap=snapshot(id,UUID.randomUUID().toString());if(snap==null)continue;
            db.execSQL("UPDATE metadata_work SET status=?,claim=?,input=?,rule=?,attempts=attempts+? WHERE document_id=?",new Object[]{snap.text.active?"waiting":"running",snap.text.active?"":snap.claim,snap.input,MetadataRules.VERSION,snap.text.active?0:1,id});
            if(!snap.text.active)return snap;
        }
        return null;
    }
    boolean complete(Snapshot snap,List<DetailValue> candidates,boolean failed) {
        SQLiteDatabase db=database.get();db.beginTransaction();
        try {
        if(!matches(snap,true))return false;
        JSONArray payload=new JSONArray();for(DetailValue value:candidates) {
            try {
                JSONObject evidence=new JSONObject(value.evidence);int position=evidence.getInt("page")-1;
                try(Cursor row=database.get().rawQuery("SELECT id,content_revision,text_revision FROM pages WHERE document_id=? AND position=?",new String[]{snap.text.documentId,Integer.toString(position)})) {
                    if(row.moveToFirst())evidence.put("page_id",row.getString(0)).put("content_revision",row.getLong(1)).put("text_revision",row.getLong(2));
                }
                JSONObject record=value.json();record.put("evidence",evidence.toString());payload.put(record);
            }catch(JSONException invalid){throw new IllegalStateException(invalid);}
        }
        // Fill only unowned slots. A draft holds the entire document revision stable.
        boolean draft;try(Cursor row=db.rawQuery("SELECT 1 FROM editor_drafts WHERE document_id=? LIMIT 1",new String[]{snap.text.documentId})){draft=row.moveToFirst();}
        Set<String> fields=new HashSet<>(),labels=new HashSet<>();
        try(Cursor rows=db.rawQuery("SELECT field_id,label FROM details WHERE document_id=?",new String[]{snap.text.documentId})){while(rows.moveToNext()){fields.add(rows.getString(0));labels.add(labelSlot(rows.getString(1)));}}
        long revision=snap.revision;
        if(!failed&&!draft&&!snap.text.active&&!snap.text.partial&&!snap.text.truncated)for(int i=0;i<payload.length();i++) {
            try {
                DetailValue value=DetailValue.read(payload.getJSONObject(i));
                if(fields.contains(value.field)||labels.contains(labelSlot(value.label)))continue;
                JSONObject evidence=new JSONObject(value.evidence).put("acceptance_actor","automatic");
                save(snap.text.documentId,new DetailValue(value.field,value.kind,value.label,value.value,value.qualifier,evidence.toString(),"accepted","saved",0),revision++);
                fields.add(value.field);labels.add(labelSlot(value.label));
            }catch(JSONException invalid){throw new IllegalStateException(invalid);}
        }
        // save() invalidates work via metadata_revision triggers; publish the fresh input/token atomically.
        Snapshot current=snapshot(snap.text.documentId,snap.claim);
        db.execSQL("UPDATE metadata_work SET status=?,payload=?,input=?,claim=?,rule=? WHERE document_id=?",new Object[]{failed?"failed":"ready",payload.toString(),current.input,snap.claim,MetadataRules.VERSION,snap.text.documentId});
        db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    private static String labelSlot(String label){String key=label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
        if(Arrays.asList("date","dateroleunknown","documentdate","invoicedate","statementdate").contains(key))return "date";
        if(Arrays.asList("total","totalamount","grandtotal").contains(key))return "total";
        if(Arrays.asList("amountdue","balancedue").contains(key))return "amountdue";
        for(String role:Arrays.asList("client","patient","invoice","account","customer","reference"))if(key.equals(role)||key.equals(role+"id")||key.equals(role+"number"))return role+"number";
        return key;
    }
    private boolean matches(Snapshot snap,boolean running) {
        Snapshot now=snapshot(snap.text.documentId,snap.claim);if(now==null||!now.input.equals(snap.input))return false;
        try(Cursor row=database.get().rawQuery("SELECT input,claim,status,rule FROM metadata_work WHERE document_id=?",new String[]{snap.text.documentId})) {
            return row.moveToFirst()&&row.getString(0).equals(snap.input)&&row.getString(3).equals(MetadataRules.VERSION)
                    &&row.getString(2).equals(running?"running":"ready")&&row.getString(1).equals(snap.claim);
        }
    }
    View view(String id) {
        SQLiteDatabase db=database.get();Snapshot snap=snapshot(id,"");if(snap==null)throw new IllegalArgumentException("Document no longer exists.");
        List<DetailValue> saved=new ArrayList<>(),offered=new ArrayList<>(),dismissed=new ArrayList<>();Set<String> decisions=new HashSet<>(),rejected=new HashSet<>(),labels=new HashSet<>();
        try(Cursor rows=db.rawQuery("SELECT * FROM details WHERE document_id=? ORDER BY field_id",new String[]{id})) {
            while(rows.moveToNext()) {DetailValue value=new DetailValue(str(rows,"field_id"),str(rows,"kind"),str(rows,"label"),str(rows,"value"),str(rows,"qualifier"),str(rows,"evidence"),str(rows,"origin"),str(rows,"state"),rows.getLong(rows.getColumnIndexOrThrow("revision")));
                decisions.add(value.field);labels.add(labelSlot(value.label));if(value.state.equals("saved"))saved.add(value);if(value.state.equals("rejected"))rejected.add(value.field);}
        }
        String status="pending";
        try(Cursor row=db.rawQuery("SELECT status,input,payload FROM metadata_work WHERE document_id=?",new String[]{id})) {
            if(row.moveToFirst()){status=row.getString(0);if(status.equals("ready")&&!row.getString(1).equals(snap.input))status="pending";
                if(status.equals("ready"))try {JSONArray payload=new JSONArray(row.getString(2));for(int i=0;i<payload.length();i++){DetailValue value=DetailValue.read(payload.getJSONObject(i));if(!decisions.contains(value.field)&&!labels.contains(labelSlot(value.label)))offered.add(value);else if(rejected.contains(value.field))dismissed.add(value);}}
                catch(JSONException invalid){status="failed";}}
        }
        try(Cursor row=db.rawQuery("SELECT notes,notes_revision FROM documents WHERE id=?",new String[]{id})) {row.moveToFirst();return new View(snap,row.getString(0),row.getLong(1),status,saved,offered,dismissed);}
    }
    boolean apply(View view,String field,String action,String value,String qualifier) {return apply(view,field,action,value,qualifier,null);}
    boolean apply(View view,String field,String action,String value,String qualifier,String label) {return apply(view,field,action,value,qualifier,label,false);}
    boolean reconsider(View view,String field,String action,String value,String qualifier,String label) {
        if(!action.equals("accepted")&&!action.equals("edited"))throw new IllegalArgumentException("Choose Apply or Edit to reconsider a dismissal.");
        return apply(view,field,action,value,qualifier,label,true);
    }
    private boolean apply(View view,String field,String action,String value,String qualifier,String label,boolean reconsider) {
        SQLiteDatabase db=database.get();db.beginTransaction();
        try {
            if(!matches(view.snapshot,false)){db.setTransactionSuccessful();return false;}
            // Re-read persisted candidates, never trust altered caller payloads.
            DetailValue candidate=null;for(DetailValue found:reconsider?view(view.snapshot.text.documentId).dismissed:view(view.snapshot.text.documentId).offered)if(found.field.equals(field))candidate=found;
            if(candidate==null){db.setTransactionSuccessful();return false;}
            if(!Arrays.asList("accepted","edited","dismissed").contains(action))throw new IllegalArgumentException("Unknown detail decision.");
            save(view.snapshot.text.documentId,new DetailValue(candidate.field,candidate.kind,label==null?candidate.label:label,action.equals("edited")?value:candidate.value,action.equals("edited")?qualifier:candidate.qualifier,candidate.evidence,action,action.equals("dismissed")?"rejected":"saved",0),view.snapshot.revision);
            db.setTransactionSuccessful();return true;
        } finally {db.endTransaction();}
    }
    void save(String id,DetailValue detail,long expected) {
        DetailValue.validate(detail.kind,detail.label,detail.value,detail.qualifier);
        SQLiteDatabase db=database.get();db.beginTransaction();
        try {
            ContentValues bump=new ContentValues();bump.put("metadata_revision",expected+1);
            if(db.update("documents",bump,"id=? AND metadata_revision=?",new String[]{id,Long.toString(expected)})!=1)throw new IllegalArgumentException("Details changed. Reopen the editor before saving.");
            ContentValues row=new ContentValues();row.put("document_id",id);row.put("field_id",detail.field);row.put("kind",detail.kind);row.put("label",LibraryRepository.cleanName(detail.label));row.put("value",detail.value);row.put("qualifier",detail.qualifier);row.put("origin",detail.origin);row.put("state",detail.state);row.put("evidence",detail.evidence);row.put("revision",expected+1);
            db.insertWithOnConflict("details",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            db.delete("document_tags","document_id=? AND field_id=?",new String[]{id,detail.field});
            if(detail.kind.equals("organization")&&detail.state.equals("saved")) {
                String tagId=null;try(Cursor tag=db.rawQuery("SELECT id FROM tags WHERE name=? COLLATE NOCASE",new String[]{detail.value})){if(tag.moveToFirst())tagId=tag.getString(0);}
                if(tagId==null){tagId=UUID.randomUUID().toString();ContentValues tag=new ContentValues();tag.put("id",tagId);tag.put("name",detail.value);db.insertOrThrow("tags",null,tag);}
                ContentValues link=new ContentValues();link.put("document_id",id);link.put("tag_id",tagId);link.put("field_id",detail.field);db.insertOrThrow("document_tags",null,link);
            }
            clearDraft(id,detail.field);reindex(id);db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    void saveNotes(String id,String notes,long expected) {
        if(notes.length()>NOTES_LIMIT||notes.indexOf('\u0000')>=0)throw new IllegalArgumentException("Use notes with at most 20,000 characters.");
        SQLiteDatabase db=database.get();db.beginTransaction();try{
            ContentValues row=new ContentValues();row.put("notes",notes);row.put("notes_revision",expected+1);
            if(db.update("documents",row,"id=? AND notes_revision=?",new String[]{id,Long.toString(expected)})!=1)throw new IllegalArgumentException("Notes changed. Reopen the editor before saving.");
            clearDraft(id,"notes");reindex(id);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    String draft(String id,String field,long revision,String fallback) {
        try(Cursor row=database.get().rawQuery("SELECT value FROM editor_drafts WHERE document_id=? AND field_id=? AND base_revision=?",new String[]{id,field,Long.toString(revision)})){return row.moveToFirst()?row.getString(0):fallback;}
    }
    void draft(String id,String field,String value,long revision) {
        if(value.length()>NOTES_LIMIT+4096)return;
        ContentValues row=new ContentValues();row.put("document_id",id);row.put("field_id",field);row.put("value",value);row.put("base_revision",revision);
        database.get().insertWithOnConflict("editor_drafts",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }
    void clearDraft(String id,String field){if(database.get().delete("editor_drafts","document_id=? AND field_id=?",new String[]{id,field})>0)retry(id);}
    void retry(String id){database.get().execSQL("UPDATE metadata_work SET status='pending',claim='',attempts=0 WHERE document_id=?",new Object[]{id});}
    private void reindex(String id){try(Cursor row=database.get().rawQuery("SELECT title FROM documents WHERE id=?",new String[]{id})){if(row.moveToFirst())LibraryRepository.index(database.get(),id,row.getString(0));}}
    private static String str(Cursor c,String name){return c.getString(c.getColumnIndexOrThrow(name));}
}
