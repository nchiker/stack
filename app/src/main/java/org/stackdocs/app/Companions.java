package org.stackdocs.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The pinned family of apps Stack can install and update: one list, one set of rules. Each app comes only from
 * its own GitHub repository's latest release (exactly one .apk). Before anything is handed to Android the download
 * is checked (published checksum when there is one), then its package name, version and signing certificate are
 * compared with the pin. Android then asks the person to confirm each install; nothing is ever silent.
 * The decision logic here is plain Java so checks can feed it fake releases and fake package info.
 */
final class Companions {
    static final String EXTRA = "companion";
    private static final String PREFS = "companions";
    private static final long STALE = 10L * 60 * 1000;
    private static final Pattern DIGEST = Pattern.compile("^sha256:([0-9a-fA-F]{64})$");
    private static final AtomicBoolean running = new AtomicBoolean();

    /** One pinned app. {@code certs} are accepted signing-certificate SHA-256 values (several allows a key change later). */
    static final class App {
        final String name, pkg, repo; final List<String> certs;
        App(String name, String pkg, String repo, String... certs) {
            this.name = name; this.pkg = pkg; this.repo = repo;
            List<String> list = new ArrayList<>();
            for (String cert : certs) list.add(cert.toLowerCase(Locale.ROOT));
            this.certs = Collections.unmodifiableList(list);
        }
        String prefix() { return "https://github.com/" + repo + "/releases/download/"; }
        String page() { return "https://github.com/" + repo + "/releases/"; }
        boolean isStack() { return pkg.equals("org.stackdocs.app"); }
    }

    static final App STACK = new App("Stack", "org.stackdocs.app", "nchiker/stack",
            "e873f2fd869005e59828db6d4f8f31d1b05a37988818a7dd94adc1ae1b07e311");
    static final App FILEN = new App("Filen Files", "app.filenfiles", "nchiker/Filen-Filemanager",
            "59ccca4c8c1126ec48257a8f3051c6be5eccdd5888df82215560ce2ff8b580bf");
    static final App AMIPRO = new App("Ami Pro", "app.amipro.editor", "nchiker/AmiPro-APK",
            "9a387c70c8c19a3bb29e9b02f18840f4b1bb18ac291983d2d0ec1c684670868a");
    static final List<App> ALL = Collections.unmodifiableList(Arrays.asList(STACK, FILEN, AMIPRO));

    enum Problem { OFFLINE, RATE_LIMIT, NO_APK, HASH, SIGNATURE, WRONG_APP, NOT_NEWER, CANCELLED, FAILED }

    static String message(Problem problem) {
        switch (problem) {
            case OFFLINE: return "No connection. Try again when online.";
            case RATE_LIMIT: return "GitHub is busy. Try again later.";
            case NO_APK: return "This release has no app file to install yet.";
            case HASH: return "That download did not match, so nothing was installed.";
            case SIGNATURE: return "That download is not from the expected source, so nothing was installed.";
            case WRONG_APP: return "That download is the wrong app, so nothing was installed.";
            case NOT_NEWER: return "You already have this version or a newer one.";
            case CANCELLED: return "Installation was cancelled. Nothing was changed.";
            default: return "The install did not finish. Nothing was changed.";
        }
    }

    static final class Fail extends IOException {
        final Problem problem;
        Fail(Problem problem) { super(problem.name()); this.problem = problem; }
    }

    /** A release found on GitHub: version, its one APK, and whatever checksum information it published ("" if none). */
    static final class Release {
        final String version, page, apkName, apk, sums, digest; final long size;
        Release(String version, String page, String apkName, String apk, String sums, String digest, long size) {
            this.version = version; this.page = page; this.apkName = apkName; this.apk = apk; this.sums = sums; this.digest = digest; this.size = size;
        }
        JSONObject toJson() throws org.json.JSONException {
            return new JSONObject().put("version", version).put("page", page).put("apkName", apkName).put("apk", apk)
                    .put("sums", sums).put("digest", digest).put("size", size);
        }
        static Release fromJson(String text) {
            try {
                JSONObject o = new JSONObject(text);
                return new Release(o.getString("version"), o.getString("page"), o.getString("apkName"), o.getString("apk"),
                        o.getString("sums"), o.getString("digest"), o.getLong("size"));
            } catch (Exception bad) { return null; }
        }
    }

