package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Real production merge and exchange on isolated authored libraries only. */
final class SyncAutoChecks {
    static void run(Instrumentation test,Consumer<String> mark)throws Exception {
        Context a=new NearbyPairingChecks.FixtureContext(test.getTargetContext(),"auto-a-"+UUID.randomUUID());
        Context b=new NearbyPairingChecks.FixtureContext(test.getTargetContext(),"auto-b-"+UUID.randomUUID());
        LibraryRepository desktop=new LibraryRepository(a),phone=new LibraryRepository(b);desktop.initialize();phone.initialize();
        try {
            desktop.importDocument(Uri.fromFile(OcrQueueChecks.fixture(a,"desktop.pdf")));
            phone.importDocument(Uri.fromFile(OcrQueueChecks.fixture(b,"phone.pdf")));
            SyncStore sa=new SyncStore(a,desktop),sb=new SyncStore(b,phone);
            String aid=sa.replica(),bid=sb.replica();sa.start(Collections.singletonList(bid));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();new SyncExchange(a,desktop).export(bid,bytes);
            try(SyncExchange.Staged staged=new SyncExchange(b,phone).stage(new ByteArrayInputStream(bytes.toByteArray()))){new SyncExchange(b,phone).mergeConnected(staged);}
            bytes.reset();new SyncExchange(b,phone).export(aid,bytes);
            try(SyncExchange.Staged staged=new SyncExchange(a,desktop).stage(new ByteArrayInputStream(bytes.toByteArray()))){new SyncExchange(a,desktop).importFile(staged,null,null);}
            NearbyPairingChecks.require(desktop.documents("",null).size()==2&&phone.documents("",null).size()==2,"Initial merge keeps both independent documents");
            NearbyPairingChecks.require(desktop.stacks().size()==7&&phone.stacks().size()==7,"Named categories merge once, without generated duplicates");
            for(StackCategory stack:phone.stacks())NearbyPairingChecks.require(!stack.name.contains("adopt"),"Internal merge wording is never a category name");
            NearbyPairingChecks.require(sa.status().getJSONObject("conflicts").length()==0&&sb.status().getJSONObject("conflicts").length()==0,"Nonconflicting initial documents merge automatically");
            mark.accept("Automatic connection unions two populated libraries and matching categories; exact documents retained without adoption UI or duplicate stacks");
            String id=desktop.documents("",null).get(0).id;
            desktop.rename(id,"Edited on desktop 🦀");phone.setMemberships(id,Collections.singletonList(phone.stacks().get(0).id));
            exchange(a,desktop,b,phone,bid);exchange(b,phone,a,desktop,aid);exchange(a,desktop,b,phone,bid);
            NearbyPairingChecks.require(phone.document(id).title.equals("Edited on desktop 🦀")&&!desktop.document(id).stackIds.isEmpty(),"Independent title and stack edits converge in both directions");
            NearbyPairingChecks.require(sa.status().getJSONObject("conflicts").length()==0,"Independent fields do not require a decision");
            desktop.rename(id,"Desktop conflict version");phone.rename(id,"Phone conflict version");
            exchange(a,desktop,b,phone,bid);exchange(b,phone,a,desktop,aid);
            JSONObject conflicts=sb.status().getJSONObject("conflicts");NearbyPairingChecks.require(conflicts.length()>0,"Genuine same-field edits retain both alternatives: "+conflicts);
            String key=null;for(String field:SyncProtocol.keys(conflicts))if(field.startsWith("documents/")&&field.endsWith("/title"))key=field;
            NearbyPairingChecks.require(key!=null,"A genuine title comparison is available");
            JSONArray alternatives=conflicts.getJSONArray(key),expected=new JSONArray();Object chosen=null;
            for(int i=0;i<alternatives.length();i++){JSONObject alternative=alternatives.getJSONObject(i);expected.put(alternative.getJSONObject("id"));if(alternative.getJSONObject("id").getString("replica").equals(bid))chosen=alternative.get("value");}
            NearbyPairingChecks.require(chosen!=null,"Phone choice is identifiable");sb.resolve(key,expected,chosen);
            NearbyPairingChecks.require(sb.status().getJSONObject("conflicts").length()==0,"One title choice resolves its internal naming record without another prompt");
            exchange(b,phone,a,desktop,aid);exchange(a,desktop,b,phone,bid);
            NearbyPairingChecks.require(desktop.document(id).title.equals("Phone conflict version")&&phone.document(id).title.equals("Phone conflict version"),"Keep phone version converges without discarding the other document");
            NearbyPairingChecks.require(sa.status().getJSONObject("conflicts").length()==0&&sb.status().getJSONObject("conflicts").length()==0&&desktop.documents("",null).size()==2,"Decision resolves on both libraries without document loss");
            mark.accept("Bidirectional saved edits and organization merge automatically; a genuine edit conflict keeps both alternatives and the phone choice converges");
            legacy(test,mark);
            recovery(test,mark);
            queuedLock(test,mark);
        } finally {desktop.closeForReset();phone.closeForReset();}
    }
    static void legacy(Instrumentation test,Consumer<String> mark)throws Exception {
        Context a=new NearbyPairingChecks.FixtureContext(test.getTargetContext(),"legacy-a-"+UUID.randomUUID()),b=new NearbyPairingChecks.FixtureContext(test.getTargetContext(),"legacy-b-"+UUID.randomUUID());
        LibraryRepository desktop=new LibraryRepository(a),phone=new LibraryRepository(b);desktop.initialize();phone.initialize();
        try {
            Document doc=phone.importDocument(Uri.fromFile(OcrQueueChecks.fixture(b,"legacy.pdf")));phone.setMemberships(doc.id,Collections.singletonList("bills"));
            String peer=new SyncStore(b,phone).replica();new SyncStore(a,desktop).start(Collections.singletonList(peer));ByteArrayOutputStream bytes=new ByteArrayOutputStream();new SyncExchange(a,desktop).export(peer,bytes);
            try(SyncExchange.Staged staged=new SyncExchange(b,phone).stage(new ByteArrayInputStream(bytes.toByteArray()))){new SyncExchange(b,phone).importReviewed(staged,new SyncExchange(b,phone).reviewAdoption(staged));}
            NearbyPairingChecks.require(phone.stacks().size()==14,"Fixture reproduces the old generated duplicate categories");
            List<String> memberships=new ArrayList<>(phone.document(phone.documents("",null).get(0).id).stackIds);File original=phone.source(phone.documents("",null).get(0));String hash=SyncStore.hash(original,original.length());
            // Restoring a portable backup keeps legacy labels but has no sync history.
            phone.database().delete("sync_state",null,null);phone.database().delete("sync_receipts",null,null);
            NearbyPairingChecks.require(new SyncStore(b,phone).cleanLegacyStacks()==7,"Only verified generated starter duplicates are repaired");
            NearbyPairingChecks.require(phone.stacks().size()==7&&phone.documents("",null).size()==1&&phone.documents("",null).get(0).stackIds.equals(memberships)&&SyncStore.hash(original,original.length()).equals(hash),"Cleanup preserves documents, exact originals and memberships");
            for(StackCategory stack:phone.stacks())NearbyPairingChecks.require(!stack.name.contains("adopt"),"Old internal category labels are gone");
            NearbyPairingChecks.require(new SyncStore(b,phone).cleanLegacyStacks()==0,"Cleanup is idempotent");mark.accept("Legacy duplicate starter cleanup removes generated labels while preserving documents, exact originals and stack memberships");
        }finally{desktop.closeForReset();phone.closeForReset();}
    }
    static void inspectLabels(Instrumentation test,Consumer<String> mark)throws Exception {
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(test.getTargetContext().getDatabasePath("library.db").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);android.database.Cursor rows=db.rawQuery("SELECT s.id,s.name,(SELECT count(*) FROM document_stacks m WHERE m.stack_id=s.id) FROM stacks s ORDER BY s.sort_order",null)) {
            JSONArray labels=new JSONArray();while(rows.moveToNext())labels.put(new JSONObject().put("id",rows.getString(0)).put("name",rows.getString(1)).put("documents",rows.getInt(2)));mark.accept("Stack labels only: "+labels);
        }
    }
    private static void recovery(Instrumentation test,Consumer<String> mark)throws Exception {
        Context a=new NearbyPairingChecks.FixtureContext(test.getTargetContext(),"resume-a-"+UUID.randomUUID()),b=new NearbyPairingChecks.FixtureContext(test.getTargetContext(),"resume-b-"+UUID.randomUUID());
        LibraryRepository desktop=new LibraryRepository(a),phone=new LibraryRepository(b);desktop.initialize();phone.initialize();
        try {
            SyncStore sa=new SyncStore(a,desktop),sb=new SyncStore(b,phone);
            String aid=sa.replica(),bid=sb.replica();String group=sa.start(Collections.singletonList(bid)).getString("library");
            PairingTransport sender=new PairingTransport(a,desktop,"stack-resume-a-"+UUID.randomUUID()),receiver=new PairingTransport(b,phone,"stack-resume-b-"+UUID.randomUUID());
            sender.identityInit();receiver.identityInit();byte[] certificate=sender.identityCertificate();receiver.enroll(group,aid,certificate,PairingTransport.fingerprint(certificate));
            JSONObject labels=new JSONObject().put("own_name","Phone").put("peer_name","Computer").put("host","192.168.1.20").put("fingerprint",PairingTransport.fingerprint(certificate)).put("setup_pending",false);
            File file=new File(b.getFilesDir(),"pairing/names.json");NearbyPairingChecks.write(file,labels.toString());
            JSONObject pending=receiver.status();NearbyPairingChecks.require(pending.optBoolean("setup_pending")&&!pending.optBoolean("adopted"),"Persisted enrollment without a baseline remains recoverable even if a completion flag was saved");
            SyncJob.restoreConnection(b,pending);NearbyPairingChecks.require(SyncJob.preferences(b).getString("sync_peer_name","").equals("Computer")&&SyncJob.preferences(b).getString("sync_host","").equals("192.168.1.20"),"Approved connection recovers scheduler state independently of the UI callback");
            SyncJob.preferences(b).edit().clear().commit();labels.put("fingerprint","00".repeat(32));NearbyPairingChecks.write(file,labels.toString());
            SyncJob.restoreConnection(b,receiver.status());NearbyPairingChecks.require(SyncJob.preferences(b).getString("sync_peer_name","").isEmpty(),"Labels for another pin cannot restore a connection");
            labels.put("fingerprint",PairingTransport.fingerprint(certificate)).put("setup_pending",true);NearbyPairingChecks.write(file,labels.toString());
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();new SyncExchange(a,desktop).export(bid,bytes);
            try(SyncExchange.Staged stage=new SyncExchange(b,phone).stage(new ByteArrayInputStream(bytes.toByteArray()))){new SyncExchange(b,phone).mergeConnected(stage);}
            receiver.markComplete();NearbyPairingChecks.require(!receiver.status().optBoolean("setup_pending"),"Completed initial merge leaves the recovery route");
            receiver.revoke();SyncJob.restoreConnection(b,receiver.status());NearbyPairingChecks.require(SyncJob.preferences(b).getString("sync_peer_name","").isEmpty(),"Disconnected peers cannot restore automatic scheduling");
            mark.accept("Interrupted approved setup recovers its saved address and scheduler without trusting changed pins or reviving disconnected peers");
        }finally{desktop.closeForReset();phone.closeForReset();}
    }
    private static void queuedLock(Instrumentation test,Consumer<String> mark)throws Exception {
        if(!test.getTargetContext().getPackageName().equals("org.stackdocs.app.checks.autosync"))throw new SecurityException("Dedicated authored application required for local lock test");
        StackApplication app=(StackApplication)test.getTargetContext().getApplicationContext();
        android.content.SharedPreferences policy=app.getSharedPreferences("local_policy",Context.MODE_PRIVATE),saved=SyncJob.preferences(app);
        boolean locked=policy.getBoolean("app_lock",false);String peer=saved.getString("sync_peer_name",null);
        java.util.concurrent.CountDownLatch blocked=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        app.io.execute(()->{blocked.countDown();try{release.await(20,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        try {
            NearbyPairingChecks.require(blocked.await(20,java.util.concurrent.TimeUnit.SECONDS),"Repository queue is deliberately occupied");
            test.runOnMainSync(()->{policy.edit().putBoolean("app_lock",false).commit();saved.edit().putString("sync_peer_name","Authored computer").commit();SyncJob.kick(app,null);NearbyPairingChecks.require(SyncJob.active.get(),"Sync is queued before lock");policy.edit().putBoolean("app_lock",true).commit();app.access.lock();});
            release.countDown();app.io.submit(()->{}).get(20,java.util.concurrent.TimeUnit.SECONDS);
            NearbyPairingChecks.require(!SyncJob.active.get()&&SyncJob.status.equals("Unlock Stack to continue syncing"),"Queued work observes the later lock before opening any connection");
            mark.accept("Locking while sync waits behind repository work prevents the queued connection from starting");
        }finally{release.countDown();test.runOnMainSync(()->{policy.edit().putBoolean("app_lock",locked).commit();android.content.SharedPreferences.Editor edit=saved.edit();if(peer==null)edit.remove("sync_peer_name");else edit.putString("sync_peer_name",peer);edit.commit();});}
    }
    private static void exchange(Context from,LibraryRepository sender,Context to,LibraryRepository receiver,String peer)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();new SyncExchange(from,sender).export(peer,bytes);
        try(SyncExchange.Staged staged=new SyncExchange(to,receiver).stage(new ByteArrayInputStream(bytes.toByteArray()))){new SyncExchange(to,receiver).importFile(staged,null,null);}
    }
    static void prepare(Instrumentation test,Consumer<String> mark)throws Exception {
        if(!test.getTargetContext().getPackageName().equals("org.stackdocs.app.checks.autosync"))throw new SecurityException("Dedicated authored UI application required");
        Context context=test.getTargetContext();LibraryRepository library=new LibraryRepository(context);library.initialize();
        try{if(library.documents("",null).isEmpty()){Document doc=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"phone-ui.pdf")));library.rename(doc.id,"Authored phone document");}}
        finally{library.closeForReset();}
        mark.accept("Prepared authored phone UI library in checks.autosync; personal application untouched");
    }
}
