package org.stackdocs.app;

/** P1 host checks of the production LinkBudget (pure logic: inclusion order, distinct-blob meter, UTF-8 length, presence mapping). */
public final class LinkBudgetHost {
    static int failures;
    static void check(boolean ok, String name) { if (!ok) { failures++; System.out.println("FAIL " + name); } }

    public static void main(String[] args) {
        // Sticky inclusion order: synced first, then added_at ascending, then id.
        int[] order = LinkBudget.inclusionOrder(new long[]{30, 10, 20, 5, 10}, new boolean[]{false, false, true, false, false}, new String[]{"d", "b", "c", "a", "a2"});
        check(java.util.Arrays.equals(order, new int[]{2, 3, 4, 1, 0}) || java.util.Arrays.equals(order, new int[]{2, 3, 1, 4, 0}), "synced first, then added_at");
        check(order[2] == 4 || order[2] == 1, "tie on added_at is decided by id");
        check(LinkBudget.orderedIds(new long[]{10, 10}, new boolean[]{false, false}, new String[]{"z", "a"}).equals(java.util.Arrays.asList("a", "z")), "id breaks a tie deterministically");
        check(LinkBudget.orderedIds(new long[]{1, 2}, new boolean[]{false, true}, new String[]{"old", "synced"}).get(0).equals("synced"), "a link already synced stays in even if newer");
        // Meter: distinct digests counted once, cap respected, force never refuses.
        LinkBudget.Meter meter = new LinkBudget.Meter(100);
        check(meter.tryAdd("a", 60), "first fits");
        check(meter.tryAdd("a", 60), "same digest is free");
        check(!meter.tryAdd("b", 50), "over the cap refused");
        check(meter.total() == 60, "a refused blob is not counted");
        check(meter.tryAdd("b", 40) && meter.total() == 100, "exact fit");
        meter.force("c", 500);
        check(meter.total() == 600 && meter.contains("c"), "force counts without a cap");
        check(!new LinkBudget.Meter(10).tryAdd("x", -1), "a negative length is refused");
        // 2 GiB / 6 GiB, the design numbers.
        check(LinkBudget.DEVICE_BYTES == 2147483648L && LinkBudget.UNACKED_BYTES == 6442450944L, "2 GiB and 6 GiB");
        LinkBudget.Meter device = new LinkBudget.Meter(LinkBudget.DEVICE_BYTES);
        int fit = 0; for (int i = 0; i < 100; i++) if (device.tryAdd("p" + i, 32L << 20)) fit++;
        check(fit == 64, "2 GiB holds sixty-four 32 MiB previews");
        // Text length is UTF-8 bytes, not UTF-16 units.
        check(LinkBudget.utf8Length("") == 0 && LinkBudget.utf8Length("abc") == 3, "ascii");
        check(LinkBudget.utf8Length("é") == 2 && LinkBudget.utf8Length("€") == 3, "two and three byte characters");
        check(LinkBudget.utf8Length("😀") == 4, "a surrogate pair is four bytes");
        check(LinkBudget.utf8Length("\ud83d") == 3, "an unpaired surrogate counts three (the writer refuses such text elsewhere)");
        check(LinkBudget.utf8Length("€".repeat(400_000)) == 1_200_000 && "€".repeat(400_000).length() == 400_000, "400,000 units can be over 1 MiB of bytes");
        check(LinkBudget.utf8Length("a".repeat(1_048_576)) == 1_048_576, "exactly 1 MiB");
        // Presence mapping.
        check(java.util.Arrays.equals(LinkBudget.presence("live", "", 9), new String[]{"live", "", "0"}), "live uses since 0");
        check(java.util.Arrays.equals(LinkBudget.presence("unreachable", "", 9), new String[]{"live", "", "0"}), "unreachable is live");
        check(java.util.Arrays.equals(LinkBudget.presence("missing", "", 9), new String[]{"live", "", "0"}), "missing is live");
        check(java.util.Arrays.equals(LinkBudget.presence("trashed", "user", 9), new String[]{"trashed", "user", "9"}), "trashed keeps reason and since");
        // H1: what a saved text becomes is decided from its UTF-8 byte length alone (no body is read for the decision).
        check(LinkBudget.textSize(-1) == LinkBudget.TextSize.ABSENT && LinkBudget.textSize(0) == LinkBudget.TextSize.EMPTY, "no row and an empty text");
        check(LinkBudget.textSize(1) == LinkBudget.TextSize.READ && LinkBudget.textSize(1_048_576) == LinkBudget.TextSize.READ, "up to 1 MiB is read");
        check(LinkBudget.textSize(1_048_577) == LinkBudget.TextSize.TOO_BIG, "one byte over 1 MiB skips the cell");
        check(LinkBudget.textSize(3_000_000) == LinkBudget.TextSize.TOO_BIG && 3_000_000 > (2 << 20), "3 MB of UTF-8 (over a 2 MB CursorWindow) is a skipped cell, never a read");
        check(LinkBudget.utf8Length("\u20ac".repeat(1_000_000)) == 3_000_000, "1,000,000 euro signs are 3,000,000 bytes");
        check(LinkBudget.TEXT_MAX_BYTES == 1_048_576, "text cap constant");
        // M2: a metadata-only outcome keeps the phone's text unless the link becomes protected or the hash changes.
        check(!LinkBudget.replacesBlob(true, true, false, false), "protected link plus a desktop filen change keeps text and preview");
        check(LinkBudget.replacesBlob(true, false, false, false), "a link that becomes protected loses them");
        check(LinkBudget.replacesBlob(true, true, true, false), "a new hash replaces them");
        check(LinkBudget.replacesBlob(false, false, true, false) && LinkBudget.replacesBlob(false, false, false, true), "a received cell or a new hash replaces");
        check(!LinkBudget.replacesBlob(false, false, false, false), "an unchanged ordinary link keeps them");
        // M3: the per-capture byte cap is 256 MiB, counts distinct work once, and refuses what does not fit in the sticky order.
        check(LinkBudget.CAPTURE_BLOB_BYTES == 256L << 20, "256 MiB per capture");
        LinkBudget.Meter work = new LinkBudget.Meter(LinkBudget.CAPTURE_BLOB_BYTES);
        int done = 0; for (int i = 0; i < 20; i++) if (work.tryAdd("copy:" + i, 32L << 20)) done++;
        check(done == 8, "eight 32 MiB previews fit in one capture, the rest wait for the retry");
        check(work.tryAdd("copy:3", 32L << 20), "work already counted is free again within the capture");
        check(LinkBudget.orderedIds(new long[]{1, 2, 3}, new boolean[]{false, true, false}, new String[]{"a", "b", "c"}).equals(java.util.Arrays.asList("b", "a", "c")), "the order the cap consumes is sticky and deterministic");
        // L1: of several declared lengths the one the kept file really has is sent; none matching means it is left out.
        java.util.TreeSet<Long> two = new java.util.TreeSet<>(java.util.Arrays.asList(10L, 11L));
        check(LinkBudget.declaredLengthFor(two, 11) == 11 && LinkBudget.declaredLengthFor(two, 10) == 10, "the real length of two declared is chosen");
        check(LinkBudget.declaredLengthFor(two, 12) == -1 && LinkBudget.declaredLengthFor(two, -1) == -1, "a missing or different file is left out, not an error");
        if (failures != 0) { System.out.println(failures + " failures"); System.exit(1); }
        System.out.println("LinkBudget host checks passed");
    }
}
