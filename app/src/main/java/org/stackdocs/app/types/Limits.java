package org.stackdocs.app.types;

/** What one handler may spend on one file. maxChars is the cap on extracted text (0 = none). */
public final class Limits {
    public final long maxBytes;
    public final int maxPages;
    public final long maxMillis;
    public final int maxChars;

    public Limits(long maxBytes, int maxPages, long maxMillis, int maxChars) {
        this.maxBytes = maxBytes; this.maxPages = maxPages; this.maxMillis = maxMillis; this.maxChars = maxChars;
    }

    public Limits withMaxBytes(long value) { return new Limits(value, maxPages, maxMillis, maxChars); }
    public Limits withMaxPages(int value) { return new Limits(maxBytes, value, maxMillis, maxChars); }
    public Limits withMaxChars(int value) { return new Limits(maxBytes, maxPages, maxMillis, value); }
}
