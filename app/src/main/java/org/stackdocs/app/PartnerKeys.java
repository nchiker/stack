package org.stackdocs.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Sharing keys for the partner (docs/STACK_LINK_SLICE7_DESIGN.md, piece 1). Per partner there are two 256-bit slots: the key this phone
 * SENDS with (used to write the share archive) and the key it RECEIVES with (used later by import; empty until the partner is scanned).
 * Everything is sealed in one file by a non-exportable Android Keystore key, the way {@link BackupKeys} does it. Keys are never logged,
 * never put in preferences or in the library, and never in a backup: the file sits outside the library database, outside every archive
 * and sync path, and {@code allowBackup} is off. Each save uses a fresh Keystore alias (kept in the file); the old alias is deleted only
 * after the new file is durable.
 */
final class PartnerKeys {
    static final String ALIAS = "stack_partner_keys";
    /** Only one partner is supported for now; the store is already keyed by partner so a second one needs no migration. */
    static final String DEFAULT_PARTNER = "partner";
    private static final String FILE = "partner_keys.bin";
    private static final int FORMAT = 2;
    private PartnerKeys() {}

    /** One partner's two slots; either may be null. Arrays are copies; callers should wipe them when done. */
    static final class Slots {
        final byte[] send, receive;
        /** The sender's rotation epoch (16 bytes, new with every new send key) and the count of publishes under it. */
        final byte[] sendEpoch; final long sendCounter;
        /** The newest (epoch, counter) imported with the current receive key; null epoch = none yet. */
        final byte[] seenEpoch; final long seenCounter;
        Slots(byte[] send, byte[] receive) { this(send, receive, send == null ? null : random(16), 0, null, 0); }
        Slots(byte[] send, byte[] receive, byte[] sendEpoch, long sendCounter, byte[] seenEpoch, long seenCounter) {
            this.send = send; this.receive = receive; this.sendEpoch = sendEpoch; this.sendCounter = sendCounter; this.seenEpoch = seenEpoch; this.seenCounter = seenCounter;
        }
        Slots withSend(byte[] key) { return new Slots(key, receive, random(16), 0, seenEpoch, seenCounter); }
        Slots withReceive(byte[] key) { return new Slots(send, key, sendEpoch, sendCounter, null, 0); }
    }

    /** What one publish carries inside the encrypted manifest. */
    static final class Stamp {
        final String epoch; final long counter;
        Stamp(String epoch, long counter) { this.epoch = epoch; this.counter = counter; }
    }

    static boolean configured(Context context) { return new File(context.getFilesDir(), FILE).exists(); }

    /** The sending key, or null when none exists yet. Throws when the sealed file is unreadable (the Keystore key was lost). */
    static synchronized byte[] sendKey(Context context, String partner) throws IOException {
        Slots s = load(context).get(partner);
        return s == null || s.send == null ? null : s.send.clone();
    }

    static synchronized byte[] receiveKey(Context context, String partner) throws IOException {
        Slots s = load(context).get(partner);
        return s == null || s.receive == null ? null : s.receive.clone();
    }

    /** The sending key, made now if there is none. */
    static synchronized byte[] ensureSendKey(Context context, String partner) throws IOException {
        Map<String, Slots> all = loadOrEmpty(context);
        Slots s = all.get(partner);
        if (s != null && s.send != null) return s.send.clone();
        byte[] key = random();
        all.put(partner, s == null ? new Slots(key, null) : s.withSend(key));
        save(context, all);
        return key.clone();
    }

    /** The next (epoch, counter) for a publish: the counter goes up by one and is saved before the file is written. Makes the send key if there is none. */
    static synchronized Stamp nextStamp(Context context, String partner) throws IOException {
        Map<String, Slots> all = loadOrEmpty(context);
        Slots s = all.get(partner);
        if (s == null || s.send == null) s = s == null ? new Slots(random(), null) : s.withSend(random());
        Slots next = new Slots(s.send, s.receive, s.sendEpoch, s.sendCounter + 1, s.seenEpoch, s.seenCounter);
        all.put(partner, next);
        save(context, all);
        return new Stamp(hex(next.sendEpoch), next.sendCounter);
    }

