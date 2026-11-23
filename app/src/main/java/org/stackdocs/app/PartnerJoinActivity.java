package org.stackdocs.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "I'm joining my partner": four steps, one idea each (install the apps, sign in to Filen Files, scan the partner's code, allow the folder),
 * then a finish screen. The decisions live in {@link PartnerJourney}; this class only shows them. The owner's email and pairing nonce from the
 * scanned code are kept in memory for the Allow step and never stored (see {@link PartnerQr}).
 */
public final class PartnerJoinActivity extends LibraryActivity {
    private static final int SCAN = 91, ALLOW = 92;
    private LinearLayout body;
    private TextView progressLine;
    private int step = PartnerJourney.INSTALL;
    private boolean signedInSeen;
    private String message = "", installNote = "";
    private String ownerEmail, nonce;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Companions.Listener listener = new Companions.Listener() {
        @Override public void progress(String text) { main.post(() -> { installNote = text; render(); }); }
        @Override public void done(Companions.Problem problem) { main.post(() -> { installNote = problem == null ? "Confirm in the Android prompt." : ""; render(); }); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this);
        bar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        bar.addView(Ui.text(this, "Joining your partner", 18, false), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        body = Ui.column(this);
        body.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, root);
    }

    @Override protected void onResume() { super.onResume(); load(); }
    @Override protected void onLibraryChanged() { load(); }
    @Override protected void onDestroy() { ownerEmail = null; nonce = null; super.onDestroy(); }

    /** Works out which steps are already finished (signing in is asked of Filen Files, off the main thread), then shows the first open one. */
    private void load() {
        new Thread(() -> {
            boolean signed = PartnerJourney.signedInConfirmed(this) || FilenFolder.ownerEmail(this) != null;
            main.post(() -> { if (isFinishing() || isDestroyed()) return; signedInSeen = signed; resolveAndShow(); carryOn(); });
        }, "Stack join check").start();
    }

    private void resolveAndShow() {
        boolean apps = PartnerJourney.appsToInstall(app -> Companions.stateOf(this, app)).isEmpty();
        boolean done = PartnerJourney.setupDone(this) && PartnerImport.enabled(this);
        step = done ? PartnerJourney.DONE
                : PartnerJourney.resolve(new PartnerJourney.Done(apps, signedInSeen, PartnerJourney.scanned(this) && partnerKeySaved(), PartnerImport.enabled(this)));
        if (step <= PartnerJourney.STEPS) PartnerJourney.saveStep(this, step);
        render();
    }

    /** The scanned answer is only believed while the partner's key is really saved. */
    private boolean partnerKeySaved() {
        byte[] key = null;
        try { key = PartnerKeys.receiveKey(this, PartnerKeys.DEFAULT_PARTNER); return key != null; }
        catch (Exception unreadable) { return false; }
        finally { if (key != null) Arrays.fill(key, (byte) 0); }
    }

    /** Install both: after each result, start the next app that still needs something. */
    private void carryOn() {
        if (Companions.busy() || Companions.waitingForAndroid(this)) return;
        List<String> waiting = Companions.pending(this);
        if (waiting.isEmpty()) return;
        Set<String> satisfied = new HashSet<>();
        for (Companions.App app : Companions.ALL) if (!Companions.needsAction(Companions.stateOf(this, app))) satisfied.add(app.pkg);
        String next = Companions.next(waiting, satisfied);
        if (next == null) { Companions.setPending(this, new ArrayList<>()); return; }
        for (Companions.App app : Companions.ALL) if (app.pkg.equals(next)) { Companions.start(this, app, listener); return; }
    }

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        String progress = PartnerJourney.progress(step);
        if (!progress.isEmpty()) { progressLine = Ui.text(this, progress, 13, false); progressLine.setTextColor(Ui.MUTED); body.addView(progressLine); }
        switch (step) {
            case PartnerJourney.INSTALL: install(); break;
            case PartnerJourney.SIGN_IN: signIn(); break;
            case PartnerJourney.SCAN: scanStep(); break;
            case PartnerJourney.ALLOW: allow(); break;
            default: showDone(); break;
        }
        if (!message.isEmpty()) { TextView line = Ui.text(this, message, 14, true); line.setPadding(0, Ui.dp(this, 12), 0, 0); body.addView(line); }
    }

    private void title(String text, String words) {
        TextView t = Ui.text(this, text, 22, true);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        body.addView(t);
        TextView w = Ui.text(this, words, 15, false);
        w.setPadding(0, 0, 0, Ui.dp(this, 12));
        body.addView(w);
    }

    private void learnMore(String heading, String text) {
        body.addView(Ui.button(this, "Learn more", () -> new AlertDialog.Builder(this).setTitle(heading).setMessage(text).setPositiveButton("Got it", null).show()));
    }

    // 1. Install

    private void install() {
        title("Install two apps", "Stack works with Filen Files and Ami Pro. Android asks you to confirm each one.");
        boolean busy = Companions.busy() || Companions.waitingForAndroid(this);
        for (Companions.App app : PartnerJourney.needed()) {
            Companions.State state = Companions.stateOf(this, app);
            LinearLayout row = Ui.column(this);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            row.addView(Ui.text(this, app.name, 16, true));
            TextView status = Ui.text(this, Companions.label(state, Companions.installedName(this, app), Companions.latest(this, app)), 13, false);
            status.setTextColor(Ui.MUTED);
            row.addView(status);
            body.addView(row);
        }
        String note = installNote.isEmpty() ? Companions.note(this) : installNote;
        if (!note.isEmpty()) { TextView line = Ui.text(this, note, 14, true); line.setPadding(0, Ui.dp(this, 8), 0, 0); body.addView(line); }
        android.widget.Button go = Ui.button(this, "Install both", () -> {
            Companions.setNote(this, "");
            Companions.setPending(this, PartnerJourney.appsToInstall(app -> Companions.stateOf(this, app)));
            carryOn();
        });
        go.setEnabled(!busy);
        body.addView(go);
        if (busy) body.addView(Ui.button(this, "Cancel", () -> AppUpdater.canceled = true));
    }

    // 2. Sign in

    private void signIn() {
        title("Sign in to Filen Files", "Open Filen Files and sign in, or make an account. Then come back here.");
        body.addView(Ui.button(this, "Open Filen Files", () -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(FilenFolder.PACKAGE);
            if (launch == null) { message = "Filen Files is not installed."; render(); return; }
            try { startActivity(launch); } catch (ActivityNotFoundException error) { message = "Filen Files is not installed."; render(); }
        }));
        body.addView(Ui.button(this, "I'm signed in", () -> { PartnerJourney.setSignedIn(this); message = ""; signedInSeen = true; resolveAndShow(); }));
        learnMore("Making an account", "Filen emails you a link when you make an account. Open it, then come back here.");
    }

    // 3. Scan

    private void scanStep() {
        title("Scan your partner's code", "On their phone, open Invite partner and tap Pair with partner. Then scan the code you see.");
        body.addView(Ui.button(this, "Scan code", () -> {
            try { startActivityForResult(new Intent(this, PairingQrActivity.class).putExtra("share_mode", true), SCAN); }
            catch (ActivityNotFoundException error) { message = "The scanner is not available."; render(); }
        }));
    }

    private void afterScan(Intent data) {
        String hex = data.getStringExtra("share_key");
        if (hex == null || !hex.matches("[0-9a-fA-F]{64}")) return;
        String email = data.getStringExtra("owner_email"), pair = data.getStringExtra("pair_nonce");
        final String scannedEmail = PartnerQr.validEmail(email) ? email : null, scannedNonce = PartnerQr.validNonce(pair) ? pair : null;
        final byte[] key = BackupEnvelope.parseRecoveryKey(hex);
        final String print = PartnerKeys.fingerprint(key);
        new Thread(() -> {
            boolean replacing;
            byte[] existing = null;
            try { existing = PartnerKeys.receiveKey(this, PartnerKeys.DEFAULT_PARTNER); replacing = existing != null; }
            catch (Exception unreadable) { replacing = false; }
            finally { if (existing != null) Arrays.fill(existing, (byte) 0); }
            final boolean ask = replacing;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) { Arrays.fill(key, (byte) 0); return; }
                if (!ask) { confirmTrust(key, print, scannedEmail, scannedNonce); return; }
                new AlertDialog.Builder(this).setTitle("Replace key?")
                        .setMessage("Documents shared with the old key stop updating. New code: " + print + ".")
                        .setCancelable(false)
                        .setPositiveButton("Replace", (d, w) -> confirmTrust(key, print, scannedEmail, scannedNonce))
                        .setNegativeButton("Cancel", (d, w) -> Arrays.fill(key, (byte) 0)).show();
            });
        }, "Stack join key check").start();
    }

    /** The last step before the scanned key is saved. Cancel changes nothing. */
    private void confirmTrust(byte[] key, String print, String email, String pair) {
        EditText field = new EditText(this);
        field.setSingleLine(true); field.setText(PartnerImport.name(this)); field.selectAll();
        new AlertDialog.Builder(this).setTitle("Trust this code?")
                .setMessage((email == null ? "" : "Filen account: " + email + "\n\n")
                        + "Short code: " + print + "\nIt must match your partner's phone.\n\nYour partner's name, shown as \"Shared by …\":")
                .setView(field).setCancelable(false)
                .setPositiveButton("Trust this code", (d, w) -> {
                    String name = field.getText().toString();
                    new Thread(() -> {
                        boolean saved = false;
                        try {
                            PartnerKeys.setReceiveKey(this, PartnerKeys.DEFAULT_PARTNER, key);
                            PartnerImport.setName(this, name);
                            PartnerImport.keyChanged(this);
                            PartnerJourney.setScanned(this);
                            saved = true;
                        } catch (Exception error) { /* reported below; nothing was changed for the person */ }
                        finally { Arrays.fill(key, (byte) 0); }
                        final boolean ok = saved;
                        main.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (!ok) { message = "Could not save the code. Nothing changed."; render(); return; }
                            message = ""; ownerEmail = email; nonce = pair;
                            resolveAndShow();
                            if (step == PartnerJourney.ALLOW && route() == PartnerJourney.Route.FILEN) startAllow();   // by hand: the person accepts the share in Filen Files first
                        });
                    }, "Stack join key").start();
                })
                .setNegativeButton("Cancel", (d, w) -> Arrays.fill(key, (byte) 0)).show();
    }

    // 4. Allow

    private PartnerJourney.Route route() { return PartnerJourney.grantRoute(FilenFolder.useFilen(FilenFolder.receivedAvailable(this)), ownerEmail); }

    private void allow() {
        boolean filen = route() == PartnerJourney.Route.FILEN;
        if (filen) title("Allow your partner's folder", "Filen Files asks you to allow it. Tap Allow.");
        else title("Choose your partner's folder", "In Filen Files, open Shared, then Shared with me. Then choose that folder here.");
        body.addView(Ui.button(this, filen ? "Open Filen Files" : "Choose folder", this::startAllow));
        if (!filen) learnMore("Folder not there?", "Your partner has to be your Filen contact first. Accept their contact request in Filen's web app.");
    }

    private void startAllow() {
        try {
            if (route() == PartnerJourney.Route.FILEN) {
                startActivityForResult(FilenFolder.pairReceivedRequest(ownerEmail, nonce), ALLOW);
            } else {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), ALLOW);
            }
        } catch (ActivityNotFoundException error) { message = "No folder picker found."; render(); }
    }

    private void allowed(int result, Intent data) {
        boolean filen = route() == PartnerJourney.Route.FILEN;
        boolean granted = result == RESULT_OK && data != null && data.getData() != null;
        if (!granted) {
            message = filen ? FilenFolder.statusLine(false, false, data == null ? null : data.getStringExtra(FilenFolder.EXTRA_ERROR), data == null ? null : data.getStringExtra(FilenFolder.EXTRA_MESSAGE)) : "";
            render();
            return;
        }
        if (filen && !FilenFolder.treeTrusted(this, data)) { message = "Filen Files is not available. Choose a folder instead."; ownerEmail = null; render(); return; }
        whenAllowed(() -> PartnerGrant.receivedFolder(app(), data, () -> {
            PartnerJourney.setSetupDone(this); PartnerJourney.saveStep(this, PartnerJourney.DONE);
            message = ""; step = PartnerJourney.DONE; render();
        }, () -> { message = "Could not keep access to that folder. Try again."; render(); }));
    }

    // Finish

    private void showDone() {
        title("You're all set", "Your partner's documents will appear in Stack.");
        body.addView(Ui.button(this, "Done", this::finish));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SCAN && result == RESULT_OK && data != null) afterScan(data);
        else if (request == ALLOW) allowed(result, data);
    }
}
