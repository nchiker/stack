package org.stackdocs.app;

import android.content.Context;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Upgrade check, read only: does updating the app keep the library? Run {@code upgradeRecord} on the old build and keep its line,
 * install the new build over it, then run {@code upgradeVerify} with that line. The line holds counts and one SHA-256 over the
 * sorted ids of documents, linked documents and stacks. It carries no titles, text or keys, and nothing here writes to the library.
 */
final class UpgradeChecks {
    private UpgradeChecks() {}

    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    /** {@code docs=N,links=M,stacks=K,digest=<64 hex>}. */
    static String snapshot(Context target) throws Exception {
        StackApplication app = (StackApplication) target.getApplicationContext();
        require(app.libraryReady.await(60, TimeUnit.SECONDS), "the library did not open");
        List<String> ids = new ArrayList<>();
        int docs = 0, links = 0, stacks = 0;
        for (Document d : app.library.documents("", null)) { ids.add("d:" + d.id); docs++; }
        for (LinkStore.Link l : app.library.links.list("")) { ids.add("l:" + l.id); links++; }
        for (StackCategory s : app.library.stacks()) { ids.add("s:" + s.id); stacks++; }
        Collections.sort(ids);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String id : ids) digest.update((id + "\n").getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return "docs=" + docs + ",links=" + links + ",stacks=" + stacks + ",digest=" + hex;
    }

    static void record(Context target, Consumer<String> mark) throws Exception {
        mark.accept("UPGRADE-RECORD " + snapshot(target));
    }

    static void verify(Context target, String expected, Consumer<String> mark) throws Exception {
        require(expected != null && expected.matches("docs=\\d+,links=\\d+,stacks=\\d+,digest=[0-9a-f]{64}"), "the recorded line is not in the expected form");
        String now = snapshot(target);
        require(expected.equals(now), "the library changed across the update: before " + counts(expected) + ", now " + counts(now));
        mark.accept("The update kept the library (" + counts(now) + ", same ids)");
    }

    private static String counts(String line) { return line.substring(0, line.indexOf(",digest=")); }
}
