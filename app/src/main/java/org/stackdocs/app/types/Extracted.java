package org.stackdocs.app.types;

import java.util.Collections;
import java.util.List;

/** What a handler read from a file. `reason` is plain words for the user (null for a clean TEXT). */
public final class Extracted {
    public enum Status { TEXT, PROTECTED, UNSUPPORTED, UNREADABLE, TOO_LARGE }

    public final Status status;
    public final List<String> pages;
    public final String reason;
    /** A title the file carries itself (Ami Pro), or null. */
    public final String title;
    /**
     * True when the reader gave up on the file but a thread of it may still be running in the process (the native reader's timeout): the extract process
     * writes its answer and then ends itself, so that thread dies with it. Not part of the answer file.
     */
    public final boolean endsProcess;

    private Extracted(Status status, List<String> pages, String reason, String title) { this(status, pages, reason, title, false); }

    private Extracted(Status status, List<String> pages, String reason, String title, boolean endsProcess) {
        this.status = status; this.pages = Collections.unmodifiableList(pages); this.reason = reason; this.title = title; this.endsProcess = endsProcess;
    }

    /** The native reader hit its own time limit: unreadable for now, and the process that ran it must not be reused. */
    public static Extracted timedOut(String reason) { return new Extracted(Status.UNREADABLE, Collections.<String>emptyList(), reason, null, true); }

    public static Extracted text(List<String> pages, String title, String note) { return new Extracted(Status.TEXT, pages, note, title); }
    public static Extracted protectedFile() { return new Extracted(Status.PROTECTED, Collections.<String>emptyList(), "This file is password protected.", null); }
    public static Extracted unsupported(String reason) { return new Extracted(Status.UNSUPPORTED, Collections.<String>emptyList(), reason, null); }
    public static Extracted unreadable(String reason) { return new Extracted(Status.UNREADABLE, Collections.<String>emptyList(), reason, null); }
    public static Extracted tooLarge(String reason) { return new Extracted(Status.TOO_LARGE, Collections.<String>emptyList(), reason, null); }
}
