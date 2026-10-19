package org.stackdocs.app;

import java.io.File;

/** Immutable original/edit snapshot. Current displayed position is deliberately absent. */
final class OcrJob {
    final String pageId, documentId, claim, config, mime;
    final long revision;
    final PageState page;
    final File source;
    OcrJob(String documentId, PageState page, String claim, long revision, String config, String mime, File source) {
        this.documentId = documentId; this.pageId = page.id; this.page = page;
        this.claim = claim; this.revision = revision; this.config = config;
        this.mime = mime; this.source = source;
    }
}
