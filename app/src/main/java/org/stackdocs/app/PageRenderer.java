package org.stackdocs.app;

import android.graphics.Bitmap;
import org.stackdocs.app.types.FileType;
import org.stackdocs.app.types.FileTypes;
import org.stackdocs.app.types.Preview;
import org.stackdocs.app.types.Source;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import androidx.exifinterface.media.ExifInterface;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

final class PageRenderer {
    private PageRenderer() {}

    interface BitmapSource { Bitmap load(PageState page) throws IOException; }

    static ColorMatrixColorFilter enhancementFilter(String preset) {
        if (preset.equals("original") || preset.equals("black_white")) return null;
        float contrast = preset.equals("bright") ? 1.18f : 1.10f;
        float offset = preset.equals("bright") ? 18 : 5;
        return new ColorMatrixColorFilter(new ColorMatrix(new float[]{contrast, 0, 0, 0, offset,
                0, contrast, 0, 0, offset, 0, 0, contrast, 0, offset, 0, 0, 0, 1, 0}));
    }

    static Bitmap render(File source, String mime, PageState state, int maxEdge) throws IOException {
        Bitmap bitmap = preview(source, mime, state.sourcePage, maxEdge);
        try {
            if (state.crop != null && !java.util.Arrays.equals(state.crop, PageGeometry.full())) {
                float[] probe = PageGeometry.checked(state.crop);
                for (int i = 0; i < 4; i++) {
                    probe[i * 2] *= bitmap.getWidth() - 1;
                    probe[i * 2 + 1] *= bitmap.getHeight() - 1;
                }
                float pageEdge = Math.max(Math.max(distance(probe, 0, 1), distance(probe, 3, 2)),
                        Math.max(distance(probe, 0, 3), distance(probe, 1, 2)));
                int decodeEdge = Math.min(Math.max(maxEdge, 4096), (int) Math.ceil(
                        maxEdge * Math.max(bitmap.getWidth(), bitmap.getHeight()) / Math.max(1f, pageEdge)));
                // A page occupying half a camera frame needs more source pixels than a
                // full-frame page. Keep the decode bounded, then cap the rectified page.
                if (decodeEdge > maxEdge) {
                    bitmap.recycle();
                    bitmap = preview(source, mime, state.sourcePage, decodeEdge);
                }
                float[] from = PageGeometry.checked(state.crop);
                for (int i = 0; i < 4; i++) {
                    from[i * 2] *= bitmap.getWidth() - 1;
                    from[i * 2 + 1] *= bitmap.getHeight() - 1;
                }
                int width = Math.max(1, Math.round(Math.max(distance(from, 0, 1), distance(from, 3, 2))));
                int height = Math.max(1, Math.round(Math.max(distance(from, 0, 3), distance(from, 1, 2))));
                float scale = Math.min(1, (float) maxEdge / Math.max(width, height));
                width = Math.max(1, Math.round(width * scale));
                height = Math.max(1, Math.round(height * scale));
                Matrix transform = new Matrix();
                if (!transform.setPolyToPoly(from, 0, new float[]{0, 0, width, 0, width, height, 0, height}, 0, 4)) {
                    throw new IOException("The crop could not be applied. Try moving the corners.");
                }
                Bitmap cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(cropped);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(bitmap, transform, new Paint(Paint.FILTER_BITMAP_FLAG));
                bitmap.recycle();
                bitmap = cropped;
            }
            bitmap = rotated(bitmap, state.rotation);
            if (state.flatten) {
                PageFlattening.Model model = PageFlattening.estimate(bitmap);
                if (model != null) {
                    Bitmap flattened = PageFlattening.apply(bitmap, model);
                    bitmap.recycle();
                    bitmap = flattened;
                }
            }
            bitmap = enhance(bitmap, state.enhancement);
            return bitmap;
        } catch (IOException | RuntimeException error) {
            bitmap.recycle();
            throw error;
        }
    }

    /** Returns an enhanced bitmap and releases the input when a replacement is needed. */
    static Bitmap enhance(Bitmap bitmap, String preset) {
        if (preset.equals("original")) return bitmap;
        Bitmap result;
        if (preset.equals("black_white")) result = PageEnhancement.blackAndWhite(bitmap);
        else {
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(enhancementFilter(preset));
            result = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            new Canvas(result).drawBitmap(bitmap, 0, 0, paint);
        }
        bitmap.recycle();
        return result;
    }

    static Bitmap rotated(Bitmap bitmap, int degrees) {
        if (degrees == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.setRotate(degrees);
        Bitmap result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (result != bitmap) bitmap.recycle();
        return result;
    }

    private static float distance(float[] points, int a, int b) {
        return (float) Math.hypot(points[a * 2] - points[b * 2], points[a * 2 + 1] - points[b * 2 + 1]);
    }

    static void exportPages(List<PageState> pages, BitmapSource source, OutputStream output) throws IOException {
        StreamingPdf.write(pages,source,output);
    }

    static Bitmap preview(File source, String mime, int pageIndex, int maxEdge) throws IOException {
        // The type decides how its file is drawn (PDF pages, or a picture decoded upright). An unknown MIME is read as a picture, as before.
        FileType type = FileTypes.byMime(mime);
        if (type == null) type = FileTypes.byId("jpeg");
        Preview preview = type.preview(Source.of(source).page(pageIndex), maxEdge);
        if (preview == null) throw new IOException("This file could not be displayed.");
        return preview.bitmap;
    }

}
