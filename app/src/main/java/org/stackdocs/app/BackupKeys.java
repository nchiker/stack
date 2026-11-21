package org.stackdocs.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The unattended-backup key store (STACK_ARCHIVE_FORMAT.md, "Keys and policy"). Holds only the two derived slot keys plus the
 * salt and iteration count, sealed by a non-exportable Android Keystore key. The passphrase and the recovery key are never stored.
 */
final class BackupKeys {
    static final String ALIAS = "stack_backup_keys";
    static final int MIN_PASSPHRASE = 12;
    private static final String FILE = "backup_keys.bin";
    private BackupKeys() {}

    static final class Slots {
        final byte[] salt, passphraseKey, recoveryKey; final int iterations;
        Slots(byte[] salt, int iterations, byte[] passphraseKey, byte[] recoveryKey) { this.salt = salt; this.iterations = iterations; this.passphraseKey = passphraseKey; this.recoveryKey = recoveryKey; }
    }

    /** A new setup waiting for the user to confirm they saved the recovery key; nothing is stored until {@link #save}. */
    static final class Pending {
        final Slots slots; final String recoveryHex;
        Pending(Slots slots, String recoveryHex) { this.slots = slots; this.recoveryHex = recoveryHex; }
    }

    static boolean configured(Context context) { return new File(context.getFilesDir(), FILE).exists(); }

    /** Slow (PBKDF2): call off the UI thread. */
    static Pending begin(String passphrase) throws GeneralSecurityException { return begin(passphrase, null); }

    /** {@code recovery}: a 32-byte key made elsewhere (Key Vault) and scanned in; null makes a new one here. */
    static Pending begin(String passphrase, byte[] recoveryKey) throws GeneralSecurityException {
        if (passphrase == null && recoveryKey == null) throw new IllegalArgumentException("A passphrase or a recovery key is required.");
        if (passphrase != null && passphrase.length() < MIN_PASSPHRASE) throw new IllegalArgumentException("Use a passphrase of at least " + MIN_PASSPHRASE + " characters.");
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[BackupEnvelope.SALT], recovery = recoveryKey != null ? recoveryKey.clone() : new byte[32];
        random.nextBytes(salt); if (recoveryKey == null) random.nextBytes(recovery);
        int iterations = BackupEnvelope.MIN_ITERATIONS;
        Slots slots = new Slots(salt, iterations, passphrase == null ? null : BackupEnvelope.passphraseSlotKey(passphrase, salt, iterations), BackupEnvelope.recoverySlotKey(recovery, salt));
        StringBuilder hex = new StringBuilder();
        for (byte b : recovery) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        java.util.Arrays.fill(recovery, (byte) 0);
        return new Pending(slots, hex.toString());
    }

    /** Each setup gets its own Keystore alias, kept in the file. The old alias is deleted only after the new file is durable. */
    static void save(Context context, Slots slots) throws GeneralSecurityException, IOException {
        String alias = ALIAS + "_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String old = currentAlias(context);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        boolean stored = false;
        try {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setUserAuthenticationRequired(false).build());
            SecretKey key = generator.generateKey();
            // flag byte: 1 = a passphrase slot exists (a key scanned from Key Vault alone needs none)
            byte[] plain = ByteBuffer.allocate(BackupEnvelope.SALT + 4 + 1 + 64).put(slots.salt).putInt(slots.iterations).put((byte) (slots.passphraseKey != null ? 1 : 0))
                    .put(slots.passphraseKey != null ? slots.passphraseKey : new byte[32]).put(slots.recoveryKey).array();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key); // the Keystore chooses the IV
            byte[] aliasBytes = alias.getBytes(StandardCharsets.US_ASCII);
            cipher.updateAAD(aliasBytes);
            byte[] sealed = cipher.doFinal(plain), iv = cipher.getIV();
            java.util.Arrays.fill(plain, (byte) 0);
            AtomicFile atomic = new AtomicFile(new File(context.getFilesDir(), FILE));
            FileOutputStream out = atomic.startWrite();
            try { out.write(aliasBytes.length); out.write(aliasBytes); out.write(iv.length); out.write(iv); out.write(sealed); out.getFD().sync(); atomic.finishWrite(out); stored = true; }
            catch (IOException error) { atomic.failWrite(out); throw error; }
        } finally {
            if (!stored) try { store.deleteEntry(alias); } catch (GeneralSecurityException ignored) {}
        }
        if (old != null) try { store.deleteEntry(old); } catch (GeneralSecurityException ignored) {}
    }

    private static String currentAlias(Context context) {
        try {
            byte[] all = new AtomicFile(new File(context.getFilesDir(), FILE)).readFully();
            return new String(all, 1, all[0] & 0xff, StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException none) { return null; }
    }

    /** Never falls back to anything: a lost or invalidated Keystore key is an error the caller must report. */
    static Slots load(Context context) throws IOException {
        byte[] plainBytes = null;
        try {
            byte[] all = new AtomicFile(new File(context.getFilesDir(), FILE)).readFully();
            int aliasLength = all[0] & 0xff;
            String alias = new String(all, 1, aliasLength, StandardCharsets.US_ASCII);
            int ivLength = all[1 + aliasLength] & 0xff, at = 2 + aliasLength;
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            SecretKey key = (SecretKey) store.getKey(alias, null);
            if (key == null) throw new IOException("missing key");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, at, ivLength));
            cipher.updateAAD(alias.getBytes(StandardCharsets.US_ASCII));
            plainBytes = cipher.doFinal(all, at + ivLength, all.length - at - ivLength);
            ByteBuffer plain = ByteBuffer.wrap(plainBytes);
            byte[] salt = new byte[BackupEnvelope.SALT], pass = new byte[32], recovery = new byte[32];
            plain.get(salt); int iterations = plain.getInt(); boolean hasPass = plain.get() == 1; plain.get(pass); plain.get(recovery);
            return new Slots(salt, iterations, hasPass ? pass : null, recovery);
        } catch (GeneralSecurityException | RuntimeException | IOException error) {
            throw new IOException("Backup encryption is unavailable on this phone (its key was lost or changed). No backup was written. Set up encryption again in Backup & restore.", error);
        } finally { if (plainBytes != null) java.util.Arrays.fill(plainBytes, (byte) 0); }
    }

    static void clear(Context context) {
        String alias = currentAlias(context);
        new File(context.getFilesDir(), FILE).delete();
        if (alias != null) try { KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias); } catch (GeneralSecurityException | IOException ignored) {}
    }
}
