package org.stackdocs.app;

/**
 * The one merge rule per link for conflicted link cells (docs/STACK_LINK_SLICE6_DESIGN.md section 3, work item P2). Pure logic, no
 * Android classes, so a host JVM runs the production code (desktop/tools/test_link_resolve_java.py).
 *
 * The rule, applied to one link whose cells conflict:
 * 1. if either side's presence is `gone`, the phone's cells win;
 * 2. else if the two `/content` hashes differ and `modified` differs, the later `modified` wins (all cells of that alternative);
 * 3. else (same hash) the phone's values win for every cell;
 * 4. only a `modified` tie with different hashes is REVIEW (phone only, one choice for the whole link).
 * A side without a `/content` cell cannot be compared; it is treated like the same hash (the phone wins), see the P2 notes.
 */
final class LinkResolve {
    private LinkResolve() {}

    enum Winner { PHONE, DESKTOP, REVIEW }

    /**
     * @param phoneGone     the phone side's presence is `gone`
     * @param desktopGone   the desktop side's presence is `gone`
     * @param phoneHash     the phone side's `/content` hash, or null when it has no content cell
     * @param desktopHash   the desktop side's `/content` hash, or null when it has no content cell
     * @param phoneModified the phone side's `/content` modified time (ignored when a hash is null)
     * @param desktopModified the desktop side's `/content` modified time (ignored when a hash is null)
     */
    static Winner decide(boolean phoneGone, boolean desktopGone, String phoneHash, String desktopHash, long phoneModified, long desktopModified) {
        if (phoneGone || desktopGone) return Winner.PHONE;
        if (phoneHash == null || desktopHash == null || phoneHash.equals(desktopHash)) return Winner.PHONE;
        if (phoneModified == desktopModified) return Winner.REVIEW;
        return phoneModified > desktopModified ? Winner.PHONE : Winner.DESKTOP;
    }
}
