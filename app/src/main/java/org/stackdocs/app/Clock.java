package org.stackdocs.app;

/**
 * The one source of calendar ("wall clock") time in Stack: stored timestamps, "is a
 * backup due", reminder ages, retention, trash expiry, the update check and file names
 * all read {@link #now()}, so a check can fix the time with {@link #fix(long)} and get
 * the same result on every run. The build refuses System.currentTimeMillis() and
 * new Date() anywhere else (app/build.gradle, checkClockUse).
 *
 * Timeouts and deadlines use SystemClock.elapsedRealtime(), which is monotonic and stays
 * real: a frozen calendar clock must never make a network wait hang.
 */
final class Clock {
    private static final long REAL = Long.MIN_VALUE;
    private static volatile long fixed = REAL;

    private Clock() {}

    /** Milliseconds since 1970. */
    static long now() {
        long value = fixed;
        return value == REAL ? System.currentTimeMillis() : value;
    }

    /** Checks only: holds the clock at {@code millis} until closed. */
    static Fixed fix(long millis) {
        Fixed holder = new Fixed();
        holder.set(millis);
        return holder;
    }

    static final class Fixed implements AutoCloseable {
        private Fixed() {}
        void set(long millis) {
            if (millis == REAL) throw new IllegalArgumentException("reserved value");
            fixed = millis;
        }
        void advance(long millis) { set(now() + millis); }
        @Override public void close() { fixed = REAL; }
    }
}
