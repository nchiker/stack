package org.stackdocs.app;

import android.app.Application;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class StackApplication extends Application {
    final ExecutorService io = Executors.newSingleThreadExecutor();
    LibraryRepository library;
    AccessPolicy access;
    PhotoMonitor photos;
    MetadataWorker metadata;
    BatchController batches;
    ArchiveController archives;
    ImportController imports;
    OcrWorker ocr;
    SuggestionWorker suggestions;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    void addLibraryListener(Runnable listener) { listeners.add(listener); }
    void removeLibraryListener(Runnable listener) { listeners.remove(listener); }
    void notifyLibraryChanged() { ui.post(() -> { for (Runnable listener : listeners) listener.run(); }); }

    @Override public void onCreate() {
        super.onCreate();
        access=new AccessPolicy(this);
        library = new LibraryRepository(this);
        photos=new PhotoMonitor(this);
        metadata=new MetadataWorker(this);
        batches=new BatchController(this);
        archives = new ArchiveController(this);
        imports = new ImportController(this);
        // This runs before any activity work submitted to the same executor.
        ocr = new OcrWorker(this);
        suggestions = new SuggestionWorker(library.suggestions,io,this::notifyLibraryChanged,access::allowed);
        library.setSuggestionChanged(() -> { suggestions.kick(); metadata.kick(); notifyLibraryChanged(); });
        library.setOcrChanged(() -> { ocr.kick(); suggestions.kick(); metadata.kick(); notifyLibraryChanged(); });
        io.execute(() -> { library.initialize(); batches.recover(); ocr.kick(); suggestions.kick(); metadata.kick(); new android.os.Handler(android.os.Looper.getMainLooper()).post(photos::configure); });
    }
}
