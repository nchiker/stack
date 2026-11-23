package org.stackdocs.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The partner's first-run path ("I'm joining my partner"): four steps, one idea each. This class holds the plain decisions (which step is next,
 * which apps are still missing, Filen or the folder picker) and the few things kept on this phone (the step reached, "signed in" and "scanned"
 * answers, "setup finished"). Nothing secret is stored here: the owner's email and pairing nonce from the scanned code stay in memory only.
 */
final class PartnerJourney {
    static final int INSTALL = 1, SIGN_IN = 2, SCAN = 3, ALLOW = 4, DONE = 5, STEPS = 4;
    static final String PREFS = "partner_journey";

    private PartnerJourney() {}

    /** What is already finished. Each answer is worked out from the phone where it can be, and remembered where it cannot. */
    static final class Done {
        final boolean apps, signedIn, scanned, folder;
        Done(boolean apps, boolean signedIn, boolean scanned, boolean folder) { this.apps = apps; this.signedIn = signedIn; this.scanned = scanned; this.folder = folder; }
    }

    /** The first step that is not finished; finished steps are skipped, so a person who comes back lands where they stopped. */
    static int resolve(Done done) {
        if (!done.apps) return INSTALL;
        if (!done.signedIn) return SIGN_IN;
        if (!done.scanned) return SCAN;
        if (!done.folder) return ALLOW;
        return DONE;
    }

    /** "Step 2 of 4", or "" on the finish screen. */
    static String progress(int step) { return step < INSTALL || step > STEPS ? "" : "Step " + step + " of " + STEPS; }

    /** Packages of the companion apps this journey needs and the phone does not have yet (installed but out of date counts as installed). */
    static List<String> appsToInstall(Function<Companions.App, Companions.State> states) {
        List<String> out = new ArrayList<>();
        for (Companions.App app : needed()) if (states.apply(app) == Companions.State.NOT_INSTALLED) out.add(app.pkg);
        return out;
    }

    static List<Companions.App> needed() {
        List<Companions.App> out = new ArrayList<>();
        out.add(Companions.FILEN); out.add(Companions.AMIPRO);
        return out;
    }

    enum Route { FILEN, PICKER }

    /** Filen's own Allow screen only when this Filen Files offers it and the scanned code named the owner; otherwise the system folder picker (Filen Files 0.4.7). */
    static Route grantRoute(boolean filenOffers, String ownerEmail) {
        return filenOffers && PartnerQr.validEmail(ownerEmail) ? Route.FILEN : Route.PICKER;
    }

    /** Home shows the two first-run cards only on a phone with nothing in the library, no finished setup, and an unfiltered Home. */
    static boolean showFirstRun(boolean setupDone, int documents, int links, boolean plainHome) {
        return !setupDone && documents == 0 && links == 0 && plainHome;
    }

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean setupDone(Context context) { return prefs(context).getBoolean("setup_done", false); }
    static void setSetupDone(Context context) { prefs(context).edit().putBoolean("setup_done", true).commit(); }
    static int step(Context context) { return prefs(context).getInt("step", 0); }
    static void saveStep(Context context, int step) { prefs(context).edit().putInt("step", step).commit(); }
    /** True once the person started the joining path and has not finished it. */
    static boolean started(Context context) { int step = step(context); return step >= INSTALL && step <= STEPS; }
    /** "Not now" on the Home cards: they stay away; the menu items remain. */
    static boolean dismissed(Context context) { return prefs(context).getBoolean("dismissed", false); }
    static void dismiss(Context context) { prefs(context).edit().putBoolean("dismissed", true).commit(); }
    static boolean signedInConfirmed(Context context) { return prefs(context).getBoolean("signed_in", false); }
    static void setSignedIn(Context context) { prefs(context).edit().putBoolean("signed_in", true).commit(); }
    static boolean scanned(Context context) { return prefs(context).getBoolean("scanned", false); }
    static void setScanned(Context context) { prefs(context).edit().putBoolean("scanned", true).commit(); }
}
