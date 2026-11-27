package org.stackdocs.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.stackdocs.app.types.Extracted;
import org.stackdocs.app.types.FileType;
import org.stackdocs.app.types.FileTypes;
import org.stackdocs.app.types.Limits;
import org.stackdocs.app.types.NativeText;
import org.stackdocs.app.types.PageRender;
import org.stackdocs.app.types.PdfPages;
import org.stackdocs.app.types.Source;

/**
 * Reads the text of ONE folder file at a time in its own process (`:extract`, docs/FOLDER_SHARING_DESIGN.md section 7). Files from a partner are
 * untrusted: a PDF that crashes the native reader, overflows a stack, runs out of memory or never ends must not take Stack down or hang it.
 * {@link ExtractClient} (main process) hands over an open file descriptor of a private copy, the type id and its limits, and a second descriptor
 * to write the answer to; it kills this process on a timeout or a dead binder and goes on with the next file.
 *
 * The service is an ISOLATED process (`android:isolatedProcess`): another uid with no access to Stack's files, database, Keystore or the network. It
 * touches only the descriptors it is given: the file, the answer file, and (for OCR) the bundled model. {@link StackApplication} does nothing in it.
 * An Error in the reader (out of memory, stack overflow), or a reader that gave up while a thread of it may still run ({@link Extracted#endsProcess}),
 * ends this process on purpose after the answer is written: its heap or threads can no longer be trusted, and the client counts the file's attempt.
 * The same service draws one page for the viewer (MSG_RENDER), so a partner's PDF or picture is never decoded in Stack's own process.
 * Descriptors are closed as soon as the answer is written.
 */
public class ExtractService extends Service {
    static final int MSG_PING = 1, MSG_EXTRACT = 2, MSG_RENDER = 3;
    /** PDF chunks: the text layer of every page, and OCR of the listed pages. No mode = the whole file in one call. */
    static final String MODE_LAYERS = "layers", MODE_OCR = "ocr";
    static final int MAGIC = 0x53545832, RENDER_MAGIC = 0x53545833;
    /** The answer file is never larger than this (the text caps of the types are far below it). */
    static final long MAX_ANSWER = 16L * 1024 * 1024;
    /** Checks builds only: a file whose type id starts with this runs a scripted failure instead of a reader. */
    static final String TEST_PREFIX = "checks-";
    /** The type id of a request to OCR a drawn page (raw pixels in the {@link #RENDER_MAGIC} format): answered by the OCR process only, never by an isolated one. */
    static final String OCR_PIXELS = "ocr-pixels";

