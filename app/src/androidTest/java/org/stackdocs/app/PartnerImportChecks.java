package org.stackdocs.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * S7-3 checks (import side): an owner library publishes a share archive with the real writer, a second, separate library imports it as foreign
 * records. Covers the round trip, generations, tombstones and "no longer listed", records listed again, the person's own records and removals,
 * that foreign records are never checked with an owner app and never reach a library export (they do reach the link journal since S7-4; LinkSync gates what travels), a wrong key, and a missing file.
 * A plain folder stands in for the granted folder (the provider side is checked by hand on a device). Synthetic data only.
 */
final class PartnerImportChecks {
    private PartnerImportChecks() {}
    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    private static final String OWNER = "app.stub.owner";

    private static LinkRecord record(String linkId, String title, String hash) throws Exception {
        JSONObject o = new JSONObject().put("contract", 1).put("owner", OWNER).put("link_id", linkId).put("kind", "doc").put("title", title)
                .put("mime", "application/x-amipro-document").put("modified", 1_800_000_000_000L).put("hash", hash)
                .put("locators", new JSONArray().put(new JSONObject().put("type", "filen").put("uuid", UUID.randomUUID().toString()).put("path", "/Docs/" + title + ".sam")));
        return LinkRecord.parse(o.toString());
    }

    private static LinkStore.Link byTitle(LinkStore links, String title) {
        for (LinkStore.Link l : links.list("")) if (l.title.equals(title)) return l;
        return null;
    }

    private static long count(SQLiteDatabase db, String sql) {
        return android.database.DatabaseUtils.longForQuery(db, sql, null);
    }

