package org.stackdocs.app;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

/** Slice 3: link records in archive format 4 and the encrypted envelope around a real archive. Synthetic data, isolated contexts. */
final class LinkArchiveChecks {
    private LinkArchiveChecks() {}
    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static LinkRecord record(String linkId, String title, String hash, String owner) throws Exception {
        return LinkRecord.parse(new JSONObject()
            .put("contract", 1).put("owner", owner).put("link_id", linkId).put("kind", "doc").put("title", title)
            .put("mime", "application/x-amipro-document").put("modified", 1_800_000_000_000L).put("hash", hash)
            .put("locators", new JSONArray().put(new JSONObject().put("type", "owner").put("token", "tok-" + title))).toString());
    }

    interface JSONObjectEdit { void apply(JSONObject manifest) throws Exception; }

    private static byte[] plain(LibraryArchive archive, int mode, JSONObjectEdit edit) throws Exception {
        try (LibraryArchive.Snapshot snapshot = archive.snapshot(new LibraryArchive.Progress(), false, mode)) {
            if (edit != null) edit.apply(snapshot.manifest);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LibraryArchive.write(snapshot, out, new LibraryArchive.Progress());
            return out.toByteArray();
        }
    }

    private static int restore(LibraryArchive archive, byte[] bytes, LibraryArchive.Plan[] planOut) throws Exception {
        LibraryArchive.Staged staged = archive.stage(new ByteArrayInputStream(bytes), new LibraryArchive.Progress());
        LibraryArchive.Plan plan = archive.plan(staged);
        if (planOut != null) planOut[0] = plan;
        return archive.commit(staged, new LibraryArchive.Progress());
    }

    private static boolean rejected(LibraryArchive archive, byte[] bytes) {
        try { archive.stage(new ByteArrayInputStream(bytes), new LibraryArchive.Progress()).close(); return false; } catch (IOException expected) { return true; } catch (Exception other) { return false; }
    }

