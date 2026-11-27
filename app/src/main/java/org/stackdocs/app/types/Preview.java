package org.stackdocs.app.types;

import android.graphics.Bitmap;

/** A rendered picture of a file or one of its pages. The caller owns (and recycles) the bitmap. */
public final class Preview {
    public final Bitmap bitmap;
    public Preview(Bitmap bitmap) { this.bitmap = bitmap; }
}
