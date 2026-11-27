package org.stackdocs.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.File;
import java.security.KeyStore;
import java.util.Enumeration;

/**
 * The rest of retiring the QR partner sharing (docs/FOLDER_SHARING_DESIGN.md section 6), run once at app start after the library migration has
 * deleted the partner's records ({@link LibraryDatabase#retireQrSharing}). It deletes the sealed partner keys and their Keystore keys, the old
 * sharing and receiving folder grants and settings, and the preview files of the deleted records. Nothing is read from `Stack shared.stackshare`
 * any more; the two trial shares in Filen are left alone. Safe to run twice: each step only removes what is still there. Keys are never read,
 * printed or logged here.
 */
final class QrRetirement {
    static final String ALIAS_PREFIX = "stack_partner_keys";
    private static final String KEY_FILE = "partner_keys.bin";
    private static final String DONE = "qr_retired";

    private QrRetirement() {}

    private static SharedPreferences marker(Context context) { return context.getSharedPreferences("folder_sharing", Context.MODE_PRIVATE); }

    /** Runs the clean-up once per installation; the marker is set only after the key deletion succeeded (otherwise the next start tries again). Call off the UI thread. */
    static void runOnce(Context context, LinkStore links) {
        if (marker(context).getBoolean(DONE, false)) return;
        SourceStore sources = null;
        try { if (context.getApplicationContext() instanceof StackApplication) sources = ((StackApplication) context.getApplicationContext()).library.sources; } catch (RuntimeException unavailable) { /* cannot tell: grants are kept */ }
        boolean keysGone = run(context, links, sources) >= 0;
        if (keysGone) marker(context).edit().putBoolean(DONE, true).commit();
    }

    /** The clean-up itself, for checks. Returns how many Keystore keys were removed, or -1 when the Keystore could not be cleared (nothing is marked done then). */
    static int run(Context context, LinkStore links, SourceStore sources) {
        int keys = deleteKeys(context);
        releaseFolders(context, sources);
        context.getSharedPreferences("partner_journey", Context.MODE_PRIVATE).edit().clear().commit();
        if (links != null) try { links.removeOrphanPreviews(); } catch (RuntimeException unreadable) { /* the next start tries again */ }
        return keys;
    }

    /** Deletes the sealed key file (and the temporary files of its atomic writes) and every Keystore key whose alias starts with the old prefix. */
    static int deleteKeys(Context context) {
        File dir = context.getFilesDir();
        for (String name : new String[]{KEY_FILE, KEY_FILE + ".new", KEY_FILE + ".bak"}) new File(dir, name).delete();
        int removed = 0;
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            java.util.List<String> doomed = new java.util.ArrayList<>();
            for (Enumeration<String> aliases = store.aliases(); aliases.hasMoreElements(); ) {
                String alias = aliases.nextElement();
                if (alias.startsWith(ALIAS_PREFIX)) doomed.add(alias);
            }
            for (String alias : doomed) { store.deleteEntry(alias); removed++; }
        } catch (java.security.GeneralSecurityException | java.io.IOException | RuntimeException unavailable) { return -1; }   // not deleted: the caller tries again at the next start
        return removed;
    }

    /** True when a folder source (including the shared scan folder) holds exactly this tree: its grant is the source's now and stays. */
    private static boolean usedBySource(SourceStore sources, Uri tree) {
        if (sources == null) return true;   // cannot tell: keep the grant
        try { return sources.byTree(tree.toString()) != null; } catch (RuntimeException unavailable) { return true; }
    }

    /** The old archive the QR sharing left in the folder that was shared. */
    static final String OLD_ARCHIVE = "Stack shared.stackshare";
    static final String OLD_ARCHIVE_NOTE = "Your old shared archive is still in the folder you shared before. Delete it in Filen if you no longer want your partner to see it.";
    /** Set when the archive could not be deleted: the Folders screen shows {@link #OLD_ARCHIVE_NOTE} once. */
    static final String NOTE_FLAG = "old_archive_note";

    /** The note is waiting: returns it once and clears it. */
    static String takeNote(Context context) {
        SharedPreferences p = marker(context);
        if (!p.getBoolean(NOTE_FLAG, false)) return "";
        p.edit().remove(NOTE_FLAG).commit();
        return OLD_ARCHIVE_NOTE;
    }

    static java.util.List<Uri> releaseFolders(Context context, SourceStore sources) {
        return releaseFolders(context, sources, tree -> new ScanShare.SafFolder(context.getContentResolver(), tree));
    }

    /**
     * Gives back the old partner folder grants (unless the automatic backup or a folder source still uses the tree) and clears the old settings. Before the
     * share folder's grant goes, `Stack shared.stackshare` is deleted from that folder; when that is not possible the one-time note is set.
     */
    static java.util.List<Uri> releaseFolders(Context context, SourceStore sources, java.util.function.Function<Uri, ScanShare.Folder> folders) {
        java.util.List<Uri> released = new java.util.ArrayList<>();
        for (String name : new String[]{"partner_share", "partner_import"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            String saved = prefs.getString("tree", "");
            if (!saved.isEmpty()) {
                Uri tree = Uri.parse(saved);
                boolean inUse = tree.equals(AutoBackup.tree(context)) || usedBySource(sources, tree);
                if (!inUse) {
                    if ("partner_share".equals(name)) {
                        boolean gone;
                        try { folders.apply(tree).delete(OLD_ARCHIVE); gone = true; } catch (java.io.IOException | RuntimeException unavailable) { gone = false; }
                        if (!gone) marker(context).edit().putBoolean(NOTE_FLAG, true).commit();
                    }
                    released.add(tree);
                    SourceStore.releaseUnlessUsed(context, tree, used -> usedBySource(sources, used));
                }
            }
            prefs.edit().clear().commit();
        }
        return released;
    }
}
