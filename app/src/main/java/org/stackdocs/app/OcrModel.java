package org.stackdocs.app;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Installs only bundled, verified data. Never touches document, draft or recovery directories. */
final class OcrModel {
    static final String SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2";
    static final String CONFIG = "t4a-4.9.0/tess-5.5.1/eng/" + SHA256 + "/page-renderer-2400-sauvola-v2";
    static final long BYTES = 4113088;

    static synchronized File install(Context context) throws IOException {
        File root = new File(context.getFilesDir(), "ocr/" + SHA256);
        File data = new File(root, "tessdata");
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("OCR storage unavailable");
        File target = new File(data, "eng.traineddata");
        File temporary = new File(data, "eng.traineddata.tmp");
        if (valid(target)) {
            if (temporary.exists() && !temporary.delete()) throw new IOException("OCR staging cleanup failed");
            return root;
        }
        try {
            try (InputStream input = context.getAssets().open("ocr/eng.traineddata");
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768];
                long copied = 0;
                for (int n; (n = input.read(buffer)) != -1;) {
                    copied += n;
                    if (copied > BYTES) throw new IOException("Bundled OCR data size mismatch");
                    output.write(buffer, 0, n);
                }
                output.flush();
                output.getFD().sync();
            }
            if (!valid(temporary)) throw new IOException("Bundled OCR data verification failed");
            // Same-directory rename publishes the complete model atomically, replacing corruption.
            if (!temporary.renameTo(target)) throw new IOException("OCR data publication failed");
            return root;
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    static boolean valid(File file) throws IOException {
        if (!file.isFile() || file.length() != BYTES) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[32768];
                for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return SHA256.equals(hex.toString());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
