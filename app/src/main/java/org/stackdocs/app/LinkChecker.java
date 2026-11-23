package org.stackdocs.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Asks owner apps whether their files still exist (docs/STACK_LINK_CONTRACT.md, `stat` and `refresh`).
 * Rules: a failed, slow, malformed or untrusted answer is only ever `unreachable`; only a well-formed `exists:false` makes a record
 * `missing`. The provider call itself runs on a small pool of its own (a call cannot be cancelled, and a hung owner must never hold
 * the library's IO thread); only the database write goes to the IO executor, and it is compare-and-set against the record's revision.
 * Call these methods from a background thread, never the main thread.
 */
final class LinkChecker {
    /** What one check found. `state` is the record's state afterwards; `busy` means nothing was asked (a call to that owner is still running). */
    static final class Result {
        final String state; final boolean changed, busy;
        Result(String state, boolean changed, boolean busy) { this.state = state; this.changed = changed; this.busy = busy; }
    }

    /** Totals for "Check linked documents". */
    static final class Summary { int checked, live, unreachable, missing, changed, skipped; }

    static final long CALL_TIMEOUT_MS = 5000, IO_TIMEOUT_MS = 15000;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final long A_DAY = 24L * 60 * 60 * 1000;

    private static final ExecutorService CALLS = new ThreadPoolExecutor(0, 3, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "link-owner-call"); thread.setDaemon(true); return thread;
    });
    /** Owners with a provider call still running. A second call to the same owner is not started. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private LinkChecker() {}

    static final class Busy extends Exception {}

    /** One `stat` for one record, and the write of its outcome. */
    static Result check(StackApplication app, LinkStore.Link link) {
        // A document the partner shares is another person's: there is no owner app here to ask.
        if (link.foreign) return new Result(link.state, false, false);
        long revision;
        try { revision = io(app, () -> app.library.links.revision(link.id)); } catch (Exception error) { return new Result(link.state, false, true); }
        if (revision < 0) return new Result(link.state, false, false);
        LinkStore.Outcome outcome;
        try {
            Bundle reply = call(app, link.owner, "stat", link.linkId, null);
            outcome = interpret(reply);
        } catch (Busy busy) {
            return new Result(link.state, false, true);
        } catch (Exception error) {
            android.util.Log.w("Stack", "Link check failed: " + error.getClass().getSimpleName() + " " + String.valueOf(error.getMessage()));
            outcome = new LinkStore.Outcome(LinkStore.UNREACHABLE, null, -1);
        }
        final LinkStore.Outcome write = outcome; final long expected = revision;
        try {
            int applied = io(app, () -> app.library.links.applyCheckDetailed(link.id, expected, write));
            return applied > 0 ? new Result(write.state, applied == 2, false) : new Result(link.state, false, false);
        } catch (Exception error) { return new Result(link.state, false, true); }
    }

    /** Checks every record that is not in Recently deleted, one at a time. One owner failing does not stop the others. */
    static Summary checkAll(StackApplication app) {
        Summary summary = new Summary();
        List<LinkStore.Link> links;
        try { links = io(app, () -> app.library.links.list("")); } catch (Exception error) { return summary; }
        for (LinkStore.Link link : links) {
            if (link.foreign) continue;
            Result result = check(app, link);
            if (result.busy) { summary.skipped++; continue; }
            summary.checked++;
            if (LinkStore.LIVE.equals(result.state)) summary.live++;
            else if (LinkStore.MISSING.equals(result.state)) summary.missing++;
            else summary.unreachable++;
            if (result.changed) summary.changed++;
        }
        if (summary.checked > 0) app.notifySyncChanged();
        return summary;
    }

    private static final long AUTO_GAP_MS = 60_000;
    private static long lastAuto = Long.MIN_VALUE;
    private static boolean autoRunning;

    /**
     * Quiet "Check linked documents" for links restored from a backup that no check has settled (restored = 1): no dialog, no toast.
     * An owner app that is not installed or does not answer leaves the link unsettled, so a later call tries it again, but never
     * more than once a minute; once every restored link has been answered (found, or not known by its owner) there is nothing left to
     * do and this does no work. Call from a background thread. Returns how many links were asked (0 when it did nothing).
     */
    static int checkRestored(StackApplication app) {
        synchronized (LinkChecker.class) {
            long now = Clock.now();
            if (autoRunning || (lastAuto != Long.MIN_VALUE && now >= lastAuto && now - lastAuto < AUTO_GAP_MS)) return 0;
            autoRunning = true; lastAuto = now;
        }
        try {
            List<LinkStore.Link> links = io(app, () -> app.library.links.restoredUnchecked());
            int asked = 0;
            for (LinkStore.Link link : links) { Result result = check(app, link); if (!result.busy) asked++; }
            if (asked > 0) app.notifySyncChanged();
            return asked;
        } catch (Exception error) { return 0; }
        finally { synchronized (LinkChecker.class) { autoRunning = false; } }
    }

    /** Forgets the last quiet attempt (tests, and after a restore) so the next call runs at once. */
    static synchronized void resetAuto() { lastAuto = Long.MIN_VALUE; }

    /** Asks the owner to publish a fresh copy. Returns null on success, otherwise a sentence the person can read. */
    static String refresh(StackApplication app, LinkStore.Link link) {
        if (link.foreign) return "This document belongs to your partner. Only they can update it.";
        String name = LinkOpener.ownerName(link.owner);
        try {
            Bundle reply = call(app, link.owner, "refresh", link.linkId, null);
            if (reply == null) return name + " could not update the saved copy.";
            if (reply.get("error") != null) return "protected".equals(reply.get("error")) ? "This document is password-protected, so " + name + " does not update the saved copy by itself. Open it there and save it." : name + " could not update the saved copy.";
            return Boolean.TRUE.equals(reply.get("ok")) ? null : name + " could not update the saved copy.";
        } catch (Busy busy) { return name + " is still busy. Try again in a moment."; }
        catch (Exception error) { return name + " could not be reached."; }
    }

    /** Reads a `stat` reply. Anything unexpected is `unreachable`; the hostile-Bundle case (a lazily unparcelled bad value) throws and is caught by the caller. */
    static LinkStore.Outcome interpret(Bundle reply) {
        if (reply == null || reply.containsKey("error")) return new LinkStore.Outcome(LinkStore.UNREACHABLE, null, -1);
        Object exists = reply.get("exists");
        if (!(exists instanceof Boolean)) return new LinkStore.Outcome(LinkStore.UNREACHABLE, null, -1);
        if (!((Boolean) exists)) return new LinkStore.Outcome(LinkStore.MISSING, null, -1);
        if (hashOf(reply) == null) return new LinkStore.Outcome(LinkStore.UNREACHABLE, null, -1);
        Object modified = reply.get("modified");
        long when = modified instanceof Long || modified instanceof Integer ? ((Number) modified).longValue() : -1;
        if (when < 0 || when > Clock.now() + A_DAY) when = -1;
        Object raw = reply.get("locators");
        List<LinkRecord.Locator> locators = raw instanceof String ? LinkRecord.parseLocators((String) raw) : null;
        return new LinkStore.Outcome(LinkStore.LIVE, locators == null ? null : LinkStore.locatorsJson(locators), when, hashOf(reply));
    }

    private static String hashOf(Bundle reply) {
        Object hash = reply == null ? null : reply.get("hash");
        return hash instanceof String && HASH.matcher((String) hash).matches() ? (String) hash : null;
    }

    /**
     * The provider call (also used by Open), with the owner's identity checked first, a time limit, and at most one call in flight
     * per owner. The slot is freed when the call really ends, or at once if the call was cancelled before it ever started.
     */
    @SuppressWarnings("deprecation")
    static Bundle call(Context context, String owner, String method, String linkId, Bundle extras) throws Exception {
        if (!LinkCallers.trustedOwnerProvider(context, owner)) throw new IllegalStateException("untrusted or missing provider");
        if (!IN_FLIGHT.add(owner)) throw new Busy();
        final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        Future<Bundle> future;
        try {
            future = CALLS.submit(() -> {
                started.set(true);
                try { return context.getContentResolver().call(Uri.parse("content://" + owner + ".stacklink"), method, linkId, extras); }
                finally { IN_FLIGHT.remove(owner); }
            });
        } catch (RejectedExecutionException full) { IN_FLIGHT.remove(owner); throw new Busy(); }
        try { return future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (TimeoutException late) {
            if (future.cancel(true) && !started.get()) IN_FLIGHT.remove(owner);
            throw late;
        }
    }

    private static <T> T io(StackApplication app, Callable<T> work) throws Exception {
        Future<T> future = app.io.submit(work);
        try { return future.get(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (TimeoutException late) { future.cancel(true); throw late; }
    }
}
