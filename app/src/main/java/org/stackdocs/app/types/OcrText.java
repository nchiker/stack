package org.stackdocs.app.types;

import android.graphics.Bitmap;
import android.os.CancellationSignal;
import java.io.IOException;
import org.stackdocs.app.OcrEngine;

/** Shared OCR step of the picture and PDF handlers. OcrEngine is created per call and released after it. */
final class OcrText {
    private OcrText() {}

    static final int RENDER_PX = 2400;

    /** The native reader is given the time that is left minus this, so a heavy PDF answers "use OCR" before the hard limit kills the process. */
    static final long NATIVE_MARGIN_MS = 10_000;

    /** `started`: when the work on this file began (before the page count was asked), so that time is taken off what the native reader may use. */
    static Extracted recognize(Source in, FileType type, int pages, Limits l, CancellationSignal c, PdfText layer, long started) throws IOException {
        if (in.context() == null) return Extracted.unsupported("Text reading needs the app.");
        long deadline = l.maxMillis > 0 ? started + l.maxMillis : Long.MAX_VALUE;
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        String[] layers = textLayers(in, pages, l, layer, deadline);
        OcrEngine engine = null;
        long chars = 0;
        String note = null;
        try {
            for (int page = 0; page < pages; page++) {
                if (c != null) c.throwIfCanceled();
                if (l.maxChars > 0 && chars >= l.maxChars) { note = "Only the first part of this file was read."; break; }
                if (android.os.SystemClock.elapsedRealtime() > deadline) return Extracted.tooLarge("Reading this file took too long.");
                String embedded = layers != null ? layers[page] : null;
                if (embedded != null) { out.add(embedded); chars += embedded.length(); continue; }
                Preview preview = type.preview(in.page(page), RENDER_PX);
                if (preview == null) return Extracted.unreadable("This file could not be displayed.");
                Bitmap bitmap = preview.bitmap;
                try {
                    if (engine == null) engine = new OcrEngine(in.context());
                    String recognized = engine.recognize(bitmap);
                    out.add(recognized); chars += recognized.length();
                } finally { bitmap.recycle(); }
            }
        } finally { if (engine != null) engine.close(); }
        return Extracted.text(out, null, note);
    }

    /** The text layer of every page in one pass, or null (no reader, not readable, or a page count that differs from the renderer's: OCR everything). */
    static String[] textLayers(Source in, int pages, Limits l, PdfText layer, long deadline) {
        if (layer == null) return null;
        long millis = deadline == Long.MAX_VALUE ? 120_000L : deadline - android.os.SystemClock.elapsedRealtime() - NATIVE_MARGIN_MS;
        if (millis < 1000) return null;   // too little time left for the native reader: every page goes to OCR (or, in a chunked read, is simply "no text layer")
        try (android.os.ParcelFileDescriptor fd = in.descriptor()) {
            String[] all = layer.pageTexts(fd, pages, l.maxChars, (int) millis);
            return all != null && all.length == pages ? all : null;
        } catch (IOException | RuntimeException e) { return null; }
    }
}
