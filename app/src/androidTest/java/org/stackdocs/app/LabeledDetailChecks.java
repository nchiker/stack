package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Independently authored coverage; no original-app contents, code or model used. */
final class LabeledDetailChecks {
    static void run(Instrumentation instrumentation,Consumer<String> mark)throws Exception {
        Context context=new ScannerChecks.IsolatedContext(instrumentation.getTargetContext());LibraryRepository library=new LibraryRepository(context);library.initialize();
        String text="Synthetic appointment summary\nClient #: 000741\nPatient #\nP-0042\nStaff Name: Alex Sample\nDate: July 31, 2026\nBalance due:\nUSD 0.00\nTotal Amount: $75.40\nInvoice number: INV-0088\nAccount ID: A-0023";
        Map<String,String> expected=new LinkedHashMap<>();expected.put("text:client-number","000741");expected.put("text:patient-number","P-0042");expected.put("text:staff-name","Alex Sample");expected.put("date:document","2026-07-31");expected.put("amount:due","0.00");expected.put("amount:total","75.40");expected.put("text:invoice-number","INV-0088");expected.put("text:account-number","A-0023");
        check(values(text),expected,"Source labeled fields");
        check(values(text.replace("Patient #\n","Patient #\n\n").replace("Balance due:\n","Balance due:\n\n\n")),expected,"Bounded blank OCR separators");
        for(String negative:new String[]{"Client # Date: July 31, 2026","Patient ID\nAccount number","Staff Name\nInvoice date","Invoice: 1234\nAccount: 0056","Client #: 0011\nClient #: 0022","Staff Name: Alex Sample\nStaff Name: Jamie Sample"})
            P1Checks.require(values(negative).stream().noneMatch(v -> v.kind.equals("text")),"Ambiguous, conflicting, missing or unsupported identifier/name stays unoffered");
        Document doc=library.importDocument(Uri.fromFile(P1CorpusChecks.render(context,"labeled-fields",Collections.singletonList(text))));
        try(OcrEngine engine=new OcrEngine(context)){OcrJob job;while((job=library.claimOcr())!=null){Bitmap bitmap=PageRenderer.render(job.source,job.mime,job.page,2400);try{library.completeOcr(job,engine.recognize(bitmap),null);}finally{bitmap.recycle();}}}
        P1Checks.analyze(library);MetadataStore.View offered=library.metadata.view(doc.id);check(offered.saved,expected,"Actual rendered OCR labeled fields, synthetic text="+offered.snapshot.text.pages);
        P1Checks.require(offered.offered.isEmpty()&&offered.saved.stream().allMatch(DetailValue::automatic),"Detected values saved automatically with accurate actor provenance");
        DetailValue client=offered.saved.stream().filter(v -> v.field.equals("text:client-number")).findFirst().get();
        P1Checks.require(client.kind.equals("text")&&client.qualifier.isEmpty()&&new org.json.JSONObject(client.evidence).getInt("page")==1,"Identifiers keep exact text, no numeric coercion, with page evidence");
        library.metadata.save(doc.id,new DetailValue(client.field,client.kind,client.label,"MANUAL-000007","",client.evidence,"edited","saved",0),offered.snapshot.revision);
        P1Checks.require(!library.metadata.apply(offered,"text:patient-number","accepted",null,null),"Other old field action becomes stale");P1Checks.analyze(library);
        P1Checks.require(library.documents("MANUAL-000007",null).size()==1,"Saved text correction searchable");
        library.metadata.retry(doc.id);P1Checks.analyze(library);P1Checks.require(library.metadata.view(doc.id).offered.stream().noneMatch(v -> v.field.equals(client.field)),"Reanalysis never replaces corrected identifier");
        byte[] bytes;try(LibraryArchive.Snapshot snapshot=library.archive().snapshot(new LibraryArchive.Progress())){ByteArrayOutputStream out=new ByteArrayOutputStream();LibraryArchive.write(snapshot,out,new LibraryArchive.Progress());bytes=out.toByteArray();}
        Context restoreContext=new ScannerChecks.IsolatedContext(instrumentation.getTargetContext());LibraryRepository restored=new LibraryRepository(restoreContext);restored.initialize();LibraryArchive archive=restored.archive();LibraryArchive.Staged staged=archive.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress());archive.plan(staged);archive.commit(staged,new LibraryArchive.Progress());
        P1Checks.require(restored.metadata.view(doc.id).saved.stream().anyMatch(v -> v.field.equals(client.field)&&v.value.equals("MANUAL-000007")&&v.origin.equals("edited")),"Archive retains explicit identifier correction/provenance");
        P1Checks.require(restored.documents("MANUAL-000007",null).size()==1,"Restored text search rebuilt");
        mark.accept("Authored labeled-field coverage: 8 exact source and actual rendered-OCR values, 6 abstention counterexamples, leading-zero identifiers/page provenance, explicit correction/stale Apply/reanalysis protection and archive/search recovery; no private or blind-quality claim");
    }
    private static List<DetailValue> values(String text){SuggestionJob job=new SuggestionJob("synthetic","",0,"input","evidence",0,0,Collections.singletonList(text),Collections.emptyList(),false,false,false);return MetadataRules.analyze(new MetadataStore.Snapshot(job,0,"claim"));}
    private static void check(List<DetailValue> values,Map<String,String> expected,String description){Map<String,String> actual=new LinkedHashMap<>();for(DetailValue v:values)actual.put(v.field,v.value);P1Checks.require(actual.equals(expected),description+" preserves exact identifiers/names and typed dates/amounts: "+actual);}
}
