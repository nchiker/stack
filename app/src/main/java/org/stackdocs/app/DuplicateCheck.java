package org.stackdocs.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds files about to be imported that Stack already has, or that appear twice in one selection.
 *
 * Identity is the SHA-256 of the file's bytes compared with the saved original of an imported document
 * ({@code files/documents/<id>/source}). Sizes are compared first, so a file whose size matches no existing
 * original (the usual case) is never read at all. Nothing is stored: no database column, no sync state.
 * Scanned documents have no single original file and are never matched.
 */
final class DuplicateCheck {
    /** Same limit as the import itself; larger files fail there anyway. */
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    static final class Match {
        final Uri uri;
        /** Document that already holds these bytes, or empty when this is the second copy within the selection. */
        final String documentId;
        /** Title of the existing document, or the file name of the earlier copy in the selection. */
        final String label;
        final long addedAt;

        Match(Uri uri, String documentId, String label, long addedAt) {
            this.uri = uri; this.documentId = documentId; this.label = label; this.addedAt = addedAt;
        }
    }

    private DuplicateCheck() {}

    /** Blocking; call from a worker thread (not the repository executor, hashing can take seconds). */
    static List<Match> find(StackApplication app, List<Uri> uris) throws Exception {
        ContentResolver resolver = app.getContentResolver();
        Map<Long, List<String>> existing = existingLengths(new File(app.getFilesDir(), "documents"));
        int count = uris.size();
        long[] sizes = new long[count];
        Map<Long, Integer> incomingSizes = new HashMap<>();
        for (int i = 0; i < count; i++) {
            sizes[i] = size(resolver, uris.get(i));
            if (sizes[i] > 0) incomingSizes.merge(sizes[i], 1, Integer::sum);
        }
        Map<String, String> existingHash = new HashMap<>();   // document id -> sha
        Map<String, Integer> firstWithHash = new HashMap<>(); // sha -> index of first incoming file
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Uri uri = uris.get(i);
            long size = sizes[i];
            // Known size that nothing else shares: cannot be a duplicate, so do not read it.
            if (size > 0 && !existing.containsKey(size) && incomingSizes.get(size) == 1) continue;
            if (size > MAX_BYTES) continue;
            String[] hashed = hash(resolver, uri);   // {sha, length}
            if (hashed == null) continue;
            String sha = hashed[0];
            long length = Long.parseLong(hashed[1]);
            String owner = null;
            List<String> candidates = existing.get(length);
            if (candidates != null) {
                for (String id : candidates) {
                    String known = existingHash.get(id);
                    if (known == null) {
                        String[] other = hash(new File(new File(app.getFilesDir(), "documents/" + id), "source"));
                        known = other == null ? "" : other[0];
                        existingHash.put(id, known);
                    }
                    if (known.equals(sha)) { owner = id; break; }
                }
            }
            if (owner != null) {
                final String documentId = owner;
                Document document = null;
                try { document = app.io.submit(() -> app.library.isTrashed(documentId) ? null : app.library.document(documentId)).get(); }
                catch (Exception missing) { /* original left over from a deleted document: not a duplicate */ }
                // A document in Recently deleted does not count: the user asked for it to be gone.
                if (document != null) matches.add(new Match(uri, owner, document.title, document.addedAt));
            } else if (firstWithHash.containsKey(sha)) {
                matches.add(new Match(uri, "", name(resolver, uris.get(firstWithHash.get(sha))), 0));
            } else {
                firstWithHash.put(sha, i);
            }
        }
        return matches;
    }

    /**
     * Groups of library documents whose saved originals are byte-identical, oldest copy first inside a group and the
     * largest groups first. Documents in Recently deleted and scanned documents (no single original) are left out.
     * Blocking; call from a worker thread.
     */
    static List<List<Document>> scan(StackApplication app) throws Exception {
        List<Document> all = app.io.submit(() -> app.library.documents("", null, null, "newest")).get();
        File originals = new File(app.getFilesDir(), "documents");
        Map<Long, List<Document>> bySize = new HashMap<>();
        for (Document document : all) {
            long length = new File(new File(originals, document.id), "source").length();
            if (length > 0) bySize.computeIfAbsent(length, k -> new ArrayList<>()).add(document);
        }
        List<List<Document>> groups = new ArrayList<>();
        for (List<Document> sameSize : bySize.values()) {
            if (sameSize.size() < 2) continue;
            Map<String, List<Document>> byHash = new java.util.LinkedHashMap<>();
            for (Document document : sameSize) {
                String[] hashed = hash(new File(new File(originals, document.id), "source"));
                if (hashed != null) byHash.computeIfAbsent(hashed[0], k -> new ArrayList<>()).add(document);
            }
            for (List<Document> group : byHash.values()) {
                if (group.size() < 2) continue;
                java.util.Collections.sort(group, (x, y) -> x.addedAt != y.addedAt ? Long.compare(x.addedAt, y.addedAt) : x.id.compareTo(y.id));
                groups.add(group);
            }
        }
        java.util.Collections.sort(groups, (x, y) -> x.size() != y.size() ? Integer.compare(y.size(), x.size()) : Long.compare(y.get(0).addedAt, x.get(0).addedAt));
        return groups;
    }

    private static Map<Long, List<String>> existingLengths(File originals) {
        Map<Long, List<String>> out = new HashMap<>();
        File[] folders = originals.listFiles();
        if (folders == null) return out;
        for (File folder : folders) {
            File source = new File(folder, "source");
            long length = source.length();
            if (length > 0) out.computeIfAbsent(length, k -> new ArrayList<>()).add(folder.getName());
        }
        return out;
    }

    private static long size(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) { /* unknown size: the file is read instead */ }
        return -1;
    }

    private static String name(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) { /* fall through */ }
        return "another selected file";
    }

    /** {sha256 hex, length}, or null when the source cannot be read or is over the import limit. */
    private static String[] hash(ContentResolver resolver, Uri uri) {
        try (InputStream in = resolver.openInputStream(uri)) {
            return in == null ? null : digest(in);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String[] hash(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return digest(in);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String[] digest(InputStream in) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_BYTES) return null;
                sha.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return new String[]{hex.toString(), Long.toString(total)};
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** For tests: hash of a byte array in the same format as {@link #digest}. */
    static String sha256(byte[] bytes) throws IOException {
        return digest(new java.io.ByteArrayInputStream(bytes))[0];
    }

    static List<String> names(List<Match> matches, int limit) {
        List<String> out = new ArrayList<>();
        for (Match m : matches) {
            if (out.size() == limit) break;
            out.add(m.documentId.isEmpty() ? m.label + " (selected twice)" : m.label);
        }
        return out;
    }
}
