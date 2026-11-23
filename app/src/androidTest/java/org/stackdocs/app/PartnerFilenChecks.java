package org.stackdocs.app;

import android.content.Intent;
import android.net.Uri;
import java.util.Arrays;
import java.util.function.Consumer;

/** Pure-logic checks for the Filen Files pairing handshake on Stack's side. No Filen Files, no device state, no real account. */
final class PartnerFilenChecks {
    private PartnerFilenChecks() {}
    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static void run(Consumer<String> mark) {
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 0x5a);
        String hex = PartnerKeys.hex(key), email = "someone.test+stack@example.org";

        // QR round trip with and without the email; the old key-only text is unchanged.
        require(PartnerKeys.qrText(key).equals("stackshare1:" + hex), "key-only text changed");
        PartnerQr.Parsed plain = PartnerQr.parse(PartnerQr.encode(key, null));
        require(plain != null && plain.email == null && plain.keyHex.equals(hex), "plain round trip");
        PartnerQr.Parsed with = PartnerQr.parse(PartnerQr.encode(key, email));
        require(with != null && with.email.equals(email) && with.keyHex.equals(hex), "email round trip");
        require(!with.toString().contains("example"), "parse object must not print the email");
        require(PartnerQr.encode(key, "not an email").equals("stackshare1:" + hex), "bad email must be left out");

        // Strict validation.
        StringBuilder big = new StringBuilder("a@");
        while (big.length() < 300) big.append("b");
        require(PartnerQr.parse("stackshare1:" + hex + ";e=" + big + ".com") == null, "oversize email");
        require(PartnerQr.parse("stackshare1:" + hex + ";e=a b@example.org") == null, "space in email");
        require(PartnerQr.parse("stackshare1:" + hex + ";e=a@exaémple.org") == null, "non-ascii in email");
        require(PartnerQr.parse("stackshare1:" + hex + ";e=a@example.org;x=1") == null, "unknown extra field after email");
        require(PartnerQr.parse("stackshare1:" + hex + ";x=1") == null, "unknown field");
        require(PartnerQr.parse("stackshare1:" + hex + ";e=") == null, "empty email field");
        require(PartnerQr.parse("stackshare1:" + hex.substring(1)) == null, "short key");
        require(PartnerQr.parse("stackshare1:" + hex + "0") == null, "long key");
        require(PartnerQr.parse("stackkey1:" + hex) == null, "recovery key code is not a partner code");
        require(PartnerQr.parse(null) == null, "null");

        // Availability fallback: Filen only when an activity resolves the action; otherwise the system picker.
        require(FilenFolder.useFilen(true) && !FilenFolder.useFilen(false), "availability");

        // Names exactly as the scope spells them.
        require(FilenFolder.PROVIDER.equals("content://app.filenfiles.stacklink") && FilenFolder.METHOD_ACCOUNT.equals("account") && FilenFolder.ACCOUNT_EMAIL.equals("email"), "provider names");
        require(FilenFolder.SHARE_ACTION.equals("app.filenfiles.action.GRANT_SHARE_FOLDER") && FilenFolder.RECEIVED_ACTION.equals("app.filenfiles.action.GRANT_RECEIVED_FOLDER"), "action names");
        require(FilenFolder.EXTRA_OWNER_EMAIL.equals("app.filenfiles.extra.OWNER_EMAIL") && FilenFolder.EXTRA_FOLDER_NAME.equals("app.filenfiles.extra.FOLDER_NAME"), "extra names");
        require(FilenFolder.ACTION_SHARED_CHANGED.equals("app.filenfiles.action.SHARED_CHANGED"), "broadcast name");
        Intent received = FilenFolder.pairReceivedRequest(email);
        require(email.equals(received.getStringExtra(FilenFolder.EXTRA_OWNER_EMAIL)) && "app.filenfiles".equals(received.getPackage()), "received intent");
        require(!FilenFolder.pairShareRequest().hasExtra(FilenFolder.EXTRA_FOLDER_NAME), "pairing share leaves the folder name to Filen");