    /** The newest (epoch, counter) imported with the current receive key, or null. */
    static synchronized Stamp seen(Context context, String partner) throws IOException {
        Slots s = load(context).get(partner);
        return s == null || s.seenEpoch == null ? null : new Stamp(hex(s.seenEpoch), s.seenCounter);
    }

    /** Records the newest imported stamp, only while `receiveKey` is still the stored receive key (a new scan resets it). */
    static synchronized void setSeen(Context context, String partner, byte[] receiveKey, Stamp stamp) throws IOException {
        Map<String, Slots> all = loadOrEmpty(context);
        Slots s = all.get(partner);
        if (s == null || s.receive == null || !java.security.MessageDigest.isEqual(s.receive, receiveKey) || stamp == null || !stamp.epoch.matches("[0-9a-f]{32}")) return;
        byte[] epoch = new byte[16];
        for (int i = 0; i < 16; i++) epoch[i] = (byte) Integer.parseInt(stamp.epoch.substring(2 * i, 2 * i + 2), 16);
        all.put(partner, new Slots(s.send, s.receive, s.sendEpoch, s.sendCounter, epoch, stamp.counter));
        save(context, all);
    }

    /** A new sending key replaces the old one, which is forgotten. Returns the new key. */
    static synchronized byte[] rotateSendKey(Context context, String partner) throws IOException {
        Map<String, Slots> all = loadOrEmpty(context);
        Slots s = all.get(partner);
        byte[] key = random();
        all.put(partner, s == null ? new Slots(key, null) : s.withSend(key));
        save(context, all);
        return key.clone();
    }

    /** Stores the key the partner sends with (used by import, stage S7-3). */
    static synchronized void setReceiveKey(Context context, String partner, byte[] key) throws IOException {
        if (key == null || key.length != 32) throw new IllegalArgumentException("A sharing key is 32 bytes.");
        Map<String, Slots> all = loadOrEmpty(context);
        Slots s = all.get(partner);
        // The same key scanned again keeps what was already imported; a different key starts fresh.
        if (s != null && s.receive != null && java.security.MessageDigest.isEqual(s.receive, key)) return;
        all.put(partner, s == null ? new Slots(null, key.clone()) : s.withReceive(key.clone()));
        save(context, all);
    }

    /** Forgets both slots for one partner. */
    static synchronized void forget(Context context, String partner) throws IOException {
        Map<String, Slots> all = loadOrEmpty(context);
        if (all.remove(partner) != null) save(context, all);
    }

