package org.stackdocs.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Is there a newer Stack?" check, on by default like Filen Files' (Settings can turn it off).
 *
 * At most once a day (while Stack is open) it makes one anonymous HTTPS request to the public GitHub release list.
 * No document, account or device identifier is sent. The notice's Download opens the release APK in the browser;
 * the person opens the file to update. Stack never installs anything itself, and Android refuses an update that is
 * not signed with the installed app's key anyway.
 */
final class UpdateCheck {
    static final String REPO = "nchiker/stack";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String PAGE_PREFIX = "https://github.com/" + REPO + "/releases/";
    private static final String DOWNLOAD_PREFIX = PAGE_PREFIX + "download/";
    private static final long INTERVAL = 24L * 60 * 60 * 1000, RETRY = 60L * 60 * 1000;
    private static final int MAX_BYTES = 512 * 1024;
    private static final Pattern APK = Pattern.compile("^Stack-(\\d+\\.\\d+\\.\\d+)\\.apk$");
    private static final AtomicBoolean running = new AtomicBoolean();

    /** The newest Android release found: version, page, the APK and SHA256SUMS downloads ("" if unknown), APK size. */
    static final class Release {
        final String version, page, apk, sums; final long size;
        Release(String version, String page, String apk, String sums, long size) { this.version = version; this.page = page; this.apk = apk; this.sums = sums; this.size = size; }
    }

    private UpdateCheck() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("updates", Context.MODE_PRIVATE); }

    static boolean enabled(Context context) { return prefs(context).getBoolean("enabled", true); }

    static void setEnabled(Context context, boolean on) {
        SharedPreferences.Editor edit = prefs(context).edit().putBoolean("enabled", on);
        if (!on) edit.remove("latest").remove("page").remove("apk").remove("sums").remove("apk_size").remove("dismissed").remove("last_check").remove("last_attempt").remove("failed");
        edit.commit();
    }

    static String installed(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception unknown) { return "0"; }
    }

    static String latest(Context context) { return prefs(context).getString("latest", ""); }

    static String page(Context context) { return prefs(context).getString("page", ""); }

    static String apk(Context context) { return prefs(context).getString("apk", ""); }
    static String sums(Context context) { return prefs(context).getString("sums", ""); }
    static long apkSize(Context context) { return prefs(context).getLong("apk_size", 0); }

    /** Only files of this repository's releases are ever downloaded. */
    static boolean isReleaseFile(String url) {
        return url != null && url.startsWith(DOWNLOAD_PREFIX) && !url.contains("..") && !url.contains("?") && !url.contains("#");
    }

    /** Where the browser fallback goes: the APK itself when known, otherwise the release page. */
    static String download(Context context) {
        String apk = prefs(context).getString("apk", "");
        return apk.isEmpty() ? page(context) : apk;
    }

    /** A newer version is known and the user has not dismissed this exact version. */
    static boolean available(Context context) {
        SharedPreferences p = prefs(context);
        String latest = p.getString("latest", "");
        return enabled(context) && !latest.isEmpty() && newer(latest, installed(context))
                && !latest.equals(p.getString("dismissed", ""));
    }

    static void dismiss(Context context) { prefs(context).edit().putString("dismissed", latest(context)).commit(); }

    /** One line for the Settings row. */
    static String status(Context context) {
        SharedPreferences p = prefs(context);
        if (!enabled(context)) return "Off · Look for new versions on GitHub";
        String latest = p.getString("latest", "");
        long checked = p.getLong("last_check", 0);
        if (!latest.isEmpty() && newer(latest, installed(context))) return "On · Version " + latest + " is available";
        if (p.getBoolean("failed", false) && checked == 0) return "On · Couldn't check yet";
        if (checked == 0) return "On · Not checked yet";
        return "On · Up to date · Checked " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(checked));
    }

    /** Foreground hook; does nothing unless the user turned the check on and a day has passed. */
    static void checkIfDue(StackApplication app) {
        SharedPreferences p = prefs(app);
        if (!enabled(app)) return;
        long now = Clock.now(), checked = p.getLong("last_check", 0), attempt = p.getLong("last_attempt", 0);
        if (checked > now) checked = 0;
        if (attempt > now) attempt = 0;
        if (now - checked < INTERVAL || now - attempt < RETRY) return;
        checkNow(app, null);
    }

    /** One request on a worker thread; the result is kept in preferences and listeners are told. */
    static void checkNow(StackApplication app, Runnable done) {
        if (!enabled(app) || !running.compareAndSet(false, true)) { if (done != null) done.run(); return; }
        new Thread(() -> {
            SharedPreferences p = prefs(app);
            p.edit().putLong("last_attempt", Clock.now()).commit();
            try {
                Release release = parse(fetch());
                SharedPreferences.Editor edit = p.edit().putLong("last_check", Clock.now()).remove("failed");
                if (release == null) edit.remove("latest").remove("page").remove("apk").remove("sums").remove("apk_size");
                else edit.putString("latest", release.version).putString("page", release.page).putString("apk", release.apk)
                        .putString("sums", release.sums).putLong("apk_size", release.size);
                edit.commit();
            } catch (Exception offline) {
                p.edit().putBoolean("failed", true).commit();
            } finally {
                running.set(false);
                app.notifySyncChanged();
                if (done != null) done.run();
            }
        }, "Stack update check").start();
    }

    private static String fetch() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        // A generic client name only: no version, device or user information.
        connection.setRequestProperty("User-Agent", "Stack-update-check");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_BYTES) throw new IOException("Response too large");
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }

    /**
     * Reads GitHub's "latest release" JSON. The Android version comes from the attached {@code Stack-x.y.z.apk},
     * so a desktop-only release (no APK) is ignored. Only links back to this repository's releases are kept: a
     * download link must be one of this repository's release files, or Download falls back to the release page.
     */
    static Release parse(String json) throws org.json.JSONException {
        JSONObject release = new JSONObject(json);
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        String sums = "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String url = asset.optString("browser_download_url", "");
            if (asset.optString("name").equals("SHA256SUMS") && isReleaseFile(url) && url.endsWith("/SHA256SUMS")) sums = url;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            Matcher match = APK.matcher(asset.optString("name"));
            if (!match.matches()) continue;
            String page = release.optString("html_url", "");
            if (!page.startsWith(PAGE_PREFIX)) page = PAGE_PREFIX + "latest";
            String apk = asset.optString("browser_download_url", "");
            if (!isReleaseFile(apk) || !apk.endsWith("/" + match.group(0))) apk = "";
            return new Release(match.group(1), page, apk, apk.isEmpty() ? "" : sums, Math.max(0, asset.optLong("size", 0)));
        }
        return null;
    }

    /** True when version {@code a} is higher than {@code b}; missing or non-numeric parts count as 0. */
    static boolean newer(String a, String b) {
        int[] x = numbers(a), y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int left = i < x.length ? x[i] : 0, right = i < y.length ? y[i] : 0;
            if (left != right) return left > right;
        }
        return false;
    }

    private static int[] numbers(String version) {
        String[] parts = version.replaceFirst("^[vV]", "").split("[.\\-+]");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException text) { out[i] = 0; }
        }
        return out;
    }
}
