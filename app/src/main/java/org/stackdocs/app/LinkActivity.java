package org.stackdocs.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** A linked document: what Stack saved from the owner app (preview and text), and a button that opens the real file there. */
public final class LinkActivity extends LibraryActivity {
    private static final int MAX_TEXT_SHOWN = 50_000;
    private static final java.util.concurrent.ExecutorService previews = java.util.concurrent.Executors.newSingleThreadExecutor();
    private String linkId;
    private LinkStore.Link link;
    private TextView title, info, notice;
    private LinearLayout previewBox, textBox;
    private android.widget.Button open;
    private int generation;
    private boolean checkedThisVisit;
    private android.widget.Button update;
    private android.widget.Switch partner;
    private TextView partnerNote;
    private boolean binding;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        linkId = getIntent().getStringExtra("link_id");
        if (linkId == null) { finish(); return; }
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this);
        bar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        title = Ui.text(this, "Linked document", 18, false);
        title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(Ui.iconButton(this, Icons.Symbol.MORE, "More actions", this::more), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 32));
        info = Ui.text(this, "", 14, false); info.setTextColor(Ui.MUTED);
        body.addView(info);
        open = Ui.primary(this, "Open", this::openOwner);
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)); openParams.topMargin = Ui.dp(this, 12);
        body.addView(open, openParams);
        update = Ui.button(this, "Update saved copy", this::updateSaved);
        update.setVisibility(View.GONE);
        body.addView(update);
        partner = new android.widget.Switch(this);
        partner.setText("Share with partner"); partner.setTextSize(16); partner.setTextColor(Ui.INK);
        partner.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        partner.setOnCheckedChangeListener((button, on) -> { if (!binding) partnerChanged(on); });
        body.addView(partner, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        partnerNote = Ui.text(this, "", 13, false); partnerNote.setTextColor(Ui.MUTED); partnerNote.setVisibility(View.GONE);
        body.addView(partnerNote);
        notice = Ui.text(this, "", 14, false); notice.setTextColor(0xFFB3261E); notice.setVisibility(View.GONE); notice.setPadding(0, Ui.dp(this, 8), 0, 0);
        body.addView(notice);
        TextView previewHeader = Ui.text(this, "Saved preview", 14, true); previewHeader.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 8));
        body.addView(previewHeader);
        previewBox = Ui.column(this);
        body.addView(previewBox);
        TextView textHeader = Ui.text(this, "Saved text", 14, true); textHeader.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 8));
        body.addView(textHeader);
        textBox = Ui.column(this);
        body.addView(textBox);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, root);
    }

    @Override protected void onResume() { super.onResume(); load(); }
    @Override protected void onLibraryChanged() { load(); }

    private static final class Loaded { LinkStore.Link link; String text; File preview; boolean failed; }

    private void load() {
        final int mine = ++generation;
        read(() -> {
            Loaded out = new Loaded();
            out.link = app().library.links.get(linkId);
            if (out.link != null) {
                out.text = app().library.links.text(linkId);
                out.preview = app().library.links.preview(linkId);
                out.failed = app().library.links.previewFailed(linkId);
            }
            return out;
        }, loaded -> { if (mine == generation) show(loaded); });
    }

    private void show(Loaded loaded) {
        if (loaded.link == null || LinkStore.TRASHED.equals(loaded.link.state)) { finish(); return; }
        link = loaded.link;
        title.setText(link.title);
        String owner = LinkOpener.ownerName(link.owner);
        StringBuilder text = new StringBuilder("Linked document · ").append(owner);
        if (link.foreign) text.append(" · Shared by ").append(link.sharedBy);
        text.append("\nSaved from ").append(owner).append(" · changed ").append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(link.modified)));
        if (link.foreign) text.append("\nA copy shared with you. Open it with Filen Files; the saved text and preview below are read-only.");
        else if (LinkStore.MISSING.equals(link.state)) text.append("\n").append(owner).append(" can't find this document. It may have been moved or deleted. The saved text and preview stay here; if it stays missing, Stack moves it to Recently deleted after 30 days.");
        else if (LinkStore.UNREACHABLE.equals(link.state)) text.append("\nCan't reach ").append(owner).append(" right now. Nothing was removed; the saved text and preview are still here.");
        if (link.shared && !link.foreign) {
            text.append("\nShared");
            if (!link.locators.contains("\"filen\"")) text.append(" — but this document is not in Filen, so your partner cannot open it. Move it into a shared Filen folder.");
        }
        if (link.isProtected) text.append("\nPassword-protected in ").append(owner).append(" — the saved text and preview here are not protected by that password.");
        info.setText(text);
        binding = true; partner.setChecked(link.partnerShare > 0); binding = false;
        partner.setVisibility(link.foreign ? View.GONE : View.VISIBLE);
        partnerNote.setVisibility(View.GONE);
        if (link.partnerShare > 0 && !link.foreign) {
            if (!link.locators.contains("\"filen\"")) { partnerNote.setText("This document is not in Filen, so your partner cannot open it. It is not shared until you move it into a shared Filen folder."); partnerNote.setVisibility(View.VISIBLE); }
            else if (!PartnerShare.enabled(this)) { partnerNote.setText("Choose a sharing folder in Settings, under Partner sharing folder, so this can reach your partner."); partnerNote.setVisibility(View.VISIBLE); }
        }
        open.setText(link.foreign ? "Open in Filen Files" : "Open in " + owner);
        if (link.foreign) update.setVisibility(View.GONE);
        else if (!checkedThisVisit) { checkedThisVisit = true; checkNow(false); }
        previewBox.removeAllViews();
        textBox.removeAllViews();
        if (loaded.preview == null) addNote(previewBox, "No preview was saved.");
        else if (loaded.failed) addNote(previewBox, "This preview could not be drawn, so Stack will not try again. The saved text below is still available.");
        else drawPreview(loaded.preview);
        String shown = LinkPreview.displayText(loaded.text, MAX_TEXT_SHOWN);
        if (shown.trim().isEmpty()) addNote(textBox, "No text was saved.");
        else {
            TextView body = Ui.text(this, shown, 15, false);
            body.setTextIsSelectable(true);
            textBox.addView(body);
            if (loaded.text.length() > shown.length()) addNote(textBox, "Showing the first " + MAX_TEXT_SHOWN + " characters. All of it is searchable.");
        }
    }

    private void addNote(LinearLayout box, String message) {
        TextView note = Ui.text(this, message, 14, false); note.setTextColor(Ui.MUTED); box.addView(note);
    }

    private void drawPreview(File pdf) {
        final int mine = generation;
        final int width = getResources().getDisplayMetrics().widthPixels - 2 * Ui.dp(this, 20);
        addNote(previewBox, "Drawing the preview…");
        previews.execute(() -> {
            List<Bitmap> pages = null;
            boolean failed = false;
            try {
                app().library.links.previewAttempt(linkId);
                pages = LinkPreview.render(pdf, width);
                app().library.links.previewSucceeded(linkId);
            } catch (Exception error) { failed = true; }
            final List<Bitmap> drawn = pages; final boolean bad = failed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || mine != generation) { if (drawn != null) for (Bitmap b : drawn) b.recycle(); return; }
                previewBox.removeAllViews();
                if (bad || drawn == null) { addNote(previewBox, "This preview could not be drawn, so Stack will not try again. The saved text below is still available."); return; }
                for (Bitmap bitmap : drawn) {
                    ImageView page = new ImageView(this);
                    page.setImageBitmap(bitmap); page.setAdjustViewBounds(true);
                    page.setBackground(Ui.outlined(this, 0xFFFFFFFF, 0xFFE8EAED, 4, 1));
                    page.setContentDescription("Saved preview page");
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = Ui.dp(this, 8);
                    previewBox.addView(page, params);
                }
            });
        });
    }

    private void openOwner() {
        if (link == null) return;
        notice.setVisibility(View.GONE);
        final LinkStore.Link target = link;
        if (target.foreign) {
            // Never the owner app (it is the partner's): Filen Files resolves the shared file by its Filen locator.
            Intent intent = FilenOpen.request(this, target.locators);
            try { if (intent == null) throw new android.content.ActivityNotFoundException(); startActivity(intent); }
            catch (RuntimeException error) { notice.setText(FilenOpen.MISSING_MESSAGE); notice.setVisibility(View.VISIBLE); }
            return;
        }
        open.setEnabled(false);
        read(() -> { try { return LinkOpener.request(this, target, "edit"); } catch (LinkOpener.Failure failure) { return failure; } }, result -> {
            open.setEnabled(true);
            if (result instanceof LinkOpener.Failure) { notice.setText(((Exception) result).getMessage()); notice.setVisibility(View.VISIBLE); return; }
            try { startActivity((android.content.Intent) result); }
            catch (RuntimeException error) { notice.setText(LinkOpener.ownerName(target.owner) + " could not be started."); notice.setVisibility(View.VISIBLE); }
        }, () -> open.setEnabled(true));
    }

    /** Asks the owner whether the file still exists. A failed ask changes nothing but the grey badge. */
    private void checkNow(boolean manual) {
        if (link == null || link.foreign) return;
        final LinkStore.Link target = link;
        check(() -> LinkChecker.check(app(), target), result -> {
            if (!result.busy) load();
            if (result.busy) { if (manual) { notice.setText(LinkOpener.ownerName(target.owner) + " is still busy. Try again in a moment."); notice.setVisibility(View.VISIBLE); } return; }
            update.setVisibility(result.changed ? View.VISIBLE : View.GONE);
            if (result.changed) { notice.setText("This document changed in " + LinkOpener.ownerName(target.owner) + " since Stack saved it."); notice.setVisibility(View.VISIBLE); }
            else if (manual) { notice.setText(LinkStore.LIVE.equals(result.state) ? "Checked: the document is there and up to date." : "Checked."); notice.setVisibility(View.VISIBLE); }
        });
    }

    /** Stack's own switch (not the owner app's "shared"). A protected link needs the person to confirm what the partner will be able to read. */
    private void partnerChanged(boolean on) {
        if (link == null) return;
        final LinkStore.Link target = link;
        if (!on) { setPartner(target, 0); return; }
        if (target.isProtected) {
            binding = true; partner.setChecked(false); binding = false;
            new AlertDialog.Builder(this).setTitle("Share a protected document?")
                .setMessage("“" + target.title + "” is password-protected in " + LinkOpener.ownerName(target.owner) + ". The text and preview Stack saved are not protected by that password, so your partner would be able to read them.")
                .setPositiveButton("Share anyway", (d, w) -> setPartner(target, 2))
                .setNegativeButton("Cancel", null).show();
            return;
        }
        setPartner(target, 1);
    }

    private void setPartner(LinkStore.Link target, int mode) {
        work(() -> app().library.links.setPartnerShare(target.id, mode), ok -> { app().notifySyncChanged(); load(); });
    }

    private void updateSaved() {
        if (link == null) return;
        final LinkStore.Link target = link;
        update.setEnabled(false);
        check(() -> LinkChecker.refresh(app(), target), problem -> {
            update.setEnabled(true);
            if (problem == null) { update.setVisibility(View.GONE); notice.setVisibility(View.GONE); } else { notice.setText(problem); notice.setVisibility(View.VISIBLE); }
        });
    }

    private void more() {
        if (link == null) return;
        if (link.foreign) {
            new AlertDialog.Builder(this).setTitle(link.title).setItems(new String[]{"Rename", "Remove from Stack"}, (d, which) -> { if (which == 0) rename(); else confirmRemove(); }).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(link.title).setItems(new String[]{"Check now", "Rename", "Remove from Stack"}, (d, which) -> { if (which == 0) { notice.setVisibility(View.GONE); checkNow(true); } else if (which == 1) rename(); else confirmRemove(); }).show();
    }

    private void rename() {
        EditText field = new EditText(this); field.setText(link.title); field.setSingleLine(true); field.selectAll();
        new AlertDialog.Builder(this).setTitle("Rename").setView(field)
            .setPositiveButton("Save", (d, w) -> {
                String name = field.getText().toString().replaceAll("[\\p{Cc}\\u202a-\\u202e\\u2066-\\u2069\\u200e\\u200f\\u061c]", "").trim();
                if (name.isEmpty() || name.length() > LinkRecord.MAX_TITLE) return;
                work(() -> { app().library.links.rename(linkId, name); return true; }, ok -> app().notifySyncChanged());
            }).setNegativeButton("Cancel", null).show();
    }

    private void confirmRemove() {
        if (link.foreign) {
            new AlertDialog.Builder(this).setTitle("Remove from Stack?")
                .setMessage("Only the copy on this phone moves to Recently deleted for 30 days. " + link.sharedBy + "'s document is not touched.")
                .setPositiveButton("Remove", (d, w) -> work(() -> { app().library.links.trash(linkId, "user"); return true; }, ok -> { app().notifySyncChanged(); finish(); }))
                .setNegativeButton("Cancel", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Remove from Stack?")
            .setMessage("“" + link.title + "” moves to Recently deleted for 30 days, then it is deleted for good. The document itself is not touched; it stays in " + LinkOpener.ownerName(link.owner) + " and wherever it is stored.")
            .setPositiveButton("Remove", (d, w) -> work(() -> { app().library.links.trash(linkId, "user"); return true; }, ok -> { app().notifySyncChanged(); finish(); }))
            .setNegativeButton("Cancel", null).show();
    }
}
