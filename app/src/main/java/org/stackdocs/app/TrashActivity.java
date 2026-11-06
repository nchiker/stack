package org.stackdocs.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Recently deleted: restore a document or delete it for good. Entries expire after 30 days. */
public final class TrashActivity extends LibraryActivity {
    private LinearLayout body;
    private Button empty;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this);
        bar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        bar.addView(Ui.text(this, "Recently deleted", 18, false), new LinearLayout.LayoutParams(0, -2, 1));
        empty = Ui.button(this, "Empty", () -> {});
        empty.setVisibility(android.view.View.GONE);
        bar.addView(empty);
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        body = Ui.column(this);
        body.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, root);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override protected void onLibraryChanged() { refresh(); }

    private void refresh() {
        // Expired entries are deleted for good before the list is read, so the screen never shows one.
        work(() -> { app().library.purgeExpiredTrash(); return app().library.trashed(); }, this::render);
    }

    private void render(List<LibraryRepository.Trashed> items) {
        body.removeAllViews();
        empty.setVisibility(items.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        empty.setOnClickListener(view -> confirmEmpty(items.size()));
        TextView intro = Ui.text(this, items.isEmpty()
                ? "Nothing here. Documents you delete stay here for 30 days before they are deleted for good."
                : "Deleted documents stay here for 30 days. If you sync with a computer, they stay there until they are deleted for good.", 14, false);
        intro.setTextColor(Ui.MUTED);
        intro.setPadding(0, 0, 0, Ui.dp(this, 12));
        body.addView(intro);
        long now = System.currentTimeMillis();
        for (LibraryRepository.Trashed item : items) {
            long ageDays = Math.max(0, (now - item.deletedAt) / (24L * 60 * 60 * 1000));
            long leftDays = Math.max(0, (LibraryRepository.TRASH_KEEP_MILLIS - (now - item.deletedAt) + 24L * 60 * 60 * 1000 - 1) / (24L * 60 * 60 * 1000));
            String when = ageDays == 0 ? "Deleted today" : ageDays == 1 ? "Deleted yesterday" : "Deleted " + ageDays + " days ago";
            String left = leftDays <= 1 ? "Deleted for good within a day" : leftDays + " days left";
            body.addView(Ui.actionRow(this, Icons.Symbol.DOCUMENT, item.title, when + " · " + left, 0xFFF0E3EF, () -> options(item)));
            body.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        }
    }

    private void options(LibraryRepository.Trashed item) {
        new AlertDialog.Builder(this).setTitle(item.title)
                .setMessage("Restore it to your library, or delete it for good.")
                .setPositiveButton("Restore", (d, w) -> work(() -> { app().library.restoreDocument(item.id); return true; }, ok -> app().notifySyncChanged()))
                .setNeutralButton("Delete for good", (d, w) -> confirmDelete(item))
                .setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(LibraryRepository.Trashed item) {
        new AlertDialog.Builder(this).setTitle("Delete for good?")
                .setMessage("“" + item.title + "” and its information will be removed from this device. This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> work(() -> { app().library.deleteDocument(item.id); return true; }, ok -> app().notifyLibraryChanged()))
                .setNegativeButton("Cancel", null).show();
    }

    private void confirmEmpty(int count) {
        new AlertDialog.Builder(this).setTitle("Delete " + count + (count == 1 ? " document" : " documents") + " for good?")
                .setMessage("Everything in Recently deleted will be removed from this device. This cannot be undone.")
                .setPositiveButton("Delete all", (d, w) -> work(() -> app().library.purgeTrash(Long.MAX_VALUE), n -> app().notifyLibraryChanged()))
                .setNegativeButton("Cancel", null).show();
    }
}
