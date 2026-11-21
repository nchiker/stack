package org.stackdocs.app;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Slice 2c (Stack side): preview drawing, display text, the owner launcher, render markers. Synthetic data only. */
final class LinkUiChecks {
    private static byte[] pdf(int pages, int width, int height, String label) throws IOException {
        PdfDocument document = new PdfDocument();
        for (int i = 0; i < pages; i++) {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(width, height, i + 1).create());
            Paint paint = new Paint(); paint.setTextSize(24);
            page.getCanvas().drawText(label + " page " + (i + 1), 40, 60, paint);
            document.finishPage(page);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.writeTo(out); document.close();
        return out.toByteArray();
    }

    private static File file(Context context, String name, byte[] bytes) throws IOException {
        File f = new File(context.getCacheDir(), name);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { out.write(bytes); }
        return f;
    }

    static void run(Context context, Context isolated, Consumer<String> mark) throws Exception {
        // Display text from another app: direction tricks and controls removed, newlines kept, length capped.
        String shown = LinkPreview.displayText("Dr‮evil\u0085 Patel\nline2\u0000\tend⁦x", 1000);
        WorkflowRepositoryChecks.require(shown.equals("Drevil Patel\nline2\tendx"), "display text drops bidi and control characters and keeps newlines: " + shown.replace("\n", "|"));
        WorkflowRepositoryChecks.require(LinkPreview.displayText("abcdef", 3).equals("abc"), "display text is capped");

        // Preview drawing.
        List<Bitmap> two = LinkPreview.render(file(context, "two.pdf", pdf(2, 595, 842, "Medicines")), 300);
        WorkflowRepositoryChecks.require(two.size() == 2 && two.get(0).getWidth() == 300 && Math.abs(two.get(0).getHeight() - 424) <= 2, "a two-page preview draws two pages at the requested width");
        List<Bitmap> many = LinkPreview.render(file(context, "many.pdf", pdf(20, 200, 300, "Long")), 200);
        WorkflowRepositoryChecks.require(many.size() == LinkPreview.MAX_PAGES, "pages are capped at " + LinkPreview.MAX_PAGES);
        List<Bitmap> tall = LinkPreview.render(file(context, "tall.pdf", pdf(1, 595, 2975, "Tall")), 1400);
        WorkflowRepositoryChecks.require((long) tall.get(0).getWidth() * tall.get(0).getHeight() <= LinkPreview.MAX_PIXELS, "a very tall page is scaled under the pixel cap");
        for (byte[] junk : new byte[][]{"%PDF-1.7\nthis is not really a pdf".getBytes("US-ASCII"), new byte[0], "%PDF-".getBytes("US-ASCII")}) {
            try { LinkPreview.render(file(context, "junk.pdf", junk), 300); throw new AssertionError("A corrupt preview was drawn"); }
            catch (IOException expected) { /* handled, not a crash */ }
        }

        // Render markers survive a stop; new bytes clear them.
        android.database.sqlite.SQLiteDatabase[] holder = new android.database.sqlite.SQLiteDatabase[1];
        LibraryDatabase helper = new LibraryDatabase(isolated);
        try {
            holder[0] = helper.getWritableDatabase();
            LinkStore store = new LinkStore(() -> holder[0], new File(isolated.getFilesDir(), "links-ui"));
            File staging = new File(isolated.getFilesDir(), "link-ui-staging"); staging.mkdirs();
            String linkId = UUID.randomUUID().toString();
            LinkRecord record = LinkRecord.parse(new JSONObject().put("contract", 1).put("owner", "app.stub.owner").put("link_id", linkId).put("kind", "doc").put("title", "Marker")
                .put("mime", "application/x-amipro-document").put("modified", 1L).put("hash", "6".repeat(64))
                .put("locators", new JSONArray().put(new JSONObject().put("type", "owner").put("token", "t"))).toString());
            String id = store.publish(record, "words", file(isolated, "m1.pdf", pdf(1, 200, 200, "m"))).id;
            WorkflowRepositoryChecks.require(!store.previewFailed(id), "a fresh preview has no failure marker");
            store.previewAttempt(id);
            WorkflowRepositoryChecks.require(store.previewFailed(id), "an attempt leaves a marker until it succeeds (a crash keeps it)");
            store.previewSucceeded(id);
            WorkflowRepositoryChecks.require(!store.previewFailed(id), "success removes the marker");
            store.previewAttempt(id);
            LinkRecord changed = LinkRecord.parse(new JSONObject().put("contract", 1).put("owner", "app.stub.owner").put("link_id", linkId).put("kind", "doc").put("title", "Marker")
                .put("mime", "application/x-amipro-document").put("modified", 2L).put("hash", "7".repeat(64))
                .put("locators", new JSONArray().put(new JSONObject().put("type", "owner").put("token", "t"))).toString());
            store.publish(changed, "words", file(isolated, "m2.pdf", pdf(1, 200, 200, "n")));
            WorkflowRepositoryChecks.require(!store.previewFailed(id), "a new preview clears an old failure");
            store.previewAttempt(id); store.delete(id);
            WorkflowRepositoryChecks.require(!store.previewFailed(id), "delete clears the marker");
        } finally { helper.close(); }

        // The owner launcher: only an explicit, grant-free intent for the owner's own component is accepted.
        Intent good = new Intent().setComponent(new ComponentName("app.amipro.editor", "app.amipro.editor.MainActivity"));
        WorkflowRepositoryChecks.require(LinkOpener.valid(good, "app.amipro.editor"), "an explicit intent to the owner's component is accepted");
        WorkflowRepositoryChecks.require(!LinkOpener.valid(new Intent().setComponent(new ComponentName("com.evil", "com.evil.X")), "app.amipro.editor"), "an intent to another app is refused");
        WorkflowRepositoryChecks.require(!LinkOpener.valid(new Intent(Intent.ACTION_VIEW), "app.amipro.editor") && !LinkOpener.valid(null, "app.amipro.editor"), "an implicit or missing intent is refused");
        WorkflowRepositoryChecks.require(!LinkOpener.valid(new Intent(good).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "app.amipro.editor"), "an intent that grants URI access is refused");
        WorkflowRepositoryChecks.require(!LinkOpener.valid(new Intent(good).setPackage("com.evil"), "app.amipro.editor"), "an intent with a different package is refused");
        Intent clipped = new Intent(good); clipped.setClipData(android.content.ClipData.newPlainText("x", "y"));
        WorkflowRepositoryChecks.require(!LinkOpener.valid(clipped, "app.amipro.editor"), "an intent with clip data is refused");
        LinkStore.Link missing = new LinkStore.Link(UUID.randomUUID().toString(), "no.such.owner", UUID.randomUUID().toString(), "doc", "Gone", "application/x-amipro-document", "8".repeat(64), "[]", "live", "", 1L, 1L, false, false, false);
        try { LinkOpener.request(context, missing, "edit"); throw new AssertionError("Opening through a missing owner must fail"); }
        catch (LinkOpener.Failure failure) { WorkflowRepositoryChecks.require(failure.getMessage().contains("isn't installed"), "a missing owner app gives a plain message: " + failure.getMessage()); }
        WorkflowRepositoryChecks.require(LinkOpener.ownerName("app.amipro.editor").equals("Ami Pro") && LinkOpener.ownerName("org.example.notes").equals("notes"), "owner names are readable");

        mark.accept("Link screens: preview draws and is capped (pages, pixels), corrupt previews are refused without a crash, failure markers persist until new bytes, hostile text is cleaned for display, owner launcher accepts only an explicit grant-free intent for the owner, missing owner gives a plain message");
    }

