package org.stackdocs.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a cached preview PDF. The file came from another app, so it is treated as hostile: pages and pixels are capped,
 * every failure (including password-protected or corrupt files) is caught, and the caller keeps a marker (LinkStore) so a
 * file that failed or crashed is never opened again.
 */
final class LinkPreview {
    static final int MAX_PAGES = 12, MAX_WIDTH = 1400, MAX_PIXELS = 4_000_000;

    private LinkPreview() {}

    /** Up to MAX_PAGES bitmaps at `widthPx` (at most MAX_WIDTH). Throws IOException for anything unusable. */
    static List<Bitmap> render(File pdf, int widthPx) throws IOException {
        List<Bitmap> pages = new ArrayList<>();
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer renderer = new PdfRenderer(fd)) {
            int count = Math.min(renderer.getPageCount(), MAX_PAGES);
            if (count <= 0) throw new IOException("The preview has no pages.");
            for (int i = 0; i < count; i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    if (page.getWidth() <= 0 || page.getHeight() <= 0) throw new IOException("The preview has an empty page.");
                    int width = Math.max(1, Math.min(widthPx, MAX_WIDTH));
                    long height = Math.max(1L, Math.round((double) width * page.getHeight() / page.getWidth()));
                    while ((long) width * height > MAX_PIXELS && width > 64) { width = width * 9 / 10; height = Math.max(1L, Math.round((double) width * page.getHeight() / page.getWidth())); }
                    if ((long) width * height > MAX_PIXELS) throw new IOException("The preview page is too large.");
                    Bitmap bitmap = Bitmap.createBitmap(width, (int) height, Bitmap.Config.ARGB_8888);
                    new Canvas(bitmap).drawColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    pages.add(bitmap);
                }
            }
            return pages;
        } catch (SecurityException | IllegalStateException | IllegalArgumentException | OutOfMemoryError error) {
            for (Bitmap bitmap : pages) bitmap.recycle();
            throw new IOException("The preview could not be drawn.");
        } catch (IOException error) {
            for (Bitmap bitmap : pages) bitmap.recycle();
            throw error;
        }
    }

    /** Text from another app for display: direction-changing and control characters removed, and capped. */
    static String displayText(String text, int max) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), max));
        for (int i = 0; i < text.length() && out.length() < max; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t') { out.append(c); continue; }
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069) || c == 0x200e || c == 0x200f || c == 0x061c) continue;
            out.append(c);
        }
        return out.toString();
    }
}
