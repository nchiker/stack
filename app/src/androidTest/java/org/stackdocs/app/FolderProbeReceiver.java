package org.stackdocs.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Test code only: runs a probe step in a background Android user (no UI). `am broadcast --user N -n PKG/org.stackdocs.app.FolderProbeReceiver --es step X --es a .. --es b .. --es c ..` */
public final class FolderProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final String step = intent.getStringExtra("step"), a = intent.getStringExtra("a"), b = intent.getStringExtra("b"), c = intent.getStringExtra("c");
        new Thread(() -> {
            Log.i("FP", "BEGIN " + step + " (receiver) a=" + a + " b=" + b + " c=" + c);
            try { new FolderProbeCore(context.getApplicationContext(), step, a, b, c).run(); }
            catch (Throwable t) { Log.i("FP", "FAIL " + step + " " + t.getClass().getName() + ": " + t.getMessage()); }
            Log.i("FP", "END " + step);
            pending.finish();
        }).start();
    }
}
