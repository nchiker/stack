package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Debug;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.util.function.Consumer;

/** Authored synthetic fixtures only. Runs in ScannerChecks' fresh child context. */
final class OcrChecks {
    static void run(Context context, Consumer<String> mark) throws Exception {
        File root = new File(context.getFilesDir(), "ocr/" + OcrModel.SHA256 + "/tessdata");
        require(!root.exists(), "Fresh isolated model storage");
        File installed = new File(OcrModel.install(context), "tessdata/eng.traineddata");
        require(OcrModel.valid(installed), "Model hash");
        try (FileOutputStream out = new FileOutputStream(installed)) { out.write(1); }
        File partial = new File(root, "eng.traineddata.tmp");
        try (FileOutputStream out = new FileOutputStream(partial)) { out.write(2); }
        OcrModel.install(context);
        require(OcrModel.valid(installed) && !partial.exists(), "Repair corrupt and interrupted install from asset");
        mark.accept("Bundled model fresh install and corrupt/partial repair, " + installed.length() + " bytes");
        Bitmap page = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888);
        page.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(page);
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(Color.BLACK); ink.setTextSize(48);
        canvas.drawText("Stack offline recognition", 90, 180, ink);
        canvas.drawText("The blue lantern shines brightly.", 90, 260, ink);
        canvas.drawText("Invoice total 123.45", 90, 340, ink);
        long began = SystemClock.elapsedRealtime();
        try (OcrEngine engine = new OcrEngine(context)) {
            String text = engine.recognize(page);
            require(text.contains("lantern") && text.contains("123.45"), "Real engine authored sentinel");
            mark.accept("Real engine " + engine.version() + ": synthetic lantern/123.45 found; "
                    + (SystemClock.elapsedRealtime() - began) + " ms; sampled PSS " + Debug.getPss() + " KiB");
            page.eraseColor(Color.WHITE);
            require(engine.recognize(page).trim().isEmpty(), "Blank succeeds with empty text");
            mark.accept("Real engine blank page succeeds");
        } finally { page.recycle(); }
        OcrQueueChecks.run(new ScannerChecks.IsolatedContext(context),mark);
        OcrWorkerChecks.run(new ScannerChecks.IsolatedContext(context),mark);
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
