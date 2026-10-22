package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Every destructive call is confined to a fresh authored fixture context. */
final class LibraryResetChecks {
    private static final class Death extends Error {}
    static void run(Instrumentation test,Consumer<String> mark)throws Exception {
        if(!ScannerChecks.disposableLibraryTarget(test.getTargetContext()))throw new SecurityException("Explicit disposable target required");
        for(String boundary:new String[]{"none","moved-0","preparing","initialized","committed","rollback-after-database"}){
            Context context=new NearbyPairingChecks.FixtureContext(test.getTargetContext(),"nearby-reset-"+UUID.randomUUID());LibraryRepository old=fixture(context);String replica=new SyncStore(context,old).replica();
            File keep=new File(context.getFilesDir(),"keep.stackbackup");NearbyPairingChecks.write(keep,"independent backup sentinel");File unknown=new File(context.getFilesDir(),"unrelated");NearbyPairingChecks.write(unknown,"keep");
            context.getSharedPreferences("local_policy",0).edit().putBoolean("photos",true).commit();LibraryReset reset=new LibraryReset(context);reset.schedule(LibraryReset.ERASE);NearbyPairingChecks.require(old.documents("",null).size()==1,"Scheduling does not erase live data");old.closeForReset();
            // A path may appear between scheduling and cold startup.
            File late=new File(context.getFilesDir(),"sync_blobs/late");late.getParentFile().mkdirs();NearbyPairingChecks.write(late,"late original");
            LibraryRepository replacement=new LibraryRepository(context);boolean[] injected={false};
            reset.probe=point->{if(boundary.equals("rollback-after-database")){if(point.equals("initialized"))throw new IOException("Authored initialization failure");if(point.equals("restored-0")){injected[0]=true;throw new Death();}}else if(boundary.equals(point)){injected[0]=true;throw new Death();}};
            try{reset.initialize(replacement);}catch(Death expected){}finally{replacement.closeForReset();}
            if(!boundary.equals("none"))NearbyPairingChecks.require(injected[0],"Requested crash boundary was reached: "+boundary);
            LibraryRepository recovered=new LibraryRepository(context);new LibraryReset(context).initialize(recovered);boolean rollback=boundary.equals("rollback-after-database");
            NearbyPairingChecks.require(recovered.documents("",null).size()==(rollback?1:0),"Cold recovery document count: "+boundary);
            String after=new SyncStore(context,recovered).replica();NearbyPairingChecks.require(rollback?replica.equals(after):!replica.equals(after),"Replica recovery: "+boundary);
            NearbyPairingChecks.require(keep.isFile()&&unknown.isFile(),"Independent backup and unknown paths retained");NearbyPairingChecks.require(!new LibraryReset(context).pending(),"Reset journal cleared after recovery");
            if(rollback)NearbyPairingChecks.require(late.isFile(),"Late original retained after interrupted rollback");else NearbyPairingChecks.require(!context.getSharedPreferences("local_policy",0).getBoolean("photos",true),"Automatic photo import durably disabled");recovered.closeForReset();
            mark.accept("Reset authored library: "+boundary+" cold recovery, fresh identity or original rollback, independent backup and unknown-file preservation");
        }
        Context context=new NearbyPairingChecks.FixtureContext(test.getTargetContext(),"nearby-connection-reset-"+UUID.randomUUID());LibraryRepository old=fixture(context);SyncStore store=new SyncStore(context,old);store.start(Collections.singletonList(UUID.randomUUID().toString()));String replica=store.replica();JSONObject graph=store.manifest();graph.put("created_at",0);File draft=new File(context.getFilesDir(),"scan_draft/authored-draft");draft.getParentFile().mkdirs();NearbyPairingChecks.write(draft,"unfinished scan sentinel");
        LibraryReset reset=new LibraryReset(context);reset.schedule(LibraryReset.CONNECTION);old.closeForReset();LibraryRepository rebuilt=new LibraryRepository(context);reset.initialize(rebuilt);JSONObject after=new SyncStore(context,rebuilt).manifest();after.put("created_at",0);NearbyPairingChecks.require(SyncProtocol.canonical(graph).equals(SyncProtocol.canonical(after)),"Connection reset preserves full portable graph and original hashes");NearbyPairingChecks.require(draft.isFile()&&!replica.equals(new SyncStore(context,rebuilt).replica())&&!new SyncStore(context,rebuilt).status().optBoolean("adopted"),"Connection reset keeps draft and clears old membership with fresh identity");rebuilt.closeForReset();mark.accept("Connection-only reset preserves documents, original hashes and unfinished draft; drops old group with fresh replica");
        Context linked=new NearbyPairingChecks.FixtureContext(test.getTargetContext(),"nearby-reset-link-"+UUID.randomUUID());LibraryRepository library=fixture(linked);File external=new File(linked.getCacheDir(),"outside");external.mkdirs();NearbyPairingChecks.write(new File(external,"policy.json"),"external sentinel");android.system.Os.symlink(external.getAbsolutePath(),new File(linked.getFilesDir(),"pairing").getAbsolutePath());boolean rejected=false;try{new LibraryReset(linked).schedule(LibraryReset.ERASE);}catch(Exception expected){rejected=true;}NearbyPairingChecks.require(rejected&&new File(external,"policy.json").isFile()&&library.documents("",null).size()==1,"Linked ancestor refused without touching external data");library.closeForReset();mark.accept("Reset refuses a linked enrollment directory and preserves external sentinel");
    }
    static void runUi(Instrumentation test,Consumer<String> mark)throws Exception {
        if(!ScannerChecks.disposableLibraryTarget(test.getTargetContext())||new LibraryReset(test.getTargetContext()).pending())throw new SecurityException("Read-only reset UI check requires the authorized spare with no pending reset");
        android.app.Activity activity=test.startActivitySync(new android.content.Intent(test.getTargetContext(),LibraryResetActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try{test.waitForIdleSync();Thread.sleep(300);click(test,"Erase this phone's library");requireText(test,"1 of 3 — Erase this phone's library?");click(test,"Review recovery warning");requireText(test,"2 of 3 — Check your recovery copy");click(test,"Continue to final confirmation");requireText(test,"3 of 3 — Final erase confirmation");requireText(test,"Erase library and close Stack");
            // Deliberately never invoke a destructive callback against the live
            // library, even with a blank phrase. Exercise cancellation only.
            click(test,"Cancel");if(new LibraryReset(test.getTargetContext()).pending())throw new AssertionError("Cancel scheduled a live reset");
            click(test,"Start a new connection");requireText(test,"Start a new connection?");click(test,"Keep current connection");
            mark.accept("Native reset UI: three erase warnings and a final confirmation screen; canceling erase/connection warnings schedules no live reset (destructive callback never invoked)");
        }finally{test.runOnMainSync(activity::finish);}
    }
    private static android.view.accessibility.AccessibilityNodeInfo node(Instrumentation test,String label){android.view.accessibility.AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();if(root==null)return null;for(android.view.accessibility.AccessibilityNodeInfo item:root.findAccessibilityNodeInfosByText(label)){if(label.equalsIgnoreCase(String.valueOf(item.getText()))||String.valueOf(item.getContentDescription()).startsWith(label+". "))return item;}return null;}
    private static void requireText(Instrumentation test,String text)throws Exception{long until=android.os.SystemClock.elapsedRealtime()+3000;while(node(test,text)==null&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(50);if(node(test,text)==null)throw new AssertionError("Native reset control missing: "+text);}
    private static void click(Instrumentation test,String text)throws Exception{requireText(test,text);android.view.accessibility.AccessibilityNodeInfo node=node(test,text);while(node!=null&&!node.isClickable())node=node.getParent();if(node==null||!node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Native reset action unreachable: "+text);test.waitForIdleSync();Thread.sleep(100);}
    private static LibraryRepository fixture(Context context)throws Exception{LibraryRepository repository=new LibraryRepository(context);repository.initialize();Document document=repository.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"reset-authored.pdf")));repository.rename(document.id,"Authored reset fixture 🦀");return repository;}
}
