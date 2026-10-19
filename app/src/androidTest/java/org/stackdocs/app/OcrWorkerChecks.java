package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Exercises the production asynchronous worker against a fresh synthetic repository. */
final class OcrWorkerChecks {
    static void run(Context context, Consumer<String> mark) throws Exception {
        ExecutorService io=Executors.newSingleThreadExecutor();
        LibraryRepository library=new LibraryRepository(context);
        OcrWorker worker=new OcrWorker(context,io,library,()->{});
        SuggestionWorker suggestions=new SuggestionWorker(library.suggestions,io,()->{});
        try {
            io.submit(library::initialize).get(5,TimeUnit.SECONDS);
            File source=OcrQueueChecks.fixture(context,"worker.pdf");
            Document broken=io.submit(()->library.importDocument(Uri.fromFile(source))).get(5,TimeUnit.SECONDS);
            io.submit(()->library.rename(broken.id,"Worker broken")).get();
            // Damage only this isolated fixture's private copy after successful import validation.
            File copy=io.submit(()->library.source(broken)).get(5,TimeUnit.SECONDS);
            try(FileOutputStream out=new FileOutputStream(copy)){out.write(new byte[]{1,2,3});}
            Document good=io.submit(()->library.importDocument(Uri.fromFile(source))).get(5,TimeUnit.SECONDS);
            io.submit(()->library.rename(good.id,"Worker healthy")).get();
            io.submit(()->{
                library.setSuggestionChanged(suggestions::kick);
                library.setOcrChanged(() -> {worker.kick();suggestions.kick();});
                worker.kick(); suggestions.kick();
            }).get(5,TimeUnit.SECONDS);
            long began=SystemClock.elapsedRealtime();
            io.submit(()->library.documents("",null)).get(1,TimeUnit.SECONDS);
            long ioMs=SystemClock.elapsedRealtime()-began;
            CountDownLatch ui=new CountDownLatch(1); began=SystemClock.elapsedRealtime();
            new Handler(Looper.getMainLooper()).post(ui::countDown);
            OcrQueueChecks.require(ui.await(1,TimeUnit.SECONDS),"UI responsive while worker runs");
            long uiMs=SystemClock.elapsedRealtime()-began;
            await(io,library,good.id);
            awaitSuggestions(io,library,good.id);
            OcrQueueChecks.require(io.submit(()->library.ocrStatus(broken.id,0).failed==2).get(),"Unrenderable fixture pages fail durably");
            OcrQueueChecks.require(io.submit(()->library.documents("quartz",null).size()==1).get(),"Failure leaves other document body searchable");
            OcrQueueChecks.require(io.submit(()->library.documents("worker",null).size()==2).get(),"Failed document title stays searchable");
            io.submit(()->library.retryOcr(good.id,library.pages(good.id).get(1).id)).get();
            await(io,library,good.id);
            OcrQueueChecks.require(io.submit(()->library.ocrStatus(good.id,1).state.equals("succeeded")).get(),"Retry wakes production worker");

            ScanDraftStore drafts=new ScanDraftStore(context);
            Bitmap page=PageRenderer.preview(source,"application/pdf",1,1600);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try {OcrQueueChecks.require(page.compress(Bitmap.CompressFormat.JPEG,95,bytes),"Scan fixture JPEG");} finally {page.recycle();}
            ScanDraftStore.Draft draft=io.submit(()->drafts.append(bytes.toByteArray())).get();
            OcrQueueChecks.require(io.submit(()->library.claimOcr()==null).get(),"Unsaved draft has no OCR work");
            Document scan=io.submit(()->library.saveScan(drafts,draft,"Authored camera draft")).get();
            await(io,library,scan.id);
            OcrQueueChecks.require(io.submit(()->library.documents("quartz",null).size()==2).get(),"Saving scan durably queues real OCR");
            awaitSuggestions(io,library,scan.id);
            OcrQueueChecks.require(io.submit(()->library.suggestions.view(good.id).result.stacks.containsKey("bills")
                    && library.suggestions.view(scan.id).result.stacks.containsKey("bills")).get(),"Both production workers wake for real OCR imports and saved scans");
            mark.accept("Production OCR/suggestion workers: corrupt sources isolated, import/save/retry wakeups, drafts deferred; IO ping "+ioMs+" ms, UI ping "+uiMs+" ms during recognition");
        } finally {
            io.submit(suggestions::shutdown).get(5,TimeUnit.SECONDS);
            io.submit(worker::shutdown).get(5,TimeUnit.SECONDS);
            io.shutdown();
        }
    }
    private static void awaitSuggestions(ExecutorService io,LibraryRepository library,String id) throws Exception {
        long limit=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<limit) {
            String state=io.submit(()->library.suggestions.view(id).status).get(5,TimeUnit.SECONDS);
            if(state.equals("ready")) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Suggestion worker did not follow real OCR");
    }
    private static void await(ExecutorService io,LibraryRepository library,String id) throws Exception {
        long limit=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<limit) {
            if(io.submit(()->library.ocrStatus(id,0).active==0).get(5,TimeUnit.SECONDS))return;
            Thread.sleep(50);
        }
        throw new AssertionError("Worker did not finish synthetic document");
    }
}
