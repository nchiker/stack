package org.stackdocs.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** D6 host checks of the production LinkPurge (pure reference counting for retained link blobs). */
public final class LinkPurgeHost {
    static int failures;
    static void check(boolean ok, String name) { if (!ok) { failures++; System.out.println("FAIL " + name); } }
    static String h(char c) { return String.valueOf(c).repeat(64); }
    static Set<String> set(String... v) { return new HashSet<>(Arrays.asList(v)); }

    public static void main(String[] args) {
        check(LinkPurge.isDigest(h('a')) && LinkPurge.isDigest(h('0')), "lowercase hex digest");
        check(!LinkPurge.isDigest(h('A')) && !LinkPurge.isDigest("abc") && !LinkPurge.isDigest(null) && !LinkPurge.isDigest(h('a') + "x") && !LinkPurge.isDigest(".link-x.part"), "not a digest");
        // Unreferenced link digest goes; referenced stays.
        TreeSet<String> out = LinkPurge.deletable(Arrays.asList(h('a'), h('b')), set(h('a'), h('b')), set(h('b')));
        check(out.size() == 1 && out.contains(h('a')), "only the unreferenced link digest is deletable");
        // A digest that was never a link digest (document original) is never deletable, even with zero references.
        check(LinkPurge.deletable(Arrays.asList(h('c')), set(h('a')), set()).isEmpty(), "document original never deleted");
        // Shared digest: stays while any reference remains (the caller unions references of both links).
        check(LinkPurge.deletable(Arrays.asList(h('d')), set(h('d')), set(h('d'))).isEmpty(), "shared digest kept while referenced");
        // Temp files and odd names are untouched.
        check(LinkPurge.deletable(Arrays.asList(".link-1.part", ".sync-2.part", "x"), set(".link-1.part", "x"), set()).isEmpty(), "non-digest names untouched");
        check(LinkPurge.deletable(null, set(), set()).isEmpty() && LinkPurge.deletable(Arrays.<String>asList(), set(h('a')), set()).isEmpty(), "empty inputs");
        // A digest that was once a link digest and has no reference at all is deletable (a never-link file is not).
        check(LinkPurge.deletable(Arrays.asList(h('e')), set(h('e')), set()).contains(h('e')), "unreferenced ever-link digest deletable");
        if (failures > 0) { System.out.println(failures + " failure(s)"); System.exit(1); }
        System.out.println("LinkPurgeHost ok");
    }
}
