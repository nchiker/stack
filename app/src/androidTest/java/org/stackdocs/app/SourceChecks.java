package org.stackdocs.app;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.stackdocs.app.types.FileTypes;

/**
 * Folder source checks (-e sources true), docs/FOLDER_SHARING_INTERFACES.md I6. Three parts:
 *  1. migration 17 to 18 on a copy of a version 17 library;
 *  2. every indexer and removal rule against a scripted provider (stale, error, another account, a vanished file, 10 minutes later);
 *  3. a real Filen tree (junk accounts, test users 10 and 11 only): index it, rename/move/save-over/delete in it, answers with no network.
 * Part 3 needs Filen's pinned test package to hold the grants (see {@link #borrowGrants}); without Filen or grants it reports SKIP.
 * `-e sourcesPhase offline|online` runs only the no-network and recovery steps of part 3 on the library the first run left behind.
 */
final class SourceChecks {
    private SourceChecks() {}

    static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    // ---- scripted provider -------------------------------------------------------------------------------------------------------

    static final class Fake implements SourceReader {
        final Map<String, List<Entry>> children = new HashMap<>();
        final Map<String, byte[]> content = new HashMap<>();
        final Map<String, Status> status = new HashMap<>();
        final Set<String> gone = new HashSet<>(), failing = new HashSet<>();
        Status defaultStatus = Status.FRESH;
        String account = "acct-1", legacy = "";
        boolean granted = true;
        int fetches, goneAsked;
        Consumer<String> onFetch = id -> {};
        int next = 1;

        String dir(String parent, String name) { String id = "filen-in:" + UUID.randomUUID(); put(parent, new Entry(id, name, DocumentsContract.Document.MIME_TYPE_DIR, 0, 1, "", true)); children.put(id, new ArrayList<>()); return id; }
        String file(String parent, String name, String text) { return file(parent, name, text.getBytes(StandardCharsets.UTF_8)); }
        String file(String parent, String name, byte[] bytes) {
            String id = "filen-in:" + UUID.randomUUID();
            put(parent, new Entry(id, name, name.endsWith(".txt") ? "text/plain" : "application/octet-stream", bytes.length, 1, "v" + next++, false)); content.put(id, bytes); return id;
        }
        void put(String parent, Entry e) { children.computeIfAbsent(parent, k -> new ArrayList<>()).add(e); }
        Entry entry(String id) { for (List<Entry> l : children.values()) for (Entry e : l) if (e.docId.equals(id)) return e; return null; }
        String parentOf(String id) { for (Map.Entry<String, List<Entry>> m : children.entrySet()) for (Entry e : m.getValue()) if (e.docId.equals(id)) return m.getKey(); return null; }
        void delete(String id) { for (List<Entry> l : children.values()) l.removeIf(e -> e.docId.equals(id)); content.remove(id); }
        void rename(String id, String name) { String p = parentOf(id); Entry e = entry(id); delete(id); put(p, new Entry(id, name, e.mime, e.size, e.modified, e.version, e.dir)); }
        void move(String id, String newParent) { String p = parentOf(id); Entry e = entry(id); for (List<Entry> l : children.values()) l.removeIf(x -> x.docId.equals(id)); put(newParent, e); }
        void setText(String id, String text) { Entry e = entry(id); String p = parentOf(id); byte[] b = text.getBytes(StandardCharsets.UTF_8); for (List<Entry> l : children.values()) l.removeIf(x -> x.docId.equals(id)); put(p, new Entry(id, e.name, e.mime, b.length, e.modified + 1, "v" + next++, false)); content.put(id, b); }
        /** A save-over as Filen shows it to a partner: the old id is gone, a new id with the same name appears. */
        String saveOver(String id, String text) { String p = parentOf(id); String name = entry(id).name; delete(id); return file(p, name, text); }

        @Override public boolean granted() { return granted; }
        @Override public Listing list(String parent) {
            Status s = status.getOrDefault(parent, defaultStatus);
            if (s != Status.FRESH && s != Status.STALE) return Listing.of(s);
            return new Listing(s, new ArrayList<>(children.getOrDefault(parent, Collections.<Entry>emptyList())), account, legacy);
        }
        @Override public void fetch(String docId, File to, long max, CancellationSignal signal) throws IOException {
            fetches++; onFetch.accept(docId);
            if (failing.contains(docId)) throw new IOException("scripted failure");
            byte[] b = content.get(docId);
            if (b == null) throw new FileNotFoundException(docId);
            try (FileOutputStream out = new FileOutputStream(to)) { out.write(b); }
        }
        @Override public boolean gone(String docId) { goneAsked++; return gone.contains(docId); }
    }

    static final class Env implements SourceIndexer.Env {
        boolean allowed = true, battery = true;
        @Override public boolean allowed() { return allowed; }
        @Override public boolean batteryOk() { return battery; }
    }

    private static final class Rig {
        final Context iso; final String dbName = "sources-" + UUID.randomUUID() + ".db"; final LibraryDatabase helper; final SourceStore store; final SourceIndexer indexer;
        final Fake fake = new Fake(); final Env env = new Env(); final String root = "filen-in:root";
        String sourceId;
        Rig(Context parent) { this(parent, null); }
        Rig(Context parent, Extractor extractor) {
            iso = new ScannerChecks.IsolatedContext(parent);
            helper = new LibraryDatabase(iso, dbName);
            store = new SourceStore(iso, helper::getWritableDatabase, new File(iso.getFilesDir(), "sources"));
            indexer = extractor == null ? new SourceIndexer(iso, store, source -> fake, env) : new SourceIndexer(iso, store, source -> fake, env, extractor);
            String tree = "content://" + SourceStore.AUTHORITY + "/tree/" + Uri.encode(root) + "#" + UUID.randomUUID();
            sourceId = UUID.randomUUID().toString();
            helper.getWritableDatabase().execSQL("INSERT INTO sources(id,kind,tree_uri,title,partner_name,state,added_at) VALUES(?,?,?,?,?,?,1)",
                    new Object[]{sourceId, SourceStore.PARTNER, tree, "Shared with me", "Sam", SourceStore.NEW});
            fake.children.put(root, new ArrayList<>());
        }
        SourceStore.Source source() { return store.get(sourceId); }
        SourceIndexer.Result scan() { return indexer.scan(source(), new SourceIndexer.Control()); }
        SourceStore.FileRow row(String docId) { return store.rows(sourceId).get(docId); }
        int count(String sql, String... args) { try (Cursor c = helper.getWritableDatabase().rawQuery(sql, args)) { c.moveToFirst(); return c.getInt(0); } }
        int files() { return count("SELECT COUNT(*) FROM source_files WHERE source_id=?", sourceId); }
        void close() { helper.close(); iso.deleteDatabase(dbName); }
    }

    // ---- the checks --------------------------------------------------------------------------------------------------------------

    static void run(Context test, Context app, android.os.Bundle args, Consumer<String> mark) throws Exception {
        String phase = args.getString("sourcesPhase", "all");
        if (phase.equals("security")) { isolation(test, app, mark); planSignature(test, app, mark); accountMove(app, mark); migration22(app, mark); partnerIdentity(app, mark); return; }   // the pre-release review's groups on their own
        if (phase.equals("all")) {
            migration(app, mark);
            rules(app, mark);
            extraction(test, app, mark);
            chunkedPdfs(test, app, mark);
            isolation(test, app, mark);
            planSignature(test, app, mark);
            accountMove(app, mark);
            migration22(app, mark);
            partnerIdentity(app, mark);
            budgetAndReader(test, app, mark);
            receiverAndStore(app, mark);
        }
        real(test, app, phase, mark);
    }

    private static void migration(Context app, Consumer<String> mark) throws Exception {
        Context iso = new ScannerChecks.IsolatedContext(app);
        String name = "migrate-sources-" + UUID.randomUUID() + ".db", copy = "copy-" + name;
        LibraryDatabase first = new LibraryDatabase(iso, name);
        try {
            SQLiteDatabase db = first.getWritableDatabase();
            db.execSQL("INSERT INTO documents(id,title,mime,added_at,page_count,initial_page_count) VALUES('d1','Old document','application/pdf',5,1,1)");
            db.execSQL("INSERT INTO document_stacks(document_id,stack_id) VALUES('d1','bills')");
            db.execSQL("INSERT INTO link_records(id,owner,link_id,kind,title,mime,modified,hash,locators,added_at,received_at) VALUES('l1','o','x','doc','Old link','m',1,'h','[]',1,1)");
            for (String table : new String[]{"item_stacks", "item_tags", "source_search", "source_files", "sources", "source_carry", "source_budget"}) db.execSQL("DROP TABLE " + table);
            db.setVersion(17);
            try (Cursor c = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) { c.moveToFirst(); }
        } finally { first.close(); }
        File from = iso.getDatabasePath(name), to = iso.getDatabasePath(copy);
        try (java.io.FileInputStream in = new java.io.FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
        LibraryDatabase second = new LibraryDatabase(iso, copy);
        try {
            SQLiteDatabase db = second.getWritableDatabase();
            require(db.getVersion() == LibraryDatabase.VERSION, "a version 17 copy upgrades to the current version");
            for (String table : new String[]{"sources", "source_files", "item_stacks", "item_tags", "source_carry", "source_budget"}) {
                try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) { require(c.moveToFirst() && c.getInt(0) == 0, table + " exists and starts empty"); }
            }
            db.execSQL("INSERT INTO source_search(file_id,title,body) VALUES('x','Quarterly','budget words')");
            try (Cursor c = db.rawQuery("SELECT file_id FROM source_search WHERE source_search MATCH 'budg*'", null)) { require(c.moveToFirst() && c.getString(0).equals("x"), "source_search is a working FTS table"); }
            try (Cursor c = db.rawQuery("SELECT title FROM documents d JOIN document_stacks s ON s.document_id=d.id WHERE s.stack_id='bills'", null)) { require(c.moveToFirst() && c.getString(0).equals("Old document"), "documents and stacks are untouched"); }
            try (Cursor c = db.rawQuery("SELECT title FROM link_records", null)) { require(c.moveToFirst() && c.getString(0).equals("Old link"), "link records are untouched"); }
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'sync_%source%' OR (type='trigger' AND tbl_name IN ('sources','source_files','item_stacks','item_tags'))", null)) { require(c.moveToFirst() && c.getInt(0) == 0, "no sync trigger on the new tables"); }
            try { db.execSQL("INSERT INTO source_files(id,source_id,provider_doc_id,parent_doc_id,name) VALUES('f','nope','a','p','n')"); require(false, "a file needs its source"); } catch (android.database.sqlite.SQLiteConstraintException expected) { /* foreign key */ }
        } finally { second.close(); iso.deleteDatabase(name); iso.deleteDatabase(copy); }
        mark.accept("Sources: migration 17 to 18 on a copy keeps every old row and adds empty source tables");
    }

    private static void rules(Context app, Consumer<String> mark) throws Exception {
        indexing(app, mark);
        refresh(app, mark);
        noRemovalOnBadAnswers(app, mark);
        confirmation(app, mark);
        saveOver(app, mark);
        unshare(app, mark);
        pauses(app, mark);
        robustness(app, mark);
        misc(app, mark);
        sharedByMeCovered(app, mark);
    }

    private static void indexing(Context app, Consumer<String> mark) {
        Rig r = new Rig(app);
        try {
            // A fresh install knows only default reader versions until the extract process has reported the real ones: the first files finished
            // must not carry a registry fingerprint that changes a moment later (the old check failed "read 1, fetched 1" on a first run).
            org.stackdocs.app.types.NativeText.remember(app, 1, 1);
            String docs = r.fake.dir(r.root, "Docs");
            String a = r.fake.file(r.root, "plan.txt", "The quarterly budget plan for the house");
            String b = r.fake.file(docs, "notes.txt", "Invoice number 4411 from the plumber");
            String c = r.fake.file(docs, "blob.xyz", new byte[]{0, 1, 2, 3, (byte) 200, (byte) 201, 0, 7});
            String d = r.fake.file(docs, "broken.pdf", "%PDF-1.4 this is not really a pdf".getBytes(StandardCharsets.UTF_8));
            SourceIndexer.Result result = r.scan();
            require(result.complete && result.stopped.isEmpty() && result.read == 4, "first scan completes and reads 4 files: " + result.read);
            SourceStore.Progress p = r.store.progress(r.sourceId);
            require(p.files == 4 && p.indexed == 2 && p.unsupported == 1 && p.unreadable == 1 && p.pending == 0, "progress: " + p.indexed + " indexed, " + p.unsupported + " unsupported, " + p.unreadable + " unreadable");
            require(SourceStore.OK.equals(p.state) && p.lastFreshScan > 0, "state ok and a fresh scan time");
            require(r.source().accountId.equals("acct-1"), "the account id is learned from the first FRESH listing");
            require(r.row(a).state.equals(SourceStore.INDEXED) && r.row(a).handlerId.equals("text") && !r.row(a).fingerprint.isEmpty(), "handler and fingerprint recorded");
            require(r.row(c).state.equals(SourceStore.UNSUPPORTED) && !r.row(c).reason.isEmpty(), "an unknown type is unsupported with a reason");
            require(r.row(d).state.equals(SourceStore.UNREADABLE) && !r.row(d).reason.isEmpty(), "a broken PDF is unreadable with a reason: " + r.row(d).state);
            require(r.row(b).path.equals("Docs/notes.txt"), "path");
            List<SourceStore.Hit> hits = r.store.search("quarterly budget", null);
            require(hits.size() == 1 && hits.get(0).name.equals("plan.txt") && hits.get(0).kind.equals(SourceStore.PARTNER) && hits.get(0).partnerName.equals("Sam"), "text search finds the file, marked with kind and partner");
            require(r.store.search("blob", null).size() == 1 && r.store.search("blob", null).get(0).state.equals(SourceStore.UNSUPPORTED), "an unsupported file is found by name");
            require(r.store.search("plumber", null).size() == 1, "text in a subfolder");
            int before = r.fake.fetches;
            result = r.scan();
            require(result.read == 0 && r.fake.fetches == before && result.removed == 0, "an unchanged tree reads nothing again: read " + result.read + ", fetched " + (r.fake.fetches - before) + ", removed " + result.removed + ", states " + r.row(a).state + "/" + r.row(b).state + "/" + r.row(c).state + "/" + r.row(d).state);
            mark.accept("Sources: indexing text, unsupported and unreadable files; search by text and name; unchanged files are not read again");
        } finally { r.close(); }
    }

