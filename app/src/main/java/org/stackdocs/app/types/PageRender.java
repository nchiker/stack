package org.stackdocs.app.types;

import android.graphics.Bitmap;
import java.io.IOException;

/**
 * One page of a PDF or one picture, drawn for the viewer. The extract process does this (ExtractService.MSG_RENDER): the viewer never decodes a partner's
 * file itself. The answer is capped: at most {@link #MAX_PIXELS} pixels and {@link #MAX_SIDE} on a side, so a hostile page size cannot ask for memory.
 */
public final class PageRender {
    private PageRender() {}

    public static final int MAX_SIDE = 3400, MAX_PIXELS = 2400 * 3400, DEFAULT_EDGE = 2400;

    public static final class Result {
        public final Bitmap bitmap; public final int page, pages;
        Result(Bitmap bitmap, int page, int pages) { this.bitmap = bitmap; this.page = page; this.pages = pages; }
    }

    /** True for the types the viewer draws through this class. */
    public static boolean drawable(FileType type) { return type != null && ("pdf".equals(type.id()) || type instanceof ImageType); }   // every picture type, the Stack scan file too

    /** `page` is clamped to the file's pages. IOException when the file cannot be drawn. */
    public static Result render(Source in, FileType type, int page, int maxEdge) throws IOException {
        if (!drawable(type)) throw new IOException("Not a drawable type.");
        int edge = Math.max(16, Math.min(maxEdge, DEFAULT_EDGE));
        int pages = 1;
        if (type instanceof PdfType) {
            pages = ((PdfType) type).pageCount(in);
            if (pages < 1) throw new IOException("This PDF has no pages.");
        }
        int shown = Math.max(0, Math.min(page, pages - 1));
        Preview preview = type.preview(in.page(shown), edge);
        if (preview == null) throw new IOException("This file could not be displayed.");
        return new Result(capped(preview.bitmap), shown, pages);
    }

    /** The bitmap itself when it is inside the caps, else a smaller copy (the first is then recycled). */
    static Bitmap capped(Bitmap bitmap) {
        long pixels = (long) bitmap.getWidth() * bitmap.getHeight();
        if (bitmap.getWidth() <= MAX_SIDE && bitmap.getHeight() <= MAX_SIDE && pixels <= MAX_PIXELS) return bitmap;
        double scale = Math.min(Math.min((double) MAX_SIDE / bitmap.getWidth(), (double) MAX_SIDE / bitmap.getHeight()), Math.sqrt((double) MAX_PIXELS / pixels));
        Bitmap smaller = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int) (bitmap.getWidth() * scale)), Math.max(1, (int) (bitmap.getHeight() * scale)), true);
        if (smaller != bitmap) bitmap.recycle();
        return smaller;
    }
}
