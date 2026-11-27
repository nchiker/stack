package org.stackdocs.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Stack's side of Filen Files' partner calls (docs/FOLDER_SHARING_INTERFACES.md I4 F7 to F11): the three screens Filen shows (partner setup, share
 * confirm sheet, create shared scan folder) and the two questions Stack asks without a screen (partner status F9, share state F10). Filen holds the
 * truth about who the partner is and what is shared; Stack only asks. The questions need the pinned Filen Files; any failure is an "unknown", never
 * a guess. {@link #api} can be replaced by a check to play Filen's answers.
 */
final class FilenPartner {
    static final String PROVIDER = "content://app.filenfiles.stacklink";
    static final String ACTION_SETUP = "app.filenfiles.action.PARTNER_SETUP";
    static final String ACTION_SHARE = "app.filenfiles.action.SHARE_WITH_PARTNER";
    static final String ACTION_CREATE_FOLDER = "app.filenfiles.action.CREATE_SHARED_FOLDER";
    static final String EXTRA_UUID = "app.filenfiles.extra.FILEN_UUID";
    static final String EXTRA_STOP = "app.filenfiles.extra.STOP";
    static final String EXTRA_NAME = "app.filenfiles.extra.NAME";
    static final String EXTRA_PARTNER_NAME = "app.filenfiles.extra.PARTNER_NAME";
    static final String EXTRA_SHARED = "app.filenfiles.extra.SHARED";
    /** STOP result: the name of the shared folder that was stopped instead, when the item was shared only through that folder (SHARED is then false). */
    static final String EXTRA_VIA_FOLDER = "app.filenfiles.extra.VIA_FOLDER";
    static final String EXTRA_ERROR = "app.filenfiles.extra.ERROR";
    /** The Filen Drive root tree (Add a Filen folder starts the picker here). */
    static final String DRIVE_ROOT = "filen:root";

    static final String NONE = "none", REQUESTED = "requested", INCOMING = "incoming", CONNECTED = "connected";

    private FilenPartner() {}

    /** The answer to "partner_status". `state` is one of the constants; `name` is the display name, "" when unknown. */
    static final class Status {
        final String state, name, id;
        /** `id` is Filen's opaque stable id of the saved partner contact (F9 `partner_id`, only when connected), "" when Filen does not send it. */
        Status(String state, String name, String id) { this.state = state; this.name = name == null ? "" : name; this.id = id == null ? "" : id; }
        Status(String state, String name) { this(state, name, ""); }
    }

    /** The answer to "share_state". `known` is false for not_found, signed_out and unknown (offline); then nothing is shown as shared or not. */
    static final class Share {
        final boolean known, shared;
        final String via;
        Share(boolean known, boolean shared, String via) { this.known = known; this.shared = shared; this.via = via == null ? "" : via; }
        static final Share UNKNOWN = new Share(false, false, "");
    }

    /** The two questions. The real one asks Filen Files; a check supplies another. Both run off the main thread and never throw. */
    interface Api {
        Status status();
        Share share(String uuid);
        /** F12: Filen's contract level, {@link #NOT_INSTALLED} when the pinned Filen Files is not there, 0 for a Filen that answers with an error (no such call), {@link #UNKNOWN} when it did not answer. */
        default int contract() { return REQUIRED_LEVEL; }
    }

    static volatile Api api;
    private static Calls calls;

    static synchronized Api api(Context context) {
        Api replaced = api;
        if (replaced != null) return replaced;
        if (calls == null) calls = new Calls(context.getApplicationContext());
        return calls;
    }

    // ---- F12: the contract level ----------------------------------------------------------------------------------------------------

    /** The contract level Stack needs before it shows any partner or sharing screen (interfaces F12). */
    static final int REQUIRED_LEVEL = 1;
    /** The pinned Filen Files is not installed. */
    static final int NOT_INSTALLED = -1;
    /** Filen did not answer the contract call (cold start, timeout): not a level, never kept, never shown as "update". */
    static final int UNKNOWN = -2;
    /** Waits before the 2nd, 3rd and 4th ask when Filen did not answer. A check shortens them. */
    static volatile long[] backoffMs = {1000, 3000, 8000};

    private static Api levelFor;
    private static int levelValue;
    private static boolean levelKnown;

    /**
     * Filen's contract level, asked once and kept for the app session; {@link #refreshLevel} (called when a screen resumes) makes the next call ask again.
     * Blocking: call it off the main thread.
     */
    static int level(Context context) {
        Api asked = api(context);
        synchronized (FilenPartner.class) { if (levelKnown && levelFor == asked) return levelValue; }
        int found = UNKNOWN;
        long[] waits = backoffMs;
        for (int attempt = 0; attempt <= waits.length; attempt++) {
            if (attempt > 0) try { Thread.sleep(waits[attempt - 1]); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); break; }
            try { found = asked.contract(); } catch (RuntimeException failed) { found = UNKNOWN; }
            if (found != UNKNOWN) break;
        }
        if (found == UNKNOWN) return UNKNOWN;   // a failure is never kept: the next ask tries again
        synchronized (FilenPartner.class) { levelFor = asked; levelValue = found; levelKnown = true; }
        return found;
    }

    static synchronized void refreshLevel() { levelKnown = false; }

    private static String cachedName = "";

    /**
     * The partner's name for a label: the partner source's name, else the name Filen's partner_status gave (kept here), else "" (callers fall back to "your partner").
     * Blocking: call it off the main thread.
     */
    static String name(Context context, String sourceName) {
        if (sourceName != null && !sourceName.trim().isEmpty()) return sourceName.trim();
        synchronized (FilenPartner.class) { if (!cachedName.isEmpty()) return cachedName; }
        if (!supported(level(context))) return "";
        Status status;
        try { status = api(context).status(); } catch (RuntimeException failed) { return ""; }
        if (status == null) return "";
        remember(status);
        synchronized (FilenPartner.class) { return cachedName; }
    }

    /**
     * The connected partner's name as Filen says it NOW (not the kept one: a partner change must be seen), "" when Filen cannot say or nobody is
     * connected. Remembers it. Blocking: call it off the main thread.
     */
    static String freshName(Context context) {
        Status status = freshStatus(context);
        return status == null ? "" : status.name.trim();
    }

    /** The stable id of the connected partner as Filen says it NOW, "" when Filen cannot say, nobody is connected or this Filen sends no `partner_id`. */
    static String freshId(Context context) {
        Status status = freshStatus(context);
        return status == null ? "" : status.id.trim();
    }

    /**
     * Filen's whole partner_status answer now (state, id and name from this one call), null when Filen did not answer (offline, no reply, a Filen too old
     * for F12). A definite state other than "connected" is an answer: nobody is connected. Remembers a connected name. Blocking.
     */
    static Status askedStatus(Context context) {
        if (!supported(level(context))) return null;
        Status status;
        try { status = api(context).status(); } catch (RuntimeException failed) { return null; }
        if (status == null) return null;
        remember(status);
        return status;
    }

    /** The connected partner as Filen says it now (state connected and a name), null otherwise. Remembers the name. Blocking. */
    static Status freshStatus(Context context) {
        if (!supported(level(context))) return null;
        Status status;
        try { status = api(context).status(); } catch (RuntimeException failed) { return null; }
        if (status == null || !CONNECTED.equals(status.state) || status.name.trim().isEmpty()) return null;
        remember(status);
        return status;
    }

    /** The name kept from partner_status, "" when none (no call to Filen). */
    static synchronized String knownName() { return cachedName; }

    /** Drops the kept name (checks). */
    static synchronized void forgetName() { cachedName = ""; }

    /** Keeps the name of a connected partner from partner_status. */
    static synchronized void remember(Status status) {
        if (status != null && CONNECTED.equals(status.state) && !status.name.trim().isEmpty()) cachedName = status.name.trim();
    }

    static boolean supported(int level) { return level >= REQUIRED_LEVEL; }

    /** The one line that stands in for any partner or sharing screen when Filen Files is missing or too old. */
    static String gateText(int level) { return level == NOT_INSTALLED ? "Get Filen Files" : level == UNKNOWN ? "Filen Files is not answering yet" : "Update Filen Files to share with your partner"; }

    static String gateButton(int level) { return level == NOT_INSTALLED ? "Get Filen Files" : level == UNKNOWN ? "Open Filen Files" : "Update"; }

    /** The Update / Get button: opens Filen Files (whose own updater installs the new version), or its release page when it is not installed. */
    static void openGate(Context context, int level) {
        try {
            Intent intent;
            if (level == NOT_INSTALLED || !LinkCallers.filenInstalledAndPinned(context)) intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Companions.FILEN.page()));
            else intent = context.getPackageManager().getLaunchIntentForPackage(FilenFolder.PACKAGE);
            if (intent == null) { android.widget.Toast.makeText(context, "Filen Files could not be opened.", android.widget.Toast.LENGTH_LONG).show(); return; }
            if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (RuntimeException unavailable) { android.widget.Toast.makeText(context, "Filen Files could not be opened.", android.widget.Toast.LENGTH_LONG).show(); }
    }

    private static final class Calls implements Api {
        private final Context context;
        Calls(Context context) { this.context = context; }

        private Bundle call(String method, Bundle extras) {
            try {
                if (!LinkCallers.filenAuthority(context, PROVIDER.substring("content://".length()))) return null;
                return context.getContentResolver().call(Uri.parse(PROVIDER), method, null, extras);
            } catch (RuntimeException unavailable) { return null; }
        }

        @Override public Status status() {
            Bundle reply = call("partner_status", null);
            if (reply == null || reply.getString("error") != null) return null;
            String state = reply.getString("state");
            if (!NONE.equals(state) && !REQUESTED.equals(state) && !INCOMING.equals(state) && !CONNECTED.equals(state)) return null;
            return new Status(state, reply.getString("name"), reply.getString("partner_id"));
        }

        @Override public int contract() {
            if (!LinkCallers.filenInstalledAndPinned(context)) return NOT_INSTALLED;
            Bundle reply = call("contract", null);
            if (reply == null) return UNKNOWN;   // no reply at all (Filen starting, timeout): not "too old"
            if (reply.getString("error") != null || !reply.containsKey("level")) return 0;
            return reply.getInt("level", 0);
        }

        @Override public Share share(String uuid) {
            if (uuid == null) return Share.UNKNOWN;
            Bundle extras = new Bundle();
            extras.putString(EXTRA_UUID, uuid);
            Bundle reply = call("share_state", extras);
            if (reply == null || reply.getString("error") != null || !reply.containsKey("shared")) return Share.UNKNOWN;
            return new Share(true, reply.getBoolean("shared"), reply.getString("shared_via"));
        }
    }

    // ---- screens ------------------------------------------------------------------------------------------------------------------

    static Intent setupRequest() { return new Intent(ACTION_SETUP).setPackage(FilenFolder.PACKAGE); }

    /** The confirm sheet for a file or folder by its Filen UUID; `stop` asks to stop sharing it. */
    static Intent shareRequest(String uuid, boolean stop) {
        Intent intent = new Intent(ACTION_SHARE).setPackage(FilenFolder.PACKAGE).putExtra(EXTRA_UUID, uuid);
        if (stop) intent.putExtra(EXTRA_STOP, true);
        return intent;
    }

    /** "Stopped sharing the folder “X”" when a STOP result says the item was shared through a folder and that folder was stopped; otherwise "". */
    static String stoppedViaFolder(int result, Intent data) {
        if (result != android.app.Activity.RESULT_OK || data == null || data.getBooleanExtra(EXTRA_SHARED, true)) return "";
        String via = data.getStringExtra(EXTRA_VIA_FOLDER);
        if (via == null) return "";
        via = via.replaceAll("[\\p{Cc}\u202a-\u202e\u2066-\u2069]", " ").trim();
        if (via.isEmpty()) return "";
        if (via.length() > 80) via = via.substring(0, 80).trim() + "...";
        return "Stopped sharing the folder \u201c" + via + "\u201d";
    }

    static Intent createFolderRequest(String name) { return new Intent(ACTION_CREATE_FOLDER).setPackage(FilenFolder.PACKAGE).putExtra(EXTRA_NAME, name); }

    static boolean available(Context context, Intent request) { return LinkCallers.filenActivity(context, request); }

    /** Where Add a Filen folder starts: the top of Filen Drive. */
    static Uri driveRoot() { return android.provider.DocumentsContract.buildDocumentUri(SourceStore.AUTHORITY, DRIVE_ROOT); }

    /** The name of the folder Filen creates for shared scans. */
    static final String SCAN_FOLDER = "Shared scans";

    /** The name Stack asks Filen for. It reads the same on the partner's side, so it has no names in it. */
    static String scanFolderName(String partner) { return SCAN_FOLDER; }

    /** True for the scan folder as the partner sees it, including the older "Stack scans shared with ..." name. */
    static boolean isScanFolder(String name) { return SCAN_FOLDER.equals(name) || (name != null && name.startsWith("Stack scans shared with ")); }

    /** The tree Filen returned for a screen, only when it really belongs to the pinned Filen Files. */
    static boolean trusted(Context context, Uri tree) { return tree != null && LinkCallers.filenAuthority(context, tree.getAuthority()) && SourceStore.validTree(tree); }
}