    private static void refresh(Context app, Consumer<String> mark) {
        Rig r = new Rig(app);
        try (Clock.Fixed clock = Clock.fix(1_800_000_000_000L)) {
            String docs = r.fake.dir(r.root, "Docs"), other = r.fake.dir(r.root, "Other");
            String a = r.fake.file(docs, "alpha.txt", "alpha apple"), u = r.fake.file(docs, "mystery.dat", new byte[]{0, 9, 8, 7, (byte) 250});
            r.scan();
            String rowId = r.row(a).id;
            String stackId = "bills";
            r.store.addToStack(SourceStore.ITEM_SOURCE, rowId, stackId);
            // rename and move keep the id (and so the stacks), and the name search follows
            r.fake.rename(a, "alpha-renamed.txt");
            r.fake.move(a, other);
            int fetches = r.fake.fetches;
            r.scan();
            SourceStore.FileRow moved = r.row(a);
            require(moved != null && moved.id.equals(rowId) && moved.path.equals("Other/alpha-renamed.txt") && moved.parentDocId.equals(other), "rename and move keep the row id");
            require(r.store.stacksOf(SourceStore.ITEM_SOURCE, rowId).equals(Collections.singletonList(stackId)), "stack kept after rename and move");
            require(r.fake.fetches == fetches, "rename and move do not read the file again");
            require(r.store.search("renamed", null).size() == 1 && r.store.search("alpha", null).size() == 1, "the new name is searchable, one result");
            require(r.row(a).missingSince == 0, "a moved file is not missing");
            // changed content: version changes, one re-read, new text
            r.fake.setText(a, "alpha avocado");
            SourceIndexer.Result result = r.scan();
            require(result.read == 1 && r.store.search("avocado", null).size() == 1 && r.store.search("apple", null).isEmpty(), "a changed version is read again and replaces the text");
            // type fingerprint: an unsupported row is read again when the fingerprint differs; one with the current fingerprint is not
            fetches = r.fake.fetches;
            r.scan();
            require(r.fake.fetches == fetches, "unsupported with the current fingerprint is left alone");
            String plain = r.fake.file(docs, "later.txt", "this one is plain text really");
            r.scan();
            SourceStore.FileRow later = r.row(plain);
            require(later.state.equals(SourceStore.INDEXED), "(setup) read by the text handler: " + later.state);
            r.helper.getWritableDatabase().execSQL("UPDATE source_files SET state=?,handler_id='',handler_version=0,type_fingerprint='old:0',reason='Not a supported type of file.' WHERE id=?", new Object[]{SourceStore.UNSUPPORTED, later.id});
            r.helper.getWritableDatabase().execSQL("DELETE FROM source_search WHERE file_id=?", new Object[]{later.id});
            r.helper.getWritableDatabase().execSQL("INSERT INTO source_search(file_id,title,body) VALUES(?,?,'')", new Object[]{later.id, later.name});
            require(r.store.search("plain text really", null).isEmpty(), "(setup) the row looks unsupported");
            fetches = r.fake.fetches;
            r.scan();
            require(r.fake.fetches == fetches + 1 && r.row(plain).state.equals(SourceStore.INDEXED) && r.row(plain).fingerprint.equals(FileTypes.fingerprint()), "a type fingerprint bump re-reads a previously unsupported row (and only that row): fetches " + (r.fake.fetches - fetches) + ", state " + r.row(plain).state + ", fp " + r.row(plain).fingerprint);
            require(r.store.search("plain text really", null).size() == 1, "its text is searchable now");
            // handler version bump on an indexed row
            r.helper.getWritableDatabase().execSQL("UPDATE source_files SET handler_version=0 WHERE id=?", new Object[]{r.row(a).id});
            fetches = r.fake.fetches;
            r.scan();
            require(r.fake.fetches == fetches + 1, "a handler version change re-reads the files of that type");
            mark.accept("Sources: rename and move keep the id and stacks, changed version re-reads, fingerprint bump re-reads previously unsupported rows only");
        } finally { r.close(); }
    }

    private static void noRemovalOnBadAnswers(Context app, Consumer<String> mark) {
        Rig r = new Rig(app);
        try (Clock.Fixed clock = Clock.fix(1_800_000_000_000L)) {
            String docs = r.fake.dir(r.root, "Docs");
            String a = r.fake.file(docs, "keep1.txt", "kept one"), b = r.fake.file(r.root, "keep2.txt", "kept two");
            SourceReader.Entry docsEntry = r.fake.entry(docs);
            r.scan();
            int rows = r.files();
            r.store.addToStack(SourceStore.ITEM_SOURCE, r.row(a).id, "bills");
            r.fake.gone.add(a); r.fake.gone.add(b);   // Filen would even confirm "gone"; the answers below must still change nothing
            for (SourceReader.Status bad : new SourceReader.Status[]{SourceReader.Status.STALE, SourceReader.Status.ERROR, SourceReader.Status.SIGNED_OUT}) {
                r.fake.defaultStatus = bad;
                clock.advance(30 * 60_000L);
                SourceIndexer.Result result = r.scan();
                require(result.removed == 0 && r.files() == rows && r.count("SELECT COUNT(*) FROM source_files WHERE missing_since>0") == 0, bad + ": nothing removed or marked");
            }
            r.fake.defaultStatus = SourceReader.Status.FRESH;
            r.fake.gone.clear();
            // a stale (FRESH=false) answer that lists NOTHING must not remove anything, even after a long time
            r.fake.defaultStatus = SourceReader.Status.STALE;
            r.fake.children.put(r.root, new ArrayList<>());
            clock.advance(60 * 60_000L);
            SourceIndexer.Result result = r.scan();
            require(result.removed == 0 && r.files() == rows && SourceStore.UNREACHABLE.equals(r.source().state), "an empty stale listing removes nothing: " + r.source().state);
            // an error in a subfolder: other folders' omissions are never confirmed in that scan
            r.fake.defaultStatus = SourceReader.Status.FRESH;
            r.fake.children.put(r.root, new ArrayList<>(Arrays.asList(docsEntry)));
            r.fake.status.put(docs, SourceReader.Status.ERROR);
            clock.advance(60 * 60_000L);
            r.scan();   // root fresh, b omitted (first time): marked; docs unreachable
            clock.advance(60 * 60_000L);
            result = r.scan();
            require(!result.complete && result.removed == 0 && r.row(b) != null, "a scan with an unreadable folder is never complete, so it removes nothing");
            r.fake.status.clear();
            // account change: pauses, removes nothing, and resumes when the account is back
            r.fake.account = "acct-2";
            r.fake.children.put(r.root, new ArrayList<>());
            clock.advance(60 * 60_000L);
            result = r.scan();
            require(result.stopped.equals("account") && result.removed == 0 && r.files() == rows && SourceStore.ACCOUNT_CHANGED.equals(r.source().state), "another account pauses the source: " + r.source().state);
            int read = r.fake.fetches;
            r.fake.account = "acct-1";
            r.fake.children.put(r.root, new ArrayList<>(Arrays.asList(docsEntry)));
            r.scan();
            require(!SourceStore.ACCOUNT_CHANGED.equals(r.source().state), "the original account resumes the source");
            // a missing grant
            r.fake.granted = false;
            result = r.scan();
            require(result.removed == 0 && r.files() > 0 && SourceStore.ACCESS_LOST.equals(r.source().state), "a lost grant removes nothing");
            r.fake.granted = true;
            mark.accept("Sources: stale, error, signed-out, empty-stale, partial-scan, another account and lost grant never remove (even when Filen would say gone)");
        } finally { r.close(); }
    }

    private static void confirmation(Context app, Consumer<String> mark) {
        Rig r = new Rig(app);
        try (Clock.Fixed clock = Clock.fix(1_800_000_000_000L)) {
            String docs = r.fake.dir(r.root, "Docs");
            String a = r.fake.file(docs, "gone-soon.txt", "ephemeral words"), b = r.fake.file(docs, "stays.txt", "stable words"), c = r.fake.file(docs, "opened.txt", "opened words");
            r.scan();
            String aRow = r.row(a).id, cRow = r.row(c).id;
            r.store.addToStack(SourceStore.ITEM_SOURCE, aRow, "bills");
            r.store.addTag(SourceStore.ITEM_SOURCE, aRow, tag(r, "t1"));
            // omitted by a FRESH listing, Filen cannot confirm: marked missing and hidden, not removed
            r.fake.delete(a); r.fake.delete(c);
            SourceIndexer.Result result = r.scan();
            require(result.removed == 0 && r.row(a) != null && r.row(a).missingSince > 0, "omitted once: marked, not removed");
            require(r.store.search("ephemeral", null).isEmpty() && r.store.files(r.sourceId).size() == 1, "a file waiting for confirmation is hidden from search and lists");
            require(r.fake.goneAsked >= 2, "Filen was asked to open the omitted ids");
            // 9 minutes later: still not enough
            clock.advance(9 * 60_000L);
            result = r.scan();
            require(result.removed == 0 && r.row(a) != null, "9 minutes later: still kept");
            // it comes back: the mark is cleared and nothing was lost
            r.fake.put(docs, new SourceReader.Entry(a, "gone-soon.txt", "text/plain", 15, 1, "v1", false)); r.fake.content.put(a, "ephemeral words".getBytes(StandardCharsets.UTF_8));
            r.scan();
            require(r.row(a).missingSince == 0 && r.row(a).id.equals(aRow) && r.store.stacksOf(SourceStore.ITEM_SOURCE, aRow).size() == 1 && r.store.search("ephemeral", null).size() == 1, "a file that reappears keeps its row, stack and text");
            // omitted again; a second FRESH scan 10 minutes after the first omission removes it
            r.fake.delete(a);
            r.scan();
            long firstMark = r.row(a).missingSince;
            clock.advance(10 * 60_000L - 1);
            r.scan();
            require(r.row(a) != null, "one millisecond short of 10 minutes: kept");
            clock.advance(2);
            result = r.scan();
            require(r.row(a) == null && result.removed >= 1, "a second FRESH scan 10 minutes later removes it");
            require(r.count("SELECT COUNT(*) FROM item_stacks WHERE item_id=?", aRow) == 0 && r.count("SELECT COUNT(*) FROM item_tags WHERE item_id=?", aRow) == 0
                    && r.count("SELECT COUNT(*) FROM source_search WHERE file_id=?", aRow) == 0, "its stacks, tags and text are removed with it");
            // c: Filen says FileNotFound on open -> removed in the same complete scan
            // (c was removed earlier by the same rule? it has been omitted for >10 minutes, so yes)
            require(r.row(c) == null, "the other omitted file went on the same rules");
            require(r.row(b) != null && r.store.search("stable", null).size() == 1, "files that were never omitted are untouched");
            // a vanished file confirmed by Filen (FileNotFoundException on open) goes at once
            String d = r.fake.file(docs, "instant.txt", "instant words");
            r.scan();
            r.fake.delete(d); r.fake.gone.add(d);
            result = r.scan();
            require(r.row(d) == null && result.removed == 1, "FileNotFoundException on open confirms at once");
            mark.accept("Sources: removal needs a FRESH omission plus Filen's FileNotFound, or a second FRESH scan 10 minutes later; reappearing files are kept");
        } finally { r.close(); }
    }

    private static String tag(Rig r, String name) {
        String id = UUID.randomUUID().toString();
        r.helper.getWritableDatabase().execSQL("INSERT INTO tags(id,name) VALUES(?,?)", new Object[]{id, name + id.substring(0, 4)});
        return id;
    }

