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
    /** Explicit broadcast Filen Files sends to Stack when a tree Stack holds a grant on changed (interfaces I4 F5). */
    static final String ACTION_SHARED_CHANGED = "app.filenfiles.action.SHARED_CHANGED";
    static final String EXTRA_TREE_URI = "app.filenfiles.extra.TREE_URI";

    private FilenFolder() {}

    static Intent request() { return new Intent(ACTION).setPackage(PACKAGE).putExtra(EXTRA_FOLDER_NAME, FOLDER); }

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
