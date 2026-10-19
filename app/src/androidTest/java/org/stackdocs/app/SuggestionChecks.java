package org.stackdocs.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Synthetic state-machine and real-engine checks. Never uses the production repository. */
final class SuggestionChecks {
    private static final String TEXT="Cedar Clinic\nMedical invoice\nPatient name: Synthetic Example\nDate of service 2026-01-01\nAmount due 45.00\nDue date 2026-02-01";
    static void run(Context context,Consumer<String> mark) throws Exception {
        LibraryRepository library=new LibraryRepository(context); library.initialize();
        File source=OcrQueueChecks.fixture(context,"suggestions.pdf");
        Document document=library.importDocument(Uri.fromFile(source));
        require(library.suggestions.claim()==null && library.suggestions.view(document.id).status.equals("waiting"),"Wait for OCR");
        finishOcr(library,TEXT);
        String oldTitle=document.title;
        SuggestionJob job=library.suggestions.claim(); require(job!=null,"Durable proposal claim");
        require(library.suggestions.complete(job,SuggestionRules.analyze(job),false),"Proposal commit");
        SuggestionStore.View view=library.suggestions.view(document.id);
        require(view.result.title.equals("Cedar Clinic · Bill") && view.result.stacks.keySet().containsAll(Arrays.asList("bills","medical")),"Multi-stack proposals");
        require(library.document(document.id).title.equals(oldTitle) && library.document(document.id).stackIds.isEmpty(),"Advisory only");
        require(library.suggestions.decide(view,"title","accepted",null,null),"Accept title");
        require(!library.suggestions.decide(view,"filing","accepted",null,null),"Old view rejected after title choice");
        drain(library);
        view=library.suggestions.view(document.id);
        require(view.titleState.equals("accepted") && view.filingState.equals("offered"),"Independent decisions");
        library.setMemberships(document.id,Arrays.asList("starred")); drain(library);
        // Manual filing suppresses the unchanged filing proposal; changed evidence can offer advice again.
        require(library.suggestions.view(document.id).filingState.equals("edited"),"Manual choice recorded");
        library.retryOcr(document.id,library.pages(document.id).get(0).id); finishOcr(library,TEXT+"\nAdditional care"); drain(library);
        view=library.suggestions.view(document.id);
        require(library.suggestions.decide(view,"filing","accepted",null,null),"Accept new evidence"); drain(library);
        require(library.document(document.id).stackIds.containsAll(Arrays.asList("starred","medical","bills")),"Add preserves manual Starred");
        require(library.documents("Cedar",null).size()==1 && library.documents("care","medical").size()==1,"Title/body/filter search after accept");
        library=new LibraryRepository(context); library.initialize(); drain(library);
        require(library.suggestions.view(document.id).filingState.equals("accepted") && library.document(document.id).stackIds.size()==3,"Restart choices and no duplicate memberships");
        mark.accept("Real repository proposals stay advisory; independent accept, manual Starred preservation, FTS/filter/reopen and idempotence");

        // Rename/refile/new result/retry/edit/remove/delete/stack deletion invalidate both success and failure.
        for(String race:Arrays.asList("rename","refile","edit","remove","retry","newtext","stack","delete","newer")) {
            for(boolean applying:new boolean[]{false,true}) {
                Context child=new ScannerChecks.IsolatedContext(context);
                LibraryRepository raceLibrary=new LibraryRepository(child); raceLibrary.initialize();
                Document doc=raceLibrary.importDocument(Uri.fromFile(source)); finishOcr(raceLibrary,TEXT);
                SuggestionJob held=raceLibrary.suggestions.claim();
                SuggestionRules.Result result=SuggestionRules.analyze(held);
                SuggestionStore.View stale=null;
                if(applying) {
                    require(raceLibrary.suggestions.complete(held,result,false),"Initial ready");
                    stale=raceLibrary.suggestions.view(doc.id);
                }
                mutate(race,raceLibrary,doc);
                if(applying) require(!raceLibrary.suggestions.decide(stale,"title","accepted",null,null)
                        && !raceLibrary.suggestions.decide(stale,"filing","accepted",null,null),race+" stale apply");
                else require(!raceLibrary.suggestions.complete(held,result,false) && !raceLibrary.suggestions.complete(held,null,true),race+" late success/failure");
                if(race.equals("delete")) require(raceLibrary.documents("",null).isEmpty(),"No resurrection");
                if(race.equals("stack")) { drain(raceLibrary); require(!raceLibrary.suggestions.view(doc.id).result.stacks.containsKey("medical"),"No deleted stack"); }
                if(race.equals("refile")) require(raceLibrary.document(doc.id).stackIds.isEmpty(),"Empty remains empty");
            }
        }
        mark.accept("Held-analysis and stale-Apply races: rename, empty refile, edit, removal, retry, changed text, stack/document deletion, newer generation; late success/failure rejected");

        Context choices=new ScannerChecks.IsolatedContext(context); library=new LibraryRepository(choices); library.initialize();
        document=library.importDocument(Uri.fromFile(source)); finishOcr(library,TEXT); drain(library); view=library.suggestions.view(document.id);
        require(library.suggestions.decide(view,"filing","dismissed",null,null),"Dismiss filing"); drain(library);
        view=library.suggestions.view(document.id); require(library.suggestions.decide(view,"title","edited","Chosen title",null),"Edit title"); drain(library);
        library.retryOcr(document.id,library.pages(document.id).get(0).id); finishOcr(library,TEXT); drain(library);
        view=library.suggestions.view(document.id);
        require(view.titleState.equals("edited") && view.filingState.equals("dismissed") && library.document(document.id).title.equals("Chosen title") && library.document(document.id).stackIds.isEmpty(),"Unchanged retry respects dismissal");
        library.suggestions.retry(document.id); drain(library); require(library.suggestions.view(document.id).filingState.equals("dismissed"),"Suggestion retry respects dismissal");
        // Explicit editor can deliberately remove everything.
        library.retryOcr(document.id,library.pages(document.id).get(0).id); finishOcr(library,TEXT+"\nNew evidence"); drain(library);
        view=library.suggestions.view(document.id);
        require(library.suggestions.decide(view,"filing","edited",null,Collections.emptyList()),"Edit to no stacks"); drain(library);
        library=new LibraryRepository(choices); library.initialize(); drain(library);
        require(library.document(document.id).stackIds.isEmpty() && library.suggestions.view(document.id).filingState.equals("edited"),"Empty editor choice survives restart");
        mark.accept("Edit/dismiss, explicit empty filing, OCR/suggestion retries and restart retain field choices for unchanged evidence");

        library.suggestions.retry(document.id); SuggestionJob interrupted=library.suggestions.claim();
        library=new LibraryRepository(choices); library.initialize();
        require(!library.suggestions.complete(interrupted,SuggestionRules.analyze(interrupted),false),"Interrupted token rejected");
        SuggestionJob recovered=library.suggestions.claim(); require(recovered!=null && library.suggestions.complete(recovered,null,true),"Recovered failure");
        require(library.suggestions.view(document.id).status.equals("failed"),"Visible failure state");
        library.suggestions.retry(document.id); drain(library);
        require(library.suggestions.view(document.id).status.equals("ready"),"Manual retry");
        library.retryOcr(document.id,library.pages(document.id).get(0).id); finishOcr(library,"");
        library.retryOcr(document.id,library.pages(document.id).get(1).id); finishOcr(library,""); drain(library);
        require(library.suggestions.view(document.id).status.equals("empty") && library.document(document.id).title.equals("Chosen title"),"Empty keeps fallback/manual title");
        mark.accept("Interrupted proposal recovery, failed/manual retry and empty-text fallback");
        limitsAndRules(new ScannerChecks.IsolatedContext(context),mark);
        draftChoices(new ScannerChecks.IsolatedContext(context),mark);
        realEngine(new ScannerChecks.IsolatedContext(context),mark);
    }
    private static void mutate(String race,LibraryRepository library,Document doc) {
        if(race.equals("rename")) library.rename(doc.id,"Deliberate choice");
        if(race.equals("refile")) library.setMemberships(doc.id,Collections.emptyList());
        if(race.equals("edit")) { PageState page=library.pages(doc.id).get(0); library.editPage(doc.id,0,page.edited(90,page.crop,page.enhancement)); }
        if(race.equals("remove")) library.removePage(doc.id,0);
        if(race.equals("retry") || race.equals("newtext")) library.retryOcr(doc.id,library.pages(doc.id).get(0).id);
        if(race.equals("newtext")) finishOcr(library,"Fresh Bank\nAccount statement\nOpening balance 100\nTransactions");
        if(race.equals("stack")) library.deleteStack("medical");
        if(race.equals("delete")) library.deleteDocument(doc.id);
        if(race.equals("newer")) {library.suggestions.retry(doc.id); drain(library);}
    }
    private static void limitsAndRules(Context context,Consumer<String> mark) throws Exception {
        LibraryRepository library=new LibraryRepository(context); library.initialize();
        Document doc=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"bounded.pdf")));
        finishOcr(library,TEXT);
        library.retryOcr(doc.id,library.pages(doc.id).get(0).id);
        OcrJob failed=library.claimOcr(); library.completeOcr(failed,null,"synthetic_failure");
        SuggestionJob partial=library.suggestions.claim();
        require(partial.partial && !partial.active && SuggestionRules.analyze(partial).stacks.containsKey("medical"),"Failed retry uses explicitly partial still-current text");
        try(LibraryDatabase helper=new LibraryDatabase(context)) {
            SQLiteDatabase db=helper.getWritableDatabase();
            db.execSQL("UPDATE suggestions SET rule='obsolete-rule' WHERE document_id=?",new Object[]{doc.id});
            require(!library.suggestions.complete(partial,SuggestionRules.analyze(partial),false),"Obsolete rule commit rejected");
            library.suggestions.recover(); drain(library);
            SuggestionStore.View old=library.suggestions.view(doc.id);
            db.execSQL("UPDATE suggestions SET rule='obsolete-rule' WHERE document_id=?",new Object[]{doc.id});
            require(!library.suggestions.decide(old,"title","accepted",null,null),"Obsolete rule apply rejected");
            String huge=TEXT+new String(new char[40000]).replace('\0','x');
            db.execSQL("UPDATE pages SET ocr_text=?,text_revision=content_revision,text_config=ocr_config,ocr_state='succeeded' WHERE document_id=?",new Object[]{huge,doc.id});
            for(int i=2;i<10;i++) db.execSQL("INSERT INTO pages(id,document_id,position,source_page,ocr_text,ocr_state,text_revision,text_config,ocr_config) VALUES(?,?,?,0,?,'succeeded',0,?,?)",
                    new Object[]{java.util.UUID.randomUUID().toString(),doc.id,i,huge,OcrModel.CONFIG,OcrModel.CONFIG});
            library.suggestions.recover(); SuggestionJob bounded=library.suggestions.claim();
            int total=0; for(String page:bounded.pages) {require(page.length()<=SuggestionRules.PAGE_LIMIT,"Per-page bound"); total+=page.length();}
            require(total==SuggestionRules.TEXT_LIMIT && bounded.truncated && bounded.pages.size()==10,"Total bound, page slots and recorded truncation");
            SuggestionRules.Result result=SuggestionRules.analyze(bounded);
            require(result.truncated && !result.stacks.containsKey("starred"),"No inferred Starred");
        }
        SuggestionRules.Result negative=SuggestionRules.analyze(Arrays.asList("This is not an invoice\nAmount due is a bookkeeping phrase"),Arrays.asList("bills","starred"),false,false);
        require(negative.title.isEmpty() && negative.stacks.isEmpty(),"Negation abstains");
        require(SuggestionRules.clean(" A/ B\\C\nD ").equals("A B C D"),"Path/control cleanup");
        mark.accept("Rule-version commit/Apply rejection, failed-OCR partial state, 32K/page and 256K/document bounds with preserved page slots, negation and manual-only Starred");
    }
    private static void draftChoices(Context context,Consumer<String> mark) throws Exception {
        LibraryRepository library=new LibraryRepository(context); library.initialize(); ScanDraftStore store=new ScanDraftStore(context);
        Bitmap image=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888); image.eraseColor(Color.WHITE);
        byte[] jpeg; try { jpeg=ScannerChecks.jpeg(image); } finally {image.recycle();}
        ScanDraftStore.Draft draft=store.append(jpeg);
        Document untouched=library.saveScan(store,draft,"Scan default",false,Collections.emptyList(),false);
        try(LibraryDatabase helper=new LibraryDatabase(context); Cursor row=helper.getReadableDatabase().rawQuery("SELECT title_user_edited,title_origin,filing_origin FROM documents WHERE id=?",new String[]{untouched.id})) {
            require(row.moveToFirst() && row.getInt(0)==0 && row.getString(1).equals("default") && row.getString(2).equals("default"),"Untouched defaults distinct");
        }
        store.discard(); draft=store.append(jpeg); String id=draft.id;
        draft=store.choices(id,"Deliberate draft",true,Collections.emptyList(),true);
        draft=store.append(jpeg); draft=store.edit(0,draft.pages.get(0).flattened(true)); draft=store.remove(1);
        ScanDraftStore.Draft restored=new ScanDraftStore(context).load();
        require(restored.name.equals("Deliberate draft") && restored.titleChosen && restored.filingChosen && restored.stackIds.isEmpty(),"Durable name and empty choice");
        Document chosen=library.saveScan(store,restored,restored.name,restored.titleChosen,restored.stackIds,restored.filingChosen);
        library.rename(chosen.id,"After save"); library.setMemberships(chosen.id,Arrays.asList("starred"));
        require(library.saveScan(store,restored,"late",false,Collections.emptyList(),false).title.equals("After save") && library.document(chosen.id).stackIds.contains("starred"),"Idempotent scan retry preserves newer choices");
        store.discard();
        ScanDraftStore.Draft legacy=store.append(jpeg);
        File manifest=new File(store.directory,"draft.json");
        org.json.JSONObject json=new org.json.JSONObject(new String(ScannerChecks.bytes(manifest),java.nio.charset.StandardCharsets.UTF_8));
        json.put("name","Recovered old draft"); json.remove("choice_tracking"); json.remove("title_chosen"); json.remove("filing_chosen");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(manifest)){out.write(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        legacy=store.load();
        require(!legacy.choiceTracking && legacy.name.equals("Recovered old draft"),"Legacy draft name and unknown provenance");
        Document old=library.saveScan(store,legacy,legacy.name,false,legacy.stackIds,false);
        try(LibraryDatabase helper=new LibraryDatabase(context); Cursor row=helper.getReadableDatabase().rawQuery("SELECT title_origin,filing_origin FROM documents WHERE id=?",new String[]{old.id})) {
            require(row.moveToFirst() && row.getString(0).equals("legacy") && row.getString(1).equals("legacy"),"Legacy absence grants no ownership assumption");
        }
        store.discard(); store.choices(id,"late keystroke",true,Collections.emptyList(),true);
        require(!new File(store.directory,"draft.json").exists(),"Delayed edit cannot resurrect draft");
        mark.accept("New untouched defaults, durable draft title/explicit empty choice across append/edit/remove/reopen, idempotent save and delayed-keystroke discard safety");
    }
    private static void realEngine(Context context,Consumer<String> mark) throws Exception {
        LibraryRepository library=new LibraryRepository(context); library.initialize();
        File source=OcrQueueChecks.fixture(context,"real.pdf"); Document document=library.importDocument(Uri.fromFile(source));
        try(OcrEngine engine=new OcrEngine(context)) {
            OcrJob job; while((job=library.claimOcr())!=null) {
                Bitmap image=PageRenderer.render(job.source,job.mime,job.page,2400);
                try {library.completeOcr(job,engine.recognize(image),null);} finally {image.recycle();}
            }
        }
        drain(library); SuggestionStore.View view=library.suggestions.view(document.id);
        require(view.result.stacks.containsKey("bills"),"Real OCR to rules");
        require(library.suggestions.decide(view,"title","edited","Real pipeline specimen",null),"Real edit"); drain(library);
        require(library.suggestions.decide(library.suggestions.view(document.id),"filing","accepted",null,null),"Real accept"); drain(library);
        library=new LibraryRepository(context); library.initialize(); drain(library);
        require(library.documents("specimen quartz","bills").size()==1 && library.documents("quartz",null).get(0).matchPageId.equals(library.pages(document.id).get(1).id),"Real OCR accept/search/reopen/page context");
        mark.accept("Real multipage Tesseract → proposals → edited name/accepted filing → title/body/stack search and stable later-page context after reopen");
    }
    static void finishOcr(LibraryRepository library,String text) { OcrJob job; while((job=library.claimOcr())!=null) require(library.completeOcr(job,text,null),"Fixture OCR commit"); }
    static void drain(LibraryRepository library) { SuggestionJob job; int count=0; while((job=library.suggestions.claim())!=null) { require(++count<100,"No duplicate loop"); require(library.suggestions.complete(job,SuggestionRules.analyze(job),false),"Current proposal"); } }
    static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
