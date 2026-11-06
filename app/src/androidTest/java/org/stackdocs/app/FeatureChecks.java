package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Duplicate warning and update check. Disposable package only; no network is used. */
final class FeatureChecks {
    private FeatureChecks() {}

    static void run(Instrumentation instrumentation, Consumer<String> mark) throws Exception {
        Context target = instrumentation.getTargetContext();
        if (!ScannerChecks.disposableLibraryTarget(target)) throw new SecurityException("Feature checks require an explicitly disposable library");
        updateChecks(mark);
        duplicateChecks(instrumentation, target, mark);
        trashChecks(instrumentation, target, mark);
        scanChecks(target, mark);
        nudgeChecks(target, mark);
    }

    private static void updateChecks(Consumer<String> mark) throws Exception {
        require(UpdateCheck.newer("0.11.0", "0.10.0"), "minor version is newer");
        require(UpdateCheck.newer("0.10.1", "0.10.0"), "patch version is newer");
        require(UpdateCheck.newer("1.0.0", "0.99.9"), "major version is newer");
        require(UpdateCheck.newer("0.10.10", "0.10.9"), "versions compare as numbers, not text");
        require(!UpdateCheck.newer("0.11.0", "0.11.0"), "same version is not newer");
        require(!UpdateCheck.newer("0.10.0", "0.11.0"), "older version is not newer");
        require(!UpdateCheck.newer("v0.11", "0.11.0"), "missing part counts as zero");
        require(!UpdateCheck.newer("garbage", "0.11.0"), "unreadable version is never newer");

        String page = "https://github.com/nchiker/stack/releases/tag/v0.12.0";
        UpdateCheck.Release release = UpdateCheck.parse("{\"tag_name\":\"v0.12.0\",\"html_url\":\"" + page + "\",\"assets\":["
                + "{\"name\":\"SHA256SUMS\"},{\"name\":\"Stack-0.12.0.apk\"},{\"name\":\"Stack-0.3.0-x86_64.flatpak\"}]}");
        require(release != null && release.version.equals("0.12.0") && release.page.equals(page), "release with an APK is read");
        require(UpdateCheck.parse("{\"tag_name\":\"v0.3.0\",\"html_url\":\"" + page + "\",\"assets\":[{\"name\":\"Stack-0.3.0-x86_64.flatpak\"}]}") == null,
                "desktop-only release is not an Android update");
        require(UpdateCheck.parse("{\"tag_name\":\"v1\",\"assets\":[]}") == null, "release without files is ignored");
        require(UpdateCheck.parse("{\"tag_name\":\"v1\"}") == null, "release without an asset list is ignored");
        UpdateCheck.Release foreign = UpdateCheck.parse("{\"html_url\":\"https://example.com/evil\",\"assets\":[{\"name\":\"Stack-9.9.9.apk\"}]}");
        require(foreign != null && foreign.page.startsWith("https://github.com/nchiker/stack/releases/"), "link outside this repository is replaced");
        require(UpdateCheck.parse("{\"assets\":[{\"name\":\"Stack-1.0.0.apk.exe\"}]}") == null, "only exact Stack-x.y.z.apk names count");
        mark.accept("Update check: version order, GitHub release parsing, desktop-only and foreign links");
    }

