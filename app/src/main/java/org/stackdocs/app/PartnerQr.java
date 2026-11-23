package org.stackdocs.app;

import java.util.regex.Pattern;

/**
 * The text inside the owner's pairing QR code: {@code stackshare1:<64 hex>} with, only when Filen Files answered its account call, one optional
 * field {@code ;e=<owner's Filen email>} and, with or without it, {@code ;n=<32 lowercase hex>} (a fresh pairing nonce per code display). The scanner accepts both forms; any other field, an over-long or odd-looking email is refused as a whole.
 *
 * PRIVACY: the email is the owner's account name, shown in the QR only so the partner's Filen Files can ask for the right share without anyone
 * typing it. It is held in memory by the two pairing screens, passed straight to Filen Files, and then dropped. It is never logged, never kept
 * in preferences, the keystore file, the library, sync cells, share archives or backups, and {@link #toString()} of a parse hides it.
 */
final class PartnerQr {
    static final String PREFIX = "stackshare1:";
    static final String EMAIL_FIELD = ";e=";
    /** Optional pairing nonce: 16 random bytes as 32 lowercase hex, new for each QR display. Binds the partner's Filen contact request to this code. */
    static final String NONCE_FIELD = ";n=";
    static final int MAX_EMAIL = 254;
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern KEY = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z0-9-]{2,63}");

    private PartnerQr() {}

    /** One scanned code: the key as 64 hex characters and the owner's email or null. */
    static final class Parsed {
        final String keyHex, email, nonce;
        Parsed(String keyHex, String email) { this(keyHex, email, null); }
        Parsed(String keyHex, String email, String nonce) { this.keyHex = keyHex; this.email = email; this.nonce = nonce; }
        @Override public String toString() { return "PartnerQr.Parsed"; }
    }

    static boolean validEmail(String email) { return email != null && email.length() <= MAX_EMAIL && EMAIL.matcher(email).matches(); }

    static boolean validNonce(String nonce) { return nonce != null && NONCE.matcher(nonce).matches(); }

    /** A new nonce for one QR display. */
    static String newNonce() {
        byte[] b = new byte[16]; new java.security.SecureRandom().nextBytes(b);
        return PartnerKeys.hex(b);
    }

    /** Text for the QR with the email and nonce. Either is left out when absent or invalid. */
    static String encode(byte[] key, String email, String nonce) {
        return encode(key, email) + (validNonce(nonce) ? NONCE_FIELD + nonce : "");
    }

    /** Text for the QR. An email that does not pass {@link #validEmail} is left out rather than risking an unreadable code. */
    static String encode(byte[] key, String email) {
        return PREFIX + PartnerKeys.hex(key) + (validEmail(email) ? EMAIL_FIELD + email : "");
    }

    /** The parsed code, or null when the text is not exactly a Stack partner code. Fields come in the order e, n; each at most once. */
    static Parsed parse(String text) {
        if (text == null || !text.startsWith(PREFIX) || text.length() > PREFIX.length() + 64 + EMAIL_FIELD.length() + MAX_EMAIL + NONCE_FIELD.length() + 32) return null;
        String rest = text.substring(PREFIX.length());
        if (rest.length() < 64 || !KEY.matcher(rest.substring(0, 64)).matches()) return null;
        String tail = rest.substring(64), email = null, nonce = null;
        if (tail.startsWith(EMAIL_FIELD)) {
            int next = tail.indexOf(NONCE_FIELD);
            email = tail.substring(EMAIL_FIELD.length(), next < 0 ? tail.length() : next);
            if (!validEmail(email)) return null;
            tail = next < 0 ? "" : tail.substring(next);
        }
        if (tail.startsWith(NONCE_FIELD)) {
            nonce = tail.substring(NONCE_FIELD.length());
            if (!validNonce(nonce)) return null;
            tail = "";
        }
        if (!tail.isEmpty()) return null;   // any other field is unknown: refuse the whole code
        return new Parsed(rest.substring(0, 64), email, nonce);
    }
}
