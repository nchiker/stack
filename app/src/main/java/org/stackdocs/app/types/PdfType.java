package org.stackdocs.app.types;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** PDF: rendered by PdfRenderer. Text is read per page from the text layer when a PdfText reader is installed, otherwise by OCR. */
final class PdfType implements FileType {
    /** The same size the native reader accepts (MAX_INPUT, 160 MB): a larger PDF is found by its name only. */
    static final long MAX_BYTES = 160L * 1024 * 1024;
    private final PdfText layer;

    PdfType(PdfText layer) { this.layer = layer; }

    @Override public String id() { return "pdf"; }
    /** The type's own version plus the text reader's: a reader change, or its arrival on this device, queues the files again. */
    @Override public int version() { return 2 * 1000 + (layer != null ? layer.version() : 0); }
    @Override public Set<String> extensions() { return Collections.singleton("pdf"); }
    @Override public Set<String> mimeTypes() { return Collections.singleton("application/pdf"); }
    @Override public Limits limits() { return new Limits(MAX_BYTES, 1000, 60_000, 1_000_000); }
    @Override public Opener opener() { return Opener.STACK_VIEWER; }
    @Override public boolean storedAsDocument() { return true; }

    @Override public boolean matches(byte[] head, String name, String mime) {
        return ImageType.starts(head, 0, '%', 'P', 'D', 'F', '-');
    }

    /** The page count; IOException with the library's wording when the PDF cannot be opened. */
    int pageCount(Source in) throws IOException {
        try (ParcelFileDescriptor descriptor = in.descriptor(); PdfRenderer renderer = new PdfRenderer(descriptor)) {
            return renderer.getPageCount();
        } catch (RuntimeException error) {
            throw new IOException("This PDF could not be opened. It may be damaged or password-protected.", error);
        }
    }

    @Override public Extracted extract(Source in, Limits l, CancellationSignal c) throws IOException {
        final long started = android.os.SystemClock.elapsedRealtime();
        if (in.length() > l.maxBytes) return Extracted.tooLarge("Too large to read");
        int pages;
        try { pages = pageCount(in); }
        catch (IOException e) { return Extracted.unreadable(e.getMessage()); }
        if (pages < 1) return Extracted.unreadable("This PDF has no pages.");
        if (pages > l.maxPages) return Extracted.tooLarge("This PDF has more than " + l.maxPages + " pages.");
        try { return OcrText.recognize(in, this, pages, l, c, layer, started); }
        catch (IOException e) { return Extracted.unreadable(e.getMessage()); }
    }

    /**
     * Chunked reading, step 1: the text layer of every page (fast). Entry i is page i's text, or null when the page needs OCR. TEXT with the page
     * count as the list size, or the same answers as {@link #extract} for a file that cannot be read.
     */
    Extracted layers(Source in, Limits l) throws IOException {
        final long started = android.os.SystemClock.elapsedRealtime();
        if (in.length() > l.maxBytes) return Extracted.tooLarge("Too large to read");
        int pages;
        try { pages = pageCount(in); }
        catch (IOException e) { return Extracted.unreadable(e.getMessage()); }
        if (pages < 1) return Extracted.unreadable("This PDF has no pages.");
        if (pages > l.maxPages) return Extracted.tooLarge("This PDF has more than " + l.maxPages + " pages.");
        String[] layers = layer == null ? null : OcrText.textLayers(in, pages, l, layer, l.maxMillis > 0 ? started + l.maxMillis : Long.MAX_VALUE);
        java.util.ArrayList<String> out = new java.util.ArrayList<>(pages);
        for (int i = 0; i < pages; i++) out.add(layers == null ? null : layers[i]);
        return Extracted.text(out, null, null);
    }

    /** Chunked reading, step 2: OCR of the given pages only. Entry i is the text of `pages[i]` (empty when OCR found none). */
    Extracted ocr(Source in, int[] pages, CancellationSignal c) throws IOException {
        if (in.context() == null) return Extracted.unsupported("Text reading needs the app.");
        java.util.ArrayList<String> out = new java.util.ArrayList<>(pages.length);
        org.stackdocs.app.OcrEngine engine = null;
        try {
            for (int page : pages) {
                if (c != null) c.throwIfCanceled();
                Preview preview = preview(in.page(page), OcrText.RENDER_PX);
                if (preview == null) return Extracted.unreadable("This file could not be displayed.");
                android.graphics.Bitmap bitmap = preview.bitmap;
                try {
                    if (engine == null) engine = new org.stackdocs.app.OcrEngine(in.context());
                    out.add(engine.recognize(bitmap));
                } finally { bitmap.recycle(); }
            }
        } finally { if (engine != null) engine.close(); }
        return Extracted.text(out, null, null);
    }

    @Override public Preview preview(Source in, int maxPx) throws IOException {
        try (ParcelFileDescriptor descriptor = in.descriptor();
             PdfRenderer renderer = new PdfRenderer(descriptor);
             PdfRenderer.Page page = renderer.openPage(in.page())) {
            float scale = Math.min((float) maxPx / page.getWidth(), (float) maxPx / page.getHeight());
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                    Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return new Preview(bitmap);
        } catch (RuntimeException error) {
            throw new IOException("This PDF page could not be displayed.", error);
        }
    }
}
