package org.stackdocs.app;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import org.stackdocs.app.types.Limits;

/**
 * Test code only (time of a first index). Scans one real source's tree into a fresh isolated database, with timing wrapped around the
 * reader (list, download) and the extractor (prepare, extract, release). `keep=true` ignores the per-file release, so the extract
 * process is reused (what the code did before "fresh isolated process per file"). Results go to logcat tag FP, no file names or contents.
 */
final class IndexTimingProbe {
    private IndexTimingProbe() {}

    static final class Totals { long list, fetch, extract, prepare, release; int lists, fetches, extracts, pdfExtracts; long maxExtract; }

    static void run(Context app, boolean keep, String kind, Consumer<String> mark) throws Exception { run(app, keep, kind, false, mark); }

    /** `real`: the app's own database and store (its file rows are removed first) instead of a fresh isolated one; the timing wrappers are the same. */
    static void run(Context app, boolean keep, String kind, boolean real, Consumer<String> mark) throws Exception {
        SourceStore.Source orig = null;
        LibraryDatabase realDb = new LibraryDatabase(app);
        try { SourceStore realStore = new SourceStore(app, realDb::getWritableDatabase, new File(app.getFilesDir(), "sources")); for (SourceStore.Source s : realStore.list()) if (s.kind.equals(kind)) orig = s; }
        finally { realDb.close(); }
        if (orig == null) throw new AssertionError("no source of kind " + kind);
        Context iso = real ? app : new ScannerChecks.IsolatedContext(app, "timing-" + System.nanoTime());
        LibraryDatabase helper = real ? null : new LibraryDatabase(iso);
        Totals t = new Totals();
        ExtractClient client = new ExtractClient(iso);
        try {
            SourceStore store;
            SourceStore.Source mine;
            if (real) {
                StackApplication application = (StackApplication) app.getApplicationContext();
                for (int n = 0; n < 200 && application.library == null; n++) Thread.sleep(100);
                for (int n = 0; n < 3000 && (application.sourceIndexer == null || application.sourceIndexer.busy()); n++) Thread.sleep(100);   // the app's own start-up scan first
                store = application.library.sources; mine = orig;
                for (SourceStore.FileRow row : new java.util.ArrayList<>(store.rows(mine.id).values())) store.removeFile(row);
            } else {
                store = new SourceStore(iso, helper::getWritableDatabase, new File(iso.getFilesDir(), "sources"));
                mine = store.add(orig.kind, orig.tree(), "timing", orig.partnerName);
            }
            Extractor timed = new Extractor() {
                @Override public ExtractClient.Outcome extract(File file, String typeId, Limits limits, CancellationSignal signal) {
                    long t0 = SystemClock.elapsedRealtime();
                    try { return client.extract(file, typeId, limits, signal); }
                    finally { long d = SystemClock.elapsedRealtime() - t0; t.extract += d; t.extracts++; t.maxExtract = Math.max(t.maxExtract, d); Log.i("FP", "TIMING extract type=" + typeId + " bytes=" + file.length() + " ms=" + d); }
                }
                @Override public ExtractClient.Outcome extractPages(File file, String typeId, String mode, int[] pages, Limits limits, CancellationSignal signal) {
                    long t0 = SystemClock.elapsedRealtime();
                    try { return client.extractPages(file, typeId, mode, pages, limits, signal); }
                    finally { long d = SystemClock.elapsedRealtime() - t0; t.extract += d; t.pdfExtracts++; Log.i("FP", "TIMING pages mode=" + mode + " n=" + (pages == null ? 0 : pages.length) + " ms=" + d); }
                }
                @Override public boolean prepare() { long t0 = SystemClock.elapsedRealtime(); try { return client.prepare(); } finally { t.prepare += SystemClock.elapsedRealtime() - t0; } }
                @Override public void release() { if (keep) return; long t0 = SystemClock.elapsedRealtime(); client.release(); t.release += SystemClock.elapsedRealtime() - t0; }
            };
            java.util.function.Function<SourceStore.Source, SourceReader> readers = source -> {
                SourceReader saf = new SourceReader.Saf(iso, store, source.tree());
                return new SourceReader() {
                    @Override public boolean granted() { return saf.granted(); }
                    @Override public Listing list(String p) { long t0 = SystemClock.elapsedRealtime(); try { return saf.list(p); } finally { t.list += SystemClock.elapsedRealtime() - t0; t.lists++; } }
                    @Override public void fetch(String d, File to, long m, CancellationSignal s) throws IOException {
                        long t0 = SystemClock.elapsedRealtime();
                        try { saf.fetch(d, to, m, s); } finally { long x = SystemClock.elapsedRealtime() - t0; t.fetch += x; t.fetches++; Log.i("FP", "TIMING fetch bytes=" + to.length() + " ms=" + x); }
                    }
                    @Override public boolean gone(String d) { return saf.gone(d); }
                };
            };
            SourceIndexer.Env env = new SourceIndexer.Env() { @Override public boolean allowed() { return true; } @Override public boolean batteryOk() { return true; } };
            SourceIndexer indexer = new SourceIndexer(iso, store, readers, env, timed);
            long t0 = SystemClock.elapsedRealtime();
            SourceIndexer.Result r = indexer.scan(mine, new SourceIndexer.Control());
            long total = SystemClock.elapsedRealtime() - t0;
            SourceStore.Progress p = store.progress(mine.id);
            mark.accept("TIMING keep=" + keep + " total_ms=" + total + " list_ms=" + t.list + " (" + t.lists + ") fetch_ms=" + t.fetch + " (" + t.fetches + ") extract_ms=" + t.extract
                + " (" + t.extracts + " + " + t.pdfExtracts + " pdf chunks, max " + t.maxExtract + ") prepare_ms=" + t.prepare + " release_ms=" + t.release
                + " files=" + p.files + " read=" + r.read + " state=" + p.state);
            Log.i("FP", "TIMING SUMMARY keep=" + keep + " total_ms=" + total + " list_ms=" + t.list + " fetch_ms=" + t.fetch + " extract_ms=" + t.extract + " prepare_ms=" + t.prepare + " release_ms=" + t.release + " files=" + p.files + " read=" + r.read);
        } finally { client.release(); if (helper != null) helper.close(); }
    }

