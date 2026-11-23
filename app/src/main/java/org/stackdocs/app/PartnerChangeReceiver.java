package org.stackdocs.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Filen Files says a share it gave Stack changed. This only asks for the normal import ({@link PartnerImport#changed}), which skips a file whose
 * size and modified time are unchanged, so the broadcast carries no trust: nothing from it is read, stored or logged. The tree URI must name exactly the folder Stack reads, and imports it triggers are limited to one a minute.
 */
public final class PartnerChangeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !FilenFolder.ACTION_SHARED_CHANGED.equals(intent.getAction())) return;
        if (!(context.getApplicationContext() instanceof StackApplication)) return;
        StackApplication app = (StackApplication) context.getApplicationContext();
        if (!matches(PartnerImport.tree(app), intent.getStringExtra(FilenFolder.EXTRA_TREE_URI))) return;
        if (!allowNow()) return;   // at most one broadcast-triggered import a minute
        final PendingResult pending = goAsync();   // keeps the process alive until the import thread ends
        PartnerImport.changed(app, pending::finish);
    }

    static final long MIN_GAP = 60_000L;
    private static long lastRun = -MIN_GAP;   // elapsed-realtime milliseconds

    /** Rate limit: true (and remembers now) when the last accepted broadcast was at least a minute ago. */
    static synchronized boolean allowNow() { return allow(android.os.SystemClock.elapsedRealtime()); }

    static synchronized boolean allow(long elapsedNow) {
        if (elapsedNow - lastRun < MIN_GAP && elapsedNow >= lastRun) return false;
        lastRun = elapsedNow;
        return true;
    }

    static synchronized void resetLimit() { lastRun = -MIN_GAP; }

    /** True only when the broadcast names exactly the folder Stack reads; a missing name is not accepted. */
    static boolean matches(Uri chosen, String named) {
        return chosen != null && named != null && !named.isEmpty() && chosen.toString().equals(named);
    }
}
