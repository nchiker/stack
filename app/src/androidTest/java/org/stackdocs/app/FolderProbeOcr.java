package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import java.io.File;
import java.util.function.Consumer;

/** Test code only (folder sharing option A measurement): OCR time per page with Stack's own engine on files pushed to the app's files dir "ocr". */
final class FolderProbeOcr {
    private FolderProbeOcr() {}

    static void run(Context context, Consumer<String> mark) throws Exception {
        File dir = new File(context.getFilesDir(), "ocr");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) throw new AssertionError("no files in " + dir);
        java.util.Arrays.sort(files);
        try (OcrEngine engine = new OcrEngine(context)) {
            mark.accept("engine " + engine.version());
            for (File f : files) {
                String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                String mime = name.endsWith(".pdf") ? "application/pdf" : "image/*";
                int pages = f.getName().startsWith("all-") ? 3 : 1;
                for (int pg = 0; pg < pages; pg++) for (int pass = 0; pass < (pages > 1 ? 1 : 2); pass++) {
                    long t0 = SystemClock.elapsedRealtime();
                    Bitmap bmp = PageRenderer.preview(f, mime, pg, 2400);
                    long t1 = SystemClock.elapsedRealtime();
                    String text = engine.recognize(bmp);
                    long t2 = SystemClock.elapsedRealtime();
                    mark.accept("ocr " + f.getName() + " page" + pg + " pass" + pass + " bytes=" + f.length() + " bmp=" + bmp.getWidth() + "x" + bmp.getHeight()
                        + " render_ms=" + (t1 - t0) + " ocr_ms=" + (t2 - t1) + " chars=" + text.trim().length()
                        + " words=" + text.trim().split("\\s+").length + " head=" + text.trim().replace('\n', ' ').substring(0, Math.min(60, text.trim().length())));
                    bmp.recycle();
                }
            }
        }
    }
}
