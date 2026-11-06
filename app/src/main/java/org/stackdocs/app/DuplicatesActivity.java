package org.stackdocs.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Review documents whose saved originals are identical and move the extra copies to Recently deleted. */
public final class DuplicatesActivity extends LibraryActivity {
    private LinearLayout body;
    private Button action;
    private final List<List<String>> groupIds = new ArrayList<>();
    private final List<List<CheckBox>> groupBoxes = new ArrayList<>();
    private int scanGeneration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this);
        bar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        bar.addView(Ui.text(this, "Find duplicates", 18, false), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        body = Ui.column(this);
        body.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        action = Ui.primary(this, "Move to Recently deleted", this::confirm);
        action.setVisibility(android.view.View.GONE);
        root.addView(action, new LinearLayout.LayoutParams(-1, -2));
        Ui.install(this, root);
    }

    @Override protected void onResume() { super.onResume(); scan(); }

    @Override protected void onLibraryChanged() { scan(); }

    private void scan() {
        final int generation = ++scanGeneration;
        body.removeAllViews();
        action.setVisibility(android.view.View.GONE);
        body.addView(note("Looking for duplicates\u2026"));
        new Thread(() -> {
            List<List<Document>> found;
            try { found = DuplicateCheck.scan(app()); } catch (Exception error) { found = new ArrayList<>(); }
            List<List<Document>> result = found;
            runOnUiThread(() -> { if (generation == scanGeneration && !isFinishing() && !isDestroyed()) render(result); });
        }, "Stack duplicate scan").start();
    }

    private TextView note(String text) {
        TextView view = Ui.text(this, text, 14, false);
        view.setTextColor(Ui.MUTED);
        view.setPadding(0, 0, 0, Ui.dp(this, 12));
        return view;
    }

    private void render(List<List<Document>> groups) {
        body.removeAllViews();
        groupIds.clear();
        groupBoxes.clear();
        if (groups.isEmpty()) {
            body.addView(note("No duplicates found. Stack compares the saved files of imported PDFs and images; scanned pages are not compared."));
            action.setVisibility(android.view.View.GONE);
            return;
        }
        body.addView(note("These documents have identical files. The oldest of each set is kept; tick the extra copies to move to Recently deleted. Their notes, tags and stacks stay recoverable for 30 days."));
        DateFormat date = DateFormat.getDateInstance(DateFormat.MEDIUM);
        for (List<Document> group : groups) {
            List<String> ids = new ArrayList<>();
            List<CheckBox> boxes = new ArrayList<>();
            body.addView(Ui.text(this, group.size() + " copies", 15, true));
            for (int i = 0; i < group.size(); i++) {
                Document document = group.get(i);
                ids.add(document.id);
                CheckBox box = new CheckBox(this);
                box.setText(document.title + "\nAdded " + date.format(new Date(document.addedAt)) + " \u00b7 " + document.pageCount + (document.pageCount == 1 ? " page" : " pages") + (i == 0 ? " \u00b7 kept" : ""));
                box.setTextSize(15);
                box.setTextColor(Ui.INK);
                box.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), 0, Ui.dp(this, 8));
                box.setChecked(i != 0);
                final int index = boxes.size();
                boxes.add(box);
                box.setOnCheckedChangeListener((view, checked) -> {
                    if (checked && allChecked(boxes)) {
                        view.setChecked(false);
                        Toast.makeText(this, "Keep at least one copy", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    updateAction();
                });
                body.addView(box);
            }
            body.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
            groupIds.add(ids);
            groupBoxes.add(boxes);
        }
        updateAction();
    }

    private static boolean allChecked(List<CheckBox> boxes) {
        for (CheckBox box : boxes) if (!box.isChecked()) return false;
        return true;
    }

    private List<String> selected() {
        List<String> out = new ArrayList<>();
        for (int g = 0; g < groupIds.size(); g++) {
            // Never trust the screen alone: a set with every copy ticked keeps its oldest.
            boolean everything = allChecked(groupBoxes.get(g));
            for (int i = 0; i < groupIds.get(g).size(); i++) {
                if (groupBoxes.get(g).get(i).isChecked() && !(everything && i == 0)) out.add(groupIds.get(g).get(i));
            }
        }
        return out;
    }

    private void updateAction() {
        int count = selected().size();
        action.setText("Move " + count + (count == 1 ? " document" : " documents") + " to Recently deleted");
        action.setEnabled(count > 0);
        action.setVisibility(android.view.View.VISIBLE);
    }

    private void confirm() {
        List<String> ids = selected();
        if (ids.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("Move " + ids.size() + (ids.size() == 1 ? " document" : " documents") + " to Recently deleted?")
                .setMessage("You can restore them for 30 days, then they are deleted for good. The oldest copy of each set stays in your library.")
                .setPositiveButton("Move", (d, w) -> work(() -> { for (String id : ids) app().library.trashDocument(id); return true; }, ok -> app().notifyLibraryChanged()))
                .setNegativeButton("Cancel", null).show();
    }
}
