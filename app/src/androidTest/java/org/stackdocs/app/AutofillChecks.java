package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Automatic defaults are real saved values, not implicit manual choices. */
final class AutofillChecks {
    static void run(Instrumentation instrumentation,Consumer<String> mark)throws Exception {
        Context context=new ScannerChecks.IsolatedContext(instrumentation.getTargetContext());LibraryRepository library=new LibraryRepository(context);library.initialize();
        Document doc=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"automatic.pdf")));
        library.metadata.save(doc.id,new DetailValue("manual:existing","text","Custom","Unrelated saved value","","{}","manual","saved",0),0);org.json.JSONObject projectionBefore=PreservationProjection.read(context);
        P1Checks.finishText(library,"Willow Credit Union\nInvoice date: 2026-10-08\nTotal: USD 24.50\nClient #: 000741");P1Checks.analyze(library);
        MetadataStore.View first=library.metadata.view(doc.id);P1Checks.require(first.saved.size()==5&&first.offered.isEmpty()&&first.status.equals("ready")&&first.saved.stream().filter(DetailValue::automatic).count()==4,"Automatic values committed together, current work ready, actor preserved");
        P1Checks.require(library.organizations().size()==1&&library.documents("000741",null).size()==1,"Automatic organization and text values indexed");
        org.json.JSONObject projectionAfter=PreservationProjection.read(context);
        DetailValue amount=first.saved.stream().filter(v -> v.kind.equals("amount")).findFirst().get();
        library.metadata.save(doc.id,new DetailValue(amount.field,amount.kind,amount.label,"89.25","USD",amount.evidence,"manual","saved",0),first.snapshot.revision);
        boolean stale=false;try{library.metadata.save(doc.id,amount,first.snapshot.revision);}catch(IllegalArgumentException expected){stale=true;}P1Checks.require(stale,"Old editor revision cannot undo a correction");
        P1Checks.analyze(library);MetadataStore.View edited=library.metadata.view(doc.id);DetailValue date=edited.saved.stream().filter(v -> v.kind.equals("date")).findFirst().get();
        library.metadata.save(doc.id,new DetailValue(date.field,date.kind,date.label,date.value,date.qualifier,date.evidence,"manual","removed",0),edited.snapshot.revision);P1Checks.analyze(library);
        library.database().execSQL("UPDATE pages SET ocr_text=replace(ocr_text,'24.50','300.00') WHERE document_id=?",new Object[]{doc.id});library.initialize();P1Checks.analyze(library);
        MetadataStore.View reopened=library.metadata.view(doc.id);P1Checks.require(reopened.saved.stream().anyMatch(v -> v.value.equals("89.25")&&!v.automatic())&&reopened.saved.stream().noneMatch(v -> v.kind.equals("date"))&&reopened.offered.isEmpty(),"Manual correction/removal survive new OCR/restart");
        byte[] archive;try(LibraryArchive.Snapshot snapshot=library.archive().snapshot(new LibraryArchive.Progress())){ByteArrayOutputStream out=new ByteArrayOutputStream();LibraryArchive.write(snapshot,out,new LibraryArchive.Progress());archive=out.toByteArray();}
        Context other=new ScannerChecks.IsolatedContext(instrumentation.getTargetContext());LibraryRepository restored=new LibraryRepository(other);restored.initialize();LibraryArchive.Staged staged=restored.archive().stage(new ByteArrayInputStream(archive),new LibraryArchive.Progress());restored.archive().plan(staged);restored.archive().commit(staged,new LibraryArchive.Progress());P1Checks.analyze(restored);
        MetadataStore.View round=restored.metadata.view(doc.id);P1Checks.require(round.saved.stream().filter(DetailValue::automatic).count()==2&&round.saved.stream().anyMatch(v -> v.value.equals("89.25")&&!v.automatic())&&round.saved.stream().noneMatch(v -> v.kind.equals("date")),"Format 2 keeps automatic actor, manual correction, removal and tag/search recovery");
        mark.accept("Automatic fill-once details: current worker transaction/provenance/indexed organization; edit/remove/stale editor/reprocess/reopen and format-2 actor/choices round trip; PROJECTION_FIXTURE_JSON "+new org.json.JSONObject().put("before",projectionBefore).put("after",projectionAfter));

        Document pending=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"pending.pdf")));P1Checks.finishText(library,"Invoice date: 2026-10-08\nTotal: USD 24.50");
        library.metadata.draft(pending.id,"manual:unfinished","Synthetic unsaved input",0);P1Checks.analyze(library);MetadataStore.View held=library.metadata.view(pending.id);P1Checks.require(held.saved.isEmpty()&&held.offered.size()==2&&held.snapshot.revision==0,"Draft holds whole metadata revision stable");
        library.metadata.clearDraft(pending.id,"manual:unfinished");MetadataStore.Snapshot claim=library.metadata.claim();
        library.database().execSQL("CREATE TRIGGER automatic_failure BEFORE INSERT ON details WHEN NEW.field_id='amount:total' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        boolean failed=false;try{library.metadata.complete(claim,MetadataRules.analyze(claim),false);}catch(RuntimeException expected){failed=true;}P1Checks.require(failed&&library.metadata.view(pending.id).saved.isEmpty()&&library.metadata.view(pending.id).snapshot.revision==0,"Failed automatic transaction leaves no partial values/revision");
        P1Checks.require(library.metadata.complete(claim,Collections.emptyList(),true)&&library.metadata.view(pending.id).status.equals("failed"),"Failed completion remains actionable");
        library.database().execSQL("DROP TRIGGER automatic_failure");library.metadata.retry(pending.id);P1Checks.analyze(library);P1Checks.require(library.metadata.view(pending.id).saved.size()==2,"Retry saves once");
        Document late=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"late-draft.pdf")));P1Checks.finishText(library,"Total: USD 12.00");MetadataStore.Snapshot old=library.metadata.claim();library.metadata.draft(late.id,"notes","Synthetic draft after worker claim",0);library.metadata.complete(old,MetadataRules.analyze(old),false);P1Checks.require(library.metadata.view(late.id).saved.isEmpty()&&library.metadata.draft(late.id,"notes",0,"").equals("Synthetic draft after worker claim"),"Late worker cannot change revision or discard a newly opened draft");
        Document denied=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"dismissed.pdf")));P1Checks.finishText(library,"Total: USD 10.00");library.metadata.save(denied.id,new DetailValue("amount:total","amount","Total","10.00","USD","{}","dismissed","rejected",0),0);P1Checks.analyze(library);P1Checks.require(library.metadata.view(denied.id).saved.isEmpty()&&library.metadata.view(denied.id).dismissed.size()==1,"Existing dismissal never auto-applies");
        Document partial=library.importDocument(Uri.fromFile(P1CorpusChecks.render(context,"partial-details",Arrays.asList("Total: USD 12.00","Unreadable synthetic page"))));
        OcrJob page;int position=0;while((page=library.claimOcr())!=null)library.completeOcr(page,position++==0?"Total: USD 12.00":"",position==1?null:"synthetic failure");P1Checks.analyze(library);
        MetadataStore.View partialView=library.metadata.view(partial.id);P1Checks.require(partialView.snapshot.text.partial&&partialView.saved.isEmpty()&&partialView.offered.size()==1,"Partial OCR retains review rather than automatic acceptance");
        P1Checks.require(library.metadata.apply(partialView,"amount:total","accepted",null,null),"Explicit review of partial evidence remains available");
        Document legacy=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"legacy-labels.pdf")));P1Checks.finishText(library,"Date: 2026-10-08\nTotal: USD 12.00\nClient #: 000741");
        library.metadata.save(legacy.id,new DetailValue("legacy:date","text","Date","Historical date","","{}","manual","removed",0),0);
        library.metadata.save(legacy.id,new DetailValue("manual:total","amount","Total Amount","99.00","USD","{}","manual","saved",0),1);
        library.metadata.save(legacy.id,new DetailValue("legacy:client","text","Client number","00777","","{}","manual","saved",0),2);P1Checks.analyze(library);
        MetadataStore.View legacyView=library.metadata.view(legacy.id);P1Checks.require(legacyView.saved.size()==2&&legacyView.offered.isEmpty()&&legacyView.saved.stream().noneMatch(DetailValue::automatic),"Equivalent imported/custom labels and removal tombstones protect alternate field identities");
        for(String[] example:new String[][]{{"Total: $18.40","USD","bare-dollar-default"},{"Total: CAD $18.40","CAD","explicit-code"},{"Total: AUD $18.40","AUD","explicit-code"},{"Total: £18.40","unknown","symbol"},{"Total: 18.40","unknown","unknown"}}){
            SuggestionJob input=new SuggestionJob("synthetic","",0,"input","evidence",0,0,Collections.singletonList(example[0]),Collections.emptyList(),false,false,false);DetailValue currency=MetadataRules.analyze(new MetadataStore.Snapshot(input,0,"claim")).get(0);P1Checks.require(currency.qualifier.equals(example[1])&&new org.json.JSONObject(currency.evidence).getString("currency_basis").equals(example[2]),"Bare-dollar USD default, explicit code override and remaining unknowns carry honest provenance");}
        mark.accept("Draft/no-overwrite gating, transactional injected failure/failed status/retry, late draft retention, partial-OCR review, equivalent legacy/manual labels/removals and prior dismissal preserved under automatic default");
    }
}
