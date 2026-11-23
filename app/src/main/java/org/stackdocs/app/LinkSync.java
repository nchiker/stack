package org.stackdocs.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Link record sync on the phone (docs/STACK_LINK_SLICE6_DESIGN.md sections 2 to 4 and 7, work item P1). This class owns
 * - capture: the rows of `link_records` become cells (`SyncGraph.linkWriter`, the writer self-check), diffed ONLY against
 *   `sync_link_projection`, never against the document projection in `sync_state`; one operation per capture;
 * - the enable operation (marker cell, metadata only, never deferred) and the derived `enabled` state;
 * - blob keeping: every text or preview blob a cell references is COPIED to `files/sync_blobs/<sha>` (write, fsync file, rename,
 *   fsync directory, verify the hash) before the caller commits the operation;
 * - install: `LinkStore.applySynced` for received rows, and the rewrite of `sync_link_projection`;
 * - P2 conflicts: the one merge rule per link (`LinkResolve`) in `resolveDecided`, the tie review per link in `resolveTie`, and the
 *   open ties for the conflict screen in `linkConflicts`. Conflicted links are never applied until resolved.
 * Only the application's IO executor calls it, inside the transaction of its caller (SyncStore). Nothing here commits.
 * - S7-4: the partner's shared records (origin 'foreign') become cells only while `foreignPeer` (the computer announced `link_foreign`); until then their projection cells are carried unchanged.
 * - D6 purge: `purge` deletes retained link blobs that nothing references any more (see its comment and LinkPurge).
 */
final class LinkSync {
    static final String DIRTY = "link_dirty", ENABLE_KIND = "links.enable", CAPTURE_KIND = "link.capture";
    /** Replicas known to be enabled (a marker op of their own exists in history). History only grows, so true never goes stale. */
    private static final Set<String> ENABLED = ConcurrentHashMap.newKeySet();
    /** Kept blobs whose hash was checked in this process (key: sha and length). Previews are verified through `preview_mtime` instead (restart-proof). */
    private static final Set<String> VERIFIED = ConcurrentHashMap.newKeySet();
    /** `sync_meta` flag: the last capture deferred a blob because of the 6 GiB unacknowledged-bytes budget, so a receipt may free it. */
    static final String DEFERRED = "link_deferred";

    private final SQLiteDatabase db;
    private final File blobs, previews;
    private final SyncExchange.Control control;
    /** Raised when capture could not finish something (a blob copy failed): the dirty flag stays up and the next capture retries. */
    boolean retry;
    /** Set by the last plan: a blob was deferred by the unacknowledged-bytes budget (a receipt can free it). */
    boolean deferred;
    /** Set by the last plan: how many text cells were left out because the saved text is over 1,048,576 UTF-8 bytes or has a NUL. */
    int textSkipped;
    /** Set by the last enable: the planned cells were refused, so only the marker was written (the cells follow in captures). */
    boolean enableFallback;

    LinkSync(SQLiteDatabase db, File filesDir, SyncExchange.Control control) {
        this.db = db; this.blobs = new File(filesDir, "sync_blobs"); this.previews = new File(filesDir, "links"); this.control = control;
    }
    File blobs() { return blobs; }

    // ---- derived enabled state and the dirty flag --------------------------------------------------------------------

    /** Enabled iff this replica's own `links.enable` operation (it carries the marker cell) is in history. Cached once true. */
    static boolean enabled(SyncProtocol.State state, String replica) {
        if (ENABLED.contains(replica)) return true;
        String marker = SyncGraph.linkMarkerKey(replica);
        for (SyncProtocol.Operation op : state.history.values()) {
            if (!op.replica.equals(replica) || !op.changes.has(marker)) continue;
            JSONObject evidence = op.json.optJSONObject("evidence");
            if (evidence != null && ENABLE_KIND.equals(evidence.optString("kind"))) { ENABLED.add(replica); return true; }
        }
        return false;
    }
    /** Forgets what this process cached about the database (call when the database is replaced or reset: L5). */
    static void forget() { ENABLED.clear(); VERIFIED.clear(); }
    static boolean deferralOutstanding(SQLiteDatabase db) { try (Cursor c = db.rawQuery("SELECT 1 FROM sync_meta WHERE key=?", new String[]{DEFERRED})) { return c.moveToFirst(); } }
    static void setDeferred(SQLiteDatabase db, boolean on) {
        if (on) db.execSQL("INSERT OR REPLACE INTO sync_meta(key,value) VALUES(?, '1')", new Object[]{DEFERRED});
        else db.execSQL("DELETE FROM sync_meta WHERE key=?", new Object[]{DEFERRED});
    }
    /**
     * Slice 7 (S7-4): `sync_meta` flag, true while the paired computer's last hello announced `link_foreign`. Only then are the partner's shared
     * records (origin 'foreign') written into link cells, so a computer that does not know them never receives them. History keeps what was
     * written; a connection reset clears `sync_meta` and with it the flag. Never set by anything but a received hello.
     */
    static final String FOREIGN_PEER = "link_foreign_peer";
    static boolean foreignPeer(SQLiteDatabase db) { try (Cursor c = db.rawQuery("SELECT 1 FROM sync_meta WHERE key=?", new String[]{FOREIGN_PEER})) { return c.moveToFirst(); } }
    /** Records what the computer's hello said. Returns true when the flag was just switched ON (the shared records are then captured). */
    static boolean setForeignPeer(SQLiteDatabase db, boolean on) {
        boolean was = foreignPeer(db);
        if (on == was) return false;
        if (on) { db.execSQL("INSERT OR REPLACE INTO sync_meta(key,value) VALUES(?, '1')", new Object[]{FOREIGN_PEER}); markDirty(db); }
        else db.execSQL("DELETE FROM sync_meta WHERE key=?", new Object[]{FOREIGN_PEER});
        return on;
    }
    static boolean dirty(SQLiteDatabase db) { try (Cursor c = db.rawQuery("SELECT 1 FROM sync_meta WHERE key=?", new String[]{DIRTY})) { return c.moveToFirst(); } }
    static void markDirty(SQLiteDatabase db) { db.execSQL("INSERT OR REPLACE INTO sync_meta(key,value) VALUES(?, '1')", new Object[]{DIRTY}); }
    static void clearDirty(SQLiteDatabase db) { db.execSQL("DELETE FROM sync_meta WHERE key=?", new Object[]{DIRTY}); }

