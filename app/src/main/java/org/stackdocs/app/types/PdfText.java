package org.stackdocs.app.types;

import android.os.ParcelFileDescriptor;

/** Reads the embedded text layer of PDF pages. A null text means the page has no usable text layer (the caller then uses OCR). */
public interface PdfText {
    /** One page. Readers that parse the whole file per call should be called through {@link #pageTexts} instead. */
    String pageText(ParcelFileDescriptor pdf, int page);

    /**
     * Every page in one pass: entry i is page i's text or null. Null overall: the file could not be read as a PDF here
     * (damaged, protected, too large, over the time budget, or no reader on this device); every page then needs OCR.
     */
    default String[] pageTexts(ParcelFileDescriptor pdf, int maxPages, int maxChars, int budgetMillis) { return null; }

    /** Changes when the reader's output changes, so indexed PDFs are read again. */
    default int version() { return 0; }
}
