package org.stackdocs.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, bounded text-only snapshot. Never logged. */
final class SuggestionJob {
    final String documentId, claim, input, evidence;
    final long generation, titleRevision, filingRevision;
    final List<String> pages, stackIds;
    final boolean active, partial, truncated;
    SuggestionJob(String id, String claim, long generation, String input, String evidence,
                  long titleRevision, long filingRevision, List<String> pages, List<String> stackIds,
                  boolean active, boolean partial, boolean truncated) {
        this.documentId=id; this.claim=claim; this.generation=generation; this.input=input; this.evidence=evidence;
        this.titleRevision=titleRevision; this.filingRevision=filingRevision;
        this.pages=Collections.unmodifiableList(new ArrayList<>(pages));
        this.stackIds=Collections.unmodifiableList(new ArrayList<>(stackIds));
        this.active=active; this.partial=partial; this.truncated=truncated;
    }
}
