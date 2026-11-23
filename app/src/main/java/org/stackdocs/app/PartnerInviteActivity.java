package org.stackdocs.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** "Invite partner": send the start-page link, then pair in person. The link holds no secret and nothing about this library. */
public final class PartnerInviteActivity extends LibraryActivity {
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this);
        bar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        bar.addView(Ui.text(this, "Invite partner", 18, false), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 24));
        TextView intro = Ui.text(this, "Send your partner a link. It shows them what to install.", 15, false);
        intro.setPadding(0, 0, 0, Ui.dp(this, 12));
        body.addView(intro);
        body.addView(Ui.button(this, "Share link", () -> {
            try { startActivity(PartnerInvite.shareIntent()); } catch (ActivityNotFoundException none) { status.setText("No app can share the link."); }
        }));
        body.addView(Ui.button(this, "Copy link", () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) { status.setText("Could not copy the link."); return; }
            clipboard.setPrimaryClip(ClipData.newPlainText("Stack invitation", PartnerInvite.message()));
            status.setText("Link copied.");
        }));
        body.addView(Ui.button(this, "Learn more", () -> new AlertDialog.Builder(this).setTitle("About the link")
                .setMessage("The link holds no secret and nothing about your library.").setPositiveButton("Got it", null).show()));
        status = Ui.text(this, "", 14, false);
        status.setTextColor(Ui.MUTED);
        body.addView(status);
        body.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        body.addView(Ui.actionRow(this, Icons.Symbol.CHECK, "Pair with partner", "When you are together", 0xFFDCECF9, () -> startActivity(new Intent(this, PairPartnerActivity.class))));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, root);
    }
}