    private static void duplicateChecks(Instrumentation instrumentation, Context target, Consumer<String> mark) throws Exception {
        StackApplication app = (StackApplication) target.getApplicationContext();
        instrumentation.runOnMainSync(() -> {});
        app.libraryReady.await();
        app.io.submit(() -> {}).get();
        File dir = new File(target.getCacheDir(), "feature-" + UUID.randomUUID());
        require(dir.mkdirs(), "scratch folder");
        try {
            String nonce = UUID.randomUUID().toString();
            File a = pdf(dir, "a.pdf", "Duplicate check A " + nonce);
            File aCopy = copy(a, new File(dir, "a-copy.pdf"));
            File aSameSize = sameSizeDifferent(a, new File(dir, "a-same-size.pdf"));
            File c = pdf(dir, "c.pdf", "Duplicate check C different text " + nonce);
            File cCopy = copy(c, new File(dir, "c-copy.pdf"));
            require(a.length() == aSameSize.length() && !Arrays.equals(Files.readAllBytes(a.toPath()), Files.readAllBytes(aSameSize.toPath())),
                    "fixture: same size, different bytes");

            Document saved = app.io.submit(() -> app.library.importDocument(Uri.fromFile(a))).get();

            List<DuplicateCheck.Match> hit = DuplicateCheck.find(app, Arrays.asList(Uri.fromFile(aCopy)));
            require(hit.size() == 1 && hit.get(0).documentId.equals(saved.id) && hit.get(0).label.equals(saved.title), "identical file is found");
            require(hit.get(0).uri.equals(Uri.fromFile(aCopy)), "match names the incoming file");
            require(DuplicateCheck.find(app, Arrays.asList(Uri.fromFile(c))).isEmpty(), "different file is not a duplicate");
            require(DuplicateCheck.find(app, Arrays.asList(Uri.fromFile(aSameSize))).isEmpty(), "same size but different bytes is not a duplicate");
            mark.accept("Duplicate check: identical file found, different and same-size files are not");

            List<DuplicateCheck.Match> twice = DuplicateCheck.find(app, Arrays.asList(Uri.fromFile(c), Uri.fromFile(cCopy), Uri.fromFile(aSameSize)));
            require(twice.size() == 1 && twice.get(0).uri.equals(Uri.fromFile(cCopy)) && twice.get(0).documentId.isEmpty(), "second copy in one selection is flagged, the first is not");
            List<DuplicateCheck.Match> mixed = DuplicateCheck.find(app, Arrays.asList(Uri.fromFile(aCopy), Uri.fromFile(c)));
            require(mixed.size() == 1 && mixed.get(0).uri.equals(Uri.fromFile(aCopy)), "only the file already saved is flagged");
            require(DuplicateCheck.names(twice, 5).get(0).endsWith("(selected twice)"), "selection duplicates are described");
            mark.accept("Duplicate check: repeated file in one selection, mixed selection");

            app.io.submit(() -> { app.library.deleteDocument(saved.id); return null; }).get();
            require(DuplicateCheck.find(app, Arrays.asList(Uri.fromFile(aCopy))).isEmpty(), "deleted document is no longer a duplicate");
            require(DuplicateCheck.sha256(Files.readAllBytes(a.toPath())).length() == 64, "hash format");
            mark.accept("Duplicate check: deleted document no longer matches");
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
            dir.delete();
        }
    }

    private static void trashChecks(Instrumentation instrumentation, Context target, Consumer<String> mark) throws Exception {
        StackApplication app = (StackApplication) target.getApplicationContext();
        app.libraryReady.await();
        File dir = new File(target.getCacheDir(), "trash-" + UUID.randomUUID());
        require(dir.mkdirs(), "scratch folder");
        try {
            String nonce = UUID.randomUUID().toString();
            File file = pdf(dir, "t.pdf", "Trash check " + nonce);
            Document doc = app.io.submit(() -> app.library.importDocument(Uri.fromFile(file), UUID.randomUUID().toString(), "Trash specimen " + nonce)).get();
            app.io.submit(() -> { app.library.setMemberships(doc.id, Arrays.asList("bills")); return null; }).get();
            int before = app.io.submit(() -> stackCount(app, "bills")).get();
            require(before >= 1, "fixture: document is counted in its stack");

            app.io.submit(() -> { app.library.trashDocument(doc.id); return null; }).get();
            require(app.io.submit(() -> !contains(app.library.documents("", null), doc.id)).get(), "trashed document leaves the list");
            require(app.io.submit(() -> !contains(app.library.documents("Trash specimen", null), doc.id)).get(), "trashed document leaves search results");
            require(app.io.submit(() -> !contains(app.library.documents("", "bills"), doc.id)).get(), "trashed document leaves its stack");
            require(app.io.submit(() -> stackCount(app, "bills")).get() == before - 1, "stack count ignores the trashed document");
            require(app.io.submit(() -> app.library.isTrashed(doc.id) && app.library.trashed().size() >= 1).get(), "trashed document is listed in Recently deleted");
            require(app.io.submit(() -> app.library.document(doc.id).title.startsWith("Trash specimen")).get(), "a trashed document can still be read");
            require(DuplicateCheck.find(app, Arrays.asList(Uri.fromFile(file))).isEmpty(), "a trashed document is not a duplicate");
            mark.accept("Trash: hidden from list, search, stack and counts; listed in Recently deleted");

            app.io.submit(() -> { app.library.restoreDocument(doc.id); return null; }).get();
            require(app.io.submit(() -> contains(app.library.documents("", "bills"), doc.id) && contains(app.library.documents("Trash specimen", null), doc.id)).get(), "restored document returns with its stack");
            require(app.io.submit(() -> stackCount(app, "bills")).get() == before, "stack count restored");
            require(app.io.submit(() -> !app.library.isTrashed(doc.id)).get(), "restore clears the entry");
            mark.accept("Trash: restore returns the document with its stack");

            // Expiry: 29 days old stays, 31 days old is deleted for good.
            app.io.submit(() -> { app.library.trashDocument(doc.id); return null; }).get();
            long day = 24L * 60 * 60 * 1000;
            app.io.submit(() -> { app.library.database().execSQL("UPDATE trash SET deleted_at=? WHERE document_id=?", new Object[]{System.currentTimeMillis() - 29 * day, doc.id}); return null; }).get();
            require(app.io.submit(() -> app.library.purgeExpiredTrash()).get() == 0 && app.io.submit(() -> app.library.isTrashed(doc.id)).get(), "29-day-old entry is kept");
            app.io.submit(() -> { app.library.database().execSQL("UPDATE trash SET deleted_at=? WHERE document_id=?", new Object[]{System.currentTimeMillis() - 31 * day, doc.id}); return null; }).get();
            require(app.io.submit(() -> app.library.purgeExpiredTrash()).get() == 1, "31-day-old entry is purged");
            require(app.io.submit(() -> { try { app.library.document(doc.id); return false; } catch (IllegalArgumentException gone) { return true; } }).get(), "purged document is gone");
            require(app.io.submit(() -> !app.library.isTrashed(doc.id) && app.library.trashed().stream().noneMatch(t -> t.id.equals(doc.id))).get(), "purge clears the entry");
            mark.accept("Trash: 30-day expiry keeps 29-day entries and deletes 31-day entries for good");

            // A deletion that arrives some other way (the computer, or any direct delete) also forgets the entry.
            Document second = app.io.submit(() -> app.library.importDocument(Uri.fromFile(pdf(dir, "t2.pdf", "Trash second " + nonce)))).get();
            app.io.submit(() -> { app.library.trashDocument(second.id); return null; }).get();
            app.io.submit(() -> { app.library.database().execSQL("DELETE FROM documents WHERE id=?", new Object[]{second.id}); return null; }).get();
            require(app.io.submit(() -> !app.library.isTrashed(second.id)).get(), "direct delete forgets the entry");
            Document third = app.io.submit(() -> app.library.importDocument(Uri.fromFile(pdf(dir, "t3.pdf", "Trash third " + nonce)))).get();
            app.io.submit(() -> { app.library.trashDocument(third.id); return null; }).get();
            require(app.io.submit(() -> app.library.purgeTrash(Long.MAX_VALUE)).get() >= 1, "empty removes everything trashed");
            require(app.io.submit(() -> app.library.trashed().isEmpty()).get(), "nothing left in Recently deleted");
            mark.accept("Trash: direct deletion and Empty clear entries");
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
            dir.delete();
        }
        upgradeCheck(target, mark);
    }