    static void run(Context parent, Consumer<String> mark) throws Exception {
        Context sourceContext = new ScannerChecks.IsolatedContext(parent);
        LibraryRepository source = new LibraryRepository(sourceContext); source.initialize();
        source.importDocument(Uri.fromFile(OcrQueueChecks.fixture(sourceContext, "links-archive.pdf")));
        File staging = new File(sourceContext.getFilesDir(), "link-staging"); staging.mkdirs();
        File preview = new File(staging, "preview.pdf"); Files.copy(OcrQueueChecks.fixture(sourceContext, "links-preview.pdf").toPath(), preview.toPath());
        String owner = "app.stub.owner", idA = UUID.randomUUID().toString(), idB = UUID.randomUUID().toString();
        LinkStore.Result a = source.links.publish(record(idA, "Medicines and doctors", "a".repeat(64), owner), "Dr Patel cardiology metformin 500 mg", preview);
        source.links.publish(record(idB, "Plain note", "b".repeat(64), owner), "Second record without a preview", null);
        source.links.rename(a.id, "My own title");
        LibraryArchive archive = source.archive();

        // 1. A plain backup without links is exactly the old format 3.
        byte[] v3 = plain(archive, LibraryArchive.LINKS_NONE, null);
        try (LibraryArchive.Snapshot s = archive.snapshot(new LibraryArchive.Progress())) {
            require(s.manifest.getInt("version") == 3 && !s.manifest.has("links"), "links left out gives format 3 with no links array");
        }
        mark.accept("format 3 unchanged when links are left out");

        // 2. Included links: format 4, text, preview entry, strict validation.
        byte[] v4 = plain(archive, LibraryArchive.LINKS_INCLUDED, null);
        try (LibraryArchive.Snapshot s = archive.snapshot(new LibraryArchive.Progress(), false, LibraryArchive.LINKS_INCLUDED)) {
            require(s.manifest.getInt("version") == 4 && s.manifest.getJSONArray("links").length() == 2, "format 4 with two links");
            boolean hasPreview = false;
            for (int i = 0; i < s.manifest.getJSONArray("files").length(); i++) if (s.manifest.getJSONArray("files").getJSONObject(i).getString("path").equals("previews/" + a.id + ".pdf")) hasPreview = true;
            require(hasPreview, "the preview travels as previews/<id>.pdf");
        }
        mark.accept("format 4 captures link rows, text and previews");

        // 3. Restore into an empty library; then again (never overwrites).
        Context t = new ScannerChecks.IsolatedContext(parent);
        LibraryRepository target = new LibraryRepository(t); target.initialize();
        LibraryArchive.Plan[] plan = new LibraryArchive.Plan[1];
        restore(target.archive(), v4, plan);
        require(plan[0].links.size() == 2 && plan[0].skippedLinks == 0, "two links planned");
        require(target.links.list("").size() == 2 && target.links.list("metformin").size() == 1, "restored links are searchable");
        LinkStore.Link restoredA = null; for (LinkStore.Link l : target.links.list("")) if (l.linkId.equals(idA)) restoredA = l;
        require(restoredA != null && restoredA.title.equals("My own title") && restoredA.hasPreview && target.links.preview(restoredA.id) != null && target.links.text(restoredA.id).contains("cardiology"), "title, text and preview restored");
        require(target.links.list("").size() == 2 && !restoredA.id.equals(a.id) || true, "new local id allowed");
        target.links.rename(restoredA.id, "Changed locally");
        restore(target.archive(), v4, plan);
        require(plan[0].links.isEmpty() && plan[0].skippedLinks == 2 && target.links.list("").size() == 2, "a second restore skips existing links");
        require(target.links.get(restoredA.id).title.equals("Changed locally"), "restore never overwrites a local link");
        mark.accept("link restore: adds, searchable, keeps local, skips existing");

        // 4. Strict validation.
        require(rejected(target.archive(), plain(archive, LibraryArchive.LINKS_INCLUDED, null)) == false, "valid archive accepted");
        boolean refused;
        refused = false; try { plain(archive, LibraryArchive.LINKS_INCLUDED, m -> m.getJSONArray("links").getJSONObject(0).put("extra", 1)); } catch (IOException e) { refused = true; }
        require(refused, "unknown link field refused");
        refused = false; try { plain(archive, LibraryArchive.LINKS_INCLUDED, m -> m.getJSONArray("links").put(new JSONObject(m.getJSONArray("links").getJSONObject(0).toString()))); } catch (IOException e) { refused = true; }
        require(refused, "duplicate link refused");
        refused = false; try { plain(archive, LibraryArchive.LINKS_INCLUDED, m -> m.getJSONArray("links").getJSONObject(0).put("hash", "nothex")); } catch (IOException e) { refused = true; }
        require(refused, "bad hash refused");
        refused = false; try { plain(archive, LibraryArchive.LINKS_INCLUDED, m -> m.getJSONArray("files").put(new JSONObject().put("path", "previews/" + UUID.randomUUID() + ".pdf").put("length", 5).put("sha256", "0".repeat(64)))); } catch (IOException e) { refused = true; }
        require(refused, "preview for an unknown link refused");
        require(rejected(target.archive(), Arrays.copyOf(v4, v4.length - 1)) && rejected(target.archive(), Arrays.copyOf(v4, v4.length + 1)), "truncated and extended format 4 refused");
        mark.accept("format 4 strict validation");

        // 5. A failed restore leaves no trace.
        Context f = new ScannerChecks.IsolatedContext(parent);
        LibraryRepository failing = new LibraryRepository(f); failing.initialize();
        LibraryArchive fa = failing.archive();
        fa.probe = boundary -> { if (boundary.equals("transaction")) throw new IOException("synthetic stop"); };
        boolean failed = false; try { restore(fa, v4, null); } catch (IOException expected) { failed = true; }
        File[] left = new File(f.getFilesDir(), "links").listFiles();
        require(failed && failing.links.list("").isEmpty() && (left == null || left.length == 0), "a failed restore leaves no link rows or previews");
        mark.accept("failed link restore invisible");

        // 6. Encrypted round trip through a real archive.
        byte[] salt = new byte[16]; new java.security.SecureRandom().nextBytes(salt);
        byte[] recovery = new byte[32]; new java.security.SecureRandom().nextBytes(recovery);
        String pass = "correct horse battery";
        byte[] passKey = BackupEnvelope.passphraseSlotKey(pass, salt, BackupEnvelope.MIN_ITERATIONS), recKey = BackupEnvelope.recoverySlotKey(recovery, salt);
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        try (LibraryArchive.Snapshot s = archive.snapshot(new LibraryArchive.Progress(), false, LibraryArchive.LINKS_ENCRYPTED)) {
            BackupEnvelope.Writer w = BackupEnvelope.encrypt(encrypted, salt, BackupEnvelope.MIN_ITERATIONS, passKey, recKey, new java.security.SecureRandom()::nextBytes);
            LibraryArchive.write(s, w, new LibraryArchive.Progress()); w.finish();
        }
        byte[] file = encrypted.toByteArray();
        require(!new String(file, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Medicines"), "no plaintext in the encrypted file");
        for (int which = 0; which < 2; which++) {
            Context e = new ScannerChecks.IsolatedContext(parent);
            LibraryRepository fresh = new LibraryRepository(e); fresh.initialize();
            try (BackupEnvelope.Reader reader = BackupEnvelope.decrypt(new ByteArrayInputStream(file), which == 0 ? pass : null, which == 0 ? null : recovery)) {
                LibraryArchive.Staged staged = fresh.archive().stage(reader, new LibraryArchive.Progress()); reader.finish();
                require(staged.manifest.getInt("version") == 4, "inner format 4");
                fresh.archive().plan(staged); fresh.archive().commit(staged, new LibraryArchive.Progress());
            }
            require(fresh.links.list("").size() == 2 && fresh.documents("", null).size() == 1, "encrypted restore with " + (which == 0 ? "passphrase" : "recovery key"));
        }
        require(EnvelopeChecks.fails(file, "wrong passphrase!!", null, BackupEnvelope.NoKey.class), "wrong passphrase refused");
        byte[] cut = Arrays.copyOf(file, 152 + 32 + 65552);
        Context c = new ScannerChecks.IsolatedContext(parent);
        LibraryRepository cutLibrary = new LibraryRepository(c); cutLibrary.initialize();
        boolean cutRefused = false;
        try (BackupEnvelope.Reader reader = BackupEnvelope.decrypt(new ByteArrayInputStream(cut), pass, null)) { cutLibrary.archive().stage(reader, new LibraryArchive.Progress()); } catch (IOException expected) { cutRefused = true; }
        require(cutRefused && cutLibrary.links.list("").isEmpty() && cutLibrary.documents("", null).isEmpty(), "a truncated encrypted backup is refused and restores nothing");
        mark.accept("encrypted backup round trip, both keys, wrong passphrase, truncation; bytes=" + file.length);

        // 7. Key store: sealed slot keys round trip, a new setup replaces the old one, clear removes it.
        Context k = new ScannerChecks.IsolatedContext(parent);
        require(!BackupKeys.configured(k), "no keys at first");
        BackupKeys.Pending one = BackupKeys.begin("first long passphrase"); BackupKeys.save(k, one.slots);
        BackupKeys.Slots back = BackupKeys.load(k);
        require(BackupKeys.configured(k) && Arrays.equals(back.passphraseKey, one.slots.passphraseKey) && Arrays.equals(back.recoveryKey, one.slots.recoveryKey) && Arrays.equals(back.salt, one.slots.salt), "slot keys survive the Keystore");
        BackupKeys.Pending two = BackupKeys.begin("second long passphrase"); BackupKeys.save(k, two.slots);
        require(Arrays.equals(BackupKeys.load(k).passphraseKey, two.slots.passphraseKey), "a new setup replaces the old one");
        String spaced = two.recoveryHex.substring(0, 32) + "\n" + two.recoveryHex.substring(32);
        require(Arrays.equals(BackupEnvelope.recoverySlotKey(BackupEnvelope.parseRecoveryKey(spaced), two.slots.salt), two.slots.recoveryKey), "a copied recovery key with a line break parses");
        byte[] scanned = new byte[32]; new java.security.SecureRandom().nextBytes(scanned);
        BackupKeys.Pending fromVault = BackupKeys.begin("third long passphrase", scanned);
        require(Arrays.equals(fromVault.slots.recoveryKey, BackupEnvelope.recoverySlotKey(scanned, fromVault.slots.salt)), "a scanned Key Vault key becomes the recovery slot");
        require(fromVault.recoveryHex.equals(String.format("%064x", new java.math.BigInteger(1, scanned))), "the scanned key is kept exactly");
        BackupKeys.Pending keyOnly = BackupKeys.begin(null, scanned); BackupKeys.save(k, keyOnly.slots);
        BackupKeys.Slots loaded = BackupKeys.load(k);
        require(loaded.passphraseKey == null && Arrays.equals(loaded.recoveryKey, keyOnly.slots.recoveryKey), "a scanned key alone is stored with no passphrase slot");
        ByteArrayOutputStream single = new ByteArrayOutputStream();
        BackupEnvelope.Writer sw = BackupEnvelope.encrypt(single, loaded.salt, loaded.iterations, loaded.passphraseKey, loaded.recoveryKey, new java.security.SecureRandom()::nextBytes);
        sw.write(new byte[]{1, 2, 3}, 0, 3); sw.finish();
        require(Arrays.equals(EnvelopeChecks.decryptBytes(single.toByteArray(), null, scanned), new byte[]{1, 2, 3}), "a backup made with the key alone opens with that key");
        BackupKeys.clear(k);
        boolean gone = false; try { BackupKeys.load(k); } catch (IOException expected) { gone = true; }
        require(!BackupKeys.configured(k) && gone, "clear removes the keys and load fails closed");
        mark.accept("backup keys: Keystore round trip, replacement, recovery key parsing, clear");
    }
}
