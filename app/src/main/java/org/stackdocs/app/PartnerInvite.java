package org.stackdocs.app;

import android.content.Intent;

/** The owner's invitation: a short message with the start link. The link and message hold no secret and nothing about this library. */
final class PartnerInvite {
    /** The static start page for the partner (one place; the page shows the steps and the downloads). */
    static final String START_URL = "https://nchiker.github.io/stack-flatpak/start/";

    private PartnerInvite() {}

    static String message() { return "Join me on Stack: " + START_URL + ". Open it on your phone and your computer."; }

    static Intent shareIntent() {
        return Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message()), "Invite your partner");
    }
}
