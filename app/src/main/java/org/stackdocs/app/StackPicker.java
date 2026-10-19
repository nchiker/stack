package org.stackdocs.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Shared visual, multiple-stack selection for filing and document details. */
final class StackPicker {
    private StackPicker() {}

    static void show(Activity activity, List<StackCategory> stacks, Collection<String> initial, Consumer<List<String>> save) {
        show(activity,stacks,initial,"Choose stacks",save);
    }

    static void show(Activity activity,List<StackCategory> stacks,Collection<String> initial,String heading,Consumer<List<String>> save) {
        Set<String> selected = new LinkedHashSet<>(initial);
        Set<String> available=new LinkedHashSet<>();
        for(StackCategory stack:stacks) available.add(stack.id);
        selected.retainAll(available); // Save only choices represented by a current tile.
        LinearLayout grid = Ui.column(activity);
        grid.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 12), Ui.dp(activity, 8), Ui.dp(activity, 12));
        int columns = Math.max(2, Math.min(4, (int) ((activity.getResources().getConfiguration().screenWidthDp - 80)
                / (84 * Math.max(1, activity.getResources().getConfiguration().fontScale)))));
        for (int start = 0; start < stacks.size(); start += columns) {
            LinearLayout row = Ui.row(activity);
            row.setGravity(Gravity.TOP);
            for (int i = 0; i < columns; i++) {
                if (start + i >= stacks.size()) { row.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1)); continue; }
                StackCategory category = stacks.get(start + i);
                LinearLayout tile = Ui.column(activity);
                tile.setGravity(Gravity.CENTER_HORIZONTAL);
                tile.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8));
                tile.setBackground(Ui.ripple(activity, android.graphics.Color.TRANSPARENT, 12));
                FrameLayout badge = Ui.stackBadge(activity, category, 64);
                ImageView checked = Ui.icon(activity, Icons.Symbol.CHECK, android.graphics.Color.WHITE);
                checked.setPadding(Ui.dp(activity, 3), Ui.dp(activity, 3), Ui.dp(activity, 3), Ui.dp(activity, 3));
                checked.setBackground(Ui.rounded(activity, Ui.BLUE, 12));
                badge.addView(checked, new FrameLayout.LayoutParams(Ui.dp(activity, 22), Ui.dp(activity, 22), Gravity.BOTTOM | Gravity.END));
                checked.setVisibility(selected.contains(category.id) ? View.VISIBLE : View.INVISIBLE);
                tile.addView(badge, new LinearLayout.LayoutParams(Ui.dp(activity, 64), Ui.dp(activity, 64)));
                android.widget.TextView name = Ui.text(activity, category.name, 13, false);
                name.setGravity(Gravity.CENTER);
                name.setPadding(0, Ui.dp(activity, 8), 0, 0);
                tile.addView(name);
                badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                tile.setSelected(selected.contains(category.id));
                tile.setContentDescription(category.name);
                tile.setFocusable(true);
                tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(android.widget.CheckBox.class.getName());
                        info.setCheckable(true);
                        info.setChecked(selected.contains(category.id));
                    }
                });
                tile.setOnClickListener(view -> {
                    if (!selected.remove(category.id)) selected.add(category.id);
                    checked.setVisibility(selected.contains(category.id) ? View.VISIBLE : View.INVISIBLE);
                    tile.setSelected(selected.contains(category.id));
                    tile.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                });
                row.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
            }
            grid.addView(row);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(grid);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(heading).setView(scroll)
                .setPositiveButton("Save", (which, action) -> save.accept(new ArrayList<>(selected)))
                .setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> {
            int maximum = Ui.dp(activity, Math.min(420, activity.getResources().getConfiguration().screenHeightDp / 2));
            scroll.post(() -> { if (scroll.getHeight() > maximum) { scroll.getLayoutParams().height = maximum; scroll.requestLayout(); } });
        });
        dialog.show();
    }
}
