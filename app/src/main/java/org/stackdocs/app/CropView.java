package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Four draggable corners, with keyboard arrows for the selected corner. */
final class CropView extends View {
    private Bitmap bitmap;
    private float[] points = PageGeometry.full();
    private final RectF image = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path polygon = new Path();
    private final Path shade = new Path();
    private int selected = -1;

    CropView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("Crop page. Drag the four corners to the document edges. Use accessibility actions to select and move a corner. Keyboard arrows move the selected corner.");
    }

    void setImage(Bitmap bitmap, float[] points) {
        release();
        this.bitmap = bitmap;
        this.points = points.clone();
        invalidate();
    }

    void setPoints(float[] points) { this.points = PageGeometry.checked(points); invalidate(); }
    float[] points() { return points.clone(); }

    void release() { if (bitmap != null) { bitmap.recycle(); bitmap = null; } }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        float padding = 24 * getResources().getDisplayMetrics().density;
        float scale = Math.min(Math.max(1, getWidth() - padding * 2) / bitmap.getWidth(),
                Math.max(1, getHeight() - padding * 2) / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        image.set((getWidth() - width) / 2, (getHeight() - height) / 2,
                (getWidth() + width) / 2, (getHeight() + height) / 2);
        canvas.drawBitmap(bitmap, null, image, paint);
        polygon.reset();
        polygon.moveTo(x(0), y(0));
        for (int i = 1; i < 4; i++) polygon.lineTo(x(i), y(i));
        polygon.close();
        shade.reset();
        shade.addRect(image, Path.Direction.CW);
        shade.addPath(polygon);
        shade.setFillType(Path.FillType.EVEN_ODD);
        paint.setStyle(Paint.Style.FILL); paint.setColor(0x99000000);
        canvas.drawPath(shade, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(Ui.BLUE); paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        canvas.drawPath(polygon, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            paint.setColor(i == selected ? Ui.BLUE : Color.WHITE);
            canvas.drawCircle(x(i), y(i), 8 * getResources().getDisplayMetrics().density, paint);
        }
    }

    private float x(int corner) { return image.left + points[corner * 2] * image.width(); }
    private float y(int corner) { return image.top + points[corner * 2 + 1] * image.height(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || bitmap == null || image.width() == 0) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            requestFocus();
            float closest = 48 * getResources().getDisplayMetrics().density;
            selected = -1;
            for (int i = 0; i < 4; i++) {
                float distance = (float) Math.hypot(event.getX() - x(i), event.getY() - y(i));
                if (distance < closest) { closest = distance; selected = i; }
            }
            invalidate();
            return selected >= 0;
        }
        if (selected >= 0 && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            move((event.getX() - image.left) / image.width(), (event.getY() - image.top) / image.height());
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) { performClick(); return true; }
        return selected >= 0;
    }

    private void move(float u, float v) {
        float[] candidate = points.clone();
        candidate[selected * 2] = Math.max(0, Math.min(1, u));
        candidate[selected * 2 + 1] = Math.max(0, Math.min(1, v));
        try { points = PageGeometry.checked(candidate); invalidate(); }
        catch (IllegalArgumentException ignored) { /* Crossing corners is not an applicable crop. */ }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isEnabled()) return false;
        if (selected < 0) selected = 0;
        float u = points[selected * 2], v = points[selected * 2 + 1];
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) u -= 0.005f;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) u += 0.005f;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) v -= 0.005f;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) v += 0.005f;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) { selected = (selected + 1) % 4; invalidate(); return true; }
        else return super.onKeyDown(keyCode, event);
        move(u, v);
        return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01020001,"Select next crop corner"));
        String[] directions={"left","right","up","down"};
        for(int i=0;i<directions.length;i++) info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01020002+i,"Move selected corner "+directions[i]));
    }

    @Override public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        if(action>=0x01020001 && action<=0x01020005 && isEnabled()) {
            if(selected<0) selected=0;
            if(action==0x01020001) selected=(selected+1)%4;
            else {
                float u=points[selected*2],v=points[selected*2+1];
                if(action==0x01020002) u-=.01f;
                if(action==0x01020003) u+=.01f;
                if(action==0x01020004) v-=.01f;
                if(action==0x01020005) v+=.01f;
                move(u,v);
            }
            String[] names={"Top left","Top right","Bottom right","Bottom left"};
            setContentDescription(names[selected]+" crop corner, "+Math.round(points[selected*2]*100)+" percent across, "+Math.round(points[selected*2+1]*100)+" percent down");
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);
            invalidate(); return true;
        }
        return super.performAccessibilityAction(action,arguments);
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
