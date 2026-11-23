package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Real-LAN link sync harness, phone side (docs/STACK_LINK_SLICE6_HANDOFF.md, "Real LAN run"). Run with `-e linkLan true`
 * plus `-e linkLanPhase <phase> -e replicaName sync-lan-<id> -e disposableDevice 0A121JEC203530`. The host script
 * `desktop/tools/test_android_link_lan.py` drives it; the pairing itself (identity, adopt, enroll) uses the existing
 * `transportPhase` steps of the same replica name, so both open one isolated library.
 *
 * Phases:
 *   seed     `links` (1..1000, default 50), `previews` (how many get a 20 MiB preview), `tie` (how many of the first links share
 *            their link id with the computer's synthetic links, with a different hash and the same `modified`: a real tie),
 *            `seed` (int). Synthetic records through LinkStore.publish only; never a real document. Idempotent.
 *   sync     `host`, `port`: one Sync now, timed. Writes the phase markers `sync-start` and `sync-done`.
 *   inspect  counts, a digest per link (title, state, text and preview sha256) and the open tie reviews.
 *   resolve  `side` phone | desktop | mixed: every open tie review is decided per link (mixed alternates).
 *   edit     `scenario` delete_edit: rename `edits` links, trash `trashes` links, delete `deletes` links (indexes follow `tie`).
 *   scenario `kill` only changes which markers are written; the host force-stops the app between and inside phases.
 * Every phase writes its name to `files/checks/<replica>/files/link-lan-phase` before and after the work (`<phase>:start`,
 * `<phase>:done`) so the host can pick a kill point. Synthetic data only. Written and compiled without a device.
 */
final class LinkLanChecks {
    private LinkLanChecks() {}
    static final String OWNER = "org.example.amipro", MIME = "application/x-amipro-document", DEVICE = "0A121JEC203530";
    static final long TIE_MODIFIED = 1_700_000_000_000L, PREVIEW_BYTES = 20L * 1024 * 1024;

    /** Same ids as the Rust helper (Uuid::from_u128): the computer's synthetic link n is 7000+n. */
    static String id(long n) { return new UUID(0L, n).toString(); }
    /** The phone's link i: the first `tie` share the computer's ids, the rest are phone only. */
    static String linkId(int i, int tie) { return i <= tie ? id(7000L + i) : id(900_000L + i); }

    static void run(Instrumentation test, Bundle args, Consumer<String> mark) throws Exception {
        Context target = test.getTargetContext();
        if (!ScannerChecks.checksTarget(target)) throw new SecurityException("Disposable Checks installation required");
        if (!DEVICE.equals(args.getString("disposableDevice"))) throw new SecurityException("Test phone authorisation required");
        String name = args.getString("replicaName", "");
        if (!name.matches("sync-lan-[a-z0-9-]+")) throw new IllegalArgumentException("Isolated LAN replica name required");
        String phase = args.getString("linkLanPhase", "");
        Context context = new ScannerChecks.IsolatedContext(target, name);
        File phaseFile = new File(context.getFilesDir(), "link-lan-phase");
        LibraryRepository repo = new LibraryRepository(context); repo.initialize();
        SyncStore store = new SyncStore(context, repo);
        marker(phaseFile, phase + ":start");
        JSONObject out;
        switch (phase) {
            case "seed": out = seed(context, repo, args); break;
            case "sync": out = sync(context, repo, store, args, phaseFile); break;
            case "inspect": out = inspect(repo, store); break;
            case "resolve": out = resolve(store, args.getString("side", "mixed")); break;
            case "edit": out = edit(repo, args); break;
            default: throw new IllegalArgumentException("Unknown phase");
        }
        marker(phaseFile, phase + ":done");
        mark.accept("link-lan " + phase + ": " + out);
    }

    static int bounded(Bundle args, String key, int fallback, int min, int max) {
        String text = args.getString(key);
        int value = text == null || text.isEmpty() ? fallback : Integer.parseInt(text);
        if (value < min || value > max) throw new IllegalArgumentException(key + " out of range");
        return value;
    }

    static void marker(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
    }

    // ---- seed -------------------------------------------------------------------------------------------------------

    static JSONObject seed(Context context, LibraryRepository repo, Bundle args) throws Exception {
        int links = bounded(args, "links", 50, 1, 1000), previews = bounded(args, "previews", 0, 0, links), tie = bounded(args, "tie", 0, 0, links), seed = bounded(args, "seed", 1, 0, 1_000_000);
        File staging = new File(context.getFilesDir(), "link-lan-staging"); staging.mkdirs();
        long started = SystemClock.elapsedRealtime(); int created = 0;
        for (int i = 1; i <= links; i++) {
            boolean tied = i <= tie;
            String hash = sha("link-lan-hash:" + seed + ":" + i + (tied ? ":phone" : ""));
            long modified = tied ? TIE_MODIFIED : 1_800_000_000_000L + i;
            String title = (tied ? "Phone link " : "Synthetic link ") + i;
            JSONObject json = new JSONObject().put("contract", 1).put("owner", OWNER).put("link_id", linkId(i, tie)).put("kind", "doc").put("title", title)
                .put("mime", MIME).put("modified", modified).put("hash", hash).put("protected", false)
                .put("locators", new JSONArray().put(new JSONObject().put("type", "filen").put("uuid", new UUID(1L, i).toString()).put("path", "/Synthetic/" + i + ".sam"))
                    .put(new JSONObject().put("type", "saf").put("uri", "content://synthetic/" + i)));
            File preview = i <= previews ? previewFile(staging, seed, i) : null;
            LinkStore.Result result = repo.links.publish(LinkRecord.parse(json.toString()), "Synthetic text of link " + i + " seed " + seed + " " + "lorem ".repeat(20), preview);
            if (result.created) created++;
        }
        return new JSONObject().put("links", links).put("previews", previews).put("tie", tie).put("created", created).put("seed_ms", SystemClock.elapsedRealtime() - started);
    }

    /** A 20 MiB file that starts like a PDF; the bytes come from a SHA-256 counter stream so the content is reproducible. */
    static File previewFile(File dir, int seed, int i) throws Exception {
        File file = new File(dir, "preview-" + i + ".pdf");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII); out.write(head);
            long written = head.length; long counter = 0;
            while (written < PREVIEW_BYTES) {
                byte[] block = digest.digest(("link-lan-preview:" + seed + ":" + i + ":" + counter++).getBytes(StandardCharsets.UTF_8));
                int n = (int) Math.min(block.length, PREVIEW_BYTES - written);
                out.write(block, 0, n); written += n;
            }
            out.getFD().sync();
        }
        return file;
    }

    // ---- sync -------------------------------------------------------------------------------------------------------

    static JSONObject sync(Context context, LibraryRepository repo, SyncStore store, Bundle args, File phaseFile) throws Exception {
        String host = args.getString("host"), port = args.getString("port");
        if (host == null || port == null) throw new IllegalArgumentException("host and port required");
        PairingTransport transport = new PairingTransport(context, repo);
        marker(phaseFile, "sync-start");
        long started = SystemClock.elapsedRealtime();
        transport.syncNow(host, Integer.parseInt(port));
        long elapsed = SystemClock.elapsedRealtime() - started;
        marker(phaseFile, "sync-done");
        JSONObject status = store.status();
        return new JSONObject().put("sync_ms", elapsed).put("link_conflicts", status.getJSONArray("link_conflicts").length())
            .put("document_conflicts", status.getJSONObject("conflicts").length()).put("operations", status.getInt("operations")).put("rows", rowCount(repo.database(), null));
    }

    // ---- inspect ----------------------------------------------------------------------------------------------------

    static long rowCount(SQLiteDatabase db, String state) {
        return state == null ? android.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM link_records", null)
            : android.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM link_records WHERE state=?", new String[]{state});
    }

    static JSONObject inspect(LibraryRepository repo, SyncStore store) throws Exception {
        SQLiteDatabase db = repo.database(); JSONArray rows = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT id,link_id,title,state,origin FROM link_records ORDER BY link_id", null)) {
            while (c.moveToNext()) {
                String id = c.getString(0); File preview = repo.links.preview(id);
                rows.put(new JSONObject().put("link_id", c.getString(1)).put("title", c.getString(2)).put("state", c.getString(3)).put("origin", c.getString(4))
                    .put("text_sha", sha(repo.links.text(id))).put("preview_sha", preview == null ? "" : fileSha(preview)).put("preview_len", preview == null ? 0 : preview.length()));
            }
        }
        JSONObject status = store.status();
        JSONArray ties = new JSONArray(); JSONArray conflicts = status.getJSONArray("link_conflicts");
        for (int i = 0; i < conflicts.length(); i++) ties.put(new JSONObject().put("link_id", conflicts.getJSONObject(i).optString("link_id")).put("title", conflicts.getJSONObject(i).optString("title")));
        return new JSONObject().put("count", rows.length()).put("live", rowCount(db, "live")).put("trashed", rowCount(db, "trashed")).put("links", rows).put("ties", ties)
            .put("operations", status.optInt("operations")).put("pending_gaps", status.optInt("pending_gaps"));
    }

    // ---- resolve ----------------------------------------------------------------------------------------------------

    static JSONObject resolve(SyncStore store, String side) throws Exception {
        if (!side.equals("phone") && !side.equals("desktop") && !side.equals("mixed")) throw new IllegalArgumentException("side");
        JSONArray conflicts = store.status().getJSONArray("link_conflicts");
        JSONArray decided = new JSONArray();
        for (int i = 0; i < conflicts.length(); i++) {
            String link = conflicts.getJSONObject(i).getString("link");
            String chosen = side.equals("mixed") ? (i % 2 == 0 ? "phone" : "desktop") : side;
            store.resolveLink(link, chosen);
            decided.put(new JSONObject().put("link_id", conflicts.getJSONObject(i).optString("link_id")).put("side", chosen));
        }
        return new JSONObject().put("resolved", decided.length()).put("decided", decided).put("remaining", store.status().getJSONArray("link_conflicts").length());
    }

    // ---- edit / trash / delete --------------------------------------------------------------------------------------

    static JSONObject edit(LibraryRepository repo, Bundle args) throws Exception {
        int links = bounded(args, "links", 50, 1, 1000), tie = bounded(args, "tie", 0, 0, links);
        int edits = bounded(args, "edits", 5, 0, links), trashes = bounded(args, "trashes", 3, 0, links), deletes = bounded(args, "deletes", 5, 0, links);
        if (tie + edits + trashes + deletes > links) throw new IllegalArgumentException("not enough links for the requested changes");
        int next = tie + 1;
        for (int n = 0; n < edits; n++, next++) repo.links.rename(rowId(repo, linkId(next, tie)), "Edited link " + next);
        for (int n = 0; n < trashes; n++, next++) repo.links.trash(rowId(repo, linkId(next, tie)), "user");
        for (int n = 0; n < deletes; n++, next++) repo.links.delete(rowId(repo, linkId(next, tie)));
        return new JSONObject().put("edited", edits).put("trashed", trashes).put("deleted", deletes);
    }

    static String rowId(LibraryRepository repo, String linkId) {
        try (Cursor c = repo.database().rawQuery("SELECT id FROM link_records WHERE owner=? AND link_id=?", new String[]{OWNER, linkId})) {
            if (!c.moveToFirst()) throw new AssertionError("seeded link missing");
            return c.getString(0);
        }
    }

    // ---- digests ----------------------------------------------------------------------------------------------------

    static String sha(String text) throws Exception { return SyncGraph.hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }

    static String fileSha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[1 << 16];
        try (FileInputStream in = new FileInputStream(file)) { for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n); }
        return SyncGraph.hex(digest.digest());
    }
}
