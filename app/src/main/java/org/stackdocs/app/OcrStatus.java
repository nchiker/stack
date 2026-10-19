package org.stackdocs.app;

final class OcrStatus {
    final String pageId, state;
    final boolean hasText;
    final int total, finished, active, failed;
    OcrStatus(String pageId, String state, boolean hasText, int total, int finished, int active, int failed) {
        this.pageId = pageId; this.state = state; this.hasText = hasText;
        this.total = total; this.finished = finished; this.active = active; this.failed = failed;
    }
    String label() {
        String progress = active > 0 ? "Reading text… " + finished + " of " + total + " pages finished. " : "";
        if (state.equals("failed")) return progress + "Couldn't read this page. Tap to retry failed pages.";
        if (state.equals("succeeded")) return progress + (failed > 0 ? failed + (failed == 1 ? " page couldn't be read. " : " pages couldn't be read. ") + "Tap to retry failed pages. " : "") + (hasText ? "Text ready to search." : "No printed text found on this page.");
        return progress.isEmpty() ? "Reading text…" : progress.trim();
    }
}
