package org.stackdocs.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Partner setup (docs/FOLDER_SHARING_DESIGN.md section 1, "Once, ever"). Filen's PARTNER_SETUP screen (F8) returns READ grants for "Shared with
 * me" (`filen-in`) and "Shared by me" (`filen-out`) as clip data; this turns them into the `partner` and `shared_by_me` sources and starts a
 * scan. The rules for what the screens show are plain functions here, so the checks can test them without Filen.
 */
final class PartnerSetup {
    /** Request code the screens use for PARTNER_SETUP. */
    static final int REQUEST = 7301;

    private PartnerSetup() {}

    // ---- what the screens show --------------------------------------------------------------------------------------------------

    /**
     * The card on the search screen ("See files [name] shares with you" when connected: Filen does not say whether anything is shared yet). Only when Filen says a request is incoming, or the partner is connected, and no partner source exists yet;
     * "none", "requested" (or anything else) gives no card: the quiet entry is in Settings only.
     */
    static String cardText(boolean hasPartnerSource, FilenPartner.Status status) {
        if (hasPartnerSource || status == null) return null;
        if (FilenPartner.INCOMING.equals(status.state)) return displayName(status.name) + " wants to share files with you.";
        if (FilenPartner.CONNECTED.equals(status.state)) return "See files " + lowerName(status.name) + " shares with you";
        return null;
    }

    /** "Not now" hides the card this long. */
    static final long CARD_HIDDEN_MS = 30L * 24 * 60 * 60 * 1000;

    static boolean cardHidden(long hiddenUntil, long now) { return now < hiddenUntil; }

    /** The Settings row: `[title, subtitle]`. */
    static String[] settingsRow(String partnerSourceName, FilenPartner.Status status) {
        if (partnerSourceName != null) return new String[]{"Partner", "Sharing with " + displayName(partnerSourceName)};
        if (status != null && FilenPartner.INCOMING.equals(status.state)) return new String[]{"Partner", displayName(status.name) + " wants to share files with you"};
        if (status != null && FilenPartner.REQUESTED.equals(status.state)) return new String[]{"Partner", "Waiting for " + displayName(status.name) + " to accept"};
        if (status != null && FilenPartner.CONNECTED.equals(status.state)) return new String[]{"Partner", "Show " + displayName(status.name) + "'s files here"};
        return new String[]{"Partner", "Share with your partner"};
    }

    /** The name to show: Filen's display name, or a plain word when Filen has none yet. */
    static String displayName(String name) { return name == null || name.trim().isEmpty() ? "Your partner" : name.trim(); }

    static String lowerName(String name) { return name == null || name.trim().isEmpty() ? "your partner" : name.trim(); }

    // ---- result of Filen's screen -----------------------------------------------------------------------------------------------

    /** The trees in a PARTNER_SETUP result: every clip data item, then the data URI when the clip had none. */
    static List<Uri> grants(Intent data) {
        List<Uri> out = new ArrayList<>();
        if (data == null) return out;
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) { Uri uri = clip.getItemAt(i).getUri(); if (uri != null && !out.contains(uri)) out.add(uri); }
        if (out.isEmpty() && data.getData() != null) out.add(data.getData());
        return out;
    }

    /** Keeps the persistable READ grants of a result. Call on the main thread inside onActivityResult, while Android still holds the temporary grants. */
    static void keepGrants(Context context, Intent data) {
        for (Uri tree : grants(data)) {
            try { context.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (RuntimeException refused) { /* SourceStore.add reports a tree that is not held */ }
        }
    }

    /**
     * Adds the sources for the granted trees: `partner` for the `filen-in` root, `shared_by_me` for the `filen-out` root. Runs off the main thread.
     * Returns how many sources were added or updated (0 means Filen gave no usable tree). Throws IOException with plain words when a tree is refused.
     */
    static int apply(SourceStore store, List<Uri> trees, String partnerName) throws IOException { return apply(store, trees, partnerName, ""); }

    /** As above, and keeps Filen's stable partner id with the sources that were added. */
    static int apply(SourceStore store, List<Uri> trees, String partnerName, String partnerId) throws IOException {
        String name = partnerName == null ? "" : partnerName.trim();
        int added = 0;
        for (Uri tree : trees) {
            if (!SourceStore.validTree(tree)) continue;
            String doc = DocumentsContract.getTreeDocumentId(tree);
            if (doc.startsWith("filen-in:")) { store.setPartnerId(store.add(SourceStore.PARTNER, tree, name.isEmpty() ? "Shared files" : name + "'s shared files", name).id, partnerId); added++; }
            else if (doc.startsWith("filen-out:")) { store.setPartnerId(store.add(SourceStore.SHARED_BY_ME, tree, "Shared by me", name).id, partnerId); added++; }
        }
        return added;
    }

    /** What to do with a finished PARTNER_SETUP screen. `done` gets "" on success or the words to show; it runs on the main thread. */
    static void finish(LibraryActivity activity, int resultCode, Intent data, java.util.function.Consumer<String> done) {
        if (resultCode != Activity.RESULT_OK || data == null) { done.accept(""); return; }
        keepGrants(activity, data);
        final List<Uri> trees = grants(data);
        final String name = data.getStringExtra(FilenPartner.EXTRA_PARTNER_NAME);
        activity.work(() -> {
            int added = apply(activity.app().library.sources, trees, name, FilenPartner.freshId(activity));
            if (added > 0) activity.app().sourceIndexer.kickAll(true);
            return added;
        }, added -> done.accept(added > 0 ? "" : "Filen did not share anything yet. Try again in a moment."));
    }

    /** Starts Filen's partner screen. False (with the words in `problem[0]`) when Filen Files cannot do it. */
    static boolean start(LibraryActivity activity, String[] problem) {
        Intent request = FilenPartner.setupRequest();
        if (!FilenPartner.available(activity, request)) { problem[0] = "Update Filen Files to share with your partner."; return false; }
        try { activity.startActivityForResult(request, REQUEST); return true; }
        catch (RuntimeException unavailable) { problem[0] = "Filen Files could not be opened."; return false; }
    }
}
