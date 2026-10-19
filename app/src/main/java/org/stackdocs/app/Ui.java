package org.stackdocs.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int INK = 0xFF202124;
    static final int MUTED = 0xFF5F6368;
    static final int BLUE = 0xFF1A73E8;
    static final int PAPER = Color.WHITE;
    static final int DOCUMENT_BG = 0xFFF1F3F4;
    static final int LINE = 0xFFDADCE0;

    private Ui() {}

    static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static TextView text(Activity activity, String value, int size, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(INK);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static LinearLayout column(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static GradientDrawable rounded(Activity activity, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(activity, radius));
        return drawable;
    }

    static Button button(Activity activity, String label, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setTextSize(14);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0xFF9AA0A6, BLUE}));
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setBackground(ripple(activity, Color.TRANSPARENT, 24));
        button.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);
        button.setMinWidth(dp(activity, 48));
        button.setMinimumWidth(dp(activity, 48));
        button.setMinHeight(dp(activity, 48));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    static RippleDrawable ripple(Activity activity, int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x221A73E8), rounded(activity, color, radius), rounded(activity, Color.WHITE, radius));
    }

    static GradientDrawable outlined(Activity activity, int color, int border, int radius, int stroke) {
        GradientDrawable drawable = rounded(activity, color, radius);
        drawable.setStroke(dp(activity, stroke), border);
        return drawable;
    }

    static ImageView icon(Activity activity, Icons.Symbol symbol, int color) {
        ImageView image = new ImageView(activity);
        image.setImageDrawable(new Icons(symbol, color));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    static ImageButton iconButton(Activity activity, Icons.Symbol symbol, String label, Runnable action) {
        ImageButton button = new ImageButton(activity);
        button.setImageDrawable(new Icons(symbol, MUTED));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setContentDescription(label);
        button.setBackground(ripple(activity, Color.TRANSPARENT, 24));
        button.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        button.setMinimumWidth(dp(activity, 48));
        button.setMinimumHeight(dp(activity, 48));
        if (Build.VERSION.SDK_INT >= 26) button.setTooltipText(label);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    static Button primary(Activity activity, String label, Runnable action) {
        Button button = button(activity, label, action);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0xFF9AA0A6, Color.WHITE}));
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, rounded(activity, DOCUMENT_BG, 24));
        background.addState(new int[]{}, ripple(activity, BLUE, 24));
        button.setBackground(background);
        return button;
    }

    static Button tool(Activity activity, Icons.Symbol symbol, String label, Runnable action) {
        Button button = button(activity, label, action);
        Icons icon = new Icons(symbol, MUTED);
        icon.setBounds(0, 0, dp(activity, 24), dp(activity, 24));
        button.setCompoundDrawables(null, icon, null, null);
        button.setCompoundDrawablePadding(dp(activity, 6));
        button.setTextColor(INK);
        button.setTextSize(12);
        button.setPadding(dp(activity, 4), dp(activity, 12), dp(activity, 4), dp(activity, 12));
        return button;
    }

    static FrameLayout stackBadge(Activity activity, StackCategory category, int size) {
        FrameLayout badge = new FrameLayout(activity);
        badge.setBackground(outlined(activity, Color.WHITE, stackColor(category), size / 2, 3));
        ImageView glyph = icon(activity, Icons.category(category), INK);
        int iconSize = size >= 64 ? 30 : 20;
        badge.addView(glyph, new FrameLayout.LayoutParams(dp(activity, iconSize), dp(activity, iconSize), Gravity.CENTER));
        return badge;
    }

    static int stackColor(StackCategory category) {
        return StackStyle.ringColor(category);
    }

    static View actionRow(Activity activity,Icons.Symbol icon,String title,String subtitle,int ring,Runnable action) {
        boolean large=activity.getResources().getConfiguration().fontScale>=1.5f;int badgeSize=large?40:56;
        LinearLayout row=Ui.row(activity);row.setMinimumHeight(Ui.dp(activity,100));row.setPadding(0,Ui.dp(activity,16),0,Ui.dp(activity,16));row.setBackground(Ui.ripple(activity,Color.TRANSPARENT,8));
        android.widget.FrameLayout badge=new android.widget.FrameLayout(activity);badge.setBackground(Ui.outlined(activity,Color.WHITE,ring,28,3));
        badge.addView(Ui.icon(activity,icon,Ui.INK),new android.widget.FrameLayout.LayoutParams(Ui.dp(activity,26),Ui.dp(activity,26),Gravity.CENTER));row.addView(badge,new LinearLayout.LayoutParams(Ui.dp(activity,badgeSize),Ui.dp(activity,badgeSize)));
        LinearLayout words=Ui.column(activity);words.setPadding(Ui.dp(activity,large?12:16),0,Ui.dp(activity,8),0);
        words.addView(Ui.text(activity,title,16,true));TextView caption=text(activity,subtitle,13,false);caption.setTextColor(MUTED);caption.setPadding(0,Ui.dp(activity,6),0,0);words.addView(caption);row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        ImageView next=Ui.icon(activity,Icons.Symbol.NEXT,Ui.MUTED);row.addView(next,new LinearLayout.LayoutParams(Ui.dp(activity,20),Ui.dp(activity,20)));
        row.setFocusable(true);row.setContentDescription(title+". "+subtitle);row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.setOnClickListener(view->action.run());return row;
    }

    static View divider(Activity activity) {
        View divider = new View(activity); divider.setBackgroundColor(0xFFEEEEEE); return divider;
    }

    static LinearLayout toolbar(Activity activity) {
        LinearLayout toolbar = row(activity);
        toolbar.setMinimumHeight(dp(activity, 56));
        toolbar.setPadding(dp(activity, 4), 0, dp(activity, 4), 0);
        toolbar.setBackgroundColor(Color.WHITE);
        return toolbar;
    }

    static Runnable backHandler(Activity activity, Runnable action) {
        return Build.VERSION.SDK_INT >= 33 ? ModernBack.register(activity, action) : () -> {};
    }

    @androidx.annotation.RequiresApi(33)
    private static final class ModernBack {
        static Runnable register(Activity activity, Runnable action) {
            android.window.OnBackInvokedCallback callback = action::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return () -> activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        }
    }

    static void darkBars(Activity activity, boolean dark) {
        activity.getWindow().setStatusBarColor(dark ? 0xFF17181A : Color.WHITE);
        activity.getWindow().setNavigationBarColor(dark ? 0xFF17181A : (Build.VERSION.SDK_INT >= 26 ? Color.WHITE : INK));
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        } else {
            activity.getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                    : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        }
    }

    static void install(Activity activity, LinearLayout root) {
        root.setBackgroundColor(PAPER);
        if (Build.VERSION.SDK_INT >= 30) activity.getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        activity.setContentView(root);
        root.requestApplyInsets();
    }
}
