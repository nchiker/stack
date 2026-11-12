package org.stackdocs.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/** Where Android's installer reports back (not exported). It opens Android's own Update confirmation. */
public final class InstallResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : legacyIntent(intent);
            if (confirm != null) {
                try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
                catch (RuntimeException ignored) { /* reported below */ }
            }
            Toast.makeText(context, "The update could not start", Toast.LENGTH_LONG).show();
            return;
        }
        AppUpdater.clearCache(context);
        if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_FAILURE_ABORTED) return;
        String words = status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                ? "This update is not signed like your Stack, so Android refused it"
                : status == PackageInstaller.STATUS_FAILURE_STORAGE ? "Not enough space for the update" : "The update did not install";
        Toast.makeText(context, words, Toast.LENGTH_LONG).show();
    }

    @SuppressWarnings("deprecation")
    private static Intent legacyIntent(Intent intent) { return intent.getParcelableExtra(Intent.EXTRA_INTENT); }
}
