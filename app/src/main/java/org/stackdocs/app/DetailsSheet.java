package org.stackdocs.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** A small persistent details sheet, with a touch/keyboard accessible expand handle. */
final class DetailsSheet extends LinearLayout {
    private final Activity activity;
    private final LinearLayout handle;
    private final ImageView arrow;
    private boolean expanded;
    private int viewport;
    private int collapsed;
    private int full;
    private float down;
    private ValueAnimator animation;

    DetailsSheet(Activity activity, View body) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        android.graphics.drawable.GradientDrawable background = Ui.rounded(activity, Color.WHITE, 0);
        float radius = Ui.dp(activity, 16);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        setBackground(background);
        setClipToOutline(true);
        setElevation(Ui.dp(activity, 8));
        handle = Ui.column(activity);
        handle.setBackground(Ui.ripple(activity, Color.TRANSPARENT, 16));
        FrameLayout gripArea = new FrameLayout(activity);
        View grip = new View(activity);
        grip.setBackground(Ui.rounded(activity, Ui.LINE, 2));
        gripArea.addView(grip, new FrameLayout.LayoutParams(Ui.dp(activity, 40), Ui.dp(activity, 4), Gravity.CENTER));
        handle.addView(gripArea, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 24)));
        LinearLayout heading = Ui.row(activity);
        heading.setPadding(Ui.dp(activity, 20), 0, Ui.dp(activity, 16), Ui.dp(activity, 8));
        heading.addView(Ui.text(activity, "Details", 14, true), new LinearLayout.LayoutParams(0, -2, 1));
        arrow = Ui.icon(activity, Icons.Symbol.NEXT, Ui.MUTED);
        arrow.setRotation(-90);
        heading.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(activity, 20), Ui.dp(activity, 20)));
        heading.setMinimumHeight(Ui.dp(activity,40));
        handle.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        handle.setFocusable(true);
        handle.setContentDescription("Expand document details");
        handle.setOnClickListener(view -> setExpanded(!expanded, true));
        handle.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { down = event.getRawY(); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float movement = event.getRawY() - down;
                if (Math.abs(movement) > Ui.dp(activity, 24)) setExpanded(movement < 0, true);
                else view.performClick();
                return true;
            }
            return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
        });
        addView(handle);
        ScrollView scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        scroll.addView(body);
        addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        collapsed = Ui.dp(activity, 160);
        full = Ui.dp(activity, 400);
    }

    int peekHeight() { return collapsed; }
    boolean expanded() { return expanded; }

    void fit(int height) {
        if (viewport == height || height <= 0) return;
        viewport = height;
        collapsed = Math.min(Ui.dp(activity, 160), Math.max(Ui.dp(activity, 104), height / 3));
        full = Math.max(collapsed, Math.min(Ui.dp(activity, 420), height - Ui.dp(activity, 48)));
        setExpanded(expanded, false);
    }

    void setExpanded(boolean value, boolean animate) {
        expanded = value;
        handle.setContentDescription(value ? "Collapse document details" : "Expand document details");
        arrow.setRotation(value ? 90 : -90);
        if (animation != null) animation.cancel();
        int target = value ? full : collapsed;
        if (getLayoutParams() == null) return;
        if (!animate) { getLayoutParams().height = target; requestLayout(); return; }
        animation = ValueAnimator.ofInt(getHeight(), target);
        animation.setDuration(180);
        animation.addUpdateListener(frame -> { getLayoutParams().height = (int) frame.getAnimatedValue(); requestLayout(); });
        animation.start();
    }

    void release() { if (animation != null) animation.cancel(); }
}
