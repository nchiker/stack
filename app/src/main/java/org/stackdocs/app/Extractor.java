package org.stackdocs.app;

import android.os.CancellationSignal;
import java.io.File;
import org.stackdocs.app.types.Limits;

/** Reads the text of one downloaded file, outside the main process. Blocking; one file at a time. {@link ExtractClient} is the real one; a check can replace it. */
interface Extractor {
    ExtractClient.Outcome extract(File file, String typeId, Limits limits, CancellationSignal signal);
    /** One chunk of a PDF: mode {@link ExtractService#MODE_LAYERS} or {@link ExtractService#MODE_OCR} (with `pages`), under `limits.maxMillis` for this call alone. */
    default ExtractClient.Outcome extractPages(File file, String typeId, String mode, int[] pages, Limits limits, CancellationSignal signal) {
        return new ExtractClient.Outcome(ExtractClient.Outcome.Kind.DONE, org.stackdocs.app.types.Extracted.unsupported("Not read in chunks here."));
    }
    /**
     * Before the first file of a run: starts the reader so the registry's versions are the real ones (a fresh install only knows defaults until the
     * extract process has reported them). False when it cannot start. A file finished before this would carry a fingerprint that changes a moment later
     * and be read a second time.
     */
    default boolean prepare() { return true; }
    /** The run is over: let the extract process go (it is started again when needed). */
    default void release() {}
}
