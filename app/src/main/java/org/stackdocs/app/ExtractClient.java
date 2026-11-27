package org.stackdocs.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.stackdocs.app.types.Extracted;
import org.stackdocs.app.types.Limits;
import org.stackdocs.app.types.NativeText;

/**
 * The main process's side of {@link ExtractService}: gives it one file at a time and waits, with a hard wall-clock limit. On a timeout, a dead binder
 * (a native abort, a stack overflow, an out-of-memory kill) or a stop from outside it kills the extract process ({@link Process#killProcess}), so the
 * next file starts in a fresh one. Nothing here reads a file's content, loads the native library or throws for a bad file.
 */
final class ExtractClient implements Extractor {
    /** What one try did. DONE has the answer; the others have none. */
    static final class Outcome {
        enum Kind { DONE, TIMEOUT, DIED, CANCELLED, UNAVAILABLE }   // UNAVAILABLE: the extract process could not be started, so the file was never handed over
        final Kind kind; final Extracted extracted;
        Outcome(Kind kind, Extracted extracted) { this.kind = kind; this.extracted = extracted; }
    }

    /** After the type's own limit the process is killed this much later: a handler that notices the limit itself still gets to answer. */
    static final long GRACE_MS = 3000;
    private static final long CONNECT_MS = 15_000;

    private final Context context;
    private final File scratch;
    private final HandlerThread thread = new HandlerThread("Stack extract replies");
    private final Messenger replies;
    private volatile Messenger service;
    private volatile ServiceConnection connection;
    private volatile IBinder binder;
    private volatile IBinder.DeathRecipient death;
    private volatile int pid;
    /** The extract process id of the last connection (checks compare it before and after a kill). */
    volatile int lastPid;
    /** The binder of the last connection (checks ask whether that process is still alive: {@link IBinder#pingBinder} works across uids, unlike /proc). */
    volatile IBinder lastBinder;
    private volatile Call current;
    private static volatile int counter;

    private static final class Call {
        final CountDownLatch done = new CountDownLatch(1);
        final int number = ++counter;   // sent as arg2 of the request; an answer with another number is not for this call
        volatile boolean died; volatile int result = -1; volatile Bundle data;
        void finish() { done.countDown(); }
    }

    private final Class<? extends ExtractService> serviceClass;
    private static ExtractClient viewer;

    /** The client the viewer draws pages with: its own isolated process, shared by every viewer screen. */
    static synchronized ExtractClient viewer(Context context) {
        if (viewer == null) viewer = new ExtractClient(context, RenderService.class);
        return viewer;
    }

    ExtractClient(Context context) { this(context, ExtractService.class); }

    ExtractClient(Context context, Class<? extends ExtractService> serviceClass) { this(context, serviceClass, serviceClass == RenderService.class ? "source-view" : "source-read"); }

    /** `scratchName`: the cache folder for answer files (a check uses its own: the indexer sweeps "source-read" when it starts a scan). */
    ExtractClient(Context context, Class<? extends ExtractService> serviceClass, String scratchName) {
        this.serviceClass = serviceClass;
        this.context = context.getApplicationContext();
        this.scratch = new File(this.context.getCacheDir(), scratchName);
        thread.start();
        replies = new Messenger(new Handler(thread.getLooper()) {
            @Override public void handleMessage(Message message) {
                Call call = current;
                if (call == null || message.arg2 != call.number) return;   // no call waiting, or the answer of an earlier call
                if (message.what == ExtractService.MSG_PING) call.data = message.getData();
                else if (message.what == ExtractService.MSG_EXTRACT || message.what == ExtractService.MSG_RENDER) call.result = message.arg1;
                call.finish();
            }
        });
    }

    @Override public Outcome extract(File file, String typeId, Limits limits, CancellationSignal signal) { return extractPages(file, typeId, "", null, limits, signal); }

