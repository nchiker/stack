package org.stackdocs.app.types;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.IOException;

/** Decoding of a picture file to at most maxEdge pixels on the longest side, upright by its EXIF orientation. Moved unchanged from PageRenderer. */
final class ImageDecode {
    private ImageDecode() {}

    /** As {@link #decode(File, int)} for a Source that may be only a descriptor (the isolated extract process). */
    static Bitmap decode(Source in, int maxEdge) throws IOException {
        if (in.file() != null) return decode(in.file(), maxEdge);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (android.os.ParcelFileDescriptor d = in.descriptor()) { BitmapFactory.decodeFileDescriptor(d.getFileDescriptor(), null, bounds); }
        Bitmap bitmap;
        try (android.os.ParcelFileDescriptor d = in.descriptor()) { bitmap = BitmapFactory.decodeFileDescriptor(d.getFileDescriptor(), null, options(bounds, maxEdge)); }
        int orientation;
        try (android.os.ParcelFileDescriptor d = in.descriptor()) { orientation = new ExifInterface(d.getFileDescriptor()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL); }
        catch (IOException | RuntimeException ignored) { orientation = ExifInterface.ORIENTATION_NORMAL; }
        return finish(bitmap, orientation, maxEdge);
    }

    private static BitmapFactory.Options options(BitmapFactory.Options bounds, int maxEdge) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2L) >= maxEdge) options.inSampleSize *= 2;
        return options;
    }

    static Bitmap decode(File source, int maxEdge) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        // Keep enough source pixels for the requested size. Rounding the sample upward
        // throws away fine print before the perspective/color processing can use it.
        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2L) >= maxEdge) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(source.getPath(), options);
        int orientation;
        try {
            orientation = new ExifInterface(source.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
            orientation = ExifInterface.ORIENTATION_NORMAL;
        }
        return finish(bitmap, orientation, maxEdge);
    }

    private static Bitmap finish(Bitmap bitmap, int orientation, int maxEdge) throws IOException {
        if (bitmap == null) throw new IOException("This image could not be displayed.");
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest > maxEdge) {
            float scale = (float) maxEdge / longest;
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (resized != bitmap) bitmap.recycle();
            bitmap = resized;
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            default: break;
        }
        if (!matrix.isIdentity()) {
            Bitmap upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (upright != bitmap) bitmap.recycle();
            bitmap = upright;
        }
        return bitmap;
    }
}
