package org.stackdocs.app;

import android.content.Context;
import android.content.Intent;

/**
 * One-tap backup folder from Filen Files: Stack asks, Filen Files shows a single Allow screen, creates
 * "Stack backups" in Filen Drive and returns a persistable tree grant for it. The result goes through the same
 * path as a folder picked with the system picker. Contract agreed with the Filen Files project.
 */
final class FilenFolder {
    static final String PACKAGE = "app.filenfiles";
    static final String ACTION = "app.filenfiles.action.GRANT_BACKUP_FOLDER";
    static final String EXTRA_FOLDER_NAME = "app.filenfiles.extra.FOLDER_NAME";
    static final String EXTRA_ERROR = "app.filenfiles.extra.ERROR";
    static final String EXTRA_MESSAGE = "app.filenfiles.extra.MESSAGE";
    static final String FOLDER = "Stack backups";
    /** Not shipped by Filen Files yet (part F of the runbook); until it is, {@link #shareAvailable} is false and the system folder picker is used. */
    static final String SHARE_ACTION = "app.filenfiles.action.GRANT_SHARE_FOLDER";
    static final String SHARE_FOLDER = "Stack shared";
    /** The partner's shared folder as received in Filen Drive (read-only tree grant). Not shipped by Filen Files yet; the system picker is used until it is. */
    static final String RECEIVED_ACTION = "app.filenfiles.action.GRANT_RECEIVED_FOLDER";

    /** Pairing handshake names, exactly as docs STACK_PARTNER_FOLDERS_SCOPE.md in the Filen Files project spells them. */
    static final String PROVIDER = "content://app.filenfiles.stacklink";
    static final String METHOD_ACCOUNT = "account";
    static final String ACCOUNT_EMAIL = "email";
    static final String EXTRA_OWNER_EMAIL = "app.filenfiles.extra.OWNER_EMAIL";
    /** Binds the partner's contact request to the QR the owner showed (a fresh random value per QR; see {@link PartnerQr}). Never logged or stored. */
    static final String EXTRA_PAIR_NONCE = "app.filenfiles.extra.PAIR_NONCE";
    static final String ERROR_WAITING_FOR_OWNER = "waiting_for_owner";
    static final String ERROR_WAITING_FOR_PARTNER = "waiting_for_partner";
    /** Explicit broadcast Filen Files sends to Stack when a received share changed. */
    static final String ACTION_SHARED_CHANGED = "app.filenfiles.action.SHARED_CHANGED";
    /** TODO-confirm with the Filen Files session: the scope says only "extra: the tree URI"; this name is Stack's guess. */
    static final String EXTRA_TREE_URI = "app.filenfiles.extra.TREE_URI";
    /** The one calm line both phones show while the other person has not finished yet. */
    static final String WAITING_LINE = "Waiting for your partner. Tap Try again when they are done.";

    private FilenFolder() {}

    /** Pairing, owner side: no folder name, so Filen Files uses its own default ("Stack shared"). */
    static Intent pairShareRequest() { return pairShareRequest(null); }

    static Intent pairShareRequest(String nonce) {
        Intent intent = new Intent(SHARE_ACTION).setPackage(PACKAGE);
        if (PartnerQr.validNonce(nonce)) intent.putExtra(EXTRA_PAIR_NONCE, nonce);
        return intent;
    }

    /** Pairing, partner side: Filen Files shows one Allow for this owner and returns a read-only tree grant. */
    static Intent pairReceivedRequest(String ownerEmail) { return pairReceivedRequest(ownerEmail, null); }

    static Intent pairReceivedRequest(String ownerEmail, String nonce) {
        Intent intent = new Intent(RECEIVED_ACTION).setPackage(PACKAGE).putExtra(EXTRA_OWNER_EMAIL, ownerEmail);
        if (PartnerQr.validNonce(nonce)) intent.putExtra(EXTRA_PAIR_NONCE, nonce);
        return intent;
    }

