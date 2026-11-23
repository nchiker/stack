package org.stackdocs.app;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Arrays;
import java.util.EnumMap;

/**
 * Shows the key this phone shares with, as a QR code the partner scans in person, plus a short fingerprint both people can read aloud.
 * The screen is excluded from screenshots and recents. "Stop sharing / make a new key" replaces the key and publishes again with it.
 */
public final class PairPartnerActivity extends LibraryActivity {
    static final String CODE_WARNING = "Anyone who photographs this code could read what you share until you make a new code. Making a new code does not remove copies already stored in Filen.";
    private static final int SCAN = 83, SHARE = 86, RECEIVE = 87;
    private ImageView code;
    private TextView fingerprint, status, waiting;
    private android.widget.Button tryAgain;
    /** What "Try again" re-starts: the same Filen Files step, with no new scan. Held in memory only (the owner email inside it is never stored). */
    private Runnable retry;
    private Bitmap shown;
    /** This pairing screen's nonce: shown in the QR, passed to Filen Files with the share request, gone when the screen closes. Memory only, never logged. */
    private volatile String pairNonce;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        pairNonce = PartnerQr.newNonce();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this);
        bar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        bar.addView(Ui.text(this, "Pair with partner", 18, false), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 24));
        TextView words = Ui.text(this, "Ask your partner to scan this code in Stack on their phone. Only someone who has scanned it can open the documents you share.", 15, false);
        body.addView(words);
        code = new ImageView(this);
        code.setBackgroundColor(Color.WHITE);
        code.setContentDescription("Pairing code for your partner to scan");
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, Ui.dp(this, 300)); size.topMargin = Ui.dp(this, 16);
        body.addView(code, size);
        fingerprint = Ui.text(this, "", 18, true);
        fingerprint.setGravity(android.view.Gravity.CENTER);
        fingerprint.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        body.addView(fingerprint);
        TextView read = Ui.text(this, "Your partner's phone shows the same short code after scanning. If the two differ, do not continue.", 13, false);
        read.setTextColor(Ui.MUTED);
        body.addView(read);
        TextView warning = Ui.text(this, CODE_WARNING, 13, false);
        warning.setTextColor(Ui.MUTED);
        body.addView(warning);
        status = Ui.text(this, "", 14, false);
        status.setTextColor(Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 8), 0, 0);
        body.addView(status);
        body.addView(Ui.button(this, "Stop sharing / make a new key", this::confirmRotate));
        // Always visible (when Filen Files offers the step) rather than enabled by a "partner scanned" signal: this phone has no way to know that
        // the partner scanned, because the partner's scan only changes the partner's phone. Filen Files itself waits for the partner and says so.
        if (FilenFolder.useFilen(FilenFolder.shareAvailable(this))) {
            TextView next = Ui.text(this, "After your partner has scanned the code, continue here to share the folder in Filen Drive with them.", 14, false);
            next.setTextColor(Ui.MUTED);
            next.setPadding(0, Ui.dp(this, 16), 0, 0);
            body.addView(next);
            body.addView(Ui.button(this, "Partner scanned it, continue", this::startShare));
        }
        waiting = Ui.text(this, FilenFolder.WAITING_LINE, 14, false);
        waiting.setTextColor(Ui.MUTED);
        waiting.setPadding(0, Ui.dp(this, 12), 0, 0);
        waiting.setVisibility(android.view.View.GONE);
        body.addView(waiting);
        tryAgain = Ui.button(this, "Try again", () -> { if (retry != null) retry.run(); });
        tryAgain.setVisibility(android.view.View.GONE);
        body.addView(tryAgain);
        TextView receive = Ui.text(this, "To receive your partner's documents, scan the code on their phone.", 15, false);
        receive.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 4));
        body.addView(receive);
        body.addView(Ui.button(this, "Scan my partner's code", this::scan));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, root);
    }

    @Override protected void onResume() { super.onResume(); loadKey(); }

    @Override protected void onDestroy() { pairNonce = null; super.onDestroy(); }

    @Override protected void onPause() {
        // The code is not kept on screen while the app is in the background.
        if (code != null) code.setImageDrawable(null);
        if (shown != null) { shown.recycle(); shown = null; }
        super.onPause();
    }

    private void loadKey() {
        status.setText(PartnerShare.enabled(this) ? "" : "Choose a sharing folder in Settings, under Partner sharing folder, so documents can be shared.");
        new Thread(() -> {
            byte[] key = null;
            try {
                key = PartnerKeys.ensureSendKey(this, PartnerKeys.DEFAULT_PARTNER);
                show(key);
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Stack could not prepare the code on this phone. Try “Stop sharing / make a new key”."));
            } finally { if (key != null) Arrays.fill(key, (byte) 0); }
        }, "Stack pairing code").start();
    }

    private void show(byte[] key) throws Exception {
        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        // The owner email is added only when Filen Files answers; it goes into the QR image and nowhere else (see PartnerQr).
        BitMatrix matrix = new QRCodeWriter().encode(PartnerQr.encode(key, FilenFolder.ownerEmail(this), pairNonce), BarcodeFormat.QR_CODE, 0, 0, hints);
        int w = matrix.getWidth(), h = matrix.getHeight(), scale = Math.max(1, 600 / w);
        int[] pixels = new int[w * scale * h * scale];
        for (int y = 0; y < h * scale; y++) for (int x = 0; x < w * scale; x++) pixels[y * w * scale + x] = matrix.get(x / scale, y / scale) ? Color.BLACK : Color.WHITE;
        Bitmap bitmap = Bitmap.createBitmap(pixels, w * scale, h * scale, Bitmap.Config.ARGB_8888);
        String print = PartnerKeys.fingerprint(key);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) { bitmap.recycle(); return; }
            if (shown != null) shown.recycle();
            shown = bitmap; code.setImageBitmap(bitmap); fingerprint.setText(print);
        });
    }

    private void scan() {
        try { startActivityForResult(new Intent(this, PairingQrActivity.class).putExtra("share_mode", true), SCAN); }
        catch (ActivityNotFoundException error) { status.setText("The scanner is not available."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SHARE || request == RECEIVE) { filenResult(request, result, data); return; }
        if (request != SCAN || result != RESULT_OK || data == null) return;
        String hex = data.getStringExtra("share_key");
        if (hex == null || !hex.matches("[0-9a-fA-F]{64}")) return;
        String scannedEmail = data.getStringExtra("owner_email");
        if (!PartnerQr.validEmail(scannedEmail)) scannedEmail = null;
        String scannedNonce = data.getStringExtra("pair_nonce");
        if (!PartnerQr.validNonce(scannedNonce)) scannedNonce = null;
        final String ownerEmail = scannedEmail, nonce = scannedNonce;
        final byte[] key = BackupEnvelope.parseRecoveryKey(hex);
        final String print = PartnerKeys.fingerprint(key);
        new Thread(() -> {
            boolean replacing;
            byte[] existing = null;
            try { existing = PartnerKeys.receiveKey(this, PartnerKeys.DEFAULT_PARTNER); replacing = existing != null; }
            catch (Exception unreadable) { replacing = false; }
            finally { if (existing != null) Arrays.fill(existing, (byte) 0); }
            final boolean ask = replacing;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) { Arrays.fill(key, (byte) 0); return; }
                if (!ask) { confirmTrust(key, print, ownerEmail, nonce); return; }
                new AlertDialog.Builder(this).setTitle("Replace your partner's key?")
                        .setMessage("This phone already has a key from your partner. If you replace it, documents shared with the old key stop updating. The new code is " + print + ".")
                        .setCancelable(false)
                        .setPositiveButton("Replace", (d, w) -> confirmTrust(key, print, ownerEmail, nonce))
                        .setNegativeButton("Cancel", (d, w) -> Arrays.fill(key, (byte) 0)).show();
            });
        }, "Stack partner key check").start();
    }

    /** The last step before the scanned key is saved: the person sees the code and, when there is one, the Filen email in the QR. Cancel changes nothing and starts nothing. */
    private void confirmTrust(byte[] key, String print, String ownerEmail, String nonce) {
        EditText field = new EditText(this);
        field.setSingleLine(true); field.setText(PartnerImport.name(this)); field.selectAll();
        new AlertDialog.Builder(this).setTitle("Trust this code?")
                .setMessage((ownerEmail == null ? "" : "Filen account in the code: " + ownerEmail + "\n\n")
                        + "Short code: " + print + "\nYour partner's phone should show the same short code. If it differs, cancel.\n\nName for your partner, shown on their documents as \"Shared by …\":")
                .setView(field).setCancelable(false)
                .setPositiveButton("Trust this code", (d, w) -> {
                    String name = field.getText().toString();
                    new Thread(() -> {
                        try {
                            PartnerKeys.setReceiveKey(this, PartnerKeys.DEFAULT_PARTNER, key);
                            PartnerImport.setName(this, name);
                            PartnerImport.keyChanged(this);
                            PartnerImport.importNow(app());
                            runOnUiThread(() -> status.setText("Paired. Documents your partner shares will appear in Stack."
                                    + (PartnerImport.enabled(this) ? "" : " Choose the folder your partner shares under Settings, Partner sharing, Partner shared folder.")));
                            if (ownerEmail != null) runOnUiThread(() -> startReceived(ownerEmail, nonce));
                        } catch (Exception error) {
                            runOnUiThread(() -> status.setText("Stack could not save the code on this phone. Nothing was changed."));
                        } finally { java.util.Arrays.fill(key, (byte) 0); }
                    }, "Stack partner key").start();
                })
                .setNegativeButton("Cancel", (d, w) -> java.util.Arrays.fill(key, (byte) 0)).show();
    }

    private void clearWaiting() { retry = null; waiting.setVisibility(android.view.View.GONE); tryAgain.setVisibility(android.view.View.GONE); }

    private void showWaiting(String line, Runnable again) {
        retry = again; waiting.setText(line); waiting.setVisibility(android.view.View.VISIBLE); tryAgain.setVisibility(android.view.View.VISIBLE);
    }

    /** Partner phone: one Allow in Filen Files for this owner. Falls back silently to the folder chooser in settings when Filen Files is missing or older. */
    private void startReceived(String ownerEmail, String nonce) {
        if (isFinishing() || !FilenFolder.useFilen(FilenFolder.receivedAvailable(this))) return;
        try { startActivityForResult(FilenFolder.pairReceivedRequest(ownerEmail, nonce), RECEIVE); clearWaiting(); retry = () -> startReceived(ownerEmail, nonce); }
        catch (ActivityNotFoundException unavailable) { /* the settings picker stays the fallback */ }
    }

    /** Owner phone: one Allow in Filen Files for the partner who scanned. */
    private void startShare() {
        try {
            if (!FilenFolder.shareAvailable(this)) throw new ActivityNotFoundException();
            startActivityForResult(FilenFolder.pairShareRequest(pairNonce), SHARE); clearWaiting(); retry = this::startShare;
        }
        catch (ActivityNotFoundException unavailable) { status.setText("Filen Files is not available. Choose the sharing folder in Settings, under Partner sharing."); }
    }

    private void filenResult(int request, int result, Intent data) {
        boolean granted = result == RESULT_OK && data != null && data.getData() != null;
        Runnable again = request == SHARE ? this::startShare : retry;
        String line = FilenFolder.statusLine(granted, granted, data == null ? null : data.getStringExtra(FilenFolder.EXTRA_ERROR), data == null ? null : data.getStringExtra(FilenFolder.EXTRA_MESSAGE));
        if (!granted) { if (again != null) showWaiting(line, again); else status.setText(line); return; }
        if (!FilenFolder.treeTrusted(this, data)) { status.setText("Filen Files is not available. Choose the folder in Settings, under Partner sharing."); clearWaiting(); return; }
        clearWaiting();
        Runnable bad = () -> status.setText("Stack could not keep access to that folder. Choose it in Settings, under Partner sharing.");
        Runnable ok = () -> status.setText(request == SHARE ? "Sharing folder ready. Documents you switch on will be shared." : "Paired. Documents your partner shares will appear in Stack.");
        whenAllowed(() -> { if (request == SHARE) PartnerGrant.sharedFolder(app(), data, ok, bad); else PartnerGrant.receivedFolder(app(), data, ok, bad); });
    }

    private void confirmRotate() {
        new AlertDialog.Builder(this).setTitle("Stop sharing and make a new key?")
                .setMessage("Your partner will no longer be able to open what you share until they scan the new code. The old code stops working. " + CODE_WARNING)
                .setPositiveButton("Make a new key", (d, w) -> new Thread(() -> {
                    byte[] key = null;
                    try { key = PartnerShare.rotate(app()); pairNonce = PartnerQr.newNonce(); show(key); runOnUiThread(() -> status.setText("New key made. Ask your partner to scan the new code.")); }
                    catch (Exception error) { runOnUiThread(() -> status.setText("Stack could not make a new key. Nothing was changed.")); }
                    finally { if (key != null) Arrays.fill(key, (byte) 0); }
                }, "Stack new sharing key").start())
                .setNegativeButton("Cancel", null).show();
    }
}
