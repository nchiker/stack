package org.stackdocs.app;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * S7-2 checks (publish side): the per-link switch and its migration column, tombstones and their 30 days, the archive built from flagged
 * links only, protected links held back unless confirmed, key storage and rotation, and that the key is never in a library export.
 * A plain folder stands in for the granted folder (the SAF sink needs a provider and is checked on the device by hand). Synthetic data only.
 */
final class PartnerPublishChecks {
    private PartnerPublishChecks() {}
    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final String OWNER = "app.stub.owner";

    private static LinkRecord record(String linkId, String title, String hash, boolean filen, boolean isProtected) throws Exception {
        JSONObject locator = filen ? new JSONObject().put("type", "filen").put("uuid", UUID.randomUUID().toString()).put("path", "/Docs/" + title + ".sam")
                : new JSONObject().put("type", "owner").put("token", "tok-" + title);
        JSONObject o = new JSONObject().put("contract", 1).put("owner", OWNER).put("link_id", linkId).put("kind", "doc").put("title", title)
                .put("mime", "application/x-amipro-document").put("modified", 1_800_000_000_000L).put("hash", hash).put("locators", new JSONArray().put(locator));
        if (isProtected) o.put("protected", true);
        return LinkRecord.parse(o.toString());
    }

    private static Set<String> titles(ShareArchive.Contents c) {
        Set<String> out = new HashSet<>();
        for (ShareArchive.Record r : c.records) out.add(r.title);
        return out;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    static void run(Context parent, Consumer<String> mark) throws Exception {
        Context ctx = new ScannerChecks.IsolatedContext(parent);
        LibraryRepository repo = new LibraryRepository(ctx); repo.initialize();
        LinkStore links = repo.links;
        File staging = new File(ctx.getFilesDir(), "link-staging"); staging.mkdirs();
        File preview = new File(staging, "preview.pdf"); Files.copy(OcrQueueChecks.fixture(ctx, "links-preview.pdf").toPath(), preview.toPath());
        File shared = new File(ctx.getCacheDir(), "partner-folder-" + UUID.randomUUID()); shared.mkdirs();
        ShareArchive.FileSink sink = new ShareArchive.FileSink(shared);
        File out = new File(shared, ShareArchive.FILE_NAME);

        String idA = UUID.randomUUID().toString(), idB = UUID.randomUUID().toString(), idC = UUID.randomUUID().toString(), idD = UUID.randomUUID().toString(), idP = UUID.randomUUID().toString();
        LinkStore.Result a = links.publish(record(idA, "Alpha", "a".repeat(64), true, false), "alpha text about medicines", preview);
        LinkStore.Result b = links.publish(record(idB, "Bravo", "b".repeat(64), true, false), "bravo text", null);
        LinkStore.Result c = links.publish(record(idC, "Charlie unflagged", "c".repeat(64), true, false), "charlie text", null);
        LinkStore.Result d = links.publish(record(idD, "Delta not in Filen", "d".repeat(64), false, false), "delta text", null);
        LinkStore.Result p = links.publish(record(idP, "Papa protected", "e".repeat(64), true, true), "papa secret text", null);

        // 1. The switch persists, defaults to off, and is not the owner's `shared` flag.
        require(links.get(a.id).partnerShare == 0 && !links.get(a.id).shared, "a new link is not shared with the partner");
        require(links.setPartnerShare(a.id, 1) && links.setPartnerShare(b.id, 1) && links.setPartnerShare(d.id, 1), "the switch can be turned on");
        require(links.get(a.id).partnerShare == 1 && links.get(b.id).partnerShare == 1 && links.get(c.id).partnerShare == 0, "the switch persists per link");
        require(!links.get(a.id).shared, "the partner switch leaves the owner's shared flag alone");
        require(links.list("").size() == 5 && links.list("alpha").get(0).partnerShare == 1, "list and search rows carry the switch");
        require(!links.setPartnerShare(UUID.randomUUID().toString(), 1) && !links.setPartnerShare(a.id, 7), "an unknown link or a bad value is refused");
        mark.accept("partner switch persists");

        // 2. A protected link is not shared without the confirmation (mode 2); mode 2 on an unprotected link is just on.
        require(!links.setPartnerShare(p.id, 1) && links.get(p.id).partnerShare == 0, "a protected link cannot be switched on without confirmation");
        byte[] key = PartnerKeys.ensureSendKey(ctx, PartnerKeys.DEFAULT_PARTNER);
        require(key.length == 32 && Arrays.equals(key, PartnerKeys.sendKey(ctx, PartnerKeys.DEFAULT_PARTNER)), "the sending key is made once and kept");
        long now = 1_900_000_000_000L;
        ShareArchive.WriteResult w = PartnerShare.publish(links, sink, key, now, nextStamp());
        ShareArchive.Contents first = ShareArchive.read(out, key);
        require(titles(first).equals(new HashSet<>(Arrays.asList("Alpha", "Bravo"))), "only flagged links with a Filen place are in the archive");
        require(w.records == 2 && w.tombstones == 0, "write result counts match");
        require(!new File(shared, ShareArchive.FILE_NAME + ".tmp").exists(), "no temporary file is left behind");
        for (ShareArchive.Record r : first.records) if (r.title.equals("Alpha")) require(r.preview != null && r.text.contains("medicines") && r.filenUuid != null, "text, preview and locator travel with the record");
        require(links.setPartnerShare(c.id, 2) && links.get(c.id).partnerShare == 1, "confirmation on an unprotected link stores plain on");
        require(links.setPartnerShare(c.id, 0), "and it can be turned off");
        require(links.setPartnerShare(p.id, 2) && links.get(p.id).partnerShare == 2, "a protected link can be switched on with the confirmation");
        PartnerShare.publish(links, sink, key, now + 1, nextStamp());
        ShareArchive.Contents withProtected = ShareArchive.read(out, key);
        boolean papa = false; for (ShareArchive.Record r : withProtected.records) if (r.title.equals("Papa protected")) papa = r.isProtected;
        require(papa && withProtected.records.size() == 3, "a confirmed protected link is included and marked protected");
        // The unconfirmed state is also held back by the planner itself, not only by the setter.
        repo.database().execSQL("UPDATE link_records SET partner_share=1 WHERE id=?", new Object[]{p.id});
        PartnerShare.Plan held = PartnerShare.plan(links, now + 2, "x");
        require(held.excludedProtected == 1 && held.included.size() == 2, "a protected link at mode 1 is excluded by the planner");
        links.setPartnerShare(p.id, 2);
        mark.accept("partner protected links held back");

        // 3. Links without a Filen place are flagged but not written (the partner could not open them).
        PartnerShare.Plan plan = PartnerShare.plan(links, now + 3, "x");
        require(plan.notInFilen == 1, "a flagged link with no Filen place is counted, not written");

        // 4. Tombstones: unflag Bravo, trash Alpha; both become notices for 30 days, then they expire.
        links.setPartnerShare(b.id, 0);
        links.trash(a.id, "user");
        long t0 = now + 10 * DAY;
        PartnerShare.publish(links, sink, key, t0, nextStamp());
        ShareArchive.Contents gone = ShareArchive.read(out, key);
        Set<String> notices = new HashSet<>();
        for (ShareArchive.Tombstone t : gone.tombstones) notices.add(t.linkId);
        require(notices.equals(new HashSet<>(Arrays.asList(idA, idB))), "an unflagged and a trashed link become tombstones");
        require(!titles(gone).contains("Alpha") && !titles(gone).contains("Bravo") && titles(gone).contains("Papa protected"), "tombstoned links are not records");
        PartnerShare.publish(links, sink, key, t0 + 29 * DAY, nextStamp());
        require(ShareArchive.read(out, key).tombstones.size() == 2, "tombstones are still there after 29 days");
        PartnerShare.publish(links, sink, key, t0 + 31 * DAY, nextStamp());
        require(ShareArchive.read(out, key).tombstones.isEmpty(), "tombstones are gone after 30 days");
        // A link that is switched on again leaves its notice.
        links.setPartnerShare(b.id, 1);
        PartnerShare.publish(links, sink, key, t0 + 32 * DAY, nextStamp());
        ShareArchive.Contents again = ShareArchive.read(out, key);
        require(titles(again).contains("Bravo") && again.tombstones.isEmpty(), "sharing again removes the notice");
        // Deleting for good is also announced.
        links.trash(b.id, "user"); links.delete(b.id);
        PartnerShare.publish(links, sink, key, t0 + 33 * DAY, nextStamp());
        boolean found = false; for (ShareArchive.Tombstone t : ShareArchive.read(out, key).tombstones) if (t.linkId.equals(idB)) found = true;
        require(found, "a link deleted for good becomes a tombstone");
        mark.accept("partner tombstones");

        // 5. The digest follows what matters and nothing else.
        String one = PartnerShare.plan(links, t0 + 34 * DAY, "k1").digest;
        require(one.equals(PartnerShare.plan(links, t0 + 34 * DAY, "k1").digest), "the digest is stable");
        require(!one.equals(PartnerShare.plan(links, t0 + 34 * DAY, "k2").digest), "a new key changes the digest");
        links.rename(p.id, "Papa renamed");
        require(!one.equals(PartnerShare.plan(links, t0 + 34 * DAY, "k1").digest), "a rename changes the digest");

        // 6. Keys: rotation, wrong and old keys, independent receive slot, and nothing readable in a library export.
        byte[] received = new byte[32]; Arrays.fill(received, (byte) 7);
        PartnerKeys.setReceiveKey(ctx, PartnerKeys.DEFAULT_PARTNER, received);
        byte[] rotated = PartnerKeys.rotateSendKey(ctx, PartnerKeys.DEFAULT_PARTNER);
        require(!Arrays.equals(rotated, key) && Arrays.equals(rotated, PartnerKeys.sendKey(ctx, PartnerKeys.DEFAULT_PARTNER)), "rotation makes and keeps a different key");
        require(Arrays.equals(received, PartnerKeys.receiveKey(ctx, PartnerKeys.DEFAULT_PARTNER)), "rotating the sending key leaves the receiving slot alone");
        PartnerShare.publish(links, sink, rotated, t0 + 35 * DAY, nextStamp());
        require(ShareArchive.read(out, rotated).records.size() >= 1, "the new key opens the new archive");
        boolean oldRefused = false, randomRefused = false;
        try { ShareArchive.read(out, key); } catch (IOException expected) { oldRefused = true; }
        try { ShareArchive.read(out, new byte[32]); } catch (IOException expected) { randomRefused = true; }
        require(oldRefused && randomRefused, "the old key and a wrong key cannot read the archive after rotation");
        require(PartnerKeys.fingerprint(key).length() == 14 && !PartnerKeys.fingerprint(key).equals(PartnerKeys.fingerprint(rotated)), "fingerprints are short and differ per key");
        File sealed = new File(ctx.getFilesDir(), "partner_keys.bin");
        require(sealed.isFile() && !contains(Files.readAllBytes(sealed.toPath()), rotated) && !contains(Files.readAllBytes(sealed.toPath()), received), "the key file holds no plain key");

        LibraryArchive archive = repo.archive();
        for (int mode : new int[]{LibraryArchive.LINKS_INCLUDED, LibraryArchive.LINKS_NONE}) {
            ByteArrayOutputStream export = new ByteArrayOutputStream();
            try (LibraryArchive.Snapshot snapshot = archive.snapshot(new LibraryArchive.Progress(), false, mode)) { LibraryArchive.write(snapshot, export, new LibraryArchive.Progress()); }
            byte[] bytes = export.toByteArray();
            require(bytes.length > 0 && !contains(bytes, rotated) && !contains(bytes, key) && !contains(bytes, received), "no sharing key in a library export");
            require(!contains(bytes, PartnerKeys.hex(rotated).getBytes(StandardCharsets.US_ASCII)) && !contains(bytes, PartnerKeys.qrText(rotated).getBytes(StandardCharsets.US_ASCII)), "no sharing key as text in a library export");
        }
        File db = ctx.getDatabasePath("library.db");
        if (db.isFile()) require(!contains(Files.readAllBytes(db.toPath()), rotated), "the library database holds no sharing key");
        PartnerKeys.forget(ctx, PartnerKeys.DEFAULT_PARTNER);
        require(PartnerKeys.sendKey(ctx, PartnerKeys.DEFAULT_PARTNER) == null && PartnerKeys.receiveKey(ctx, PartnerKeys.DEFAULT_PARTNER) == null, "a forgotten partner has no keys");
        PartnerKeys.clear(ctx);
        require(!PartnerKeys.configured(ctx), "clearing removes the key file");
        mark.accept("partner keys and publish");
    }

    private static long stampCounter = 0;
    /** A fresh publish stamp: one epoch, a counter that goes up on every call (as PartnerKeys.nextStamp does on the phone). */
    static PartnerKeys.Stamp nextStamp() { return new PartnerKeys.Stamp("0123456789abcdef0123456789abcdef", ++stampCounter); }
}
