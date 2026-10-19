package org.stackdocs.app;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.function.Consumer;

/** Hold a durable text-analysis claim, force-stop externally, verify a genuinely new process. */
final class SuggestionRestartChecks {
    static void run(Context context,boolean prepare,Consumer<String> mark) throws Exception {
        LibraryRepository library=new LibraryRepository(context); library.initialize();
        File token=new File(context.getFilesDir(),"held.json");
        if(prepare) {
            SuggestionChecks.require(library.documents("",null).isEmpty(),"Fresh restart context");
            Document document=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"restart.pdf")));
            SuggestionChecks.finishOcr(library,"Cedar Market\nSales receipt\nTotal 5\nCash"); SuggestionChecks.drain(library);
            library.suggestions.decide(library.suggestions.view(document.id),"filing","dismissed",null,null); SuggestionChecks.drain(library);
            library.suggestions.retry(document.id); SuggestionJob job=library.suggestions.claim();
            JSONObject saved=new JSONObject(); saved.put("id",job.documentId); saved.put("claim",job.claim); saved.put("generation",job.generation);
            saved.put("input",job.input); saved.put("evidence",job.evidence); saved.put("title",job.titleRevision); saved.put("filing",job.filingRevision);
            try(FileOutputStream out=new FileOutputStream(token)){out.write(saved.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            mark.accept("SUGGESTION_RESTART_READY: durable running claim held before analysis");
            // The external controller force-stops this process after the status reaches it.
            Thread.sleep(30000);
            throw new AssertionError("Restart check was not interrupted");
        } else {
            JSONObject saved=new JSONObject(new String(ScannerChecks.bytes(token),StandardCharsets.UTF_8));
            SuggestionJob old=new SuggestionJob(saved.getString("id"),saved.getString("claim"),saved.getLong("generation"),saved.getString("input"),saved.getString("evidence"),saved.getLong("title"),saved.getLong("filing"),Collections.emptyList(),Collections.emptyList(),false,false,false);
            SuggestionChecks.require(!library.suggestions.complete(old,null,true),"Old process token rejected");
            SuggestionChecks.drain(library); SuggestionStore.View view=library.suggestions.view(old.documentId);
            SuggestionChecks.require(view.status.equals("ready") && view.filingState.equals("dismissed") && library.document(old.documentId).stackIds.isEmpty(),"Choice survives recovery");
            library.initialize(); SuggestionChecks.require(library.suggestions.claim()==null && library.documents("",null).size()==1,"Idempotent recovery");
            mark.accept("Force-stop held suggestion claim recovered in new process; stale token rejected, dismissal retained, no duplicate rows or memberships");
        }
    }
}
