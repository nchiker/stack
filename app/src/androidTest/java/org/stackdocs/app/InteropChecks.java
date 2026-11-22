package org.stackdocs.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Dev-only archive interop bed (phone side). Works only inside an IsolatedContext named by `-e replicaName interop-...` on the
 * disposable Pixel 4a Checks install; the desktop harness pushes inbox.stackbackup and pulls outbox files. Synthetic data only.
 * TEST_KEY is public (bytes 0x01..0x20, the same as desktop stack-core tests/links.rs KEY_TEXT); it is the only key used here.
 * Each phase reports one mark line: `interop-<phase> {json}`.
 * Options (all optional, `-e name true`): `interopPassphrase` (write also makes outbox-passphrase.stackbackup, encrypted with the recovery
 * slot AND a passphrase slot from the public synthetic TEST_PASSPHRASE) and `interopBrief` (read reports counts, a title digest and the
 * previews only, not one json object per link; used for the 1000-link file). `read` always reports `read_ms`, the time of the first restore.
 */
final class InteropChecks {
    private InteropChecks() {}
    static final byte[] TEST_KEY = new byte[32];
    static { for (int i = 0; i < 32; i++) TEST_KEY[i] = (byte) (i + 1); }
    /** Public synthetic passphrase, deliberately not NFC (e + U+0301, u + U+0308): BackupEnvelope.passphraseSlotKey normalises to NFC itself. */
    static final String TEST_PASSPHRASE = "Cafe\u0301 Stack u\u0308ber 7";
    private static final String OWNER = "app.interop.owner";

    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static void run(Context target, Bundle args, Consumer<String> mark) throws Exception {
        String phase = args.getString("interopPhase", ""), name = args.getString("replicaName", "");
        if (!ScannerChecks.checksTarget(target) || target.getPackageName().equals("org.stackdocs.app")
            || !"0A121JEC203530".equals(args.getString("disposableDevice"))
            || !"sunfish".equals(android.os.Build.DEVICE) || !"Pixel 4a".equals(android.os.Build.MODEL))
            throw new SecurityException("Interop checks need the disposable Pixel 4a Checks installation");
        if (!name.matches("interop-[a-z0-9-]+")) throw new IllegalArgumentException("replicaName must match interop-[a-z0-9-]+");
        File root = new File(target.getFilesDir(), "checks/" + name);
        if (phase.equals("cleanup")) { delete(root); mark.accept("interop-cleanup " + new JSONObject().put("removed", !root.exists())); return; }
        Context replica = new ScannerChecks.IsolatedContext(target, name);
        LibraryRepository library = new LibraryRepository(replica); library.initialize();
        require(library.links.list("").isEmpty() && library.documents("", null).isEmpty(), "replica library is not fresh; run cleanup first");
        if (phase.equals("read")) mark.accept("interop-read " + read(replica, library, "true".equals(args.getString("interopBrief"))));
        else if (phase.equals("write")) mark.accept("interop-write " + write(replica, library, "true".equals(args.getString("interopPassphrase"))));
        else throw new IllegalArgumentException("interopPhase must be read, write or cleanup");
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }

    private static byte[] all(File f) throws IOException { return Files.readAllBytes(f.toPath()); }

