package org.stackdocs.app;

import android.graphics.Bitmap;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Claim/commit on io; rendering and the non-thread-safe engine on one separate worker. */
final class OcrWorker {
    private final android.content.Context context;
    private final ExecutorService io;
    private final LibraryRepository library;
    private final Runnable changed;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean active; // Accessed only on app.io.
    private OcrEngine engine; // Accessed only on worker.
    OcrWorker(StackApplication app) { this(app,app.io,app.library,app::notifyLibraryChanged); }
    OcrWorker(android.content.Context context, ExecutorService io, LibraryRepository library, Runnable changed) {
        this.context=context; this.io=io; this.library=library; this.changed=changed;
    }
    void shutdown() { worker.execute(this::closeEngine); worker.shutdown(); }

    void kick() {
        if (active || (context instanceof StackApplication && !((StackApplication)context).access.allowed())) return;
        OcrJob job;
        try { job=library.claimOcr(); }
        catch (RuntimeException unavailable) { return; } // Library UI reports unavailable storage.
        if (job == null) {
            worker.execute(this::closeEngine);
            return;
        }
        active=true;
        changed.run();
        worker.execute(() -> recognize(job));
    }

    private void recognize(OcrJob job) {
        Bitmap bitmap=null;
        String text=null, error=null;
        try {
            if (job.source == null || !job.source.isFile()) throw new IOException("Missing original");
            bitmap=PageRenderer.render(job.source,job.mime,job.page,2400);
            if (engine == null) engine=new OcrEngine(context);
            text=engine.recognize(bitmap);
        } catch (IOException | RuntimeException failure) {
            error="recognition_failed";
            closeEngine();
        } catch (OutOfMemoryError | LinkageError failure) {
            error="engine_unavailable";
            closeEngine();
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
        String recognized=text, failure=error;
        io.execute(() -> {
            try {
                library.completeOcr(job,recognized,failure);
                changed.run();
            } finally { active=false; }
            kick();
            library.suggestionsAvailable();
        });
    }

    private void closeEngine() {
        if (engine != null) { engine.close(); engine=null; }
    }
}
