package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.IOException;

/** Confined to one OCR worker; all page allocations are released after each recognition. */
final class OcrEngine implements AutoCloseable {
    private TessBaseAPI engine;

    OcrEngine(Context context) throws IOException {
        String path = OcrModel.install(context).getAbsolutePath();
        TessBaseAPI candidate = new TessBaseAPI();
        try {
            if (!candidate.init(path, "eng", TessBaseAPI.OEM_LSTM_ONLY)) throw new IOException("OCR engine initialization failed");
            // Local thresholding handles uneven paper without changing saved scanner presets.
            if (!candidate.setVariable("thresholding_method","2")) throw new IOException("OCR thresholding unavailable");
            candidate.setDebug(false);
            engine = candidate;
        } finally {
            if (engine == null) candidate.recycle();
        }
    }

    String recognize(Bitmap image) throws IOException {
        if (engine == null) throw new IOException("OCR engine is closed");
        try {
            engine.setImage(image);
            String text = engine.getUTF8Text();
            if (text == null) throw new IOException("OCR did not return a result");
            return text;
        } finally { engine.clear(); }
    }

    String version() { return engine.getVersion() + "/" + engine.getLibraryFlavor(); }
    @Override public void close() {
        if (engine != null) { engine.recycle(); engine = null; }
    }
}
