package org.stackdocs.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.HashMap;
import java.util.Map;

/**
 * Filen Files says a tree Stack reads changed (interfaces I4 F5). The broadcast is only a hint: it asks {@link SourceIndexer} to scan the one
 * source that holds exactly that tree, and the scan decides everything from FRESH listings. Nothing from the broadcast is stored or logged. On
 * Android 14 and later the sender must be Filen Files itself (`getSentFromPackage`); on older versions the broadcast cannot be attributed, which
 * is why it can do no more than ask for a re-check. The source is looked up first and only a real source uses the source's one-a-minute window,
 * so a flood of broadcasts for trees Stack does not read costs nothing and cannot delay a real one. The window is never reset by a success.
 */
public final class PartnerChangeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !FilenFolder.ACTION_SHARED_CHANGED.equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT >= 34 && !FilenFolder.PACKAGE.equals(sender(this))) return;
        if (!(context.getApplicationContext() instanceof StackApplication)) return;
        StackApplication app = (StackApplication) context.getApplicationContext();
        String named = intent.getStringExtra(FilenFolder.EXTRA_TREE_URI);
        if (named == null || app.sourceIndexer == null) return;
        SourceStore.Source source = null;
        try { source = app.library.sources.byTree(named); } catch (RuntimeException unavailable) { /* library not open */ }
        if (source == null || !app.access.allowed()) return;
        if (allowNow(source.id)) app.sourceIndexer.kickSource(source.id);
    }

    private static String sender(BroadcastReceiver receiver) {
        try { return Build.VERSION.SDK_INT >= 34 ? receiver.getSentFromPackage() : null; } catch (RuntimeException unknown) { return null; }
    }

    static final long MIN_GAP = 60_000L;
    private static final Map<String, Long> lastRun = new HashMap<>();   // source id -> elapsed-realtime milliseconds

    /** Rate limit per source: true (and remembers now) when the last accepted broadcast for it was at least a minute ago. */
    static synchronized boolean allowNow(String sourceId) { return allow(sourceId, android.os.SystemClock.elapsedRealtime()); }

    static synchronized boolean allow(String sourceId, long elapsedNow) {
        Long last = lastRun.get(sourceId);
        if (last != null && elapsedNow - last < MIN_GAP && elapsedNow >= last) return false;
        lastRun.put(sourceId, elapsedNow);
        return true;
    }

    static synchronized void forget() { lastRun.clear(); }
}
