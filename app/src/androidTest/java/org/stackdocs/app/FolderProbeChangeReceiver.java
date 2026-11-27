package org.stackdocs.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Test code only: logs Filen's SHARED_CHANGED notice (tree id only). */
public final class FolderProbeChangeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Log.i("FP", "CHANGED action=" + intent.getAction() + " tree=" + intent.getStringExtra("app.filenfiles.extra.TREE_URI"));
    }
}
