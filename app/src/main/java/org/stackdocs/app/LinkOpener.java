package org.stackdocs.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Opens a linked document in the app that owns it (docs/STACK_LINK_CONTRACT.md, `open`). Stack asks the owner's provider
 * `<owner>.stacklink` for an intent and starts it; no file bytes pass through Stack. The intent comes from another app, so
 * it is only started if it names the owner's own component and grants nothing.
 */
final class LinkOpener {
    /** A refusal the person can read. */
    static final class Failure extends Exception { Failure(String message) { super(message); } }

    private LinkOpener() {}

    static String ownerName(String owner) {
        if ("app.amipro.editor".equals(owner) || "app.amipro.editor.debug".equals(owner)) return "Ami Pro";
        int dot = owner.lastIndexOf('.');
        return dot >= 0 && dot < owner.length() - 1 ? owner.substring(dot + 1) : owner;
    }

    /** True only for an explicit intent to a component of `owner` that grants no URI access and carries no selector or clip. */
    static boolean valid(Intent intent, String owner) {
        if (intent == null || intent.getComponent() == null || !owner.equals(intent.getComponent().getPackageName())) return false;
        if (intent.getPackage() != null && !owner.equals(intent.getPackage())) return false;
        int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        return (intent.getFlags() & grants) == 0 && intent.getClipData() == null && intent.getSelector() == null;
    }

    /**
     * The owner app's own launcher screen, for "Set up again": the document is not known there yet, so there is nothing to open, only
     * the app to start so the person can share the document with Stack again. Only an owner whose provider passes the same trust check
     * as every other call is started. Not on the main thread (the check reads package certificates).
     */
    static Intent launch(Context context, String owner) throws Failure {
        String name = ownerName(owner);
        if (!LinkCallers.trustedOwnerProvider(context, owner)) throw new Failure(name + " isn't installed, or is too old to work with Stack.");
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(owner);
        if (intent == null || intent.getComponent() == null || !owner.equals(intent.getComponent().getPackageName())) throw new Failure(name + " could not be started.");
        return new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Asks the owner for the intent that opens the document. Runs a provider call, so not on the main thread. */
    @SuppressWarnings("deprecation")
    static Intent request(Context context, LinkStore.Link link, String mode) throws Failure {
        String name = ownerName(link.owner);
        Bundle reply;
        try {
            Bundle extras = new Bundle(); extras.putString("mode", mode);
            reply = LinkChecker.call(context, link.owner, "open", link.linkId, extras);
        } catch (LinkChecker.Busy busy) {
            throw new Failure(name + " is still busy. Try again in a moment.");
        } catch (java.util.concurrent.TimeoutException late) {
            throw new Failure(name + " took too long to answer. Try again.");
        } catch (IllegalArgumentException | SecurityException | UnsupportedOperationException | IllegalStateException error) {
            throw new Failure(name + " isn't installed, or is too old to work with Stack. You can still read the saved text here.");
        } catch (Exception error) {
            Throwable cause = error.getCause();
            if (cause instanceof IllegalArgumentException || cause instanceof SecurityException || cause instanceof UnsupportedOperationException)
                throw new Failure(name + " isn't installed, or is too old to work with Stack. You can still read the saved text here.");
            throw new Failure(name + " could not be reached. Try again.");
        }
        if (reply == null) throw new Failure(name + " isn't installed, or is too old to work with Stack. You can still read the saved text here.");
        String problem = reply.getString("error");
        if (problem != null) throw new Failure("not_found".equals(problem) ? name + " can't find this document any more." : name + " could not open it.");
        Intent intent;
        try { intent = reply.getParcelable("intent"); } catch (RuntimeException error) { intent = null; }
        if (!valid(intent, link.owner)) throw new Failure(name + " sent an answer Stack can't use. Update both apps.");
        return new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
