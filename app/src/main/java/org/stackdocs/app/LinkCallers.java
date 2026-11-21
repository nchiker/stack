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

    private LinkCallers() {}

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

    /** The calling package if the Binder uid is allowed, else null. */
    static String allowedCaller(Context context, int uid) {
        String callerPackage = packageOf(context, uid);
        if (callerPackage == null) return null;
        String own = certificateOf(context, context.getPackageName());
        return decide(callerPackage, certificateOf(context, callerPackage), own, PINNED) ? callerPackage : null;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
