package org.stackdocs.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Sharing settings: the folder the shared file goes into, a quiet status line, and the way to pair with the partner. */
public final class PartnerShareActivity extends LibraryActivity {
    private static final int PICK = 81, FILEN = 82, RECEIVE_PICK = 84, RECEIVE_FILEN = 85;
    private LinearLayout body;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this);
        bar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        bar.addView(Ui.text(this, "Partner sharing", 18, false), new LinearLayout.LayoutParams(0, -2, 1));
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
        if (body == null) return;
        body.removeAllViews();
        TextView intro = Ui.text(this, "Documents set to “Share with partner” are saved, encrypted, in one folder your partner reads.", 14, false);
        intro.setTextColor(Ui.MUTED);
        body.addView(intro);
        body.addView(Ui.actionRow(this, Icons.Symbol.SHARE, "Invite partner", "Send a link to get started", 0xFFF9E8DC, () -> startActivity(new Intent(this, PartnerInviteActivity.class))));
        body.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        body.addView(Ui.actionRow(this, Icons.Symbol.DOCUMENTS, "Partner sharing folder", PartnerShare.label(this), 0xFFDCEADB, this::folder));
        String error = PartnerShare.lastError(this);
        if (PartnerShare.enabled(this) && !error.isEmpty()) {
            TextView line = Ui.text(this, error + " Stack will try again.", 13, false);
            line.setTextColor(Ui.MUTED);
            body.addView(line);
        }
        int skipped = PartnerShare.prefs(this).getInt("last_skipped", 0);
        if (PartnerShare.enabled(this) && skipped > 0) {
            TextView line = Ui.text(this, skipped + (skipped == 1 ? " document is" : " documents are") + " not in Filen yet, so not shared.", 13, false);
            line.setTextColor(Ui.MUTED);
            body.addView(line);
        }
        body.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        body.addView(Ui.actionRow(this, Icons.Symbol.CHECK, "Pair with partner", "Show a code for your partner to scan", 0xFFDCECF9, () -> startActivity(new Intent(this, PairPartnerActivity.class))));
        TextView photo = Ui.text(this, PairPartnerActivity.CODE_WARNING, 13, false);
        photo.setTextColor(Ui.MUTED);
        body.addView(photo);
        if (PartnerShare.enabled(this)) body.addView(Ui.button(this, "Stop using this folder", () -> { PartnerShare.disable(app()); refresh(); }));
        body.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        TextView receiveIntro = Ui.text(this, "Your partner's documents appear here, marked “Shared by”. Scan their code in Pair with partner, then choose their folder.", 14, false);
        receiveIntro.setTextColor(Ui.MUTED);
        body.addView(receiveIntro);
        body.addView(Ui.actionRow(this, Icons.Symbol.DOCUMENTS, "Partner shared folder", PartnerImport.label(this), 0xFFDCEADB, this::receiveFolder));
        String problem = PartnerImport.problem(this);
        if (!problem.isEmpty()) {
            TextView line = Ui.text(this, problem, 13, false);
            line.setTextColor(Ui.MUTED);
            body.addView(line);
        }
        if (PartnerImport.enabled(this)) body.addView(Ui.button(this, "Stop reading this folder", () -> { PartnerImport.disable(app()); refresh(); }));
    }

    /** One tap through Filen Files when it offers the share-folder screen; otherwise the system folder picker. */
    private void folder() {
        if (FilenFolder.shareAvailable(this)) {
            new AlertDialog.Builder(this).setTitle("Partner sharing folder")
                    .setMessage("Filen Files can set up a shared folder in Filen Drive for you.")
                    .setPositiveButton("Use Filen Drive", (d, w) -> filen())
                    .setNeutralButton("Other folder", (d, w) -> pick())
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        pick();
    }

    /** One tap through Filen Files when it offers the received-folder screen; otherwise the system folder picker (read access only). */
    private void receiveFolder() {
        if (FilenFolder.receivedAvailable(this)) {
            new AlertDialog.Builder(this).setTitle("Partner shared folder")
                    .setMessage("Use the folder your partner shared in Filen Drive.")
                    .setPositiveButton("Use Filen Drive", (d, w) -> {
                        try { if (!FilenFolder.receivedAvailable(this)) throw new ActivityNotFoundException(); startActivityForResult(FilenFolder.receivedRequest(), RECEIVE_FILEN); } catch (ActivityNotFoundException error) { receivePick(); }
                    })
                    .setNeutralButton("Other folder", (d, w) -> receivePick())
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        receivePick();
    }

    private void receivePick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, RECEIVE_PICK); }
        catch (ActivityNotFoundException error) { new AlertDialog.Builder(this).setMessage("No folder picker found.").setPositiveButton("Got it", null).show(); }
    }

    private void filen() {
        try { if (!FilenFolder.shareAvailable(this)) throw new ActivityNotFoundException(); startActivityForResult(FilenFolder.shareRequest(), FILEN); } catch (ActivityNotFoundException error) { pick(); }
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, PICK); }
        catch (ActivityNotFoundException error) { new AlertDialog.Builder(this).setMessage("No folder picker found.").setPositiveButton("Got it", null).show(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK && request != FILEN && request != RECEIVE_PICK && request != RECEIVE_FILEN) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            String why = request == FILEN || request == RECEIVE_FILEN ? FilenFolder.reason(data) : "";
            if (!why.isEmpty()) new AlertDialog.Builder(this).setTitle("Filen Drive").setMessage(why).setPositiveButton("Got it", null).setNeutralButton("Other folder", (d, w) -> { if (request == RECEIVE_FILEN) receivePick(); else pick(); }).show();
            return;
        }
        if ((request == FILEN || request == RECEIVE_FILEN) && !FilenFolder.treeTrusted(this, data)) {
            new AlertDialog.Builder(this).setTitle("Filen Drive").setMessage("Filen Files is not available. Use another folder.").setPositiveButton("Got it", null)
                    .setNeutralButton("Other folder", (d, w) -> { if (request == RECEIVE_FILEN) receivePick(); else pick(); }).show();
            return;
        }
        if (request == RECEIVE_PICK || request == RECEIVE_FILEN) { whenAllowed(() -> chosenReceive(data)); return; }
        whenAllowed(() -> chosen(data));
    }

    private void failedFolder() {
        new AlertDialog.Builder(this).setMessage("Could not keep access to that folder. Choose another.").setPositiveButton("Got it", null).show();
    }

    /** Keeps the read permission for the folder the partner shares, then imports from it (shared with the pairing screen). */
    private void chosenReceive(Intent data) { PartnerGrant.receivedFolder(app(), data, this::refresh, this::failedFolder); }

    /** Keeps the folder permission, then publishes (shared with the pairing screen). */
    private void chosen(Intent data) { PartnerGrant.sharedFolder(app(), data, this::refresh, this::failedFolder); }
}
