package org.stackdocs.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Receives link records from owner apps (docs/STACK_LINK_CONTRACT.md, slice 2). Flow: `begin` with the record JSON
 * returns a token; the owner writes `text` and, optionally, `preview` through openFile(stage/token/name, "w"); `commit`
 * validates and stores. Every entry point checks the caller (LinkCallers) and that the record's `owner` is the caller's own
 * package, so one app cannot publish as another. The provider never reads or returns library content.
 */
public final class LinkProvider extends ContentProvider {
    static final String METHOD_CONTRACT = "contract", METHOD_BEGIN = "begin", METHOD_COMMIT = "commit", METHOD_ABORT = "abort";
    static final int MAX_RECORD_JSON = 256 * 1024, MAX_TEXT_BYTES = 4 * 1024 * 1024, MAX_STAGED = 8;
    static final long STAGING_LIFETIME_MS = 10 * 60 * 1000L;
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** At most two commits wait on the shared IO executor at once, so a caller cannot tie up the binder threads. */
    private static final Semaphore COMMITS = new Semaphore(2);

    @Override public boolean onCreate() { sweep(); return true; }

    private File root() { return new File(getContext().getFilesDir(), "link-staging"); }

    /** The calling package, or a SecurityException. Never trusts the caller's own claims. */
    private String caller() {
        String allowed = LinkCallers.allowedCaller(getContext(), Binder.getCallingUid());
        if (allowed == null) throw new SecurityException("This app may not use Stack's link provider.");
        return allowed;
    }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        String caller = caller();
        Bundle out = new Bundle();
        try {
            switch (method == null ? "" : method) {
                case METHOD_CONTRACT: out.putInt("min", LinkRecord.CONTRACT); out.putInt("max", LinkRecord.CONTRACT); return out;
                case METHOD_BEGIN: return begin(caller, argument);
                case METHOD_COMMIT: return commit(caller, argument);
                case METHOD_ABORT: { File dir = stage(caller, argument); if (dir != null) deleteTree(dir); return out; }
                default: out.putString("error", "unknown_method"); return out;
            }
        } catch (LinkRecord.UnsupportedContract error) {
            out.putString("error", "unsupported_contract"); out.putInt("version", error.version); out.putString("message", error.getMessage()); return out;
        } catch (IOException error) {
            out.putString("error", "invalid"); out.putString("message", String.valueOf(error.getMessage())); return out;
        }
    }

    private Bundle begin(String caller, String json) throws IOException {
        if (json == null || json.length() > MAX_RECORD_JSON) throw new IOException("The record is missing or too large.");
        LinkRecord record = LinkRecord.parse(json);
        if (!record.owner.equals(caller)) throw new IOException("A record can only be published by the app named in its owner field.");
        File root = root();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create the staging folder.");
        int live = sweep();
        if (live >= MAX_STAGED) throw new IOException("Too many records are being sent. Try again in a moment.");
        String token = UUID.randomUUID().toString();
        File dir = new File(root, token);
        if (!dir.mkdir()) throw new IOException("Cannot create the staging folder.");
        write(new File(dir, "record.json"), json);
        write(new File(dir, "caller"), caller);
        for (String name : new String[]{"text", "preview"}) if (!new File(dir, name).createNewFile()) throw new IOException("Cannot stage the record.");
        Bundle out = new Bundle();
        out.putString("token", token);
        return out;
    }

    private Bundle commit(String caller, String token) throws IOException {
        File dir = stage(caller, token);
        if (dir == null) throw new IOException("Unknown or expired token.");
        if (!COMMITS.tryAcquire()) throw new IOException("Stack is busy. Try again in a moment.");
        Future<LinkStore.Result> work = null;
        try {
            LinkRecord record = LinkRecord.parse(decode(readLimited(new File(dir, "record.json"), MAX_RECORD_JSON)));
            // The owner may still hold a writable descriptor on the staged files, so nothing is trusted in place:
            // every read is bounded, and the preview is copied into a file only Stack has open before it is checked.
            String text = decode(readLimited(new File(dir, "text"), MAX_TEXT_BYTES));
            LinkRecord.checkText(text);
            File staged = new File(dir, "preview"), checked = null;
            if (staged.length() > 0) {
                checked = new File(dir, "preview.checked");
                copyLimited(staged, checked, LinkRecord.MAX_PREVIEW);
                byte[] head = new byte[8];
                int n;
                try (InputStream in = new FileInputStream(checked)) { n = in.read(head); }
                LinkRecord.checkPreview(checked.length(), java.util.Arrays.copyOf(head, Math.max(n, 0)));
            }
            StackApplication app = (StackApplication) getContext().getApplicationContext();
            final File previewToStore = checked;
            LinkStore.Result result;
            try {
                if (!app.libraryReady.await(20, TimeUnit.SECONDS)) throw new IOException("Stack is still starting. Try again.");
                work = app.io.submit(() -> app.library.links.publish(record, text, previewToStore));
                result = work.get(30, TimeUnit.SECONDS);
            } catch (InterruptedException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
                if (work != null && !work.isDone()) work.cancel(true);
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                throw cause instanceof IOException ? (IOException) cause : new IOException("Stack could not store the record. Try again.");
            }
            app.notifySyncChanged();
            // Only the local id and what happened are returned; whether other records hold the same bytes stays inside Stack.
            Bundle out = new Bundle();
            out.putString("id", result.id); out.putBoolean("created", result.created); out.putBoolean("content_changed", result.contentChanged);
            return out;
        } finally {
            COMMITS.release();
            // A publish that is still running owns the checked preview; the sweep removes the folder later.
            if (work == null || work.isDone()) deleteTree(dir);
        }
    }

    /** Removes staging folders that are old (judged by their newest file) or dated in the future; returns how many remain. */
    private int sweep() {
        File[] dirs = root().listFiles();
        if (dirs == null) return 0;
        long now = Clock.now();
        int live = 0;
        for (File dir : dirs) {
            long newest = dir.lastModified();
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) newest = Math.max(newest, file.lastModified());
            long age = now - newest;
            if (age < 0 || age > STAGING_LIFETIME_MS) deleteTree(dir); else live++;
        }
        return live;
    }

    /** The staging folder for `token` if the token is well formed, exists, and was begun by `caller`; otherwise null. */
    private File stage(String caller, String token) {
        if (token == null || !TOKEN.matcher(token).matches()) return null;
        File dir = new File(root(), token);
        if (!dir.isDirectory()) return null;
        try { return caller.equals(read(new File(dir, "caller"), 256)) ? dir : null; } catch (IOException error) { return null; }
    }

    /** Write-only access to `stage/<token>/text` or `.../preview` for the caller that began that token. */
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        String caller = caller();
        java.util.List<String> parts = uri.getPathSegments();
        if (parts.size() != 3 || !"stage".equals(parts.get(0)) || !("w".equals(mode) || "wt".equals(mode))) throw new java.io.FileNotFoundException();
        String name = parts.get(2);
        if (!"text".equals(name) && !"preview".equals(name)) throw new java.io.FileNotFoundException();
        File dir = stage(caller, parts.get(1));
        if (dir == null) throw new java.io.FileNotFoundException();
        return ParcelFileDescriptor.open(new File(dir, name), ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
    }

    private static String decode(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw new IOException("The text is not valid UTF-8."); }
    }

    /** At most `limit` bytes; a longer file is refused. Never sized from File.length(), which the owner can change while this runs. */
    private static byte[] readLimited(File file, int limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            for (int n; (n = in.read(buffer)) > 0; ) {
                if (out.size() + n > limit) throw new IOException("A staged file is too large.");
                out.write(buffer, 0, n);
            }
        }
        return out.toByteArray();
    }

    /** Copies at most `limit` bytes into a new file Stack alone has open; refuses (and deletes the copy) if the source is longer. */
    private static void copyLimited(File from, File to, long limit) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            for (int n; (n = in.read(buffer)) > 0; ) {
                total += n;
                if (total > limit) { to.delete(); throw new IOException("The preview is too large."); }
                out.write(buffer, 0, n);
            }
        }
    }

    private static String read(File file, int limit) throws IOException { return decode(readLimited(file, limit)); }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { caller(); return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { caller(); throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { caller(); throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { caller(); throw new UnsupportedOperationException(); }
}
