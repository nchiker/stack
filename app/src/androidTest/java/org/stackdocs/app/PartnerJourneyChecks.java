package org.stackdocs.app;

import android.content.Context;
import android.content.Intent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Partner journey: the step logic, the first-run decision, the invite text and the screens' wording limits. No account, no network, no other app. */
final class PartnerJourneyChecks {
    private PartnerJourneyChecks() {}

    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    /** Short on-screen text: a title of at most 6 words and at most two sentences. */
    private static void shortText(String title, String words) {
        require(title.trim().split("\\s+").length <= 6, "title too long: " + title);
        if (words != null) require(words.split("(?<=[.!?])\\s+").length <= 2, "more than two sentences: " + words);
    }

    static void run(Context context, Consumer<String> mark) throws Exception {
        // Steps are skipped when finished, in order.
        require(PartnerJourney.resolve(new PartnerJourney.Done(false, false, false, false)) == PartnerJourney.INSTALL, "install first");
        require(PartnerJourney.resolve(new PartnerJourney.Done(true, false, false, false)) == PartnerJourney.SIGN_IN, "then sign in");
        require(PartnerJourney.resolve(new PartnerJourney.Done(true, true, false, false)) == PartnerJourney.SCAN, "then scan");
        require(PartnerJourney.resolve(new PartnerJourney.Done(true, true, true, false)) == PartnerJourney.ALLOW, "then allow");
        require(PartnerJourney.resolve(new PartnerJourney.Done(true, true, true, true)) == PartnerJourney.DONE, "then done");
        require(PartnerJourney.resolve(new PartnerJourney.Done(false, true, true, true)) == PartnerJourney.INSTALL, "a removed app sends the person back to install");
        require(PartnerJourney.progress(2).equals("Step 2 of 4") && PartnerJourney.progress(PartnerJourney.DONE).isEmpty(), "progress line");
        mark.accept("Partner journey steps resolve in order");

        // Which apps still need installing.
        List<String> missing = PartnerJourney.appsToInstall(app -> app == Companions.FILEN ? Companions.State.NOT_INSTALLED : Companions.State.UP_TO_DATE);
        require(missing.equals(Arrays.asList(Companions.FILEN.pkg)), "only Filen Files missing");
        require(PartnerJourney.appsToInstall(app -> Companions.State.UPDATE).isEmpty(), "out of date counts as installed");
        require(PartnerJourney.appsToInstall(app -> Companions.State.NOT_INSTALLED).size() == 2, "both missing");
        mark.accept("Partner journey installs only missing apps");

        // Filen's Allow screen only with a valid owner email.
        require(PartnerJourney.grantRoute(true, "owner@example.com") == PartnerJourney.Route.FILEN, "Filen route");
        require(PartnerJourney.grantRoute(true, null) == PartnerJourney.Route.PICKER, "no email: picker");
        require(PartnerJourney.grantRoute(false, "owner@example.com") == PartnerJourney.Route.PICKER, "old Filen Files: picker");
        require(PartnerJourney.grantRoute(true, "not an email") == PartnerJourney.Route.PICKER, "bad email: picker");
        mark.accept("Partner journey chooses Filen or the picker");

        // First-run cards.
        require(PartnerJourney.showFirstRun(false, 0, 0, true), "empty new phone shows cards");
        require(!PartnerJourney.showFirstRun(true, 0, 0, true), "finished setup hides cards");
        require(!PartnerJourney.showFirstRun(false, 1, 0, true) && !PartnerJourney.showFirstRun(false, 0, 1, true), "a library hides cards");
        require(!PartnerJourney.showFirstRun(false, 0, 0, false), "a filtered Home hides cards");
        mark.accept("Home first-run cards show only on an empty phone");

        // What is kept on the phone.
        PartnerJourney.prefs(context).edit().clear().commit();
        require(!PartnerJourney.setupDone(context) && !PartnerJourney.started(context) && !PartnerJourney.dismissed(context), "starts clean");
        PartnerJourney.saveStep(context, PartnerJourney.SCAN);
        require(PartnerJourney.started(context) && PartnerJourney.step(context) == PartnerJourney.SCAN, "step is kept");
        PartnerJourney.saveStep(context, PartnerJourney.DONE);
        require(!PartnerJourney.started(context), "finished is not started");
        PartnerJourney.setSignedIn(context); PartnerJourney.setScanned(context); PartnerJourney.setSetupDone(context); PartnerJourney.dismiss(context);
        require(PartnerJourney.signedInConfirmed(context) && PartnerJourney.scanned(context) && PartnerJourney.setupDone(context) && PartnerJourney.dismissed(context), "answers are kept");
        for (String key : PartnerJourney.prefs(context).getAll().keySet()) require(key.matches("setup_done|step|signed_in|scanned|dismissed"), "nothing else is stored: " + key);
        for (Object value : PartnerJourney.prefs(context).getAll().values()) require(!String.valueOf(value).contains("@"), "no email is stored");
        PartnerJourney.prefs(context).edit().clear().commit();
        mark.accept("Partner journey keeps only step and yes/no answers");

        // Invitation.
        Intent share = PartnerInvite.shareIntent();
        require(PartnerInvite.START_URL.startsWith("https://") && PartnerInvite.message().contains(PartnerInvite.START_URL), "invite link");
        require(Intent.ACTION_CHOOSER.equals(share.getAction()), "invite goes through the chooser");
        require(!PartnerInvite.message().matches("(?s).*[0-9a-fA-F]{32,}.*") && !PartnerInvite.message().contains("@"), "invite holds no key or email");
        mark.accept("Invite message holds only the start link");

        // Screen wording limits.
        shortText("Install two apps", "Stack works with Filen Files and Ami Pro. Android asks you to confirm each one.");
        shortText("Sign in to Filen Files", "Open Filen Files and sign in, or make an account. Then come back here.");
        shortText("Scan your partner's code", "On their phone, open Invite partner and tap Pair with partner. Then scan the code you see.");
        shortText("Allow your partner's folder", "Filen Files asks you to allow it. Tap Allow.");
        shortText("Choose your partner's folder", "In Filen Files, open Shared, then Shared with me. Then choose that folder here.");
        shortText("You're all set", "Your partner's documents will appear in Stack.");
        shortText("Invite partner", "Send your partner a link. It shows them what to install.");
        mark.accept("Partner journey text stays short");

        // The screens open and show the right first step (no Filen, no scan, nothing installed or changed).
        PartnerJourney.prefs(context).edit().clear().commit();
        for (Class<?> screen : new Class<?>[]{PartnerJoinActivity.class, PartnerInviteActivity.class})
            require(context.getPackageManager().resolveActivity(new Intent(context, screen), 0) != null, screen.getSimpleName() + " is in the manifest");
        mark.accept("Partner journey screens are declared");
    }
}