    /**
     * One PDF chunk ({@link ExtractService#MODE_LAYERS}: the text layer of every page; {@link ExtractService#MODE_OCR}: OCR of `pages`). `limits.maxMillis`
     * is this call's own hard limit: the process is killed that much (plus the grace) after the hand-over, whatever else the file holds.
     */
    @Override public synchronized Outcome extractPages(File file, String typeId, String mode, int[] pages, Limits limits, CancellationSignal signal) {
        File answer = new File(scratch, file.getName() + ".answer");
        if (serviceClass == ExtractService.class && ocrNeeded(typeId, mode)) return ocrByPixels(file, typeId, pages, limits, signal);
        ParcelFileDescriptor in = null, out = null;
        try {
            if (!scratch.isDirectory()) scratch.mkdirs();
            if (!connect()) { kill(); return new Outcome(Outcome.Kind.UNAVAILABLE, null); }
            in = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            out = ParcelFileDescriptor.open(answer, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
            Call call = new Call();
            current = call;
            Message message = Message.obtain(null, ExtractService.MSG_EXTRACT);
            message.arg2 = call.number;
            Bundle data = new Bundle();
            data.putParcelable("in", in); data.putParcelable("out", out); data.putString("type", typeId);
            if (!mode.isEmpty()) { data.putString("mode", mode); data.putIntArray("pages", pages == null ? new int[0] : pages); }
            data.putLong("maxBytes", limits.maxBytes); data.putInt("maxPages", limits.maxPages); data.putLong("maxMillis", limits.maxMillis); data.putInt("maxChars", limits.maxChars);
            message.setData(data); message.replyTo = replies;
            try { service.send(message); }
            catch (android.os.RemoteException | RuntimeException gone) { kill(); return new Outcome(Outcome.Kind.DIED, null); }
            close(in); in = null; close(out); out = null;   // the other process has its own copies now
            Outcome waited = await(call, limits.maxMillis, signal);
            if (waited != null) return waited;
            if (!endProcess()) { kill(); return new Outcome(Outcome.Kind.DIED, null); }   // the reader ends before its answer is read: it holds the answer descriptor no more, so nothing grows or changes under the read
            if (call.result != 0) return new Outcome(Outcome.Kind.DONE, Extracted.unreadable("Could not be read."));
            try { return new Outcome(Outcome.Kind.DONE, ExtractService.read(answer)); }
            catch (IOException | RuntimeException unreadable) { kill(); return new Outcome(Outcome.Kind.DIED, null); }
        } catch (IOException | RuntimeException failed) {
            kill();
            return new Outcome(Outcome.Kind.DIED, null);
        } finally {
            current = null;
            close(in); close(out);
            answer.delete();
        }
    }

    // ---- OCR: the page is drawn in the isolated process, the pixels are read in the OCR process -----------------------------------------------

    private ExtractClient ocrClient;

    /** OCR of a PDF's chunk or of a picture: the two processes of {@link #ocrByPixels}. */
    private static boolean ocrNeeded(String typeId, String mode) {
        if ("pdf".equals(typeId)) return ExtractService.MODE_OCR.equals(mode);
        return "jpeg".equals(typeId) || "png".equals(typeId) || "webp".equals(typeId) || "stack-scan".equals(typeId);
    }

    /**
     * OCR without the partner's file ever reaching the OCR process: for each page the isolated process draws raw pixels into a file (size-capped, see
     * {@link org.stackdocs.app.types.PageRender}); the OCR process (not isolated: Tesseract cannot start in an isolated one) reads only that file. One limit
     * for the whole call; both processes are killed by their own timeouts, a stop or a death like any other call.
     */
    private Outcome ocrByPixels(File file, String typeId, int[] pages, Limits limits, CancellationSignal signal) {
        boolean pdf = "pdf".equals(typeId);
        if (!pdf && file.length() > limits.maxBytes) return new Outcome(Outcome.Kind.DONE, Extracted.tooLarge("This picture is too large to read."));
        if (pdf && (pages == null || pages.length == 0 || pages.length > 64)) return new Outcome(Outcome.Kind.DONE, Extracted.unsupported("Not a supported request."));
        int[] which = pdf ? pages : new int[]{0};
        if (ocrClient == null) ocrClient = new ExtractClient(context, OcrService.class, scratch.getName());
        long deadline = SystemClock.elapsedRealtime() + Math.max(1000, limits.maxMillis);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int page : which) {
            long left = deadline - SystemClock.elapsedRealtime();
            if (left <= 0) { kill(); return new Outcome(Outcome.Kind.TIMEOUT, null); }
            File pixels = new File(scratch, file.getName() + ".px" + page);
            try {
                Page drawn = renderInto(file, typeId, page, org.stackdocs.app.types.PageRender.DEFAULT_EDGE, left, signal, pixels, true);
                if (drawn.kind != Outcome.Kind.DONE) return new Outcome(drawn.kind, null);
                if (!drawn.pixels) return new Outcome(Outcome.Kind.DONE, Extracted.unreadable("This file could not be displayed."));
                left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) { kill(); return new Outcome(Outcome.Kind.TIMEOUT, null); }
                try { if (!ExtractService.pixelHeaderOk(pixels)) return new Outcome(Outcome.Kind.DONE, Extracted.unreadable("This file could not be displayed.")); }   // reopened: the length is checked again right before :ocr gets it
                catch (IOException | RuntimeException changed) { kill(); return new Outcome(Outcome.Kind.DIED, null); }
                Outcome read = ocrClient.extractPages(pixels, ExtractService.OCR_PIXELS, "", null, new Limits(limits.maxBytes, 1, left, limits.maxChars), signal);
                if (read.kind != Outcome.Kind.DONE) { if (read.kind == Outcome.Kind.TIMEOUT || read.kind == Outcome.Kind.CANCELLED) kill(); return read; }
                if (read.extracted.status != Extracted.Status.TEXT || read.extracted.pages.size() != 1) return read;
                out.add(read.extracted.pages.get(0));
            } finally { pixels.delete(); }
        }
        return new Outcome(Outcome.Kind.DONE, Extracted.text(out, null, null));
    }

    /** Waits for the call's answer; null = it came (or the process died after answering). Otherwise the outcome that ends the call. The process is killed on a timeout, a death or a stop. */
    private Outcome await(Call call, long maxMillis, CancellationSignal signal) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(1000, maxMillis) + GRACE_MS;
        while (true) {
            try { if (call.done.await(100, TimeUnit.MILLISECONDS)) break; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); kill(); return new Outcome(Outcome.Kind.CANCELLED, null); }
            if (signal != null && signal.isCanceled()) { kill(); return new Outcome(Outcome.Kind.CANCELLED, null); }
            if (SystemClock.elapsedRealtime() >= deadline) { kill(); return new Outcome(Outcome.Kind.TIMEOUT, null); }
        }
        // A process that answered and ended itself can be reported dead a moment before its answer is handled: give the answer that moment.
        if (call.died && call.result < 0) for (int n = 0; n < 6 && call.result < 0; n++) { try { Thread.sleep(50); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; } }
        if (call.died && call.result < 0) { kill(); return new Outcome(Outcome.Kind.DIED, null); }   // died without answering
        return null;
    }

    /**
     * Ends the extract process now and waits (briefly) until it is really gone, so it can no longer write to the answer descriptors it was given.
     * Unlike {@link #kill} it leaves the OCR process alone.
     */
    private boolean endProcess() {
        IBinder gone = binder;
        int victim = pid;
        unbind();
        if (victim > 0 && victim != Process.myPid()) Process.killProcess(victim);
        for (int n = 0; gone != null && n < 40 && gone.pingBinder(); n++) { try { Thread.sleep(5); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; } }
        return gone == null || !gone.pingBinder();   // killProcess cannot end an isolated uid: unbinding does, and only a binder that no longer answers means it is gone
    }

    /** The result of {@link #render}: the page, or why not. `page` and `pages` are the clamped page and the page count; a bitmap is the caller's to recycle. */
    static final class Page {
        final Outcome.Kind kind; final android.graphics.Bitmap bitmap; final int page, pages; final boolean pixels;
        Page(Outcome.Kind kind, android.graphics.Bitmap bitmap, int page, int pages) { this(kind, bitmap, page, pages, bitmap != null); }
        Page(Outcome.Kind kind, android.graphics.Bitmap bitmap, int page, int pages, boolean pixels) { this.kind = kind; this.bitmap = bitmap; this.page = page; this.pages = pages; this.pixels = pixels; }
        boolean drawn() { return kind == Outcome.Kind.DONE && bitmap != null; }
    }

    /**
     * Draws one page of a PDF or a picture (page numbers from 0) in the isolated process, one page at a time, as raw pixels capped to
     * {@link org.stackdocs.app.types.PageRender#MAX_PIXELS}. Blocking, with its own hard limit; the process is killed on a timeout like for a read.
     */
    synchronized Page render(File file, String typeId, int page, int maxPx, long maxMillis, CancellationSignal signal) {
        File answer = new File(scratch, file.getName() + ".page");
        try {
            Page done = renderInto(file, typeId, page, maxPx, maxMillis, signal, answer, false);
            if (done.kind != Outcome.Kind.DONE || !done.pixels) return new Page(done.kind, null, 0, 0);
            try { ExtractService.Rendered r = ExtractService.readRendered(answer); return new Page(Outcome.Kind.DONE, r.bitmap, r.page, r.pages); }
            catch (IOException | RuntimeException unreadable) { kill(); return new Page(Outcome.Kind.DIED, null, 0, 0); }
        } finally { answer.delete(); }
    }

    /** As {@link #render}, but the pixels stay in `answer` (the caller deletes it); `pixels` says whether the file holds a page. The header is checked here. */
    private synchronized Page renderInto(File file, String typeId, int page, int maxPx, long maxMillis, CancellationSignal signal, File answer, boolean endFirst) {
        ParcelFileDescriptor in = null, out = null;
        try {
            if (!scratch.isDirectory()) scratch.mkdirs();
            if (!connect()) { kill(); return new Page(Outcome.Kind.UNAVAILABLE, null, 0, 0); }
            in = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            out = ParcelFileDescriptor.open(answer, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
            Call call = new Call();
            current = call;
            Message message = Message.obtain(null, ExtractService.MSG_RENDER);
            message.arg2 = call.number;
            Bundle data = new Bundle();
            data.putParcelable("in", in); data.putParcelable("out", out); data.putString("type", typeId);
            data.putInt("page", page); data.putInt("maxPx", maxPx); data.putLong("maxMillis", maxMillis);
            message.setData(data); message.replyTo = replies;
            try { service.send(message); }
            catch (android.os.RemoteException | RuntimeException gone) { kill(); return new Page(Outcome.Kind.DIED, null, 0, 0); }
            close(in); in = null; close(out); out = null;
            Outcome waited = await(call, maxMillis, signal);
            if (waited != null) return new Page(waited.kind, null, 0, 0);
            if (endFirst && !endProcess()) { kill(); return new Page(Outcome.Kind.DIED, null, 0, 0); }   // pixels another process will read: the drawing process is gone first
            if (call.result != 0) return new Page(Outcome.Kind.DONE, null, 0, 0, false);
            try { return new Page(Outcome.Kind.DONE, null, 0, 0, ExtractService.pixelHeaderOk(answer)); }
            catch (IOException | RuntimeException unreadable) { kill(); return new Page(Outcome.Kind.DIED, null, 0, 0); }
        } catch (IOException | RuntimeException failed) {
            kill();
            return new Page(Outcome.Kind.DIED, null, 0, 0);
        } finally {
            current = null;
            close(in); close(out);
        }
    }

    @Override public synchronized boolean prepare() {
        if (serviceClass != ExtractService.class) return true;
        boolean started = connect();
        endProcess();   // only the versions were wanted: the first file gets a process of its own
        return started;
    }

    @Override public synchronized void release() { unbind(); if (ocrClient != null) ocrClient.release(); }

    /** Kills the extract process now (a check or the app lock); the next file starts a new one. */
    synchronized void kill() {
        if (ocrClient != null) ocrClient.kill();
        int victim = pid;
        unbind();
        if (victim > 0 && victim != Process.myPid()) Process.killProcess(victim);
    }

    private void unbind() {
        ServiceConnection c; IBinder b; IBinder.DeathRecipient d;
        synchronized (stateLock) { c = connection; b = binder; d = death; connection = null; service = null; binder = null; death = null; pid = 0; }
        if (b != null && d != null) try { b.unlinkToDeath(d, 0); } catch (RuntimeException ignored) { /* already gone */ }
        if (c != null) try { context.unbindService(c); } catch (RuntimeException ignored) { /* not bound */ }
    }

    private boolean connect() {
        IBinder held = binder;
        if (service != null && pid > 0 && held != null && held.pingBinder()) return true;   // a process that ended itself is not "connected" even before its death notice arrives
        unbind();
        final CountDownLatch bound = new CountDownLatch(1);
        ServiceConnection c = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder b) {
                service = new Messenger(b); binder = b; lastBinder = b; bound.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { lost(); bound.countDown(); }
            @Override public void onBindingDied(ComponentName name) { lost(); bound.countDown(); }
            @Override public void onNullBinding(ComponentName name) { bound.countDown(); }
        };
        if (!context.bindService(new Intent(context, serviceClass), c, Context.BIND_AUTO_CREATE)) return false;
        connection = c;
        try { if (!bound.await(CONNECT_MS, TimeUnit.MILLISECONDS)) return false; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        if (service == null || binder == null) return false;
        death = this::lost;
        try { binder.linkToDeath(death, 0); } catch (android.os.RemoteException gone) { return false; }
        Call ping = new Call();
        current = ping;
        try {
            Message message = Message.obtain(null, ExtractService.MSG_PING);
            message.arg2 = ping.number;
            message.replyTo = replies;
            service.send(message);
            if (!ping.done.await(CONNECT_MS, TimeUnit.MILLISECONDS) || ping.died || ping.data == null) return false;
        } catch (android.os.RemoteException | RuntimeException | InterruptedException failed) { return false; }
        finally { current = null; }
        pid = ping.data.getInt("pid"); lastPid = pid;
        NativeText.remember(context, ping.data.getInt("pdf"), ping.data.getInt("amipro"));
        return pid > 0;
    }

    /**
     * The other process died (binder death or the system dropped the connection): whoever waits for an answer stops waiting, and the connection is given up
     * for good. Kept bound, Android would restart the service for it by itself and hand this client a process it never pinged.
     */
    private void lost() {
        Call call = current;
        if (call != null) { call.died = true; call.finish(); }
        ServiceConnection c; IBinder b; IBinder.DeathRecipient d;
        synchronized (stateLock) { c = connection; b = binder; d = death; connection = null; binder = null; death = null; service = null; pid = 0; }
        if (b != null && d != null) try { b.unlinkToDeath(d, 0); } catch (RuntimeException ignored) { /* already gone */ }
        if (c != null) try { context.unbindService(c); } catch (RuntimeException ignored) { /* not bound */ }
    }

    private final Object stateLock = new Object();

    private static void close(ParcelFileDescriptor fd) { if (fd != null) try { fd.close(); } catch (IOException ignored) { /* closed */ } }
}