    /** True when an operation (incoming, so not by this replica) announces link sync: the enable evidence or any link cell. */
    static boolean announcesLinks(Iterable<SyncProtocol.Operation> operations) {
        for (SyncProtocol.Operation op : operations) {
            JSONObject evidence = op.json.optJSONObject("evidence");
            if (evidence != null && ENABLE_KIND.equals(evidence.optString("kind"))) return true;
            for (String key : SyncProtocol.keys(op.changes)) if (SyncGraph.isLinkKey(key)) return true;
        }
        return false;
    }

    // ---- the link projection (`sync_link_projection`) ----------------------------------------------------------------

    JSONObject projection() throws IOException, JSONException {
        JSONObject out = new JSONObject();
        try (Cursor c = db.rawQuery("SELECT key,value FROM sync_link_projection", null)) { while (c.moveToNext()) out.put(c.getString(0), ArchiveJson.read(c.getString(1))); }
        return out;
    }
    /** Brings the stored projection from `before` to `after` (the difference only). */
    void writeProjection(JSONObject before, JSONObject after) throws IOException, JSONException {
        JSONObject changes = SyncGraph.diff(before, after);
        for (String key : SyncProtocol.keys(changes)) {
            if (changes.isNull(key)) db.execSQL("DELETE FROM sync_link_projection WHERE key=?", new Object[]{key});
            else { ContentValues row = new ContentValues(); row.put("key", key); row.put("value", SyncProtocol.canonical(changes.get(key))); db.insertWithOnConflict("sync_link_projection", null, row, SQLiteDatabase.CONFLICT_REPLACE); }
        }
    }

