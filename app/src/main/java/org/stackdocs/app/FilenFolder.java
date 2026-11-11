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

    private FilenFolder() {}

    static Intent request() { return new Intent(ACTION).setPackage(PACKAGE).putExtra(EXTRA_FOLDER_NAME, FOLDER); }

    /** True when an installed Filen Files offers the grant screen (older versions do not). */
    static boolean available(Context context) {
        return request().resolveActivity(context.getPackageManager()) != null;
    }

    /** Short words for a refusal, or "" when the person backed out (user_cancelled, or no code at all). */
    static String reason(Intent data) {
        String code = data == null ? "" : String.valueOf(data.getStringExtra(EXTRA_ERROR));
        switch (code) {
            case "not_signed_in": return "Sign in to Filen in Filen Files first, then try again.";
            case "offline": return "Filen Drive could not be reached. Connect to the internet and try again.";
            case "failed": {
                String message = data.getStringExtra(EXTRA_MESSAGE);
                return message == null || message.trim().isEmpty() ? "Filen Files could not set up the folder." : message.trim();
            }
            default: return "";
        }
    }
}
