package org.stackdocs.app;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.function.Consumer;

final class ColorOptions {
    private static final String[] VALUES = {"black_white", "original", "medium", "bright"};
    private static final String[] LABELS = {"Black & white", "Original color", "Color · Medium", "Color · Bright"};

    private ColorOptions() {}

    static void show(Activity activity, String current, Consumer<String> selected) {
        int checked = 1;
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i].equals(current)) checked = i;
        new AlertDialog.Builder(activity).setTitle("Page color")
                .setSingleChoiceItems(LABELS, checked, (dialog, which) -> {
                    dialog.dismiss();
                    selected.accept(VALUES[which]);
                }).setNegativeButton("Cancel", null).show();
    }
}