    /** Removes everything, including the Keystore key. */
    static synchronized void clear(Context context) {
        String alias = currentAlias(context);
        new File(context.getFilesDir(), FILE).delete();
        if (alias != null) try { KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias); } catch (GeneralSecurityException | IOException ignored) {}
    }

    /** Text that goes into the QR code, key only (see {@link PartnerQr} for the form with the owner's email). */
    static String qrText(byte[] key) { return PartnerQr.encode(key, null); }

    /** Six bytes (12 hex characters) of SHA-256 over the lowercase hex key text, shown in groups of four; same idea as the backup key fingerprint. */
    static String fingerprint(byte[] key) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(hex(key).getBytes(StandardCharsets.US_ASCII));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 6; i++) { if (i > 0 && i % 2 == 0) out.append(' '); out.append(String.format(Locale.ROOT, "%02x", d[i] & 0xff)); }
            return out.toString();
        } catch (GeneralSecurityException impossible) { return ""; }
    }

    static String hex(byte[] key) {
        StringBuilder out = new StringBuilder();
        for (byte b : key) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static byte[] random() { return random(32); }
    private static byte[] random(int n) { byte[] key = new byte[n]; new SecureRandom().nextBytes(key); return key; }

    // ---- sealed file -------------------------------------------------------------------------------------------

    private static Map<String, Slots> loadOrEmpty(Context context) throws IOException {
        return configured(context) ? load(context) : new LinkedHashMap<>();
    }

    private static Map<String, Slots> load(Context context) throws IOException {
        if (!configured(context)) return new LinkedHashMap<>();
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
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(plainBytes));
            if (in.readUnsignedByte() != FORMAT) throw new IOException("format");
            int count = in.readUnsignedShort();
            Map<String, Slots> out = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                byte[] name = new byte[in.readUnsignedByte()]; in.readFully(name);
                byte[] send = slot(in), receive = slot(in);
                byte[] sendEpoch = in.readUnsignedByte() == 0 ? null : fixed(in, 16); long sendCounter = in.readLong();
                byte[] seenEpoch = in.readUnsignedByte() == 0 ? null : fixed(in, 16); long seenCounter = in.readLong();
                out.put(new String(name, StandardCharsets.UTF_8), new Slots(send, receive, sendEpoch, sendCounter, seenEpoch, seenCounter));
            }
            return out;
        } catch (GeneralSecurityException | RuntimeException | IOException error) {
            throw new IOException("The sharing key is unavailable on this phone. Make a new code in Pair with partner.", error);
        } finally { if (plainBytes != null) Arrays.fill(plainBytes, (byte) 0); }
    }

    private static byte[] slot(DataInputStream in) throws IOException {
        if (in.readUnsignedByte() == 0) return null;
        byte[] key = new byte[32]; in.readFully(key); return key;
    }

    private static byte[] fixed(DataInputStream in, int n) throws IOException { byte[] b = new byte[n]; in.readFully(b); return b; }

    private static void putEpoch(DataOutputStream out, byte[] epoch, long counter) throws IOException {
        if (epoch == null) out.writeByte(0); else { out.writeByte(1); out.write(epoch); }
        out.writeLong(counter);
    }

    private static void put(DataOutputStream out, byte[] key) throws IOException {
        if (key == null) { out.writeByte(0); return; }
        out.writeByte(1); out.write(key);
    }

    private static void save(Context context, Map<String, Slots> all) throws IOException {
        String alias = ALIAS + "_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String old = currentAlias(context);
        boolean stored = false;
        byte[] plain = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(FORMAT); out.writeShort(all.size());
            for (Map.Entry<String, Slots> e : all.entrySet()) {
                byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeByte(name.length); out.write(name); put(out, e.getValue().send); put(out, e.getValue().receive);
                putEpoch(out, e.getValue().sendEpoch, e.getValue().sendCounter); putEpoch(out, e.getValue().seenEpoch, e.getValue().seenCounter);
            }
            out.flush(); plain = bytes.toByteArray();
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setUserAuthenticationRequired(false).build());
            SecretKey key = generator.generateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] aliasBytes = alias.getBytes(StandardCharsets.US_ASCII);
            cipher.updateAAD(aliasBytes);
            byte[] sealed = cipher.doFinal(plain), iv = cipher.getIV();
            AtomicFile atomic = new AtomicFile(new File(context.getFilesDir(), FILE));
            FileOutputStream file = atomic.startWrite();
            try { file.write(aliasBytes.length); file.write(aliasBytes); file.write(iv.length); file.write(iv); file.write(sealed); file.getFD().sync(); atomic.finishWrite(file); stored = true; }
            catch (IOException error) { atomic.failWrite(file); throw error; }
        } catch (GeneralSecurityException error) {
            throw new IOException("The partner sharing key could not be protected on this phone.", error);
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
            if (!stored) try { KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias); } catch (GeneralSecurityException | IOException ignored) {}
        }
        if (old != null) try { KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(old); } catch (GeneralSecurityException | IOException ignored) {}
    }

    private static String currentAlias(Context context) {
        try {
            byte[] all = new AtomicFile(new File(context.getFilesDir(), FILE)).readFully();
            return new String(all, 1, all[0] & 0xff, StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException none) { return null; }
    }
}
