package org.stackdocs.app;

import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import java.util.concurrent.Future;

/** Bounded, local first-page thumbnails; detached list cells cancel queued rendering. */
final class DocumentThumbnails {
    private final LibraryActivity activity;
    private int generation;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };

    DocumentThumbnails(LibraryActivity activity) { this.activity = activity; }
    void invalidate() { generation++; cache.evictAll(); }

    void bind(ImageView image, Document document) {
        Bitmap cached = cache.get(document.id);
        if (cached != null) { image.setImageBitmap(cached); return; }
        Object token = new Object();
        image.setTag(token);
        int ticket = generation;
        image.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private Future<?> pending;
            @Override public void onViewAttachedToWindow(View view) {
                Bitmap existing = cache.get(document.id);
                if (existing != null) { image.setImageBitmap(existing); return; }
                pending = activity.app().io.submit(() -> {
                    Bitmap bitmap;
                    try { bitmap = activity.app().library.renderPage(document, 0, 360); }
                    catch (Exception error) { return; } // A thumbnail failure must leave the document accessible.
                    activity.runOnUiThread(() -> {
                        if (activity.isDestroyed() || activity.isFinishing() || ticket != generation) { bitmap.recycle(); return; }
                        cache.put(document.id, bitmap);
                        if (image.getTag() == token && image.isAttachedToWindow()) image.setImageBitmap(bitmap);
                    });
                });
            }
            @Override public void onViewDetachedFromWindow(View view) {
                if (pending != null) pending.cancel(false);
            }
        });
    }
}