    /**
     * The first index in the REAL app: the source's file rows are removed, then the app's own indexer (its thread, its listeners, the screen in the foreground)
     * reads the folder again. Total time and the state are logged (tag FP); no names or contents.
     */
    static void runReal(Context target, String kind, String screen, Consumer<String> mark) throws Exception {
        StackApplication app = (StackApplication) target.getApplicationContext();
        for (int n = 0; n < 200 && (app.library == null || app.sourceIndexer == null); n++) Thread.sleep(100);   // the application finishes starting
        for (int quiet = 0, n = 0; quiet < 80 && n < 6000; n++) { quiet = app.sourceIndexer.busy() ? 0 : quiet + 1; Thread.sleep(100); }   // the app's own start-up scans first: 8 quiet seconds
        SourceStore.Source source = null;
        for (SourceStore.Source s : app.library.sources.list()) if (s.kind.equals(kind)) source = s;
        if (source == null) throw new AssertionError("no source of kind " + kind);
        for (SourceStore.FileRow row : new java.util.ArrayList<>(app.library.sources.rows(source.id).values())) app.library.sources.removeFile(row);
        if ("main".equals(screen) || "folders".equals(screen)) {   // the screen is opened in this process (an instrument start ends any earlier one)
            target.startActivity(new android.content.Intent(target, "main".equals(screen) ? MainActivity.class : FoldersActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(4000);
        }
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        long t0 = SystemClock.elapsedRealtime();
        final long[] spent = new long[6];   // allowed, battery, list, fetch, extract, prepare+release (ms)
        final int[] calls = new int[6];
        if ("direct".equals(screen)) {
            Class<?> k = SourceIndexer.class;
            java.lang.reflect.Field fe = k.getDeclaredField("extractor"), fr = k.getDeclaredField("readers"), fv = k.getDeclaredField("env");
            fe.setAccessible(true); fr.setAccessible(true); fv.setAccessible(true);
            final Extractor ex = (Extractor) fe.get(app.sourceIndexer);
            @SuppressWarnings("unchecked") final java.util.function.Function<SourceStore.Source, SourceReader> rd = (java.util.function.Function<SourceStore.Source, SourceReader>) fr.get(app.sourceIndexer);
            final SourceIndexer.Env ev = (SourceIndexer.Env) fv.get(app.sourceIndexer);
            fv.set(app.sourceIndexer, new SourceIndexer.Env() {
                @Override public boolean allowed() { long a = SystemClock.elapsedRealtime(); try { return ev.allowed(); } finally { spent[0] += SystemClock.elapsedRealtime() - a; calls[0]++; } }
                @Override public boolean batteryOk() { long a = SystemClock.elapsedRealtime(); try { return ev.batteryOk(); } finally { spent[1] += SystemClock.elapsedRealtime() - a; calls[1]++; } }
            });
            fr.set(app.sourceIndexer, (java.util.function.Function<SourceStore.Source, SourceReader>) src -> {
                SourceReader saf = rd.apply(src);
                return new SourceReader() {
                    @Override public boolean granted() { return saf.granted(); }
                    @Override public Listing list(String p) { long a = SystemClock.elapsedRealtime(); try { return saf.list(p); } finally { spent[2] += SystemClock.elapsedRealtime() - a; calls[2]++; } }
                    @Override public void fetch(String d, File to, long m, CancellationSignal sg) throws IOException { long a = SystemClock.elapsedRealtime(); try { saf.fetch(d, to, m, sg); } finally { spent[3] += SystemClock.elapsedRealtime() - a; calls[3]++; } }
                    @Override public boolean gone(String d) { return saf.gone(d); }
                };
            });
            fe.set(app.sourceIndexer, new Extractor() {
                @Override public ExtractClient.Outcome extract(File f, String t, Limits l, CancellationSignal sg) { long a = SystemClock.elapsedRealtime(); try { return ex.extract(f, t, l, sg); } finally { long d = SystemClock.elapsedRealtime() - a; spent[4] += d; calls[4]++; Log.i("FP", "TIMING extract type=" + t + " bytes=" + f.length() + " ms=" + d); } }
                @Override public ExtractClient.Outcome extractPages(File f, String t, String m, int[] pg, Limits l, CancellationSignal sg) { long a = SystemClock.elapsedRealtime(); try { return ex.extractPages(f, t, m, pg, l, sg); } finally { long d = SystemClock.elapsedRealtime() - a; spent[4] += d; calls[4]++; Log.i("FP", "TIMING pages mode=" + m + " n=" + (pg == null ? 0 : pg.length) + " ms=" + d); } }
                @Override public boolean prepare() { long a = SystemClock.elapsedRealtime(); try { return ex.prepare(); } finally { spent[5] += SystemClock.elapsedRealtime() - a; } }
                @Override public void release() { long a = SystemClock.elapsedRealtime(); try { ex.release(); } finally { spent[5] += SystemClock.elapsedRealtime() - a; calls[5]++; } }
            });
        }
        if ("direct".equals(screen)) { app.sourceIndexer.scan(source, new SourceIndexer.Control()); done.countDown(); }   // the app's indexer, on this thread
        else app.sourceIndexer.run(source.id, done::countDown);
        if (!done.await(900, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("index did not finish");
        long total = SystemClock.elapsedRealtime() - t0;
        SourceStore.Progress p = app.library.sources.progress(source.id);
        mark.accept("SPLIT allowed=" + spent[0] + "(" + calls[0] + ") battery=" + spent[1] + "(" + calls[1] + ") list=" + spent[2] + "(" + calls[2] + ") fetch=" + spent[3] + "(" + calls[3] + ") extract=" + spent[4] + "(" + calls[4] + ") prepare+release=" + spent[5] + "(" + calls[5] + " releases)");
        mark.accept("TIMING real screen=" + screen + " kind=" + kind + " total_ms=" + total + " files=" + p.files + " pending=" + p.pending + " state=" + p.state);
        Log.i("FP", "TIMING REAL total_ms=" + total + " files=" + p.files + " pending=" + p.pending + " state=" + p.state);
    }
}
