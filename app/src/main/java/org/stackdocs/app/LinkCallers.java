package org.stackdocs.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;

/**
 * Who may talk to Stack's link provider (docs/STACK_LINK_CONTRACT.md, "Caller check"). The caller's identity comes from
 * the kernel-supplied uid, never from anything the caller writes. A caller is allowed only if it has exactly one signing
 * certificate and either (a) that certificate is Stack's own, or (b) the (package, certificate) pair is pinned below.
 */
final class LinkCallers {
    /** Pinned owner apps: package, then the SHA-256 of its signing certificate (lowercase hex). */
    static final String[][] PINNED = {
        {"app.amipro.editor", "9a387c70c8c19a3bb29e9b02f18840f4b1bb18ac291983d2d0ec1c684670868a"},
        // The AmiPro debug build and the other apps are added here when they implement the contract.
    };

    /** Filen Files: pinned ONLY for Filen-origin events ({@link LinkProvider}'s filen_uuid messages) and for Stack's own calls to Filen Files. Never an owner app. */
    static final String FILEN_PACKAGE = "app.filenfiles";
    static final String[][] FILEN = {{FILEN_PACKAGE, "59ccca4c8c1126ec48257a8f3051c6be5eccdd5888df82215560ce2ff8b580bf"}};

    /** Ami Pro: the package a folder file of its type is opened in, pinned like Filen Files (the same pin as in {@link #PINNED}). */
    static final String AMIPRO_PACKAGE = "app.amipro.editor";

    private LinkCallers() {}

    /** True when the installed app.amipro.editor carries the pinned certificate. Stack checks this before it hands Ami Pro a file. */
    static boolean amiProInstalledAndPinned(Context context) {
        String cert = certificateOf(context, AMIPRO_PACKAGE);
        if (cert == null) return false;
        for (String[] pin : pinnedFor(context)) if (pin[0].equals(AMIPRO_PACKAGE) && pin[1].equals(cert)) return true;
        return false;
    }

    /** The caller's package for a Binder uid, or null when the uid is shared by several packages or has none. */
    static String packageOf(Context context, int uid) {
        String[] names = context.getPackageManager().getPackagesForUid(uid);
        return names != null && names.length == 1 ? names[0] : null;
    }

    /** Lowercase SHA-256 of the package's one signing certificate, or null if it has none, several, or is unknown. */
    @SuppressWarnings("deprecation")
    static String certificateOf(Context context, String packageName) {
        try {
            PackageManager manager = context.getPackageManager();
            android.content.pm.Signature[] signers;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null || info.signingInfo.hasMultipleSigners()) return null;
                signers = info.signingInfo.getApkContentsSigners();
            } else {
                signers = manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures;
            }
            if (signers == null || signers.length != 1) return null;
            return hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()));
        } catch (PackageManager.NameNotFoundException | java.security.NoSuchAlgorithmException | RuntimeException error) {
            return null;
        }
    }

    /** The pure decision, so it can be checked without a second signed app. */
    static boolean decide(String callerPackage, String callerCertificate, String ownCertificate, String[][] pinned) {
        if (callerPackage == null || callerCertificate == null || ownCertificate == null) return false;
        if (callerCertificate.equals(ownCertificate)) return true;
        for (String[] pin : pinned) if (pin[0].equals(callerPackage) && pin[1].equals(callerCertificate)) return true;
        return false;
    }

    /** The pinned list plus, in a checks build only, the one extra `package:sha256` pin given at build time. */
    static String[][] pinnedFor(Context context) {
        String extra = context.getString(R.string.stack_extra_pin).trim().toLowerCase(java.util.Locale.ROOT);
        int colon = extra.indexOf(':');
        if (colon <= 0 || !extra.substring(colon + 1).matches("[0-9a-f]{64}")) return PINNED;
        String[][] all = java.util.Arrays.copyOf(PINNED, PINNED.length + 1);
        all[PINNED.length] = new String[]{extra.substring(0, colon), extra.substring(colon + 1)};
        return all;
    }

    /** The calling package if the Binder uid is allowed, else null. */
    static String allowedCaller(Context context, int uid) {
        String callerPackage = packageOf(context, uid);
        if (callerPackage == null) return null;
        String own = certificateOf(context, context.getPackageName());
        if (FILEN_PACKAGE.equals(callerPackage)) return null;   // Filen Files is not an owner app: see allowedFilenCaller
        return decide(callerPackage, certificateOf(context, callerPackage), own, pinnedFor(context)) ? callerPackage : null;
    }

    /** The Filen pin, plus (checks builds only) the extra pin when it names Filen Files' package. */
    static String[][] filenPins(Context context) {
        for (String[] pin : pinnedFor(context)) if (FILEN_PACKAGE.equals(pin[0])) return new String[][]{pin, FILEN[0]};
        return FILEN;
    }

    /** The calling package if the Binder uid is Filen Files with the pinned certificate, else null. Only for Filen-origin events. */
    static String allowedFilenCaller(Context context, int uid) {
        String callerPackage = packageOf(context, uid);
        if (!FILEN_PACKAGE.equals(callerPackage)) return null;
        return decide(callerPackage, certificateOf(context, callerPackage), certificateOf(context, context.getPackageName()), filenPins(context)) ? callerPackage : null;
    }

    /** True when the installed app.filenfiles carries the pinned certificate. Stack checks this before it sends anything to Filen Files or trusts what it returns. */
    static boolean filenInstalledAndPinned(Context context) {
        String cert = certificateOf(context, FILEN_PACKAGE);
        if (cert == null) return false;
        for (String[] pin : filenPins(context)) if (pin[0].equals(FILEN_PACKAGE) && pin[1].equals(cert)) return true;
        return false;
    }

    /** True when `intent` resolves to an activity of the pinned Filen Files. */
    static boolean filenActivity(Context context, android.content.Intent intent) {
        try {
            android.content.pm.ResolveInfo info = context.getPackageManager().resolveActivity(intent, 0);
            return info != null && info.activityInfo != null && FILEN_PACKAGE.equals(info.activityInfo.packageName) && filenInstalledAndPinned(context);
        } catch (RuntimeException error) { return false; }
    }

    /** True when the content authority is served by the pinned Filen Files. */
    static boolean filenAuthority(Context context, String authority) {
        try {
            android.content.pm.ProviderInfo info = authority == null ? null : context.getPackageManager().resolveContentProvider(authority, 0);
            return info != null && FILEN_PACKAGE.equals(info.packageName) && filenInstalledAndPinned(context);
        } catch (RuntimeException error) { return false; }
    }

    /**
     * True only if the app that actually serves `<owner>.stacklink` is `owner` itself and passes the same pinned-certificate test
     * as a caller. Without this, any app could claim that authority after the owner is uninstalled and answer "no such file".
     */
    static boolean trustedOwnerProvider(Context context, String owner) {
        try {
            android.content.pm.ProviderInfo info = context.getPackageManager().resolveContentProvider(owner + ".stacklink", 0);
            if (info == null || !owner.equals(info.packageName)) return false;
            return decide(owner, certificateOf(context, owner), certificateOf(context, context.getPackageName()), pinnedFor(context));
        } catch (RuntimeException error) { return false; }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
