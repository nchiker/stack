package org.stackdocs.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-app update, like Financial's: download the release APK from Stack's own GitHub release, check its SHA-256
 * against the release's SHA256SUMS while saving it and again while handing it to Android's installer, then let
 * Android ask the person to Update. Android itself refuses an APK not signed with Stack's key. Nothing installs
 * without that tap, and only files under github.com/nchiker/stack/releases/download/ are fetched.
 */
final class AppUpdater {
    static final String CACHE_DIR = "update";
    private static final long MAX_APK = 200L * 1024 * 1024, MAX_SUMS = 64 * 1024;
    private static final AtomicBoolean running = new AtomicBoolean();
    private static volatile boolean canceled;

    private AppUpdater() {}

    /** Starts the update from a visible screen; shows its own small progress window. */
    static void start(Activity activity) {
        String apk = UpdateCheck.apk(activity), sums = UpdateCheck.sums(activity), version = UpdateCheck.latest(activity);
        if (apk.isEmpty() || sums.isEmpty()) { browser(activity); return; }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity).setTitle("Allow updates")
                    .setMessage("Android asks once: allow Stack to install its own updates. Then come back and tap Update.")
                    .setPositiveButton("Open setting", (d, w) -> {
                        try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
                        catch (RuntimeException none) { browser(activity); }
                    })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        canceled = false;
        AlertDialog progress = new AlertDialog.Builder(activity).setTitle("Stack " + version)
                .setMessage("Downloading…").setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> canceled = true).show();
        Handler main = new Handler(Looper.getMainLooper());
        Context app = activity.getApplicationContext();
        long expected = UpdateCheck.apkSize(activity);
        new Thread(() -> {
            String failure = null;
            try {
                String want = expectedHash(sums, fileName(apk));
                File file = new File(new File(app.getCacheDir(), CACHE_DIR), "update.apk");
                clearCache(app);
                if (!file.getParentFile().mkdirs() && !file.getParentFile().isDirectory()) throw new IOException("no cache folder");
                long[] size = {0};
                String got;
                try (OutputStream out = new FileOutputStream(file)) {
                    got = download(apk, out, expected, size, percent -> main.post(() -> progress.setMessage("Downloading… " + percent + "%")));
                }
                if (!got.equals(want)) throw new IOException("The download did not match the release checksum. Nothing was installed.");
                main.post(() -> progress.setMessage("Checking…"));
                commit(app, file, size[0], want);
            } catch (Canceled stop) {
                failure = "";
            } catch (Exception error) {
                failure = error instanceof IOException && error.getMessage() != null && error.getMessage().endsWith(".")
                        ? error.getMessage() : "The update could not be downloaded. Try again later, or download it in your browser.";
            } finally {
                running.set(false);
                if (failure != null) clearCache(app);
            }
            String message = failure;
            main.post(() -> {
                if (progress.isShowing()) progress.dismiss();
                if (message != null && !message.isEmpty() && !activity.isFinishing())
                    new AlertDialog.Builder(activity).setTitle("Update").setMessage(message)
                            .setPositiveButton("OK", null).setNeutralButton("Use browser", (d, w) -> browser(activity)).show();
            });
        }, "Stack update").start();
    }

    /** The fallback: open the APK (or the release page) in the browser. */
    static void browser(Activity activity) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.download(activity)))); }
        catch (RuntimeException none) { android.widget.Toast.makeText(activity, "No browser is available", android.widget.Toast.LENGTH_SHORT).show(); }
    }

    static void clearCache(Context context) { LibraryArchive.remove(new File(context.getCacheDir(), CACHE_DIR)); }

    static String fileName(String url) { return url.substring(url.lastIndexOf('/') + 1); }

    /** The SHA-256 the release lists for {@code name} in SHA256SUMS ("hash  name" lines). */
    static String expectedHash(String sumsUrl, String name) throws IOException {
        java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
        download(sumsUrl, text, -1, new long[1], percent -> {});
        String found = parseSums(new String(text.toByteArray(), StandardCharsets.UTF_8), name);
        if (found == null) throw new IOException("The release does not list a checksum for this file. Nothing was installed.");
        return found;
    }

    static String parseSums(String text, String name) {
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("\\s+\\*?", 2);
            if (parts.length == 2 && parts[1].trim().equals(name) && parts[0].matches("[0-9a-fA-F]{64}")) return parts[0].toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private interface Progress { void percent(int value); }
    private static final class Canceled extends IOException {}

    /** Downloads one release file (only from this repository's release downloads), returning its SHA-256 hex. */
    private static String download(String url, OutputStream out, long expected, long[] size, Progress progress) throws IOException {
        if (!UpdateCheck.isReleaseFile(url)) throw new IOException("not a release file");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "Stack-update");
        connection.setInstanceFollowRedirects(true);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            if (!connection.getURL().getProtocol().equals("https")) throw new IOException("not https");
            long length = connection.getContentLengthLong(), limit = expected > 0 ? expected : (out instanceof java.io.ByteArrayOutputStream ? MAX_SUMS : MAX_APK);
            if (expected > 0 && length >= 0 && length != expected) throw new IOException("The download size did not match the release. Nothing was installed.");
            MessageDigest digest = sha256();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[65536];
                long total = 0; int n, shown = -1;
                while ((n = in.read(buffer)) != -1) {
                    if (canceled) throw new Canceled();
                    total += n;
                    if (total > limit) throw new IOException("The download is larger than the release says. Nothing was installed.");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    long of = expected > 0 ? expected : length;
                    if (of > 0) { int percent = (int) (total * 100 / of); if (percent != shown) { shown = percent; progress.percent(percent); } }
                }
                if (expected > 0 && total != expected) throw new IOException("The download size did not match the release. Nothing was installed.");
                size[0] = total;
            }
            return hex(digest.digest());
        } finally { connection.disconnect(); }
    }

    /** Writes the checked file into an install session, hashing it again, and commits it. */
    private static void commit(Context context, File file, long size, String want) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(size);
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            MessageDigest digest = sha256();
            try (InputStream in = new FileInputStream(file); OutputStream out = session.openWrite("base.apk", 0, size)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) { if (canceled) throw new Canceled(); digest.update(buffer, 0, n); out.write(buffer, 0, n); }
                session.fsync(out);
            }
            if (!hex(digest.digest()).equals(want)) throw new IOException("The saved update changed before installing. Nothing was installed.");
            Intent intent = new Intent(context, InstallResultReceiver.class).setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).getIntentSender());
            committed = true;
        } finally {
            if (!committed) try { installer.abandonSession(id); } catch (RuntimeException ignored) {}
            clearCache(context);
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }
}
