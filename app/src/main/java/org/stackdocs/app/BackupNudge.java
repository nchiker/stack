package org.stackdocs.app;

import android.content.Context;
import android.content.SharedPreferences;

/** A gentle reminder on the main screen when documents exist but no recent backup does. */
final class BackupNudge {
    private static final long STALE = 30L * 24 * 60 * 60 * 1000, SNOOZE = 7L * 24 * 60 * 60 * 1000;

    private BackupNudge() {}

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("backup_nudge", Context.MODE_PRIVATE); }

    /** Text to show, or empty when there is nothing to remind about (no documents, automatic backup on, recent backup, snoozed). */
    static String message(Context context, int documents) { return message(context, documents, System.currentTimeMillis()); }

    static String message(Context context, int documents, long now) {
        if (documents <= 0 || AutoBackup.enabled(context)) return "";
        if (prefs(context).getLong("snoozed_until", 0) > now) return "";
        long last = BackupHistory.last(context);
        if (last <= 0 || last > now) return "Your documents aren't backed up yet";
        long days = (now - last) / (24L * 60 * 60 * 1000);
        return now - last > STALE ? "Last backup was " + days + " days ago" : "";
    }

    static void snooze(Context context) { prefs(context).edit().putLong("snoozed_until", System.currentTimeMillis() + SNOOZE).commit(); }
}