    private static void saveOver(Context app, Consumer<String> mark) {
        Rig r = new Rig(app);
        try (Clock.Fixed clock = Clock.fix(1_800_000_000_000L)) {
            String docs = r.fake.dir(r.root, "Docs");
            String old = r.fake.file(docs, "report.txt", "version one report");
            r.fake.file(docs, "other.txt", "other file");
            r.scan();
            String oldRow = r.row(old).id, tagId = tag(r, "keep");
            r.store.addToStack(SourceStore.ITEM_SOURCE, oldRow, "bills");
            r.store.addToStack(SourceStore.ITEM_SOURCE, oldRow, "banking");
            r.store.addTag(SourceStore.ITEM_SOURCE, oldRow, tagId);
            String fresh = r.fake.saveOver(old, "version two report");
            SourceIndexer.Result result = r.scan();
            SourceStore.FileRow neu = r.row(fresh);
            require(neu != null && !neu.id.equals(oldRow), "the new id is a new row");
            require(r.store.stacksOf(SourceStore.ITEM_SOURCE, neu.id).equals(Arrays.asList("banking", "bills")), "stacks carried to the new id");
            require(r.store.tagsOf(SourceStore.ITEM_SOURCE, neu.id).equals(Collections.singletonList(tagId)), "tags carried to the new id");
            require(r.store.search("report", null).size() == 1 && r.store.search("report", null).get(0).id.equals(neu.id) && r.store.search("two", null).size() == 1, "one visible result with the new text");
            require(r.row(old) != null && r.row(old).missingSince > 0, "the old id waits for the removal rules (not removed by the replacement alone)");
            clock.advance(11 * 60_000L);
            r.scan();
            require(r.row(old) == null && r.store.stacksOf(SourceStore.ITEM_SOURCE, neu.id).size() == 2, "after confirmation the old row goes and the new one keeps the stacks");
            // not the same parent: a same-name file elsewhere is not a replacement
            String lonely = r.fake.file(docs, "solo.txt", "solo text");
            r.scan();
            r.store.addToStack(SourceStore.ITEM_SOURCE, r.row(lonely).id, "ids");
            r.fake.delete(lonely);
            String elsewhere = r.fake.file(r.root, "solo.txt", "solo text elsewhere");
            r.scan();
            require(r.store.stacksOf(SourceStore.ITEM_SOURCE, r.row(elsewhere).id).isEmpty(), "same name in another folder is a different file");
            // listing lag: the old id is gone and the new one is not listed yet for one scan, then it shows up
            String lagOld = r.fake.file(docs, "lag.txt", "lag one");
            r.scan();
            String lagRow = r.row(lagOld).id, lagTag = tag(r, "lag");
            r.store.addToStack(SourceStore.ITEM_SOURCE, lagRow, "medical");
            r.store.addTag(SourceStore.ITEM_SOURCE, lagRow, lagTag);
            r.fake.delete(lagOld);
            r.fake.gone.add(lagOld);   // Filen says the old id is gone: removed at once, nothing listed in its place
            r.scan();
            require(r.row(lagOld) == null && r.count("SELECT COUNT(*) FROM source_carry WHERE name=?", "lag.txt") == 1, "lag: old row removed, its marks kept for the new file");
            clock.advance(5 * 60_000L);
            String lagNew = r.fake.file(docs, "lag.txt", "lag two");
            r.scan();
            SourceStore.FileRow lagRowNew = r.row(lagNew);
            require(lagRowNew != null && r.store.stacksOf(SourceStore.ITEM_SOURCE, lagRowNew.id).equals(Collections.singletonList("medical"))
                    && r.store.tagsOf(SourceStore.ITEM_SOURCE, lagRowNew.id).equals(Collections.singletonList(lagTag)), "lag: stacks and tags end up on the new row");
            require(r.count("SELECT COUNT(*) FROM source_carry WHERE name=?", "lag.txt") == 0, "lag: the record is deleted once used");
            // expiry after one hour
            String lateOld = r.fake.file(docs, "late.txt", "late one");
            r.scan();
            r.store.addToStack(SourceStore.ITEM_SOURCE, r.row(lateOld).id, "ids");
            r.fake.delete(lateOld); r.fake.gone.add(lateOld);
            r.scan();
            clock.advance(61 * 60_000L);
            String lateNew = r.fake.file(docs, "late.txt", "late two");
            r.scan();
            require(r.store.stacksOf(SourceStore.ITEM_SOURCE, r.row(lateNew).id).isEmpty() && r.count("SELECT COUNT(*) FROM source_carry WHERE name=?", "late.txt") == 0, "lag: after an hour the marks are not carried");
            mark.accept("Sources: save-over (new id, same parent and name) carries stacks and tags, also through a scan that lists neither id; another folder's same name does not; expires after an hour");
        } finally { r.close(); }
    }

    private static void unshare(Context app, Consumer<String> mark) {
        Rig r = new Rig(app);
        try (Clock.Fixed clock = Clock.fix(1_800_000_000_000L)) {
            String share = r.fake.dir(r.root, "Sam shares");
            String sub = r.fake.dir(share, "Sub");
            String f1 = r.fake.file(share, "one.txt", "shared words one"), f2 = r.fake.file(sub, "two.txt", "shared words two");
            String keep = r.fake.dir(r.root, "Another share");
            r.fake.file(keep, "three.txt", "kept words three");
            r.scan();
            r.store.addToStack(SourceStore.ITEM_SOURCE, r.row(f2).id, "ids");
            r.fake.delete(share);   // the share disappears from the FRESH root listing; nothing below it can be listed any more
            r.fake.children.remove(share); r.fake.children.remove(sub);
            r.fake.gone.add(f1); r.fake.gone.add(f2);   // Filen says the files are gone (their share is)
            SourceIndexer.Result result = r.scan();
            require(r.row(share) != null && r.row(share).missingSince > 0, "the share folder row waits for the 10-minute rule (a folder cannot be opened)");
            require(r.row(f1) == null && r.row(f2) == null, "files under it are confirmed by FileNotFound and removed");
            require(r.store.search("shared", null).isEmpty() && r.store.search("kept", null).size() == 1, "its text is gone, the other share stays");
            clock.advance(10 * 60_000L + 1);
            r.scan();
            require(r.row(share) == null && r.row(sub) == null && r.row(keep) != null, "after 10 minutes the share and its subtree are removed");
            require(r.count("SELECT COUNT(*) FROM item_stacks WHERE item_kind='source' AND item_id NOT IN (SELECT id FROM source_files)") == 0, "no orphan stack rows");
            // without Filen's confirmation: the whole subtree waits the same 10 minutes
            String share2 = r.fake.dir(r.root, "Second share"); String g = r.fake.file(share2, "g.txt", "second shared words");
            r.scan();
            r.fake.delete(share2); r.fake.children.remove(share2);
            r.scan();
            require(r.row(g) != null && r.row(g).missingSince > 0 && r.store.search("second", null).isEmpty(), "unconfirmed subtree is hidden but kept");
            clock.advance(10 * 60_000L + 1);
            r.scan();
            require(r.row(g) == null && r.row(share2) == null, "and removed by the second FRESH scan");
            mark.accept("Sources: unshare (a share missing from the FRESH root listing) removes its subtree after the same confirmation rule");
        } finally { r.close(); }
    }

    private static void pauses(Context app, Consumer<String> mark) {
        Rig r = new Rig(app);
        try (Clock.Fixed clock = Clock.fix(1_800_000_000_000L)) {
            for (int i = 0; i < 6; i++) r.fake.file(r.root, "p" + i + ".txt", "pause words " + i);
            r.env.allowed = false;
            SourceIndexer.Result result = r.scan();
            require(result.stopped.equals("locked") && r.fake.fetches == 0 && SourceStore.WAITING.equals(r.source().state), "locked: nothing is read");
            r.env.allowed = true; r.env.battery = false;
            result = r.scan();
            require(result.stopped.equals("battery") && r.fake.fetches == 0 && SourceStore.WAITING.equals(r.source().state), "low battery: nothing is read");
            r.env.battery = true;
            // lock during a run: it stops after the file in hand, the rest stay pending, the next run finishes
            final SourceIndexer.Control control = new SourceIndexer.Control();
            r.fake.onFetch = id -> { if (r.fake.fetches == 3) control.stop("locked"); };
            result = r.indexer.scan(r.source(), control);
            SourceStore.Progress p = r.store.progress(r.sourceId);
            require(result.stopped.equals("locked") && p.pending > 0 && p.pending < 6 && p.indexed >= 2 && p.files == 6, "stopped part way: " + p.indexed + " read, " + p.pending + " pending");
            require(SourceStore.WAITING.equals(p.state), "state is waiting");
            r.fake.onFetch = id -> {};
            int fetches = r.fake.fetches;
            result = r.scan();
            p = r.store.progress(r.sourceId);
            require(p.pending == 0 && p.indexed == 6 && r.fake.fetches - fetches <= 4 && result.complete, "the next run resumes from the database and reads only the rest");
            // an interrupted scan (process killed) is only waiting
            r.helper.getWritableDatabase().execSQL("UPDATE sources SET state='scanning'");
            r.store.recoverInterrupted();
            require(SourceStore.WAITING.equals(r.source().state), "a scan interrupted by a restart is waiting");
            mark.accept("Sources: pauses on app lock and low battery, resumes from the database, never removes while paused");
        } finally { r.close(); }
    }


    // ---- extraction in its own process (review C2, H3) ---------------------------------------------------------------------------

