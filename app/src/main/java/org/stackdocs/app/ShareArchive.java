package org.stackdocs.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The share archive (docs/STACK_LINK_SLICE7_DESIGN.md, piece 2, stage S7-1): one encrypted file holding the link records an owner
 * shares with the partner, plus tombstones for records no longer shared. The outer layer is {@link BackupEnvelope} with ONE
 * partner slot whose key is the 256-bit sharing key itself. Everything else (manifest, text, previews) is inside the encryption.
 *
 * Inner stream (plaintext, before the envelope's chunking): `STACKSH` 0x01, manifest length u32, manifest JSON (UTF-8), then for
 * each manifest record in order: text length u32 + UTF-8 text, preview length u32 (0 = none) + PDF bytes. Big-endian.
 * Manifest: {"version":1,"epoch":"<32 hex>","counter":n,"records":[LinkRecord JSON + "generation"],"tombstones":[{"owner","link_id","removed_at"}]}.
 * `epoch` (16 random bytes, new with each new send key) and `counter` (per sender, up by one on every publish) are inside the encryption; the importer
 * refuses an archive whose epoch is the one it already imported and whose counter is lower, so an old file cannot be put back.
 *
 * Pure Java apart from org.json (through {@link LinkRecord} and {@link ArchiveJson}); no Android context, no logging, keys never leave the caller.
 */
final class ShareArchive {
    static final String FILE_NAME = "Stack shared.stackshare";
    static final byte[] MAGIC = {'S', 'T', 'A', 'C', 'K', 'S', 'H', 1};
    static final int VERSION = 1, MAX_RECORDS = 1000, MAX_TOMBSTONES = 5000, MAX_MANIFEST = 4 << 20;
    /** Text and previews are held in memory on both sides, so the total is capped at what a phone can realistically hold. */
    static final long MAX_TOTAL = 256L << 20;
    static final long MAX_FILE = MAX_TOTAL + (64L << 20);

    static final String ZERO_EPOCH = "00000000000000000000000000000000";
    static final long MAX_COUNTER = 1L << 53;

    private ShareArchive() {}

    /** The file is refused, or the request would produce a file, over a stated limit. */
    static final class LimitExceeded extends IOException { LimitExceeded(String message) { super(message); } }
    /** The content is well-encrypted but not a valid share archive (or a record breaks the link rules). */
    static final class Invalid extends IOException { Invalid(String message) { super(message); } }

    static final class Record {
        final String owner, linkId, kind, filenUuid, filenPath, title, mime, hash, text;
        final long modified, generation;
        final byte[] preview;
        final boolean isProtected;
        Record(String owner, String linkId, String kind, String filenUuid, String filenPath, String title, String mime, String hash,
               long modified, String text, byte[] preview, long generation, boolean isProtected) {
            this.owner = owner; this.linkId = linkId; this.kind = kind; this.filenUuid = filenUuid; this.filenPath = filenPath;
            this.title = title; this.mime = mime; this.hash = hash; this.modified = modified; this.text = text;
            this.preview = preview; this.generation = generation; this.isProtected = isProtected;
        }
        String key() { return owner + "\n" + linkId; }
    }

    static final class Tombstone {
        final String owner, linkId; final long removedAt;
        Tombstone(String owner, String linkId, long removedAt) { this.owner = owner; this.linkId = linkId; this.removedAt = removedAt; }
        String key() { return owner + "\n" + linkId; }
    }

    static final class Contents {
        final List<Record> records; final List<Tombstone> tombstones;
        final String epoch; final long counter;
        Contents(List<Record> records, List<Tombstone> tombstones) { this(records, tombstones, ZERO_EPOCH, 0); }
        Contents(List<Record> records, List<Tombstone> tombstones, String epoch, long counter) {
            this.records = Collections.unmodifiableList(records); this.tombstones = Collections.unmodifiableList(tombstones);
            this.epoch = epoch; this.counter = counter;
        }
    }

    static final class WriteResult {
        final int records, tombstones, excludedProtected;
        WriteResult(int records, int tombstones, int excludedProtected) { this.records = records; this.tombstones = tombstones; this.excludedProtected = excludedProtected; }
    }

    /** Where the file goes. A SAF folder can implement this later; the writer only ever creates a temp name, then commits it over the final name. */
    interface Sink {
        OutputStream create(String tempName) throws IOException;
        /** Atomically makes `tempName` the file `finalName`, replacing any earlier one. */
        void commit(String tempName, String finalName) throws IOException;
        /** Removes the temp file after a failure; never throws. */
        void discard(String tempName);
    }

    /** A plain folder. The rename is atomic, so a reader sees the old file or the new one, never half a file. */
    static final class FileSink implements Sink {
        private final File dir;
        FileSink(File dir) { this.dir = dir; }
        @Override public OutputStream create(String tempName) throws IOException {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("The shared folder is not available.");
            return new FileOutputStream(new File(dir, tempName));
        }
        @Override public void commit(String tempName, String finalName) throws IOException {
            // rename(2) is atomic and replaces an existing file on the same file system (File.renameTo works on every supported API level).
            if (!new File(dir, tempName).renameTo(new File(dir, finalName))) throw new IOException("The shared folder cannot replace the file in one step.");
        }
        @Override public void discard(String tempName) { try { new File(dir, tempName).delete(); } catch (RuntimeException ignored) { } }
    }

    // ---- writing --------------------------------------------------------------------------------------------

    static WriteResult write(Sink sink, byte[] sharingKey, List<Record> records, List<Record> explicitProtected, List<Tombstone> tombstones) throws IOException {
        return write(sink, sharingKey, records, explicitProtected, tombstones, ZERO_EPOCH, 1);
    }

    static WriteResult write(Sink sink, byte[] sharingKey, List<Record> records, List<Record> explicitProtected, List<Tombstone> tombstones, String epoch, long counter) throws IOException {
        return write(sink, FILE_NAME, sharingKey, records, explicitProtected, tombstones, epoch, counter, new java.security.SecureRandom()::nextBytes);
    }

    static WriteResult write(Sink sink, String finalName, byte[] sharingKey, List<Record> records, List<Record> explicitProtected,
                             List<Tombstone> tombstones, BackupEnvelope.Rand random) throws IOException {
        return write(sink, finalName, sharingKey, records, explicitProtected, tombstones, ZERO_EPOCH, 1, random);
    }

    /**
     * Records marked protected in `records` are left out (counted in the result); only `explicitProtected` may carry protected records,
     * and they are written marked protected. Everything is checked before the sink is touched, so a refusal leaves any earlier file alone.
     */
    static WriteResult write(Sink sink, String finalName, byte[] sharingKey, List<Record> records, List<Record> explicitProtected,
                             List<Tombstone> tombstones, String epoch, long counter, BackupEnvelope.Rand random) throws IOException {
        checkKey(sharingKey);
        if (epoch == null || !epoch.matches("[0-9a-f]{32}") || counter < 0 || counter > MAX_COUNTER) throw new Invalid("The sharing counter is not valid.");
        List<Record> chosen = new ArrayList<>();
        int excluded = 0;
        for (Record r : records) { if (r.isProtected) excluded++; else chosen.add(r); }
        if (explicitProtected != null) for (Record r : explicitProtected) chosen.add(r.isProtected ? r : withProtected(r));
        if (chosen.size() > MAX_RECORDS) throw new LimitExceeded("Too many shared documents: at most " + MAX_RECORDS + " can be shared.");
        if (tombstones.size() > MAX_TOMBSTONES) throw new LimitExceeded("Too many removed-document notices: at most " + MAX_TOMBSTONES + ".");
        Set<String> keys = new HashSet<>();
        long total = 0;
        JSONArray recordJson = new JSONArray();
        for (Record r : chosen) {
            if (!keys.add(r.key())) throw new Invalid("A document is listed twice.");
            if (r.text == null) throw new Invalid("A shared document has no saved text.");
            if (r.text.length() > LinkRecord.MAX_TEXT) throw new LimitExceeded("The saved text of \"" + shorten(r.title) + "\" is over 1 MiB.");
            long textBytes = r.text.getBytes(StandardCharsets.UTF_8).length;
            long previewBytes = r.preview == null ? 0 : r.preview.length;
            if (previewBytes > LinkRecord.MAX_PREVIEW) throw new LimitExceeded("The preview of \"" + shorten(r.title) + "\" is over 32 MiB.");
            total += textBytes + previewBytes;
            if (total > MAX_TOTAL) throw new LimitExceeded("The shared documents together are over 256 MiB.");
            recordJson.put(recordJson(r));
        }
        JSONArray tombstoneJson = new JSONArray();
        for (Tombstone t : tombstones) {
            if (keys.contains(t.key()) || !keys.add(t.key())) throw new Invalid("A document is both shared and listed as removed, or listed twice.");
            tombstoneJson.put(tombstoneJson(t));
        }
        byte[] manifest;
        try {
            manifest = new JSONObject().put("version", VERSION).put("epoch", epoch).put("counter", counter).put("records", recordJson).put("tombstones", tombstoneJson).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) { throw new Invalid("The shared list could not be written."); }
        if (manifest.length > MAX_MANIFEST) throw new LimitExceeded("The shared list is too large.");

        String temp = finalName + ".tmp";
        OutputStream raw = sink.create(temp);
        boolean committed = false;
        try {
            byte[] salt = new byte[BackupEnvelope.SALT]; random.fill(salt);
            BackupEnvelope.Writer out = BackupEnvelope.encryptPartner(raw, salt, BackupEnvelope.MIN_ITERATIONS, sharingKey, random);
            out.write(MAGIC, 0, MAGIC.length);
            u32(out, manifest.length);
            out.write(manifest, 0, manifest.length);
            for (Record r : chosen) {
                byte[] text = r.text.getBytes(StandardCharsets.UTF_8);
                u32(out, text.length); out.write(text, 0, text.length);
                if (r.preview == null) u32(out, 0);
                else { u32(out, r.preview.length); out.write(r.preview, 0, r.preview.length); }
            }
            out.finish();
            raw.close();
            sink.commit(temp, finalName);
            committed = true;
        } finally {
            if (!committed) { try { raw.close(); } catch (IOException ignored) { } sink.discard(temp); }
        }
        return new WriteResult(chosen.size(), tombstones.size(), excluded);
    }

    private static Record withProtected(Record r) {
        return new Record(r.owner, r.linkId, r.kind, r.filenUuid, r.filenPath, r.title, r.mime, r.hash, r.modified, r.text, r.preview, r.generation, true);
    }

    private static JSONObject recordJson(Record r) throws IOException {
        try {
            JSONObject o = new JSONObject().put("contract", LinkRecord.CONTRACT).put("owner", r.owner).put("link_id", r.linkId)
                .put("kind", r.kind).put("title", r.title).put("mime", r.mime).put("modified", r.modified).put("hash", r.hash)
                .put("locators", new JSONArray().put(new JSONObject().put("type", "filen").put("uuid", r.filenUuid).put("path", r.filenPath)));
            if (r.isProtected) o.put("protected", true);
            // The same rules as every other link record, applied before anything is written.
            LinkRecord.parse(o.toString());
            if (r.generation < 0 || r.generation > (1L << 53)) throw new Invalid("A generation number is out of range.");
            LinkRecord.checkText(r.text);
            if (r.preview != null) LinkRecord.checkPreview(r.preview.length, Arrays.copyOf(r.preview, Math.min(r.preview.length, 8)));
            return o.put("generation", r.generation);
        } catch (JSONException error) { throw new Invalid("A shared document could not be written."); }
    }

    private static JSONObject tombstoneJson(Tombstone t) throws IOException {
        try {
            LinkRecord.parseUnshare(new JSONObject().put("contract", LinkRecord.CONTRACT).put("owner", t.owner).put("link_id", t.linkId).toString());
            if (t.removedAt < 0) throw new Invalid("A removal time is out of range.");
            return new JSONObject().put("owner", t.owner).put("link_id", t.linkId).put("removed_at", t.removedAt);
        } catch (JSONException error) { throw new Invalid("A removal notice could not be written."); }
    }

    // ---- reading --------------------------------------------------------------------------------------------

    static Contents read(File file, byte[] sharingKey) throws IOException {
        if (file.length() > MAX_FILE) throw new LimitExceeded("The shared file is too large.");
        try (InputStream in = new FileInputStream(file)) { return read(in, sharingKey); }
    }

    /** Everything is decrypted, authenticated to the final chunk and validated before anything is returned; a failure returns nothing. */
    static Contents read(InputStream raw, byte[] sharingKey) throws IOException {
        checkKey(sharingKey);
        try {
            return readChecked(raw, sharingKey);
        } catch (OutOfMemoryError tooBig) {
            throw new LimitExceeded("The shared file is too large for this phone's memory.");
        }
    }

    private static Contents readChecked(InputStream raw, byte[] sharingKey) throws IOException {
        try (BackupEnvelope.Reader in = BackupEnvelope.decryptPartner(new Capped(raw, MAX_FILE), sharingKey)) {
            byte[] magic = exactly(in, MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) throw new Invalid("This is not a Stack shared file.");
            long manifestLength = u32(in);
            if (manifestLength <= 0 || manifestLength > MAX_MANIFEST) throw new LimitExceeded("The shared list is too large.");
            String manifestText = decodeUtf8(exactly(in, (int) manifestLength), "the shared list");
            JSONObject manifest;
            try { manifest = ArchiveJson.read(manifestText); } catch (IOException e) { throw new Invalid("The shared list is malformed, duplicated or too deeply nested."); }
            exactKeys(manifest, "version", "epoch", "counter", "records", "tombstones");
            if (!(manifest.opt("version") instanceof Integer) || manifest.getInt("version") != VERSION) throw new Invalid("This shared file is from a newer version of Stack.");
            Object epochValue = manifest.opt("epoch"), counterValue = manifest.opt("counter");
            if (!(epochValue instanceof String) || !((String) epochValue).matches("[0-9a-f]{32}")) throw new Invalid("The shared file has no valid epoch.");
            if (!(counterValue instanceof Integer) && !(counterValue instanceof Long) || ((Number) counterValue).longValue() < 0 || ((Number) counterValue).longValue() > MAX_COUNTER) throw new Invalid("The shared file has no valid counter.");
            JSONArray recordList = array(manifest, "records"), tombstoneList = array(manifest, "tombstones");
            if (recordList.length() > MAX_RECORDS) throw new LimitExceeded("The shared file lists more than " + MAX_RECORDS + " documents.");
            if (tombstoneList.length() > MAX_TOMBSTONES) throw new LimitExceeded("The shared file lists too many removed documents.");
            List<Record> records = new ArrayList<>();
            List<Tombstone> tombstones = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            long total = 0;
            for (int i = 0; i < recordList.length(); i++) {
                Object entry = recordList.opt(i);
                if (!(entry instanceof JSONObject)) throw new Invalid("The shared list is not valid.");
                JSONObject o = (JSONObject) entry;
                Object generation = o.opt("generation");
                if (!(generation instanceof Integer) && !(generation instanceof Long) || ((Number) generation).longValue() < 0 || ((Number) generation).longValue() > (1L << 53)) throw new Invalid("A generation number is not valid.");
                o.remove("generation");
                LinkRecord link;
                try { link = LinkRecord.parse(o.toString()); } catch (Invalid e) { throw e; } catch (IOException e) { throw new Invalid("A shared document is not valid: " + e.getMessage()); }
                if (link.locators.size() != 1 || !link.locators.get(0).syncs()) throw new Invalid("A shared document must carry exactly one Filen location.");
                if (!keys.add(link.owner + "\n" + link.linkId)) throw new Invalid("A document is listed twice.");
                long textLength = u32(in);
                if (textLength > 3L * LinkRecord.MAX_TEXT) throw new LimitExceeded("A shared document's text is over 1 MiB.");
                String text = decodeUtf8(exactly(in, (int) textLength), "a document's text");
                LinkRecord.checkText(text);
                long previewLength = u32(in);
                if (previewLength > LinkRecord.MAX_PREVIEW) throw new LimitExceeded("A shared document's preview is over 32 MiB.");
                total += textLength + previewLength;
                if (total > MAX_TOTAL) throw new LimitExceeded("The shared documents together are over 256 MiB.");
                byte[] preview = null;
                if (previewLength > 0) { preview = exactly(in, (int) previewLength); LinkRecord.checkPreview(preview.length, preview); }
                LinkRecord.Locator locator = link.locators.get(0);
                records.add(new Record(link.owner, link.linkId, link.kind, locator.uuid, locator.path, link.title, link.mime, link.hash,
                    link.modified, text, preview, ((Number) generation).longValue(), link.isProtected));
            }
            for (int i = 0; i < tombstoneList.length(); i++) {
                Object entry = tombstoneList.opt(i);
                if (!(entry instanceof JSONObject)) throw new Invalid("The removed-document list is not valid.");
                JSONObject o = (JSONObject) entry;
                exactKeys(o, "owner", "link_id", "removed_at");
                Object at = o.opt("removed_at");
                if (!(at instanceof Integer) && !(at instanceof Long) || ((Number) at).longValue() < 0) throw new Invalid("A removal time is not valid.");
                Object owner = o.opt("owner"), linkId = o.opt("link_id");
                if (!(owner instanceof String) || !(linkId instanceof String)) throw new Invalid("A removal notice is not valid.");
                String[] checked;
                try { checked = LinkRecord.parseUnshare(new JSONObject().put("contract", LinkRecord.CONTRACT).put("owner", owner).put("link_id", linkId).toString()); } catch (IOException e) { throw new Invalid("A removal notice is not valid: " + e.getMessage()); }
                if (!keys.add(checked[0] + "\n" + checked[1])) throw new Invalid("A document is both shared and listed as removed, or listed twice.");
                tombstones.add(new Tombstone(checked[0], checked[1], ((Number) at).longValue()));
            }
            if (in.read() >= 0) throw new Invalid("The shared file has extra data at the end.");
            in.finish();
            return new Contents(records, tombstones, (String) epochValue, ((Number) counterValue).longValue());
        } catch (JSONException | IllegalArgumentException error) {
            throw new Invalid("The shared file is not valid.");
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static void checkKey(byte[] key) { if (key == null || key.length != 32) throw new IllegalArgumentException("The sharing key must be 32 bytes."); }
    private static String shorten(String s) { return s == null ? "" : s.length() <= 40 ? s : s.substring(0, 40); }

    private static void u32(OutputStream out, long value) throws IOException { byte[] b = ByteBuffer.allocate(4).putInt((int) value).array(); out.write(b, 0, 4); }
    private static long u32(InputStream in) throws IOException { return ByteBuffer.wrap(exactly(in, 4)).getInt() & 0xffffffffL; }

    private static byte[] exactly(InputStream in, int n) throws IOException {
        byte[] b = new byte[n]; int got = 0;
        while (got < n) { int r = in.read(b, got, n - got); if (r < 0) throw new BackupEnvelope.Damaged("The shared file is damaged (truncated)."); got += r; }
        return b;
    }

    private static String decodeUtf8(byte[] bytes, String what) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw new Invalid("The shared file has invalid text in " + what + "."); }
    }

    private static JSONArray array(JSONObject o, String key) throws IOException {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) throw new Invalid("The shared list is not valid.");
        return (JSONArray) v;
    }

    private static void exactKeys(JSONObject o, String... names) throws IOException {
        Set<String> allowed = new HashSet<>(Arrays.asList(names)), seen = new HashSet<>();
        for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
            String k = keys.next();
            if (!allowed.contains(k)) throw new Invalid("The shared file has an unknown field.");
            seen.add(k);
        }
        if (seen.size() != allowed.size()) throw new Invalid("The shared file is missing a field.");
    }

    /** Stops reading past the largest legitimate file. */
    private static final class Capped extends InputStream {
        private final InputStream in; private long left;
        Capped(InputStream in, long max) { this.in = in; this.left = max; }
        @Override public int read() throws IOException { byte[] one = new byte[1]; int r = read(one, 0, 1); return r < 0 ? -1 : one[0] & 0xff; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int r = in.read(b, off, (int) Math.min(len, left + 1));
            if (r > 0) { left -= r; if (left < 0) throw new LimitExceeded("The shared file is too large."); }
            return r;
        }
    }
}