    private static void scanChecks(Context target, Consumer<String> mark) throws Exception {
        StackApplication app = (StackApplication) target.getApplicationContext();
        app.libraryReady.await();
        File dir = new File(target.getCacheDir(), "scan-" + UUID.randomUUID());
        require(dir.mkdirs(), "scratch folder");
        try {
            String nonce = UUID.randomUUID().toString();
            File a = pdf(dir, "a.pdf", "Scan check A " + nonce);
            File c = pdf(dir, "c.pdf", "Scan check C " + nonce);
            Document first = app.io.submit(() -> app.library.importDocument(Uri.fromFile(a), UUID.randomUUID().toString(), "Scan first " + nonce)).get();
            Thread.sleep(20);
            Document second = app.io.submit(() -> app.library.importDocument(Uri.fromFile(a), UUID.randomUUID().toString(), "Scan second " + nonce)).get();
            Document other = app.io.submit(() -> app.library.importDocument(Uri.fromFile(c), UUID.randomUUID().toString(), "Scan other " + nonce)).get();

            List<Document> group = groupWith(DuplicateCheck.scan(app), first.id);
            require(group != null && group.size() == 2 && group.get(0).id.equals(first.id) && group.get(1).id.equals(second.id), "identical originals form one group, oldest first");
            require(groupWith(DuplicateCheck.scan(app), other.id) == null, "a unique document is in no group");
            mark.accept("Find duplicates: identical originals grouped oldest first, unique documents left out");

            app.io.submit(() -> { app.library.trashDocument(second.id); return null; }).get();
            require(groupWith(DuplicateCheck.scan(app), first.id) == null, "a trashed copy leaves the group");
            app.io.submit(() -> { app.library.restoreDocument(second.id); return null; }).get();
            require(groupWith(DuplicateCheck.scan(app), first.id) != null, "restoring brings the copy back");
            for (String id : new String[]{first.id, second.id, other.id}) app.io.submit(() -> { app.library.deleteDocument(id); return null; }).get();
            mark.accept("Find duplicates: trashed copy excluded, restored copy returns");
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
            dir.delete();
        }
    }

    private static List<Document> groupWith(List<List<Document>> groups, String id) {
        for (List<Document> group : groups) for (Document d : group) if (d.id.equals(id)) return group;
        return null;
    }