    /** Leaves two sample linked documents in this test install's library so the real screens can be looked at. */
    static void seed(Context context) throws Exception {
        String me = context.getPackageName();
        publish(context, me, "Medicines and doctors", "Current medicines\nMetformin 500 mg twice daily, Dr Patel (cardiology)\nLevetiracetam 250 mg, Dr Okafor (neurology)\nRefill due 12 December\nPharmacy: Main Street, 555 0100", pdf(2, 595, 842, "Medicines and doctors"));
        publish(context, me, "Insurance contacts", "Claims line 555 0142\nPolicy number on the card, renewal in March", null);
    }

    static void unseed(Context context, StackApplication app) throws Exception {
        for (LinkStore.Link link : app.io.submit(() -> app.library.links.list("")).get()) if (link.owner.equals(context.getPackageName())) app.io.submit(() -> { app.library.links.delete(link.id); return null; }).get();
    }

    private static void publish(Context context, String owner, String title, String text, byte[] preview) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri base = Uri.parse("content://" + context.getPackageName() + ".link");
        String json = new JSONObject().put("contract", 1).put("owner", owner).put("link_id", UUID.randomUUID().toString()).put("kind", "doc").put("title", title)
            .put("mime", "application/x-amipro-document").put("modified", System.currentTimeMillis()).put("hash", (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64))
            .put("locators", new JSONArray().put(new JSONObject().put("type", "owner").put("token", "seed"))).toString();
        String token = resolver.call(base, "begin", json, null).getString("token");
        try (OutputStream out = resolver.openOutputStream(Uri.parse(base + "/stage/" + token + "/text"), "w")) { out.write(text.getBytes("UTF-8")); }
        if (preview != null) try (OutputStream out = resolver.openOutputStream(Uri.parse(base + "/stage/" + token + "/preview"), "w")) { out.write(preview); }
        Bundle done = resolver.call(base, "commit", token, null);
        if (done.getString("id") == null) throw new AssertionError("seed failed: " + done.getString("message"));
    }
}
