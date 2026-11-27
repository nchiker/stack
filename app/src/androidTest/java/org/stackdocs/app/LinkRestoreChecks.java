package org.stackdocs.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * "Set up again" (docs/STACK_LINK_CONTRACT.md, Restore and setup): a restore marks links, the quiet check runs once, and the owner's next
 * share of the same file is adopted into the restored row instead of creating a duplicate. Synthetic data only.
 */
final class LinkRestoreChecks {
    private LinkRestoreChecks() {}
    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    private static String hash() { return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64); }

    private static LinkRecord record(String owner, String linkId, String title, String hash, String filenUuid) throws Exception {
        JSONArray locators = new JSONArray();
        if (filenUuid != null) locators.put(new JSONObject().put("type", "filen").put("uuid", filenUuid).put("path", "/Docs/" + title + ".sam"));
        else locators.put(new JSONObject().put("type", "owner").put("token", "tok/" + linkId));
        return LinkRecord.parse(new JSONObject().put("contract", 1).put("owner", owner).put("link_id", linkId).put("kind", "doc").put("title", title)
            .put("mime", "application/x-amipro-document").put("modified", 1_800_000_000_000L).put("hash", hash).put("locators", locators).toString());
    }

    private static LinkStore.Link byLinkId(LibraryRepository repo, String linkId) {
        for (LinkStore.Link l : repo.links.list("")) if (l.linkId.equals(linkId)) return l;
        return null;
    }

    private static long revision(LibraryRepository repo, String id) { return repo.links.revision(id); }

    static void run(Context parent, String testPackage, Consumer<String> mark) throws Exception {
        StackApplication app = (StackApplication) parent.getApplicationContext();
        restoreAndAdopt(parent, mark);
        migration(parent, mark);
        autoCheck(app, parent, testPackage, mark);
    }

    private static void restoreAndAdopt(Context parent, Consumer<String> mark) throws Exception {
        String owner = "app.stub.owner";
        Context sourceContext = new ScannerChecks.IsolatedContext(parent);
        LibraryRepository source = new LibraryRepository(sourceContext); source.initialize();
        String u1 = UUID.randomUUID().toString(), u2 = UUID.randomUUID().toString(), u3 = UUID.randomUUID().toString(), u4 = UUID.randomUUID().toString();
        String hashByHash = hash(), hashA = hash(), keepOther = hash();
        String idA = UUID.randomUUID().toString(), idH = UUID.randomUUID().toString(), idD1 = UUID.randomUUID().toString(), idD2 = UUID.randomUUID().toString();
        String idF = UUID.randomUUID().toString(), idO = UUID.randomUUID().toString();
        source.links.publish(record(owner, idA, "Doc A", hashA, u1), "alpha saved text", null);
        source.links.publish(record(owner, idH, "Doc by hash", hashByHash, null), "beta saved text", null);
        source.links.publish(record(owner, idD1, "Twin one", hash(), u3), "twin text one", null);
        source.links.publish(record(owner, idD2, "Twin two", hash(), u3), "twin text two", null);
        source.links.publish(record(owner, idF, "Foreign look-alike", hash(), u2), "gamma text", null);
        source.links.publish(record("app.other.owner", idO, "Other owner", keepOther, u4), "delta text", null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LibraryArchive.Snapshot snapshot = source.archive().snapshot(new LibraryArchive.Progress(), false, LibraryArchive.LINKS_INCLUDED)) { LibraryArchive.write(snapshot, out, new LibraryArchive.Progress()); }

        Context t = new ScannerChecks.IsolatedContext(parent);
        LibraryRepository target = new LibraryRepository(t); target.initialize();
        LibraryArchive archive = target.archive();
        LibraryArchive.Staged staged = archive.stage(new ByteArrayInputStream(out.toByteArray()), new LibraryArchive.Progress());
        archive.plan(staged); archive.commit(staged, new LibraryArchive.Progress());
        require(target.links.list("").size() == 6, "six links restored");
        for (LinkStore.Link l : target.links.list("")) require(LinkStore.UNREACHABLE.equals(l.state) && target.links.restoredState(l.id) == 1, "a restored link is unreachable and marked restored");
        mark.accept("Link restore: a restore marks every link unreachable with the restored reason");

        LinkStore.Link a = byLinkId(target, idA), byHash = byLinkId(target, idH), foreignLook = byLinkId(target, idF), twin1 = byLinkId(target, idD1);
        target.links.rename(a.id, "My own name");
        long journalBefore = target.database().compileStatement("SELECT count(*) FROM link_records").simpleQueryForLong();
        long revisionBefore = revision(target, a.id);

        // Adoption by filen uuid: new link_id, new hash, new title from the owner.
        String newA = UUID.randomUUID().toString(), newHashA = hash();
        LinkStore.Result r = target.links.publish(record(owner, newA, "Owner renamed A", newHashA, u1), "alpha refreshed text", null);
        require(!r.created && r.id.equals(a.id), "a publish with the same filen uuid adopts the restored row");
        LinkStore.Link adopted = target.links.get(a.id);
        require(adopted.linkId.equals(newA) && LinkStore.LIVE.equals(adopted.state) && target.links.restoredState(a.id) == 0, "adopted: new link_id, live, no longer restored");
        require(byLinkId(target, idA) == null && target.links.list("").size() == journalBefore, "no duplicate row and the old link_id is gone");
        require(adopted.title.equals("My own name") && adopted.addedAt == a.addedAt, "the user's title edit and the original added date are kept");
        require(adopted.hash.equals(newHashA) && target.links.text(a.id).contains("refreshed"), "text and hash are refreshed");
        require(revision(target, a.id) > revisionBefore, "the revision is bumped");
        require(target.links.list("refreshed").size() == 1 && target.links.list("alpha").size() == 1, "search follows the adopted row only");
        // A second publish of the same link_id is now an ordinary update.
        LinkStore.Result again = target.links.publish(record(owner, newA, "Owner renamed A", newHashA, u1), "alpha refreshed text", null);
        require(!again.created && again.id.equals(a.id) && target.links.list("").size() == journalBefore, "the next publish is an ordinary update");
        mark.accept("Link restore: adoption by Filen uuid keeps title and identity, refreshes content, bumps revision, no duplicate");

        // Adoption by hash (no filen locator on the record).
        String newH = UUID.randomUUID().toString();
        LinkStore.Result h = target.links.publish(record(owner, newH, "By hash", hashByHash, null), "beta saved text", null);
        require(!h.created && h.id.equals(byHash.id) && target.links.get(h.id).linkId.equals(newH) && target.links.list("").size() == journalBefore, "same hash and no filen locator adopts");
        mark.accept("Link restore: adoption by content hash");

        // Ambiguity: two restored rows hold the same uuid -> none is adopted.
        int before = target.links.list("").size();
        LinkStore.Result amb = target.links.publish(record(owner, UUID.randomUUID().toString(), "Twin", hash(), u3), "twin new", null);
        require(amb.created && target.links.list("").size() == before + 1 && target.links.restoredState(twin1.id) == 1 && byLinkId(target, idD1) != null && byLinkId(target, idD2) != null, "two candidates: none is adopted, a new row is created");
        // Foreign rows are never candidates.
        target.database().execSQL("UPDATE link_records SET origin='foreign', shared_by='Partner' WHERE id=?", new Object[]{foreignLook.id});
        before = target.links.list("").size();
        LinkStore.Result f = target.links.publish(record(owner, UUID.randomUUID().toString(), "Not foreign", hash(), u2), "new text", null);
        require(f.created && target.links.list("").size() == before + 1 && target.links.get(foreignLook.id).foreign && target.links.get(foreignLook.id).linkId.equals(idF), "a foreign row is never adopted");
        // Another owner's restored row is never adopted, nor a live row.
        before = target.links.list("").size();
        LinkStore.Result other = target.links.publish(record(owner, UUID.randomUUID().toString(), "Same uuid other owner", hash(), u4), "x", null);
        require(other.created && target.links.list("").size() == before + 1 && target.links.restoredState(byLinkId(target, idO).id) == 1, "another owner's restored row is not adopted");
        LinkStore.Result live = target.links.publish(record(owner, UUID.randomUUID().toString(), "Same uuid as live", hash(), u1), "y", null);
        require(live.created, "a row that is already live (not restored) is not adopted");
        // A filen record whose uuid matches nothing does not fall back to the hash.
        before = target.links.list("").size();
        LinkStore.Result noFallback = target.links.publish(record(owner, UUID.randomUUID().toString(), "Fresh uuid", keepOther, UUID.randomUUID().toString()), "z", null);
        require(noFallback.created, "a record with a filen locator only matches by uuid");
        mark.accept("Link restore: no adoption on ambiguity, foreign, another owner, live rows");
    }

    private static void migration(Context parent, Consumer<String> mark) throws Exception {
        Context isolated = new ScannerChecks.IsolatedContext(parent);
        String name = "migrate-restore-" + UUID.randomUUID() + ".db", id = UUID.randomUUID().toString();
        LibraryDatabase first = new LibraryDatabase(isolated, name);
        try {
            SQLiteDatabase db = first.getWritableDatabase();
            db.execSQL("INSERT INTO link_records(id,owner,link_id,kind,title,mime,modified,hash,locators,added_at,received_at,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                new Object[]{id, "app.stub.owner", UUID.randomUUID().toString(), "doc", "Old row", "application/x-amipro-document", 5L, hash(), "[]", 1L, 1L, "unreachable"});
            // A real version 16 library has every column except `restored`.
            List<String> columns = new ArrayList<>();
            try (Cursor c = db.rawQuery("PRAGMA table_info(link_records)", null)) { while (c.moveToNext()) if (!"restored".equals(c.getString(1))) columns.add(c.getString(1)); }
            db.execSQL("CREATE TABLE link_old AS SELECT " + String.join(",", columns) + " FROM link_records");
            db.execSQL("DROP TABLE link_records"); db.execSQL("ALTER TABLE link_old RENAME TO link_records");
            db.setVersion(16);
        } finally { first.close(); }
        LibraryDatabase second = new LibraryDatabase(isolated, name);
        try {
            SQLiteDatabase db = second.getWritableDatabase();
            require(db.getVersion() == LibraryDatabase.VERSION, "a version 16 library upgrades to the current version");
            try (Cursor c = db.rawQuery("SELECT title,state,restored FROM link_records WHERE id=?", new String[]{id})) {
                require(c.moveToFirst() && c.getString(0).equals("Old row") && c.getString(1).equals("unreachable") && c.getInt(2) == 0, "the row survives and restored is 0");
            }
        } finally { second.close(); isolated.deleteDatabase(name); }
        mark.accept("Link restore: schema 16 to 17 adds restored with 0 for existing rows");
    }

    private static Bundle stat(boolean exists, String hash) {
        Bundle b = new Bundle(); b.putBoolean("exists", exists); if (hash != null) b.putString("hash", hash); return b;
    }

    private static void configure(StackApplication app, String owner, Bundle stat, boolean fail) {
        Bundle extras = new Bundle();
        if (stat != null) extras.putBundle("stat", stat);
        extras.putLong("delay", 0); extras.putBoolean("fail", fail);
        app.getContentResolver().call(Uri.parse("content://" + owner + ".stacklink"), "configure", null, extras);
    }

    private static int calls(StackApplication app, String owner) {
        return app.getContentResolver().call(Uri.parse("content://" + owner + ".stacklink"), "report", null, null).getInt("calls");
    }

    private static void autoCheck(StackApplication app, Context parent, String owner, Consumer<String> mark) throws Exception {
        String h = hash();
        LinkStore.Result r = app.io.submit(() -> app.library.links.publish(record(owner, UUID.randomUUID().toString(), "Auto A", h, null), "auto text", null)).get();
        app.io.submit(() -> { app.library.database().execSQL("UPDATE link_records SET restored=1, state='unreachable' WHERE id=?", new Object[]{r.id}); return 0; }).get();
        // The owner app does not answer yet: the link stays unsettled and is tried again later.
        configure(app, owner, null, true);
        LinkChecker.resetAuto();
        require(LinkChecker.checkRestored(app) >= 1, "the quiet check asks the owner");
        require(app.io.submit(() -> app.library.links.restoredState(r.id)).get() == 1 && LinkStore.UNREACHABLE.equals(app.io.submit(() -> app.library.links.get(r.id)).get().state), "an owner that fails leaves the link unsettled, still unreachable");
        require(LinkChecker.checkRestored(app) == 0, "a second attempt within a minute does nothing");
        // The owner answers: it does not know the id -> unreachable with the restored reason (never missing).
        configure(app, owner, stat(false, null), false);
        LinkChecker.resetAuto();
        int asked = LinkChecker.checkRestored(app);
        require(asked >= 1 && app.io.submit(() -> app.library.links.restoredState(r.id)).get() == 2
            && LinkStore.UNREACHABLE.equals(app.io.submit(() -> app.library.links.get(r.id)).get().state), "owner says not found: unreachable, reason restored, never missing");
        // Settled: no further automatic work and no further owner calls.
        int before = calls(app, owner);
        LinkChecker.resetAuto();
        require(LinkChecker.checkRestored(app) == 0 && calls(app, owner) == before, "the quiet check runs once per restore: nothing left to ask");
        // A manual check keeps the reason.
        LinkChecker.check(app, app.io.submit(() -> app.library.links.get(r.id)).get());
        require(app.io.submit(() -> app.library.links.restoredState(r.id)).get() == 2 && LinkStore.UNREACHABLE.equals(app.io.submit(() -> app.library.links.get(r.id)).get().state), "a manual check keeps unreachable and the reason");
        // The owner knows the id after all -> an ordinary live link.
        configure(app, owner, stat(true, h), false);
        LinkChecker.check(app, app.io.submit(() -> app.library.links.get(r.id)).get());
        require(app.io.submit(() -> app.library.links.restoredState(r.id)).get() == 0 && LinkStore.LIVE.equals(app.io.submit(() -> app.library.links.get(r.id)).get().state), "an owner that knows the id makes it live and clears the reason");
        app.io.submit(() -> { app.library.links.delete(r.id); return 0; }).get();
        configure(app, owner, null, false);
        mark.accept("Link restore: quiet automatic check runs once, keeps unreachable with the reason, settles live when the owner knows it");
    }
}