    private static boolean nativeMapped() {
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"))) {
            for (String line; (line = in.readLine()) != null; ) if (line.contains("libstacknative")) return true;
        } catch (IOException unreadable) { return false; }
        return false;
    }

    private static boolean alive(int pid) { return new File("/proc/" + pid).exists(); }

    private static void extraction(Context test, Context app, Consumer<String> mark) throws Exception {
        final boolean loadedBefore = nativeMapped();
        final ExtractClient client = new ExtractClient(app);
        final List<ExtractClient.Outcome.Kind> kinds = new ArrayList<>();
        final List<android.os.IBinder> binders = new ArrayList<>();
        Extractor scripted = new Extractor() {
            @Override public ExtractClient.Outcome extract(File file, String typeId, org.stackdocs.app.types.Limits limits, CancellationSignal signal) {
                String head;
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) { byte[] b = new byte[8]; int n = in.read(b); head = new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8); }
                catch (IOException e) { head = ""; }
                String id = head.startsWith("HANG") ? "checks-hang" : head.startsWith("CRASH") ? "checks-crash" : head.startsWith("OOM") ? "checks-oom" : typeId;
                org.stackdocs.app.types.Limits use = id.equals(typeId) ? limits : new org.stackdocs.app.types.Limits(limits.maxBytes, 1, 1000, 1000);
                ExtractClient.Outcome out = client.extract(file, id, use, signal);
                if (!id.equals(typeId)) { kinds.add(out.kind); binders.add(client.lastBinder); }
                return out;
            }
            @Override public ExtractClient.Outcome extractPages(File file, String typeId, String mode, int[] pages, org.stackdocs.app.types.Limits limits, CancellationSignal signal) {
                return client.extractPages(file, typeId, mode, pages, limits, signal);
            }
            @Override public void release() { client.release(); }
        };
        Rig r = new Rig(app, scripted);
        try {
            String hang = r.fake.file(r.root, "hang.txt", "HANG forever"), crash = r.fake.file(r.root, "crash.txt", "CRASH now"), oom = r.fake.file(r.root, "oom.txt", "OOM please");
            String good = r.fake.file(r.root, "good.txt", "good words about apples");
            byte[] pdf;
            try (java.io.InputStream in = test.getAssets().open("types/pdf/born-digital.pdf")) { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); pdf = o.toByteArray(); }
            String pdfId = r.fake.file(r.root, "paper.pdf", pdf);
            int myPid = android.os.Process.myPid();
            r.scan();
            require(android.os.Process.myPid() == myPid, "the main process survived");
            require(r.row(good).state.equals(SourceStore.INDEXED) && r.store.text(r.row(good).id).contains("apples"), "a good file is read in the extract process: " + r.row(good).state);
            require(r.row(pdfId).state.equals(SourceStore.INDEXED) && !r.store.text(r.row(pdfId).id).trim().isEmpty(), "a PDF is read in the extract process (descriptor handed over): " + r.row(pdfId).state + "/" + r.row(pdfId).reason);
            for (String id : new String[]{hang, crash, oom}) require(r.row(id).state.equals(SourceStore.PENDING) && r.row(id).attempts == 1, "the attempt is counted before the hand-over and kept (attempt 1): " + r.row(id).state + " " + r.row(id).attempts);
            require(kinds.contains(ExtractClient.Outcome.Kind.TIMEOUT) && kinds.contains(ExtractClient.Outcome.Kind.DIED), "a hang is a timeout, a crash and an out-of-memory are dead binders: " + kinds);
            android.os.IBinder hungBinder = binders.get(kinds.indexOf(ExtractClient.Outcome.Kind.TIMEOUT));
            for (int n = 0; n < 40 && hungBinder.pingBinder(); n++) Thread.sleep(100);
            require(!hungBinder.pingBinder(), "the hung extract process was killed");
            r.scan(); r.scan();
            for (String id : new String[]{hang, crash, oom})
                require(r.row(id).state.equals(SourceStore.UNREADABLE) && SourceIndexer.GAVE_UP.equals(r.row(id).reason) && r.store.search(r.row(id).name.replace(".txt", ""), null).size() == 1, "after 3 tries the file is unreadable with the reason and still found by name: " + r.row(id).state);
            require(r.row(good).state.equals(SourceStore.INDEXED) && r.row(good).attempts == 0, "a file that was read has its attempts reset");
            // A file that kills the reader and the process counting for it: attempts already at the limit are not tried again.
            String late = r.fake.file(r.root, "late.txt", "late words");
            r.scan();
            r.helper.getWritableDatabase().execSQL("UPDATE source_files SET state='pending', attempts=3 WHERE provider_doc_id=?", new Object[]{late});
            int before = kinds.size(); r.scan();
            require(r.row(late).state.equals(SourceStore.UNREADABLE) && kinds.size() == before, "three counted attempts and no more: the file is marked without a fourth try");
            require(loadedBefore || !nativeMapped(), "the main process never loaded libstacknative for folder files");
            mark.accept("Sources: extraction in its own process; a hanging, crashing and out-of-memory reader are killed and the run goes on; unreadable after 3 counted tries; main process alive; no native library in it");
        } finally { client.kill(); r.close(); }
    }


    // ---- PDFs read in page chunks ---------------------------------------------------------------------------------------------------

    private static byte[] asset(Context test, String name) throws IOException {
        try (java.io.InputStream in = test.getAssets().open(name)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    /** The real extract client, with every call recorded (and, for a check, a hook that can fail or stop a chunk). */
    private static final class Chunks implements Extractor {
        final ExtractClient client; final List<String> log = new ArrayList<>(); final List<Long> millis = new ArrayList<>(), limitsMs = new ArrayList<>();
        final List<Integer> ocrPages = new ArrayList<>(); int ocrCalls, layerCalls;
        int failPage = -1;   // an OCR chunk that holds this page hangs (the reader is killed at its limit)
        SourceIndexer.Control stopAfterFirstChunk;
        Chunks(Context app) { client = new ExtractClient(app); }
        @Override public ExtractClient.Outcome extract(File file, String typeId, org.stackdocs.app.types.Limits limits, CancellationSignal signal) { return client.extract(file, typeId, limits, signal); }
        @Override public ExtractClient.Outcome extractPages(File file, String typeId, String mode, int[] pages, org.stackdocs.app.types.Limits limits, CancellationSignal signal) {
            boolean ocr = ExtractService.MODE_OCR.equals(mode);
            String id = typeId; org.stackdocs.app.types.Limits use = limits;
            if (ocr && failPage >= 0) for (int page : pages) if (page == failPage) { id = "checks-hang"; use = new org.stackdocs.app.types.Limits(limits.maxBytes, 1, 1000, 1000); }
            long t0 = SystemClock.elapsedRealtime();
            ExtractClient.Outcome out = client.extractPages(file, id, mode, pages, use, signal);
            long ms = SystemClock.elapsedRealtime() - t0;
            if (ocr) { ocrCalls++; for (int page : pages) ocrPages.add(page); millis.add(ms); limitsMs.add(use.maxMillis); } else layerCalls++;
            log.add(mode + (pages == null ? "" : Arrays.toString(pages)) + " " + out.kind + " " + ms + "ms limit " + use.maxMillis);
            if (ocr && stopAfterFirstChunk != null) { stopAfterFirstChunk.stop("cancelled"); stopAfterFirstChunk = null; }
            return out;
        }
        @Override public void release() { client.release(); }
    }

    private static final String[] SCAN_WORDS = {"alpaca", "bobcat", "cormorant", "dolphin", "echidna", "flamingo", "gazelle", "hedgehog", "iguana", "jackal", "kingfisher", "lemur",
            "meerkat", "narwhal", "ostrich", "pelican", "quail", "raccoon", "salamander", "tortoise", "urchin", "vulture", "walrus", "yak", "zebra", "badger", "cheetah", "dragonfly", "eagle", "ferret"};

    /**
     * Scanned PDFs are read page by page: a 30-page image-only PDF is fully indexed (its time is reported), a stopped run resumes after the last
     * finished chunk without reading a finished page again, and a page that hangs the reader is left out after three tries while the file stays
     * indexed with the others ("Some pages could not be read").
     */
    private static void chunkedPdfs(Context test, Context app, Consumer<String> mark) throws Exception {
        // 1. thirty scanned pages, in chunks, each with its own limit
        Chunks chunks = new Chunks(app);
        Rig r = new Rig(app, chunks);
        try {
            String id = r.fake.file(r.root, "scanned.pdf", asset(test, "sources/scanned-30-pages.pdf"));
            long t0 = SystemClock.elapsedRealtime();
            r.scan();
            long total = SystemClock.elapsedRealtime() - t0;
            SourceStore.FileRow row = r.row(id);
            require(row.state.equals(SourceStore.INDEXED) && row.reason.isEmpty(), "a 30-page scanned PDF is fully indexed: " + row.state + " / " + row.reason + " " + chunks.log);
            String text = r.store.text(row.id);
            int found = 0;
            for (String word : SCAN_WORDS) if (text.toLowerCase(java.util.Locale.ROOT).contains(word)) found++;
            require(found >= 28 && !r.store.search(SCAN_WORDS[0], null).isEmpty() && !r.store.search(SCAN_WORDS[29], null).isEmpty(), "OCR text of the first and last page is searchable (" + found + " of 30 page words)");
            Set<Integer> distinct = new HashSet<>(chunks.ocrPages);
            long max = 0, sum = 0;
            for (long ms : chunks.millis) { max = Math.max(max, ms); sum += ms; }
            require(chunks.layerCalls == 1 && chunks.ocrPages.size() == 30 && distinct.size() == 30, "one text-layer call, every page OCR'd once: " + chunks.ocrPages.size() + " " + distinct.size());
            require(chunks.ocrCalls >= 8 && chunks.ocrCalls <= 9, "chunks of 4 pages: " + chunks.ocrCalls + " calls");
            for (int i = 0; i < chunks.millis.size(); i++) require(chunks.millis.get(i) < chunks.limitsMs.get(i), "each chunk finished inside its own limit: " + chunks.log);
            require(r.count("SELECT COUNT(*) FROM source_pages") == 0, "page rows are gone once the file has its answer");
            mark.accept("Sources: 30-page scanned PDF read in " + chunks.ocrCalls + " chunks of up to 4 pages, fully indexed in " + total + " ms (" + (sum / Math.max(1, chunks.ocrCalls)) + " ms per chunk on average, slowest "
                    + max + " ms, chunk limit " + chunks.limitsMs.get(0) + " ms for 4 pages), " + found + " of 30 page words found");
        } finally { chunks.client.kill(); r.close(); }
        // 2. a stop after the first chunk: the next run goes on with the next page
        chunks = new Chunks(app);
        r = new Rig(app, chunks);
        try {
            String id = r.fake.file(r.root, "scanned6.pdf", asset(test, "sources/scanned-6-pages.pdf"));
            SourceIndexer.Control stop = new SourceIndexer.Control();
            chunks.stopAfterFirstChunk = stop;
            r.indexer.scan(r.source(), stop);
            SourceStore.FileRow row = r.row(id);
            require(row.state.equals(SourceStore.PENDING) && r.store.pageTotal(row.id) == 6 && r.count("SELECT COUNT(*) FROM source_pages WHERE file_id=? AND state='ok'", row.id) == 4,
                    "stopped after the first chunk: pending with 4 of 6 pages stored: " + row.state + " " + chunks.log);
            SourceStore.Progress progress = r.store.progress(r.sourceId);
            require(progress.pageTotal == 6 && progress.pageDone == 4 && FoldersActivity.stateText(progress).equals("Reading page 5 of 6"), "the Folders screen says which page: " + FoldersActivity.stateText(progress));
            int first = chunks.ocrPages.size();
            r.scan();
            row = r.row(id);
            List<Integer> second = chunks.ocrPages.subList(first, chunks.ocrPages.size());
            require(row.state.equals(SourceStore.INDEXED) && row.reason.isEmpty() && second.equals(Arrays.asList(4, 5)), "the next run reads only the pages that were left: " + second + " " + row.state);
            require(chunks.layerCalls == 1, "the text layers are not asked for again: " + chunks.layerCalls);
            mark.accept("Sources: a stopped PDF run resumes after the last finished chunk (pages " + chunks.ocrPages + "), progress text \"Reading page 5 of 6\"");
        } finally { chunks.client.kill(); r.close(); }
        // 3. one page that hangs the reader costs only itself
        chunks = new Chunks(app);
        r = new Rig(app, chunks);
        try {
            String id = r.fake.file(r.root, "scanned6.pdf", asset(test, "sources/scanned-6-pages.pdf"));
            chunks.failPage = 2;
            r.scan();
            SourceStore.FileRow row = r.row(id);
            String text = r.store.text(row.id).toLowerCase(java.util.Locale.ROOT);
            require(row.state.equals(SourceStore.INDEXED) && SourceIndexer.SOME_PAGES.equals(row.reason), "the file stays indexed: " + row.state + " / " + row.reason + " " + chunks.log);
            require(text.contains(SCAN_WORDS[0]) && text.contains(SCAN_WORDS[1]) && !text.contains(SCAN_WORDS[2]) && text.contains(SCAN_WORDS[3]) && text.contains(SCAN_WORDS[5]), "the other pages are in the text, the bad page is not");
            int hangs = 0; for (String line : chunks.log) if (line.contains("TIMEOUT")) hangs++;
            require(hangs == 3, "the bad page was tried 3 times (once with its chunk, twice alone): " + chunks.log);
            mark.accept("Sources: a page that hangs the reader is left out after 3 counted tries, the file stays indexed with the others (\"" + SourceIndexer.SOME_PAGES + "\"); calls " + chunks.log.size());
        } finally { chunks.client.kill(); r.close(); }
    }

    // ---- isolated extraction (the extract and viewer processes) -----------------------------------------------------------------------------

    private static boolean processAlive(Context app, int pid) {
        android.app.ActivityManager manager = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> running = manager == null ? null : manager.getRunningAppProcesses();
        if (running != null) for (android.app.ActivityManager.RunningAppProcessInfo info : running) if (info.pid == pid) return true;
        return alive(pid);
    }

    private static File cacheFile(Context app, String name, byte[] bytes) throws IOException {
        File dir = new File(app.getCacheDir(), "isolation-checks");
        dir.mkdirs();
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
        return f;
    }

    /**
     * The extract process is an isolated process: it cannot open the library database, its files directory, the network or Stack's Keystore keys (it reports
     * what it could reach); the main process survives its crash; text, PDF text, Ami Pro, OCR (the OCR process, pixels only) and the viewer's page drawing all
     * work through it; a native "timeout" answer is delivered and the process then ends itself.
     */
    private static void isolation(Context test, Context app, Consumer<String> mark) throws Exception {
        String alias = app.getPackageName() + ".isolation.key";
        java.security.KeyStore keys = java.security.KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        javax.crypto.KeyGenerator generator = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(alias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build());
        generator.generateKey();
        ExtractClient client = new ExtractClient(app, ExtractService.class, "isolation-read");   // not the indexer's folder: the app's own scan sweeps that one
        ExtractClient viewer = new ExtractClient(app, RenderService.class, "isolation-page");
        final int myPid = android.os.Process.myPid();
        org.stackdocs.app.types.Limits quick = new org.stackdocs.app.types.Limits(64L << 20, 1000, 30_000, 100_000);
        try {
            require(keys.containsAlias(alias), "the main process holds the key");
            File probe = cacheFile(app, "probe.bin", new byte[]{1});
            ExtractClient.Outcome facts = client.extract(probe, "checks-sandbox", quick, new CancellationSignal());
            require(facts.kind == ExtractClient.Outcome.Kind.DONE && facts.extracted.status == org.stackdocs.app.types.Extracted.Status.TEXT, "the sandbox probe answers: " + facts.kind);
            String all = String.join(" | ", facts.extracted.pages);
            require(all.contains("isolated=true"), "the extract process runs as an isolated uid: " + all);
            require(all.contains("db=denied"), "it cannot open the library database: " + all);
            require(all.contains("files=denied") && all.contains("write=denied"), "it cannot list or write Stack's files directory: " + all);
            require(all.contains("net=denied") && !all.contains("ConnectException") && !all.contains("net=connected"), "it cannot use the network: " + all);
            require(!all.contains("sees the key"), "it cannot see Stack's Keystore keys: " + all);
            require(client.lastPid > 0 && client.lastPid != myPid && android.os.Process.myPid() == myPid, "the extract process is another process and the main process survives");
            // Reading works in it: text, a PDF's text layer, Ami Pro, a picture and a scanned PDF page by OCR (the OCR process gets drawn pixels only).
            File text = cacheFile(app, "words.txt", "isolated words about quinces".getBytes(StandardCharsets.UTF_8));
            ExtractClient.Outcome out = client.extract(text, "text", quick, new CancellationSignal());
            require(out.kind == ExtractClient.Outcome.Kind.DONE && String.join(" ", out.extracted.pages).contains("quinces"), "text is read in the isolated process: " + out.kind + " " + (out.extracted == null ? "" : out.extracted.status + " " + out.extracted.reason + " " + out.extracted.pages));
            File pdf = cacheFile(app, "born.pdf", asset(test, "types/pdf/born-digital.pdf"));
            out = client.extractPages(pdf, "pdf", ExtractService.MODE_LAYERS, null, quick, new CancellationSignal());
            require(out.kind == ExtractClient.Outcome.Kind.DONE && out.extracted.status == org.stackdocs.app.types.Extracted.Status.TEXT && !String.join("", out.extracted.pages.stream().map(x -> x == null ? "" : x).toArray(String[]::new)).trim().isEmpty(), "a PDF's text layer is read in the isolated process: " + out.kind);
            File sam = cacheFile(app, "synthetic.sam", asset(test, "types/amipro/synthetic.sam"));
            out = client.extract(sam, "amipro", quick, new CancellationSignal());
            require(out.kind == ExtractClient.Outcome.Kind.DONE && out.extracted.status == org.stackdocs.app.types.Extracted.Status.TEXT && !String.join("", out.extracted.pages).trim().isEmpty(), "an Ami Pro file is read in the isolated process: " + out.kind + " " + (out.extracted == null ? "" : out.extracted.status));
            File scanned = cacheFile(app, "scanned6.pdf", asset(test, "sources/scanned-6-pages.pdf"));
            out = client.extractPages(scanned, "pdf", ExtractService.MODE_OCR, new int[]{0, 1}, new org.stackdocs.app.types.Limits(64L << 20, 1000, 60_000, 100_000), new CancellationSignal());
            require(out.kind == ExtractClient.Outcome.Kind.DONE && out.extracted.pages.size() == 2 && out.extracted.pages.get(0).toLowerCase(java.util.Locale.ROOT).contains(SCAN_WORDS[0]) && out.extracted.pages.get(1).toLowerCase(java.util.Locale.ROOT).contains(SCAN_WORDS[1]),
                    "two scanned pages are OCR'd (drawn in the isolated process, read in the OCR process): " + out.kind + " " + (out.extracted == null ? "" : out.extracted.pages));
            File jpg = cacheFile(app, "synthetic.jpg", asset(test, "types/jpeg/synthetic.jpg"));
            out = client.extract(jpg, "jpeg", new org.stackdocs.app.types.Limits(64L << 20, 1, 60_000, 100_000), new CancellationSignal());
            require(out.kind == ExtractClient.Outcome.Kind.DONE && out.extracted.status == org.stackdocs.app.types.Extracted.Status.TEXT, "a picture is OCR'd through the same two processes: " + out.kind + " " + (out.extracted == null ? "" : out.extracted.status + " " + out.extracted.reason));
            // The viewer: one page at a time, in its own isolated process, as capped pixels.
            ExtractClient.Page page = viewer.render(pdf, "pdf", 0, 2400, 30_000, new CancellationSignal());
            require(page.drawn() && page.pages >= 1 && page.page == 0 && page.bitmap.getWidth() <= 3400 && page.bitmap.getHeight() <= 3400 && (long) page.bitmap.getWidth() * page.bitmap.getHeight() <= 2400L * 3400, "a PDF page is drawn by the viewer process within the caps: " + page.kind);
            int[] px = new int[page.bitmap.getWidth() * page.bitmap.getHeight()]; page.bitmap.getPixels(px, 0, page.bitmap.getWidth(), 0, 0, page.bitmap.getWidth(), page.bitmap.getHeight());
            boolean ink = false; for (int c : px) if ((c & 0xffffff) != 0xffffff) { ink = true; break; }
            require(ink, "the drawn page is not blank");
            int pages = page.pages; page.bitmap.recycle();
            page = viewer.render(scanned, "pdf", 999, 2400, 30_000, new CancellationSignal());
            require(page.drawn() && page.pages == 6 && page.page == 5, "a page number past the end shows the last page: " + page.kind + " " + page.page + "/" + page.pages);
            page.bitmap.recycle();
            page = viewer.render(jpg, "jpeg", 0, 2400, 30_000, new CancellationSignal());
            require(page.drawn() && page.pages == 1, "a picture is drawn by the viewer process");
            page.bitmap.recycle();
            // A page that asks for a huge canvas is drawn smaller, never larger than the caps.
            String huge = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200000 200000]>>endobj\ntrailer<</Root 1 0 R/Size 4>>\n%%EOF\n";
            File big = cacheFile(app, "huge.pdf", huge.getBytes(StandardCharsets.US_ASCII));
            page = viewer.render(big, "pdf", 0, 2400, 30_000, new CancellationSignal());
            require(!page.drawn() || ((long) page.bitmap.getWidth() * page.bitmap.getHeight() <= 2400L * 3400 && page.bitmap.getWidth() <= 3400 && page.bitmap.getHeight() <= 3400), "a huge page is drawn within the caps (or not at all)");
            if (page.bitmap != null) page.bitmap.recycle();
            // Garbage is not drawn; the main process is untouched and the next page draws.
            File junk = cacheFile(app, "junk.pdf", "not a pdf at all".getBytes(StandardCharsets.US_ASCII));
            page = viewer.render(junk, "pdf", 0, 2400, 30_000, new CancellationSignal());
            require(!page.drawn() && android.os.Process.myPid() == myPid, "a damaged file is not drawn and the main process is untouched");
            page = viewer.render(pdf, "pdf", 0, 2400, 30_000, new CancellationSignal());
            require(page.drawn() && page.pages == pages, "the viewer draws the next file after a bad one");
            page.bitmap.recycle();
            // A native "timeout" answer: delivered, then the process ends itself, so the next call starts a fresh one.
            File t = cacheFile(app, "timeout.bin", new byte[]{1});
            out = client.extract(t, "checks-timeout", quick, new CancellationSignal());
            require(out.kind == ExtractClient.Outcome.Kind.DONE && out.extracted.status == org.stackdocs.app.types.Extracted.Status.UNREADABLE && out.extracted.reason.contains("gave up"), "the timeout answer arrives: " + out.kind);
            final int ended = client.lastPid;
            final android.os.IBinder endedBinder = client.lastBinder;
            for (int n = 0; n < 40 && endedBinder.pingBinder(); n++) Thread.sleep(100);
            require(!endedBinder.pingBinder(), "the process that gave the timeout answer ended itself");
            out = client.extract(text, "text", quick, new CancellationSignal());
            require(out.kind == ExtractClient.Outcome.Kind.DONE && client.lastPid != ended && client.lastPid > 0, "the next file is read in a fresh process: " + out.kind + " " + ended + " -> " + client.lastPid);
            // A hung reader dies with the connection: killed by the client at its limit, gone afterwards.
            File hang = cacheFile(app, "hang.bin", new byte[]{1});
            out = client.extract(hang, "checks-hang", new org.stackdocs.app.types.Limits(1 << 20, 1, 1000, 1000), new CancellationSignal());
            final android.os.IBinder hungBinder = client.lastBinder;
            require(out.kind == ExtractClient.Outcome.Kind.TIMEOUT, "a hang is a timeout: " + out.kind);
            for (int n = 0; n < 40 && hungBinder.pingBinder(); n++) Thread.sleep(100);
            require(!hungBinder.pingBinder(), "the hung isolated process is gone (its binder is dead)");

            // ---- one fresh reader process per file; the process is gone before its answer is read (the pre-release review) ----
            File each = cacheFile(app, "each.txt", "one fresh process each".getBytes(StandardCharsets.UTF_8));
            int[] pids = new int[5]; long total = 0;
            for (int n = 0; n < pids.length; n++) {
                long began = android.os.SystemClock.elapsedRealtime();
                out = client.extract(each, "text", quick, new CancellationSignal());
                total += android.os.SystemClock.elapsedRealtime() - began;
                pids[n] = client.lastPid;
                require(out.kind == ExtractClient.Outcome.Kind.DONE && !client.lastBinder.pingBinder(), "after the file is read its process is gone: " + out.kind);
                if (n > 0) require(pids[n] != pids[n - 1], "each file gets a fresh reader process: " + pids[n - 1] + " -> " + pids[n]);
            }
            long perFile = total / pids.length;
            long startedAt = android.os.SystemClock.elapsedRealtime();
            require(client.prepare(), "the reader starts (versions learned)");
            long bind = android.os.SystemClock.elapsedRealtime() - startedAt;
            // A reader that keeps writing to its answer descriptor after it answered is ended before the answer is read.
            File flood = cacheFile(app, "flood.bin", new byte[]{1});
            out = client.extract(flood, "checks-flood", quick, new CancellationSignal());
            require(out.kind == ExtractClient.Outcome.Kind.DONE && out.extracted.status == org.stackdocs.app.types.Extracted.Status.TEXT && out.extracted.pages.contains("flooded") && !client.lastBinder.pingBinder(),
                    "an answer is read only after the process that wrote it ended, so a reader that keeps writing cannot change or grow it: " + out.kind);
            // The answer is read with its size cap enforced while reading: a file over the cap, or one that claims more than it has, is refused.
            File oversize = cacheFile(app, "huge.answer", new byte[(int) ExtractService.MAX_ANSWER + 4096]);
            try { ExtractService.read(oversize); require(false, "an answer over the cap is read"); } catch (IOException refused) { /* the cap */ }
            java.io.ByteArrayOutputStream forged = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream forgedOut = new java.io.DataOutputStream(forged);
            forgedOut.writeInt(ExtractService.MAGIC); forgedOut.writeByte(0); forgedOut.writeInt(Integer.MAX_VALUE);
            File lie = cacheFile(app, "lie.answer", forged.toByteArray());
            try { ExtractService.read(lie); require(false, "an answer with a forged length is read"); } catch (IOException refused) { /* the length */ }

            // ---- the OCR process does OCR of drawn pixels and nothing else (item 5) ----
            ExtractClient ocr = new ExtractClient(app, OcrService.class, "isolation-ocr");
            try {
                out = ocr.extract(text, "text", quick, new CancellationSignal());
                require(out.kind == ExtractClient.Outcome.Kind.DONE && out.extracted.status == org.stackdocs.app.types.Extracted.Status.UNREADABLE && out.extracted.pages.isEmpty(), "the OCR process refuses to read a text file: " + out.kind + " " + out.extracted.status);
                out = ocr.extract(probe, "checks-sandbox", quick, new CancellationSignal());
                require(out.extracted.status != org.stackdocs.app.types.Extracted.Status.TEXT, "it does not run the sandbox probe or any reader");
                out = ocr.extractPages(pdf, "pdf", ExtractService.MODE_LAYERS, null, quick, new CancellationSignal());
                require(out.extracted.status != org.stackdocs.app.types.Extracted.Status.TEXT, "it refuses a PDF text-layer request");
                out = ocr.extractPages(pdf, "pdf", ExtractService.MODE_OCR, new int[]{0}, quick, new CancellationSignal());
                require(out.extracted.status != org.stackdocs.app.types.Extracted.Status.TEXT, "it refuses a PDF given to it directly (only drawn pixels are OCR'd)");
                out = ocr.extract(jpg, "jpeg", quick, new CancellationSignal());
                require(out.extracted.status != org.stackdocs.app.types.Extracted.Status.TEXT, "it refuses a picture given to it directly");
                out = ocr.extract(text, ExtractService.OCR_PIXELS, quick, new CancellationSignal());
                require(out.extracted.status != org.stackdocs.app.types.Extracted.Status.TEXT, "it refuses OCR_PIXELS with something that is not drawn pixels");
                ExtractClient.Page none = ocr.render(pdf, "pdf", 0, 2400, 30_000, new CancellationSignal());
                require(!none.drawn(), "it refuses to draw a page");
                require(OcrService.class.getDeclaredMethod("accepts", int.class, android.os.Bundle.class) != null, "the refusal is in the service itself");
            } finally { ocr.kill(); }

            // ---- can Tesseract start inside the isolated process (item 6)? ----
            boolean fromMemory = false;
            for (java.lang.reflect.Method m : com.googlecode.tesseract.android.TessBaseAPI.class.getDeclaredMethods())
                if (m.getName().toLowerCase(java.util.Locale.ROOT).contains("init")) for (Class<?> c : m.getParameterTypes()) if (c == byte[].class || c == java.nio.ByteBuffer.class || c == android.os.ParcelFileDescriptor.class || c == java.io.FileDescriptor.class) fromMemory = true;
            require(!fromMemory, "tesseract4android now has an init that takes memory or a descriptor: OCR can move into the isolated process (see the design, section 7)");
            mark.accept("Sources: one fresh reader process per file (" + perFile + " ms per small file with a fresh process, of which about " + bind + " ms is starting and pinging it), the process is gone before its answer is read (a writer that keeps writing is ended), answer size cap enforced while reading; the OCR process refuses everything but drawn pixels; tesseract4android 4.9.0 has no init from memory or a descriptor, so OCR stays in its own process");

            mark.accept("Sources: isolated extraction - extract process uid isolated (" + all.substring(0, Math.min(40, all.length())) + "), cannot open the DB, files, network or Keystore key; text, PDF text, Ami Pro, picture and scanned-page OCR work through it; viewer draws pages (PDF, picture, huge, junk) in its own isolated process within the pixel caps; timeout answer then self-end; hang killed");
        } finally {
            client.kill(); viewer.kill();
            try { keys.deleteEntry(alias); } catch (java.security.KeyStoreException ignored) { /* gone */ }
            File[] left = new File(app.getCacheDir(), "isolation-checks").listFiles(); if (left != null) for (File f : left) f.delete();
        }
    }


    /** "Shared by me" files that an own folder already covers (same Filen UUID) are not downloaded or read a second time. */
    private static void sharedByMeCovered(Context app, Consumer<String> mark) throws Exception {
        Rig r = new Rig(app);
        try {
            String uuid = UUID.randomUUID().toString(), lone = UUID.randomUUID().toString();
            r.helper.getWritableDatabase().execSQL("UPDATE sources SET kind='own', title='Home files', partner_name='' WHERE id=?", new Object[]{r.sourceId});
            String outId = UUID.randomUUID().toString(), outRoot = "filen-out:root";
            r.helper.getWritableDatabase().execSQL("INSERT INTO sources(id,kind,tree_uri,title,partner_name,state,added_at) VALUES(?,?,?,?,?,?,2)",
                    new Object[]{outId, SourceStore.SHARED_BY_ME, "content://" + SourceStore.AUTHORITY + "/tree/" + Uri.encode(outRoot) + "#" + UUID.randomUUID(), "Shared by me", "Sam", SourceStore.NEW});
            Fake out = new Fake();
            out.children.put(outRoot, new ArrayList<>());
            byte[] words = "quarterly zebra budget".getBytes(StandardCharsets.UTF_8), loneWords = "lonely zebra outside".getBytes(StandardCharsets.UTF_8);
            r.fake.put(r.root, new SourceReader.Entry("filen:" + uuid, "budget.txt", "text/plain", words.length, 1, "v1", false)); r.fake.content.put("filen:" + uuid, words);
            out.put(outRoot, new SourceReader.Entry("filen-out:" + uuid, "budget.txt", "text/plain", words.length, 1, "v1", false)); out.content.put("filen-out:" + uuid, words);
            out.put(outRoot, new SourceReader.Entry("filen-out:" + lone, "lonely.txt", "text/plain", loneWords.length, 1, "v1", false)); out.content.put("filen-out:" + lone, loneWords);
            SourceIndexer indexer = new SourceIndexer(r.iso, r.store, source -> source.id.equals(r.sourceId) ? r.fake : out, r.env);
            indexer.scanAll(null, new SourceIndexer.Control());
            require(r.fake.fetches == 1 && out.fetches == 1, "the covered file is fetched once (own folder), the lonely one once: own " + r.fake.fetches + ", shared by me " + out.fetches);
            SourceStore.FileRow covered = r.store.rows(outId).get("filen-out:" + uuid);
            require(covered != null && SourceStore.INDEXED.equals(covered.state) && SourceStore.COVERED.equals(covered.reason) && r.store.text(covered.id).isEmpty(), "the covered row is done, with no text of its own");
            require(SourceStore.INDEXED.equals(r.store.rows(outId).get("filen-out:" + lone).state), "the file outside the added folders is read");
            List<SourceStore.Hit> hits = r.store.search("zebra", null);
            require(hits.size() == 2, "one row per file in search: " + hits.size());
            require(r.store.search("budget", null).size() == 1 && SourceStore.OWN.equals(r.store.search("budget", null).get(0).kind) && r.store.search("budget", null).get(0).sharedByMe, "the surviving row is the own one, marked shared");
            indexer.scanAll(null, new SourceIndexer.Control());
            require(r.fake.fetches == 1 && out.fetches == 1, "a second scan reads nothing again");
            // The own copy goes away (not in the folder any more): the shared one is read after all.
            r.helper.getWritableDatabase().execSQL("UPDATE source_files SET missing_since=1 WHERE source_id=? AND provider_doc_id=?", new Object[]{r.sourceId, "filen:" + uuid});
            indexer.scan(r.store.get(outId), new SourceIndexer.Control());
            require(out.fetches == 2 && r.store.search("quarterly", null).size() == 1 && SourceStore.SHARED_BY_ME.equals(r.store.search("quarterly", null).get(0).kind), "when no own folder covers it, the shared copy is read and found");
            mark.accept("Sources: a Shared-by-me file covered by an own folder is not downloaded or read twice (" + r.fake.fetches + " + " + out.fetches + " fetches for 2 files), search shows one row; read after all when the own copy goes");
        } finally { r.close(); }
    }

    /** Partner change: Filen's fresh partner_id first, then the fresh name; the kept id and name only when Filen cannot answer. */
    private static void partnerIdentity(Context app, Consumer<String> mark) throws Exception {
        Rig r = new Rig(app);
        try {
            SQLiteDatabase db = r.helper.getWritableDatabase();
            ScanShare share = new ScanShare(r.iso, r.store, source -> null, (id, out) -> {}, () -> true);
            db.execSQL("DELETE FROM sources");
            String scan = UUID.randomUUID().toString();
            db.execSQL("INSERT INTO sources(id,kind,tree_uri,title,partner_name,partner_id,state,added_at) VALUES(?,?,?,?,?,?,?,1)",
                    new Object[]{scan, SourceStore.SCAN_SHARE, "content://" + SourceStore.AUTHORITY + "/tree/" + Uri.encode("filen:scans") + "#" + UUID.randomUUID(), "Scans", "Sam", "id-1", SourceStore.OK});
            java.util.function.BiFunction<String, String, Boolean> changed = (id, name) -> { share.partnerIdLookup = () -> id; share.partnerLookup = () -> name; return share.partnerChanged(r.store.get(scan)); };
            require(!changed.apply("id-1", "Sam"), "same id, same name: not changed");
            require(!changed.apply("id-1", "Samantha"), "the same contact renamed in Filen: not changed (the id decides)");
            require(changed.apply("id-2", "Sam"), "another contact with the same display name: changed (the id decides)");
            require(changed.apply("id-2", ""), "another id with no name: changed");
            require(!changed.apply("id-1", ""), "the same id with no name: not changed");
            // Filen sends no id (an older Filen): the fresh name is compared first.
            db.execSQL("DELETE FROM sources WHERE kind='partner'");
            require(!changed.apply("", "Sam"), "no id: same fresh name: not changed");
            require(changed.apply("", "Alex"), "no id: another fresh name: changed");
            db.execSQL("INSERT INTO sources(id,kind,tree_uri,title,partner_name,partner_id,state,added_at) VALUES(?,?,?,?,?,?,?,2)",
                    new Object[]{UUID.randomUUID().toString(), SourceStore.PARTNER, "content://" + SourceStore.AUTHORITY + "/tree/" + Uri.encode("filen-in:root") + "#" + UUID.randomUUID(), "Alex's shared files", "Alex", "id-9", SourceStore.OK});
            require(!changed.apply("", "Sam"), "the stored partner source says Alex, Filen says Sam now: Filen's fresh answer wins, not changed");
            require(changed.apply("", "Alex"), "the stored partner source says Alex, Filen says Alex: the folder is Sam's, changed");
            // Filen cannot answer: the kept id, else the kept name, of the partner source stands in.
            require(changed.apply("", ""), "Filen away: the kept partner (id-9, Alex) is not the folder's (id-1, Sam): changed");
            db.execSQL("UPDATE sources SET partner_id='id-1', partner_name='Sam' WHERE kind='partner'");
            require(!changed.apply("", ""), "Filen away: the kept partner id matches: not changed");
            db.execSQL("DELETE FROM sources WHERE kind='partner'");
            require(!changed.apply("", ""), "Filen away and nothing kept: nothing is assumed");
            // A folder made before ids: compared by name, and the id is kept once the name matches.
            db.execSQL("UPDATE sources SET partner_id='' WHERE id=?", new Object[]{scan});
            require(!changed.apply("id-1", "Sam") && "id-1".equals(r.store.get(scan).partnerId), "a folder without an id, same name: not changed, and the id is kept now");
            require(changed.apply("id-2", "Sam"), "and from then on another id is a change");
            // The folder Filen creates keeps the id.
            r.store.heldOverride = uri -> true;
            ScanShare made = new ScanShare(r.iso, r.store, source -> null, (id, out) -> {}, () -> true);
            made.partnerIdLookup = () -> "id-7"; made.partnerLookup = () -> "Robin";
            db.execSQL("DELETE FROM sources");
            SourceStore.Source created = made.setFolder(Uri.parse("content://" + SourceStore.AUTHORITY + "/tree/" + Uri.encode("filen:newscans")), "Scans shared with Robin", "Robin");
            require("id-7".equals(created.partnerId) && "Robin".equals(created.partnerName), "the scan folder keeps the partner id: " + created.partnerId);
            made.partnerIdLookup = () -> "id-8"; made.partnerLookup = () -> "Robin";
            SourceStore.Source moved = made.setFolder(Uri.parse("content://" + SourceStore.AUTHORITY + "/tree/" + Uri.encode("filen:newscans2")), "Scans shared with Robin", "Robin");
            require("id-8".equals(moved.partnerId), "a replaced folder records the new partner's id: " + moved.partnerId);
            mark.accept("Sources: partner change decided by Filen's fresh partner_id first (a renamed contact is the same, another contact with the same name is new), then Filen's fresh name, the kept id and name only when Filen cannot answer; the id is stored with the scan folder");
        } finally { r.store.heldOverride = null; r.close(); }
    }

    /** A plan of pages made for other content (the file changed between two fetches under the same listing version) is thrown away. */
    private static void planSignature(Context test, Context app, Consumer<String> mark) throws Exception {
        Chunks chunks = new Chunks(app);
        Rig r = new Rig(app, chunks);
        try {
            String id = r.fake.file(r.root, "scanned6.pdf", asset(test, "sources/scanned-6-pages.pdf"));
            SourceIndexer.Control stop = new SourceIndexer.Control();
            chunks.stopAfterFirstChunk = stop;
            r.indexer.scan(r.source(), stop);
            SourceStore.FileRow row = r.row(id);
            String first = r.store.planSignature(row.id);
            require(row.state.equals(SourceStore.PENDING) && r.store.pageTotal(row.id) == 6 && first.startsWith(row.version + "|") && first.split("\\|").length == 3, "the plan carries the version, size and digest of what was fetched: " + first);
            r.scan();
            require(r.row(id).state.equals(SourceStore.INDEXED), "the same content resumes and finishes: " + r.row(id).state);
            require(chunks.layerCalls == 1, "same content: the plan was kept, no new text-layer call: " + chunks.layerCalls);
        } finally { chunks.client.kill(); r.close(); }
        chunks = new Chunks(app);
        r = new Rig(app, chunks);
        try {
            String id = r.fake.file(r.root, "scanned6.pdf", asset(test, "sources/scanned-6-pages.pdf"));
            SourceIndexer.Control stop = new SourceIndexer.Control();
            chunks.stopAfterFirstChunk = stop;
            r.indexer.scan(r.source(), stop);
            SourceStore.FileRow row = r.row(id);
            String first = r.store.planSignature(row.id);
            require(row.state.equals(SourceStore.PENDING) && r.store.pageTotal(row.id) == 6, "a plan exists");
            r.fake.content.put(id, asset(test, "types/pdf/born-digital.pdf"));   // other bytes, the same listing version
            r.scan();
            row = r.row(id);
            require(chunks.layerCalls == 2, "different content under the same version: the plan is cleared and made again: " + chunks.layerCalls);
            require(row.state.equals(SourceStore.INDEXED) && !r.store.text(row.id).toLowerCase(java.util.Locale.ROOT).contains(SCAN_WORDS[4]), "the text is that of the new content: " + row.state);
            mark.accept("Sources: the page plan stores the fetched content's version, size and digest; the same content resumes, other content under the same version starts over");
        } finally { chunks.client.kill(); r.close(); }
    }

    /** Filen's new account id: a source stored under the legacy id adopts the new one once, without pausing; any other change still pauses. */
    private static void accountMove(Context app, Consumer<String> mark) throws Exception {
        Rig r = new Rig(app);
        try {
            r.fake.file(r.root, "a.txt", "alpha");
            r.scan();
            require("acct-1".equals(r.source().accountId) && SourceStore.OK.equals(r.source().state), "the account is recorded: " + r.source().accountId);
            r.fake.account = "acct-new"; r.fake.legacy = "acct-1";
            SourceIndexer.Result result = r.scan();
            require(result.stopped.isEmpty() && "acct-new".equals(r.source().accountId) && SourceStore.OK.equals(r.source().state), "the legacy id moves to the new one without pausing: " + result.stopped + " " + r.source().accountId + " " + r.source().state);
            r.fake.legacy = "";
            result = r.scan();
            require(result.stopped.isEmpty() && "acct-new".equals(r.source().accountId), "the new id is the account now");
            r.fake.account = "acct-other"; r.fake.legacy = "acct-unrelated";
            result = r.scan();
            require("account".equals(result.stopped) && SourceStore.ACCOUNT_CHANGED.equals(r.source().state) && "acct-new".equals(r.source().accountId), "another account (a legacy id that is not ours) still pauses: " + result.stopped);
            mark.accept("Sources: Filen's ACCOUNT_LEGACY moves a stored account id to the new one once, without pausing; a different account still pauses");
        } finally { r.close(); }
    }

    /** A version 22 library (no plan signature, no scan-share journal) upgrades to the current version. */
    private static void migration22(Context app, Consumer<String> mark) throws Exception {
        Context iso = new ScannerChecks.IsolatedContext(app);
        String name = "migrate22-" + UUID.randomUUID() + ".db";
        LibraryDatabase first = new LibraryDatabase(iso, name);
        try {
            SQLiteDatabase db = first.getWritableDatabase();
            db.execSQL("DROP TABLE scan_share_journal");
            db.execSQL("DROP TABLE source_pages");
            db.execSQL("CREATE TABLE source_pages (file_id TEXT NOT NULL REFERENCES source_files(id) ON DELETE CASCADE, page INTEGER NOT NULL, total INTEGER NOT NULL, state TEXT NOT NULL, tries INTEGER NOT NULL DEFAULT 0, text TEXT NOT NULL DEFAULT '', PRIMARY KEY(file_id, page))");
            db.setVersion(22);
            try (Cursor c = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) { c.moveToFirst(); }
        } finally { first.close(); }
        LibraryDatabase second = new LibraryDatabase(iso, name);
        try {
            SQLiteDatabase db = second.getWritableDatabase();
            require(db.getVersion() == LibraryDatabase.VERSION, "a version 22 library upgrades to the current version");
            db.execSQL("INSERT INTO scan_share_journal(file_name,document_id) VALUES('x [12345678].pdf','d')");
            try (Cursor c = db.rawQuery("SELECT plan_sig FROM source_pages LIMIT 1", null)) { require(c != null, "source_pages has plan_sig"); }
            mark.accept("Sources: a version 22 library gets the scan-share journal and the page plan signature");
        } finally { second.close(); iso.deleteDatabase(name); }
    }

    /** The total budget of a file and a reader that will not start (the coordinator's S3). */
    private static void budgetAndReader(Context test, Context app, Consumer<String> mark) throws Exception {
        Chunks chunks = new Chunks(app);
        Rig r = new Rig(app, chunks);
        try {
            String id = r.fake.file(r.root, "scanned6.pdf", asset(test, "sources/scanned-6-pages.pdf"));
            SourceIndexer.Control stop = new SourceIndexer.Control();
            chunks.stopAfterFirstChunk = stop;
            r.indexer.scan(r.source(), stop);
            SourceStore.FileRow row = r.row(id);
            require(row.state.equals(SourceStore.PENDING) && r.store.budgetUsed(row.id) > 0, "time spent on the file is counted: " + r.store.budgetUsed(row.id) + " ms");
            r.store.addBudget(row.id, SourceIndexer.TOTAL_BUDGET_MS);   // 30 minutes of reading are used up
            int calls = chunks.ocrCalls;
            r.scan();
            row = r.row(id);
            require(row.state.equals(SourceStore.INDEXED) && SourceIndexer.SOME_PAGES.equals(row.reason) && chunks.ocrCalls == calls, "budget spent: finished as partly read with no more OCR: " + row.state + " / " + row.reason);
            r.scan();
            require(chunks.ocrCalls == calls && r.row(id).state.equals(SourceStore.INDEXED), "not tried again while the content is unchanged");
        } finally { chunks.client.kill(); r.close(); }
        // the reader will not start: nothing is counted and the source waits
        final Extractor down = new Extractor() {
            @Override public ExtractClient.Outcome extract(File file, String typeId, org.stackdocs.app.types.Limits limits, CancellationSignal signal) { return new ExtractClient.Outcome(ExtractClient.Outcome.Kind.UNAVAILABLE, null); }
            @Override public ExtractClient.Outcome extractPages(File file, String typeId, String mode, int[] pages, org.stackdocs.app.types.Limits limits, CancellationSignal signal) { return new ExtractClient.Outcome(ExtractClient.Outcome.Kind.UNAVAILABLE, null); }
            @Override public void release() {}
        };
        r = new Rig(app, down);
        try {
            String a = r.fake.file(r.root, "a.txt", "alpha words"), b = r.fake.file(r.root, "b.pdf", asset(test, "sources/scanned-6-pages.pdf"));
            for (int i = 0; i < 4; i++) r.scan();
            require(r.row(a).state.equals(SourceStore.PENDING) && r.row(a).attempts == 0 && r.row(b).state.equals(SourceStore.PENDING) && r.row(b).attempts == 0, "no attempt is counted when the reader cannot start");
            require(SourceStore.WAITING.equals(r.source().state) && !r.source().reason.isEmpty(), "the source waits with a reason: " + r.source().state + " / " + r.source().reason);
        } finally { r.close(); }
        mark.accept("Sources: a file's 30-minute extract budget ends it as partly read and it is not retried; a reader that will not start counts no attempts and the source waits");
    }

    // ---- the change notice, the store's kind rule, the caches (review findings 3, 4, 7) -------------------------------------------

    private static void receiverAndStore(Context app, Consumer<String> mark) throws Exception {
        PartnerChangeReceiver.forget();
        require(PartnerChangeReceiver.allow("a", 1_000) && !PartnerChangeReceiver.allow("a", 30_000) && PartnerChangeReceiver.allow("b", 30_000), "the window is per source");
        require(!PartnerChangeReceiver.allow("a", 60_999) && PartnerChangeReceiver.allow("a", 61_000) && !PartnerChangeReceiver.allow("a", 62_000), "a success does not reset it");
        PartnerChangeReceiver.forget();
        Rig r = new Rig(app);
        try {
            r.store.heldOverride = tree -> true;
            Uri own = Uri.parse("content://" + SourceStore.AUTHORITY + "/tree/" + Uri.encode("filen:" + UUID.randomUUID()));
            Uri shared = Uri.parse("content://" + SourceStore.AUTHORITY + "/tree/" + Uri.encode("filen-in:" + UUID.randomUUID()));
            Uri scans = Uri.parse("content://" + SourceStore.AUTHORITY + "/tree/" + Uri.encode("filen:" + UUID.randomUUID()));
            SourceStore.Source a = r.store.add(SourceStore.OWN, own, "Bills", "");
            require(r.store.add(SourceStore.OWN, own, "Bills 2", "").id.equals(a.id) && r.store.list().size() == 2, "the same tree and kind is the same source");
            require(SourceStore.ALREADY_ADDED.equals(r.store.pickProblem(own)), "the picker says already added");
            r.store.add(SourceStore.SCAN_SHARE, scans, "Shared scans", "Sam");
            try { r.store.add(SourceStore.OWN, scans, "x", ""); require(false, "refused"); } catch (IOException refused) { require(SourceStore.ALREADY_OTHER.equals(refused.getMessage()), "the kind of a tree is never changed: " + refused.getMessage()); }
            require(r.store.byTree(scans.toString()).kind.equals(SourceStore.SCAN_SHARE) && SourceStore.ALREADY_OTHER.equals(r.store.pickProblem(scans)), "the scan folder stays a scan folder and the picker refuses it");
            r.store.add(SourceStore.PARTNER, shared, "Sam", "Sam");
            require(SourceStore.ALREADY_OTHER.equals(r.store.pickProblem(shared)) && SourceStore.NOT_OWN.equals(r.store.pickProblem(Uri.parse("content://" + SourceStore.AUTHORITY + "/tree/" + Uri.encode("filen-in:" + UUID.randomUUID())))), "the partner's tree is refused, a new partner-looking tree is refused");
            // Caches of a removed source: the files being read (by row id) and the viewer's copies (by source id).
            ContentValues v = new ContentValues();
            v.put("id", "row-1"); v.put("source_id", a.id); v.put("provider_doc_id", "filen:" + UUID.randomUUID()); v.put("parent_doc_id", "p"); v.put("name", "f.txt"); v.put("state", "pending");
            r.store.db().insertOrThrow("source_files", null, v);
            File reading = new File(r.iso.getCacheDir(), "source-read"), viewing = new File(r.iso.getCacheDir(), "source-view");
            reading.mkdirs(); viewing.mkdirs();
            File one = new File(reading, "row-1"), answer = new File(reading, "row-1.answer"), mine = new File(viewing, a.id + "_x.tmp"), other = new File(viewing, "other_x.tmp");
            for (File f : new File[]{one, answer, mine, other}) try (FileOutputStream out = new FileOutputStream(f)) { out.write(1); }
            r.store.remove(a.id);
            require(!one.exists() && !answer.exists() && !mine.exists() && other.exists(), "removing a source clears its copies in both caches and no others");
            // Replacing the scan folder in place: the one source takes the new tree.
            Uri newScans = Uri.parse("content://" + SourceStore.AUTHORITY + "/tree/" + Uri.encode("filen:" + UUID.randomUUID()));
            String id = r.store.first(SourceStore.SCAN_SHARE).id;
            SourceStore.Source moved = r.store.replaceScanShare(id, newScans, "Shared scans", "Alex", false);
            require(moved.id.equals(id) && moved.treeUri.equals(newScans.toString()) && moved.partnerName.equals("Alex") && moved.accountId.isEmpty() && r.store.list().size() == 3, "the scan source is replaced in place");
            other.delete();
        } finally { r.close(); }
        mark.accept("Sources: change notice window per source and not reset by a success; a tree's kind never changes and the picker refuses scan and partner trees; removing a source clears both caches; scan source replaced in place");
    }

    private static void robustness(Context app, Consumer<String> mark) {
        Rig r = new Rig(app);
        try (Clock.Fixed clock = Clock.fix(1_800_000_000_000L)) {
            String bad = r.fake.file(r.root, "bad.txt", "bad words");
            String good = r.fake.file(r.root, "good.txt", "good words");
            String empty = r.fake.file(r.root, "empty.txt", new byte[0]);
            String random = r.fake.file(r.root, "random.txt", new byte[]{(byte) 0xff, (byte) 0xfe, 0, 1, 2, 3, 4});
            r.fake.failing.add(bad);
            SourceIndexer.Result result = r.scan();
            require(r.row(good).state.equals(SourceStore.INDEXED), "a failing file does not stop the run");
            require(r.row(bad).state.equals(SourceStore.PENDING) && r.row(bad).attempts == 1, "the failing file is tried again later (attempt 1)");
            require(r.row(empty).state.equals(SourceStore.UNSUPPORTED) && r.row(random).state.equals(SourceStore.UNREADABLE) && !r.row(empty).reason.isEmpty() && !r.row(random).reason.isEmpty(), "an empty file is unsupported and undecodable text is unreadable, each with a reason: " + r.row(empty).state + "/" + r.row(random).state);
            for (int i = 0; i < 5; i++) r.scan();
            require(r.row(bad).state.equals(SourceStore.UNREADABLE) && SourceIndexer.GAVE_UP.equals(r.row(bad).reason) && r.store.search("bad", null).size() == 1, "after 3 tries it is unreadable with the reason, but still found by name");
            // the file changes: tried again
            r.fake.failing.clear(); r.fake.setText(bad, "bad words now fine");
            r.scan();
            require(r.row(bad).state.equals(SourceStore.INDEXED), "a changed file is read again");
            mark.accept("Sources: one bad file never stops the run; failures are retried then reported; empty and undecodable files get a reason");
        } finally { r.close(); }
    }

    private static void misc(Context app, Consumer<String> mark) throws Exception {
        Rig r = new Rig(app);
        try {
            String a = r.fake.file(r.root, "linked.txt", "shared with an ami pro document");
            String uuid = SourceStore.uuidOf(a);
            r.scan();
            LinkStore.Link link = new LinkStore.Link("l1", "app.amipro.editor", "x", "doc", "Linked doc", "m", "h", "[{\"type\":\"filen\",\"uuid\":\"" + uuid + "\",\"path\":\"/x\"}]", "live", "", 1, 1, true, false, false, 0);
            LinkStore.Link other = new LinkStore.Link("l2", "app.amipro.editor", "y", "doc", "Other doc", "m", "h", "[{\"type\":\"filen\",\"uuid\":\"" + UUID.randomUUID() + "\"}]", "live", "", 1, 1, true, false, false, 0);
            require(r.store.search("linked", null).size() == 1, "found without a link record");
            require(r.store.search("linked", Arrays.asList(link)).isEmpty(), "same Filen UUID as a link record: one result (the link record's)");
            require(r.store.search("linked", Arrays.asList(other)).size() == 1, "a link record with another UUID does not hide it");
            require(SourceStore.uuidOf("filen-in:root") == null && SourceStore.uuidOf("filen-out:" + uuid).equals(uuid), "uuid from document ids");
            // tree URI validation
            require(SourceStore.validTree(Uri.parse("content://app.filenfiles.documents/tree/filen-in%3Aroot")), "Filen's tree is valid");
            require(!SourceStore.validTree(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")) && !SourceStore.validTree(Uri.parse("content://app.filenfiles.documents/document/filen%3Aabc"))
                    && !SourceStore.validTree(Uri.parse("file:///sdcard")) && !SourceStore.validTree(null), "other authorities, documents and files are refused");
            try { r.store.add(SourceStore.OWN, Uri.parse("content://com.example.documents/tree/x"), "x", ""); require(false, "refused"); } catch (IOException expected) { /* plain words */ }
            // removing a source removes everything
            r.store.addToStack(SourceStore.ITEM_SOURCE, r.row(a).id, "bills");
            File preview = new File(r.store.previewDir(r.sourceId), r.row(a).id + ".png");
            preview.getParentFile().mkdirs(); try (FileOutputStream out = new FileOutputStream(preview)) { out.write(1); }
            r.store.remove(r.sourceId);
            require(r.store.list().isEmpty() && r.count("SELECT COUNT(*) FROM source_files") == 0 && r.count("SELECT COUNT(*) FROM source_search") == 0
                    && r.count("SELECT COUNT(*) FROM item_stacks") == 0 && !preview.exists() && !r.store.previewDir(r.sourceId).exists(), "removing a source deletes its rows, text, stacks and previews");
            mark.accept("Sources: dedupe with link records by Filen UUID, tree URI validation, source removal");
        } finally { r.close(); }
    }

    // ---- part 3: a real Filen tree ------------------------------------------------------------------------------------------------

    static final String PHASE_DB = "sources-real";

    private static void probe(Context target, String testPackage, String step, String a, String b, String c) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Intent intent = new Intent().setComponent(new ComponentName(testPackage, "org.stackdocs.app.FolderProbeReceiver")).putExtra("step", step).putExtra("a", a).putExtra("b", b).putExtra("c", c);
        target.sendOrderedBroadcast(intent, null, new BroadcastReceiver() { @Override public void onReceive(Context context, Intent i) { done.countDown(); } }, new Handler(Looper.getMainLooper()), 0, null, null);
        require(done.await(180, TimeUnit.SECONDS), "probe step " + step + " finished");
    }

    /**
     * Filen pins its callers, and in a checks build that is the test package, which holds the persisted grants Filen's PARTNER_SETUP returned. The app
     * under test is another Android user id, so the test package passes its READ grants on (a test-only probe step) and this takes them as the app's own.
     */
    private static List<Uri> borrowGrants(Context test, Context target) throws Exception {
        probe(target, test.getPackageName(), "delegate", null, null, null);
        List<Uri> out = new ArrayList<>();
        for (String id : new String[]{"filen-in:root", "filen-out:root"}) {
            Uri tree = DocumentsContract.buildTreeDocumentUri(SourceStore.AUTHORITY, id);
            try { target.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); out.add(tree); } catch (SecurityException none) { /* not granted in this user */ }
        }
        return out;
    }

    private static void real(Context test, Context app, String phase, Consumer<String> mark) throws Exception {
        if (!filenInstalled(app)) { mark.accept("Sources: SKIP real tree (Filen Files is not installed for this user)"); return; }
        Context iso = new ScannerChecks.IsolatedContext(app, PHASE_DB);
        LibraryDatabase helper = new LibraryDatabase(iso);
        try {
            SourceStore store = new SourceStore(iso, helper::getWritableDatabase, new File(iso.getFilesDir(), "sources"));
            Env env = new Env();
            SourceIndexer indexer = new SourceIndexer(iso, store, SourceIndexer.safReaders(iso, store), env);
            if (phase.equals("all")) {
                for (SourceStore.Source old : store.list()) store.remove(old.id);
                List<Uri> trees = borrowGrants(test, app);
                if (trees.isEmpty()) { mark.accept("Sources: SKIP real tree (no Filen grants for this user; run Filen PARTNER_SETUP first)"); return; }
                for (Uri tree : trees) {
                    boolean in = tree.toString().contains("filen-in");
                    store.add(in ? SourceStore.PARTNER : SourceStore.SHARED_BY_ME, tree, in ? "Shared with me" : "Shared by me", in ? "Partner" : "");
                }
                for (SourceStore.Source source : store.list()) realIndex(source, store, indexer, mark);
                for (SourceStore.Source source : store.list()) if (SourceStore.SHARED_BY_ME.equals(source.kind)) realMutations(test, app, source, store, indexer, mark);
            } else if (phase.equals("offline") || phase.equals("online")) {
                realNetwork(phase, store, indexer, mark);
            } else throw new IllegalArgumentException("Unknown sourcesPhase " + phase);
        } finally { helper.close(); }
    }

    private static boolean filenInstalled(Context app) {
        try { app.getPackageManager().getPackageInfo("app.filenfiles", 0); return true; } catch (android.content.pm.PackageManager.NameNotFoundException none) { return false; }
    }

    private static String summary(SourceStore store, String sourceId) {
        Map<String, Integer> by = new TreeMap<>();
        try (Cursor c = store.db().rawQuery("SELECT CASE WHEN handler_id='' THEN '-' ELSE handler_id END, state, COUNT(*) FROM source_files WHERE source_id=? AND is_dir=0 GROUP BY 1,2", new String[]{sourceId})) {
            while (c.moveToNext()) by.put(c.getString(0) + "/" + c.getString(1), c.getInt(2));
        }
        return by.toString();
    }

    private static void realIndex(SourceStore.Source source, SourceStore store, SourceIndexer indexer, Consumer<String> mark) {
        long t0 = SystemClock.elapsedRealtime();
        SourceIndexer.Result result = indexer.scan(source, new SourceIndexer.Control());
        long first = SystemClock.elapsedRealtime() - t0;
        SourceStore.Progress p = store.progress(source.id);
        SourceStore.Source after = store.get(source.id);
        require(result.stopped.isEmpty() && result.complete && SourceStore.OK.equals(p.state), source.kind + ": real scan completes: " + p.state + " " + p.reason + " stopped=" + result.stopped);
        require(!after.accountId.isEmpty(), "the account id came from Filen's ACCOUNT extra");
        require(p.pending == 0 && (p.files > 0 || SourceStore.SHARED_BY_ME.equals(source.kind) || SourceStore.PARTNER.equals(source.kind)), source.kind + ": nothing left pending, files " + p.files);
        long t1 = SystemClock.elapsedRealtime();
        SourceIndexer.Result again = indexer.scan(store.get(source.id), new SourceIndexer.Control());
        long second = SystemClock.elapsedRealtime() - t1;
        require(again.read == 0 && again.removed == 0, source.kind + ": a second scan reads nothing (version column) and removes nothing");
        String word = firstWord(store, source.id);
        require(word == null || !store.search(word, null).isEmpty(), "search finds a real word of a real file: " + word);
        mark.accept("Sources: real " + source.kind + " tree, " + p.files + " files (" + result.listed + " folders), scan " + first + " ms, rescan " + second + " ms, " + summary(store, source.id) + ", missing " + p.missing);
    }

    private static String firstWord(SourceStore store, String sourceId) {
        try (Cursor c = store.db().rawQuery("SELECT f.id FROM source_files f WHERE f.source_id=? AND f.state='indexed' ORDER BY f.size, f.id LIMIT 20", new String[]{sourceId})) {
            while (c.moveToNext()) {
                for (String w : store.text(c.getString(0)).split("[^\\p{L}]+")) if (w.length() >= 6) return w;
            }
        }
        return null;
    }

    private static String filenId(String docId) { String uuid = SourceStore.uuidOf(docId); return uuid == null ? null : "filen:" + uuid; }

    private static void realMutations(Context test, Context app, SourceStore.Source source, SourceStore store, SourceIndexer indexer, Consumer<String> mark) throws Exception {
        // keep the deleted scratch file from being found again: the check removes only what it made
        String pkg = test.getPackageName();
        SourceStore.FileRow folder = null;
        for (SourceStore.FileRow f : store.rows(source.id).values()) if (f.dir && f.parentDocId.equals(source.rootDocId()) && f.path.equals("Probe Shared")) folder = f;
        if (folder == null) { mark.accept("Sources: SKIP real mutations (no folder named Probe Shared in Shared by me)"); return; }
        String parent = filenId(folder.docId);
        for (SourceStore.FileRow old : new ArrayList<>(store.rows(source.id).values())) {   // scratch files an earlier run left behind (this check made them)
            if (!old.dir && old.name.startsWith("stack-scratch-")) probe(app, pkg, "delete", "drive", filenId(old.docId), null);
        }
        String one = letters(), two = letters();
        String name = "stack-scratch-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
        // create a scratch file in the owner's drive (never an existing file), through the test package's write grant
        probe(app, pkg, "put", "drive", parent, name + "@@" + "scratch alpha words " + one);
        SourceIndexer.Result result = scanUntil(store, indexer, source, () -> byName(store, source.id, name) != null && store.search(one, null).size() == 1);
        SourceStore.FileRow scratch = byName(store, source.id, name);
        require(scratch != null, "the scratch file shows up in a FRESH scan (Filen uploads it after the write)");
        String docId = scratch.docId, driveId = filenId(docId);
        scratch = byName(store, source.id, name);
        require(scratch != null && scratch.state.equals(SourceStore.INDEXED) && !store.search(one, null).isEmpty(), "a written file is read and found");
        String keepId = scratch.id;
        String tag = UUID.randomUUID().toString();
        store.db().execSQL("INSERT INTO tags(id,name) VALUES(?,?)", new Object[]{tag, "real-" + tag.substring(0, 6)});
        store.addToStack(SourceStore.ITEM_SOURCE, keepId, "bills");
        store.addTag(SourceStore.ITEM_SOURCE, keepId, tag);
        // rename keeps the id
        String renamed = name.replace("scratch", "scratch-renamed");
        probe(app, pkg, "rename", "drive", driveId, renamed);
        scanUntil(store, indexer, source, () -> byName(store, source.id, renamed) != null);   // Filen's listing can lag the change by a few seconds
        SourceStore.FileRow after = byName(store, source.id, renamed);
        require(after != null && after.docId.equals(docId) && after.id.equals(keepId), "real rename keeps the Filen id and the row");
        // move to the root of the source's folder's sibling: a subfolder if there is one
        SourceStore.FileRow dest = null;
        for (SourceStore.FileRow f : store.rows(source.id).values()) if (f.dir && !f.docId.equals(folder.docId) && f.path.startsWith("Probe Shared/")) { dest = f; break; }
        if (dest != null) {
            probe(app, pkg, "move", "drive", driveId, parent + "@@" + filenId(dest.docId));
            final String destDoc = dest.docId;
            scanUntil(store, indexer, source, () -> { SourceStore.FileRow moved = byName(store, source.id, renamed); return moved != null && moved.parentDocId.equals(destDoc); });
            after = byName(store, source.id, renamed);
            require(after != null && after.docId.equals(docId) && after.id.equals(keepId) && after.parentDocId.equals(dest.docId), "real move keeps the Filen id and the row");
        }
        // save-over
        probe(app, pkg, "overwrite", "drive", driveId, "scratch second version words " + two);
        result = scanUntil(store, indexer, source, () -> !store.search(two, null).isEmpty());
        after = byName(store, source.id, renamed);
        require(after != null && !store.search(two, null).isEmpty() && store.search(one, null).isEmpty(), "save-over: the new text replaces the old");
        require(store.stacksOf(SourceStore.ITEM_SOURCE, after.id).contains("bills") && store.tagsOf(SourceStore.ITEM_SOURCE, after.id).contains(tag), "save-over keeps stacks and tags");
        int visible = 0; for (SourceStore.Hit h : store.search(two, null)) if (h.name.equals(renamed)) visible++;
        require(visible == 1, "one result after the save-over");
        String sameId = after.docId.equals(docId) ? "same id" : "new id";
        // delete: Filen confirms with FileNotFound on open
        String currentDoc = after.docId;
        probe(app, pkg, "delete", "drive", filenId(currentDoc), null);
        result = scanUntil(store, indexer, source, () -> byName(store, source.id, renamed) == null);
        // Filen moves a deleted file to its trash, where it can still be opened, so the typed FileNotFound may not come; the 10-minute rule then decides.
        boolean omitted = byName(store, source.id, renamed) == null;
        String how = result.removed >= 1 ? "FileNotFound on open (first scan)" : "open said " + new SourceReader.Saf(app, store, source.tree()).lastGone;
        require(omitted, "a real delete: the file is omitted from the FRESH listing and hidden");
        if (result.removed == 0) {
            require(store.rows(source.id).containsKey(currentDoc), "not removed by one scan alone");
            try (Clock.Fixed clock = Clock.fix(Clock.now())) {
                clock.advance(10 * 60_000L + 1000);
                result = indexer.scan(store.get(source.id), new SourceIndexer.Control());
            }
            how = "second FRESH scan 10 minutes later";
        }
        require(!store.rows(source.id).containsKey(currentDoc) && result.removed >= 1, "a real delete is removed after confirmation (" + how + ")");
        require(store.search(two, null).isEmpty() && store.stacksOf(SourceStore.ITEM_SOURCE, keepId).isEmpty() && store.tagsOf(SourceStore.ITEM_SOURCE, keepId).isEmpty(), "its text, stacks and tags are gone");
        mark.accept("Sources: real rename and move keep the id, save-over (" + sameId + ") keeps stacks and tags, delete removed by " + how);
    }

    /** Scans every few seconds until `done` (Filen uploads a write after the file is closed) or a minute and a half has passed. */
    private static SourceIndexer.Result scanUntil(SourceStore store, SourceIndexer indexer, SourceStore.Source source, java.util.function.BooleanSupplier done) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + 90_000;
        SourceIndexer.Result result;
        do {
            result = indexer.scan(store.get(source.id), new SourceIndexer.Control());
            if (done.getAsBoolean()) break;
            Thread.sleep(3000);
        } while (SystemClock.elapsedRealtime() < end);
        return result;
    }

    /** A word no real file contains. */
    private static String letters() {
        StringBuilder out = new StringBuilder("q");
        for (char c : UUID.randomUUID().toString().replace("-", "").substring(0, 10).toCharArray()) out.append((char) ('a' + Character.digit(c, 16)));
        return out.toString();
    }

    private static SourceStore.FileRow byName(SourceStore store, String sourceId, String name) {
        for (SourceStore.FileRow f : store.rows(sourceId).values()) if (!f.dir && f.name.equals(name) && f.missingSince == 0) return f;
        return null;
    }

    private static void realNetwork(String phase, SourceStore store, SourceIndexer indexer, Consumer<String> mark) {
        List<SourceStore.Source> sources = store.list();
        require(!sources.isEmpty(), "run the default phase first, so there is a library to check");
        for (SourceStore.Source source : sources) {
            int files = store.progress(source.id).files;
            int rows = count(store, source.id);
            long t0 = SystemClock.elapsedRealtime();
            SourceIndexer.Result result = indexer.scan(source, new SourceIndexer.Control());
            long ms = SystemClock.elapsedRealtime() - t0;
            SourceStore.Progress p = store.progress(source.id);
            if (phase.equals("offline")) {
                require(result.removed == 0 && count(store, source.id) == rows && p.missing == 0, source.kind + ": no network, nothing removed (" + rows + " rows)");
                mark.accept("Sources: real " + source.kind + " offline: state " + p.state + ", complete=" + result.complete + ", " + ms + " ms, " + p.files + " files kept, none removed, none marked");
            } else {
                require(result.complete && SourceStore.OK.equals(p.state) && p.files == files && result.removed == 0, source.kind + ": back online, same files, nothing removed: " + p.state + " " + p.files + " vs " + files);
                mark.accept("Sources: real " + source.kind + " back online: state ok, " + p.files + " files, " + ms + " ms");
            }
        }
    }

    private static int count(SourceStore store, String sourceId) {
        try (Cursor c = store.db().rawQuery("SELECT COUNT(*) FROM source_files WHERE source_id=?", new String[]{sourceId})) { c.moveToFirst(); return c.getInt(0); }
    }
}
