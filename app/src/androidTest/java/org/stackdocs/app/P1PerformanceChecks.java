package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Pinned production PDF/render paths, synthetic context; no universal latency/memory threshold claim. */
final class P1PerformanceChecks {
    static void run(Instrumentation instrumentation,Consumer<String> mark)throws Exception {
        Context context=new ScannerChecks.IsolatedContext(instrumentation.getTargetContext());LibraryRepository library=new LibraryRepository(context);library.initialize();List<String> pages=new ArrayList<>();for(int i=0;i<8;i++)pages.add("SYNTHETIC PERFORMANCE PAGE "+(i+1)+"\nInvoice date: 2026-10-08\nAmount due: USD 24.50\nFine-print quartz context retained.");
        File original=P1CorpusChecks.render(context,"p1-performance",pages);Document document=library.importDocument(Uri.fromFile(original));for(int i=0;i<8;i++)library.editPage(document.id,i,library.pages(document.id).get(i).edited(90,new float[]{.03f,.03f,.97f,.03f,.97f,.97f,.03f,.97f},"original"));
        long start=SystemClock.elapsedRealtime();PdfCopies.Plan plan=PdfCopies.prepare(library,new File(context.getCacheDir(),"plans"),document.id);long pinMs=SystemClock.elapsedRealtime()-start;
        start=SystemClock.elapsedRealtime();Bitmap zoom=PageRenderer.render(new File(plan.directory,plan.pages.get(0).sourceFile),plan.document.mime,plan.pages.get(0),3200);long zoomMs=SystemClock.elapsedRealtime()-start,zoomBytes=zoom.getAllocationByteCount();int w=zoom.getWidth(),h=zoom.getHeight();zoom.recycle();
        ExecutorService worker=Executors.newSingleThreadExecutor(),io=Executors.newSingleThreadExecutor();File output=new File(context.getCacheDir(),"p1-edited.pdf");long[] pings={0,0};int[] samples={0};long maxPss=0;start=SystemClock.elapsedRealtime();
        try {
            Future<?> writing=worker.submit(() -> {try(plan;FileOutputStream out=new FileOutputStream(output)){plan.write(out);out.getFD().sync();}catch(IOException error){throw new RuntimeException(error);}});
            while(!writing.isDone()){long ping=SystemClock.elapsedRealtimeNanos();io.submit(() -> {}).get();pings[0]=Math.max(pings[0],(SystemClock.elapsedRealtimeNanos()-ping)/1000000);ping=SystemClock.elapsedRealtimeNanos();instrumentation.runOnMainSync(() -> {});pings[1]=Math.max(pings[1],(SystemClock.elapsedRealtimeNanos()-ping)/1000000);maxPss=Math.max(maxPss,Debug.getPss());samples[0]++;Thread.sleep(100);}
            writing.get();
        }finally{worker.shutdown();io.shutdown();}
        long exportMs=SystemClock.elapsedRealtime()-start;P1Checks.require(!plan.directory.exists(),"Pinned operation cleanup after export");
        try(PdfCopies.Plan failed=PdfCopies.prepare(library,new File(context.getCacheDir(),"plans"),document.id)) {boolean canceled=false;try{failed.write(new OutputStream(){long count;public void write(int b)throws IOException{if(++count>65536)throw new IOException("Synthetic cancellation");}public void write(byte[] bytes,int off,int n)throws IOException{count+=n;if(count>65536)throw new IOException("Synthetic cancellation");}});}catch(IOException expected){canceled=true;}P1Checks.require(canceled,"Stream interruption reported");}
        mark.accept("P1_PERFORMANCE_JSON "+new JSONObject().put("device",Build.MODEL).put("api",Build.VERSION.SDK_INT).put("edited_pages",8).put("source_bytes",original.length()).put("pinned_snapshot_ms",pinMs).put("zoom_edge",3200).put("zoom_width",w).put("zoom_height",h).put("zoom_bitmap_bytes",zoomBytes).put("zoom_render_ms",zoomMs).put("edited_pdf_bytes",output.length()).put("edited_pdf_ms",exportMs).put("max_serial_io_ping_ms",pings[0]).put("max_ui_ping_ms",pings[1]).put("sampled_peak_pss_kib",maxPss).put("samples",samples[0]).put("caveat","Whole instrumentation process; 100 ms plus sampling/ping overhead, not instantaneous peak or a controlled baseline. Isolated serializer; production pinned renderer/writer. ZIP measured separately in production batch checks."));
    }
}
