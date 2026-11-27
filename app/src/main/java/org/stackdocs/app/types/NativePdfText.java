package org.stackdocs.app.types;

import android.os.ParcelFileDescriptor;

/** The PDF text layer reader in libstacknative (arm64-v8a). Panics, hostile files and time are handled natively; none of it throws here. */
public final class NativePdfText implements PdfText {
    private NativePdfText() {}

    /** The reader, or null on a device where the native library is not present (other ABIs): PDFs are then read by OCR only. */
    public static PdfText ifAvailable() { return NativeText.supported() ? new NativePdfText() : null; }

    @Override public String pageText(ParcelFileDescriptor pdf, int page) {
        String[] all = pageTexts(pdf, 0, 0, 60_000);
        return all != null && page >= 0 && page < all.length ? all[page] : null;
    }

    @Override public String[] pageTexts(ParcelFileDescriptor pdf, int maxPages, int maxChars, int budgetMillis) {
        if (pdf == null) return null;
        if (!NativeText.available()) return null;
        try { return NativeText.pdfPageTexts(pdf.getFd(), maxPages, maxChars, budgetMillis); }
        catch (UnsatisfiedLinkError | RuntimeException e) { return null; }
    }

    @Override public int version() { return NativeText.supported() ? NativeText.pdfReaderVersion() : 0; }
}
