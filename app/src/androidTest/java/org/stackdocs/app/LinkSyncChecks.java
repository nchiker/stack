package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Slice 6, work item P1 (docs/STACK_LINK_SLICE6_DESIGN.md sections 2 to 4, 7, 9): the phone side of link sync. Isolated libraries and
 * synthetic links only. The "desktop" is a fake peer: a bare causal state (no database) that authors link operations by the
 * production SyncGraph writer and is delivered through the production SyncExchange. Nothing announces on a real session
 * (PairingTransport.ANNOUNCE_LINKS is false), so the announcing peer is simulated with `PairingTransport.onPeerHello`.
 * Run with `-e linkSync true`. Written and compiled without a device; it has not been run (see the P1 notes in the design doc).
 */
final class LinkSyncChecks {
    private LinkSyncChecks() {}
    private static final String OWNER = "app.stub.owner", MIME = "application/x-amipro-document";

    static void run(Instrumentation test, Consumer<String> mark) throws Exception {
        budgetRules(mark);
        migration(test.getTargetContext(), mark);
        captureAndTriggers(test, mark);
        enableAndHello(test, mark);
        receiveAndApply(test, mark);
        applySyncedDirect(test, mark);
        digestsAndBlobs(test, mark);
        resetClears(test, mark);
        reviewTextAndEnable(test, mark);
        reviewExportAndCaps(test, mark);
        reviewApply(test, mark);
        reviewPreviewFiles(test, mark);
        resolution(test, mark);
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------------

    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    /** One isolated phone library with sync started against a fake desktop replica. */
    static final class Phone {
        final Context context; final LibraryRepository repo; final SyncStore store; final SQLiteDatabase db; final LinkStore links; final String desktop = UUID.randomUUID().toString();
        final File staging;
        Phone(Instrumentation test, String name) throws Exception {
            context = new NearbyPairingChecks.FixtureContext(test.getTargetContext(), name + "-" + UUID.randomUUID());
            repo = new LibraryRepository(context); repo.initialize();
            store = new SyncStore(context, repo); db = repo.database(); links = repo.links;
            staging = new File(context.getFilesDir(), "link-sync-staging"); staging.mkdirs();
            store.start(Collections.singletonList(desktop));
        }
        String replica() { return store.replica(); }
        SyncProtocol.State state() throws Exception { return store.load(); }
        void close() { repo.closeForReset(); }
        File pdf(String name, String body) throws IOException { File f = new File(staging, name); try (FileOutputStream o = new FileOutputStream(f)) { o.write(("%PDF-1.7\n" + body).getBytes(StandardCharsets.US_ASCII)); } return f; }
        String publish(String linkId, String title, String hash, String text, String previewBody, boolean isProtected) throws Exception {
            LinkRecord record = LinkRecord.parse(new JSONObject().put("contract", 1).put("owner", OWNER).put("link_id", linkId).put("kind", "doc").put("title", title)
                .put("mime", MIME).put("modified", 1_800_000_000_000L).put("hash", hash).put("protected", isProtected)
                .put("locators", new JSONArray().put(new JSONObject().put("type", "filen").put("uuid", UUID.randomUUID().toString()).put("path", "/Health/" + title + ".sam"))
                    .put(new JSONObject().put("type", "saf").put("uri", "content://stub/" + linkId))).toString());
            return links.publish(record, text, previewBody == null ? null : pdf(UUID.randomUUID() + ".pdf", previewBody)).id;
        }
        long count(String sql, String... args) { return android.database.DatabaseUtils.longForQuery(db, sql, args); }
        String text(String sql, String... args) { try (Cursor c = db.rawQuery(sql, args)) { return c.moveToFirst() ? c.getString(0) : null; } }
        /** Operations written by this replica whose changes touch link cells, in order. */
        List<SyncProtocol.Operation> linkOps() throws Exception {
            List<SyncProtocol.Operation> out = new ArrayList<>(); String me = replica();
            for (SyncProtocol.Operation op : state().history.values()) if (op.replica.equals(me) && hasLinkKey(op)) out.add(op);
            return out;
        }
        int ownOps() throws Exception { int n = 0; String me = replica(); for (SyncProtocol.Operation op : state().history.values()) if (op.replica.equals(me)) n++; return n; }
        SyncProtocol.Operation lastOwn() throws Exception { SyncProtocol.Operation last = null; String me = replica(); for (SyncProtocol.Operation op : state().history.values()) if (op.replica.equals(me)) last = op; return last; }
    }

    static boolean nullCell(SyncProtocol.Operation op, String key) { return op.changes.has(key) && op.changes.isNull(key); }
    static boolean hasLinkKey(SyncProtocol.Operation op) { for (String key : SyncProtocol.keys(op.changes)) if (SyncGraph.isLinkKey(key)) return true; return false; }
    static String evidence(SyncProtocol.Operation op) throws Exception { return op.json.getJSONObject("evidence").optString("kind"); }
    static String hex(byte[] bytes) throws Exception { return SyncGraph.hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String hash(char c) { return String.valueOf(c).repeat(64); }
    static String key(String linkId) throws Exception { return SyncGraph.linkKey(OWNER, linkId); }

    /** The fake computer: a causal state that never opens a database. */
    static final class Desk {
        final String replica; final SyncProtocol.State state; long delivered; final Map<String, byte[]> blobs = new TreeMap<>();
        Desk(Phone phone) throws Exception {
            replica = phone.desktop; SyncProtocol.State p = phone.state();
            state = new SyncProtocol.State(p.library, p.members); learn(phone);
        }
        /** The computer receives everything the phone has written so far. */
        void learn(Phone phone) throws Exception { state.receive(new ArrayList<>(phone.state().history.values())); }
        /** The cells of one desktop link (no presence cell, as the real enable op omits it). */
        JSONObject link(String linkId, String hash, long modified, String title, String text, byte[] preview, boolean isProtected, String path) throws Exception {
            JSONObject row = new JSONObject().put("owner", OWNER).put("link_id", linkId).put("kind", "doc").put("added_at", 5L).put("hash", hash).put("mime", MIME).put("modified", modified)
                .put("shared", 0).put("protected", isProtected ? 1 : 0).put("title", title).put("title_user_edited", 0);
            if (path != null) row.put("filen", new JSONObject().put("uuid", UUID.randomUUID().toString()).put("path", path));
            if (text != null) { row.put("text", text); byte[] bytes = text.getBytes(StandardCharsets.UTF_8); blobs.put(hex(bytes), bytes); }
            if (preview != null) { String sha = hex(preview); row.put("preview", new JSONObject().put("sha256", sha).put("length", preview.length)); blobs.put(sha, preview); }
            return SyncGraph.linkChanges(SyncGraph.linkWriter(row, 0));
        }
        SyncProtocol.Operation write(JSONObject changes, String kind) throws Exception { return state.local(replica, changes, new JSONObject().put("kind", kind)); }
        SyncProtocol.Operation enable(JSONObject... links) throws Exception {
            JSONObject changes = new JSONObject();
            for (JSONObject link : links) for (String k : SyncProtocol.keys(link)) changes.put(k, link.get(k));
            JSONObject marker = SyncGraph.linkMarker(replica, 1L); for (String k : SyncProtocol.keys(marker)) changes.put(k, marker.get(k));
            return write(changes, "links.enable");
        }
        /** A STACKEX1 bundle of the operations the phone has not been sent, with the blobs they reference. */
        byte[] bundle() throws Exception {
            JSONArray ops = new JSONArray(); long top = delivered;
            for (SyncProtocol.Operation op : state.history.values()) if (op.replica.equals(replica) && op.sequence > delivered) { ops.put(op.json); top = Math.max(top, op.sequence); }
            JSONArray descriptors = new JSONArray(); List<byte[]> bodies = new ArrayList<>();
            for (Map.Entry<String, byte[]> blob : blobs.entrySet()) { descriptors.put(new JSONObject().put("sha256", blob.getKey()).put("length", blob.getValue().length)); bodies.add(blob.getValue()); }
            JSONObject header = new JSONObject().put("format", "org.stackdocs.exchange").put("version", 1).put("library", state.library).put("sender", replica)
                .put("acknowledged", SyncProtocol.json(state.frontier)).put("operations", ops).put("blobs", descriptors);
            byte[] metadata = SyncProtocol.canonical(header).getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.write(new byte[]{'S', 'T', 'A', 'C', 'K', 'E', 'X', 1}); out.writeInt(metadata.length); out.write(metadata);
            for (byte[] body : bodies) out.write(body);
            delivered = top; blobs.clear();
            return bytes.toByteArray();
        }
        JSONObject deliver(Phone phone) throws Exception {
            SyncExchange exchange = new SyncExchange(phone.context, phone.repo);
            try (SyncExchange.Staged staged = exchange.stage(new ByteArrayInputStream(bundle()))) { return exchange.importFile(staged, null, null); }
        }
    }

    // ---- pure budget rules -------------------------------------------------------------------------------------------

    private static void budgetRules(Consumer<String> mark) {
        int[] order = LinkBudget.inclusionOrder(new long[]{30, 10, 20, 5}, new boolean[]{false, false, true, false}, new String[]{"d", "b", "c", "a"});
        require(order[0] == 2 && order[1] == 3 && order[2] == 1 && order[3] == 0, "already-synced links first, then added_at ascending (sticky)");
        LinkBudget.Meter meter = new LinkBudget.Meter(100);
        require(meter.tryAdd("a", 60) && meter.tryAdd("a", 60) && !meter.tryAdd("b", 50) && meter.tryAdd("b", 40) && meter.total() == 100, "distinct digests are counted once and the cap holds");
        require(LinkBudget.utf8Length("€".repeat(3)) == 9 && LinkBudget.utf8Length("😀") == 4 && LinkBudget.utf8Length("abc") == 3, "text length is UTF-8 bytes");
        require(LinkBudget.DEVICE_BYTES == 2L * 1024 * 1024 * 1024 && LinkBudget.UNACKED_BYTES == 6L * 1024 * 1024 * 1024, "2 GiB and 6 GiB");
        mark.accept("Link sync: inclusion order, distinct-blob meter and UTF-8 length rules");
    }

    // ---- migration 12 -> 13 ------------------------------------------------------------------------------------------

    private static void migration(Context target, Consumer<String> mark) throws Exception {
        Context isolated = new ScannerChecks.IsolatedContext(target);
        String name = "migrate-" + UUID.randomUUID() + ".db", id = UUID.randomUUID().toString();
        LibraryDatabase first = new LibraryDatabase(isolated, name);
        try {
            SQLiteDatabase db = first.getWritableDatabase();
            db.execSQL("INSERT INTO link_records(id,owner,link_id,kind,title,mime,modified,hash,locators,added_at,received_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                new Object[]{id, OWNER, UUID.randomUUID().toString(), "doc", "Old row", MIME, 5L, hash('a'), "[]", 1L, 1L});
            // Make it a real version 12 library: no digest columns, no projection table, no link triggers.
            String v12 = "id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,state,preview_file,added_at,received_at,title_user_edited,state_since,checked_at,revision,removed_reason";
            db.execSQL("CREATE TABLE link_old AS SELECT " + v12 + " FROM link_records");
            db.execSQL("DROP TABLE link_records"); db.execSQL("ALTER TABLE link_old RENAME TO link_records"); db.execSQL("DROP TABLE sync_link_projection");
            db.setVersion(12);
        } finally { first.close(); }
        LibraryDatabase second = new LibraryDatabase(isolated, name);
        try {
            SQLiteDatabase db = second.getWritableDatabase();
            require(db.getVersion() == 13, "a version 12 library upgrades to 13");
            try (Cursor c = db.rawQuery("SELECT title,text_sha,text_len,preview_sha,preview_len,preview_pending FROM link_records WHERE id=?", new String[]{id})) {
                require(c.moveToFirst() && c.getString(0).equals("Old row") && c.getString(1).isEmpty() && c.getLong(2) == 0 && c.getString(3).isEmpty() && c.getLong(4) == 0 && c.getLong(5) == 0, "the row survives with empty digests and preview_pending 0");
            }
            require(android.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM sync_link_projection", null) == 0, "the link projection table exists and is empty");
            require(android.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'sync_link_records_%'", null) == 3, "insert, update and delete triggers exist");
        } finally { second.close(); isolated.deleteDatabase(name); }
        mark.accept("Link sync: schema 12 to 13 keeps link rows, adds digest columns, preview_pending, sync_link_projection and three triggers");
    }

    // ---- capture, triggers, lifecycle-only writes --------------------------------------------------------------------

    private static void captureAndTriggers(Instrumentation test, Consumer<String> mark) throws Exception {
        Phone p = new Phone(test, "ls-capture");
        try {
            String a = UUID.randomUUID().toString();
            String id = p.publish(a, "Cardiology", hash('1'), "Dr Patel metformin", "page one", false);
            // Links are not enabled yet: nothing is captured, and the flag is simply cleared by the capture.
            int ownBefore = p.ownOps(); p.store.capture();
            require(p.ownOps() == ownBefore && p.count("SELECT count(*) FROM sync_link_projection") == 0, "nothing is captured before this device enabled links");
            require(!LinkSync.dirty(p.db), "the dirty flag is cleared by a capture while links are off");
            // Zero-link style enable with an existing row: metadata only, marker cell, never text or preview.
            require(p.store.enableLinks(), "the phone's own enable operation is written");
            SyncProtocol.Operation enable = p.lastOwn();
            require("links.enable".equals(evidence(enable)) && enable.changes.has(SyncGraph.linkMarkerKey(p.replica())), "the enable operation carries the marker");
            boolean blobCell = false; for (String k : SyncProtocol.keys(enable.changes)) if (k.endsWith("/text") || k.endsWith("/preview")) blobCell = true;
            require(!blobCell && enable.changes.has(key(a)) && enable.changes.has(key(a) + "/content") && enable.changes.has(key(a) + "/presence"), "the enable operation is metadata only (identity, content, title, filen, presence)");
            require(LinkSync.enabled(p.state(), p.replica()) && !p.store.enableLinks(), "enabled is derived from the operation and written once");
            // The blobs follow in the next capture.
            p.store.capture();
            SyncProtocol.Operation captured = p.lastOwn();
            require("link.capture".equals(evidence(captured)) && captured.changes.has(key(a) + "/text") && captured.changes.has(key(a) + "/preview") && captured.changes.length() == 2, "text and preview follow the metadata-only enable, and only they");
            String textSha = captured.changes.getJSONObject(key(a) + "/text").getString("sha256"), previewSha = captured.changes.getJSONObject(key(a) + "/preview").getString("sha256");
            File kept = new File(p.context.getFilesDir(), "sync_blobs/" + previewSha), text = new File(p.context.getFilesDir(), "sync_blobs/" + textSha);
            require(kept.isFile() && LinkSync.hash(kept, () -> { }).equals(previewSha) && text.isFile() && LinkSync.hash(text, () -> { }).equals(textSha), "both blobs were copied into sync_blobs and verify");
            require(captured.changes.getJSONObject(key(a) + "/text").getLong("length") == "Dr Patel metformin".getBytes(StandardCharsets.UTF_8).length, "the text length is UTF-8 bytes");
            // H-D: an in-place rewrite of the live preview (the LinkStore copy fallback) must not change the kept blob.
            try (FileOutputStream rewrite = new FileOutputStream(p.links.preview(id))) { rewrite.write("%PDF-1.7\nrewritten in place".getBytes(StandardCharsets.US_ASCII)); }
            require(LinkSync.hash(kept, () -> { }).equals(previewSha), "the kept blob is a copy: rewriting the live preview does not change it");
            LinkSync.markDirty(p.db); p.store.capture();                      // the digest no longer matches the file: it is recomputed and the new bytes are kept
            SyncProtocol.Operation changedPreview = p.lastOwn();
            require(changedPreview.changes.length() == 1 && !changedPreview.changes.getJSONObject(key(a) + "/preview").getString("sha256").equals(previewSha), "a preview changed behind the digest is rehashed and re-emitted");
            require(p.links.preview(id).length() == p.count("SELECT preview_len FROM link_records WHERE id=?", id), "the stored digest follows the file");

            // Lifecycle-only writes are not captured (LinkChecker applyCheckDetailed, state_since, revision, checked_at).
            p.store.capture(); int ops = p.ownOps(); long changes = p.count("SELECT count(*) FROM sync_change WHERE table_name='link_records'");
            long revision = p.links.revision(id);
            require(p.links.applyCheckDetailed(id, revision, new LinkStore.Outcome(LinkStore.LIVE, null, -1, hash('1'))) == 1, "a check applies");
            require(p.links.applyCheckDetailed(id, revision, new LinkStore.Outcome(LinkStore.MISSING, null, -1)) == 1, "a check that finds the file missing applies");
            p.db.execSQL("UPDATE link_records SET checked_at=checked_at+1, state_since=state_since+1, revision=revision+1, received_at=received_at+1 WHERE id=?", new Object[]{id});
            p.db.execSQL("UPDATE link_records SET state='unreachable' WHERE id=?", new Object[]{id});
            require(p.count("SELECT count(*) FROM sync_change WHERE table_name='link_records'") == changes && !LinkSync.dirty(p.db), "checked_at, state_since, revision and live/unreachable/missing writes fire no trigger");
            p.store.capture();
            require(p.ownOps() == ops, "no operation for lifecycle-only writes");
            // Real changes are captured: rename, trash, restore.
            p.links.rename(id, "Cardiology notes"); p.store.capture();
            require(p.lastOwn().changes.length() == 1 && p.lastOwn().changes.getJSONObject(key(a) + "/title").getInt("title_user_edited") == 1, "a rename is captured as the title cell only");
            p.links.trash(id, "user"); p.store.capture();
            JSONObject presence = p.lastOwn().changes.getJSONObject(key(a) + "/presence");
            require(presence.getString("state").equals("trashed") && presence.getString("reason").equals("user") && presence.getLong("since") > 0, "trash becomes presence trashed with its reason and since");
            p.links.restore(id); p.store.capture();
            presence = p.lastOwn().changes.getJSONObject(key(a) + "/presence");
            require(presence.getString("state").equals("live") && presence.getLong("since") == 0, "restore becomes presence live with since 0");
            p.links.delete(id); p.store.capture();
            SyncProtocol.Operation gone = p.lastOwn();
            require(gone.changes.getJSONObject(key(a) + "/presence").getString("state").equals("gone") && nullCell(gone, key(a) + "/content") && nullCell(gone, key(a) + "/text") && nullCell(gone, key(a) + "/preview")
                && !gone.changes.has(key(a)), "delete becomes gone: presence gone, content/text/preview null, the identity cell is never nulled");
            // A document edit emits no link change and the link projection is kept (C1).
            long projection = p.count("SELECT count(*) FROM sync_link_projection");
            p.repo.importDocument(android.net.Uri.fromFile(OcrQueueChecks.fixture(p.context, "doc.pdf")));
            p.store.capture();
            require(!hasLinkKey(p.lastOwn()) && p.count("SELECT count(*) FROM sync_link_projection") == projection, "a document edit emits no link change and keeps the link projection");
            String saved = p.text("SELECT projection FROM sync_state WHERE singleton=1");
            require(!saved.contains("link_records/"), "the document projection never holds link cells");
            // Capture runs when the flag is up although the journal shows nothing new (not gated by the cursor).
            String b = UUID.randomUUID().toString(); p.publish(b, "Second", hash('2'), "words", null, false); p.store.capture();
            p.db.execSQL("UPDATE sync_control SET enabled=0 WHERE singleton=1");                      // silence the triggers on purpose
            p.db.execSQL("UPDATE link_records SET title='Renamed quietly' WHERE link_id=?", new Object[]{b});
            p.db.execSQL("UPDATE sync_control SET enabled=1 WHERE singleton=1");
            long cursor = p.count("SELECT journal_cursor FROM sync_state WHERE singleton=1"), top = p.count("SELECT ifnull(max(sequence),0) FROM sync_change");
            require(cursor == top, "the journal cursor is at the top, so only the dirty flag can start capture");
            LinkSync.markDirty(p.db); p.store.capture();
            require(p.lastOwn().changes.has(key(b) + "/title"), "link capture runs on the flag alone");
        } finally { p.close(); }
        mark.accept("Link sync: enable is metadata-only with a marker, blobs follow; copies not hard links; lifecycle-only writes not captured; rename/trash/restore/gone captured; document edits keep the link projection");
    }

    // ---- enable through the hello gate -------------------------------------------------------------------------------

    private static void enableAndHello(Instrumentation test, Consumer<String> mark) throws Exception {
        Phone p = new Phone(test, "ls-hello");
        try {
            PairingTransport transport = new PairingTransport(p.context, p.repo, "stack-checks-" + UUID.randomUUID());
            int ops = p.ownOps();
            transport.onPeerHello(new HelloGate.Peer(true, false, null), true);
            require(p.ownOps() == ops && !LinkSync.enabled(p.state(), p.replica()), "a peer that does not announce links creates nothing");
            transport.onPeerHello(new HelloGate.Peer(true, true, false));      // L3: the phone itself does not announce (ANNOUNCE_LINKS is false), so a real session never enables
            require(p.ownOps() == ops && !LinkSync.enabled(p.state(), p.replica()) && !PairingTransport.announcesLinks(), "the phone enables only when it announces links too");
            transport.onPeerHello(new HelloGate.Peer(true, true, false), true);      // the fake announcing peer; no links at all: the marker alone keeps the operation valid
            SyncProtocol.Operation enable = p.lastOwn();
            require(p.ownOps() == ops + 1 && enable.changes.length() == 1 && enable.changes.has(SyncGraph.linkMarkerKey(p.replica())), "a zero-link enable is a marker-only operation");
            transport.onPeerHello(new HelloGate.Peer(true, true, false), true);
            require(p.ownOps() == ops + 1, "the enable operation is written once");
            LinkSync.forget();   // L5: what a process restart (or a replaced database) does to the cache
            require(LinkSync.enabled(p.state(), p.replica()), "the enabled state is derived from history again after the cache is forgotten");
        } finally { p.close(); }
        mark.accept("Link sync: the hello listener writes a marker-only enable once, only for a peer that announces links");
    }

    // ---- receiving: desktop-only, update, trashed -> live, gone, local locators --------------------------------------------

    private static void receiveAndApply(Instrumentation test, Consumer<String> mark) throws Exception {
        Phone p = new Phone(test, "ls-apply");
        try {
            String shared = UUID.randomUUID().toString(), only = UUID.randomUUID().toString(), dead = UUID.randomUUID().toString(), conflicted = UUID.randomUUID().toString();
            String sharedId = p.publish(shared, "Shared", hash('3'), "phone text", "phone preview", false);
            String conflictId = p.publish(conflicted, "Conflicted", hash('4'), "phone words", null, false);
            Desk desk = new Desk(p);
            byte[] deskPreview = "%PDF-1.7\ndesktop preview".getBytes(StandardCharsets.US_ASCII);
            // The announcing bundle arrives while the phone has NOT enabled: the phone writes its own enable first (concurrent with the desktop's).
            JSONObject enableLinks = desk.link(only, hash('5'), 9L, "Desktop only", "desktop text", deskPreview, false, "/Docs/only.sam");
            JSONObject deadCells = SyncGraph.linkChanges(SyncGraph.linkWriter(new JSONObject().put("owner", OWNER).put("link_id", dead).put("kind", "doc").put("added_at", 6L)
                .put("presence", new JSONObject().put("state", "gone").put("reason", "").put("since", 7L)), 0));
            JSONObject diff = desk.link(conflicted, hash('6'), 1_900_000_000_000L, "Conflicted on desktop", null, null, false, "/Docs/c.sam");
            SyncProtocol.Operation deskEnable = desk.enable(enableLinks, deadCells, diff);
            int before = p.ownOps();
            JSONObject summary = desk.deliver(p);
            require(summary.getInt("applied") == 1, "the desktop's enable operation was applied");
            SyncProtocol.State state = p.state();
            SyncProtocol.Operation phoneEnable = null; for (SyncProtocol.Operation op : state.history.values()) if (op.replica.equals(p.replica()) && "links.enable".equals(evidence(op))) phoneEnable = op;
            require(phoneEnable != null && p.ownOps() == before + 1 && state.concurrent(phoneEnable, deskEnable), "the phone's enable was written before receiving, so it is concurrent with the desktop's");

            // Desktop-only link: trashed, reason user (never live), revision 1, text and preview placed, projection holds the received cells.
            require("trashed".equals(p.text("SELECT state FROM link_records WHERE link_id=?", only)) && "user".equals(p.text("SELECT removed_reason FROM link_records WHERE link_id=?", only)) && p.count("SELECT revision FROM link_records WHERE link_id=?", only) == 1, "a desktop-only link arrives trashed with reason user");
            String onlyId = p.text("SELECT id FROM link_records WHERE link_id=?", only);
            require(p.links.text(onlyId).equals("desktop text") && p.links.preview(onlyId) != null && p.count("SELECT preview_pending FROM link_records WHERE id=?", onlyId) == 0 && p.text("SELECT preview_file FROM link_records WHERE id=?", onlyId).equals(onlyId + ".pdf"), "its text is searchable and its preview was placed after the commit");
            require(p.links.list("desktop").isEmpty() && p.links.trashed().size() == 1, "it is in Recently deleted, not in the list");
            p.links.restore(onlyId);
            require("unreachable".equals(p.text("SELECT state FROM link_records WHERE id=?", onlyId)), "restore gives unreachable");
            require(p.count("SELECT count(*) FROM link_records WHERE link_id=?", dead) == 0, "an identity whose presence is gone is never inserted");
            require(p.count("SELECT count(*) FROM sync_link_projection WHERE key LIKE ?", key(only) + "%") > 0 && !p.text("SELECT projection FROM sync_state WHERE singleton=1").contains("link_records/"), "install rewrote the link projection; the document projection has no link cells");
            // The conflicting link (different hash, both sides wrote): the desktop's modified is later, so P2 rule 2 applies the desktop cells.
            require(p.text("SELECT hash FROM link_records WHERE link_id=?", conflicted).equals(hash('6')) && "Conflicted on desktop".equals(p.text("SELECT title FROM link_records WHERE link_id=?", conflicted)), "P2: the later modified wins all cells of the link");
            require(LinkSync.conflictedKeys(p.state()).isEmpty() && SyncStore.documentConflicts(p.state()).length() == 0, "the resolved link leaves no conflict, and link keys are kept out of the document conflicts");
            // The next capture publishes presence for the desktop-only link (restored: live), and nothing for the rest.
            p.store.capture();
            SyncProtocol.Operation next = p.lastOwn();
            require(next.changes.has(key(only) + "/presence") && !next.changes.has(key(only) + "/title"), "capture publishes the presence of the desktop-only link and does not re-send what was received");

            // An ordinary successor update from the desktop (it has seen the phone's cells): applied to the existing row.
            desk.learn(p);
            byte[] newer = "%PDF-1.7\nnewer desktop preview".getBytes(StandardCharsets.US_ASCII);
            long revision = p.links.revision(sharedId);
            JSONObject update = desk.link(shared, hash('7'), 1_900_000_000_001L, "Shared (desktop)", "newer text", newer, false, "/Docs/shared-new.sam");
            desk.write(update, "test.update");
            // A LinkChecker write based on the old revision must lose after the apply.
            desk.deliver(p);
            require(p.text("SELECT hash FROM link_records WHERE id=?", sharedId).equals(hash('7')) && p.links.text(sharedId).equals("newer text") && p.links.revision(sharedId) > revision, "an existing row follows the received content and bumps revision");
            require(p.links.applyCheckDetailed(sharedId, revision, new LinkStore.Outcome(LinkStore.LIVE, null, -1)) == 0, "an in-flight check based on the old revision is rejected");
            String locators = p.text("SELECT locators FROM link_records WHERE id=?", sharedId).replace("\\/", "/");   // Android org.json escapes slashes
            require(locators.contains("content://stub/" + shared) && locators.contains("shared-new.sam") && !locators.contains("Shared.sam"), "the local saf locator is kept and the filen locator is replaced");
            require(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(p.links.preview(sharedId).toPath()), newer), "the received preview replaced the old one");
            // trashed -> live from the desktop side: the phone trashed the row, the desktop writes presence live later.
            p.links.trash(sharedId, "user"); p.store.capture(); desk.learn(p);
            desk.write(new JSONObject().put(key(shared) + "/presence", new JSONObject().put("state", "live").put("reason", "").put("since", 0)), "test.presence");
            desk.deliver(p);
            require("unreachable".equals(p.text("SELECT state FROM link_records WHERE id=?", sharedId)) && p.text("SELECT removed_reason FROM link_records WHERE id=?", sharedId).isEmpty(), "presence live brings a trashed row back (unreachable)");
            // A received gone never deletes a phone row.
            desk.learn(p);
            desk.write(new JSONObject().put(key(shared) + "/presence", new JSONObject().put("state", "gone").put("reason", "").put("since", 99)), "test.gone");
            desk.deliver(p);
            require(p.count("SELECT count(*) FROM link_records WHERE id=?", sharedId) == 1, "a received gone never removes a phone row");
        } finally { p.close(); }
        mark.accept("Link sync: desktop-only links arrive trashed/user, gone identities are never inserted, successor updates apply with revision bump and kept local locators, trashed->live, received gone ignored, conflicts left alone");
    }

    // ---- LinkStore.applySynced paths on their own ---------------------------------------------------------------------

    private static void applySyncedDirect(Instrumentation test, Consumer<String> mark) throws Exception {
        Phone p = new Phone(test, "ls-direct");
        try {
            File blobs = new File(p.context.getFilesDir(), "sync_blobs"); blobs.mkdirs();
            byte[] textBytes = "hello from sync".getBytes(StandardCharsets.UTF_8), pdf = "%PDF-1.7\nsynced".getBytes(StandardCharsets.US_ASCII);
            String textSha = hex(textBytes), pdfSha = hex(pdf);
            try (FileOutputStream o = new FileOutputStream(new File(blobs, textSha))) { o.write(textBytes); }
            try (FileOutputStream o = new FileOutputStream(new File(blobs, pdfSha))) { o.write(pdf); }
            Desk desk = new Desk(p);
            // 1: a link with its own presence cell (live) arrives unreachable, not trashed.
            String live = UUID.randomUUID().toString();
            JSONObject cells = desk.link(live, hash('8'), 3L, "Has presence", "hello from sync", pdf, false, "/D/p.sam");
            cells.put(key(live) + "/presence", new JSONObject().put("state", "live").put("reason", "").put("since", 0));
            LinkStore.Applied applied = p.links.applySynced(p.db, SyncGraph.projectLinks(allCells(cells), null).getJSONArray("links"), blobs);
            require(applied.inserted == 1 && "unreachable".equals(p.text("SELECT state FROM link_records WHERE link_id=?", live)), "a new row with presence live arrives unreachable");
            String liveId = p.text("SELECT id FROM link_records WHERE link_id=?", live);
            require(p.count("SELECT preview_pending FROM link_records WHERE id=?", liveId) == 1 && p.links.preview(liveId) == null, "the preview is only marked pending inside the transaction");
            p.links.afterApply(applied, blobs);
            require(p.links.preview(liveId) != null && p.count("SELECT preview_pending FROM link_records WHERE id=?", liveId) == 0, "the preview is placed after the commit");
            require(p.count("SELECT applying FROM sync_control WHERE singleton=1") == 0, "the applying flag is restored");
            // 2: protected links apply metadata only, even if a text cell was sent.
            String prot = UUID.randomUUID().toString();
            JSONObject protectedCells = desk.link(prot, hash('9'), 3L, "Secret", null, null, true, "/D/s.sam");
            protectedCells.put(key(prot) + "/text", new JSONObject().put("sha256", textSha).put("length", textBytes.length));
            applied = p.links.applySynced(p.db, SyncGraph.projectLinks(allCells(protectedCells), null).getJSONArray("links"), blobs);
            String protId = p.text("SELECT id FROM link_records WHERE link_id=?", prot);
            require(applied.inserted == 1 && p.links.text(protId).isEmpty() && p.text("SELECT text_sha FROM link_records WHERE id=?", protId).isEmpty(), "a protected link is applied without text or preview");
            // 3: a missing blob skips the link and stores nothing; a link without a filen locator is kept trashed with a placeholder (M4).
            String missing = UUID.randomUUID().toString();
            JSONObject missingCells = desk.link(missing, hash('a'), 3L, "No blob", "text that is not there", null, false, "/D/m.sam");
            applied = p.links.applySynced(p.db, SyncGraph.projectLinks(allCells(missingCells), null).getJSONArray("links"), blobs);
            require(applied.skipped == 1 && p.count("SELECT count(*) FROM link_records WHERE link_id=?", missing) == 0, "a missing text blob skips the link");
            String bare = UUID.randomUUID().toString();
            JSONObject bareCells = desk.link(bare, hash('b'), 3L, "No filen", null, null, false, null);
            applied = p.links.applySynced(p.db, SyncGraph.projectLinks(allCells(bareCells), null).getJSONArray("links"), blobs);
            // M4: a link only the computer has, with no filen cell, is inserted trashed (reason user) with the inert placeholder locator.
            require(applied.inserted == 1 && "trashed".equals(p.text("SELECT state FROM link_records WHERE link_id=?", bare)) && "user".equals(p.text("SELECT removed_reason FROM link_records WHERE link_id=?", bare))
                && LinkBudget.PLACEHOLDER_LOCATORS.equals(p.text("SELECT locators FROM link_records WHERE link_id=?", bare)), "a link without a filen locator arrives trashed with the placeholder locator");
            // 4: gone is never inserted and never deletes.
            JSONObject goneCells = SyncGraph.linkChanges(SyncGraph.linkWriter(new JSONObject().put("owner", OWNER).put("link_id", live).put("kind", "doc").put("added_at", 1L)
                .put("presence", new JSONObject().put("state", "gone").put("reason", "").put("since", 5L)), 0));
            applied = p.links.applySynced(p.db, SyncGraph.projectLinks(allCells(goneCells), null).getJSONArray("links"), blobs);
            require(applied.ignoredGone == 1 && p.count("SELECT count(*) FROM link_records WHERE link_id=?", live) == 1, "a received gone leaves the row");
            // 5: the triggers stayed silent for every applied row.
            require(p.count("SELECT count(*) FROM sync_change WHERE table_name='link_records'") == 0 && !LinkSync.dirty(p.db), "applySynced never fires the capture triggers");
        } finally { p.close(); }
        mark.accept("Link sync: applySynced inserts unreachable/trashed, defers preview placement, applies protected metadata only, skips missing blobs, keeps filen-less links trashed with a placeholder, ignores gone, keeps the triggers silent");
    }

    private static JSONObject allCells(JSONObject changes) throws Exception {
        JSONObject out = new JSONObject();
        for (String k : SyncProtocol.keys(changes)) out.put(k, changes.get(k));
        return out;
    }

    // ---- digests, blob copy order, protected, long text, export --------------------------------------------------------

    private static void digestsAndBlobs(Instrumentation test, Consumer<String> mark) throws Exception {
        Phone p = new Phone(test, "ls-blobs");
        try {
            String a = UUID.randomUUID().toString(), prot = UUID.randomUUID().toString(), big = UUID.randomUUID().toString();
            String id = p.publish(a, "Digest", hash('c'), "first text", "first preview", false);
            String protId = p.publish(prot, "Protected", hash('d'), "must not sync", "must not sync either", true);
            String bigId = p.publish(big, "Long text", hash('e'), "€".repeat(400_000), null, false);   // 400,000 UTF-16 units, 1,200,000 UTF-8 bytes
            p.store.enableLinks(); p.store.capture();
            SyncProtocol.Operation cap = p.lastOwn();
            require(p.text("SELECT text_sha FROM link_records WHERE id=?", id).length() == 64 && p.text("SELECT preview_sha FROM link_records WHERE id=?", id).length() == 64, "capture stores the digests");
            require(!cap.changes.has(key(prot) + "/text") && !cap.changes.has(key(prot) + "/preview"), "a protected link syncs metadata only");
            require(!cap.changes.has(key(big) + "/text"), "text over 1,048,576 UTF-8 bytes skips the text cell only");
            boolean metadata = false; for (SyncProtocol.Operation op : p.state().history.values()) if (op.replica.equals(p.replica()) && "links.enable".equals(evidence(op)) && op.changes.has(key(big) + "/content")) metadata = true;
            require(metadata, "the long-text link still syncs its metadata");
            // publish resets the digests.
            p.publish(a, "Digest", hash('f'), "second text", "second preview", false);
            require(p.text("SELECT text_sha FROM link_records WHERE id=?", id).isEmpty() && p.count("SELECT preview_len FROM link_records WHERE id=?", id) == 0, "a publish with new content resets the digests");
            // C2: the earlier operation's preview blob is still exportable after the live preview changed, and export reads sync_blobs only.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            p.store.capture();
            new SyncExchange(p.context, p.repo).export(p.desktop, out);
            File live = p.links.preview(id); require(live != null && live.delete(), "remove the live preview");
            out.reset(); new SyncExchange(p.context, p.repo).export(p.desktop, out);
            try (SyncExchange.Staged staged = new SyncExchange(p.context, p.repo).stage(new ByteArrayInputStream(out.toByteArray()))) {
                require(staged.header.getJSONArray("blobs").length() >= 2, "the exported bundle carries the link blobs, read from sync_blobs although the live preview is gone");
            }
            // Blob keep order: a failing copy leaves nothing behind; an orphan (copied, never committed) is harmless.
            LinkSync sync = p.store.linkSync();
            byte[] bytes = "orphan blob".getBytes(StandardCharsets.UTF_8); String sha = hex(bytes);
            try { sync.keep(hex("other".getBytes(StandardCharsets.UTF_8)), bytes.length, new ByteArrayInputStream(bytes)); throw new AssertionError("wrong digest must fail"); } catch (IOException expected) { /* nothing kept */ }
            File dir = new File(p.context.getFilesDir(), "sync_blobs"); String[] names = dir.list();
            boolean part = false; if (names != null) for (String n : names) if (n.endsWith(".part")) part = true;
            require(!part && !new File(dir, hex("other".getBytes(StandardCharsets.UTF_8))).exists(), "a failed copy leaves no blob and no temp file");
            sync.keep(sha, bytes.length, new ByteArrayInputStream(bytes));
            require(new File(dir, sha).isFile() && LinkSync.hash(new File(dir, sha), () -> { }).equals(sha), "a kept copy is fsynced, renamed and verifies");
            // A capture whose transaction never commits may leave copied blobs behind: harmless, and the next capture succeeds.
            p.store.capture();
            String orphan = UUID.randomUUID().toString(); p.publish(orphan, "Orphan", hash('0'), "orphan text", "orphan preview", false);
            int ops = p.ownOps(); SyncProtocol.State scratch = p.state();
            p.db.beginTransaction();
            try { p.store.linkSync().capture(scratch, p.replica(), new TreeMap<>()); } finally { p.db.endTransaction(); }   // rolled back
            String orphanSha = hex("orphan text".getBytes(StandardCharsets.UTF_8));
            require(new File(dir, orphanSha).isFile() && p.ownOps() == ops && p.count("SELECT count(*) FROM sync_link_projection WHERE key LIKE ?", key(orphan) + "%") == 0, "after a rolled-back capture the blob exists but no operation or projection row does");
            p.store.capture();
            require(p.ownOps() == ops + 1 && p.lastOwn().changes.getJSONObject(key(orphan) + "/text").getString("sha256").equals(orphanSha), "the next capture succeeds with the same blob");
        } finally { p.close(); }
        mark.accept("Link sync: digests reset on publish and rehash on mismatch, protected metadata-only, long text skips its cell, export reads sync_blobs, blob copy verifies, orphan blobs are harmless");
    }

    // ---- reset ----------------------------------------------------------------------------------------------------------

    private static void resetClears(Instrumentation test, Consumer<String> mark) throws Exception {
        Phone p = new Phone(test, "ls-reset");
        try {
            String a = UUID.randomUUID().toString();
            p.publish(a, "Reset me", hash('1'), "text", null, false);
            p.store.enableLinks(); p.store.capture();
            require(p.count("SELECT count(*) FROM sync_link_projection") > 0 && LinkSync.enabled(p.state(), p.replica()), "projection and enabled state exist before the reset");
            String old = p.replica();
            java.lang.reflect.Method method = LibraryReset.class.getDeclaredMethod("resetConnection", SQLiteDatabase.class, String.class); method.setAccessible(true);
            String fresh = UUID.randomUUID().toString(); method.invoke(null, p.db, fresh);
            require(p.count("SELECT count(*) FROM sync_link_projection") == 0 && p.count("SELECT count(*) FROM sync_state") == 0 && p.count("SELECT count(*) FROM link_records") == 1, "a connection reset clears the link projection and deletes no link row");
            require(p.replica().equals(fresh) && !old.equals(fresh) && !LinkSync.dirty(p.db), "the reset gives a fresh replica, so links are not enabled any more");
        } finally { p.close(); }
        mark.accept("Link sync: connection reset clears sync_link_projection, deletes no link row, and the derived enabled state starts over");
    }

    // ---- P1 review fixes (Opus review, 2026-11-22) -------------------------------------------------------------------------

    private static File blobDir(Phone p) { File dir = new File(p.context.getFilesDir(), "sync_blobs"); dir.mkdirs(); return dir; }

    /** H1 (text over a CursorWindow), H2 (enable falls back to the marker), L5. */
    private static void reviewTextAndEnable(Instrumentation test, Consumer<String> mark) throws Exception {
        Phone p = new Phone(test, "ls-h1");
        try {
            String big = UUID.randomUUID().toString(), small = UUID.randomUUID().toString();
            // 1,000,000 euro signs are 1,000,000 UTF-16 units (within the 1 MiB-unit publish limit) but 3,000,000 UTF-8 bytes: more than a 2 MB CursorWindow.
            String bigId = p.publish(big, "Huge", hash('1'), "\u20ac".repeat(1_000_000), null, false);
            String smallId = p.publish(small, "Small", hash('2'), "small text", "small preview", false);
            require(p.count("SELECT length(CAST(body AS BLOB)) FROM link_search WHERE record_id=?", bigId) > 2L * 1024 * 1024, "the fixture text is over a CursorWindow");
            p.store.enableLinks(); p.store.capture();
            boolean bigMetadata = false, bigText = false, smallText = false, smallPreview = false;
            for (SyncProtocol.Operation op : p.linkOps()) {
                bigMetadata |= op.changes.has(key(big) + "/content") && op.changes.has(key(big) + "/presence");
                bigText |= op.changes.has(key(big) + "/text");
                smallText |= op.changes.has(key(small) + "/text"); smallPreview |= op.changes.has(key(small) + "/preview");
            }
            require(bigMetadata && !bigText, "a text over 1,048,576 UTF-8 bytes skips only its own text cell, metadata and presence are written");
            require(smallText && smallPreview, "the other links still get text and preview in the same capture");
            int ops = p.ownOps(); p.store.capture();
            require(p.ownOps() == ops && !LinkSync.dirty(p.db), "nothing repeats at the next capture");
        } finally { p.close(); }

        Phone q = new Phone(test, "ls-h2");
        try {
            String a = UUID.randomUUID().toString();
            q.publish(a, "Enable me", hash('3'), "text", null, false);
            // A projection row that cannot be read makes the plan fail; the enable must still be written, marker only.
            q.db.execSQL("INSERT INTO sync_link_projection(key,value) VALUES(?, '{')", new Object[]{"link_records/[\"x\",\"y\"]"});
            int ops = q.ownOps();
            require(q.store.enableLinks(), "enable is written although planning the link cells failed");
            SyncProtocol.Operation enable = q.lastOwn();
            require(q.ownOps() == ops + 1 && "links.enable".equals(evidence(enable)) && enable.changes.length() == 1 && enable.changes.has(SyncGraph.linkMarkerKey(q.replica())), "the fallback enable carries the marker alone");
            require(LinkSync.dirty(q.db) && LinkSync.enabled(q.state(), q.replica()), "the dirty flag is up so capture publishes the cells later");
            q.db.execSQL("DELETE FROM sync_link_projection");
            q.store.capture();
            require(q.lastOwn().changes.has(key(a) + "/content") && q.lastOwn().changes.has(key(a) + "/text"), "the next capture writes the link cells");
        } finally { q.close(); }
        mark.accept("Link sync review: text over a CursorWindow skips only its cell, enable falls back to the marker alone, enabled state survives forget()");
    }

    /** M1 (export with a lost blob), M3 (byte cap, restart-proof preview check, receipt flag), L1 (digest with two lengths). */
    private static void reviewExportAndCaps(Instrumentation test, Consumer<String> mark) throws Exception {
        Phone p = new Phone(test, "ls-m1");
        try {
            String a = UUID.randomUUID().toString();
            String id = p.publish(a, "Lost blob", hash('4'), "kept text", "kept preview", false);
            p.store.enableLinks(); p.store.capture();
            File blobs = blobDir(p);
            require(new File(blobs, hex("kept text".getBytes(StandardCharsets.UTF_8))).delete(), "lose one kept blob");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            SyncExchange exchange = new SyncExchange(p.context, p.repo);
            exchange.export(p.desktop, out);
            require(exchange.skippedLinkBlobs == 1, "the lost link blob is counted and skipped, the export still works");
            try (SyncExchange.Staged staged = exchange.stage(new ByteArrayInputStream(out.toByteArray()))) {
                require(staged.header.getJSONArray("blobs").length() == 1, "only the blob that is kept is in the bundle");
            }

            // M3: the byte cap defers (nothing is written for a blob that does not fit), and a restart does not re-hash a verified preview.
            LinkSync sync = p.store.linkSync();
            byte[] bytes = "0123456789A".getBytes(StandardCharsets.UTF_8);
            try { sync.keep(hex(bytes), bytes.length, new ByteArrayInputStream(bytes), new LinkBudget.Meter(10)); throw new AssertionError("over the cap must defer"); } catch (LinkSync.Deferred expected) { /* retried later */ }
            require(!new File(blobs, hex(bytes)).exists(), "a deferred blob is not copied");
            File preview = p.links.preview(id); long mtime = preview.lastModified();
            require(mtime != 0 && p.count("SELECT preview_mtime FROM link_records WHERE id=?", id) == mtime, "capture stores the modified time next to the verified digest");
            String sha = p.text("SELECT preview_sha FROM link_records WHERE id=?", id);
            try (FileOutputStream o = new FileOutputStream(preview)) { o.write("%PDF-1.7\nkept PREVIEW".getBytes(StandardCharsets.US_ASCII)); }   // other bytes, same length
            require(preview.setLastModified(mtime), "restore the time");
            LinkSync.forget(); LinkSync.markDirty(p.db); p.store.capture();   // "after a restart"
            require(p.text("SELECT preview_sha FROM link_records WHERE id=?", id).equals(sha), "a restart trusts the stored digest while length and modified time match (no re-hash)");
            // A different modified time means the file is hashed again.
            require(preview.setLastModified(mtime + 5000), "change the time");
            LinkSync.markDirty(p.db); p.store.capture();
            require(!p.text("SELECT preview_sha FROM link_records WHERE id=?", id).equals(sha), "a changed file is hashed again");
            // The dirty flag is raised by a receipt only while a deferral waits.
            LinkSync.clearDirty(p.db); LinkSync.setDeferred(p.db, false);
            p.store.receipt(p.desktop, new TreeMap<>());
            require(!LinkSync.dirty(p.db), "a receipt with nothing deferred does not raise the dirty flag");
            LinkSync.setDeferred(p.db, true);
            p.store.receipt(p.desktop, new TreeMap<>());
            require(LinkSync.dirty(p.db), "a receipt raises it while a deferral is outstanding");
            p.store.capture();
            require(!LinkSync.deferralOutstanding(p.db), "a capture with nothing deferred clears the outstanding deferral");
        } finally { p.close(); }

        // L1: one digest declared with two lengths is tolerated (import and export), the real one is sent.
        Phone q = new Phone(test, "ls-l1");
        try {
            Desk desk = new Desk(q);
            byte[] pdf = "%PDF-1.7\ntwo lengths".getBytes(StandardCharsets.US_ASCII);
            String one = UUID.randomUUID().toString(), two = UUID.randomUUID().toString();
            JSONObject first = desk.link(one, hash('5'), 3L, "Honest", null, pdf, false, "/D/one.sam");
            JSONObject second = desk.link(two, hash('6'), 3L, "Odd", null, pdf, false, "/D/two.sam");
            second.put(key(two) + "/preview", new JSONObject().put("sha256", hex(pdf)).put("length", pdf.length + 1));
            desk.enable(first, second);
            desk.deliver(q);   // used to throw "two lengths" in the import check
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new SyncExchange(q.context, q.repo).export(q.desktop, out);
            require(q.count("SELECT count(*) FROM link_records WHERE link_id=?", one) == 1, "the link with the honest length was applied");
        } finally { q.close(); }
        mark.accept("Link sync review: export skips a lost link blob, byte cap defers, verified preview survives a restart, receipt flag only with a deferral, two declared lengths tolerated");
    }

    /** M2 (protected link keeps its text), M4 (placeholder locator), L7 (a bad link never fails the outer transaction). */
    private static void reviewApply(Instrumentation test, Consumer<String> mark) throws Exception {
        Phone p = new Phone(test, "ls-m2");
        try {
            File blobs = blobDir(p);
            String prot = UUID.randomUUID().toString(), becomes = UUID.randomUUID().toString();
            String protId = p.publish(prot, "Protected", hash('7'), "my own text", "my own preview", true);
            String becomesId = p.publish(becomes, "Open", hash('8'), "open text", null, false);
            Desk desk = new Desk(p);
            // The computer only changes the filen path of the protected link (same hash): text, preview and digests stay.
            JSONObject cells = desk.link(prot, hash('7'), 1_800_000_000_000L, "Protected", null, null, true, "/New/place.sam");
            String sha = p.text("SELECT text_sha FROM link_records WHERE id=?", protId);
            LinkStore.Applied applied = p.links.applySynced(p.db, SyncGraph.projectLinks(allCells(cells), null).getJSONArray("links"), blobs);
            p.links.afterApply(applied, blobs);
            require(applied.updated == 1 && p.links.text(protId).equals("my own text") && p.links.preview(protId) != null && p.text("SELECT locators FROM link_records WHERE id=?", protId).replace("\\/", "/").contains("/New/place.sam"), "a metadata-only change keeps the phone's text and preview");
            require(sha != null && sha.equals(p.text("SELECT text_sha FROM link_records WHERE id=?", protId)), "and the digests");
            // A link that BECOMES protected loses them.
            JSONObject nowProtected = desk.link(becomes, hash('8'), 1_800_000_000_000L, "Open", null, null, true, "/New/open.sam");
            applied = p.links.applySynced(p.db, SyncGraph.projectLinks(allCells(nowProtected), null).getJSONArray("links"), blobs);
            p.links.afterApply(applied, blobs);
            require(p.links.text(becomesId).isEmpty(), "a link that becomes protected loses its text");

            // M4: a link with no filen cell is kept trashed (user) with the placeholder; backups accept it, capture sends no filen, the checker ignores it.
            String bare = UUID.randomUUID().toString();
            applied = p.links.applySynced(p.db, SyncGraph.projectLinks(allCells(desk.link(bare, hash('9'), 3L, "No filen", null, null, false, null)), null).getJSONArray("links"), blobs);
            String bareId = p.text("SELECT id FROM link_records WHERE link_id=?", bare);
            require(applied.inserted == 1 && bareId != null && "trashed".equals(p.text("SELECT state FROM link_records WHERE id=?", bareId)), "the filen-less link is inserted trashed");
            LinkArchive.validate(LinkArchive.capture(p.db));   // a backup accepts the placeholder locator (throws otherwise)
            long revision = p.links.revision(bareId);
            require(!p.links.applyCheck(bareId, revision, new LinkStore.Outcome(LinkStore.LIVE, null, -1)), "a check never writes a trashed row");
            p.store.enableLinks(); p.store.capture();
            for (SyncProtocol.Operation op : p.linkOps()) require(!op.changes.has(key(bare) + "/filen"), "no filen cell is sent for the placeholder link");

            // L7: one bad link inside the document install's transaction skips only itself.
            String good = UUID.randomUUID().toString();
            org.json.JSONArray list = SyncGraph.projectLinks(allCells(desk.link(good, hash('a'), 3L, "Good", null, null, false, "/D/good.sam")), null).getJSONArray("links");
            JSONObject broken = new JSONObject(list.getJSONObject(0).toString());
            broken.put("key", key("bad")).put("link_id", UUID.randomUUID().toString()).put("content", new JSONObject().put("hash", hash('b')));   // no mime, protected, ...
            org.json.JSONArray both = new org.json.JSONArray().put(broken).put(list.getJSONObject(0));
            p.db.beginTransaction();
            try {
                p.db.execSQL("INSERT OR REPLACE INTO sync_meta(key,value) VALUES('l7-probe','1')");
                applied = p.links.applySynced(p.db, both, blobs);
                p.db.setTransactionSuccessful();
            } finally { p.db.endTransaction(); }
            require(applied.skipped == 1 && applied.inserted == 1 && p.count("SELECT count(*) FROM link_records WHERE link_id=?", good) == 1, "the bad link is skipped, the good one applied");
            require("1".equals(p.text("SELECT value FROM sync_meta WHERE key='l7-probe'")), "the outer transaction (the document install) still committed");
        } finally { p.close(); }
        mark.accept("Link sync review: metadata-only keeps phone text, filen-less link kept trashed with placeholder, a bad link skips only itself");
    }

    /** L2: preview placement is durable, restores the applying flag, and removes leftovers. */
    /** P2: one merge rule per link (gone, later modified, same hash), the tie review per link (one choice, once per link), and a new local write over a tie. */
    private static void resolution(Instrumentation test, Consumer<String> mark) throws Exception {
        Phone p = new Phone(test, "ls-resolve");
        try {
            String gone = UUID.randomUUID().toString(), later = UUID.randomUUID().toString(), same = UUID.randomUUID().toString(), tie = UUID.randomUUID().toString();
            String goneId = p.publish(gone, "Gone test", hash('a'), "phone text", null, false);
            String laterId = p.publish(later, "Later test", hash('c'), "phone later text", null, false);
            String sameId = p.publish(same, "Phone title", hash('e'), "same text", null, false);
            String tieId = p.publish(tie, "Tie phone", hash('1'), "phone tie text", null, false);
            Desk desk = new Desk(p);
            JSONObject goneCells = SyncGraph.linkChanges(SyncGraph.linkWriter(new JSONObject().put("owner", OWNER).put("link_id", gone).put("kind", "doc").put("added_at", 5L)
                .put("presence", new JSONObject().put("state", "gone").put("reason", "deleted").put("since", 9L)), 0));
            JSONObject laterCells = desk.link(later, hash('d'), 1_900_000_000_000L, "Later desktop", null, null, false, "/Docs/later.sam");
            JSONObject sameCells = desk.link(same, hash('e'), 1_900_000_000_000L, "Desktop title", null, null, false, "/Docs/same.sam");
            // Same modified as the phone's (1.8e12) and a different hash: a tie.
            JSONObject tieCells = desk.link(tie, hash('2'), 1_800_000_000_000L, "Tie desktop", null, null, false, "/Docs/tie.sam");
            desk.enable(goneCells, laterCells, sameCells, tieCells);
            desk.deliver(p);

            // Rule 1: a desktop gone loses to the phone's live link; the phone row is kept with its own content.
            require(hash('a').equals(p.text("SELECT hash FROM link_records WHERE id=?", goneId)) && p.count("SELECT count(*) FROM link_records WHERE id=?", goneId) == 1, "P2 rule 1: a gone desktop side does not remove or change a live phone link");
            // Rule 2: the later modified (desktop) wins every cell.
            require(hash('d').equals(p.text("SELECT hash FROM link_records WHERE id=?", laterId)) && "Later desktop".equals(p.text("SELECT title FROM link_records WHERE id=?", laterId)), "P2 rule 2: the later modified wins all cells");
            // Rule 3: same hash, the phone's values win for every cell (the desktop's title is not applied).
            require(hash('e').equals(p.text("SELECT hash FROM link_records WHERE id=?", sameId)) && "Phone title".equals(p.text("SELECT title FROM link_records WHERE id=?", sameId)), "P2 rule 3: same hash, the phone's values win");
            // The resolution operation carries the winners' values (evidence link.resolve).
            SyncProtocol.Operation resolve = null;
            for (SyncProtocol.Operation op : p.linkOps()) if ("link.resolve".equals(evidence(op)) && op.changes.has(key(gone) + "/content")) resolve = op;
            require(resolve != null && hash('a').equals(resolve.changes.getJSONObject(key(gone) + "/content").getString("hash")) && resolve.changes.has(key(later) + "/title"), "one link.resolve operation carries the winning cells");
            // Rule 4: the tie is not applied; the review lists the link ONCE, with its title, although several of its cells conflict.
            require(hash('1').equals(p.text("SELECT hash FROM link_records WHERE id=?", tieId)) && "Tie phone".equals(p.text("SELECT title FROM link_records WHERE id=?", tieId)), "a modified tie is not applied before the review");
            JSONArray reviews = p.store.status().optJSONArray("link_conflicts");
            require(reviews != null && reviews.length() == 1 && key(tie).equals(reviews.getJSONObject(0).getString("link")) && "Tie phone".equals(reviews.getJSONObject(0).getString("title")), "one review entry per tied link, named by its title");
            require(reviews.getJSONObject(0).getJSONArray("choices").length() == 2 && p.store.status().optJSONObject("conflicts").length() == 0, "the review has both choices and the document review stays empty");

            // A new local write over the tie supersedes both alternatives of that KEY (the title); the content key stays in review.
            p.publish(tie, "Tie phone edited", hash('1'), "phone tie text", null, false);
            p.store.capture();
            SyncProtocol.State state = p.state();
            require(!state.conflicts().has(key(tie) + "/title") && state.conflicts().has(key(tie) + "/content"), "a newer local write supersedes the tie for its key only");

            // One choice (desktop) resolves every cell of the link, then the rows are applied and nothing is left in review.
            p.store.resolveLink(key(tie), "desktop");
            require(hash('2').equals(p.text("SELECT hash FROM link_records WHERE id=?", tieId)) && "Tie desktop".equals(p.text("SELECT title FROM link_records WHERE id=?", tieId)), "one desktop choice applies every cell of the tied link");
            require(LinkSync.conflictedKeys(p.state()).isEmpty() && p.store.status().optJSONArray("link_conflicts").length() == 0, "no link is left in review after the choice");
        } finally { p.close(); }
        mark.accept("Link sync P2: one rule per link (gone, later modified, same hash), one resolution operation, a modified tie reviewed once per link with one choice for all cells, a new local write over a tie");
    }

    private static void reviewPreviewFiles(Instrumentation test, Consumer<String> mark) throws Exception {
        Phone p = new Phone(test, "ls-l2");
        try {
            File blobs = blobDir(p), dir = new File(p.context.getFilesDir(), "links"); dir.mkdirs();
            String stale = UUID.randomUUID().toString(), pending = UUID.randomUUID().toString();
            String staleId = p.publish(stale, "Dropped", hash('c'), "t", null, false);
            try (FileOutputStream o = new FileOutputStream(new File(dir, staleId + ".pdf"))) { o.write("%PDF-1.7\nstale".getBytes(StandardCharsets.US_ASCII)); }
            require(p.links.preview(staleId) == null, "a preview file the row does not claim is not shown");
            try (FileOutputStream o = new FileOutputStream(new File(dir, UUID.randomUUID() + ".pdf.part"))) { o.write(1); }
            byte[] pdf = "%PDF-1.7\nreceived".getBytes(StandardCharsets.US_ASCII); String sha = hex(pdf);
            try (FileOutputStream o = new FileOutputStream(new File(blobs, sha))) { o.write(pdf); }
            String pendingId = p.publish(pending, "Pending", hash('d'), "t", null, false);
            p.db.execSQL("UPDATE link_records SET preview_sha=?, preview_len=?, preview_pending=1 WHERE id=?", new Object[]{sha, pdf.length, pendingId});
            p.db.execSQL("UPDATE sync_control SET applying=1 WHERE singleton=1");
            int placed = p.links.placePreviews(blobs);
            require(p.count("SELECT applying FROM sync_control WHERE singleton=1") == 1, "the previous applying value is restored, not set to 0");
            p.db.execSQL("UPDATE sync_control SET applying=0 WHERE singleton=1");
            require(placed == 1 && p.links.preview(pendingId) != null && p.count("SELECT preview_pending FROM link_records WHERE id=?", pendingId) == 0, "the pending preview is placed");
            require(!new File(dir, staleId + ".pdf").exists(), "the stale preview file is removed");
            String[] names = dir.list(); boolean part = false; if (names != null) for (String n : names) if (n.endsWith(".pdf.part")) part = true;
            require(!part, "leftover .pdf.part files are removed");
        } finally { p.close(); }
        mark.accept("Link sync review: placePreviews fsyncs, restores the applying flag, removes leftovers and stale previews");
    }
}