    private static void nudgeChecks(Context target, Consumer<String> mark) throws Exception {
        long day = 24L * 60 * 60 * 1000, now = System.currentTimeMillis();
        target.getSharedPreferences("backup_history", Context.MODE_PRIVATE).edit().clear().commit();
        target.getSharedPreferences("backup_nudge", Context.MODE_PRIVATE).edit().clear().commit();
        AutoBackup.prefs(target).edit().clear().commit();
        try {
            require(BackupNudge.message(target, 0, now).isEmpty(), "no documents, no reminder");
            require(BackupNudge.message(target, 3, now).equals("Your documents aren't backed up yet"), "documents but no backup");
            require(BackupHistory.completed(target, now - 10 * day) && BackupNudge.message(target, 3, now).isEmpty(), "a 10-day-old backup is fresh enough");
            require(BackupHistory.completed(target, now - 45 * day) && BackupNudge.message(target, 3, now).equals("Last backup was 45 days ago"), "a 45-day-old backup is reminded");
            BackupNudge.snooze(target);
            require(BackupNudge.message(target, 3, now).isEmpty(), "snooze silences the reminder");
            require(!BackupNudge.message(target, 3, now + 8 * day).isEmpty(), "reminder returns after the snooze");
            target.getSharedPreferences("backup_nudge", Context.MODE_PRIVATE).edit().clear().commit();
            AutoBackup.prefs(target).edit().putString("tree", "content://example/tree/x").commit();
            require(BackupNudge.message(target, 3, now).isEmpty(), "automatic backup on, no reminder");
            mark.accept("Backup reminder: none/never/fresh/stale/snoozed/automatic");
        } finally {
            target.getSharedPreferences("backup_history", Context.MODE_PRIVATE).edit().clear().commit();
            target.getSharedPreferences("backup_nudge", Context.MODE_PRIVATE).edit().clear().commit();
            AutoBackup.prefs(target).edit().clear().commit();
        }
    }

    /** A version 9 library (no trash table) opens, upgrades to 10 and keeps its documents. */
    private static void upgradeCheck(Context target, Consumer<String> mark) throws Exception {
        Context isolated = new ScannerChecks.IsolatedContext(target);
        String id = UUID.randomUUID().toString();
        LibraryDatabase first = new LibraryDatabase(isolated);
        try {
            android.database.sqlite.SQLiteDatabase db = first.getWritableDatabase();
            db.execSQL("INSERT INTO documents(id,title,mime,added_at,page_count,initial_page_count) VALUES(?,?,?,?,?,?)", new Object[]{id, "Upgrade specimen", "application/pdf", 1L, 1, 1});
            db.execSQL("DROP TRIGGER trash_cleanup");
            db.execSQL("DROP TABLE trash");
            db.setVersion(9);
        } finally { first.close(); }
        LibraryDatabase second = new LibraryDatabase(isolated);
        try {
            android.database.sqlite.SQLiteDatabase db = second.getWritableDatabase();
            require(db.getVersion() == 10, "library upgrades from version 9 to 10");
            try (android.database.Cursor c = db.rawQuery("SELECT title FROM documents WHERE id=?", new String[]{id})) { require(c.moveToFirst() && c.getString(0).equals("Upgrade specimen"), "documents survive the upgrade"); }
            db.execSQL("INSERT INTO trash(document_id,deleted_at) VALUES(?,1)", new Object[]{id});
            db.execSQL("DELETE FROM documents WHERE id=?", new Object[]{id});
            try (android.database.Cursor c = db.rawQuery("SELECT count(*) FROM trash", null)) { c.moveToFirst(); require(c.getInt(0) == 0, "cleanup trigger exists after the upgrade"); }
        } finally { second.close(); }
        mark.accept("Trash: version 9 library upgrades to 10 with its documents and trigger");
    }

    private static boolean contains(List<Document> documents, String id) {
        for (Document d : documents) if (d.id.equals(id)) return true;
        return false;
    }

    private static int stackCount(StackApplication app, String stack) {
        for (StackCategory c : app.library.stacks()) if (c.id.equals(stack)) return c.count;
        return -1;
    }

    private static File pdf(File dir, String name, String text) throws Exception {
        File file = new File(dir, name);
        PdfDocument pdf = new PdfDocument();
        try {
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setColor(Color.BLACK);
            ink.setTextSize(20);
            PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(600, 800, 1).create());
            page.getCanvas().drawColor(Color.WHITE);
            page.getCanvas().drawText(text, 20, 100, ink);
            pdf.finishPage(page);
            try (FileOutputStream out = new FileOutputStream(file)) { pdf.writeTo(out); }
        } finally { pdf.close(); }
        return file;
    }

    private static File copy(File from, File to) throws Exception {
        Files.copy(from.toPath(), to.toPath());
        return to;
    }

    /** Same length, one byte changed in the middle: size matches, content does not. */
    private static File sameSizeDifferent(File from, File to) throws Exception {
        byte[] bytes = Files.readAllBytes(from.toPath());
        bytes[bytes.length / 2] ^= 0x55;
        Files.write(to.toPath(), bytes);
        return to;
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
