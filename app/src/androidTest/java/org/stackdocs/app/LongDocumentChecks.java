package org.stackdocs.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Debug;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A longer authored PDF through both actual workers, decisions, later-page search and export. */
final class LongDocumentChecks {
    static void run(Context context,Consumer<String> mark) throws Exception {
        File fixture=new File(context.getCacheDir(),"long-workflow.pdf");int characters=0;
        PdfDocument pdf=new PdfDocument();
        try {
            Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);ink.setColor(Color.BLACK);ink.setTextSize(16);
            for(int number=1;number<=6;number++) {
                PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(600,800,number).create());page.getCanvas().drawColor(Color.WHITE);
                String[] headings=number==1 ? new String[]{"Maple Veterinary Clinic","42 Cedar Street","Medical invoice","Patient name Synthetic","Amount due 25","Due date May 2"}
                        : new String[]{"Examination continuation", "Reference section "+number};
                int y=40;for(String line:headings){page.getCanvas().drawText(line,40,y,ink);characters+=line.length();y+=22;}
                for(int row=1;row<=24;row++) {
                    String line="Printed examination record section "+number+" row "+row+".";
                    page.getCanvas().drawText(line,40,y,ink);characters+=line.length();y+=22;
                }
                if(number==6){String line="Search target violetquartz appears on this page.";page.getCanvas().drawText(line,40,y,ink);characters+=line.length();}
                pdf.finishPage(page);
            }
            try(FileOutputStream out=new FileOutputStream(fixture)){pdf.writeTo(out);}
        }finally{pdf.close();}
        byte[] original=ScannerChecks.bytes(fixture);ExecutorService io=Executors.newSingleThreadExecutor();
        LibraryRepository library=new LibraryRepository(context);OcrWorker ocr=new OcrWorker(context,io,library,()->{});
        SuggestionWorker suggestions=new SuggestionWorker(library.suggestions,io,()->{});
        try {
            io.submit(library::initialize).get();
            Document document=io.submit(() -> library.importDocument(Uri.fromFile(fixture))).get();
            io.submit(() -> {library.setMemberships(document.id,Arrays.asList("starred"));library.setSuggestionChanged(suggestions::kick);library.setOcrChanged(() -> {ocr.kick();suggestions.kick();});ocr.kick();suggestions.kick();}).get();
            long began=SystemClock.elapsedRealtime();
            long ping=SystemClock.elapsedRealtime();io.submit(() -> library.documents("",null)).get(2,TimeUnit.SECONDS);long ioMs=SystemClock.elapsedRealtime()-ping;
            CountDownLatch ui=new CountDownLatch(1);ping=SystemClock.elapsedRealtime();new android.os.Handler(android.os.Looper.getMainLooper()).post(ui::countDown);
            WorkflowRepositoryChecks.require(ui.await(2,TimeUnit.SECONDS),"UI responsive during longer native job");long uiMs=SystemClock.elapsedRealtime()-ping;
            await(io,library,document.id);long workerMs=SystemClock.elapsedRealtime()-began;
            SuggestionStore.View proposal=io.submit(() -> library.suggestions.view(document.id)).get();
            WorkflowRepositoryChecks.require(proposal.result.title.equals("Maple Veterinary Clinic · Bill") && proposal.result.stacks.containsKey("medical") && proposal.result.stacks.containsKey("bills"),"Long real OCR finds issuer and appropriate categories");
            io.submit(() -> {WorkflowRepositoryChecks.require(library.suggestions.decide(proposal,"title","accepted",null,null),"Long name acceptance");suggestions.kick();}).get();await(io,library,document.id);
            io.submit(() -> {WorkflowRepositoryChecks.require(library.suggestions.decide(library.suggestions.view(document.id),"filing","accepted",null,null),"Long filing acceptance");suggestions.kick();}).get();await(io,library,document.id);
            Document hit=io.submit(() -> library.documents("violetquartz","bills").get(0)).get();
            WorkflowRepositoryChecks.require(io.submit(() -> library.pagePosition(document.id,hit.matchPageId)==5 && hit.snippet.contains("Page 6") && library.document(document.id).stackIds.contains("starred")).get(),"Stable page-6 search and unrelated manual membership");
            File exported=new File(context.getCacheDir(),"long-export.pdf");
            io.submit(() -> {try(FileOutputStream out=new FileOutputStream(exported)){library.exportPdf(library.document(document.id),out);}return true;}).get();
            WorkflowRepositoryChecks.require(Arrays.equals(original,ScannerChecks.bytes(exported)),"Long OCR/accepted names and filing leave PDF bytes identical");
            LibraryRepository reopened=new LibraryRepository(context);reopened.initialize();
            WorkflowRepositoryChecks.require(reopened.document(document.id).title.equals("Maple Veterinary Clinic · Bill") && reopened.documents("violetquartz","medical").get(0).matchPageId.equals(hit.matchPageId),"Long workflow durable on reopen");
            JSONObject report=new JSONObject();report.put("device",android.os.Build.MODEL);report.put("api",android.os.Build.VERSION.SDK_INT);report.put("rules",SuggestionRules.VERSION);
            report.put("pages",6);report.put("authored_characters",characters);report.put("source_bytes",original.length);report.put("both_workers_ms",workerMs);report.put("io_ping_ms",ioMs);report.put("ui_ping_ms",uiMs);report.put("post_workflow_pss_kib",Debug.getPss());
            mark.accept("LONG_WORKFLOW_JSON "+report);
        }finally{
            io.submit(suggestions::shutdown).get();io.submit(ocr::shutdown).get();io.shutdown();io.awaitTermination(5,TimeUnit.SECONDS);
        }
    }
    private static void await(ExecutorService io,LibraryRepository library,String id) throws Exception {
        long until=SystemClock.elapsedRealtime()+60000;
        while(SystemClock.elapsedRealtime()<until) {
            String state=io.submit(() -> library.suggestions.view(id).status).get(5,TimeUnit.SECONDS);
            if(state.equals("ready"))return;
            WorkflowRepositoryChecks.require(!state.equals("empty") && !state.equals("failed"),"Long suggestion result available");Thread.sleep(50);
        }
        throw new AssertionError("Long workflow timed out");
    }
}
