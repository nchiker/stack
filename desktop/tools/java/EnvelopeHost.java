package org.stackdocs.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;

/**
 * Host driver for the unchanged production BackupEnvelope. The recovery key (64 hex) is read from stdin only.
 *   decrypt IN OUT   recovery-slot decrypt; OUT is written only after the final chunk verified
 *   encrypt IN OUT   key-only (recovery slot) encrypt with fresh random salt/data key
 * Failure: one generic line on stderr, exit 1. Key text and bytes are never printed.
 */
public final class EnvelopeHost {
    public static void main(String[] args) throws Exception {
        try {
            if (args.length != 3) throw new IllegalArgumentException("usage: decrypt|encrypt IN OUT");
            String text = new String(System.in.readAllBytes(), StandardCharsets.US_ASCII).trim();
            byte[] key = BackupEnvelope.parseRecoveryKey(text);
            byte[] input = Files.readAllBytes(Path.of(args[1]));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            if (args[0].equals("decrypt")) {
                try (BackupEnvelope.Reader reader = BackupEnvelope.decrypt(new ByteArrayInputStream(input), null, key)) {
                    reader.transferTo(result);
                    reader.finish();
                }
            } else if (args[0].equals("encrypt")) {
                SecureRandom random = new SecureRandom();
                byte[] salt = new byte[BackupEnvelope.SALT];
                random.nextBytes(salt);
                byte[] slotKey = BackupEnvelope.recoverySlotKey(key, salt);
                try (BackupEnvelope.Writer writer = BackupEnvelope.encrypt(result, salt, BackupEnvelope.MIN_ITERATIONS, null, slotKey, random::nextBytes)) {
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
