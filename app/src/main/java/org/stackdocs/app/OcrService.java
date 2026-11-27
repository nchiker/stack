package org.stackdocs.app;

/**
 * Tesseract, in its own process (`:ocr`) and NOT isolated, because an isolated process cannot start it (see {@link ExtractService}: Tesseract needs a
 * directory with tessdata/eng.traineddata, and tesseract4android's Java API starts only from such a directory; see docs/FOLDER_SHARING_DESIGN.md
 * section 7). It receives only pixels drawn by the isolated process, never a partner's file, and like the others it can be killed by the main process
 * on a timeout. It does one thing: a ping, and OCR of drawn pixels. Everything else it is sent is refused ({@link #accepts}), so a compromised caller
 * cannot use it to read a file or run a reader. {@link StackApplication} does nothing in it.
 */
public final class OcrService extends ExtractService {
    @Override boolean accepts(int what, android.os.Bundle data) {
        return what == MSG_EXTRACT && OCR_PIXELS.equals(data.getString("type", "")) && data.getString("mode", "").isEmpty();
    }
}
