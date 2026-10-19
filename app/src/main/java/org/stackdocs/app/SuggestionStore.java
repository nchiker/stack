package org.stackdocs.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Short serialized repository operations; analysis uses a separate executor. */
final class SuggestionStore {
    private final Supplier<SQLiteDatabase> database;
    SuggestionStore(Supplier<SQLiteDatabase> database) { this.database=database; }
    static final class View {
        final SuggestionJob job;
        final String status, titleState, filingState;
        final SuggestionRules.Result result;
        View(SuggestionJob job,String status,String titleState,String filingState,SuggestionRules.Result result) {
            this.job=job; this.status=status; this.titleState=titleState; this.filingState=filingState; this.result=result;
        }
    }
    void recover() {
        SQLiteDatabase db=database.get();
        db.execSQL("UPDATE suggestions SET status='pending',claim='',generation=generation+1 WHERE status='running' OR rule<>?",new Object[]{SuggestionRules.VERSION});
    }
    private static final class Digest {
        final MessageDigest digest;
        Digest() { try { digest=MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException error) { throw new AssertionError(error); } }
        void add(String value) { digest.update((value.length()+":").getBytes(StandardCharsets.UTF_8)); digest.update(value.getBytes(StandardCharsets.UTF_8)); }
        String finish() { StringBuilder out=new StringBuilder(); for(byte b:digest.digest()) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return out.toString(); }
    }
    SuggestionJob snapshot(String id,String claim,long generation) {
        SQLiteDatabase db=database.get();
        long titleRevision,filingRevision;
        try(Cursor row=db.rawQuery("SELECT title_decision,filing_decision FROM documents WHERE id=?",new String[]{id})) {
            if(!row.moveToFirst()) return null;
            titleRevision=row.getLong(0); filingRevision=row.getLong(1);
        }
        Digest evidence=new Digest(),input=new Digest();
        evidence.add(id); input.add(SuggestionRules.VERSION);
        List<String> pages=new ArrayList<>(),stacks=new ArrayList<>();
        boolean active=false,partial=false,truncated=false;
        int remaining=SuggestionRules.TEXT_LIMIT;
        try(Cursor rows=db.rawQuery("SELECT id,position,content_revision,text_revision,ocr_config,text_config,ocr_text,ocr_state,ocr_claim,ocr_attempts "
                + "FROM pages WHERE document_id=? ORDER BY position",new String[]{id})) {
            while(rows.moveToNext()) {
                for(int col=0;col<7;col++) evidence.add(rows.getString(col));
                for(int col=7;col<10;col++) input.add(rows.getString(col));
                String state=rows.getString(7);
                active |= state.equals("pending") || state.equals("running"); partial |= state.equals("failed");
                boolean current=rows.getLong(2)==rows.getLong(3) && rows.getString(4).equals(rows.getString(5));
                String text=current ? rows.getString(6) : "";
                int keep=Math.min(text.length(),Math.min(remaining,SuggestionRules.PAGE_LIMIT));
                truncated |= keep<text.length(); remaining-=keep;
                // Preserve empty page slots: later pages must not become first-page headings.
                pages.add(text.substring(0,keep));
            }
        }
        try(Cursor rows=db.rawQuery("SELECT id FROM stacks ORDER BY id",null)) { while(rows.moveToNext()) { stacks.add(rows.getString(0)); input.add(rows.getString(0)); } }
        String signature=evidence.finish(); input.add(signature); input.add(Long.toString(titleRevision)); input.add(Long.toString(filingRevision));
        return new SuggestionJob(id,claim,generation,input.finish(),signature,titleRevision,filingRevision,pages,stacks,active,partial,truncated);
    }
    String currentEvidence(String id) {
        SuggestionJob job=snapshot(id,"",0);
        if(job==null) throw new IllegalArgumentException("Document no longer exists.");
        return job.evidence;
    }
    SuggestionJob claim() {
        SQLiteDatabase db=database.get(); db.beginTransaction();
        try {
            List<String> ids=new ArrayList<>();
            try(Cursor rows=db.rawQuery("SELECT document_id FROM suggestions WHERE status='pending' ORDER BY document_id",null)) {
                while(rows.moveToNext()) ids.add(rows.getString(0));
            }
            for(String id:ids) {
                long generation;
                try(Cursor row=db.rawQuery("SELECT generation FROM suggestions WHERE document_id=?",new String[]{id})) { if(!row.moveToFirst()) continue; generation=row.getLong(0); }
                String claim=UUID.randomUUID().toString(); SuggestionJob job=snapshot(id,claim,generation);
                if(job==null) continue;
                ContentValues values=new ContentValues(); values.put("rule",SuggestionRules.VERSION);
                values.put("status",job.active ? "waiting" : "running"); values.put("claim",job.active ? "" : claim);
                values.put("input",job.input); values.put("evidence",job.evidence);
                values.put("title_revision",job.titleRevision); values.put("filing_revision",job.filingRevision);
                db.update("suggestions",values,"document_id=?",new String[]{id});
                if(!job.active) { db.setTransactionSuccessful(); return job; }
            }
            db.setTransactionSuccessful(); return null;
        } finally { db.endTransaction(); }
    }
    boolean complete(SuggestionJob job,SuggestionRules.Result result,boolean failed) {
        SQLiteDatabase db=database.get(); db.beginTransaction();
        try {
            if(!matches(job,"running")) { db.setTransactionSuccessful(); return false; }
            ContentValues values=new ContentValues(); values.put("status",failed ? "failed" : result.title.isEmpty() && result.stacks.isEmpty() ? "empty" : "ready");
            values.put("claim",""); values.put("payload",failed ? "{}" : result.encode());
            values.put("title_state",choice(job,"title")); values.put("filing_state",choice(job,"filing"));
            db.update("suggestions",values,"document_id=?",new String[]{job.documentId});
            db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    private String choice(SuggestionJob job,String field) {
        try(Cursor row=database.get().rawQuery("SELECT choice FROM suggestion_choices WHERE document_id=? AND evidence=? AND field=?",new String[]{job.documentId,job.evidence,field})) {
            return row.moveToFirst() ? row.getString(0) : "offered";
        }
    }
    private boolean matches(SuggestionJob job,String status) {
        SuggestionJob current=snapshot(job.documentId,job.claim,job.generation);
        if(current==null || !current.input.equals(job.input)) return false;
        try(Cursor row=database.get().rawQuery("SELECT generation,claim,input,rule,status FROM suggestions WHERE document_id=?",new String[]{job.documentId})) {
            return row.moveToFirst() && row.getLong(0)==job.generation && row.getString(2).equals(job.input)
                    && row.getString(3).equals(SuggestionRules.VERSION) && row.getString(4).equals(status)
                    && (!status.equals("running") || row.getString(1).equals(job.claim));
        }
    }
    View view(String id) {
        try(Cursor row=database.get().rawQuery("SELECT * FROM suggestions WHERE document_id=?",new String[]{id})) {
            if(!row.moveToFirst()) return null;
            long generation=row.getLong(row.getColumnIndexOrThrow("generation"));
            SuggestionJob current=snapshot(id,"",generation);
            String status=str(row,"status");
            if((status.equals("ready") || status.equals("empty") || status.equals("failed"))
                    && (!current.input.equals(str(row,"input")) || !SuggestionRules.VERSION.equals(str(row,"rule")))) status="pending";
            return new View(current,status,str(row,"title_state"),str(row,"filing_state"),SuggestionRules.Result.decode(str(row,"payload")));
        }
    }
    void retry(String id) {
        database.get().execSQL("UPDATE suggestions SET status='pending',claim='',generation=generation+1 WHERE document_id=?",new Object[]{id});
    }
    void manualChoice(String id,String field,String value) {
        SuggestionJob current=snapshot(id,"",0);
        if(current!=null) record(current,field,"edited",value);
    }
    private void record(SuggestionJob job,String field,String choice,String value) {
        ContentValues row=new ContentValues(); row.put("document_id",job.documentId); row.put("evidence",job.evidence);
        row.put("field",field); row.put("choice",choice); row.put("value",value);
        database.get().insertWithOnConflict("suggestion_choices",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }
    /** selected stacks replace the full set only in the explicitly labelled editor; null adds the proposal. */
    boolean decide(View view,String field,String action,String title,List<String> selected) {
        SQLiteDatabase db=database.get(); db.beginTransaction();
        try {
            SuggestionJob job=view.job;
            if(!matches(job,"ready") || !choice(job,field).equals("offered")) { db.setTransactionSuccessful(); return false; }
            if(!field.equals("title") && !field.equals("filing")) throw new IllegalArgumentException("Unknown choice");
            if(!action.equals("accepted") && !action.equals("edited") && !action.equals("dismissed")) throw new IllegalArgumentException("Unknown choice");
            // Read authoritative persisted payload, never trust a caller's altered result.
            SuggestionRules.Result result;
            try(Cursor row=db.rawQuery("SELECT payload FROM suggestions WHERE document_id=?",new String[]{job.documentId})) { row.moveToFirst(); result=SuggestionRules.Result.decode(row.getString(0)); }
            String value="";
            if(!action.equals("dismissed")) {
                if(field.equals("title")) {
                    value=LibraryRepository.cleanName(action.equals("edited") ? title : result.title);
                    ContentValues update=new ContentValues(); update.put("title",value); update.put("title_user_edited",1); update.put("title_origin",action);
                    db.update("documents",update,"id=?",new String[]{job.documentId}); LibraryRepository.index(db,job.documentId,value);
                } else {
                    LinkedHashSet<String> stacks=new LinkedHashSet<>();
                    if(action.equals("edited")) stacks.addAll(selected);
                    else {
                        try(Cursor rows=db.rawQuery("SELECT stack_id FROM document_stacks WHERE document_id=?",new String[]{job.documentId})) { while(rows.moveToNext()) stacks.add(rows.getString(0)); }
                        stacks.addAll(result.stacks.keySet());
                    }
                    for(String id:stacks) if(!job.stackIds.contains(id)) { db.setTransactionSuccessful(); return false; }
                    db.delete("document_stacks","document_id=?",new String[]{job.documentId});
                    for(String id:stacks) { ContentValues row=new ContentValues(); row.put("document_id",job.documentId); row.put("stack_id",id); db.insertOrThrow("document_stacks",null,row); }
                    value=android.text.TextUtils.join(",",stacks);
                    db.execSQL("UPDATE documents SET filing_origin=? WHERE id=?",new Object[]{action,job.documentId});
                }
            }
            try {
                org.json.JSONObject provenance=new org.json.JSONObject();
                provenance.put("rule",SuggestionRules.VERSION); provenance.put("input",job.input);
                provenance.put("proposed",new org.json.JSONObject(result.encode())); provenance.put("chosen",value);
                record(job,field,action,provenance.toString());
            } catch(org.json.JSONException error) { throw new IllegalStateException(error); }
            String column=field.equals("title") ? "title_decision" : "filing_decision";
            db.execSQL("UPDATE documents SET "+column+"="+column+"+1 WHERE id=?",new Object[]{job.documentId});
            db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    private static String str(Cursor row,String name) { return row.getString(row.getColumnIndexOrThrow(name)); }
}
