package org.stackdocs.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Companion apps: one place to install and update the family. Each install still needs Android's own confirmation. */
public final class CompanionsActivity extends LibraryActivity {
    private LinearLayout body;
    private String progress = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Companions.Listener listener = new Companions.Listener() {
        @Override public void progress(String text) { main.post(() -> { progress = text; refresh(); }); }
        @Override public void done(Companions.Problem problem) { main.post(() -> { progress = problem == null ? "Confirm in the Android prompt." : ""; refresh(); }); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this);
        bar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        bar.addView(Ui.text(this, "Companion apps", 18, false), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        body = Ui.column(this);
        body.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, root);
    }

    @Override protected void onResume() { super.onResume(); refresh(); carryOn(); }
    @Override protected void onLibraryChanged() { refresh(); carryOn(); }

    /** Update all: after each result, start the next app that still needs something. */
    private void carryOn() {
        if (Companions.busy() || Companions.waitingForAndroid(this)) return;
        List<String> waiting = Companions.pending(this);
        if (waiting.isEmpty()) return;
        Set<String> satisfied = new HashSet<>();
        for (Companions.App app : Companions.ALL) if (!Companions.needsAction(Companions.stateOf(this, app))) satisfied.add(app.pkg);
        String next = Companions.next(waiting, satisfied);
        if (next == null) { Companions.setPending(this, new ArrayList<>()); refresh(); return; }
        for (Companions.App app : Companions.ALL) if (app.pkg.equals(next)) {
            if (app.isStack()) Companions.setPending(this, new ArrayList<>());   // its update restarts Stack
            Companions.start(this, app, listener);
            return;
        }
    }

    private void refresh() {
        if (body == null) return;
        body.removeAllViews();
        boolean busy = Companions.busy() || Companions.waitingForAndroid(this);
        TextView intro = Ui.text(this, "Stack can install and update the apps that go with it. Android asks you to confirm each one.", 14, false);
        intro.setTextColor(Ui.MUTED);
        body.addView(intro);
        String note = progress.isEmpty() ? Companions.note(this) : progress;
        if (!note.isEmpty()) { TextView line = Ui.text(this, note, 14, true); line.setPadding(0, Ui.dp(this, 12), 0, 0); body.addView(line); }
        for (Companions.App app : Companions.ALL) {
            body.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
            Companions.State state = Companions.stateOf(this, app);
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
            LinearLayout words = Ui.column(this);
            words.addView(Ui.text(this, app.name, 16, true));
            TextView status = Ui.text(this, Companions.label(state, Companions.installedName(this, app), Companions.latest(this, app)), 13, false);
            status.setTextColor(Ui.MUTED);
            words.addView(status);
            if (state == Companions.State.TEST_BUILD) {
                TextView why = Ui.text(this, "Test copy of Stack: no release updates.", 13, false);
                why.setTextColor(Ui.MUTED);
                words.addView(why);
            }
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            String label = Companions.button(state);
            if (label != null) {
                Button button = Ui.button(this, label, () -> { Companions.setPending(this, new ArrayList<>()); Companions.start(this, app, listener); });
                button.setEnabled(!busy);
                row.addView(button);
            }
            body.addView(row);
        }
        body.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        boolean any = !Companions.queue(Companions.ALL, app -> Companions.stateOf(this, app)).isEmpty();
        Button all = Ui.button(this, "Update all", () -> {
            Companions.setPending(this, Companions.queue(Companions.ALL, app -> Companions.stateOf(this, app)));
            Companions.setNote(this, "");
            carryOn();
        });
        all.setEnabled(any && !busy);
        body.addView(all);
        Button check = Ui.button(this, "Look for updates", () -> {
            progress = "Looking for updates…"; refresh();
            new Thread(() -> { Companions.refreshAll(app()); main.post(() -> { progress = ""; refresh(); }); }, "Companion check").start();
        });
        check.setEnabled(!busy);
        body.addView(check);
        if (busy) {
            body.addView(Ui.button(this, "Cancel", () -> AppUpdater.canceled = true));
        }
    }
}
