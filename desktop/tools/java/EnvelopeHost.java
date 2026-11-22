package org.stackdocs.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;

/**
 * Host driver for the unchanged production BackupEnvelope. The recovery key (64 hex) is read from stdin only.
 *   decrypt IN OUT              recovery-slot decrypt; OUT is written only after the final chunk verified
 *   encrypt IN OUT              key-only (recovery slot) encrypt with fresh random salt/data key
 *   encrypt-both IN OUT         recovery slot AND passphrase slot (what the phone writes with a passphrase set)
 *   decrypt-passphrase IN OUT   passphrase-slot decrypt
 * stdin (UTF-8): line 1 is the recovery key (64 hex) for decrypt, encrypt and encrypt-both; encrypt-both takes the passphrase
 * as line 2; decrypt-passphrase takes only the passphrase as line 1. A line break ends a line; nothing else is trimmed from the
 * passphrase. Neither secret is ever an argument. Failure: one generic line on stderr, exit 1. Secret text and bytes are never printed.
 */
public final class EnvelopeHost {
    public static void main(String[] args) throws Exception {
        try {
            if (args.length != 3) throw new IllegalArgumentException("usage: decrypt|encrypt|encrypt-both|decrypt-passphrase IN OUT");
            String[] lines = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length; i++) if (lines[i].endsWith("\r")) lines[i] = lines[i].substring(0, lines[i].length() - 1);
            boolean passphraseOnly = args[0].equals("decrypt-passphrase");
            byte[] key = passphraseOnly ? null : BackupEnvelope.parseRecoveryKey(lines[0].trim());
            String passphrase = passphraseOnly ? lines[0] : args[0].equals("encrypt-both") && lines.length > 1 ? lines[1] : null;
            byte[] input = Files.readAllBytes(Path.of(args[1]));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            if (args[0].equals("decrypt") || passphraseOnly) {
                try (BackupEnvelope.Reader reader = BackupEnvelope.decrypt(new ByteArrayInputStream(input), passphraseOnly ? passphrase : null, key)) {
                    reader.transferTo(result);
                    reader.finish();
                }
            } else if (args[0].equals("encrypt") || args[0].equals("encrypt-both")) {
                SecureRandom random = new SecureRandom();
                byte[] salt = new byte[BackupEnvelope.SALT];
                random.nextBytes(salt);
                byte[] slotKey = BackupEnvelope.recoverySlotKey(key, salt);
                byte[] passKey = passphrase == null ? null : BackupEnvelope.passphraseSlotKey(passphrase, salt, BackupEnvelope.MIN_ITERATIONS);
                if (args[0].equals("encrypt-both") && passKey == null) throw new IllegalArgumentException("encrypt-both needs a passphrase on line 2");
                try (BackupEnvelope.Writer writer = BackupEnvelope.encrypt(result, salt, BackupEnvelope.MIN_ITERATIONS, passKey, slotKey, random::nextBytes)) {
                    writer.write(input, 0, input.length);
                    writer.finish();
                }
            } else throw new IllegalArgumentException("unknown command");
            Files.write(Path.of(args[2]), result.toByteArray(), StandardOpenOption.CREATE_NEW);
            System.out.println("ok " + args[0] + " " + result.size());
        } catch (Exception error) {
            System.err.println("EnvelopeHost: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            System.exit(1);
        }
    }
}