    static void run(Context parent, Consumer<String> mark) throws Exception {
        Context ctxA = new ScannerChecks.IsolatedContext(parent), ctxB = new ScannerChecks.IsolatedContext(parent);
        LibraryRepository owner = new LibraryRepository(ctxA); owner.initialize();
        LibraryRepository partner = new LibraryRepository(ctxB); partner.initialize();
        LinkStore a = owner.links, b = partner.links;
        SQLiteDatabase db = partner.database();
        File staging = new File(ctxA.getFilesDir(), "link-staging"); staging.mkdirs();
        File preview = new File(staging, "preview.pdf"); Files.copy(OcrQueueChecks.fixture(ctxA, "links-preview.pdf").toPath(), preview.toPath());
        File folder = new File(ctxA.getCacheDir(), "partner-import-" + UUID.randomUUID()); folder.mkdirs();
        ShareArchive.FileSink sink = new ShareArchive.FileSink(folder);
        File file = new File(folder, ShareArchive.FILE_NAME);
        PartnerImport.Source source = new PartnerImport.FileSource(file);

        String idA = UUID.randomUUID().toString(), idB = UUID.randomUUID().toString(), idO = UUID.randomUUID().toString();
        LinkStore.Result alpha = a.publish(record(idA, "Alpha", "a".repeat(64)), "alpha text about medicines", preview);
        LinkStore.Result bravo = a.publish(record(idB, "Bravo", "b".repeat(64)), "bravo text about holidays", null);
        LinkStore.Result both = a.publish(record(idO, "Same id", "c".repeat(64)), "partner version of the shared id", null);
        a.setPartnerShare(alpha.id, 1); a.setPartnerShare(bravo.id, 1); a.setPartnerShare(both.id, 1);
        byte[] key = PartnerKeys.ensureSendKey(ctxA, PartnerKeys.DEFAULT_PARTNER);
        PartnerKeys.setReceiveKey(ctxB, PartnerKeys.DEFAULT_PARTNER, key);
        byte[] receive = PartnerKeys.receiveKey(ctxB, PartnerKeys.DEFAULT_PARTNER);
        require(Arrays.equals(receive, key), "the scanned key is stored in the receive slot");
        // The partner's own record under the same owner and link id: it must never be overwritten.
        LinkStore.Result mine = b.publish(record(idO, "My own copy", "d".repeat(64)), "my own text", null);
        long now = 1_900_000_000_000L;

        // Since S7-4 the triggers cover foreign rows too (they reach the link journal and the dirty flag; what travels to a computer is decided by LinkSync's gate). An own publish is the control that the triggers are live.
        db.execSQL("INSERT OR REPLACE INTO sync_control(singleton,enabled,applying) VALUES(1,1,0)");
        db.execSQL("DELETE FROM sync_change");
        long ownChanges = count(db, "SELECT count(*) FROM sync_change WHERE table_name='link_records'");
        require(ownChanges == 0, "journal starts empty");
        b.publish(record(UUID.randomUUID().toString(), "Control own", "e".repeat(64)), "control", null);
        require(count(db, "SELECT count(*) FROM sync_change WHERE table_name='link_records'") > 0, "an own record is journalled (control)");
        db.execSQL("DELETE FROM sync_change"); db.execSQL("DELETE FROM sync_meta WHERE key='link_dirty'");

        // 1. Round trip.
        PartnerShare.publish(a, sink, key, now, nextStamp());
        PartnerImport.Result first = PartnerImport.importOnce(b, source, receive, "Sam", now, "", null);
        require(first.kind == PartnerImport.Kind.IMPORTED && first.imported.inserted == 2 && first.imported.ownKept == 1, "the first import inserts two and keeps the own record");
        LinkStore.Link fa = byTitle(b, "Alpha"), fb = byTitle(b, "Bravo");
        require(fa != null && fa.foreign && "Sam".equals(fa.sharedBy) && LinkStore.LIVE.equals(fa.state) && fa.shared && fa.locators.contains("\"filen\""), "a foreign record is live, shared by the name, with the filen place");
        require(b.preview(fa.id) != null && b.text(fa.id).contains("medicines") && fb.sharedBy.equals("Sam"), "text and preview are stored");
        require(b.list("medicines").size() == 1 && b.list("medicines").get(0).foreign, "search finds the foreign text");
        require(!b.list("holidays").isEmpty() && b.list("holidays").get(0).sharedBy.equals("Sam"), "search rows carry the badge name");
        LinkStore.Link own = b.get(mine.id);
        require(!own.foreign && own.title.equals("My own copy") && own.hash.equals("d".repeat(64)) && b.text(mine.id).equals("my own text"), "the person's own record is never overwritten");
        require(count(db, "SELECT count(*) FROM link_records WHERE origin='foreign'") == 2 && count(db, "SELECT foreign_generation FROM link_records WHERE id='" + fa.id + "'") >= 0, "two foreign rows");
        mark.accept("partner import: round trip");

        // 2. Unchanged file is skipped; a different key fingerprint reads again.
        require(PartnerImport.importOnce(b, source, receive, "Sam", now, first.signature, null).kind == PartnerImport.Kind.UNCHANGED, "same size and time are skipped");
        mark.accept("partner import: unchanged skipped");

        // 3. Higher generation updates; the person's own title is kept.
        b.rename(fb.id, "Bravo (mine)");
        a.publish(record(idA, "Alpha", "f".repeat(64)), "alpha text, second edition", null);
        a.publish(record(idB, "Bravo", "b".repeat(64)), "bravo text about holidays", null);
        PartnerShare.publish(a, sink, key, now + 1, nextStamp());
        PartnerImport.Result second = PartnerImport.importOnce(b, source, receive, "Sam", now + 1, first.signature, null);
        require(second.kind == PartnerImport.Kind.IMPORTED && second.imported.updated >= 1, "a higher generation updates");
        require(b.text(fa.id).contains("second edition") && b.get(fa.id).hash.equals("f".repeat(64)) && b.preview(fa.id) == null, "text, hash and the missing preview follow the archive");
        require(b.get(fb.id).title.equals("Bravo (mine)"), "the person's own title survives an update");
        mark.accept("partner import: higher generation");

        // 4. A lower generation is ignored.
        ShareArchive.Contents current = ShareArchive.read(file, key);
        List<ShareArchive.Record> older = new ArrayList<>();
        for (ShareArchive.Record r : current.records)
            older.add(new ShareArchive.Record(r.owner, r.linkId, r.kind, r.filenUuid, r.filenPath, r.title + " OLD", r.mime, r.hash, r.modified, "OLD TEXT", r.preview, Math.max(0, r.generation - 1), r.isProtected));
        ShareArchive.write(sink, key, older, null, new ArrayList<>());
        PartnerImport.Result lower = PartnerImport.importOnce(b, source, receive, "Sam", now + 2, "", null);
        require(lower.kind == PartnerImport.Kind.IMPORTED && lower.imported.updated == 0 && b.text(fa.id).contains("second edition") && byTitle(b, "Alpha") != null, "a lower generation changes nothing");
        mark.accept("partner import: lower generation ignored");

        // 4b. Replay and rollback: the same epoch with a lower counter is refused; a new epoch is accepted and resets.
        PartnerKeys.Stamp current4 = nextStamp();
        PartnerShare.publish(a, sink, key, now + 2, current4);
        PartnerKeys.Stamp seenAhead = new PartnerKeys.Stamp(current4.epoch, current4.counter + 5);
        PartnerImport.Result replayed = PartnerImport.importOnce(b, source, receive, "Sam", now + 2, "", null, seenAhead);
        require(replayed.kind == PartnerImport.Kind.OLDER && replayed.imported == null && replayed.stamp == null, "an archive with a lower counter in the same epoch is refused");
        require(PartnerImport.OLDER_PROBLEM.equals("The shared file is older than one already imported."), "the quiet line");
        PartnerImport.Result sameCounter = PartnerImport.importOnce(b, source, receive, "Sam", now + 2, "", null, new PartnerKeys.Stamp(current4.epoch, current4.counter));
        require(sameCounter.kind == PartnerImport.Kind.IMPORTED && sameCounter.stamp.counter == current4.counter, "the same counter is read again");
        PartnerShare.publish(a, sink, key, now + 2, new PartnerKeys.Stamp("fedcba9876543210fedcba9876543210", 1));
        PartnerImport.Result fresh = PartnerImport.importOnce(b, source, receive, "Sam", now + 2, "", null, seenAhead);
        require(fresh.kind == PartnerImport.Kind.IMPORTED && "fedcba9876543210fedcba9876543210".equals(fresh.stamp.epoch) && fresh.stamp.counter == 1, "a new epoch is accepted even with a lower counter");
        // Direction-changing characters never reach the search text.
        require(LinkRecord.stripDirection("a\u202eb\u2066c\u200fd\u061ce").equals("abcde"), "direction characters are stripped");
        mark.accept("partner import: replay refused, new epoch accepted");

        // 5. Tombstone, and "no longer listed", both go to Recently deleted as unshared.
        a.setPartnerShare(bravo.id, 0);
        PartnerShare.publish(a, sink, key, now + 3, nextStamp());
        PartnerImport.Result gone = PartnerImport.importOnce(b, source, receive, "Sam", now + 3, "", null);
        require(gone.imported.unshared >= 1 && LinkStore.TRASHED.equals(b.get(fb.id).state), "a tombstoned record is trashed");
        boolean listedAsUnshared = false;
        for (LinkStore.Trashed t : b.trashed()) if (t.id.equals(fb.id)) listedAsUnshared = t.noLongerShared() && t.reason.equals("unshared") && t.sharedBy.equals("Sam");
        require(listedAsUnshared, "Recently deleted says unshared / no longer shared");
        ShareArchive.write(sink, key, new ArrayList<>(), null, new ArrayList<>());
        PartnerImport.importOnce(b, source, receive, "Sam", now + 4, "", null);
        require(LinkStore.TRASHED.equals(b.get(fa.id).state) && "unshared".equals(trashedReason(b, fa.id)), "a record no longer listed is trashed as unshared");
        mark.accept("partner import: tombstone and no-longer-listed");

        // 6. Listed again: comes back (the record and its saved text are intact).
        a.setPartnerShare(bravo.id, 1);
        PartnerShare.publish(a, sink, key, now + 5, nextStamp());
        PartnerImport.importOnce(b, source, receive, "Sam", now + 5, "", null);
        require(LinkStore.LIVE.equals(b.get(fa.id).state) && LinkStore.LIVE.equals(b.get(fb.id).state) && b.get(fb.id).title.equals("Bravo (mine)"), "records listed again come back live with the person's title");
        // What the person removed stays removed.
        b.trash(fb.id, "user");
        PartnerImport.importOnce(b, source, receive, "Sam", now + 6, "", null);
        require(LinkStore.TRASHED.equals(b.get(fb.id).state) && "user".equals(trashedReason(b, fb.id)), "a record the person removed stays removed");
        // Restoring an unshared one from Recently deleted makes it live without calling any owner app.
        b.trash(fa.id, LinkStore.UNSHARED); b.restore(fa.id);
        require(LinkStore.LIVE.equals(b.get(fa.id).state), "restore gives a foreign record back as live");
        mark.accept("partner import: listed again, user removal stays");

        // 7. Foreign records: never checked, journalled for the link sync (S7-4), never in a backup export or the link archive.
        LinkStore.Link foreign = b.get(fa.id);
        LinkChecker.Result checked = LinkChecker.check(null, foreign);
        require(!checked.busy && !checked.changed && foreign.state.equals(checked.state), "LinkChecker skips a foreign record without any call");
        require("This document belongs to your partner. Only they can update it.".equals(LinkChecker.refresh(null, foreign)), "refresh is refused for a foreign record");
        require(count(db, "SELECT count(*) FROM sync_change WHERE table_name='link_records'") > 0 && count(db, "SELECT count(*) FROM sync_meta WHERE key='link_dirty'") == 1, "foreign changes reach the link journal and raise the dirty flag (S7-4)");
        db.beginTransaction();
        try {
            JSONArray exported = LinkArchive.capture(db);
            boolean leaked = false;
            for (int i = 0; i < exported.length(); i++) if (exported.getJSONObject(i).getString("id").equals(fa.id) || exported.getJSONObject(i).getString("id").equals(fb.id)) leaked = true;
            require(!leaked && exported.length() == 2, "the backup link list holds only the person's own records");
        } finally { db.endTransaction(); }
        require(a.partnerFlagged().size() == 3 && b.partnerFlagged().isEmpty(), "foreign records are never offered for sharing onwards");
        require(FilenOpen.request(ctxB, foreign.locators) == null || FilenOpen.ACTION.equals(FilenOpen.request(ctxB, foreign.locators).getAction()), "Open goes to Filen Files, never an owner app");
        require(FilenOpen.request("u", "/p").getPackage().equals(FilenFolder.PACKAGE) && FilenOpen.ACTION.equals(FilenOpen.request("u", "/p").getAction()), "the Filen open request is explicit");
        mark.accept("partner import: foreign records stay out of checks, sync and backup");

        // 8. A wrong key is reported quietly; a missing file and garbage are "nothing new"; a replaced file is read again.
        PartnerShare.publish(a, sink, key, now + 7, nextStamp());
        require(PartnerImport.importOnce(b, source, new byte[32], "Sam", now + 8, "", null).kind == PartnerImport.Kind.BAD_KEY, "a wrong key is BAD_KEY, not an exception");
        require(PartnerImport.KEY_PROBLEM.equals("The shared file cannot be opened with your key. Ask your partner to pair again."), "the quiet line has the agreed words");
        File moved = new File(folder, "away");
        require(file.renameTo(moved), "move the file away");
        PartnerImport.Result missing = PartnerImport.importOnce(b, source, receive, "Sam", now + 9, "", null);
        require(missing.kind == PartnerImport.Kind.MISSING && missing.imported == null, "a missing file is nothing new");
        require(moved.renameTo(file), "put it back");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[]{1, 2, 3, 4, 5}); }
        require(PartnerImport.importOnce(b, source, receive, "Sam", now + 10, "", null).kind == PartnerImport.Kind.UNREADABLE, "garbage is unreadable, nothing new");
        require(count(db, "SELECT count(*) FROM link_records WHERE origin='foreign' AND state<>'trashed'") >= 1, "nothing was removed by the failed reads");
        PartnerShare.publish(a, sink, key, now + 11, nextStamp());
        require(PartnerImport.importOnce(b, source, receive, "Pat", now + 11, "", null).kind == PartnerImport.Kind.IMPORTED && "Pat".equals(b.get(fa.id).sharedBy), "the badge name follows the name chosen at pairing");
        require(PartnerImport.cleanName("  ‮ ").equals("Partner") && PartnerImport.cleanName("Alex").equals("Alex"), "the name is cleaned and defaults to Partner");
        mark.accept("partner import: wrong key quiet, missing and unreadable ignored");

        // 9. A failed apply leaves nothing behind (one transaction).
        long before = count(db, "SELECT count(*) FROM link_records");
        ShareArchive.Record clash = new ShareArchive.Record(OWNER, UUID.randomUUID().toString(), "doc", UUID.randomUUID().toString(), "/x.sam", "Broken", "text/plain", "9".repeat(64), 1, "t", null, 1, false);
        List<ShareArchive.Record> one = new ArrayList<>(); one.add(clash);
        db.execSQL("CREATE TRIGGER partner_import_failure BEFORE UPDATE OF state ON link_records BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        boolean failed = false;
        try { b.applyPartnerArchive(new ShareArchive.Contents(one, new ArrayList<>()), "Sam", now + 12); } catch (RuntimeException | java.io.IOException expected) { failed = true; }
        db.execSQL("DROP TRIGGER partner_import_failure");
        require(failed && count(db, "SELECT count(*) FROM link_records") == before, "a failed apply rolls everything back");
        PartnerKeys.forget(ctxA, PartnerKeys.DEFAULT_PARTNER); PartnerKeys.forget(ctxB, PartnerKeys.DEFAULT_PARTNER);
        mark.accept("partner import: one transaction");
    }

    private static String trashedReason(LinkStore links, String id) {
        for (LinkStore.Trashed t : links.trashed()) if (t.id.equals(id)) return t.reason;
        return "";
    }

    private static long stampCounter = 0;
    /** A fresh publish stamp: one epoch, a counter that goes up on every call (as PartnerKeys.nextStamp does on the phone). */
    static PartnerKeys.Stamp nextStamp() { return new PartnerKeys.Stamp("0123456789abcdef0123456789abcdef", ++stampCounter); }
}