    /** Reads GitHub's "latest release" JSON for {@code app}: exactly one .apk asset from that repo's release downloads, or NO_APK. */
    static Release parse(App app, String json) throws Fail {
        JSONObject release;
        try { release = new JSONObject(json); } catch (org.json.JSONException bad) { throw new Fail(Problem.FAILED); }
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new Fail(Problem.NO_APK);
        JSONObject apk = null; String sums = "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name"), url = asset.optString("browser_download_url", "");
            if (name.equals("SHA256SUMS") && releaseFile(app, url) && url.endsWith("/SHA256SUMS")) sums = url;
            if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                if (apk != null) throw new Fail(Problem.NO_APK);   // more than one: refuse to guess
                apk = asset;
            }
        }
        if (apk == null) throw new Fail(Problem.NO_APK);
        String name = apk.optString("name"), url = apk.optString("browser_download_url", "");
        if (!releaseFile(app, url) || !url.endsWith("/" + name)) throw new Fail(Problem.NO_APK);
        String version = release.optString("tag_name", "").replaceFirst("^[vV]", "");
        if (version.isEmpty()) throw new Fail(Problem.NO_APK);
        java.util.regex.Matcher d = DIGEST.matcher(apk.optString("digest", ""));
        String page = release.optString("html_url", "");
        if (!page.startsWith(app.page())) page = app.page() + "latest";
        return new Release(version, page, name, url, sums, d.matches() ? d.group(1).toLowerCase(Locale.ROOT) : "", Math.max(0, apk.optLong("size", 0)));
    }

    static boolean releaseFile(App app, String url) {
        return url != null && url.startsWith(app.prefix()) && !url.contains("..") && !url.contains("?") && !url.contains("#");
    }

    /** What to compare the download with: {@code want} is a SHA-256 hex, or null when the release published none. */
    static final class Integrity {
        final boolean refuse; final String want;
        Integrity(boolean refuse, String want) { this.refuse = refuse; this.want = want; }
    }

    /**
     * Order: the release's SHA256SUMS line for the file, else GitHub's asset digest, else nothing (the pinned
     * certificate, package name and version checks then decide). If both are published they must agree.
     */
    static Integrity integrity(String sumsText, String digest, String apkName) {
        String fromSums = sumsText == null ? null : AppUpdater.parseSums(sumsText, apkName);
        String fromDigest = digest == null || digest.isEmpty() ? null : digest.toLowerCase(Locale.ROOT);
        if (fromSums != null && fromDigest != null && !fromSums.equals(fromDigest)) return new Integrity(true, null);
        return new Integrity(false, fromSums != null ? fromSums : fromDigest);
    }

    /** What Android reports about an APK file or an installed app. {@code certs} are SHA-256 hex of the current signer(s). */
    static final class PkgInfo {
        final String pkg; final long versionCode; final List<String> certs; final boolean multiple;
        PkgInfo(String pkg, long versionCode, List<String> certs, boolean multiple) { this.pkg = pkg; this.versionCode = versionCode; this.certs = certs; this.multiple = multiple; }
    }

    /** The checks before install: null when the download may be handed to Android, otherwise why it is refused. */
    static Problem verify(App app, PkgInfo archive, Long installedCode) {
        if (archive == null || archive.pkg == null || !archive.pkg.equals(app.pkg)) return Problem.WRONG_APP;
        if (installedCode != null && archive.versionCode <= installedCode) return Problem.NOT_NEWER;
        if (archive.multiple || archive.certs == null || archive.certs.size() != 1) return Problem.SIGNATURE;
        return app.certs.contains(archive.certs.get(0).toLowerCase(Locale.ROOT)) ? null : Problem.SIGNATURE;
    }

    enum State { NOT_INSTALLED, UP_TO_DATE, UPDATE, TEST_BUILD }

    /** {@code installedName} null = not installed; {@code latest} empty = not known yet. */
    static State state(App app, String installedName, String latest, boolean testBuild) {
        if (app.isStack() && testBuild) return State.TEST_BUILD;
        if (installedName == null) return State.NOT_INSTALLED;
        return latest != null && !latest.isEmpty() && UpdateCheck.newer(latest, installedName) ? State.UPDATE : State.UP_TO_DATE;
    }

    static String label(State state, String installedName, String latest) {
        switch (state) {
            case NOT_INSTALLED: return "Not installed";
            case UPDATE: return "Update available · version " + latest;
            case TEST_BUILD: return "Test copy · not updated here";
            default: return "Installed · version " + installedName;
        }
    }

    /** The button on a row, or null for none. */
    static String button(State state) {
        return state == State.NOT_INSTALLED ? "Install" : state == State.UPDATE ? "Update" : null;
    }

    static boolean needsAction(State state) { return state == State.NOT_INSTALLED || state == State.UPDATE; }

    /** The Update all order: list order, Stack last (its update restarts it), only apps that need something. */
    static List<String> queue(List<App> apps, Function<App, State> states) {
        List<String> out = new ArrayList<>(); String stack = null;
        for (App app : apps) {
            if (!needsAction(states.apply(app))) continue;
            if (app.isStack()) stack = app.pkg; else out.add(app.pkg);
        }
        if (stack != null) out.add(stack);
        return out;
    }

    /** First waiting app that still needs something; apps done meanwhile are skipped. Null when finished. */
    static String next(List<String> pending, Set<String> satisfied) {
        for (String pkg : pending) if (!satisfied.contains(pkg)) return pkg;
        return null;
    }

    /** The waiting list after Android reports on {@code pkg}: success carries on, anything else stops the walk. */
    static List<String> afterResult(List<String> pending, String pkg, boolean success) {
        List<String> out = new ArrayList<>();
        if (success) for (String p : pending) if (!p.equals(pkg)) out.add(p);
        return out;
    }

    static String problemFor(int status) {
        return (status == PackageInstaller.STATUS_FAILURE_ABORTED ? Problem.CANCELLED
                : status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ? Problem.SIGNATURE
                : Problem.FAILED).name();
    }

    // ---- Android side ----

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean testBuild(Context context) { return context.getPackageName().startsWith("org.stackdocs.app.checks"); }

    static PkgInfo installedInfo(Context context, App app) {
        try { return toInfo(context.getPackageManager().getPackageInfo(app.pkg, 0), false); }
        catch (PackageManager.NameNotFoundException none) { return null; }
    }

    @SuppressWarnings("deprecation")
    private static PkgInfo toInfo(PackageInfo info, boolean withSigners) {
        if (info == null) return null;
        long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        List<String> certs = new ArrayList<>(); boolean multiple = false;
        if (withSigners) {
            Signature[] signers = null;
            if (Build.VERSION.SDK_INT >= 28) {
                if (info.signingInfo != null) { multiple = info.signingInfo.hasMultipleSigners(); signers = info.signingInfo.getApkContentsSigners(); }
            } else { signers = info.signatures; multiple = signers != null && signers.length > 1; }
            if (signers != null) for (Signature s : signers) certs.add(AppUpdater.hex(AppUpdater.sha256().digest(s.toByteArray())));
        }
        return new PkgInfo(info.packageName, code, certs, multiple);
    }

    @SuppressWarnings("deprecation")
    static PkgInfo archiveInfo(Context context, File file) {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        return toInfo(context.getPackageManager().getPackageArchiveInfo(file.getPath(), flags), true);
    }

    static String installedName(Context context, App app) {
        try { return context.getPackageManager().getPackageInfo(app.pkg, 0).versionName; }
        catch (PackageManager.NameNotFoundException none) { return null; }
    }

    static Release known(Context context, App app) { return Release.fromJson(prefs(context).getString("rel_" + app.pkg, "")); }

    /** Stack's own latest comes from its daily check; the others from their last look at GitHub. */
    static String latest(Context context, App app) {
        if (app.isStack()) return UpdateCheck.latest(context);
        Release release = known(context, app);
        return release == null ? "" : release.version;
    }

    static State stateOf(Context context, App app) {
        return state(app, installedName(context, app), latest(context, app), testBuild(context));
    }

    static boolean busy() { return running.get(); }

    static String note(Context context) { return prefs(context).getString("note", ""); }
    static void setNote(Context context, String text) { prefs(context).edit().putString("note", text).commit(); }

    static List<String> pending(Context context) {
        String text = prefs(context).getString("queue", "");
        return text.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(text.split(",")));
    }
    static void setPending(Context context, List<String> list) { prefs(context).edit().putString("queue", String.join(",", list)).commit(); }

    /** True while Android's confirmation for an install we handed over is still open (ignored once stale). */
    static boolean waitingForAndroid(Context context) {
        String value = prefs(context).getString("inflight", "");
        int bar = value.indexOf('|');
        if (bar < 0) return false;
        try { long at = Long.parseLong(value.substring(bar + 1)); return at <= Clock.now() && Clock.now() - at < STALE; }
        catch (NumberFormatException bad) { return false; }
    }

    /** Called by the install receiver when Android reports the outcome for a companion. */
    static void onResult(Context context, String pkg, int status) {
        boolean success = status == PackageInstaller.STATUS_SUCCESS;
        SharedPreferences.Editor edit = prefs(context).edit().remove("inflight");
        edit.putString("note", success ? "" : message(Problem.valueOf(problemFor(status))));
        edit.putString("queue", String.join(",", afterResult(pending(context), pkg, success)));
        edit.commit();
        if (context.getApplicationContext() instanceof StackApplication) ((StackApplication) context.getApplicationContext()).refreshListeners();
    }

    /** One line for the Home notice, or null when every companion is installed and current. */
    static String notice(Context context) {
        if (testBuild(context)) return null;
        List<String> missing = new ArrayList<>(), update = new ArrayList<>();
        for (App app : ALL) {
            if (app.isStack()) continue;
            State s = stateOf(context, app);
            if (s == State.NOT_INSTALLED) missing.add(app.name); else if (s == State.UPDATE) update.add(app.name);
        }
        if (missing.isEmpty() && update.isEmpty()) return null;
        if (!update.isEmpty() && missing.isEmpty()) return "Update available for " + String.join(" and ", update);
        return missing.isEmpty() ? null : String.join(" and ", missing) + (missing.size() > 1 ? " are" : " is") + " not installed yet";
    }

    static boolean noticeDismissed(Context context, String text) { return text.equals(prefs(context).getString("dismissed", "")); }
    static void dismissNotice(Context context, String text) { prefs(context).edit().putString("dismissed", text).commit(); }

    // ---- network ----

    static Release fetch(App app) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.github.com/repos/" + app.repo + "/releases/latest").openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Stack-update-check");
        try {
            int code = connection.getResponseCode();
            if (code == 403 || code == 429) throw new Fail(Problem.RATE_LIMIT);
            if (code == 404) throw new Fail(Problem.NO_APK);
            if (code != 200) throw new Fail(Problem.FAILED);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); if (out.size() > 512 * 1024) throw new Fail(Problem.FAILED); }
                return parse(app, out.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (Fail fail) { throw fail;
        } catch (IOException offline) { throw new Fail(Problem.OFFLINE);
        } finally { connection.disconnect(); }
    }

    /** Looks at the other apps' releases (Stack's own is looked at by UpdateCheck). Never installs. */
    static void refreshAll(StackApplication app) {
        for (App one : ALL) {
            if (one.isStack()) continue;
            try {
                Release release = fetch(one);
                prefs(app).edit().putString("rel_" + one.pkg, release.toJson().toString()).commit();
            } catch (Fail fail) {
                if (fail.problem == Problem.NO_APK) prefs(app).edit().remove("rel_" + one.pkg).commit();
            } catch (Exception other) { /* leave what we knew */ }
        }
        app.refreshListeners();
    }

    interface Listener { void progress(String text); void done(Problem problem); }

    /** One tap: install or update {@code app}. Stack's own row uses the existing self-update. */
    static void start(Activity activity, App app, Listener listener) {
        if (app.isStack()) { AppUpdater.start(activity); return; }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            setPending(activity, new ArrayList<>());
            new AlertDialog.Builder(activity).setTitle("Allow installs")
                    .setMessage("Allow Stack to install apps, then come back and tap Install.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
                        catch (RuntimeException none) { /* nothing more to offer */ }
                    }).setNegativeButton("Cancel", null).show();
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        AppUpdater.canceled = false;
        setNote(activity, "");
        Context context = activity.getApplicationContext();
        new Thread(() -> {
            Problem problem = null;
            File file = new File(new File(context.getCacheDir(), AppUpdater.CACHE_DIR), app.pkg + ".apk");
            try {
                listener.progress("Looking for " + app.name + "…");
                Release release = fetch(app);
                prefs(context).edit().putString("rel_" + app.pkg, release.toJson().toString()).commit();
                PkgInfo installed = installedInfo(context, app);
                Long have = installed == null ? null : installed.versionCode;
                String sumsText = null;
                if (!release.sums.isEmpty()) {
                    ByteArrayOutputStream text = new ByteArrayOutputStream();
                    AppUpdater.download(release.sums, app.prefix(), text, -1, new long[1], p -> {});
                    sumsText = new String(text.toByteArray(), StandardCharsets.UTF_8);
                }
                Integrity check = integrity(sumsText, release.digest, release.apkName);
                if (check.refuse) throw new Fail(Problem.HASH);
                AppUpdater.clearCache(context);
                if (!file.getParentFile().mkdirs() && !file.getParentFile().isDirectory()) throw new IOException("no cache folder");
                long[] size = {0}; String got;
                try (OutputStream out = new FileOutputStream(file)) {
                    got = AppUpdater.download(release.apk, app.prefix(), out, release.size, size, p -> listener.progress("Downloading " + app.name + "… " + p + "%"));
                }
                if (check.want != null && !check.want.equals(got)) throw new Fail(Problem.HASH);
                listener.progress("Checking " + app.name + "…");
                Problem refused = verify(app, archiveInfo(context, file), have);
                if (refused != null) throw new Fail(refused);
                prefs(context).edit().putString("inflight", app.pkg + "|" + Clock.now()).commit();
                AppUpdater.commit(context, file, size[0], got, app.pkg);
            } catch (Fail fail) { problem = fail.problem;
            } catch (AppUpdater.Canceled stop) { problem = Problem.CANCELLED;
            } catch (SocketTimeoutException | java.net.UnknownHostException offline) { problem = Problem.OFFLINE;
            } catch (Exception other) { problem = Problem.FAILED;
            } finally {
                running.set(false);
                if (problem != null) { prefs(context).edit().remove("inflight").commit(); AppUpdater.clearCache(context); }
            }
            if (problem != null) {
                setPending(context, new ArrayList<>());   // a refusal stops Update all
                setNote(context, message(problem));
            }
            listener.done(problem);
        }, "Companion install").start();
    }
}
