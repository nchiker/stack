package org.stackdocs.app;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Immutable UI snapshot. Titles and categories never determine storage paths. */
final class Document {
    final String id;
    final String title;
    final String mime;
    final long addedAt;
    final int pageCount;
    final List<String> stackIds;
    final String snippet;
    final String matchPageId;

    Document(String id, String title, String mime, long addedAt, int pageCount,
             List<String> stackIds, String snippet) {
        this(id,title,mime,addedAt,pageCount,stackIds,snippet,null);
    }

    Document(String id, String title, String mime, long addedAt, int pageCount,
             List<String> stackIds, String snippet, String matchPageId) {
        this.id = id;
        this.title = title;
        this.mime = mime;
        this.addedAt = addedAt;
        this.pageCount = pageCount;
        this.stackIds = Collections.unmodifiableList(new ArrayList<>(stackIds));
        this.snippet = snippet;
        this.matchPageId = matchPageId;
    }
}
