package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.database.DatabaseUtils;
import org.json.JSONObject;
import java.util.UUID;
import java.util.function.Consumer;

/** A populated, authored library reproduces the real CursorWindow boundary. */
final class SyncLargeStateChecks {
    static void run(Instrumentation test,Consumer<String> mark)throws Exception {
        if(!ScannerChecks.disposableLibraryTarget(test.getTargetContext()))throw new SecurityException("Explicit disposable target required");
        Context context=new NearbyPairingChecks.FixtureContext(test.getTargetContext(),"nearby-large-state-"+UUID.randomUUID());
        LibraryRepository repository=new LibraryRepository(context);repository.initialize();
        try {
            java.io.File pdf=OcrQueueChecks.fixture(context,"large-state.pdf");
            String notes="票据🦀\n".repeat(3500); // Below the production 20,000-character notes limit.
            for(int i=0;i<64;i++){
                Document document=repository.importDocument(android.net.Uri.fromFile(pdf));
                ContentValues row=new ContentValues();row.put("notes",notes);repository.database().update("documents",row,"id=?",new String[]{document.id});
            }
            SyncStore store=new SyncStore(context,repository);
            JSONObject status=store.start(java.util.Collections.singletonList(UUID.randomUUID().toString()));
            for(String column:new String[]{"state","projection","baseline"}){
                long bytes=DatabaseUtils.longForQuery(repository.database(),"SELECT length(CAST("+column+" AS BLOB)) FROM sync_state WHERE singleton=1",null);
                NearbyPairingChecks.require(bytes>2*1024*1024,"Large "+column+" crosses the real cursor boundary: "+bytes);
            }
            NearbyPairingChecks.require(status.optBoolean("adopted")&&repository.documents("",null).size()==64,"Populated setup remains readable");
            SyncProtocol.State state=store.load();store.savePending(state);store.install(state,null);
            String before=SyncProtocol.canonical(store.load().saved());
            NearbyPairingChecks.require(notes.equals(DatabaseUtils.stringForQuery(repository.database(),"SELECT notes FROM documents LIMIT 1",null)),"Large aggregate retains exact Unicode notes");
            String id=DatabaseUtils.stringForQuery(repository.database(),"SELECT id FROM documents LIMIT 1",null);
            ContentValues changed=new ContentValues();changed.put("title","Saved after large-state setup 🦀");repository.database().update("documents",changed,"id=?",new String[]{id});
            store.capture();NearbyPairingChecks.require(!before.equals(SyncProtocol.canonical(store.load().saved())),"Large projection captures a later saved change");
            SyncProtocol.State captured=store.load();store.install(captured,null);
            NearbyPairingChecks.require(repository.documents("",null).size()==64&&store.status().optInt("pending_gaps")==0,"Large baseline is usable during subsequent apply");
            mark.accept("Large sync JSON: state/projection/baseline each exceed2MiB; setup/status/reload/pending/capture/apply preserve64 authored documents and exact Unicode notes");
            int operations=store.load().history.size();
            for(int i=0;i<4;i++)repository.database().execSQL("UPDATE suggestions SET generation=generation+1,status='pending'");
            long started=android.os.SystemClock.elapsedRealtime();store.capture();long elapsed=android.os.SystemClock.elapsedRealtime()-started;
            NearbyPairingChecks.require(store.load().history.size()==operations+256,"Every queue invalidation retains its separate causal operation");
            NearbyPairingChecks.require(elapsed<30000,"Populated queue replay completes within30s, actual="+elapsed+"ms");
            long cursor=DatabaseUtils.longForQuery(repository.database(),"SELECT journal_cursor FROM sync_state WHERE singleton=1",null);
            NearbyPairingChecks.require(cursor==DatabaseUtils.longForQuery(repository.database(),"SELECT max(sequence) FROM sync_change",null),"Queue replay durably advances through every recorded row");
            store.capture();NearbyPairingChecks.require(store.load().history.size()==operations+256&&notes.equals(DatabaseUtils.stringForQuery(repository.database(),"SELECT notes FROM documents LIMIT 1",null)),"Queue replay is idempotent and preserves exact document notes");
            mark.accept("Populated queue replay retains256 causal updates without rebuilding the full library per row; elapsed="+elapsed+"ms");
        } finally {repository.closeForReset();}
    }
}