        // Result codes become quiet lines, never dialogs.
        require(FilenFolder.outcome(true, true, null) == FilenFolder.Outcome.GRANTED, "granted");
        require(FilenFolder.statusLine(true, true, null, null).isEmpty(), "granted has no line");
        require(FilenFolder.outcome(false, false, "waiting_for_owner") == FilenFolder.Outcome.WAITING, "waiting_for_owner");
        require(FilenFolder.outcome(false, false, "waiting_for_partner") == FilenFolder.Outcome.WAITING, "waiting_for_partner");
        require(FilenFolder.statusLine(false, false, "waiting_for_owner", null).equals(FilenFolder.WAITING_LINE), "waiting line");
        require(FilenFolder.statusLine(false, false, null, null).equals(FilenFolder.WAITING_LINE), "cancel line");
        require(FilenFolder.statusLine(false, false, "user_cancelled", null).equals(FilenFolder.WAITING_LINE), "user_cancelled line");
        require(FilenFolder.outcome(true, false, null) == FilenFolder.Outcome.BACKED_OUT, "OK without a tree is not a grant");
        require(FilenFolder.statusLine(false, false, "not_signed_in", null).startsWith("Sign in"), "not_signed_in");
        require(FilenFolder.statusLine(false, false, "offline", null).contains("connection"), "offline");
        require(FilenFolder.statusLine(false, false, "failed", " odd ").equals("odd"), "failed message");
        require(FilenFolder.statusLine(false, false, "no_caller", null).equals(FilenFolder.WAITING_LINE), "unknown code stays calm");

        // The change broadcast only matters for the folder Stack reads.
        Uri tree = Uri.parse("content://app.filenfiles.documents/tree/x");
        require(!PartnerChangeReceiver.matches(tree, null) && !PartnerChangeReceiver.matches(tree, "") && PartnerChangeReceiver.matches(tree, tree.toString()), "only the exact chosen tree is accepted");
        PartnerChangeReceiver.resetLimit();
        require(PartnerChangeReceiver.allow(1_000_000L) && !PartnerChangeReceiver.allow(1_000_000L + 59_999L) && PartnerChangeReceiver.allow(1_000_000L + 60_000L), "broadcast imports are limited to one a minute");
        PartnerChangeReceiver.resetLimit();
        // The pairing nonce: optional in the QR, strictly validated, passed to Filen Files in both request intents.
        byte[] qrKey = new byte[32]; java.util.Arrays.fill(qrKey, (byte) 7);
        String nonce = PartnerQr.newNonce();
        require(PartnerQr.validNonce(nonce) && !nonce.equals(PartnerQr.newNonce()), "fresh 32-hex nonce each time");
        PartnerQr.Parsed both = PartnerQr.parse(PartnerQr.encode(qrKey, email, nonce));
        require(both != null && nonce.equals(both.nonce) && email.equals(both.email), "QR with email and nonce round trips");
        PartnerQr.Parsed onlyNonce = PartnerQr.parse(PartnerQr.encode(qrKey, null, nonce));
        require(onlyNonce != null && nonce.equals(onlyNonce.nonce) && onlyNonce.email == null, "QR with nonce only round trips");
        PartnerQr.Parsed noNonce = PartnerQr.parse(PartnerQr.encode(qrKey, null, null));
        require(noNonce != null && noNonce.nonce == null && noNonce.email == null && PartnerQr.parse(PartnerQr.encode(qrKey, email)).nonce == null, "QR without a nonce is accepted");
        String base = PartnerQr.encode(qrKey, null);
        require(PartnerQr.parse(base + ";n=ABCDEF0123456789ABCDEF0123456789") == null && PartnerQr.parse(base + ";n=0123") == null
                && PartnerQr.parse(base + ";n=" + nonce + "0") == null && PartnerQr.parse(base + ";n=" + nonce + ";n=" + nonce) == null
                && PartnerQr.parse(base + ";n=" + nonce + ";e=" + email) == null && PartnerQr.parse(base + ";n=") == null, "a bad or repeated nonce rejects the whole code");
        require(nonce.equals(FilenFolder.pairShareRequest(nonce).getStringExtra(FilenFolder.EXTRA_PAIR_NONCE)) && nonce.equals(FilenFolder.pairReceivedRequest(email, nonce).getStringExtra(FilenFolder.EXTRA_PAIR_NONCE))
                && FilenFolder.EXTRA_PAIR_NONCE.equals("app.filenfiles.extra.PAIR_NONCE") && !FilenFolder.pairShareRequest(null).hasExtra(FilenFolder.EXTRA_PAIR_NONCE)
                && !FilenFolder.pairShareRequest("bad").hasExtra(FilenFolder.EXTRA_PAIR_NONCE), "nonce extra is in the built intents only when valid");
        require(!PartnerChangeReceiver.matches(tree, "content://other/tree/y") && !PartnerChangeReceiver.matches(null, null), "other tree ignored");
        mark.accept("partner filen handshake ok");
    }
}
