package org.stackdocs.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class ArchivePerformanceChecks {
    static void run(Context context,Consumer<String> mark)throws Exception {
        int count=12;File fixture=new File(context.getCacheDir(),"large-authored.pdf");PdfDocument pdf=new PdfDocument();
        try {
            Paint ink=new Paint();ink.setTextSize(18);ink.setColor(Color.BLACK);
            for(int i=0;i<count;i++){PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(600,800,i+1).create());page.getCanvas().drawColor(Color.WHITE);for(int row=0;row<28;row++)page.getCanvas().drawText("Independent synthetic page "+i+" row "+row,30,40+row*24,ink);pdf.finishPage(page);}
            try(FileOutputStream out=new FileOutputStream(fixture)){pdf.writeTo(out);}
        }finally{pdf.close();}
        LibraryRepository library=new LibraryRepository(context);library.initialize();Document document=library.importDocument(Uri.fromFile(fixture));
        for(int i=0;i<count;i++){PageState page=library.pages(document.id).get(i);library.editPage(document.id,i,page.edited(90,null,"bright"));}
        AtomicLong peak=new AtomicLong();AtomicBoolean running=new AtomicBoolean(true);Thread sample=new Thread(()->{while(running.get()){peak.accumulateAndGet(Debug.getPss(),Math::max);try{Thread.sleep(100);}catch(InterruptedException stop){return;}}});sample.start();
        ExecutorService io=Executors.newSingleThreadExecutor(),transfer=Executors.newSingleThreadExecutor();
        try {
            long start=SystemClock.elapsedRealtime();File exported=new File(context.getCacheDir(),"large-edited.pdf");Future<?> work=io.submit(()->{try(FileOutputStream out=new FileOutputStream(exported)){library.exportPdf(document,out);}catch(IOException error){throw new RuntimeException(error);}});
            CountDownLatch ui=new CountDownLatch(1);long ping=SystemClock.elapsedRealtime();new android.os.Handler(android.os.Looper.getMainLooper()).post(ui::countDown);WorkflowRepositoryChecks.require(ui.await(2,TimeUnit.SECONDS),"UI stays responsive during edited export");long uiMs=SystemClock.elapsedRealtime()-ping;work.get(90,TimeUnit.SECONDS);long exportMs=SystemClock.elapsedRealtime()-start;
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(exported,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer rendered=new PdfRenderer(fd)){WorkflowRepositoryChecks.require(rendered.getPageCount()==count,"Large edited PDF complete");try(PdfRenderer.Page page=rendered.openPage(count-1)){WorkflowRepositoryChecks.require(page.getWidth()>page.getHeight(),"Saved rotation retained on final page");}}
            start=SystemClock.elapsedRealtime();LibraryArchive.Snapshot snapshot=io.submit(()->library.archive().snapshot(new LibraryArchive.Progress())).get();long snapshotMs=SystemClock.elapsedRealtime()-start;
            File backup=new File(context.getCacheDir(),"large.stackbackup");start=SystemClock.elapsedRealtime();Future<?> write=transfer.submit(()->{try(snapshot;FileOutputStream out=new FileOutputStream(backup)){LibraryArchive.write(snapshot,out,new LibraryArchive.Progress());}catch(IOException error){throw new RuntimeException(error);}});
            ping=SystemClock.elapsedRealtime();io.submit(()->library.documents("",null)).get(2,TimeUnit.SECONDS);long ioMs=SystemClock.elapsedRealtime()-ping;write.get(30,TimeUnit.SECONDS);long archiveMs=SystemClock.elapsedRealtime()-start;
            Context target=new ScannerChecks.IsolatedContext(context);LibraryRepository restored=new LibraryRepository(target);restored.initialize();LibraryArchive archive=restored.archive();start=SystemClock.elapsedRealtime();LibraryArchive.Staged staged;try(InputStream input=new FileInputStream(backup)){staged=archive.stage(input,new LibraryArchive.Progress());}archive.plan(staged);archive.commit(staged,new LibraryArchive.Progress());long restoreMs=SystemClock.elapsedRealtime()-start;
            JSONObject result=new JSONObject();result.put("device",android.os.Build.MODEL);result.put("api",android.os.Build.VERSION.SDK_INT);result.put("pages",count);result.put("render_edge",3200);result.put("source_bytes",fixture.length());result.put("edited_pdf_bytes",exported.length());result.put("backup_bytes",backup.length());result.put("edited_export_ms",exportMs);result.put("snapshot_ms",snapshotMs);result.put("archive_write_ms",archiveMs);result.put("restore_ms",restoreMs);result.put("ui_ping_ms",uiMs);result.put("io_ping_during_archive_ms",ioMs);result.put("sampled_peak_pss_kib",peak.get());result.put("sampling_caveat","100 ms plus Debug.getPss overhead, whole instrumentation process; not instantaneous peak or controlled baseline");mark.accept("ARCHIVE_PERFORMANCE_JSON "+result);
        }finally{running.set(false);sample.interrupt();sample.join();io.shutdownNow();transfer.shutdownNow();}
    }
}
