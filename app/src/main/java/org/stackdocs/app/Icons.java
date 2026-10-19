package org.stackdocs.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Independently drawn, small geometric symbols. No reference artwork or icon font. */
final class Icons extends Drawable {
    enum Symbol { SEARCH, BACK, CLOSE, MORE, ADD, HOME, DOCUMENTS, DOCUMENT, CAMERA, PHOTO,
        SHARE, FILTER, EDIT, TUNE, CROP, ROTATE, COLOR, DELETE, LIGHT, NEXT, PREVIOUS, CHECK,
        BILLS, BANKING, IDS, HOUSE, MEDICAL, RECEIPTS, STARRED, FOLDER }

    private final Symbol symbol;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;
    private boolean enabled = true;

    Icons(Symbol symbol, int color) {
        this.symbol = symbol;
        paint.setColor(color);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    static Symbol category(StackCategory category) {
        // Restored/imported stacks can have opaque or remapped IDs. Keep the
        // identity of renamed starter stacks, then recognize familiar names.
        Symbol byId = categorySymbol(category.id);
        return byId != Symbol.FOLDER ? byId : categorySymbol(category.name);
    }

    private static Symbol categorySymbol(String key) {
        switch (key.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "bills": return Symbol.BILLS;
            case "banking": return Symbol.BANKING;
            case "ids": return Symbol.IDS;
            case "house": return Symbol.HOUSE;
            case "medical": return Symbol.MEDICAL;
            case "receipts": return Symbol.RECEIPTS;
            case "starred": return Symbol.STARRED;
            default: return Symbol.FOLDER;
        }
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        int save = canvas.save();
        canvas.translate(b.left, b.top);
        canvas.scale(b.width() / 24f, b.height() / 24f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setAlpha(enabled ? alpha : alpha / 3);
        switch (symbol) {
            case SEARCH: canvas.drawCircle(10.5f, 10.5f, 6, paint); line(canvas, 15, 15, 20, 20); break;
            case BACK: line(canvas, 20, 12, 4, 12); path(canvas, 11, 5, 4, 12, 11, 19); break;
            case NEXT: path(canvas, 9, 5, 16, 12, 9, 19); break;
            case PREVIOUS: path(canvas, 15, 5, 8, 12, 15, 19); break;
            case CLOSE: line(canvas, 6, 6, 18, 18); line(canvas, 18, 6, 6, 18); break;
            case ADD: line(canvas, 12, 5, 12, 19); line(canvas, 5, 12, 19, 12); break;
            case FILTER: line(canvas, 4, 6, 20, 6); line(canvas, 7, 12, 17, 12); line(canvas, 10, 18, 14, 18); break;
            case MORE:
                paint.setStyle(Paint.Style.FILL);
                for (int y = 5; y <= 19; y += 7) canvas.drawCircle(12, y, 1.5f, paint);
                break;
            case HOME: case HOUSE:
                path(canvas, 3, 11, 12, 3, 21, 11);
                path(canvas, 5, 10, 5, 21, 10, 21, 10, 15, 14, 15, 14, 21, 19, 21, 19, 10); break;
            case DOCUMENTS:
                path(canvas, 4, 7, 4, 21, 17, 21);
                document(canvas, 8, 3, 13, 16); break;
            case DOCUMENT: case BILLS:
                document(canvas, 5, 2, 14, 20);
                if (symbol == Symbol.BILLS) {
                    path(canvas, 14, 10, 10, 10, 10, 13, 14, 13, 14, 16, 10, 16);
                    line(canvas, 12, 8.5f, 12, 17.5f);
                } else { line(canvas, 8, 12, 16, 12); line(canvas, 8, 16, 16, 16); }
                break;
            case BANKING:
                path(canvas, 16, 7, 14, 5, 10, 5, 8, 7, 8, 9, 10, 11, 14, 12, 16, 14, 16, 17, 14, 19, 10, 19, 8, 17);
                line(canvas, 12, 2.5f, 12, 21.5f); break;
            case IDS:
                canvas.drawRoundRect(3, 7, 21, 21, 1.5f, 1.5f, paint);
                canvas.drawRoundRect(9, 3, 15, 10, 1, 1, paint);
                canvas.drawCircle(8, 13, 1.5f, paint);
                path(canvas, 5.5f, 18, 5.5f, 17, 8, 16, 10.5f, 17, 10.5f, 18);
                line(canvas, 14, 13, 18, 13); line(canvas, 14, 17, 18, 17); break;
            case MEDICAL:
                path(canvas, 9, 3, 15, 3, 15, 9, 21, 9, 21, 15, 15, 15, 15, 21, 9, 21, 9, 15, 3, 15, 3, 9, 9, 9, 9, 3); break;
            case RECEIPTS:
                path(canvas, 5, 3, 7, 4, 9, 3, 11, 4, 13, 3, 15, 4, 17, 3, 19, 4, 19, 21, 17, 20, 15, 21, 13, 20, 11, 21, 9, 20, 7, 21, 5, 20, 5, 3);
                for (int y = 8; y <= 16; y += 4) line(canvas, 8, y, 16, y);
                break;
            case STARRED:
                Path star = new Path();
                for (int i = 0; i < 10; i++) {
                    double angle = -Math.PI / 2 + i * Math.PI / 5;
                    float radius = i % 2 == 0 ? 10 : 4.5f;
                    float x = 12 + (float) Math.cos(angle) * radius, y = 12 + (float) Math.sin(angle) * radius;
                    if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
                }
                star.close(); paint.setStyle(Paint.Style.FILL); canvas.drawPath(star, paint); break;
            case FOLDER:
                path(canvas, 3, 7, 3, 5, 10, 5, 12, 8, 21, 8, 21, 20, 3, 20, 3, 7); break;
            case CAMERA:
                path(canvas, 3, 7, 7, 7, 9, 4, 15, 4, 17, 7, 21, 7, 21, 20, 3, 20, 3, 7);
                canvas.drawCircle(12, 13, 4, paint); break;
            case PHOTO:
                canvas.drawRoundRect(3, 3, 21, 21, 1.5f, 1.5f, paint);
                canvas.drawCircle(8, 8, 1.5f, paint);
                path(canvas, 4, 18, 9, 13, 12, 16, 16, 10, 20, 16); break;
            case SHARE:
                line(canvas, 6, 12, 18, 5); line(canvas, 6, 12, 18, 19);
                for (int[] point : new int[][]{{5, 12}, {19, 4}, {19, 20}}) {
                    paint.setStyle(Paint.Style.FILL); canvas.drawCircle(point[0], point[1], 2.5f, paint);
                }
                break;
            case CHECK: path(canvas, 4, 12, 9, 17, 20, 6); break;
            case EDIT:
                path(canvas, 4, 20, 5, 15, 16, 4, 20, 8, 9, 19, 4, 20);
                line(canvas, 14, 6, 18, 10); break;
            case TUNE:
                for (int y = 6; y <= 18; y += 6) line(canvas, 3, y, 21, y);
                canvas.drawCircle(8, 6, 2, paint); canvas.drawCircle(16, 12, 2, paint); canvas.drawCircle(10, 18, 2, paint); break;
            case CROP:
                path(canvas, 3, 7, 17, 7, 17, 22); path(canvas, 7, 2, 7, 17, 22, 17);
                line(canvas, 11, 13, 21, 3); break;
            case ROTATE:
                canvas.drawArc(5, 5, 21, 21, 200, 285, false, paint);
                path(canvas, 3, 9, 5, 3, 11, 5); break;
            case COLOR:
                canvas.drawCircle(12, 12, 9, paint);
                for (float[] point : new float[][]{{8, 8}, {14, 6.5f}, {18, 11}, {8, 15}}) {
                    paint.setStyle(Paint.Style.FILL); canvas.drawCircle(point[0], point[1], 1.5f, paint);
                }
                break;
            case DELETE:
                canvas.drawRoundRect(6, 7, 18, 21, 1, 1, paint); line(canvas, 4, 5, 20, 5);
                path(canvas, 9, 5, 9, 2, 15, 2, 15, 5); line(canvas, 10, 10, 10, 18); line(canvas, 14, 10, 14, 18); break;
            case LIGHT: path(canvas, 13, 2, 5, 13, 11, 13, 10, 22, 20, 9, 13, 9, 13, 2); break;
        }
        canvas.restoreToCount(save);
    }

    private void line(Canvas c, float a, float b, float x, float y) { c.drawLine(a, b, x, y, paint); }
    private void path(Canvas canvas, float... coordinates) {
        Path path = new Path(); path.moveTo(coordinates[0], coordinates[1]);
        for (int i = 2; i < coordinates.length; i += 2) path.lineTo(coordinates[i], coordinates[i + 1]);
        canvas.drawPath(path, paint);
    }
    private void document(Canvas c, float x, float y, float w, float h) {
        path(c, x, y, x + w - 5, y, x + w, y + 5, x + w, y + h, x, y + h, x, y);
        path(c, x + w - 5, y, x + w - 5, y + 5, x + w, y + 5);
    }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] states) {
        boolean value = android.util.StateSet.stateSetMatches(new int[]{android.R.attr.state_enabled}, states);
        if (enabled == value) return false;
        enabled = value; invalidateSelf(); return true;
    }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 24; }
    @Override public int getIntrinsicHeight() { return 24; }
}