    /** Link keys whose register holds more than one distinct value (a conflict). The marker cells are never conflicts that matter. */
    static Set<String> conflictedKeys(SyncProtocol.State state) throws IOException, JSONException {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, List<JSONObject>> entry : state.registers.entrySet()) {
            String key = entry.getKey();
            if (!SyncGraph.isLinkKey(key) || key.startsWith(SyncGraph.LINK_MARKER_PREFIX) || entry.getValue().size() < 2) continue;
            String first = SyncProtocol.canonical(entry.getValue().get(0).get("value"));
            for (JSONObject alternative : entry.getValue()) if (!first.equals(SyncProtocol.canonical(alternative.get("value")))) { out.add(key); break; }
        }
        return out;
    }

    // ---- reading rows -------------------------------------------------------------------------------------------------

    private static final class Row {
        String id, owner, linkId, kind, title, mime, hash, locators, state, removedReason, previewFile, textSha, previewSha, origin, sharedBy;
        long foreignGeneration;
        long modified, addedAt, stateSince, shared, isProtected, titleEdited, textLen, previewLen, previewPending, previewMtime;
    }

    /**
     * The rows that become cells. Own rows always. Documents the partner shares with us (origin 'foreign', S7-3) only when `withForeign`
     * (S7-4: the computer announced `link_foreign`); they are phone-authoritative, and the computer never writes them back.
     */
    private List<Row> rows(boolean withForeign) {
        List<Row> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id,owner,link_id,kind,title,mime,hash,locators,state,removed_reason,preview_file,text_sha,preview_sha,"
                + "modified,added_at,state_since,shared,protected,title_user_edited,text_len,preview_len,preview_pending,preview_mtime,origin,shared_by,foreign_generation"
                + " FROM link_records" + (withForeign ? "" : " WHERE origin='own'") + " ORDER BY added_at,id", null)) {
            while (c.moveToNext()) {
                Row r = new Row();
                r.id = c.getString(0); r.owner = c.getString(1); r.linkId = c.getString(2); r.kind = c.getString(3); r.title = c.getString(4); r.mime = c.getString(5);
                r.hash = c.getString(6); r.locators = c.getString(7); r.state = c.getString(8); r.removedReason = c.getString(9); r.previewFile = c.getString(10);
                r.textSha = c.getString(11); r.previewSha = c.getString(12); r.modified = c.getLong(13); r.addedAt = c.getLong(14); r.stateSince = c.getLong(15);
                r.shared = c.getLong(16); r.isProtected = c.getLong(17); r.titleEdited = c.getLong(18); r.textLen = c.getLong(19); r.previewLen = c.getLong(20); r.previewPending = c.getLong(21); r.previewMtime = c.getLong(22);
                r.origin = c.getString(23); r.sharedBy = c.getString(24); r.foreignGeneration = c.getLong(25);
                out.add(r);
            }
        }
        return out;
    }

    /** Identity-only view of the partner's rows (for keeping their cells while the computer cannot take them). */
    private List<Row> foreignRows() {
        List<Row> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT owner,link_id FROM link_records WHERE origin='foreign'", null)) {
            while (c.moveToNext()) { Row r = new Row(); r.owner = c.getString(0); r.linkId = c.getString(1); out.add(r); }
        }
        return out;
    }

    private static JSONObject firstFilen(String locators) {
        try {
            JSONArray array = new JSONArray(locators);
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry != null && "filen".equals(entry.optString("type"))) return new JSONObject().put("uuid", entry.optString("uuid")).put("path", entry.optString("path"));
            }
        } catch (JSONException invalid) { /* no filen locator */ }
        return null;
    }

    /** The writer-self-check input of one row: metadata and presence. Text and preview are added by the caller (digests only). */
    private static JSONObject writerInput(Row r) throws JSONException {
        JSONObject input = new JSONObject().put("owner", r.owner).put("link_id", r.linkId).put("kind", r.kind).put("added_at", r.addedAt)
            .put("hash", r.hash).put("mime", r.mime).put("modified", r.modified).put("shared", r.shared).put("protected", r.isProtected)
            .put("title", r.title).put("title_user_edited", r.titleEdited);
        JSONObject filen = firstFilen(r.locators);
        if (filen != null) input.put("filen", filen);
        if ("foreign".equals(r.origin)) input.put("origin", "foreign").put("shared_by", r.sharedBy).put("foreign_generation", r.foreignGeneration);
        String[] p = LinkBudget.presence(r.state, r.removedReason, r.stateSince);
        return input.put("presence", new JSONObject().put("state", p[0]).put("reason", p[1]).put("since", Long.parseLong(p[2])));
    }

    // ---- planning one capture ----------------------------------------------------------------------------------------

    /** What a capture or enable wants to write: the changes (full cell keys), and the projection after they are written. */
    static final class Plan {
        final JSONObject changes = new JSONObject(), projection;
        Plan(JSONObject projection) { this.projection = projection; }
    }

    /** NONE/SKIP: no cell. CARRY: keep the previous cell and retry. WAIT: keep the previous cell, nothing to retry (a preview file is pending). */
    private enum Kind { NONE, OK, SKIP, CARRY, WAIT, READ }
    /** `READ`: the text still has to be read (`length` is its `link_search` rowid). `bytes` or `file`: the blob is hashed but not kept yet. */
    private static final class Blob {
        final Kind kind; final String sha; final long length; byte[] bytes; File file;
        Blob(Kind kind, String sha, long length) { this.kind = kind; this.sha = sha; this.length = length; }
    }
    private static final Blob NONE = new Blob(Kind.NONE, null, 0), SKIP = new Blob(Kind.SKIP, null, 0), CARRY = new Blob(Kind.CARRY, null, 0), WAIT = new Blob(Kind.WAIT, null, 0);

    /**
     * Builds the changes between `sync_link_projection` and the rows now. Rules (design sections 2, 3, 7):
     * - identity, content, title, filen and presence are always planned (the writer self-check refuses or skips a bad row/cell;
     *   a refused row keeps its previous cells, it is "not synced" and nothing invalid is written);
     * - text and preview cells only when `blobs` is true (the enable operation is metadata only), never for a protected link,
     *   within the 2 GiB distinct-blob budget (inclusion order: already synced first, then added_at) and, for a blob not yet in an
     *   unacknowledged operation, within 6 GiB of unacknowledged blob bytes; a deferred cell keeps its previous value and is
     *   retried after the next acknowledgement (which raises the dirty flag);
     * - a row that no longer exists becomes `gone` (identity kept, content/text/preview/title/filen null, presence gone);
     * - a conflicted key is emitted when this phone changed it: the new local write is newer than both alternatives and supersedes
     *   them (P2); the other keys of a tied link stay conflicted until the review;
     * - each link's changes are validated; a link that would be rejected is dropped, so nothing invalid enters the chain.
     * Blob files are copied to `sync_blobs` here, before the caller commits.
     */
    Plan plan(SyncProtocol.State state, String replica, TreeMap<String, Long> acknowledged, boolean withBlobs) throws IOException, JSONException {
        JSONObject projection = projection();
        // S7-4: the partner's records are written only for a computer that announced it can show them. While it has not, they are left
        // exactly as the projection has them (never turned into `gone` just because they are not read here).
        boolean foreignOn = foreignPeer(db);
        List<Row> rows = rows(foreignOn);
        Set<String> held = new HashSet<>();
        if (!foreignOn) for (Row r : foreignRows()) { String base = baseKey(r); if (base != null) held.add(base); }
        long[] added = new long[rows.size()]; boolean[] synced = new boolean[rows.size()]; String[] ids = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i); added[i] = r.addedAt; ids[i] = r.id;
            String base = baseKey(r);
            synced[i] = base != null && (projection.has(base + "/text") || projection.has(base + "/preview"));
        }
        LinkBudget.Meter device = new LinkBudget.Meter(LinkBudget.DEVICE_BYTES), unacked = new LinkBudget.Meter(LinkBudget.UNACKED_BYTES);
        // M3: bytes newly hashed or copied in this capture; what does not fit is retried (the order below stays the sticky one).
        LinkBudget.Meter work = new LinkBudget.Meter(LinkBudget.CAPTURE_BLOB_BYTES);
        deferred = false; textSkipped = 0;
        if (withBlobs) for (Map.Entry<String, Long> blob : unackedBlobs(state, replica, acknowledged, false).entrySet()) unacked.force(blob.getKey(), blob.getValue());
        JSONObject target = new JSONObject();
        Set<String> present = new HashSet<>();
        long counted = 0;
        Map<String, Blob> texts = withBlobs ? texts(rows) : new TreeMap<>();
        for (int index : LinkBudget.inclusionOrder(added, synced, ids)) {
            control.check();
            Row r = rows.get(index);
            String base = baseKey(r);
            if (base == null) continue;
            present.add(base);
            JSONObject result = SyncGraph.linkWriter(writerInput(r), counted);
            if ("row_refused".equals(result.getString("outcome"))) { carry(projection, base, target); continue; }
            counted++;
            JSONObject cells = result.getJSONObject("cells");
            for (String suffix : SyncProtocol.keys(cells)) if (!cells.isNull(suffix)) target.put(base + suffix, cells.get(suffix));
            if (!withBlobs || r.isProtected == 1) continue;
            Blob text = texts.getOrDefault(r.id, NONE);
            if (text.kind == Kind.READ) text = readText(r, text.length);
            blobCell(r, base, "/text", text, projection, target, device, unacked, work);
            blobCell(r, base, "/preview", preparePreview(r, device, work), projection, target, device, unacked, work);
        }
        long now = Clock.now();
        for (String key : SyncProtocol.keys(projection)) {
            if (key.indexOf(']') != key.length() - 1) continue;   // identity keys only
            if (present.contains(key) || held.contains(key)) continue;
            JSONObject presence = projection.optJSONObject(key + "/presence");
            if (presence != null && "gone".equals(presence.optString("state"))) { carry(projection, key, target); continue; }
            JSONObject identity = projection.getJSONObject(key);
            JSONObject gone = SyncGraph.linkWriter(new JSONObject().put("owner", identity.getString("owner")).put("link_id", identity.getString("link_id")).put("kind", identity.getString("kind"))
                .put("added_at", identity.get("added_at")).put("presence", new JSONObject().put("state", "gone").put("reason", "deleted").put("since", now)), 0);
            if ("row_refused".equals(gone.getString("outcome"))) { carry(projection, key, target); continue; }
            JSONObject cells = gone.getJSONObject("cells");
            for (String suffix : SyncProtocol.keys(cells)) if (!cells.isNull(suffix)) target.put(key + suffix, cells.get(suffix));
        }
        for (String base : held) carry(projection, base, target);
        // Carried cells (refused rows, deferred blobs) were copied into `target` above; now only the difference is written.
        Plan plan = new Plan(SyncProtocol.copy(projection));
        JSONObject changes = SyncGraph.diff(projection, target);
        Map<String, JSONObject> byLink = new TreeMap<>();
        for (String key : SyncProtocol.keys(changes)) {
            if (changes.isNull(key) && (key.endsWith("]") || key.endsWith("/presence"))) continue;  // identity and presence are not nullable
            byLink.computeIfAbsent(key.substring(0, key.indexOf(']') + 1), ignored -> new JSONObject()).put(key, changes.get(key));
        }
        for (Map.Entry<String, JSONObject> link : byLink.entrySet()) {
            try { SyncGraph.validateChanges(link.getValue()); }
            catch (IOException invalid) { continue; }   // not synced: the link stays as it was in the projection
            for (String key : SyncProtocol.keys(link.getValue())) {
                plan.changes.put(key, link.getValue().get(key));
                if (link.getValue().isNull(key)) plan.projection.remove(key); else plan.projection.put(key, link.getValue().get(key));
            }
        }
        if (plan.changes.length() > 0) SyncGraph.validateChanges(plan.changes);
        return plan;
    }

    private static String baseKey(Row r) {
        try { return SyncGraph.linkKey(r.owner, r.linkId); } catch (IOException | JSONException invalid) { return null; }
    }
    private static void carry(JSONObject projection, String base, JSONObject target) throws JSONException {
        for (String key : SyncProtocol.keys(projection)) if (key.equals(base) || key.startsWith(base + "/")) target.put(key, projection.get(key));
    }

    /** Puts one text or preview cell into `target` or, when it cannot be captured now, keeps what the projection has. */
    private void blobCell(Row r, String base, String suffix, Blob blob, JSONObject projection, JSONObject target, LinkBudget.Meter device, LinkBudget.Meter unacked,
                          LinkBudget.Meter work) throws IOException, JSONException {
        String key = base + suffix;
        switch (blob.kind) {
            case NONE: case SKIP: return;                                    // no cell (an existing one is nulled by the diff)
            case WAIT: case CARRY: if (projection.has(key)) target.put(key, projection.get(key)); if (blob.kind == Kind.CARRY) retry = true; return;
            case OK: break;
            default: return;
        }
        JSONObject previous = projection.optJSONObject(key);
        boolean unchanged = previous != null && blob.sha.equals(previous.optString("sha256"));
        if (!device.tryAdd(blob.sha, blob.length)) return;                   // over 2 GiB: not captured (sticky order decided who stays)
        if (!unchanged && !unacked.contains(blob.sha) && !unacked.tryAdd(blob.sha, blob.length)) {
            deferred = true;
            if (previous != null) target.put(key, previous);                 // deferred: retried after the next acknowledgement
            return;
        }
        // The copy happens only for a blob that is going to be in a cell (and within this capture's byte cap).
        if (!materialize(blob, work)) { if (previous != null) target.put(key, previous); retry = true; return; }
        target.put(key, new JSONObject().put("sha256", blob.sha).put("length", blob.length));
    }

    /** Keeps the blob in `sync_blobs` (nothing to do when it was kept before). False: it could not be kept now; retried. */
    private boolean materialize(Blob blob, LinkBudget.Meter work) throws IOException {
        if (blob.bytes == null && blob.file == null) return true;
        try {
            if (blob.bytes != null) keep(blob.sha, blob.length, new ByteArrayInputStream(blob.bytes), work);
            else try (InputStream in = new FileInputStream(blob.file)) { keep(blob.sha, blob.length, in, work); }
            return true;
        } catch (IOException failed) { control.check(); return false; }
    }

    /** Blob digests (-> length) of the link cells in this replica's own operations the peer has not acknowledged. */
    /** `withResolutions`: also count `link.resolve` operations (the budget does not, the purge must: an export may still send their blobs). */
    private static Map<String, Long> unackedBlobs(SyncProtocol.State state, String replica, TreeMap<String, Long> acknowledged, boolean withResolutions) throws JSONException {
        Map<String, Long> out = new TreeMap<>();
        long seen = acknowledged.getOrDefault(replica, 0L);
        for (SyncProtocol.Operation op : state.history.values()) {
            if (!op.replica.equals(replica) || op.sequence <= seen) continue;
            JSONObject evidence = op.json.optJSONObject("evidence");
            if (!withResolutions && evidence != null && RESOLVE_KIND.equals(evidence.optString("kind"))) continue;   // P2: a resolution may repeat the desktop's blob; it is not counted
            for (String key : SyncProtocol.keys(op.changes)) {
                if (!SyncGraph.isLinkKey(key) || !(key.endsWith("/text") || key.endsWith("/preview")) || op.changes.isNull(key)) continue;
                JSONObject cell = op.changes.getJSONObject(key);
                out.put(cell.getString("sha256"), cell.getLong("length"));
            }
        }
        return out;
    }

    // ---- D6: purge of retained blobs nothing references any more ------------------------------------------------------

    /**
     * Deletes `sync_blobs/<sha>` files of LINKS whose digest has zero references. Run only AFTER the transaction that changed
     * the references has committed (the database already says "unreferenced"; a crash before or during the deletes leaves only
     * files the next pass removes). References, all kept: `files/` refs of history and pending operations (document originals
     * share the directory), every alternative of every link text/preview register (current cell and conflicted alternatives),
     * link blobs of every operation (any writer) the peer has not acknowledged, as the export counts them (resolutions included) and of all pending
     * operations, the link projection, and rows whose preview file still has to be placed. Only a digest that was ever a link
     * cell digest (history, pending, projection) is deleted, so a document original is never purged. Returns files deleted.
     */
    int purge(SyncProtocol.State state, String replica, TreeMap<String, Long> acknowledged) throws IOException, JSONException {
        String[] names = blobs.list();
        if (names == null || names.length == 0) return 0;
        Set<String> everLink = new HashSet<>(), keep = new HashSet<>();
        List<SyncProtocol.Operation> operations = new ArrayList<>(state.history.values());
        operations.addAll(state.pending.values());
        for (SyncProtocol.Operation op : operations) {
            for (String key : SyncProtocol.keys(op.changes)) {
                if (op.changes.isNull(key)) continue;
                if (key.startsWith("files/")) { String sha = shaOf(op.changes.opt(key)); if (sha != null) keep.add(sha); }
                else if (isBlobKey(key)) { String sha = shaOf(op.changes.opt(key)); if (sha != null) { everLink.add(sha); if (state.pending.containsKey(op.key)) keep.add(sha); } }
            }
        }
        for (Map.Entry<String, List<JSONObject>> entry : state.registers.entrySet()) {
            if (!isBlobKey(entry.getKey())) continue;
            for (JSONObject alternative : entry.getValue()) { String sha = shaOf(alternative.opt("value")); if (sha != null) { everLink.add(sha); keep.add(sha); } }
        }
        // Q3: a blob is deleted when no register alternative, retained op, pending op, projection or pending preview references it and no
        // operation the peer has not acknowledged needs it. "Unacknowledged" is the export's notion (SyncExchange.export): every history
        // operation, of ANY replica, with sequence > acknowledged[op.replica]; its link blobs (resolutions included) are kept.
        for (SyncProtocol.Operation op : state.history.values()) {
            if (op.sequence <= acknowledged.getOrDefault(op.replica, 0L)) continue;
            for (String key : SyncProtocol.keys(op.changes)) if (isBlobKey(key) && !op.changes.isNull(key)) { String sha = shaOf(op.changes.opt(key)); if (sha != null) keep.add(sha); }
        }
        JSONObject projection = projection();
        for (String key : SyncProtocol.keys(projection)) {
            if (!isBlobKey(key)) continue;
            String sha = shaOf(projection.opt(key)); if (sha != null) { everLink.add(sha); keep.add(sha); }
        }
        try (Cursor c = db.rawQuery("SELECT preview_sha FROM link_records WHERE preview_pending=1", null)) { while (c.moveToNext()) keep.add(c.getString(0)); }
        int deleted = 0;
        for (String sha : LinkPurge.deletable(Arrays.asList(names), everLink, keep)) { control.check(); if (new File(blobs, sha).delete()) deleted++; }
        return deleted;
    }
    private static boolean isBlobKey(String key) { return SyncGraph.isLinkKey(key) && (key.endsWith("/text") || key.endsWith("/preview")); }
    private static String shaOf(Object cell) {
        if (!(cell instanceof JSONObject)) return null;
        String sha = ((JSONObject) cell).optString("sha256", null);
        return LinkPurge.isDigest(sha) ? sha : null;
    }

    // ---- blobs: digests and keeping -----------------------------------------------------------------------------------

    /**
     * The text blob of every row that needs one. A row whose stored digest is intact (length 1 .. 1 MiB and the kept copy has that
     * length) is reused. For the others ONE pass over `link_search` reads only `rowid, record_id, length(CAST(body AS BLOB))`
     * (H1: a body can be 3 MB of UTF-8, more than a 2 MB CursorWindow, so no body is read here): an empty text has no cell, one over
     * 1,048,576 UTF-8 bytes skips its cell only, the rest are read later, ONE ROW AT A TIME by rowid, in `readText`. If even the size
     * pass fails, the text cells are retried and everything else is still captured.
     */
    private Map<String, Blob> texts(List<Row> rows) throws IOException {
        Map<String, Blob> out = new TreeMap<>(); Map<String, Row> need = new TreeMap<>();
        for (Row r : rows) {
            if (r.isProtected == 1) continue;
            if (!r.textSha.isEmpty() && r.textLen >= 1 && r.textLen <= SyncGraph.LINK_TEXT_BYTES && keptLength(r.textSha) == r.textLen) out.put(r.id, new Blob(Kind.OK, r.textSha, r.textLen));
            else need.put(r.id, r);
        }
        if (need.isEmpty()) return out;
        Set<String> seen = new HashSet<>(); boolean failed = false;
        try (Cursor c = db.rawQuery("SELECT rowid, record_id, length(CAST(body AS BLOB)) FROM link_search", null)) {
            while (c.moveToNext()) {
                Row r = need.get(c.getString(1));
                if (r == null) continue;
                control.check();
                seen.add(r.id);
                switch (LinkBudget.textSize(c.isNull(2) ? 0 : c.getLong(2))) {
                    case EMPTY: digests(r, "text", "", 0); break;
                    case TOO_BIG: out.put(r.id, SKIP); textSkipped++; break;     // over 1,048,576 UTF-8 bytes: the cell only
                    case READ: out.put(r.id, new Blob(Kind.READ, null, c.getLong(0))); break;
                    default: break;
                }
            }
        } catch (RuntimeException unreadable) { failed = true; }
        if (failed) for (String id : need.keySet()) if (!seen.contains(id)) out.put(id, CARRY);   // text cells retried, nothing else is lost
        return out;
    }

    /** Reads one saved text (by rowid), checks and hashes it. A failed read only loses this row's text cell (it is retried). */
    private Blob readText(Row r, long rowid) throws IOException {
        String body;
        try (Cursor c = db.rawQuery("SELECT body FROM link_search WHERE rowid=?", new String[]{Long.toString(rowid)})) {
            if (!c.moveToFirst()) return NONE;
            body = c.getString(0);
        } catch (RuntimeException unreadable) { return CARRY; }
        control.check();
        if (body == null || body.isEmpty()) { digests(r, "text", "", 0); return NONE; }
        if (body.indexOf('\0') >= 0 || LinkBudget.utf8Length(body) > SyncGraph.LINK_TEXT_BYTES) { textSkipped++; return SKIP; }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String sha = SyncGraph.hex(digest().digest(bytes));
        digests(r, "text", sha, bytes.length);
        Blob blob = new Blob(Kind.OK, sha, bytes.length); blob.bytes = bytes;
        return blob;
    }

    /**
     * The preview blob of a row. Its stored digest is trusted when length AND modified time are those recorded when the digest was
     * verified (`preview_mtime`, which survives a restart); otherwise the file is hashed once, within this capture's byte cap.
     */
    private Blob preparePreview(Row r, LinkBudget.Meter device, LinkBudget.Meter work) throws IOException, JSONException {
        if (r.previewPending == 1) return WAIT;                              // the file is not placed yet; keep the synced cell
        if (r.previewFile.isEmpty() || !r.previewFile.equals(r.id + ".pdf")) { digests(r, "preview", "", 0, 0); return NONE; }
        File file = new File(previews, r.previewFile);
        if (!file.isFile() || file.length() == 0) { digests(r, "preview", "", 0, 0); return NONE; }
        long length = file.length(), mtime = file.lastModified();
        if (length > SyncGraph.LINK_PREVIEW_BYTES) return SKIP;
        String sha = r.previewSha;
        boolean trusted = !sha.isEmpty() && r.previewLen == length && mtime != 0 && r.previewMtime == mtime;
        if (!trusted) {
            // M7: a stop between placing the file and the commit can leave a new file under an old digest, so a digest is reused only
            // when length and modified time are the verified ones.
            if (!work.tryAdd("hash:" + r.id + ":" + length, length)) return device.tryAdd("later:" + r.id, length) ? CARRY : SKIP;   // keeps its place in the order
            sha = hash(file, control);
            if (file.length() != length || file.lastModified() != mtime) return CARRY;
            digests(r, "preview", sha, length, mtime);
        }
        Blob blob = new Blob(Kind.OK, sha, length); blob.file = file;
        return blob;
    }

    private void digests(Row r, String which, String sha, long length) { digests(r, which, sha, length, 0); }
    private void digests(Row r, String which, String sha, long length, long mtime) {
        boolean text = which.equals("text");
        if (text ? r.textSha.equals(sha) && r.textLen == length : r.previewSha.equals(sha) && r.previewLen == length && r.previewMtime == mtime) return;
        // Not a column the sync triggers watch, so this write is never captured. `hash=?` keeps a concurrent publish's reset safe.
        if (text) db.execSQL("UPDATE link_records SET text_sha=?, text_len=? WHERE id=? AND hash=?", new Object[]{sha, length, r.id, r.hash});
        else db.execSQL("UPDATE link_records SET preview_sha=?, preview_len=?, preview_mtime=? WHERE id=? AND hash=?", new Object[]{sha, length, mtime, r.id, r.hash});
    }

    private long keptLength(String sha) { File f = new File(blobs, sha); return f.isFile() ? f.length() : -1; }

    /**
     * Copies a blob into `sync_blobs/<sha>`, always a COPY (never a hard link): temp file in the same directory, hash computed
     * while writing, fsync the file, rename, fsync the directory, hash the kept copy again. Only then may the caller commit.
     * A kept blob of the right length that was verified in this process is reused.
     */
    void keep(String sha, long length, InputStream source) throws IOException { keep(sha, length, source, null); }

    /** Thrown when the capture's byte cap (M3) leaves no room for this blob now; nothing was changed, the capture retries it. */
    static final class Deferred extends IOException { Deferred() { super("Capture byte cap reached; retried"); } }

    /** As above; `work` (null = no cap) counts the bytes this capture newly hashes or copies and refuses what does not fit. */
    void keep(String sha, long length, InputStream source, LinkBudget.Meter work) throws IOException {
        if (!blobs.isDirectory() && !blobs.mkdirs()) throw new IOException("Could not retain link blobs");
        File target = new File(blobs, sha);
        String mark = sha + ":" + length;
        if (target.isFile() && target.length() == length) {
            if (VERIFIED.contains("blob:" + mark)) return;
            if (work != null && !work.tryAdd("verify:" + mark, length)) throw new Deferred();
            if (hash(target, control).equals(sha)) { VERIFIED.add("blob:" + mark); return; }
            if (!target.delete()) throw new IOException("A retained blob is damaged");
        }
        if (work != null && !work.tryAdd("copy:" + mark, length)) throw new Deferred();
        File temporary = new File(blobs, ".link-" + UUID.randomUUID() + ".part");
        boolean done = false;
        try {
            MessageDigest digest = digest(); long written = 0;
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = source.read(buffer)) != -1) { control.check(); out.write(buffer, 0, n); digest.update(buffer, 0, n); written += n; }
                out.getFD().sync();                                                       // (2) fsync the file
            }
            if (written != length || !sha.equals(SyncGraph.hex(digest.digest()))) throw new IOException("A link file changed while it was copied");
            if (!temporary.renameTo(target)) throw new IOException("Could not retain a link blob");   // (3) rename
            syncDir(blobs);                                                               //     and fsync the directory
            if (!hash(target, control).equals(sha)) throw new IOException("A retained link blob is damaged");   // (4) verify the kept copy
            VERIFIED.add("blob:" + mark);
            done = true;
        } finally { if (!done) temporary.delete(); }
    }

    static String hash(File file, SyncExchange.Control control) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = new FileInputStream(file)) { byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) != -1) { control.check(); digest.update(buffer, 0, n); } }
        return SyncGraph.hex(digest.digest());
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); } }
    static void syncDir(File directory) throws IOException {
        java.io.FileDescriptor fd = null;
        try { fd = android.system.Os.open(directory.getAbsolutePath(), android.system.OsConstants.O_RDONLY, 0); android.system.Os.fsync(fd); }
        catch (android.system.ErrnoException error) { throw new IOException("Could not sync the link blob directory", error); }
        finally { if (fd != null) try { android.system.Os.close(fd); } catch (android.system.ErrnoException ignored) { } }
    }

    // ---- capture and enable (the caller holds the transaction and saves the state text) -------------------------------

    /**
     * Writes one `link.capture` operation if the rows differ from the link projection, and updates the projection. Returns the
     * number of operations written (0 or 1). The caller commits state and projection together, after the blob copies made here.
     */
    int capture(SyncProtocol.State state, String replica, TreeMap<String, Long> acknowledged) throws IOException, JSONException {
        retry = false; deferred = false;
        Plan plan;
        // Nothing has been changed yet, so a failure while planning (a full disk while copying a blob, an unexpected row) must not stop
        // the session that called capture: the document sync goes on, the dirty flag stays up and the next capture retries.
        try { plan = plan(state, replica, acknowledged, true); }
        catch (IOException | JSONException | RuntimeException failed) {
            control.check();
            android.util.Log.w("StackLinkSync", "Link capture postponed: " + failed.getClass().getSimpleName());
            retry = true; return 0;
        }
        setDeferred(db, deferred);   // a receipt raises the dirty flag only while something is waiting for it
        if (plan.changes.length() == 0) return 0;
        JSONObject before = projection();
        state.local(replica, plan.changes, new JSONObject().put("kind", CAPTURE_KIND));
        writeProjection(before, plan.projection);
        return 1;
    }

    /**
     * The enable operation: marker cell plus the link cells WITHOUT text or preview (metadata only), never deferred by any budget.
     * With no links it is the marker alone, which keeps the kernel from rejecting an empty operation. The blobs follow in later
     * capture operations, so the dirty flag is raised. Returns false when this replica is already enabled.
     */
    boolean enable(SyncProtocol.State state, String replica) throws IOException, JSONException {
        enableFallback = false;
        if (enabled(state, replica)) return false;
        JSONObject changes, projectionAfter = null;
        // H2: this runs inside the hello and the import, where any exception ends the whole session and repeats at every Sync, so no
        // document would sync again. If the planning or the validation of the link cells fails for ANY reason, the marker alone is
        // written (it is always valid); the cells follow in captures (the flag stays up) and retry there.
        try {
            Plan plan = plan(state, replica, new TreeMap<>(), false);
            changes = plan.changes;
            JSONObject marker = SyncGraph.linkMarker(replica, Clock.now());
            for (String key : SyncProtocol.keys(marker)) changes.put(key, marker.get(key));
            SyncGraph.validateChanges(changes);
            projectionAfter = plan.projection;
        } catch (IOException | JSONException | RuntimeException failed) {
            control.check();
            android.util.Log.w("StackLinkSync", "Link enable falls back to the marker alone: " + failed.getClass().getSimpleName());
            enableFallback = true;
            changes = new JSONObject();
            JSONObject marker = SyncGraph.linkMarker(replica, Clock.now());
            for (String key : SyncProtocol.keys(marker)) changes.put(key, marker.get(key));
            SyncGraph.validateChanges(changes);
        }
        JSONObject before = null;
        if (projectionAfter != null) try { before = projection(); } catch (IOException | JSONException | RuntimeException unreadable) { projectionAfter = null; }
        state.local(replica, changes, new JSONObject().put("kind", ENABLE_KIND));
        // The operation is in the state now: a projection that cannot be written is only a stale diff base (the next capture repeats
        // equal cells), never a reason to fail the session.
        if (projectionAfter != null) try { writeProjection(before, projectionAfter); } catch (IOException | JSONException | RuntimeException stale) { enableFallback = true; }
        ENABLED.add(replica);
        markDirty(db);
        return true;
    }

    // ---- P2: one merge rule per link, tie review per link, open ties for the conflict screen --------------------------------

    private static final String[] CELL_SUFFIXES = {"", "/content", "/text", "/preview", "/title", "/filen", "/presence"};
    /** The evidence kind of a resolution operation (design section 3). */
    static final String RESOLVE_KIND = "link.resolve";
    /** Most links in one resolution operation. A normal resolution is one operation; this only bounds the size of a huge one. */
    static final int RESOLVE_LINKS = 256;

    /** The identity keys (`link_records/[owner,link_id]`) of the links that have at least one conflicted cell, sorted. */
    static TreeSet<String> conflictedBases(SyncProtocol.State state) throws IOException, JSONException {
        TreeSet<String> out = new TreeSet<>();
        for (String key : conflictedKeys(state)) out.add(key.substring(0, key.lastIndexOf(']') + 1));
        return out;
    }
    private static Set<String> cellKeys(Collection<String> bases) {
        Set<String> out = new HashSet<>();
        for (String base : bases) for (String suffix : CELL_SUFFIXES) out.add(base + suffix);
        return out;
    }
    /**
     * One side's LATEST value of each cell in `keys`, from the history: the phone side is this replica's operations, the desktop side
     * every other replica's. A value is JSON null when that side wrote null. Ops are read in history order, so the last write wins.
     */
    private static void sideValues(SyncProtocol.State state, String replica, Set<String> keys, boolean phoneSide, Map<String, Object> out) throws JSONException {
        for (SyncProtocol.Operation op : state.history.values()) {
            if (op.replica.equals(replica) != phoneSide) continue;
            for (String key : SyncProtocol.keys(op.changes)) if (keys.contains(key)) out.put(key, op.changes.get(key));
        }
    }
    private static JSONObject object(Object value) { return value instanceof JSONObject ? (JSONObject) value : null; }
    private static boolean isGone(Object presence) { JSONObject p = object(presence); return p != null && "gone".equals(p.optString("state")); }
    private static String hashOf(JSONObject content) { return content == null || !(content.opt("hash") instanceof String) ? null : content.optString("hash"); }
    private static LinkResolve.Winner decide(String base, Map<String, Object> phone, Map<String, Object> desk) {
        JSONObject pc = object(phone.get(base + "/content")), dc = object(desk.get(base + "/content"));
        return LinkResolve.decide(isGone(phone.get(base + "/presence")), isGone(desk.get(base + "/presence")), hashOf(pc), hashOf(dc),
            pc == null ? 0 : pc.optLong("modified"), dc == null ? 0 : dc.optLong("modified"));
    }
    /**
     * The full cell set of one side for a link (design section 3, H-C): every suffix, null where the side lacks the cell, and live
     * presence where the side has none (the writer omits presence for a live link). The identity falls back to the other side.
     * Null when neither side has an identity.
     */
    private static JSONObject winnerCells(String base, Map<String, Object> winner, Map<String, Object> other) throws JSONException {
        JSONObject cells = new JSONObject();
        JSONObject winnerContent = object(winner.get(base + "/content")), otherContent = object(other.get(base + "/content"));
        // H-C: a /text or /preview cell the winner never wrote (absent, not written-null) is KEPT from the other side when that side's
        // content hash is the winner's and the winner is not protected; otherwise it is nulled.
        boolean keepOther = winnerContent != null && otherContent != null && hashOf(winnerContent) != null
            && hashOf(winnerContent).equals(hashOf(otherContent)) && winnerContent.optLong("protected") != 1;
        for (String suffix : CELL_SUFFIXES) {
            String key = base + suffix;
            Object value = winner.get(key);
            if (suffix.isEmpty() && value == null) value = other.get(key);
            if (suffix.isEmpty() && (value == null || value == JSONObject.NULL)) return null;
            if (value == null && keepOther && (suffix.equals("/text") || suffix.equals("/preview")) && other.get(key) instanceof JSONObject) value = other.get(key);
            if (value == null || (value == JSONObject.NULL && suffix.equals("/presence"))) value = suffix.equals("/presence") ? livePresence() : JSONObject.NULL;
            cells.put(key, value);
        }
        return cells;
    }
    private static JSONObject livePresence() throws JSONException { return new JSONObject().put("state", "live").put("reason", "").put("since", 0); }
    private static void writeResolution(SyncProtocol.State state, String replica, JSONObject changes, JSONArray links) throws IOException, JSONException {
        SyncGraph.validateChanges(changes);
        state.local(replica, changes, new JSONObject().put("kind", RESOLVE_KIND).put("links", links));
    }

    /**
     * P2 auto-resolution: every conflicted link that the rule decides (not a tie) is resolved by `link.resolve` operations written by this
     * replica, each carrying the winner's full cell set. Called inside the install transaction, before the received rows are applied.
     * Returns the number of links resolved.
     */
    static int resolveDecided(SyncProtocol.State state, String replica) throws IOException, JSONException {
        TreeSet<String> bases = conflictedBases(state);
        if (bases.isEmpty()) return 0;
        Map<String, Object> phone = new TreeMap<>(), desk = new TreeMap<>();
        Set<String> keys = cellKeys(bases);
        sideValues(state, replica, keys, true, phone);
        sideValues(state, replica, keys, false, desk);
        JSONObject changes = new JSONObject(); JSONArray links = new JSONArray(); int resolved = 0;
        for (String base : bases) {
            LinkResolve.Winner winner = decide(base, phone, desk);
            if (winner == LinkResolve.Winner.REVIEW) continue;
            boolean phoneWins = winner == LinkResolve.Winner.PHONE;
            JSONObject cells = winnerCells(base, phoneWins ? phone : desk, phoneWins ? desk : phone);
            if (cells != null && !phoneWins) {
                // Rule 2, desktop wins: a link the phone trashed stays trashed (Recently deleted); only its other cells follow the desktop.
                JSONObject phonePresence = object(phone.get(base + "/presence")), won = object(cells.get(base + "/presence"));
                if (phonePresence != null && "trashed".equals(phonePresence.optString("state")) && (won == null || "live".equals(won.optString("state"))))
                    cells.put(base + "/presence", phonePresence);
            }
            if (cells == null || !SyncGraph.linkCellsOk(cells, base)) continue;   // same content checks as the writer: a failing link is dropped
            for (String key : SyncProtocol.keys(cells)) changes.put(key, cells.get(key));
            links.put(new JSONObject().put("link", base).put("winner", phoneWins ? "phone" : "desktop").put("basis", "auto"));
            resolved++;
            if (links.length() == RESOLVE_LINKS) { writeResolution(state, replica, changes, links); changes = new JSONObject(); links = new JSONArray(); }
        }
        if (links.length() > 0) writeResolution(state, replica, changes, links);
        return resolved;
    }

    /**
     * P2 tie review, one choice per LINK: writes one `link.resolve` operation with the full cell set of the chosen side (the phone's when
     * `phoneSide`, otherwise the desktop's). The caller must have checked that the link is still conflicted.
     */
    static void resolveTie(SyncProtocol.State state, String replica, String base, boolean phoneSide) throws IOException, JSONException {
        Set<String> keys = cellKeys(Collections.singletonList(base));
        Map<String, Object> phone = new TreeMap<>(), desk = new TreeMap<>();
        sideValues(state, replica, keys, true, phone);
        sideValues(state, replica, keys, false, desk);
        JSONObject cells = winnerCells(base, phoneSide ? phone : desk, phoneSide ? desk : phone);
        if (cells == null) throw SyncProtocol.invalid("link has no identity");
        if (!phoneSide) {
            // Choosing the desktop keeps the phone's causally newer writes: a /title or /filen key that is no longer conflicted was
            // superseded by a local write, so its register value stays. The content, text and preview cells are the desktop's.
            JSONObject values = state.values(), conflicts = state.conflicts();
            for (String suffix : new String[]{"/title", "/filen"}) {
                String key = base + suffix;
                if (!conflicts.has(key) && values.has(key)) cells.put(key, values.get(key));
            }
        }
        if (!SyncGraph.linkCellsOk(cells, base)) throw SyncProtocol.invalid("link cells are not valid");
        writeResolution(state, replica, cells, new JSONArray().put(new JSONObject().put("link", base).put("winner", phoneSide ? "phone" : "desktop").put("basis", "review")));
    }

    /** The open link ties, ONCE per link, with the title and both choices, for the conflict screen (`SyncStore.status`). */
    static JSONArray linkConflicts(SyncProtocol.State state, String replica) throws IOException, JSONException {
        JSONArray out = new JSONArray();
        TreeSet<String> bases = conflictedBases(state);
        if (bases.isEmpty()) return out;
        Map<String, Object> phone = new TreeMap<>(), desk = new TreeMap<>();
        Set<String> keys = cellKeys(bases);
        sideValues(state, replica, keys, true, phone);
        sideValues(state, replica, keys, false, desk);
        for (String base : bases) {
            if (decide(base, phone, desk) != LinkResolve.Winner.REVIEW) continue;   // a decidable link is not a tie (resolveDecided will write it)
            JSONObject identity = object(phone.get(base)) != null ? object(phone.get(base)) : object(desk.get(base));
            JSONObject phoneTitle = object(phone.get(base + "/title")), deskTitle = object(desk.get(base + "/title"));
            String title = phoneTitle != null ? phoneTitle.optString("title") : deskTitle != null ? deskTitle.optString("title") : "";
            out.put(new JSONObject().put("link", base).put("owner", identity == null ? "" : identity.optString("owner"))
                .put("link_id", identity == null ? "" : identity.optString("link_id")).put("title", title)
                .put("choices", new JSONArray().put(choice("phone", phone, base)).put(choice("desktop", desk, base))));
        }
        return out;
    }
    private static JSONObject choice(String side, Map<String, Object> cells, String base) throws JSONException {
        JSONObject content = object(cells.get(base + "/content")), title = object(cells.get(base + "/title"));
        return new JSONObject().put("side", side).put("title", title == null ? "" : title.optString("title"))
            .put("hash", hashOf(content) == null ? "" : hashOf(content)).put("modified", content == null ? 0 : content.optLong("modified"))
            .put("gone", isGone(cells.get(base + "/presence")));
    }

    // ---- install -------------------------------------------------------------------------------------------------------

    /**
     * Applies the link cells of the causal state to the rows (inside the caller's install transaction) and rewrites
     * `sync_link_projection` to the cells of the rows as applied, so the next capture does not re-send or re-diff what was just
     * received. Which links are applied: a link the phone does not have (a desktop-only arrival), or a link whose received cells
     * differ from the projection (a change authored elsewhere). An existing row that has no projection yet is the phone's own
     * and is left alone; capture publishes it. Conflicted links are skipped. After the commit the caller calls
     * `LinkStore.afterApply`.
     */
    LinkStore.Applied install(SyncProtocol.State state, JSONObject values, LinkStore links) throws IOException, JSONException {
        JSONObject projected = SyncGraph.projectLinks(values, conflictedKeys(state));
        JSONObject old = projection(), after = SyncProtocol.copy(old);
        JSONArray all = projected.getJSONArray("links"), apply = new JSONArray();
        Map<String, JSONObject> received = new TreeMap<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject link = all.getJSONObject(i);
            String base = link.getString("key");
            JSONObject cells = cellsOf(values, base), previous = cellsOf(old, base);
            received.put(base, cells);
            boolean exists = exists(link.getString("owner"), link.getString("link_id"));
            if ("gone".equals(link.getString("outcome"))) { if (!exists) replaceCells(after, base, cells); continue; }
            if (!exists || (previous.length() > 0 && !SyncProtocol.canonical(previous).equals(SyncProtocol.canonical(cells)))) apply.put(link);
        }
        LinkStore.Applied applied = links.applySynced(db, apply, blobs);
        for (Map.Entry<String, String> entry : applied.status.entrySet())
            if (entry.getValue().equals("inserted") || entry.getValue().equals("updated")) replaceCells(after, entry.getKey(), received.get(entry.getKey()));
        writeProjection(old, after);
        markDirty(db);   // desktop-only arrivals must publish their presence; deferred blobs may fit now
        return applied;
    }

    private boolean exists(String owner, String linkId) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM link_records WHERE owner=? AND link_id=?", new String[]{owner, linkId})) { return c.moveToFirst(); }
    }
    /** The non-null cells of one link (identity and every suffix) in a cells object. */
    private static JSONObject cellsOf(JSONObject cells, String base) throws JSONException {
        JSONObject out = new JSONObject();
        for (String key : SyncProtocol.keys(cells)) if ((key.equals(base) || key.startsWith(base + "/")) && !cells.isNull(key)) out.put(key, cells.get(key));
        return out;
    }
    private static void replaceCells(JSONObject target, String base, JSONObject cells) throws JSONException {
        for (String key : SyncProtocol.keys(target)) if (key.equals(base) || key.startsWith(base + "/")) target.remove(key);
        for (String key : SyncProtocol.keys(cells)) target.put(key, cells.get(key));
    }
}