    /** The owner's Filen account email, or null when Filen Files is missing, too old, does not know this caller, or has nobody signed in. Never logged. */
    static String ownerEmail(Context context) {
        try {
            // Only the pinned Filen Files may answer: another app claiming the authority gets nothing from us.
            if (!LinkCallers.filenAuthority(context, PROVIDER.substring("content://".length()))) return null;
            android.os.Bundle reply = context.getContentResolver().call(android.net.Uri.parse(PROVIDER), METHOD_ACCOUNT, null, null);
            String email = reply == null ? null : reply.getString(ACCOUNT_EMAIL);
            return PartnerQr.validEmail(email) ? email : null;
        } catch (RuntimeException unavailable) { return null; }   // includes a refused caller; nothing about it is kept
    }

    /** What a handshake screen did, in words the activities act on. */
    enum Outcome { GRANTED, WAITING, REFUSED, BACKED_OUT }

    /** Pure mapping of a result to an outcome. `error` is the ERROR extra (may be null). A grant needs RESULT_OK and a tree. */
    static Outcome outcome(boolean resultOk, boolean hasTree, String error) {
        if (resultOk && hasTree) return Outcome.GRANTED;
        if (ERROR_WAITING_FOR_OWNER.equals(error) || ERROR_WAITING_FOR_PARTNER.equals(error)) return Outcome.WAITING;
        if (error == null || error.isEmpty() || "user_cancelled".equals(error)) return Outcome.BACKED_OUT;
        return Outcome.REFUSED;
    }

    /** The quiet line for a finished handshake result, "" when it was granted. A plain cancel also gets the retry line so Try again stays offered. */
    static String statusLine(boolean resultOk, boolean hasTree, String error, String message) {
        switch (outcome(resultOk, hasTree, error)) {
            case GRANTED: return "";
            case WAITING: case BACKED_OUT: return WAITING_LINE;
            default: { String why = reason(error, message); return why.isEmpty() ? WAITING_LINE : why; }
        }
    }

    /** The Filen path or the system picker: Filen only when an activity resolves the action (older Filen Files falls back). */
    static boolean useFilen(boolean actionResolves) { return actionResolves; }

    static Intent request() { return new Intent(ACTION).setPackage(PACKAGE).putExtra(EXTRA_FOLDER_NAME, FOLDER); }

    /** Asks Filen Files to create "Stack shared", share it with the partner and return a persistable tree grant. */
    static Intent shareRequest() { return new Intent(SHARE_ACTION).setPackage(PACKAGE).putExtra(EXTRA_FOLDER_NAME, SHARE_FOLDER); }

    /** Asks Filen Files for a read-only tree grant of the folder the partner shared with this account. */
    static Intent receivedRequest() { return new Intent(RECEIVED_ACTION).setPackage(PACKAGE).putExtra(EXTRA_FOLDER_NAME, SHARE_FOLDER); }

    /** True when an installed Filen Files offers the received-folder grant screen. */
    static boolean receivedAvailable(Context context) {
        return LinkCallers.filenActivity(context, receivedRequest());
    }

    /** True when an installed Filen Files offers the share-folder grant screen. */
    static boolean shareAvailable(Context context) {
        return LinkCallers.filenActivity(context, shareRequest());
    }

    /** True when an installed Filen Files offers the grant screen (older versions do not). */
    static boolean available(Context context) {
        return LinkCallers.filenActivity(context, request());
    }

    /** True when a tree returned by Filen Files really belongs to a provider of the pinned Filen Files app. */
    static boolean treeTrusted(Context context, Intent data) {
        android.net.Uri tree = data == null ? null : data.getData();
        return tree != null && LinkCallers.filenAuthority(context, tree.getAuthority());
    }

    /** Short words for a refusal, or "" when the person backed out (user_cancelled, or no code at all). */
    static String reason(Intent data) {
        return data == null ? "" : reason(data.getStringExtra(EXTRA_ERROR), data.getStringExtra(EXTRA_MESSAGE));
    }

    static String reason(String error, String message) {
        switch (String.valueOf(error)) {
            case "not_signed_in": return "Sign in to Filen Files first.";
            case "offline": return "Filen Drive is unreachable. Check your connection.";
            case "failed": {
                return message == null || message.trim().isEmpty() ? "Filen Files could not set up the folder." : message.trim();
            }
            default: return "";
        }
    }
}
