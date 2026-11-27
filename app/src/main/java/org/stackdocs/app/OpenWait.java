package org.stackdocs.app;

import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

/**
 * What a person sees while a file from Filen is being fetched to open: nothing new for 5 seconds, then "Waiting for Filen…" with a Cancel button,
 * and after 30 seconds without the file "Needs the internet". Cancel (or the 30 s) stops the attempt: its {@link #signal} is cancelled and an answer
 * that arrives later is ignored ({@link #live}). The timing is plain code ({@link #text}, {@link #tick}) so a check can drive it without waiting.
 */
final class OpenWait {
    static final long WAITING_MS = 5_000, GIVE_UP_MS = 30_000;
    static final String OPENING = "Opening…", WAITING = "Waiting for Filen…", NEEDS_INTERNET = "Needs the internet";

    /** The words for this long a wait. */
    static String text(long elapsedMs) { return elapsedMs >= GIVE_UP_MS ? NEEDS_INTERNET : elapsedMs >= WAITING_MS ? WAITING : OPENING; }

    /** Whether the Cancel button shows at this point of the wait. */
    static boolean cancelable(long elapsedMs) { return elapsedMs >= WAITING_MS && elapsedMs < GIVE_UP_MS; }

    interface Sink { void show(String text, boolean cancelable); }

    private final Sink sink;
    private final Handler handler = new Handler(Looper.getMainLooper());
    final CancellationSignal signal = new CancellationSignal();
    private boolean ended;

    OpenWait(Sink sink) { this.sink = sink; }

    /** Main thread: shows "Opening…" and schedules the two changes. */
    void start() {
        sink.show(OPENING, false);
        handler.postDelayed(() -> tick(WAITING_MS), WAITING_MS);
        handler.postDelayed(() -> tick(GIVE_UP_MS), GIVE_UP_MS);
    }

    /** Main thread: the wait has lasted `elapsedMs`. */
    void tick(long elapsedMs) {
        if (ended) return;
        sink.show(text(elapsedMs), cancelable(elapsedMs));
        if (elapsedMs >= GIVE_UP_MS) stop();
    }

    /** The Cancel button. */
    void cancel() { stop(); }

    /** The file arrived (or failed): stop the timers without touching the screen. */
    void done() { stop(); }

    boolean live() { return !ended; }

    private void stop() {
        ended = true;
        handler.removeCallbacksAndMessages(null);
        try { signal.cancel(); } catch (RuntimeException ignored) { /* the provider did not take it */ }
    }
}
