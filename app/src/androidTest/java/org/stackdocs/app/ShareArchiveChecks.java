package org.stackdocs.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * S7-1 checks for the share archive. `runEnvelope` needs no org.json, so it also runs on a host JDK through main;
 * `run` covers the whole archive (it uses LinkRecord and ArchiveJson, which need Android's org.json). Synthetic data only.
 */
final class ShareArchiveChecks {
    private ShareArchiveChecks() {}
    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) throws Exception { runEnvelope(System.out::println); System.out.println("ALL PASS (host: envelope and sink only)"); }

    /** In-memory sink that can fail the commit, to check that a failure leaves nothing behind. */
    static final class MemorySink implements ShareArchive.Sink {
        final java.util.Map<String, byte[]> files = new java.util.HashMap<>();
        private final java.util.Map<String, ByteArrayOutputStream> open = new java.util.HashMap<>();
        boolean failCommit;
        @Override public OutputStream create(String temp) { ByteArrayOutputStream o = new ByteArrayOutputStream(); open.put(temp, o); return o; }
        @Override public void commit(String temp, String name) throws IOException {
            if (failCommit) throw new IOException("commit failed");
            files.put(name, open.remove(temp).toByteArray());
        }
        @Override public void discard(String temp) { open.remove(temp); }
    }

    private static byte[] key(int start) { byte[] k = new byte[32]; EnvelopeChecks.counter(start).fill(k); return k; }

    private static boolean readFails(byte[] file, byte[] key, Class<? extends IOException> kind) {
        try { ShareArchive.read(new ByteArrayInputStream(file), key); return false; }
        catch (IOException e) { return kind.isInstance(e) && e.getMessage() != null && !e.getMessage().isEmpty(); }
    }

    static void runEnvelope(Consumer<String> mark) throws Exception {
        byte[] salt = new byte[16]; EnvelopeChecks.counter(100).fill(salt);
        byte[] sharing = key(1), other = key(2);
        for (int size : new int[]{1, 65535, 65536, 65537, 200000}) {
            byte[] data = EnvelopeChecks.body(size);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (BackupEnvelope.Writer w = BackupEnvelope.encryptPartner(out, salt, BackupEnvelope.MIN_ITERATIONS, sharing, EnvelopeChecks.counter(50))) { w.write(data, 0, data.length); }
            byte[] file = out.toByteArray();
            try (BackupEnvelope.Reader r = BackupEnvelope.decryptPartner(new ByteArrayInputStream(file), sharing)) {
                ByteArrayOutputStream plain = new ByteArrayOutputStream(); byte[] buf = new byte[4000]; int n;
                while ((n = r.read(buf)) >= 0) plain.write(buf, 0, n);
                r.finish();
                require(Arrays.equals(plain.toByteArray(), data), "partner envelope round trip " + size);
            }
            if (size == 200000) {
                boolean wrong = false; try { BackupEnvelope.decryptPartner(new ByteArrayInputStream(file), other); } catch (BackupEnvelope.NoKey e) { wrong = e.getMessage().contains("sharing key"); }
                require(wrong, "wrong sharing key refused with a readable message");
                require(EnvelopeChecks.fails(file, "any passphrase", null, BackupEnvelope.NoKey.class), "a backup reader finds no passphrase slot in a share file");
                require(EnvelopeChecks.fails(file, null, sharing, BackupEnvelope.NoKey.class), "a backup reader finds no recovery slot in a share file");
                byte[] flipped = file.clone(); flipped[flipped.length - 100] ^= 1;
                boolean bad = false;
                try (BackupEnvelope.Reader r = BackupEnvelope.decryptPartner(new ByteArrayInputStream(flipped), sharing)) { r.finish(); } catch (BackupEnvelope.Damaged e) { bad = true; }
                require(bad, "a flipped byte fails");
                boolean cut = false;
                try (BackupEnvelope.Reader r = BackupEnvelope.decryptPartner(new ByteArrayInputStream(Arrays.copyOf(file, file.length - 70000)), sharing)) { r.finish(); } catch (BackupEnvelope.Damaged e) { cut = true; }
                require(cut, "a truncated file fails");
            }
        }
        mark.accept("share envelope: single partner slot round trip, wrong key, tampering, truncation");

        File dir = Files.createTempDirectory("stack-share-check").toFile();
        try {
            ShareArchive.FileSink sink = new ShareArchive.FileSink(dir);
            try (OutputStream o = sink.create("a.tmp")) { o.write(1); }
            sink.commit("a.tmp", "a");
            require(new File(dir, "a").exists() && !new File(dir, "a.tmp").exists(), "commit renames the temp file");
            try (OutputStream o = sink.create("a.tmp")) { o.write(new byte[]{2, 2}); }
            sink.commit("a.tmp", "a");
            require(new File(dir, "a").length() == 2, "commit replaces the earlier file");
            try (OutputStream o = sink.create("b.tmp")) { o.write(3); }
            sink.discard("b.tmp");
            require(!new File(dir, "b.tmp").exists(), "discard removes the temp file");
        } finally { for (File f : dir.listFiles()) f.delete(); dir.delete(); }
        mark.accept("share sink: atomic rename and discard");
    }

    // ---- full archive ---------------------------------------------------------------------------------------

    private static final String OWNER = "org.example.amipro";
    private static ShareArchive.Record record(String title, String text, byte[] preview, long generation, boolean isProtected) {
        return new ShareArchive.Record(OWNER, UUID.randomUUID().toString(), "doc", UUID.randomUUID().toString(), "/Shared/" + title + ".sam",
            title, "application/x-amipro-document", (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64),
            1_800_000_000_000L, text, preview, generation, isProtected);
    }
    private static byte[] pdf(int size) { byte[] b = new byte[size]; byte[] m = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII); System.arraycopy(m, 0, b, 0, m.length); for (int i = m.length; i < size; i++) b[i] = (byte) i; return b; }
    private static byte[] writeBytes(byte[] key, List<ShareArchive.Record> records, List<ShareArchive.Record> explicit, List<ShareArchive.Tombstone> tombstones) throws IOException {
        MemorySink sink = new MemorySink();
        ShareArchive.write(sink, "x.stackshare", key, records, explicit, tombstones, EnvelopeChecks.counter(9));
        return sink.files.get("x.stackshare");
    }
    private static boolean writeFails(List<ShareArchive.Record> records, List<ShareArchive.Record> explicit, List<ShareArchive.Tombstone> tombstones, Class<? extends IOException> kind) {
        MemorySink sink = new MemorySink();
        try { ShareArchive.write(sink, "x.stackshare", key(1), records, explicit, tombstones, EnvelopeChecks.counter(9)); return false; }
        catch (IOException e) { return kind.isInstance(e) && e.getMessage() != null && sink.files.isEmpty(); }
    }

    static void run(Consumer<String> mark) throws Exception {
        byte[] sharing = key(1), other = key(2);
        List<ShareArchive.Record> none = Collections.emptyList();
        List<ShareArchive.Tombstone> noTombs = Collections.emptyList();
        ShareArchive.Record a = record("First note", "alpha text with ünïcode 😀", pdf(5000), 3, false);
        ShareArchive.Record b = record("Second", "", null, 0, false);
        ShareArchive.Tombstone t = new ShareArchive.Tombstone(OWNER, UUID.randomUUID().toString(), 1_800_000_500_000L);
        byte[] file = writeBytes(sharing, Arrays.asList(a, b), none, Collections.singletonList(t));
        ShareArchive.Contents back = ShareArchive.read(new ByteArrayInputStream(file), sharing);
        require(back.records.size() == 2 && back.tombstones.size() == 1, "counts survive");
        ShareArchive.Record x = back.records.get(0), y = back.records.get(1);
        require(x.linkId.equals(a.linkId) && x.owner.equals(a.owner) && x.filenUuid.equals(a.filenUuid) && x.filenPath.equals(a.filenPath)
            && x.title.equals(a.title) && x.mime.equals(a.mime) && x.hash.equals(a.hash) && x.modified == a.modified && x.generation == 3
            && x.text.equals(a.text) && Arrays.equals(x.preview, a.preview) && !x.isProtected, "record fields survive");
        require(y.text.isEmpty() && y.preview == null && y.generation == 0, "empty text and no preview survive");
        ShareArchive.Tombstone tb = back.tombstones.get(0);
        require(tb.linkId.equals(t.linkId) && tb.owner.equals(OWNER) && tb.removedAt == t.removedAt, "tombstone survives");
        mark.accept("share archive: round trip with records, preview and tombstone");
        MemorySink stamped = new MemorySink();
        ShareArchive.write(stamped, "x.stackshare", sharing, Arrays.asList(a), none, noTombs, "00112233445566778899aabbccddeeff", 42, EnvelopeChecks.counter(9));
        ShareArchive.Contents stampedBack = ShareArchive.read(new ByteArrayInputStream(stamped.files.get("x.stackshare")), sharing);
        require("00112233445566778899aabbccddeeff".equals(stampedBack.epoch) && stampedBack.counter == 42, "epoch and counter survive inside the encrypted manifest");
        boolean badEpoch = false;
        try { ShareArchive.write(new MemorySink(), "x.stackshare", sharing, Arrays.asList(a), none, noTombs, "XYZ", 1, EnvelopeChecks.counter(9)); } catch (ShareArchive.Invalid e) { badEpoch = true; }
        require(badEpoch, "a malformed epoch is refused on write");
        require(ShareArchive.MAX_TOTAL == 256L << 20, "memory cap is 256 MiB");
        mark.accept("share archive: epoch and counter");

        require(readFails(file, other, BackupEnvelope.NoKey.class), "wrong key");
        for (int at : new int[]{10, 30, 100, 200, file.length / 2, file.length - 1}) {
            byte[] flipped = file.clone(); flipped[at] ^= 1;
            require(readFails(flipped, sharing, IOException.class), "flipped byte at " + at);
        }
        require(readFails(Arrays.copyOf(file, file.length - 1), sharing, BackupEnvelope.Damaged.class), "truncated by a byte");
        require(readFails(Arrays.copyOf(file, 20), sharing, BackupEnvelope.Damaged.class), "truncated in the header");
        require(readFails(new byte[0], sharing, BackupEnvelope.Damaged.class), "empty file");
        require(readFails(Arrays.copyOf(file, file.length + 1), sharing, BackupEnvelope.Damaged.class), "extended by a byte");
        mark.accept("share archive: wrong key, flipped bytes, truncation, extension fail cleanly");

        // Protected records: left out of the main list, in only when named separately.
        ShareArchive.Record p = record("Private", "secret", null, 1, true);
        ShareArchive.Record q = record("Named", "named", null, 1, false);
        MemorySink sink = new MemorySink();
        ShareArchive.WriteResult res = ShareArchive.write(sink, "x.stackshare", sharing, Arrays.asList(a, p), none, noTombs, EnvelopeChecks.counter(9));
        require(res.records == 1 && res.excludedProtected == 1, "protected record excluded and counted");
        ShareArchive.Contents c = ShareArchive.read(new ByteArrayInputStream(sink.files.get("x.stackshare")), sharing);
        require(c.records.size() == 1 && !c.records.get(0).isProtected, "protected record not in the file");
        ShareArchive.Contents named = ShareArchive.read(new ByteArrayInputStream(writeBytes(sharing, Arrays.asList(a), Arrays.asList(q), noTombs)), sharing);
        require(named.records.size() == 2 && named.records.get(1).isProtected, "an explicitly named record is included and marked protected");
        mark.accept("share archive: protected records excluded unless named");

        // Limits (each refusal leaves the sink untouched).
        List<ShareArchive.Record> many = new ArrayList<>();
        for (int i = 0; i < 1001; i++) many.add(record("n" + i, "t", null, 1, false));
        require(writeFails(many, none, noTombs, ShareArchive.LimitExceeded.class), "1001 records refused");
        List<ShareArchive.Record> thousand = many.subList(0, 1000);
        require(readFails(writeBytes(sharing, thousand, none, noTombs), other, BackupEnvelope.NoKey.class), "1000 records are accepted by the writer");
        char[] big = new char[LinkRecord.MAX_TEXT + 1]; Arrays.fill(big, 'a');
        require(writeFails(Arrays.asList(record("big", new String(big), null, 1, false)), none, noTombs, ShareArchive.LimitExceeded.class), "text over 1 MiB refused");
        require(writeFails(Arrays.asList(record("big", "t", pdf(LinkRecord.MAX_PREVIEW + 1), 1, false)), none, noTombs, ShareArchive.LimitExceeded.class), "preview over 32 MiB refused");
        require(writeFails(Arrays.asList(record("bad", "t", new byte[]{1, 2, 3}, 1, false)), none, noTombs, IOException.class), "a preview that is not a PDF refused");
        require(writeFails(Arrays.asList(a, a), none, noTombs, ShareArchive.Invalid.class), "a record listed twice refused");
        require(writeFails(Arrays.asList(a), none, Collections.singletonList(new ShareArchive.Tombstone(a.owner, a.linkId, 5)), ShareArchive.Invalid.class), "shared and removed at once refused");
        require(writeFails(Arrays.asList(new ShareArchive.Record("Not A Package", a.linkId, "doc", a.filenUuid, a.filenPath, "t", a.mime, a.hash, 1, "t", null, 1, false)), none, noTombs, IOException.class), "link rules apply on write");
        // Many previews together over 256 MiB: refused before anything is allocated beyond the previews themselves.
        byte[] shared = pdf(LinkRecord.MAX_PREVIEW);
        List<ShareArchive.Record> huge = new ArrayList<>();
        for (int i = 0; i < 65; i++) huge.add(record("h" + i, "t", shared, 1, false));
        require(writeFails(huge, none, noTombs, ShareArchive.LimitExceeded.class), "total over 256 MiB refused");
        // A failed commit leaves no file and no temp file.
        MemorySink failing = new MemorySink(); failing.failCommit = true;
        boolean threw = false; try { ShareArchive.write(failing, "x.stackshare", sharing, Arrays.asList(a), none, noTombs, EnvelopeChecks.counter(9)); } catch (IOException e) { threw = true; }
        require(threw && failing.files.isEmpty(), "a failed commit leaves nothing");
        mark.accept("share archive: limits refused with readable errors");

        // Strict reading: a hand-made file with an unknown field is refused.
        byte[] salt = new byte[16]; EnvelopeChecks.counter(3).fill(salt);
        for (String manifest : new String[]{
            "{\"version\":1,\"records\":[],\"tombstones\":[],\"extra\":1}",
            "{\"version\":2,\"records\":[],\"tombstones\":[]}",
            "{\"version\":1,\"records\":[],\"tombstones\":[{\"owner\":\"" + OWNER + "\",\"link_id\":\"" + UUID.randomUUID() + "\",\"removed_at\":1,\"x\":1}]}",
            "{\"version\":1,\"records\":[{\"contract\":1,\"bogus\":1,\"generation\":1}],\"tombstones\":[]}",
            "{\"version\":1,\"version\":1,\"records\":[],\"tombstones\":[]}"}) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (BackupEnvelope.Writer w = BackupEnvelope.encryptPartner(raw, salt, BackupEnvelope.MIN_ITERATIONS, sharing, EnvelopeChecks.counter(7))) {
                byte[] m = manifest.getBytes(StandardCharsets.UTF_8);
                w.write(ShareArchive.MAGIC, 0, 8); w.write(new byte[]{0, 0, (byte) (m.length >> 8), (byte) m.length}, 0, 4); w.write(m, 0, m.length);
            }
            require(readFails(raw.toByteArray(), sharing, ShareArchive.Invalid.class), "strict manifest refused: " + manifest.substring(0, 30));
        }
        mark.accept("share archive: unknown fields, duplicate keys and bad versions refused");

        File dir = Files.createTempDirectory("stack-share-run").toFile();
        try {
            ShareArchive.write(new ShareArchive.FileSink(dir), sharing, Arrays.asList(a), none, noTombs);
            File out = new File(dir, ShareArchive.FILE_NAME);
            require(out.exists() && dir.list().length == 1, "file written, no temp left");
            require(ShareArchive.read(out, sharing).records.size() == 1, "file round trip");
        } finally { for (File f : dir.listFiles()) f.delete(); dir.delete(); }
        mark.accept("share archive: file sink round trip");
    }
}