    private HandlerThread thread;
    private Messenger messenger;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "Stack extract"));
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "Stack extract limit"); t.setDaemon(true); return t; });

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("Stack extract messages");
        thread.start();
        messenger = new Messenger(new Handler(thread.getLooper()) {
            @Override public void handleMessage(Message message) {
                if (message.what == MSG_PING) {
                    if (message.replyTo == null) return;
                    int[] versions = isolated() ? versions() : new int[]{0, 0};   // the OCR process does not load the readers
                    android.os.Bundle answer = new android.os.Bundle();
                    answer.putInt("pid", Process.myPid()); answer.putInt("pdf", versions[0]); answer.putInt("amipro", versions[1]);
                    Message m = Message.obtain(null, MSG_PING); m.arg2 = message.arg2; m.setData(answer);   // the call number comes back, so the client can tell an old answer
                    try { message.replyTo.send(m); } catch (android.os.RemoteException gone) { /* the client is gone */ }
                }
                else if (message.what == MSG_EXTRACT || message.what == MSG_RENDER) {
                    final android.os.Bundle data = message.getData();
                    data.setClassLoader(ParcelFileDescriptor.class.getClassLoader());
                    if (!accepts(message.what, data)) {   // this process does one kind of work only
                        close(data.<ParcelFileDescriptor>getParcelable("in")); close(data.<ParcelFileDescriptor>getParcelable("out"));
                        reply(message, message.what, 1, message.arg2);
                        return;
                    }
                    final Message request = Message.obtain(message);
                    worker.execute(() -> { if (request.what == MSG_RENDER) handleRender(request, data); else handle(request, data); });
                }
            }
        });
    }

    /** Whether this service does the request. The reader processes do all of them; {@link OcrService} overrides this to refuse all but one. */
    boolean accepts(int what, android.os.Bundle data) { return true; }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    /** The client let go of the service (it finished, or killed the call): nothing in this process may outlive that, a hung reader thread included. */
    @Override public void onDestroy() {
        thread.quit(); worker.shutdownNow(); super.onDestroy();
        Process.killProcess(Process.myPid());
    }

    private static void reply(Message request, int what, int arg1, int arg2) {
        if (request.replyTo == null) return;
        try { Message m = Message.obtain(null, what, arg1, arg2); request.replyTo.send(m); } catch (android.os.RemoteException gone) { /* the client is gone */ }
    }

    private void handle(Message request, android.os.Bundle data) {
        ParcelFileDescriptor in = data.getParcelable("in"), out = data.getParcelable("out");
        int ok = 1;
        boolean endProcess = false;
        try {
            if (in == null || out == null) return;
            String typeId = data.getString("type", "");
            Limits limits = new Limits(data.getLong("maxBytes"), data.getInt("maxPages"), data.getLong("maxMillis"), data.getInt("maxChars"));
            String mode = data.getString("mode", "");
            Source source = Source.ofDescriptor(getApplicationContext(), in);
            Extracted answer = mode.isEmpty() ? run(typeId, source, limits) : runChunk(typeId, mode, data.getIntArray("pages"), source, limits);
            ParcelFileDescriptor spare = "checks-flood".equals(typeId) && getResources().getBoolean(R.bool.stack_checks) ? out.dup() : null;
            write(out, answer);
            if (spare != null) flood(spare);
            endProcess = answer.endsProcess;
            ok = 0;
        } catch (IOException | RuntimeException failed) {
            ok = 1;
        } catch (Error broken) {
            Process.killProcess(Process.myPid());   // out of memory, stack overflow, native trouble: nothing in this process can be trusted
        } finally {
            close(in); close(out);
            reply(request, MSG_EXTRACT, ok, request.arg2);
            request.recycle();
            if (endProcess) Process.killProcess(Process.myPid());   // the answer is written and sent: a reader thread still running dies with the process
        }
    }

    /** One page for the viewer: raw pixels into the answer descriptor (see {@link #writeRendered}), under the size caps of {@link PageRender}. */
    private void handleRender(Message request, android.os.Bundle data) {
        ParcelFileDescriptor in = data.getParcelable("in"), out = data.getParcelable("out");
        int ok = 1;
        try {
            if (in == null || out == null) return;
            FileType type = FileTypes.byId(data.getString("type", ""));
            if (!PageRender.drawable(type)) { writeRendered(out, null, 0, 0); ok = 0; return; }
            final CancellationSignal signal = new CancellationSignal();
            java.util.concurrent.ScheduledFuture<?> limit = watchdog.schedule(signal::cancel, Math.max(1000, data.getLong("maxMillis", 30_000)), TimeUnit.MILLISECONDS);
            try {
                PageRender.Result page = PageRender.render(Source.ofDescriptor(getApplicationContext(), in), type, data.getInt("page", 0), data.getInt("maxPx", PageRender.DEFAULT_EDGE));
                writeRendered(out, page.bitmap, page.page, page.pages);
                page.bitmap.recycle();
                ok = 0;
            } finally { limit.cancel(false); }
        } catch (IOException | RuntimeException failed) {
            ok = 1;
        } catch (Error broken) {
            Process.killProcess(Process.myPid());
        } finally {
            close(in); close(out);
            reply(request, MSG_RENDER, ok, request.arg2);
            request.recycle();
        }
    }

    private Extracted run(String typeId, Source source, Limits limits) throws IOException {
        if (typeId.startsWith(TEST_PREFIX)) {
            if (!getResources().getBoolean(R.bool.stack_checks)) return Extracted.unsupported("Not a supported type of file.");
            Extracted scripted = scripted(typeId, source);
            if (scripted != null) return scripted;
        }
        if (OCR_PIXELS.equals(typeId)) return ocrPixels(source);
        FileType type = FileTypes.byId(typeId);
        if (type == null) return Extracted.unsupported("Not a supported type of file.");
        if (isolated() && PageRender.drawable(type)) return Extracted.unsupported("Pictures and PDFs are read page by page (ExtractClient).");   // OCR is not in this process
        final CancellationSignal signal = new CancellationSignal();
        java.util.concurrent.ScheduledFuture<?> limit = watchdog.schedule(signal::cancel, Math.max(1000, limits.maxMillis), TimeUnit.MILLISECONDS);
        try {
            return type.extract(source, limits, signal);
        } finally { limit.cancel(false); }
    }

    /** One chunk of a PDF, under the chunk's own limit. The same protections as {@link #run}: the descriptor's file only, a watchdog, Errors end the process. */
    private Extracted runChunk(String typeId, String mode, int[] pages, Source source, Limits limits) throws IOException {
        if (typeId.startsWith(TEST_PREFIX)) {
            if (!getResources().getBoolean(R.bool.stack_checks)) return Extracted.unsupported("Not a supported type of file.");
            Extracted scripted = scripted(typeId, source);
            if (scripted != null) return scripted;
        }
        if (!"pdf".equals(typeId)) return Extracted.unsupported("Not a supported type of file.");
        if (isolated() && !MODE_LAYERS.equals(mode)) return Extracted.unsupported("OCR does not run in this process.");
        final CancellationSignal signal = new CancellationSignal();
        java.util.concurrent.ScheduledFuture<?> limit = watchdog.schedule(signal::cancel, Math.max(1000, limits.maxMillis), TimeUnit.MILLISECONDS);
        try {
            if (MODE_LAYERS.equals(mode)) return PdfPages.layers(source, limits);
            if (!MODE_OCR.equals(mode) || pages == null || pages.length == 0 || pages.length > 64) return Extracted.unsupported("Not a supported request.");
            return PdfPages.ocr(source, pages, signal);
        } finally { limit.cancel(false); }
    }

    /** True in the isolated processes (`:extract`, `:render`): another uid, no files, no network. */
    private boolean isolated() { int uid = Process.myUid() % 100000; return uid >= 99000 && uid <= 99999; }

    /**
     * OCR of one drawn page, in the OCR process only. This is the one reader that is NOT isolated: Tesseract starts only from a directory that holds
     * tessdata/eng.traineddata, and an isolated uid has no directory it can write and cannot be handed one (a directory descriptor to it is refused by
     * SELinux). What reaches it is only pixels, in a fixed format with size caps, drawn by the isolated process; never the partner's file.
     */
    private Extracted ocrPixels(Source source) throws IOException {
        if (isolated()) return Extracted.unsupported("OCR does not run in this process.");
        android.graphics.Bitmap bitmap;
        try (java.io.InputStream in = source.open()) { bitmap = readRendered(in, source.length()).bitmap; }
        if (bitmap == null) return Extracted.unreadable("This file could not be displayed.");
        try (OcrEngine engine = new OcrEngine(getApplicationContext())) {
            List<String> out = new ArrayList<>();
            out.add(engine.recognize(bitmap));
            return Extracted.text(out, null, null);
        } finally { bitmap.recycle(); }
    }

    /**
     * Checks builds only. A hang, a crash and an out-of-memory, the three ways a real reader can fail; a reader that timed out natively; and a look at what
     * this process can reach (`checks-sandbox`: one line per question, see SandboxChecks in the tests).
     */
    private Extracted scripted(String typeId, Source source) {
        switch (typeId) {
            case "checks-hang": try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ignored) { /* killed */ } return null;
            case "checks-crash": Process.killProcess(Process.myPid()); return null;
            case "checks-oom": { List<long[]> hold = new ArrayList<>(); while (true) hold.add(new long[16 * 1024 * 1024]); }
            case "checks-flood": return Extracted.text(java.util.Collections.singletonList("flooded"), null, null);   // the answer is fine; flood() then keeps writing
            case "checks-timeout": return Extracted.timedOut("Scripted: the reader gave up.");
            case "checks-sandbox": return Extracted.text(sandbox(source), null, null);
            default: return null;
        }
    }

    /** Checks builds only: a compromised reader that goes on writing to its answer descriptor after it answered (until this process is ended). */
    private static void flood(ParcelFileDescriptor copy) {
        Thread writer = new Thread(() -> {
            byte[] block = new byte[1 << 16];
            try (FileOutputStream stream = new FileOutputStream(copy.getFileDescriptor())) { while (true) stream.write(block); }
            catch (IOException ignored) { /* the file is gone or the disk is full */ }
        }, "Stack extract flood");
        writer.setDaemon(true); writer.start();
    }

    private List<String> sandbox(Source source) {
        List<String> facts = new ArrayList<>();
        int uid = Process.myUid();
        facts.add("isolated=" + isolated() + " uid=" + uid);
        try { android.database.sqlite.SQLiteDatabase.openDatabase(getDatabasePath("library.db").getPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).close(); facts.add("db=opened"); }
        catch (RuntimeException denied) { facts.add("db=denied " + denied.getClass().getSimpleName()); }
        try { File[] list = getFilesDir().listFiles(); facts.add("files=" + (list == null ? "denied" : "listed " + list.length)); }
        catch (RuntimeException denied) { facts.add("files=denied " + denied.getClass().getSimpleName()); }
        try { File probe = new File(getFilesDir(), "isolation-probe"); facts.add("write=" + (probe.createNewFile() ? "created" : "no")); probe.delete(); }
        catch (IOException | RuntimeException denied) { facts.add("write=denied " + denied.getClass().getSimpleName()); }
        try { java.net.Socket socket = new java.net.Socket(); socket.connect(new java.net.InetSocketAddress("127.0.0.1", 9), 500); socket.close(); facts.add("net=connected"); }
        catch (IOException | RuntimeException denied) { facts.add("net=denied " + denied.getClass().getSimpleName()); }
        try {
            java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            facts.add("keystore=" + (store.containsAlias(getPackageName() + ".isolation.key") ? "sees the key" : "no key"));
        } catch (java.security.GeneralSecurityException | IOException | RuntimeException denied) { facts.add("keystore=denied " + denied.getClass().getSimpleName()); }
        try { facts.add("cache=" + (getCacheDir() != null && getCacheDir().canWrite() ? "writable" : "no")); } catch (RuntimeException denied) { facts.add("cache=denied"); }
        return facts;
    }

    // ---- the answer file (also read by ExtractClient) ----------------------------------------------------------------------------

    private static void write(ParcelFileDescriptor target, Extracted answer) throws IOException {
        try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(target.getFileDescriptor())))) {
            stream.writeInt(MAGIC);
            stream.writeByte(answer.status.ordinal());
            string(stream, answer.reason); string(stream, answer.title);
            stream.writeInt(answer.pages.size());
            for (String page : answer.pages) string(stream, page);
            stream.flush();
        }
    }

    private static void string(DataOutputStream stream, String value) throws IOException {
        if (value == null) { stream.writeInt(-1); return; }
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        stream.writeInt(bytes.length); stream.write(bytes);
    }

    /** Reads an answer written by {@link #write}; IOException when it is not one. */
    static Extracted read(File file) throws IOException {
        // The size is enforced while reading, not only before: the file is read at most MAX_ANSWER + 1 bytes into memory, then parsed from there.
        byte[] all;
        try (InputStream in = new FileInputStream(file)) { all = in.readNBytes((int) MAX_ANSWER + 1); }
        if (all.length > MAX_ANSWER) throw new IOException("answer too large");
        try (DataInputStream stream = new DataInputStream(new java.io.ByteArrayInputStream(all))) {
            if (stream.readInt() != MAGIC) throw new IOException("not an answer");
            int ordinal = stream.readByte();
            Extracted.Status[] all2 = Extracted.Status.values();
            if (ordinal < 0 || ordinal >= all2.length) throw new IOException("bad status");
            String reason = readString(stream, all.length), title = readString(stream, all.length);
            int count = stream.readInt();
            if (count < 0 || count > 100_000) throw new IOException("bad page count");
            List<String> pages = new ArrayList<>();
            for (int i = 0; i < count; i++) pages.add(readString(stream, all.length));
            switch (all2[ordinal]) {
                case TEXT: return Extracted.text(pages, title, reason);
                case PROTECTED: return Extracted.protectedFile();
                case UNSUPPORTED: return Extracted.unsupported(reason);
                case TOO_LARGE: return Extracted.tooLarge(reason);
                default: return Extracted.unreadable(reason);
            }
        }
    }

    private static String readString(DataInputStream stream, long limit) throws IOException {
        int length = stream.readInt();
        if (length < 0) return null;
        if (length > limit) throw new IOException("bad length");
        byte[] bytes = new byte[length];
        stream.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- a drawn page (also read by ExtractClient) ----------------------------------------------------------------------------------------

    /** header: magic, status (0 failed, 1 drawn), page, pages, width, height; then width x height ARGB ints. A null bitmap is a "cannot be drawn" answer. */
    private static void writeRendered(ParcelFileDescriptor target, android.graphics.Bitmap bitmap, int page, int pages) throws IOException {
        try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(target.getFileDescriptor()), 1 << 16))) {
            stream.writeInt(RENDER_MAGIC);
            if (bitmap == null) { stream.writeByte(0); stream.writeInt(0); stream.writeInt(0); stream.writeInt(0); stream.writeInt(0); return; }
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            if (width > PageRender.MAX_SIDE || height > PageRender.MAX_SIDE || (long) width * height > PageRender.MAX_PIXELS) throw new IOException("page too large");
            stream.writeByte(1); stream.writeInt(page); stream.writeInt(pages); stream.writeInt(width); stream.writeInt(height);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) stream.writeInt(row[x]);
            }
            stream.flush();
        }
    }

    /** True when `file` is a page answer with a drawn page inside the caps (the header only: the pixels are not read). IOException when it is not an answer at all. */
    static boolean pixelHeaderOk(File file) throws IOException {
        try (DataInputStream stream = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(file), 64))) {
            if (stream.readInt() != RENDER_MAGIC) throw new IOException("not a page");
            int status = stream.readByte(); stream.readInt(); int pages = stream.readInt(), width = stream.readInt(), height = stream.readInt();
            if (status == 0) return false;
            if (status != 1 || width < 1 || height < 1 || width > PageRender.MAX_SIDE || height > PageRender.MAX_SIDE || (long) width * height > PageRender.MAX_PIXELS || pages < 1) throw new IOException("bad page header");
            if (file.length() != 21L + 4L * width * height) throw new IOException("bad page size");
            return true;
        }
    }

    /** What {@link #readRendered} found: `bitmap` is null for "cannot be drawn". */
    static final class Rendered { final android.graphics.Bitmap bitmap; final int page, pages; Rendered(android.graphics.Bitmap bitmap, int page, int pages) { this.bitmap = bitmap; this.page = page; this.pages = pages; } }

    /** Reads an answer written by {@link #writeRendered}; every number is checked against the caps before memory is asked for. IOException when it is not one. */
    static Rendered readRendered(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return readRendered(in, file.length()); }
    }

    static Rendered readRendered(InputStream in, long length) throws IOException {
        try (DataInputStream stream = new DataInputStream(new java.io.BufferedInputStream(in, 1 << 16))) {
            if (stream.readInt() != RENDER_MAGIC) throw new IOException("not a page");
            int status = stream.readByte(), page = stream.readInt(), pages = stream.readInt(), width = stream.readInt(), height = stream.readInt();
            if (status == 0) return new Rendered(null, 0, 0);
            if (status != 1 || width < 1 || height < 1 || width > PageRender.MAX_SIDE || height > PageRender.MAX_SIDE || (long) width * height > PageRender.MAX_PIXELS
                    || pages < 1 || pages > 1_000_000 || page < 0 || page >= pages) throw new IOException("bad page header");
            if (length != 21L + 4L * width * height) throw new IOException("bad page size");
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
            try {
                int[] row = new int[width];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) row[x] = stream.readInt();
                    bitmap.setPixels(row, 0, width, 0, y, width, 1);
                }
            } catch (IOException | RuntimeException bad) { bitmap.recycle(); throw bad instanceof IOException ? (IOException) bad : new IOException(bad); }
            return new Rendered(bitmap, page, pages);
        }
    }

    /** The reader versions in this process, for the main process's fingerprint: [pdf, amipro]. */
    static int[] versions() { return NativeText.versions(); }

    private static void close(ParcelFileDescriptor fd) { if (fd != null) try { fd.close(); } catch (IOException ignored) { /* closed */ } }

    /** True in the isolated reader processes (`:extract`, `:render`): an isolated uid, or a process name that says so (the system names them `package:extract:class`). */
    static boolean inExtractProcess(Context context) {
        int uid = Process.myUid() % 100000;   // the app id; isolated ones are 99000..99999
        if (uid >= 99000 && uid <= 99999) return true;
        String name = null;
        if (android.os.Build.VERSION.SDK_INT >= 28) name = android.app.Application.getProcessName();
        else try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/cmdline"))) { String line = r.readLine(); name = line == null ? null : line.trim().replace("\0", ""); } catch (IOException ignored) { /* unknown: treated as main */ }
        return name != null && (name.contains(":extract") || name.contains(":render") || name.contains(":ocr"));
    }
}
