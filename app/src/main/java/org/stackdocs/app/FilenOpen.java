package org.stackdocs.app;

import android.content.Context;
import android.content.Intent;
import java.util.List;

/**
 * Opens a document the partner shares (slice 7, S7-3): the Filen locator (uuid and path) goes to Filen Files, which resolves it in the received
 * share. Stack never calls the owner app for such a document. `OPEN_SHARED` is not shipped by Filen Files yet (part F of the runbook); until it is,
 * nothing resolves it and the person gets {@link #MISSING_MESSAGE} while the saved text and preview stay on screen.
 */
final class FilenOpen {
    static final String ACTION = "app.filenfiles.action.OPEN_SHARED";
    static final String EXTRA_UUID = "app.filenfiles.extra.UUID";
    static final String EXTRA_PATH = "app.filenfiles.extra.PATH";
    static final String MISSING_MESSAGE = "Open needs Filen Files; the saved text and preview are shown here.";

    private FilenOpen() {}

    /** The explicit request for one Filen place. */
    static Intent request(String uuid, String path) {
        return new Intent(ACTION).setPackage(FilenFolder.PACKAGE).putExtra(EXTRA_UUID, uuid).putExtra(EXTRA_PATH, path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The request for a stored record's locators, or null when it has no Filen place or no Filen Files activity answers this action. */
    static Intent request(Context context, String locatorsJson) {
        List<LinkRecord.Locator> list = LinkRecord.parseLocators(locatorsJson);
        if (list == null) return null;
        for (LinkRecord.Locator l : list) {
            if (LinkRecord.Locator.FILEN.equals(l.type) && l.uuid != null && l.path != null) {
                Intent intent = request(l.uuid, l.path);
                return LinkCallers.filenActivity(context, intent) ? intent : null;
            }
        }
        return null;
    }
}
