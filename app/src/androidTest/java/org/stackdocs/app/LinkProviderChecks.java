package org.stackdocs.app;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Slice 2 (Stack side): the real LinkProvider, called through ContentResolver. The instrumentation runs in Stack's own
 * process, so the caller is Stack itself (allowed by the own-certificate rule); the allow/refuse decision for other apps
 * is checked as a pure function plus a real foreign-signed package ("android"). Synthetic data only.
 */
final class LinkProviderChecks {
    private static Context target;
    private static ContentResolver resolver;
    private static String authority;
    private static StackApplication app;

    private static Bundle call(String method, String argument) { return resolver.call(Uri.parse("content://" + authority), method, argument, null); }
    private static Uri stage(String token, String name) { return Uri.parse("content://" + authority + "/stage/" + token + "/" + name); }

    private static String record(String owner, String linkId, String title, String hash) throws Exception {
        return new JSONObject().put("contract", 1).put("owner", owner).put("link_id", linkId).put("kind", "doc").put("title", title)
            .put("mime", "application/x-amipro-document").put("modified", 1_800_000_000_000L).put("hash", hash)
            .put("locators", new JSONArray().put(new JSONObject().put("type", "owner").put("token", "private/1"))).toString();
    }

    private static String hash() { return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64); }

    private static void write(String token, String name, byte[] bytes) throws Exception {
        try (OutputStream out = resolver.openOutputStream(stage(token, name), "w")) { out.write(bytes); }
    }

    private static byte[] pdf(String body) { return ("%PDF-1.7\n" + body).getBytes(java.nio.charset.StandardCharsets.US_ASCII); }

    private static <T> T onIo(java.util.concurrent.Callable<T> work) throws Exception { return app.io.submit(work).get(); }

    private static String error(Bundle b) { return b == null ? "null" : b.getString("error", ""); }

    /** begin + write + commit; returns the commit result. */
    private static Bundle publish(String json, String text, byte[] preview) throws Exception {
        Bundle begun = call("begin", json);
        WorkflowRepositoryChecks.require(begun.getString("token") != null, "begin gives a token: " + error(begun) + " " + begun.getString("message"));
        String token = begun.getString("token");
        write(token, "text", text.getBytes("UTF-8"));
        if (preview != null) write(token, "preview", preview);
        return call("commit", token);
    }

    static void run(Context context, Consumer<String> mark) throws Exception {
        target = context; resolver = context.getContentResolver(); authority = context.getPackageName() + ".link";
        app = (StackApplication) context.getApplicationContext();
        String me = context.getPackageName();
        String own = LinkCallers.certificateOf(context, me);
        WorkflowRepositoryChecks.require(own != null && own.length() == 64, "Stack can read its own signing certificate");
        java.util.ArrayList<String> made = new java.util.ArrayList<>();
        try {
            Bundle contract = call("contract", null);
            WorkflowRepositoryChecks.require(contract.getInt("min") == 1 && contract.getInt("max") == 1, "the provider reports the contract range");

            // Happy path.
            String linkId = UUID.randomUUID().toString(), h1 = hash();
            Bundle done = publish(record(me, linkId, "Medicines and doctors", h1), "Dr Patel cardiology metformin 500 mg", pdf("one"));
            WorkflowRepositoryChecks.require(done.getString("id") != null && done.getBoolean("created") && done.getBoolean("content_changed"), "commit stores a new record: " + error(done) + " " + done.getString("message"));
            String id = done.getString("id"); made.add(id);
            WorkflowRepositoryChecks.require(!done.containsKey("duplicate_of"), "the provider never reveals other records");
            List<LinkStore.Link> found = onIo(() -> app.library.links.list("metformin"));
            WorkflowRepositoryChecks.require(found.size() == 1 && found.get(0).id.equals(id) && found.get(0).hasPreview, "the published record is searchable and has a preview");
            File stored = onIo(() -> app.library.links.preview(id));
            WorkflowRepositoryChecks.require(stored != null && new String(java.nio.file.Files.readAllBytes(stored.toPath()), "US-ASCII").startsWith("%PDF-"), "the stored preview is the copy Stack made");
            WorkflowRepositoryChecks.require(!new File(target.getFilesDir(), "link-staging/" + "x").exists() && stagedCount() == 0, "staging is cleaned after commit");

            // The review's main finding: a descriptor opened before commit must not reach the stored file afterwards.
            Bundle begun = call("begin", record(me, UUID.randomUUID().toString(), "Held descriptor", hash()));
            String token = begun.getString("token");
            ParcelFileDescriptor held = resolver.openFileDescriptor(stage(token, "preview"), "w");
            write(token, "text", "held descriptor text".getBytes("UTF-8"));
            try (FileOutputStream out = new FileOutputStream(held.getFileDescriptor())) { out.write(pdf("A")); out.flush();
                Bundle committed = call("commit", token);
                String heldId = committed.getString("id"); made.add(heldId);
                long length = onIo(() -> app.library.links.preview(heldId)).length();
                byte[] rest = new byte[1 << 20];
                out.write(rest); out.flush();
                WorkflowRepositoryChecks.require(onIo(() -> app.library.links.preview(heldId)).length() == length, "writing through an old descriptor after commit does not change the stored preview");
            }
            held.close();

            // Refusals: nothing may be stored for any of these.
            int before = onIo(() -> app.library.links.list("").size());
            WorkflowRepositoryChecks.require("invalid".equals(error(call("begin", record("app.other.app", UUID.randomUUID().toString(), "Impersonation", hash())))), "a record naming another app as owner is refused");
            WorkflowRepositoryChecks.require("invalid".equals(error(call("begin", "{\"contract\":1}"))), "a malformed record is refused");
            Bundle future = call("begin", new JSONObject(record(me, UUID.randomUUID().toString(), "Future", hash())).put("contract", 2).toString());
            WorkflowRepositoryChecks.require("unsupported_contract".equals(error(future)) && future.getInt("version") == 2, "a newer contract is reported with its number");
            WorkflowRepositoryChecks.require("invalid".equals(error(call("commit", UUID.randomUUID().toString()))) && "invalid".equals(error(call("commit", "../../x"))), "unknown and malformed tokens are refused");
            WorkflowRepositoryChecks.require("unknown_method".equals(error(call("drop_everything", null))), "an unknown method is refused");

            String t2 = call("begin", record(me, UUID.randomUUID().toString(), "Not a PDF", hash())).getString("token");
            write(t2, "text", "words".getBytes("UTF-8")); write(t2, "preview", "GIF89a not a pdf".getBytes("US-ASCII"));
            WorkflowRepositoryChecks.require("invalid".equals(error(call("commit", t2))), "a preview that is not a PDF is refused");
            String t3 = call("begin", record(me, UUID.randomUUID().toString(), "Bad text", hash())).getString("token");
            write(t3, "text", new byte[]{(byte) 0xff, (byte) 0xfe, 'a'});
            WorkflowRepositoryChecks.require("invalid".equals(error(call("commit", t3))), "text that is not valid UTF-8 is refused");
            String t4 = call("begin", record(me, UUID.randomUUID().toString(), "Huge text", hash())).getString("token");
            try (OutputStream out = resolver.openOutputStream(stage(t4, "text"), "w")) { byte[] chunk = new byte[1 << 20]; java.util.Arrays.fill(chunk, (byte) 'a'); for (int i = 0; i < 5; i++) out.write(chunk); }
            WorkflowRepositoryChecks.require("invalid".equals(error(call("commit", t4))), "text over the limit is refused without being read into memory");
            WorkflowRepositoryChecks.require(onIo(() -> app.library.links.list("").size()) == before, "none of the refused records was stored");

            // File access rules.
            String t5 = call("begin", record(me, UUID.randomUUID().toString(), "Access", hash())).getString("token");
            mustNotOpen(stage(t5, "text"), "r", "a staged file cannot be read back");
            mustNotOpen(stage(t5, "text"), "rw", "read-write is refused");
            mustNotOpen(stage(t5, "record.json"), "w", "only text and preview can be opened");
            mustNotOpen(stage(t5, "caller"), "w", "the caller marker cannot be opened");
            mustNotOpen(Uri.parse("content://" + authority + "/stage/" + t5 + "/../" + t5 + "/text"), "w", "a path with .. is refused");
            mustNotOpen(Uri.parse("content://" + authority + "/stage/" + t5 + "/text/extra"), "w", "extra path segments are refused");
            mustNotOpen(stage(UUID.randomUUID().toString(), "text"), "w", "an unknown token is refused");
            mustNotOpen(Uri.parse("content://" + authority + "/stage/%2e%2e/text"), "w", "an encoded .. token is refused");
            call("abort", t5);
            mustNotOpen(stage(t5, "text"), "w", "an aborted token is gone");

            // Staging quota: a ninth open begin is refused; abort frees a slot.
            java.util.ArrayList<String> open = new java.util.ArrayList<>();
            String refused = "";
            for (int i = 0; i < LinkProvider.MAX_STAGED + 1; i++) {
                Bundle b = call("begin", record(me, UUID.randomUUID().toString(), "Quota " + i, hash()));
                if (b.getString("token") != null) open.add(b.getString("token")); else refused = error(b);
            }
            WorkflowRepositoryChecks.require("invalid".equals(refused) && open.size() <= LinkProvider.MAX_STAGED, "the staging quota refuses extra begins");
            for (String t : open) call("abort", t);
            WorkflowRepositoryChecks.require(stagedCount() == 0, "abort removes the staging folders");

            // Who is allowed (pure decision, with a real foreign-signed package for the negative case).
            String[][] pins = LinkCallers.PINNED;
            WorkflowRepositoryChecks.require(LinkCallers.decide("anything", own, own, pins), "a package signed with Stack's own certificate is allowed");
            WorkflowRepositoryChecks.require(LinkCallers.decide(pins[0][0], pins[0][1], own, pins), "the pinned AmiPro package and certificate pair is allowed");
            WorkflowRepositoryChecks.require(!LinkCallers.decide("app.other.app", pins[0][1], own, pins), "the pinned certificate under another package name is refused");
            WorkflowRepositoryChecks.require(!LinkCallers.decide(pins[0][0], hash(), own, pins), "the pinned package name with another certificate is refused");
            WorkflowRepositoryChecks.require(!LinkCallers.decide(pins[0][0], null, own, pins) && !LinkCallers.decide(null, own, own, pins) && !LinkCallers.decide("x", own, null, pins), "missing package or certificate is refused");
            String foreign = LinkCallers.certificateOf(context, "android");
            WorkflowRepositoryChecks.require(foreign != null && !foreign.equals(own) && !LinkCallers.decide("android", foreign, own, pins), "a real package with a different certificate is refused");
            WorkflowRepositoryChecks.require(LinkCallers.certificateOf(context, "no.such.package.here") == null, "an unknown package has no certificate");
            for (String[] pin : pins) WorkflowRepositoryChecks.require(!"app.filenfiles".equals(pin[0]), "Filen Files is not in the owner-app pin list");
            WorkflowRepositoryChecks.require(LinkCallers.decide("app.filenfiles", LinkCallers.FILEN[0][1], own, LinkCallers.FILEN) && !LinkCallers.decide("app.filenfiles", LinkCallers.FILEN[0][1], own, pins), "Filen Files is accepted by the separate Filen pin only");
        } finally {
            for (String id : made) { try { onIo(() -> { app.library.links.delete(id); return null; }); } catch (Exception ignored) {} }
        }
        mark.accept("Link provider: publish through begin/write/commit; owner must be the caller; held descriptor cannot change a stored preview; refusals (impersonation, bad record, newer contract, bad tokens, non-PDF, bad UTF-8, oversize); file access rules and path tricks; staging quota; caller decision incl. a real foreign-signed package");
    }

    private static int stagedCount() {
        File[] dirs = new File(target.getFilesDir(), "link-staging").listFiles();
        return dirs == null ? 0 : dirs.length;
    }

    private static void mustNotOpen(Uri uri, String mode, String what) throws Exception {
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, mode)) { throw new AssertionError("Opened: " + what); }
        catch (FileNotFoundException | SecurityException expected) { /* refused */ }
        catch (IllegalArgumentException | UnsupportedOperationException expected) { /* refused */ }
    }
}
