package org.stackdocs.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Partner sharing, publish side (docs/STACK_LINK_SLICE7_DESIGN.md, S7-2). Builds the share archive from the links the person switched
 * "Share with partner" on and writes it, replacing the old file, into the folder the person granted for sharing (the same persistable
 * tree grant mechanism as {@link AutoBackup}). It runs after any change (a short debounce on the sync-changed path), never on the UI
 * thread, and a failure is a quiet line in the sharing settings, never a dialog. The key never leaves {@link PartnerKeys} except as the
 * argument of {@link ShareArchive#write}.
 */
final class PartnerShare {
    static final long DEBOUNCE = 3000L;
    private static final long RETRY_AFTER = 10L * 60 * 1000;
    private static final AtomicBoolean running = new AtomicBoolean(), again = new AtomicBoolean();
    private static final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable scheduled;

    private PartnerShare() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("partner_share", Context.MODE_PRIVATE); }

    static Uri tree(Context context) {
        String saved = prefs(context).getString("tree", "");
        return saved.isEmpty() ? null : Uri.parse(saved);
    }

    static boolean enabled(Context context) { return tree(context) != null; }

    // ---- the folder grant ----------------------------------------------------------------------------------------

    /** Keeps the permission Android grants for the chosen folder across restarts and publishes into it. Call off the UI thread. */
    static void enable(StackApplication app, Uri tree, int grantFlags, String displayName) {
        ContentResolver resolver = app.getContentResolver();
        Uri previous = tree(app);
        resolver.takePersistableUriPermission(tree, grantFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        if (previous != null && !previous.equals(tree)) release(app, previous);
        prefs(app).edit().putString("tree", tree.toString()).putString("tree_name", displayName == null ? "" : displayName)
                .remove("last_error").remove("last_digest").remove("too_large_digest").putLong("last_ok", 0).putLong("last_attempt", 0).commit();
        publishNow(app, true);
    }

    static void disable(StackApplication app) {
        Uri previous = tree(app);
        if (previous != null) release(app, previous);
        prefs(app).edit().clear().commit();
        app.refreshListeners();
    }

    /** A grant that the automatic backup also uses stays. */
    private static void release(Context context, Uri tree) {
        if (tree.equals(AutoBackup.tree(context))) return;
        try { context.getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
        catch (RuntimeException ignored) { /* the grant is already gone */ }
    }

    // ---- status for the sharing settings --------------------------------------------------------------------------

    static String lastError(Context context) { return prefs(context).getString("last_error", ""); }

    static String label(Context context) {
        SharedPreferences p = prefs(context);
        if (tree(context) == null) return "Off · Choose a folder to share with your partner";
        String folder = p.getString("tree_name", "");
        StringBuilder out = new StringBuilder("Sharing to ").append(folder.isEmpty() ? "the chosen folder" : folder);
        long ok = p.getLong("last_ok", 0);
        if (!p.getString("last_error", "").isEmpty()) out.append(" · Not updated yet");
        else if (ok > 0) out.append(" · Updated ").append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(ok)));
        else out.append(" · Waiting to share");
        return out.toString();
    }

    // ---- when it runs ---------------------------------------------------------------------------------------------

    /** Called on every sync-changed notification; a burst of changes becomes one publish a few seconds later. Safe on the UI thread. */
    static void changed(StackApplication app) {
        if (!enabled(app)) return;
        if (scheduled != null) ui.removeCallbacks(scheduled);
        scheduled = () -> start(app, false);
        ui.postDelayed(scheduled, DEBOUNCE);
    }

    /** Runs a publish now (a new folder, a new key); {@code force} writes even when nothing changed. */
    static void publishNow(StackApplication app, boolean force) { start(app, force); }

    /** Called from the foreground poll: tries again after a failure or a locked app, not more often than every ten minutes. */
    static void retryIfNeeded(StackApplication app) {
        SharedPreferences p = prefs(app);
        if (!enabled(app) || !p.getBoolean("pending", false)) return;
        long now = Clock.now(), attempt = p.getLong("last_attempt", 0);
        if (attempt > now || now - attempt >= RETRY_AFTER) start(app, false);
    }

    private static void start(StackApplication app, boolean force) {
        if (!enabled(app)) return;
        if (app.resetRequested || !app.access.allowed()) { prefs(app).edit().putBoolean("pending", true).apply(); return; }
        if (!running.compareAndSet(false, true)) { again.set(true); return; }
        new Thread(() -> {
            try { run(app, force); }
            finally { running.set(false); if (again.getAndSet(false)) ui.post(() -> changed(app)); }
        }, "Stack partner share").start();
    }

    private static void run(StackApplication app, boolean force) {
        SharedPreferences p = prefs(app);
        Uri tree = tree(app);
        if (tree == null) return;
        byte[] key = null;
        String tooLargeDigest = "";
        try {
            key = PartnerKeys.ensureSendKey(app, PartnerKeys.DEFAULT_PARTNER);
            final String fingerprint = PartnerKeys.fingerprint(key);
            Plan plan = app.io.submit(() -> plan(app.library.links, Clock.now(), fingerprint)).get();
            tooLargeDigest = plan.digest;
            // The same content that was already too large or failed for memory is not tried again until a document, the list or the key changes.
            if (!force && plan.digest.equals(p.getString("too_large_digest", "x"))) { p.edit().remove("pending").apply(); return; }
            if (!force && plan.digest.equals(p.getString("last_digest", "")) && p.getLong("last_ok", 0) > 0) { p.edit().remove("pending").apply(); return; }
            p.edit().putLong("last_attempt", Clock.now()).apply();
            // The counter goes up (and is saved) before the file is written; a failed write just leaves a gap, which the importer accepts.
            final PartnerKeys.Stamp stamp = PartnerKeys.nextStamp(app, PartnerKeys.DEFAULT_PARTNER);
            Loaded loaded = app.io.submit(() -> load(app.library.links, plan, stamp.counter)).get();
            ShareArchive.write(new SafSink(app.getContentResolver(), tree), key, loaded.records, loaded.explicitProtected, plan.tombstones, stamp.epoch, stamp.counter);
            p.edit().putLong("last_ok", Clock.now()).putString("last_digest", plan.digest).putInt("last_skipped", plan.notInFilen)
                    .remove("last_error").remove("too_large_digest").remove("pending").commit();
        } catch (Exception | OutOfMemoryError error) {
            Throwable cause = error instanceof java.util.concurrent.ExecutionException && error.getCause() != null ? error.getCause() : error;
            String message = cause instanceof IOException && cause.getMessage() != null && !cause.getMessage().isEmpty() ? cause.getMessage()
                    : cause instanceof OutOfMemoryError ? "The shared documents are too large to share at once." : "Stack could not update the shared file. It will try again.";
            if (cause instanceof ShareArchive.LimitExceeded || cause instanceof OutOfMemoryError) {
                // Too large: retrying the same content cannot succeed, so remember what was tried and stop until something changes.
                p.edit().putString("last_error", message).putString("too_large_digest", tooLargeDigest).remove("pending").putLong("last_attempt", Clock.now()).commit();
                return;
            }
            p.edit().putString("last_error", message).putBoolean("pending", true).putLong("last_attempt", Clock.now()).commit();
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            app.refreshListeners();
        }
    }

    /** Makes a new sending key (the old one is forgotten) and republishes with it. Call off the UI thread; returns the new key. */
    static byte[] rotate(StackApplication app) throws IOException {
        byte[] key = PartnerKeys.rotateSendKey(app, PartnerKeys.DEFAULT_PARTNER);
        prefs(app).edit().remove("last_digest").remove("last_error").remove("too_large_digest").apply();
        publishNow(app, true);
        return key;
    }

    // ---- what goes into the file ----------------------------------------------------------------------------------

    /** The decision, without the heavy text and previews. */
    static final class Plan {
        final List<LinkStore.Candidate> included; final List<ShareArchive.Tombstone> tombstones; final int notInFilen, excludedProtected; final String digest;
        Plan(List<LinkStore.Candidate> included, List<ShareArchive.Tombstone> tombstones, int notInFilen, int excludedProtected, String digest) {
            this.included = included; this.tombstones = tombstones; this.notInFilen = notInFilen; this.excludedProtected = excludedProtected; this.digest = digest;
        }
    }

    /** What {@link #load} read: ordinary records, and protected ones the person confirmed. */
    static final class Loaded {
        final List<ShareArchive.Record> records = new ArrayList<>(), explicitProtected = new ArrayList<>();
    }

    /**
     * Decides what is shared now and brings the 30-day removal notices up to date (run on the IO executor). A link goes in when its switch is on
     * and it is live or unreachable and has a Filen place; a protected link only with the person's confirmation (mode 2). Anything that was shared
     * before and no longer qualifies (switched off, removed, trashed, deleted for good) becomes a removal notice for 30 days. A link that is only
     * missing for now is neither shared nor announced as removed. {@code keyFingerprint} is part of the digest so a new key always republishes.
     */
    static Plan plan(LinkStore links, long now, String keyFingerprint) throws Exception {
        List<LinkStore.Candidate> included = new ArrayList<>();
        Set<String> sharedNow = new HashSet<>();
        int notInFilen = 0, excluded = 0;
        for (LinkStore.Candidate c : links.partnerFlagged()) {
            boolean up = LinkStore.LIVE.equals(c.state) || LinkStore.UNREACHABLE.equals(c.state);
            if (!up) { if (LinkStore.MISSING.equals(c.state)) sharedNow.add(c.owner + "\n" + c.linkId); continue; }
            if (c.isProtected && c.mode != 2) { excluded++; continue; }
            if (filen(c.locators) == null) { notInFilen++; continue; }
            included.add(c); sharedNow.add(c.owner + "\n" + c.linkId);
        }
        // A missing link keeps whatever notice state it had: it is in sharedNow only so it does not turn into a notice.
        List<ShareArchive.Tombstone> tombstones = links.partnerTombstones(sharedNow, now);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(("key|" + keyFingerprint + "\n").getBytes("UTF-8"));
        for (LinkStore.Candidate c : included)
            sha.update((c.id + "|" + c.revision + "|" + c.mode + "|" + c.state + "|" + c.hash + "|" + c.title + "|" + c.modified + "|" + c.locators + "\n").getBytes("UTF-8"));
        for (ShareArchive.Tombstone t : tombstones) sha.update(("gone|" + t.owner + "|" + t.linkId + "|" + t.removedAt + "\n").getBytes("UTF-8"));
        return new Plan(included, tombstones, notInFilen, excluded, PartnerKeys.hex(sha.digest()));
    }

    /** Reads the saved text and preview of every included link (run on the IO executor). */
    static Loaded load(LinkStore links, Plan plan, long counter) throws IOException {
        Loaded out = new Loaded();
        for (LinkStore.Candidate c : plan.included) {
            LinkRecord.Locator filen = filen(c.locators);
            ShareArchive.Record r = new ShareArchive.Record(c.owner, c.linkId, c.kind, filen.uuid, filen.path, c.title, c.mime, c.hash, c.modified,
                    links.text(c.id), links.previewBytes(c.id), counter, c.isProtected);
            (c.isProtected ? out.explicitProtected : out.records).add(r);
        }
        return out;
    }

    private static LinkRecord.Locator filen(String locatorsJson) {
        List<LinkRecord.Locator> list = LinkRecord.parseLocators(locatorsJson);
        if (list != null) for (LinkRecord.Locator l : list) if (LinkRecord.Locator.FILEN.equals(l.type) && l.uuid != null && l.path != null) return l;
        return null;
    }

    /**
     * The plan and the file in one step, for a folder the caller already has a sink for (used by checks and by the phone with a SAF sink).
     * Runs on the IO executor when called by the app; the checks call it directly.
     */
    static ShareArchive.WriteResult publish(LinkStore links, ShareArchive.Sink sink, byte[] key, long now, PartnerKeys.Stamp stamp) throws Exception {
        Plan plan = plan(links, now, PartnerKeys.fingerprint(key));
        Loaded loaded = load(links, plan, stamp.counter);
        return ShareArchive.write(sink, key, loaded.records, loaded.explicitProtected, plan.tombstones, stamp.epoch, stamp.counter);
    }

    // ---- writing into the granted folder --------------------------------------------------------------------------

    /**
     * A folder reached through a document provider. The new file is written under a temporary name and renamed over the old one, so a reader
     * never sees a half-written file. Most providers cannot replace a file in one step, so the old file is deleted just before the rename; for
     * that instant a reader sees no file, which an importer treats as "nothing new".
     */
    static final class SafSink implements ShareArchive.Sink {
        private final ContentResolver resolver; private final Uri tree, folder;
        private Uri temp;
        SafSink(ContentResolver resolver, Uri tree) {
            this.resolver = resolver; this.tree = tree;
            this.folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        }
        private Uri find(String name) {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null) while (c.moveToNext()) if (name.equals(c.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
            } catch (RuntimeException unreadable) { /* treated as not there */ }
            return null;
        }
        @Override public OutputStream create(String tempName) throws IOException {
            Uri stale = find(tempName);
            if (stale != null) try { DocumentsContract.deleteDocument(resolver, stale); } catch (Exception ignored) { /* best effort */ }
            temp = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", tempName);
            if (temp == null) throw new IOException("The sharing folder does not accept new files. Choose another folder.");
            OutputStream out = resolver.openOutputStream(temp, "w");
            if (out == null) throw new IOException("The sharing folder could not be opened. Choose another folder.");
            return out;
        }
        @Override public void commit(String tempName, String finalName) throws IOException {
            if (temp == null) throw new IOException("The shared file was not written.");
            temp = landed(tempName, temp);
            Uri old = find(finalName);
            if (old != null) {
                boolean gone;
                try { gone = DocumentsContract.deleteDocument(resolver, old); } catch (Exception error) { gone = false; }
                if (!gone) throw new IOException("The sharing folder does not let Stack replace the shared file.");
            }
            Uri renamed;
            try { renamed = DocumentsContract.renameDocument(resolver, temp, finalName); } catch (Exception error) { renamed = null; }
            if (renamed == null) throw new IOException("The sharing folder does not let Stack rename the shared file.");
            temp = null;
        }
        /**
         * Some providers (Filen Files) hand back a provisional "pending" document id while the upload runs and only list the real document afterwards.
         * Deleting the old shared file before the new one has landed would leave the partner with nothing, so wait (up to a minute) until the temp file
         * is listed under a non-provisional id, and use that id for the rename. Providers that write at once answer on the first look.
         */
        private Uri landed(String tempName, Uri created) throws IOException {
            long until = android.os.SystemClock.elapsedRealtime() + 60_000;
            while (true) {
                Uri listed = find(tempName);
                if (listed != null && !DocumentsContract.getDocumentId(listed).contains("pending")) return listed;
                if (android.os.SystemClock.elapsedRealtime() >= until) {
                    if (listed != null) return listed;
                    throw new IOException("The sharing folder has not finished saving the shared file. Stack will try again.");
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while saving the shared file."); }
            }
        }

        @Override public void discard(String tempName) {
            Uri leftover = temp != null ? temp : find(tempName);
            temp = null;
            if (leftover != null) try { DocumentsContract.deleteDocument(resolver, leftover); } catch (Exception ignored) { /* nothing more to do */ }
        }
    }
}
