package org.stackdocs.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/** Pure checks for the companion list: fake release JSON and fake package info, no network. */
final class CompanionChecks {
    private CompanionChecks() {}

    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static String release(Companions.App app, String tag, String... assets) {
        StringBuilder list = new StringBuilder();
        for (String asset : assets) list.append(list.length() == 0 ? "" : ",").append(asset);
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"" + app.page() + "tag/" + tag + "\",\"assets\":[" + list + "]}";
    }
    private static String asset(Companions.App app, String name, String extra) {
        return "{\"name\":\"" + name + "\",\"size\":1000,\"browser_download_url\":\"" + app.prefix() + "v1/" + name + "\"" + extra + "}";
    }
    private static Companions.Problem parseProblem(Companions.App app, String json) {
        try { Companions.parse(app, json); return null; } catch (Companions.Fail fail) { return fail.problem; }
    }
    private static Companions.PkgInfo info(String pkg, long code, String cert, boolean multiple) {
        return new Companions.PkgInfo(pkg, code, cert == null ? new ArrayList<>() : Arrays.asList(cert), multiple);
    }

    static void run(Consumer<String> mark) throws Exception {
        Companions.App f = Companions.FILEN, a = Companions.AMIPRO, s = Companions.STACK;
        // The pinned list.
        require(Companions.ALL.size() == 3 && s.pkg.equals("org.stackdocs.app") && f.repo.equals("nchiker/Filen-Filemanager") && a.pkg.equals("app.amipro.editor"), "pinned list");
        require(f.certs.get(0).length() == 64 && a.certs.get(0).startsWith("9a387c70") && s.certs.get(0).endsWith("e311"), "pins are SHA-256 lists");
        mark.accept("Companion pins: three apps, certificate lists");

        // Version comparison.
        require(UpdateCheck.newer("0.4.8", "0.4.7") && !UpdateCheck.newer("0.4.7", "0.4.7") && UpdateCheck.newer("0.10.0", "0.9.9"), "versions compare as numbers");
        mark.accept("Companion version comparison");

        // Asset selection.
        Companions.Release one = Companions.parse(f, release(f, "v0.4.7", asset(f, "Filen-0.4.7.apk", "")));
        require(one.version.equals("0.4.7") && one.apkName.equals("Filen-0.4.7.apk") && one.sums.isEmpty() && one.digest.isEmpty(), "one apk, no checksum");
        String hash = "ab".repeat(32);
        Companions.Release withSums = Companions.parse(a, release(a, "1.2.0", asset(a, "AmiPro.apk", ",\"digest\":\"sha256:" + hash + "\""), asset(a, "SHA256SUMS", "")));
        require(withSums.sums.endsWith("/SHA256SUMS") && withSums.digest.equals(hash), "sums and digest read");
        require(parseProblem(f, release(f, "v1", asset(f, "notes.txt", ""))) == Companions.Problem.NO_APK, "no apk asset");
        require(parseProblem(f, release(f, "v1")) == Companions.Problem.NO_APK, "empty release");
        require(parseProblem(f, release(f, "v1", asset(f, "a.apk", ""), asset(f, "b.apk", ""))) == Companions.Problem.NO_APK, "two apks refused");
        require(parseProblem(f, "{\"tag_name\":\"v1\",\"assets\":[{\"name\":\"a.apk\",\"browser_download_url\":\"https://example.com/a.apk\"}]}") == Companions.Problem.NO_APK, "foreign host refused");
        require(parseProblem(f, "{\"tag_name\":\"v1\",\"assets\":[{\"name\":\"a.apk\",\"browser_download_url\":\"" + a.prefix() + "v1/a.apk\"}]}") == Companions.Problem.NO_APK, "another app's repo refused");
        require(parseProblem(f, "not json") == Companions.Problem.FAILED, "garbage is a calm failure");
        mark.accept("Companion asset selection");

        // Integrity: sums, else digest, else none; present but different always refuses.
        String file = "Filen-0.4.7.apk", other = "cd".repeat(32);
        Companions.Integrity bySums = Companions.integrity(hash + "  " + file + "\n", other.equals(hash) ? "" : "", file);
        require(!bySums.refuse && hash.equals(bySums.want), "SHA256SUMS used first");
        Companions.Integrity byDigest = Companions.integrity(null, hash, file);
        require(!byDigest.refuse && hash.equals(byDigest.want), "digest used when no sums");
        Companions.Integrity none = Companions.integrity(null, "", file);
        require(!none.refuse && none.want == null, "no checksum at all is allowed");
        require(!Companions.integrity("zz  other.apk\n", "", file).refuse && Companions.integrity("zz  other.apk\n", "", file).want == null, "sums without our file falls through");
        require(Companions.integrity(hash + "  " + file, other, file).refuse, "sums and digest that disagree refuse");
        mark.accept("Companion integrity: sums, digest, none");

        // Pin verification.
        String good = f.certs.get(0), bad = "00".repeat(32);
        require(Companions.verify(f, info("app.filenfiles", 48, good, false), 47L) == null, "accept: right app, newer, right cert");
        require(Companions.verify(f, info("app.filenfiles", 48, good.toUpperCase(), false), null) == null, "accept: not installed, upper-case hash");
        require(Companions.verify(f, info("app.amipro.editor", 48, good, false), null) == Companions.Problem.WRONG_APP, "reject: wrong package");
        require(Companions.verify(f, null, null) == Companions.Problem.WRONG_APP, "reject: unreadable file");
        require(Companions.verify(f, info("app.filenfiles", 47, good, false), 47L) == Companions.Problem.NOT_NEWER, "reject: same version");
        require(Companions.verify(f, info("app.filenfiles", 40, good, false), 47L) == Companions.Problem.NOT_NEWER, "reject: older version");
        require(Companions.verify(f, info("app.filenfiles", 48, bad, false), 47L) == Companions.Problem.SIGNATURE, "reject: wrong cert");
        require(Companions.verify(f, info("app.filenfiles", 48, null, false), 47L) == Companions.Problem.SIGNATURE, "reject: no cert");
        require(Companions.verify(f, info("app.filenfiles", 48, good, true), 47L) == Companions.Problem.SIGNATURE, "reject: multiple signers");
        require(Companions.verify(f, new Companions.PkgInfo("app.filenfiles", 48, Arrays.asList(good, bad), false), 47L) == Companions.Problem.SIGNATURE, "reject: two certs");
        Companions.App rotated = new Companions.App("X", "x.y", "o/r", bad, good);
        require(Companions.verify(rotated, info("x.y", 2, good, false), 1L) == null && Companions.verify(rotated, info("x.y", 2, bad, false), 1L) == null, "any cert in the list is accepted");
        require(Companions.verify(rotated, info("x.y", 2, "11".repeat(32), false), 1L) == Companions.Problem.SIGNATURE, "cert outside the list refused");
        mark.accept("Companion pin verification");

        // State to label.
        require(Companions.state(f, null, "0.4.7", false) == Companions.State.NOT_INSTALLED && Companions.label(Companions.State.NOT_INSTALLED, null, "") .equals("Not installed"), "not installed");
        require(Companions.state(f, "0.4.7", "0.4.7", false) == Companions.State.UP_TO_DATE && Companions.label(Companions.State.UP_TO_DATE, "0.4.7", "0.4.7").equals("Installed · version 0.4.7"), "installed");
        require(Companions.state(f, "0.4.7", "0.4.8", false) == Companions.State.UPDATE && Companions.label(Companions.State.UPDATE, "0.4.7", "0.4.8").equals("Update available · version 0.4.8"), "update");
        require(Companions.state(f, "0.4.7", "", false) == Companions.State.UP_TO_DATE, "unknown latest is not an update");
        require(Companions.state(s, "0.19.0", "9.0.0", true) == Companions.State.TEST_BUILD && Companions.button(Companions.State.TEST_BUILD) == null, "test build gets no update");
        require("Install".equals(Companions.button(Companions.State.NOT_INSTALLED)) && "Update".equals(Companions.button(Companions.State.UPDATE)) && Companions.button(Companions.State.UP_TO_DATE) == null, "buttons");
        for (Companions.Problem p : Companions.Problem.values()) require(!Companions.message(p).isEmpty() && !Companions.message(p).contains("/") && !Companions.message(p).toLowerCase().contains("sha"), "calm plain message " + p);
        require(Companions.message(Companions.Problem.SIGNATURE).contains("nothing was installed"), "signature message says nothing was installed");
        mark.accept("Companion states and messages");

        // Update all ordering and resume.
        List<String> order = Companions.queue(Companions.ALL, app -> Companions.State.UPDATE);
        require(order.equals(Arrays.asList("app.filenfiles", "app.amipro.editor", "org.stackdocs.app")), "Stack goes last");
        require(Companions.queue(Companions.ALL, app -> app == a ? Companions.State.NOT_INSTALLED : Companions.State.UP_TO_DATE).equals(Collections.singletonList("app.amipro.editor")), "only apps that need something");
        require(Companions.queue(Companions.ALL, app -> Companions.State.TEST_BUILD).isEmpty(), "test build is never queued");
        List<String> pending = new ArrayList<>(Arrays.asList("app.filenfiles", "app.amipro.editor"));
        require("app.filenfiles".equals(Companions.next(pending, new HashSet<>())), "first in line");
        require("app.amipro.editor".equals(Companions.next(pending, new HashSet<>(Collections.singletonList("app.filenfiles")))), "skips what is already done");
        require(Companions.next(pending, new HashSet<>(pending)) == null, "finished");
        require(Companions.afterResult(pending, "app.filenfiles", true).equals(Collections.singletonList("app.amipro.editor")), "success carries on");
        require(Companions.afterResult(pending, "app.filenfiles", false).isEmpty(), "cancel or refusal stops the walk");
        require(Companions.problemFor(android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED).equals("CANCELLED")
                && Companions.problemFor(android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT).equals("SIGNATURE")
                && Companions.problemFor(android.content.pm.PackageInstaller.STATUS_FAILURE).equals("FAILED"), "installer results map to messages");
        mark.accept("Companion Update all ordering and resume");
    }
}
