package org.stackdocs.app;

/** P2 host checks of the production LinkResolve rule (one merge rule per link; pure logic). */
public final class LinkResolveHost {
    static int failures;
    static void check(boolean ok, String name) { if (!ok) { failures++; System.out.println("FAIL " + name); } }

    public static void main(String[] args) {
        LinkResolve.Winner phone = LinkResolve.Winner.PHONE, desk = LinkResolve.Winner.DESKTOP, review = LinkResolve.Winner.REVIEW;
        String a = "a".repeat(64), b = "b".repeat(64);
        // Rule 1: gone on either side, the phone wins whatever the times and hashes say.
        check(LinkResolve.decide(true, false, a, b, 1, 9) == phone, "phone gone beats a later desktop change");
        check(LinkResolve.decide(false, true, a, b, 1, 9) == phone, "desktop gone: the phone still wins");
        check(LinkResolve.decide(true, true, a, b, 9, 1) == phone, "both gone: the phone wins");
        // Rule 2: different hashes, different modified: the later modified wins.
        check(LinkResolve.decide(false, false, a, b, 1, 2) == desk, "later desktop modified wins");
        check(LinkResolve.decide(false, false, a, b, 2, 1) == phone, "later phone modified wins");
        // Rule 3: same hash: the phone wins (whatever the modified values).
        check(LinkResolve.decide(false, false, a, a, 1, 9) == phone, "same hash: phone wins, desktop newer");
        check(LinkResolve.decide(false, false, a, a, 9, 1) == phone, "same hash: phone wins, phone newer");
        // Rule 4: a modified tie with different hashes goes to review.
        check(LinkResolve.decide(false, false, a, b, 5, 5) == review, "modified tie with different hashes is review");
        // A side without content cannot be compared: the phone wins (documented edge).
        check(LinkResolve.decide(false, false, null, b, 5, 9) == phone, "no phone content: phone wins");
        check(LinkResolve.decide(false, false, a, null, 5, 9) == phone, "no desktop content: phone wins");
        check(LinkResolve.decide(false, false, null, null, 0, 0) == phone, "no content on either side: phone wins");
        System.out.println(failures == 0 ? "LinkResolveHost: all checks passed" : "LinkResolveHost: " + failures + " failed");
        if (failures != 0) System.exit(1);
    }
}