    private static String sha(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static boolean encrypted(byte[] file) {
        return file.length >= BackupEnvelope.MAGIC.length && Arrays.equals(Arrays.copyOf(file, BackupEnvelope.MAGIC.length), BackupEnvelope.MAGIC);
    }

    /** Stage, plan and commit one archive (decrypting with `key` when it is an envelope); returns the plan. */
    private static LibraryArchive.Plan restore(LibraryRepository library, byte[] file, byte[] key) throws Exception {
        LibraryArchive archive = library.archive();
        LibraryArchive.Staged staged;
        if (encrypted(file)) {
            try (BackupEnvelope.Reader reader = BackupEnvelope.decrypt(new ByteArrayInputStream(file), null, key)) {
                staged = archive.stage(reader, new LibraryArchive.Progress()); reader.finish();
            }
        } else staged = archive.stage(new ByteArrayInputStream(file), new LibraryArchive.Progress());
        LibraryArchive.Plan plan = archive.plan(staged);
        archive.commit(staged, new LibraryArchive.Progress());
        return plan;
    }

    /** Sum of SHA-256(UTF-8 title) as 256-bit integers modulo 2^256, 64 hex digits: order independent, the same on the desktop harness. */
    static String titlesDigest(Iterable<String> titles) throws Exception {
        java.math.BigInteger total = java.math.BigInteger.ZERO;
        for (String t : titles) total = total.add(new java.math.BigInteger(1, MessageDigest.getInstance("SHA-256").digest(t.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return String.format("%064x", total.mod(java.math.BigInteger.ONE.shiftLeft(256)));
    }

    private static String read(Context replica, LibraryRepository library, boolean brief) throws Exception {
        byte[] file = all(new File(replica.getFilesDir(), "inbox.stackbackup"));
        boolean enc = encrypted(file);
        JSONObject negative = new JSONObject();
        if (enc) {
            byte[] wrong = TEST_KEY.clone(); wrong[0] ^= 1;
            boolean noKey = false;
            try { BackupEnvelope.decrypt(new ByteArrayInputStream(file), null, wrong).close(); } catch (BackupEnvelope.NoKey expected) { noKey = true; }
            require(noKey, "a flipped key must give NoKey");
            negative.put("wrong_key", "NoKey");
        }
        byte[] cut = Arrays.copyOf(file, file.length / 2);
        String cutKind = null;
        try { restore(library, cut, TEST_KEY); } catch (IOException e) { cutKind = e.getClass().getSimpleName(); }
        require(cutKind != null && (!enc || cutKind.equals("Damaged")), "a truncated copy must be refused (Damaged when encrypted), got " + cutKind);
        require(library.links.list("").isEmpty() && library.documents("", null).isEmpty(), "a truncated copy restores nothing");
        negative.put("truncated", cutKind).put("truncated_restored", 0);

        long started = System.nanoTime();
        LibraryArchive.Plan plan = restore(library, file, TEST_KEY);
        long readMs = (System.nanoTime() - started) / 1_000_000L;
        JSONArray links = new JSONArray(), docs = new JSONArray(), previews = new JSONArray();
        java.util.List<String> linkTitles = new java.util.ArrayList<>();
        for (Document d : library.documents("", null)) docs.put(d.title);
        for (LinkStore.Link l : library.links.list("")) {
            require(l.state.equals("unreachable"), "restored link must be unreachable, was " + l.state);
            String text = library.links.text(l.id);
            boolean searchable = false;
            for (String word : text.split("\\s+")) if (word.length() >= 4) { for (LinkStore.Link hit : library.links.list(word)) searchable |= hit.id.equals(l.id); break; }
            require(text.isEmpty() || searchable, "link text must be searchable: " + l.title);
            File preview = library.links.preview(l.id);
            require(l.hasPreview == (preview != null), "preview(id) must match hasPreview");
            String previewSha = preview == null ? null : sha(all(preview));
            linkTitles.add(l.title);
            if (preview != null) previews.put(new JSONObject().put("link_id", l.linkId).put("preview_sha256", previewSha).put("bytes", preview.length()));
            if (!brief) links.put(new JSONObject().put("link_id", l.linkId).put("owner", l.owner).put("title", l.title).put("state", l.state)
                .put("text_len", text.length()).put("searchable", searchable).put("has_preview", preview != null)
                .put("preview_sha256", previewSha == null ? JSONObject.NULL : previewSha));
        }
        require(linkTitles.size() == plan.links.size(), "stored links match the plan");
        LibraryArchive.Plan again = restore(library, file, TEST_KEY);
        require(again.links.isEmpty() && again.skippedLinks == plan.links.size() && library.links.list("").size() == plan.links.size(), "re-import skips every link");
        JSONObject result = new JSONObject().put("encrypted", enc).put("documents", plan.documents.size()).put("document_titles", docs)
            .put("links_added", plan.links.size()).put("links_skipped", plan.skippedLinks).put("reimport_links_skipped", again.skippedLinks)
            .put("read_ms", readMs).put("link_titles_digest", titlesDigest(linkTitles)).put("negative", negative);
        if (brief) result.put("brief", true).put("previews", previews); else result.put("links", links);
        return result.toString();
    }

    private static String write(Context replica, LibraryRepository library, boolean withPassphrase) throws Exception {
        JSONArray docs = new JSONArray(), links = new JSONArray();
        for (String label : new String[]{"Interop document one", "Interop document two"}) {
            File f = new File(replica.getCacheDir(), label.toLowerCase().replace(' ', '-') + ".pdf");
            Files.write(f.toPath(), LinkUiChecks.pdf(1, 595, 842, label));
            docs.put(library.importDocument(Uri.fromFile(f)).title);
        }
        String[] titles = {"Phone link alpha", "Phone link beta", "Phone link gamma"}, words = {"cardiology metformin", "umbrella tangerine", "lantern quartz"};
        for (int i = 0; i < 3; i++) {
            String linkId = UUID.randomUUID().toString(), hash = String.valueOf(i + 1).repeat(64);
            LinkRecord record = LinkRecord.parse(new JSONObject().put("contract", 1).put("owner", OWNER).put("link_id", linkId).put("kind", "doc").put("title", titles[i])
                .put("mime", "application/x-amipro-document").put("modified", 1_800_000_000_000L + i).put("hash", hash)
                .put("locators", new JSONArray().put(new JSONObject().put("type", "owner").put("token", "tok-" + i))).toString());
            File preview = null; String previewSha = null;
            if (i < 2) {
                byte[] bytes = LinkUiChecks.pdf(1, 400, 300, titles[i]);
                preview = new File(replica.getCacheDir(), "interop-preview-" + i + ".pdf"); Files.write(preview.toPath(), bytes);
                previewSha = sha(bytes);
            }
            library.links.publish(record, "Text of " + titles[i] + " " + words[i], preview);
            links.put(new JSONObject().put("link_id", linkId).put("owner", OWNER).put("title", titles[i]).put("hash", hash)
                .put("text", "Text of " + titles[i] + " " + words[i]).put("has_preview", i < 2).put("preview_sha256", previewSha == null ? JSONObject.NULL : previewSha));
        }
        LibraryArchive archive = library.archive();
        File plainFile = new File(replica.getFilesDir(), "outbox-plain.stackbackup"), encFile = new File(replica.getFilesDir(), "outbox.stackbackup");
        try (LibraryArchive.Snapshot s = archive.snapshot(new LibraryArchive.Progress(), false, LibraryArchive.LINKS_INCLUDED);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(plainFile))) { LibraryArchive.write(s, out, new LibraryArchive.Progress()); }
        byte[] salt = new byte[16]; new java.security.SecureRandom().nextBytes(salt);
        try (LibraryArchive.Snapshot s = archive.snapshot(new LibraryArchive.Progress(), false, LibraryArchive.LINKS_ENCRYPTED);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(encFile))) {
            BackupEnvelope.Writer w = BackupEnvelope.encrypt(out, salt, BackupEnvelope.MIN_ITERATIONS, null, BackupEnvelope.recoverySlotKey(TEST_KEY, salt), new java.security.SecureRandom()::nextBytes);
            LibraryArchive.write(s, w, new LibraryArchive.Progress()); w.finish();
        }
        require(encrypted(all(encFile)) && !encrypted(all(plainFile)), "outbox.stackbackup is an envelope and outbox-plain.stackbackup is not");
        JSONObject result = new JSONObject().put("documents", docs.length()).put("document_titles", docs).put("links", links)
            .put("encrypted_file", "outbox.stackbackup").put("encrypted_length", encFile.length())
            .put("plain_file", "outbox-plain.stackbackup").put("plain_length", plainFile.length());
        if (withPassphrase) {
            // Two slots under one data key: the passphrase slot (key derived from the NFC UTF-8 bytes by the production code) and the recovery slot.
            File passFile = new File(replica.getFilesDir(), "outbox-passphrase.stackbackup");
            byte[] passSalt = new byte[BackupEnvelope.SALT]; new java.security.SecureRandom().nextBytes(passSalt);
            try (LibraryArchive.Snapshot s = archive.snapshot(new LibraryArchive.Progress(), false, LibraryArchive.LINKS_ENCRYPTED);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(passFile))) {
                BackupEnvelope.Writer w = BackupEnvelope.encrypt(out, passSalt, BackupEnvelope.MIN_ITERATIONS,
                    BackupEnvelope.passphraseSlotKey(TEST_PASSPHRASE, passSalt, BackupEnvelope.MIN_ITERATIONS),
                    BackupEnvelope.recoverySlotKey(TEST_KEY, passSalt), new java.security.SecureRandom()::nextBytes);
                LibraryArchive.write(s, w, new LibraryArchive.Progress()); w.finish();
            }
            byte[] file = all(passFile);
            require(encrypted(file), "outbox-passphrase.stackbackup is an envelope");
            // Both slots open it here too, and a wrong passphrase gives NoKey.
            for (boolean byKey : new boolean[]{true, false})
                try (BackupEnvelope.Reader r = BackupEnvelope.decrypt(new ByteArrayInputStream(file), byKey ? null : TEST_PASSPHRASE, byKey ? TEST_KEY : null)) {
                    byte[] sink = new byte[8192]; while (r.read(sink) >= 0) { /* verify every chunk */ } r.finish();
                }
            boolean noKey = false;
            try { BackupEnvelope.decrypt(new ByteArrayInputStream(file), TEST_PASSPHRASE + "!", null).close(); } catch (BackupEnvelope.NoKey expected) { noKey = true; }
            require(noKey, "a wrong passphrase must give NoKey");
            result.put("passphrase_file", "outbox-passphrase.stackbackup").put("passphrase_length", passFile.length());
        }
        return result.toString();
    }
}
