package org.stackdocs.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure rules of the link blob budget (docs/STACK_LINK_SLICE6_DESIGN.md section 2, "Total byte budget"). No Android classes, so a
 * host JVM runs the production code (desktop/tools/test_link_budget_java.py).
 */
final class LinkBudget {
    private LinkBudget() {}
    /** Distinct link blob bytes referenced by CURRENT link cells, per device. */
    static final long DEVICE_BYTES = 2L << 30;
    /** Link blob bytes referenced by operations the peer has not acknowledged yet. */
    static final long UNACKED_BYTES = 6L << 30;

    /**
     * Deterministic, sticky inclusion order: links whose blobs were already synced first, then by `added_at` ascending, then by
     * id so the order never depends on the database. Returns indexes into the arrays.
     */
    static int[] inclusionOrder(long[] addedAt, boolean[] alreadySynced, String[] ids) {
        Integer[] order = new Integer[addedAt.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            if (alreadySynced[a] != alreadySynced[b]) return alreadySynced[a] ? -1 : 1;
            int byTime = Long.compare(addedAt[a], addedAt[b]);
            return byTime != 0 ? byTime : ids[a].compareTo(ids[b]);
        });
        int[] out = new int[order.length];
        for (int i = 0; i < out.length; i++) out[i] = order[i];
        return out;
    }

    /** Counts DISTINCT blobs (two links may share a digest) against a cap. */
    static final class Meter {
        private final long cap;
        private final Set<String> seen = new HashSet<>();
        private long total;
        Meter(long cap) { this.cap = cap; }
        /** A digest already counted is free; otherwise it is added only if it fits. Returns whether the blob is allowed. */
        boolean tryAdd(String sha, long length) {
            if (seen.contains(sha)) return true;
            if (length < 0 || total + length > cap) return false;
            seen.add(sha); total += length;
            return true;
        }
        /** Counts a blob that is there already (an acknowledged-pending one), without a cap check. */
        void force(String sha, long length) { if (seen.add(sha)) total += length; }
        boolean contains(String sha) { return seen.contains(sha); }
        long total() { return total; }
    }

    /** UTF-8 length of `text` without allocating the bytes when it is short (the cell length is bytes, not UTF-16 units). */
    static long utf8Length(String text) {
        long n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) n += 1;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) { n += 4; i++; }
            else n += 3;
        }
        return n;
    }

    /** Presence mapping (design section 2): live/unreachable/missing are `live`; trashed stays trashed with its reason and since. */
    static String[] presence(String state, String removedReason, long stateSince) {
        if ("trashed".equals(state)) return new String[]{"trashed", removedReason, Long.toString(stateSince)};
        return new String[]{"live", "", "0"};
    }

    /** M3: bytes one capture may newly hash or copy (a first enable must not hold the session for minutes); the rest is retried. */
    static final long CAPTURE_BLOB_BYTES = 256L << 20;
    /** The largest text cell, in UTF-8 bytes (the same limit as `SyncGraph.LINK_TEXT_BYTES`). */
    static final long TEXT_MAX_BYTES = 1L << 20;

    enum TextSize { ABSENT, EMPTY, READ, TOO_BIG }
    /**
     * H1: what to do with a saved text, decided from its UTF-8 byte length alone (the SQL `length(CAST(body AS BLOB))`, a negative
     * number for "no row"), so a body is read only when it can become a cell and a huge one never reaches a CursorWindow.
     */
    static TextSize textSize(long utf8Bytes) {
        if (utf8Bytes < 0) return TextSize.ABSENT;
        if (utf8Bytes == 0) return TextSize.EMPTY;
        return utf8Bytes > TEXT_MAX_BYTES ? TextSize.TOO_BIG : TextSize.READ;
    }

    /**
     * M2: whether a received change of an existing link replaces its saved text (or preview). A new hash replaces both; a received
     * cell replaces its own; a metadata-only outcome (the owner now says protected) clears them only when the link BECOMES
     * protected. A metadata-only outcome for a link already protected here (a desktop /filen or title change) keeps them.
     */
    static boolean replacesBlob(boolean metadataOnly, boolean wasProtected, boolean hashChanged, boolean cellReceived) {
        return hashChanged || cellReceived || (metadataOnly && !wasProtected);
    }

    /** L1: of the lengths declared for one digest, the one the kept file really has, or -1 (then the blob cannot be sent). */
    static long declaredLengthFor(java.util.Collection<Long> declared, long keptLength) {
        return keptLength >= 0 && declared.contains(keptLength) ? keptLength : -1;
    }

    /** M4: the inert locator for a link only the computer had, as the computer writes it (stack-core `link_sync.rs`). */
    static final String PLACEHOLDER_LOCATORS = "[{\"type\":\"owner\",\"token\":\"stack-sync\"}]";

    /** The ids ordered for a list of rows already filtered; convenience for tests. */
    static List<String> orderedIds(long[] addedAt, boolean[] synced, String[] ids) {
        List<String> out = new ArrayList<>();
        for (int i : inclusionOrder(addedAt, synced, ids)) out.add(ids[i]);
        return out;
    }
}
