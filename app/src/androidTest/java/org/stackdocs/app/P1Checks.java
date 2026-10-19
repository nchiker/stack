package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

final class P1Checks {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void finishText(LibraryRepository library,String text){OcrJob page;while((page=library.claimOcr())!=null)library.completeOcr(page,text,null);}
    static void analyze(LibraryRepository library){MetadataStore.Snapshot snap;while((snap=library.metadata.claim())!=null)library.metadata.complete(snap,MetadataRules.analyze(snap),false);}
    static void run(Instrumentation instrumentation,Consumer<String> mark)throws Exception {
        AutofillChecks.run(instrumentation,mark);
        LabeledDetailChecks.run(instrumentation,mark);
        Context context=new ScannerChecks.IsolatedContext(instrumentation.getTargetContext());LibraryRepository library=new LibraryRepository(context);library.initialize();
        Context nestedContext=new ScannerChecks.IsolatedContext(instrumentation.getTargetContext());LibraryRepository nested=new LibraryRepository(nestedContext);nested.initialize();int[] kicks={0};nested.setOcrChanged(() -> {require(!nested.database().inTransaction(),"OCR signal only after durable outer commit");kicks[0]++;});
        nested.database().beginTransaction();try{nested.importDocument(Uri.fromFile(OcrQueueChecks.fixture(nestedContext,"nested-batch.pdf")));require(kicks[0]==0,"Nested import does not launch OCR before batch commit");nested.database().setTransactionSuccessful();}finally{nested.database().endTransaction();}nested.archiveChanged();require(kicks[0]==1,"Outer operation emits work after commit");
        Context reviewContext=new ScannerChecks.IsolatedContext(instrumentation.getTargetContext());LibraryRepository review=new LibraryRepository(reviewContext);review.initialize();
        Document reviewDoc=review.importDocument(Uri.fromFile(OcrQueueChecks.fixture(reviewContext,"inline-details.pdf")));review.metadata.draft(reviewDoc.id,"manual:hold","Synthetic pending editor",0);finishText(review,"Synthetic reference 444 · Date: 07/31/2026 · Page 1\nTotal: USD 68.25");analyze(review);
        MetadataStore.View initialReview=review.metadata.view(reviewDoc.id);require(initialReview.offered.stream().anyMatch(v -> v.kind.equals("date")&&v.value.equals("2026-07-31")),"Labeled date within longer header is offered");
        require(review.metadata.apply(initialReview,"amount:total","dismissed",null,null),"Explicit dismissal");analyze(review);MetadataStore.View dismissedReview=review.metadata.view(reviewDoc.id);
        require(dismissedReview.dismissed.size()==1&&dismissedReview.offered.stream().noneMatch(v -> v.field.equals("amount:total")),"Dismissal stays out of ordinary offers");
        require(!review.metadata.apply(dismissedReview,"amount:total","accepted",null,null),"Ordinary Apply cannot undo a dismissal");
        require(review.metadata.reconsider(dismissedReview,"amount:total","edited","69.50","USD","Reviewed total"),"Only explicit fresh reconsider/edit changes dismissal");
        require(!review.metadata.reconsider(dismissedReview,"amount:total","accepted",null,null,null),"Stale reconsider is rejected");analyze(review);MetadataStore.View reconsidered=review.metadata.view(reviewDoc.id);DetailValue reviewedAmount=reconsidered.saved.stream().filter(v -> v.kind.equals("amount")).findFirst().get();
        review.metadata.save(reviewDoc.id,new DetailValue(reviewedAmount.field,reviewedAmount.kind,reviewedAmount.label,reviewedAmount.value,reviewedAmount.qualifier,reviewedAmount.evidence,"manual","removed",0),reconsidered.snapshot.revision);analyze(review);
        require(review.metadata.view(reviewDoc.id).dismissed.isEmpty()&&!review.metadata.reconsider(review.metadata.view(reviewDoc.id),"amount:total","accepted",null,null,null),"Explicit removal is not reopened as a dismissal");
        SuggestionJob inlineJob=new SuggestionJob("synthetic", "",0,"input","evidence",0,0,java.util.Arrays.asList("Reference 222 Invoice date: 2026-10-08 Due date: 2026-11-08","Date: 05/06/2026","Date: 2026-02-01 to 2026-02-28"),java.util.Collections.emptyList(),false,false,false);
        java.util.List<DetailValue> inlineValues=MetadataRules.analyze(new MetadataStore.Snapshot(inlineJob,0,"claim"));require(inlineValues.stream().anyMatch(v -> v.field.equals("date:due"))&&inlineValues.stream().noneMatch(v -> v.field.equals("date:document")),"Multiple labeled roles work; ambiguous numeric dates/ranges still abstain");
        mark.accept("Inline labeled dates retain roles/ambiguity guards; explicit dismissed-suggestion reconsideration validates fresh worker/decision tokens; normal Apply and removed fields stay protected");
        Document doc=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"p1.pdf")));library.metadata.draft(doc.id,"manual:hold","Synthetic pending editor",0);finishText(library,"Willow Credit Union\nInvoice date: 2026-10-08\nDue date: 2026-11-08\nTotal: USD 24.50");analyze(library);
        MetadataStore.View offered=library.metadata.view(doc.id);require(offered.offered.size()==4,"Typed details plus organization proposed");
        require(library.metadata.apply(offered,"amount:total","edited","24.75","USD"),"Reviewed exact correction");
        require(!library.metadata.apply(offered,"date:document","accepted",null,null),"Stale Apply rejected after independent decision");
        analyze(library);MetadataStore.View next=library.metadata.view(doc.id);require(library.metadata.apply(next,"date:document","dismissed",null,null),"Date dismissal");
        analyze(library);next=library.metadata.view(doc.id);require(library.metadata.apply(next,"organization:issuer","accepted",null,null),"Reviewed organization identity");
        analyze(library);MetadataStore.View saved=library.metadata.view(doc.id);DetailValue amount=saved.saved.stream().filter(v -> v.kind.equals("amount")).findFirst().get();
        library.metadata.save(doc.id,new DetailValue(amount.field,amount.kind,amount.label,amount.value,amount.qualifier,amount.evidence,"manual","removed",0),saved.snapshot.revision);
        library.metadata.saveNotes(doc.id,"A quartz-notes-only sentinel",0);require(library.documents("quartz-notes-only",null).size()==1,"Notes searchable without OCR hit");
        Document hit=library.documents("quartz-notes-only",null).get(0);require(hit.matchPageId==null&&hit.snippet.contains("Notes"),"Notes context has no invented page");
        String tag=library.organizations().keySet().iterator().next();library.setMemberships(doc.id,Collections.singletonList("bills"));
        require(library.documents("quartz-notes-only","bills",tag).size()==1&&library.documents("quartz-notes-only","medical",tag).isEmpty(),"Organization, stack and query compose");
        library.metadata.draft(doc.id,"notes","Uncommitted recoverable input",1);require(library.metadata.draft(doc.id,"notes",1,"").equals("Uncommitted recoverable input"),"Durable editor draft");
        library.database().execSQL("CREATE TRIGGER p1_notes_failure BEFORE UPDATE OF notes ON documents BEGIN SELECT RAISE(ABORT,'synthetic storage failure'); END");
        boolean rejected=false;try{library.metadata.saveNotes(doc.id,"Must not commit",1);}catch(RuntimeException expected){rejected=true;}
        require(rejected&&library.metadata.view(doc.id).notes.equals("A quartz-notes-only sentinel")&&library.metadata.draft(doc.id,"notes",1,"").equals("Uncommitted recoverable input"),"Failed notes save retains input and authoritative notes");library.database().execSQL("DROP TRIGGER p1_notes_failure");
        library.metadata.clearDraft(doc.id,"notes");require(library.metadata.draft(doc.id,"notes",1,"fallback").equals("fallback"),"Cancel removes draft without persisting");
        saved=library.metadata.view(doc.id);require(MetadataRules.completions("Candidate",doc.addedAt,saved.saved,saved.offered).size()<=5,"Small explicit completions");
        mark.accept("P1 typed exact detail correction/dismiss/removal, independent stale Apply, durable notes/drafts/failed save/cancel, organization identity and query/stack/tag composition; bounded completions");
        // Changed content, late work, deleted page and retry may not revive a field tombstone or erase accepted/manual values.
        library.metadata.retry(doc.id);MetadataStore.Snapshot stale=library.metadata.claim();library.editPage(doc.id,0,library.pages(doc.id).get(0).edited(90,null,"original"));
        require(!library.metadata.complete(stale,MetadataRules.analyze(stale),false),"Stale content worker rejected");finishText(library,"Willow Credit Union\nInvoice date: 2026-10-08\nTotal: USD 500.00");analyze(library);library.initialize();analyze(library);
        require(library.metadata.view(doc.id).offered.stream().noneMatch(v -> v.field.equals("amount:total")||v.field.equals("date:document")),"Removed/rejected fields do not revive after reprocessing/restart");
        require(library.metadata.view(doc.id).saved.stream().anyMatch(v -> v.kind.equals("organization")),"Accepted organization survives invalidated source");
        for(String[] invalid:new String[][]{{"amount","NaN","USD"},{"amount","1.0","ZZZ"},{"date","2026-02-30","day"},{"date","10/11/2026","day"}}){boolean refused=false;try{DetailValue.validate(invalid[0],"Label",invalid[1],invalid[2]);}catch(IllegalArgumentException expected){refused=true;}require(refused,"Typed invalid input refused");}
        mark.accept("Metadata claim/content/config invalidation, accepted values survive source changes; removed and dismissed details survive reprocess/reopen; strict typed dates/currency/decimal validation");
        byte[] archive;JSONObject before;
        try(LibraryArchive.Snapshot snap=library.archive().snapshot(new LibraryArchive.Progress())){ByteArrayOutputStream bytes=new ByteArrayOutputStream();LibraryArchive.write(snap,bytes,new LibraryArchive.Progress());archive=bytes.toByteArray();before=new JSONObject(snap.manifest.toString());}
        Context target=new ScannerChecks.IsolatedContext(instrumentation.getTargetContext());LibraryRepository restored=new LibraryRepository(target);restored.initialize();LibraryArchive restore=restored.archive();
        LibraryArchive.Staged staged=restore.stage(new ByteArrayInputStream(archive),new LibraryArchive.Progress());restore.plan(staged);restore.commit(staged,new LibraryArchive.Progress());
        try(LibraryArchive.Snapshot snap=restore.snapshot(new LibraryArchive.Progress())){for(String table:new String[]{"documents","details","tags","document_tags"})require(canonical(before.getJSONArray(table)).equals(canonical(snap.manifest.getJSONArray(table))),"P1 authoritative archive table round trip "+table);}
        require(restored.documents("quartz-notes-only",null,tag).size()==1,"Restored notes/tag search rebuilt");
        staged=restore.stage(new ByteArrayInputStream(archive),new LibraryArchive.Progress());LibraryArchive.Plan plan=restore.plan(staged);restore.commit(staged,new LibraryArchive.Progress());String mapped=plan.documents.get(doc.id);analyze(restored);
        require(restored.metadata.view(mapped).notes.equals("A quartz-notes-only sentinel")&&restored.metadata.view(mapped).offered.stream().noneMatch(v -> v.field.equals("amount:total")||v.field.equals("date:document")),"Collision-rebased restore preserves notes, tombstones and tags");
        library.database().execSQL("UPDATE pages SET ocr_text=ocr_text||char(10)||'Due date: 2026-11-08' WHERE document_id=?",new Object[]{doc.id});
        analyze(library);MetadataStore.View oldView=library.metadata.view(doc.id);byte[] staleArchive;require(oldView.offered.stream().anyMatch(v -> v.field.equals("date:due")),"Pre-restore actionable metadata offer exists");
        try(LibraryArchive.Snapshot snapshot=library.archive().snapshot(new LibraryArchive.Progress())){ByteArrayOutputStream bytes=new ByteArrayOutputStream();LibraryArchive.write(snapshot,bytes,new LibraryArchive.Progress());staleArchive=bytes.toByteArray();}
        library.deleteDocument(doc.id);LibraryArchive same=library.archive();LibraryArchive.Staged sameStage=same.stage(new ByteArrayInputStream(staleArchive),new LibraryArchive.Progress());same.plan(sameStage);same.commit(sameStage,new LibraryArchive.Progress());analyze(library);
        require(!library.metadata.apply(oldView,"date:due","accepted",null,null),"Pre-restore metadata Apply token rejected when IDs/evidence/revisions are reused");
        byte[] old=rewrite(archive,json -> {for(String key:new String[]{"details","tags","document_tags"})json.remove(key);JSONArray docs=json.getJSONArray("documents");for(int i=0;i<docs.length();i++){JSONObject row=docs.getJSONObject(i);row.remove("notes");row.remove("notes_revision");row.remove("metadata_revision");}for(int i=0;i<json.getJSONArray("stacks").length();i++)json.getJSONArray("stacks").getJSONObject(i).remove("sort_order");json.put("version",1).put("schema",6);});
        staged=restore.stage(new ByteArrayInputStream(old),new LibraryArchive.Progress());plan=restore.plan(staged);restore.commit(staged,new LibraryArchive.Progress());require(restored.metadata.view(plan.documents.get(doc.id)).notes.isEmpty(),"Old archive explicit empty defaults");
        for(byte[] hostile:new byte[][]{rewrite(archive,json -> json.put("version",99)),rewrite(archive,json -> json.getJSONArray("details").getJSONObject(0).put("document_id",UUID.randomUUID().toString())),rewrite(archive,json -> json.getJSONArray("document_tags").getJSONObject(0).put("tag_id",UUID.randomUUID().toString())),rewrite(archive,json -> json.getJSONArray("documents").getJSONObject(0).put("notes",new String(new char[MetadataStore.NOTES_LIMIT+1]).replace('\u0000','x')))}){boolean refused=false;try{restore.stage(new ByteArrayInputStream(hostile),new LibraryArchive.Progress());}catch(IOException expected){refused=true;}require(refused,"Hostile P1 archive rejected");}
        mark.accept("Format 2 full notes/details/tags/tombstone round trip, additive ID collisions and rebuilt notes/tag search; format 1/schema 6 explicit defaults; future/invalid references/oversize notes rejected");
        android.content.ClipData clip=DetailClipboard.clip("24.75");require(clip.getItemAt(0).getText().toString().equals("24.75")&&clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"),"Exact sensitive detail clip without reading/writing global clipboard");
        AccessPolicy.Session session=new AccessPolicy.Session();require(!session.authenticated(),"Cold session locked");long token=session.begin();require(!session.result(token,false)&&!session.authenticated(),"Cancel never grants");token=session.begin();session.lock();require(!session.result(token,true),"Stale authentication ignored");token=session.begin();require(session.result(token,true)&&session.authenticated(),"Current success grants");session.lock();require(!session.authenticated(),"Relock clears session");
        require(!PdfSelectionActivity.accepts(new Intent(Intent.ACTION_GET_CONTENT).setType("image/jpeg"))&&PdfSelectionActivity.accepts(new Intent(Intent.ACTION_GET_CONTENT).setType("application/pdf")),"Selection MIME contract");
        require(!instrumentation.getTargetContext().getSharedPreferences("local_policy",Context.MODE_PRIVATE).getBoolean("photos",false)&&!instrumentation.getTargetContext().getSharedPreferences("local_policy",Context.MODE_PRIVATE).getBoolean("app_lock",false),"No new opt-in defaults");
        List<String> ambiguityText=Arrays.asList("Date: 2026-12-14\nTotal: £7.40");
        SuggestionJob ambiguousText=new SuggestionJob("synthetic","",0,"input","evidence",0,0,ambiguityText,Collections.emptyList(),false,false,false);
        List<DetailValue> ambiguityValues=MetadataRules.analyze(new MetadataStore.Snapshot(ambiguousText,0,""));
        require(ambiguityValues.stream().anyMatch(v -> v.kind.equals("date")&&v.label.equals("Date (role unknown)")),"Generic Date has unknown role");
        for(DetailValue detail:ambiguityValues)if(detail.kind.equals("date"))require(new JSONObject(detail.evidence).getString("role").equals("unknown"),"Unknown role is portable provenance");
        require(ambiguityValues.stream().anyMatch(v -> v.kind.equals("amount")&&v.qualifier.equals("unknown")),"Pound symbol alone has unknown currency");
        for(String line:Arrays.asList("Total: EUR $7.40","Total: USD £7.40","Total: GBP €7.40")) {
            SuggestionJob mismatch=new SuggestionJob("synthetic","",0,"input","evidence",0,0,Collections.singletonList(line),Collections.emptyList(),false,false,false);
            require(MetadataRules.analyze(new MetadataStore.Snapshot(mismatch,0,"")).isEmpty(),"Conflicting symbol/code abstains");
        }
        mark.accept("Supplementary ambiguity regressions: generic Date role stays unknown in label/evidence, pound symbol stays unknown, incompatible symbol/code pairs abstain; stable primary date ID retains tombstones");
        PhotoLedger photos=new PhotoLedger(library);String photo=photos.reserve("synthetic-photo-source","digest-one");require(photo.equals(photos.reserve("synthetic-photo-source","digest-one")),"Photo identity stable across duplicate events");
        library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"photo-source.pdf")),photo,"Synthetic photo import");require(photos.decided("synthetic-photo-source"),"Death after commit recovers source acknowledgment");library.deleteDocument(photo);require(photos.decided("synthetic-photo-source")&&photos.reserve("synthetic-photo-source","changed-digest")==null,"Deleted photo never resurrects, changed source never overwrites");
        String unacknowledged=photos.reserve("deleted-before-ack","digest-two");library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"unacknowledged-photo.pdf")),unacknowledged,"Synthetic unacknowledged photo");library.deleteDocument(unacknowledged);
        require(photos.decided("deleted-before-ack")&&photos.reserve("deleted-before-ack","digest-two")==null,"Deletion before recovered source acknowledgment cannot resurrect a photo");
        photos.record("ignored-photo","ignored-digest","ignored","");require(photos.decided("ignored-photo")&&photos.duplicate("renamed-source","ignored-digest"),"Ignored/content-duplicate source remains ignored");
        mark.accept("Synthetic production photo ledger: source reservation/retry identity, postcommit acknowledgment recovery, deletion/changed-source no resurrection and ignored/content deduplication; no MediaStore access");
        mark.accept("Deterministic auth-result cancel/success/stale/relock boundary (no system enrollment claim), selection MIME contract and default-off local policy");
    }
    interface Mutation{void apply(JSONObject json)throws Exception;}
    static byte[] rewrite(byte[] archive,Mutation mutation)throws Exception{DataInputStream in=new DataInputStream(new ByteArrayInputStream(archive));byte[] magic=new byte[8];in.readFully(magic);int length=in.readInt();in.skipBytes(32);byte[] bytes=new byte[length];in.readFully(bytes);JSONObject json=new JSONObject(new String(bytes,StandardCharsets.UTF_8));mutation.apply(json);byte[] changed=json.toString().getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream result=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(result);out.write(magic);out.writeInt(changed.length);out.write(java.security.MessageDigest.getInstance("SHA-256").digest(changed));out.write(changed);byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return result.toByteArray();}
    private static String canonical(Object value)throws Exception {if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys)out.append(key).append(':').append(canonical(object.get(key))).append(',');return out+"}";}if(value instanceof JSONArray){List<String> rows=new ArrayList<>();JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)rows.add(canonical(array.get(i)));Collections.sort(rows);return rows.toString();}return String.valueOf(value);}
}
