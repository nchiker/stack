package org.stackdocs.app;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Work item D6, the pure part of the purge of retained link blobs `sync_blobs/<sha>` (design section 4, "Purge").
 * A kept file is deleted only when (1) its name is a SHA-256 digest, (2) that digest was EVER a link cell digest (so a document
 * original, or any file this code cannot tell apart, is never touched) and (3) it has zero references. The caller builds the
 * reference set from: `files/` refs of history and pending operations (document originals share the directory), current link
 * cells, conflicted alternatives, unacknowledged and pending operations, the link projection and rows still waiting for a preview.
 */
final class LinkPurge {
    private LinkPurge() { }

    static boolean isDigest(String name) {
        if (name == null || name.length() != 64) return false;
        for (int i = 0; i < 64; i++) { char c = name.charAt(i); if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false; }
        return true;
    }

    /** The names in `onDisk` that may be deleted now: digests that were ever link digests and have no reference at all. */
    static TreeSet<String> deletable(Collection<String> onDisk, Set<String> everLink, Set<String> referenced) {
        TreeSet<String> out = new TreeSet<>();
        if (onDisk == null) return out;
        for (String name : onDisk) if (isDigest(name) && everLink.contains(name) && !referenced.contains(name)) out.add(name);
        return out;
    }
}
