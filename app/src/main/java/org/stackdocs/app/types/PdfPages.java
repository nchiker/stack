package org.stackdocs.app.types;

import android.os.CancellationSignal;
import java.io.IOException;

/**
 * Reading a PDF in page chunks (the extract process runs these, see ExtractService): first the text layer of every page, then OCR of a few
 * pages at a time, so no single call needs the time of a whole scanned file.
 */
public final class PdfPages {
    private PdfPages() {}

    /** The text layer per page: TEXT with one entry per page (null = needs OCR), or why the file cannot be read. */
    public static Extracted layers(Source in, Limits limits) throws IOException {
        FileType type = FileTypes.byId("pdf");
        if (!(type instanceof PdfType)) return Extracted.unsupported("Not a supported type of file.");
        return ((PdfType) type).layers(in, limits);
    }

    /** OCR of `pages` (0-based) only: TEXT with one entry per requested page. */
    public static Extracted ocr(Source in, int[] pages, CancellationSignal signal) throws IOException {
        FileType type = FileTypes.byId("pdf");
        if (!(type instanceof PdfType)) return Extracted.unsupported("Not a supported type of file.");
        return ((PdfType) type).ocr(in, pages, signal);
    }
}
