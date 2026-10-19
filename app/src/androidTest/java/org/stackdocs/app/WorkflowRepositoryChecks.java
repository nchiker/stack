package org.stackdocs.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

/** Failure injection is confined to new synthetic repositories, never the production database. */
final class WorkflowRepositoryChecks {
    static void run(Context context,Consumer<String> mark) throws Exception {
        LibraryRepository library=new LibraryRepository(context); library.initialize();
        File pdf=OcrQueueChecks.fixture(context,"workflow.pdf");
        Document existing=library.importDocument(Uri.fromFile(pdf));
        library.rename(existing.id,"Existing synthetic record"); library.setMemberships(existing.id,Arrays.asList("bills","starred"));
        byte[] original=ScannerChecks.bytes(library.source(existing));
        ScanDraftStore store=new ScanDraftStore(context);
        Bitmap blank=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888); blank.eraseColor(Color.WHITE);
        byte[] jpeg; try {jpeg=ScannerChecks.jpeg(blank);} finally {blank.recycle();}
        ScanDraftStore.Draft draft=store.append(jpeg);
        draft=store.choices(draft.id,"Recoverable synthetic draft",true,Collections.emptyList(),true);
        byte[] manifest=ScannerChecks.bytes(new File(store.directory,"draft.json"));
        String fingerprint=PreservationChecks.fingerprint(context);
        try(LibraryDatabase helper=new LibraryDatabase(context)) {
            SQLiteDatabase db=helper.getWritableDatabase();
            for(String table:Arrays.asList("documents","pages","document_stacks")) {
                db.execSQL("CREATE TRIGGER synthetic_commit_abort BEFORE INSERT ON "+table+" BEGIN SELECT RAISE(ABORT,'synthetic_commit_failure'); END");
                try {
                    boolean failed=false;
                    try {library.saveScan(store,draft,draft.name,true,Arrays.asList("bills"),true);} catch(RuntimeException expected) {failed=true;}
                    require(failed,"Injected "+table+" scan failure reached");
                    require(library.documents("",null).size()==1,"Failed scan rolls back all metadata");
                    require(Arrays.equals(manifest,ScannerChecks.bytes(new File(store.directory,"draft.json"))) && Arrays.equals(jpeg,ScannerChecks.bytes(store.file(draft.pages.get(0)))),"Failed save retains draft manifest/original");
                    if(!table.equals("document_stacks")) {
                        failed=false; try {library.importDocument(Uri.fromFile(pdf));} catch(RuntimeException expected) {failed=true;}
                        require(failed,"Injected "+table+" import failure reached");
                    }
                    require(PreservationChecks.fingerprint(context).equals(fingerprint),"Existing document, filing, scanner and queue metadata preserved");
                    require(childCount(new File(context.getFilesDir(),"incoming"))==0 && childCount(new File(context.getFilesDir(),"documents"))==1,"No staged/orphan originals from failed commit");
                    require(Arrays.equals(original,ScannerChecks.bytes(library.source(existing))),"Existing source preserved");
                } finally {db.execSQL("DROP TRIGGER synthetic_commit_abort");}
            }
            long revision;
            try(Cursor row=db.rawQuery("SELECT filing_decision FROM documents WHERE id=?",new String[]{existing.id})) {row.moveToFirst();revision=row.getLong(0);}
            boolean rejected=false;
            try {library.setMemberships(existing.id,Arrays.asList("bills","deleted-stack"));} catch(IllegalArgumentException expected) {rejected=expected.getMessage().contains("deleted");}
            require(rejected && library.document(existing.id).stackIds.containsAll(Arrays.asList("bills","starred")),"Deleted stack gives actionable failure without losing memberships");
            try(Cursor row=db.rawQuery("SELECT filing_decision FROM documents WHERE id=?",new String[]{existing.id})) {row.moveToFirst();require(row.getLong(0)==revision,"Failed filing records no new decision");}
        }
        mark.accept("Commit failures at document/page/membership insertion roll back rows and copied files; existing metadata/originals and draft bytes preserved; deleted-stack filing is actionable and atomic");

        Document saved=library.saveScan(store,draft,draft.name,true,Arrays.asList("bills"),true);
        require(library.saveScan(store,draft,"Late retry",false,Collections.emptyList(),false).id.equals(saved.id) && library.documents("",null).size()==2,"Successful retry commits scan exactly once");
        File staging=new File(new File(context.getFilesDir(),"incoming"),UUID.randomUUID().toString()); require(staging.mkdirs(),"Isolated abandoned staging");
        try(FileOutputStream out=new FileOutputStream(new File(staging,"source"))){out.write(1);}
        File orphan=new File(new File(context.getFilesDir(),"documents"),UUID.randomUUID().toString());require(orphan.mkdirs(),"Isolated abandoned destination");
        library=new LibraryRepository(context);library.initialize();
        require(!staging.exists() && !orphan.exists() && library.documents("",null).size()==2,"Restart reclaims abandoned copies only");
        require(Arrays.equals(manifest,ScannerChecks.bytes(new File(store.directory,"draft.json"))) && Arrays.equals(original,ScannerChecks.bytes(library.source(existing))),"Recovery retains draft and committed original");
        require(library.claimOcr()!=null,"Successful saved data remains processable");
        mark.accept("Failed-save retry is idempotent; restart reclaims only uncommitted staging/destinations and preserves draft/originals");

        File incoming=new File(context.getFilesDir(),"incoming");require(incoming.delete(),"Empty isolated incoming directory");
        try(FileOutputStream out=new FileOutputStream(incoming)){out.write(1);}
        LibraryRepository unavailable=new LibraryRepository(context);unavailable.initialize();
        boolean failed=false;try {unavailable.importDocument(Uri.fromFile(pdf));} catch(IllegalStateException expected){failed=true;}
        require(failed && Arrays.equals(manifest,ScannerChecks.bytes(new File(store.directory,"draft.json"))),"Unavailable storage refuses import without touching recovery draft");
        require(incoming.delete(),"Repair isolated storage");library=new LibraryRepository(context);library.initialize();
        require(library.documents("",null).size()==2 && Arrays.equals(original,ScannerChecks.bytes(library.source(existing))),"Reopen after storage repair retains committed data");
        mark.accept("Unavailable isolated staging storage refuses ingestion; repair/new repository reopens existing documents and recovery draft");
    }
    private static int childCount(File directory) {String[] children=directory.list();return children==null ? 0 : children.length;}
    static void require(boolean valid,String message){if(!valid)throw new AssertionError(message);}
}
