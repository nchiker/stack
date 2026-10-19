package org.stackdocs.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

/** Real repository/format/filesystem/SQLite checks in disposable synthetic child contexts only. */
final class ArchiveChecks {
    private static final class Death extends Error {}
    static void run(Context parent,Consumer<String> mark) throws Exception {
        Context source=new ScannerChecks.IsolatedContext(parent);LibraryRepository library=new LibraryRepository(source);library.initialize();
        Document pdf=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(source,"archive.pdf")));
        library.rename(pdf.id,"Authoritative manual title");library.setMemberships(pdf.id,Collections.emptyList());library.setStackVisible("medical",false);library.createStack("Synthetic custom");
        String custom=library.stacks().get(library.stacks().size()-1).id;
        Bitmap image=Bitmap.createBitmap(120,160,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);byte[] jpeg;try{jpeg=ScannerChecks.jpeg(image);}finally{image.recycle();}
        ScanDraftStore store=new ScanDraftStore(source);ScanDraftStore.Draft draft=store.append(jpeg);draft=store.append(jpeg);draft=store.edit(0,draft.pages.get(0).edited(90,new float[]{.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f},"bright").flattened(true));
        Document scan=library.saveScan(store,draft,"Explicit scan",true,Arrays.asList("bills",custom),true);
        OcrJob job;while((job=library.claimOcr())!=null)library.completeOcr(job,job.page.sourcePage==1?"quartz archive sentinel":"Invoice\nAmount due 24\nDue date tomorrow",null);
        SuggestionJob proposal;while((proposal=library.suggestions.claim())!=null)library.suggestions.complete(proposal,SuggestionRules.analyze(proposal),false);
        SuggestionStore.View view=library.suggestions.view(pdf.id);require(view.status.equals("ready"),"Synthetic ready proposal");
        require(library.suggestions.decide(view,"filing","dismissed",null,null),"Dismiss filing");
        while((proposal=library.suggestions.claim())!=null)library.suggestions.complete(proposal,SuggestionRules.analyze(proposal),false);
        library.retryOcr(scan.id,library.pages(scan.id).get(0).id);job=library.claimOcr();library.completeOcr(job,null,"synthetic_failure");
        byte[] draftBefore=ScannerChecks.bytes(new File(store.directory,"draft.json"));
        File recovery=new File(source.getFilesDir(),"consolidation_recovery/keep");require(recovery.getParentFile().mkdirs(),"Recovery fixture directory");try(FileOutputStream out=new FileOutputStream(recovery)){out.write(123);}
        LibraryArchive archive=library.archive();LibraryArchive.Progress progress=new LibraryArchive.Progress();byte[] bytes;
        JSONObject authoritative;
        long start=SystemClock.elapsedRealtime();
        try(LibraryArchive.Snapshot snapshot=archive.snapshot(progress)) {
            authoritative=new JSONObject(snapshot.manifest.toString());ByteArrayOutputStream out=new ByteArrayOutputStream();LibraryArchive.write(snapshot,out,progress);bytes=out.toByteArray();
        }
        long exportMs=SystemClock.elapsedRealtime()-start;
        require(Arrays.equals(draftBefore,ScannerChecks.bytes(new File(store.directory,"draft.json"))) && recovery.isFile(),"Backup leaves draft/recovery unchanged");
        Context target=new ScannerChecks.IsolatedContext(parent);LibraryRepository restored=new LibraryRepository(target);restored.initialize();
        ScanDraftStore targetDraft=new ScanDraftStore(target);targetDraft.append(jpeg);byte[] targetDraftBefore=ScannerChecks.bytes(new File(targetDraft.directory,"draft.json"));
        LibraryArchive restore=restored.archive();start=SystemClock.elapsedRealtime();LibraryArchive.Staged staged=restore.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress());
        require(restored.documents("",null).isEmpty(),"Validation doesn't mutate library");restore.plan(staged);require(restored.documents("",null).isEmpty(),"Review doesn't mutate library");
        require(restore.commit(staged,new LibraryArchive.Progress())==2,"Restore two documents");long restoreMs=SystemClock.elapsedRealtime()-start;
        require(restore.commit(staged,new LibraryArchive.Progress())==2 && restored.documents("",null).size()==2,"Same operation retry idempotent");
        require(Arrays.equals(targetDraftBefore,ScannerChecks.bytes(new File(targetDraft.directory,"draft.json"))),"Restore leaves active local draft intact");
        try(LibraryArchive.Snapshot snapshot=restore.snapshot(new LibraryArchive.Progress())) {
            equalRows(authoritative,snapshot.manifest,"documents",Collections.emptySet());equalRows(authoritative,snapshot.manifest,"pages",new HashSet<>(Arrays.asList("ocr_error")));
            equalRows(authoritative,snapshot.manifest,"stacks",Collections.emptySet());equalRows(authoritative,snapshot.manifest,"memberships",Collections.emptySet());equalRows(authoritative,snapshot.manifest,"choices",Collections.emptySet());
            equalRows(authoritative,snapshot.manifest,"suggestions",new HashSet<>(Arrays.asList("generation","status")));
            for(JSONObject file:objects(snapshot.manifest.getJSONArray("files")))require(Arrays.equals(ScannerChecks.bytes(new File(snapshot.directory,file.getString("path"))),ScannerChecks.bytes(new File(source.getFilesDir(),"documents/"+file.getString("path").substring(10)))),"Every retained original byte preserved");
        }
        Document hit=restored.documents("quartz",null).get(0);require(hit.id.equals(pdf.id) && hit.matchPageId.equals(restored.pages(pdf.id).get(1).id) && hit.snippet.contains("Page 2"),"Later-page FTS/context rebuilt");
        ByteArrayOutputStream pdfExport=new ByteArrayOutputStream();restored.exportPdf(restored.document(pdf.id),pdfExport);require(Arrays.equals(ScannerChecks.bytes(library.source(pdf)),pdfExport.toByteArray()),"Untouched restored PDF byte-identical export");
        while((proposal=restored.suggestions.claim())!=null)restored.suggestions.complete(proposal,SuggestionRules.analyze(proposal),false);
        require(restored.suggestions.view(pdf.id).filingState.equals("dismissed") && restored.document(pdf.id).stackIds.isEmpty(),"Dismissal/explicit empty filing survive recomputation");
        require(restored.pages(scan.id).get(0).flatten && restored.pages(scan.id).get(0).rotation==90,"Saved geometry/enhancement survives");
        restored.retryFailedOcr(scan.id);require(restored.claimOcr()!=null,"Restored failed work can retry");
        mark.accept("Archive full authoritative metadata/original round trip; empty restore, draft/recovery exclusion, later-page FTS, untouched PDF export, explicit empty/dismissal and failed OCR retry; bytes="+bytes.length+" export_ms="+exportMs+" restore_ms="+restoreMs);

        String originalFingerprint=PreservationChecks.fingerprint(target);byte[] originalBytes=ScannerChecks.bytes(restored.source(restored.document(pdf.id)));
        staged=restore.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress());LibraryArchive.Plan collision=restore.plan(staged);require(collision.collisions==2,"Review identifies ID conflicts");restore.commit(staged,new LibraryArchive.Progress());
        require(restored.documents("",null).size()==4 && restored.document(pdf.id).title.equals("Authoritative manual title") && restored.document(pdf.id).stackIds.isEmpty(),"Additive restore keeps existing IDs/names/empty filing");
        require(Arrays.equals(originalBytes,ScannerChecks.bytes(restored.source(restored.document(pdf.id)))),"Additive restore keeps existing original");
        String mappedPdf=collision.documents.get(pdf.id);while((proposal=restored.suggestions.claim())!=null)restored.suggestions.complete(proposal,SuggestionRules.analyze(proposal),false);
        require(restored.suggestions.view(mappedPdf).filingState.equals("dismissed"),"Mapped current evidence keeps dismissal");
        staged=restore.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress());restore.plan(staged);restore.commit(staged,new LibraryArchive.Progress());require(restored.documents("",null).size()==6,"Selecting archive again imports again without overwrite");
        mark.accept("Nonempty additive conflicts remap documents/pages/scan linkage, merge stacks, rebase current choice evidence, and explicit import-again semantics");

        LibraryArchive.Snapshot pinned=archive.snapshot(new LibraryArchive.Progress());library.rename(pdf.id,"Changed after snapshot");library.deleteDocument(pdf.id);ByteArrayOutputStream pinnedOutput=new ByteArrayOutputStream();LibraryArchive.write(pinned,pinnedOutput,new LibraryArchive.Progress());pinned.close();
        Context pinTarget=new ScannerChecks.IsolatedContext(parent);LibraryRepository pinLibrary=new LibraryRepository(pinTarget);pinLibrary.initialize();LibraryArchive pin=pinLibrary.archive();staged=pin.stage(new ByteArrayInputStream(pinnedOutput.toByteArray()),new LibraryArchive.Progress());pin.plan(staged);pin.commit(staged,new LibraryArchive.Progress());require(pinLibrary.document(pdf.id).title.equals("Authoritative manual title"),"Pinned export coherent across rename/delete");
        mark.accept("Immutable snapshot retains bytes and metadata through subsequent repository rename/delete");

        String fingerprint=PreservationChecks.fingerprint(target);int rejected=0;
        List<byte[]> hostile=new ArrayList<>();hostile.add(new byte[]{'P','K',3,4});hostile.add(Arrays.copyOf(bytes,bytes.length-1));byte[] corrupt=bytes.clone();corrupt[corrupt.length-1]^=1;hostile.add(corrupt);hostile.add(Arrays.copyOf(bytes,bytes.length+1));
        hostile.add(rewrite(bytes,json->json.put("version",99)));
        hostile.add(rewrite(bytes,json->json.getJSONArray("files").getJSONObject(0).put("path","../private")));
        hostile.add(rewrite(bytes,json->json.getJSONArray("files").put(json.getJSONArray("files").getJSONObject(0))));
        hostile.add(rewrite(bytes,json->json.getJSONArray("files").getJSONObject(0).put("length",LibraryArchive.FILE_BYTES+1)));
        hostile.add(rewrite(bytes,json->json.getJSONArray("pages").getJSONObject(0).put("position",999)));
        hostile.add(rewrite(bytes,json->json.getJSONArray("pages").getJSONObject(0).put("rotation",17)));
        hostile.add(rewrite(bytes,json->json.getJSONArray("pages").getJSONObject(0).put("document_id",UUID.randomUUID().toString())));
        hostile.add(rewrite(bytes,json->json.getJSONArray("files").getJSONObject(0).put("path","originals/"+pdf.id+"/symlink")));
        hostile.add(rewrite(bytes,json->json.getJSONArray("documents").put(json.getJSONArray("documents").getJSONObject(0))));
        for(byte[] input:hostile){boolean failed=false;try{LibraryArchive.Staged invalid=restore.stage(new ByteArrayInputStream(input),new LibraryArchive.Progress());invalid.close();}catch(IOException expected){failed=true;}require(failed,"Hostile archive rejected "+rejected);rejected++;require(PreservationChecks.fingerprint(target).equals(fingerprint),"Hostile input preserves existing rows");}
        for(String malformed:Arrays.asList("{\"x\":1,\"x\":2}","{'x':1}","{\"x\":01}","{\"x\":NaN}","{\"x\":\"a\n\"}")){boolean failed=false;try{ArchiveJson.read(malformed);}catch(IOException expected){failed=true;}require(failed,"Strict JSON refused");}
        LibraryArchive.Progress canceled=new LibraryArchive.Progress();canceled.canceled=true;boolean canceledStage=false;try{restore.stage(new ByteArrayInputStream(bytes),canceled);}catch(InterruptedIOException expected){canceledStage=true;}require(canceledStage,"Validation canceled");
        staged=restore.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress());restore.plan(staged);canceled=new LibraryArchive.Progress();canceled.canceled=true;try{restore.commit(staged,canceled);throw new AssertionError("Canceled commit accepted");}catch(InterruptedIOException expected){}staged.close();require(PreservationChecks.fingerprint(target).equals(fingerprint),"Cancel leaves existing library unchanged");
        try(LibraryArchive.Snapshot snapshot=restore.snapshot(new LibraryArchive.Progress())) {
            boolean failed=false;try{LibraryArchive.write(snapshot,new OutputStream(){int written;public void write(int b)throws IOException{if(++written>200)throw new IOException("synthetic write failure");}},new LibraryArchive.Progress());}catch(IOException expected){failed=true;}require(failed,"Destination write failure surfaced");
        }
        mark.accept("Hostile format/future version/traversal/link-type/duplicate/missing/trailing/corrupt/limit/order/geometry/reference inputs rejected; strict JSON; canceled staging/commit and failed output preserve library ("+rejected+" archives)");

        for(String boundary:Arrays.asList("journal","file","transaction","committed")) {
            Context interrupted=new ScannerChecks.IsolatedContext(parent);LibraryRepository live=new LibraryRepository(interrupted);live.initialize();Document existing=live.importDocument(Uri.fromFile(OcrQueueChecks.fixture(interrupted,"existing.pdf")));live.rename(existing.id,"Keep existing");
            LibraryArchive transaction=live.archive();LibraryArchive.Staged pending=transaction.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress());transaction.plan(pending);transaction.probe=at->{if(at.equals(boundary))throw new Death();};
            try{transaction.commit(pending,new LibraryArchive.Progress());throw new AssertionError("Boundary not reached "+boundary);}catch(Death expected){}
            live=new LibraryRepository(interrupted);live.initialize();int count=boundary.equals("committed")?3:1;require(live.documents("",null).size()==count && live.document(existing.id).title.equals("Keep existing"),"Recovery visibility at "+boundary);
            require(new File(interrupted.getFilesDir(),"documents").list().length==count,"Recovery only committed original directories at "+boundary);
            live.initialize();require(live.documents("",null).size()==count,"Repeated recovery no duplicates");
        }
        mark.accept("Injected process-death boundaries at durable journal, file installation, transaction and committed marker recover atomically; no existing loss/orphan/duplicate (exception simulation, not power-loss evidence)");

        // A deleted row ID may be reused by an archive; old OCR and suggestion actions must remain invalid.
        LibraryRepository race=new LibraryRepository(new ScannerChecks.IsolatedContext(parent));race.initialize();LibraryArchive raceArchive=race.archive();staged=raceArchive.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress());raceArchive.plan(staged);raceArchive.commit(staged,new LibraryArchive.Progress());
        while((proposal=race.suggestions.claim())!=null)race.suggestions.complete(proposal,SuggestionRules.analyze(proposal),false);
        SuggestionStore.View oldReview=race.suggestions.view(pdf.id);
        race.retryOcr(pdf.id,race.pages(pdf.id).get(0).id);OcrJob old=race.claimOcr();race.deleteDocument(pdf.id);
        staged=raceArchive.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress());raceArchive.plan(staged);raceArchive.commit(staged,new LibraryArchive.Progress());
        require(!race.completeOcr(old,"STALE RESTORE RESULT",null)&&!race.completeOcr(old,null,"late_failure"),"Pre-restore claims rejected");
        while((proposal=race.suggestions.claim())!=null)race.suggestions.complete(proposal,SuggestionRules.analyze(proposal),false);
        require(!race.suggestions.decide(oldReview,"title","accepted",null,null),"Pre-restore ready review action rejected even after recomputation");
        mark.accept("Pre-restore native success/failure and ready review actions cannot modify a restored reused ID; fresh suggestion incarnation and normalized claims");
        Context changing=new ScannerChecks.IsolatedContext(parent);LibraryRepository concurrent=new LibraryRepository(changing);concurrent.initialize();LibraryArchive reviewed=concurrent.archive();
        LibraryArchive.Staged review=reviewed.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress());reviewed.plan(review);
        Document added=concurrent.importDocument(Uri.fromFile(OcrQueueChecks.fixture(changing,"during-review.pdf")));concurrent.setMemberships(added.id,Arrays.asList("bills"));
        boolean changed=false;try{reviewed.commit(review,new LibraryArchive.Progress());}catch(IOException expected){changed=true;}review.close();
        require(changed && concurrent.documents("",null).size()==1 && concurrent.document(added.id).stackIds.contains("bills"),"Pristine review cannot remove stacks of a concurrent new document");
        mark.accept("Library changes during pristine restore review reject commit without deleting new memberships");
    }
    private interface Mutation {void apply(JSONObject json)throws Exception;}
    private static byte[] rewrite(byte[] archive,Mutation mutation)throws Exception {
        DataInputStream input=new DataInputStream(new ByteArrayInputStream(archive));input.skipBytes(8);int size=input.readInt();input.skipBytes(32);byte[] manifest=new byte[size];input.readFully(manifest);JSONObject json=new JSONObject(new String(manifest,StandardCharsets.UTF_8));mutation.apply(json);byte[] edited=json.toString().getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream output=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(output);out.write(LibraryArchive.MAGIC);out.writeInt(edited.length);out.write(MessageDigest.getInstance("SHA-256").digest(edited));out.write(edited);out.write(archive,44+size,archive.length-44-size);return output.toByteArray();
    }
    private static void equalRows(JSONObject before,JSONObject after,String table,Set<String> ignored)throws Exception {
        JSONArray a=before.getJSONArray(table),b=after.getJSONArray(table);require(a.length()==b.length(),table+" row count");
        Set<String> rows=new HashSet<>();for(JSONObject row:objects(a)){for(String key:ignored)row.remove(key);rows.add(canonical(row));}for(JSONObject row:objects(b)){for(String key:ignored)row.remove(key);require(rows.remove(canonical(row)),table+" authoritative row mismatch");}require(rows.isEmpty(),"All "+table+" rows matched");
    }
    private static String canonical(JSONObject row)throws Exception {TreeMap<String,String> map=new TreeMap<>();for(Iterator<String> it=row.keys();it.hasNext();){String key=it.next();map.put(key,row.get(key).toString());}return map.toString();}
    private static List<JSONObject> objects(JSONArray array)throws Exception {List<JSONObject> out=new ArrayList<>();for(int i=0;i<array.length();i++)out.add(array.getJSONObject(i));return out;}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
