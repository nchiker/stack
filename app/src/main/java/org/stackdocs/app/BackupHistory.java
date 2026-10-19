package org.stackdocs.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;

/** Successful exports on this device only. Never restored as a backup or consent claim. */
final class BackupHistory {
    static long last(Context context){return context.getSharedPreferences("backup_history",Context.MODE_PRIVATE).getLong("completed_at",0);}
    static boolean completed(Context context,long time){
        SharedPreferences prefs=context.getSharedPreferences("backup_history",Context.MODE_PRIVATE);
        long previous=last(context);
        try {
            if(prefs.edit().putLong("completed_at",time).commit())return true;
        } catch(RuntimeException ignored) { /* The archive itself is already safely closed. */ }
        prefs.edit().putLong("completed_at",previous).apply();
        return false;
    }
    static String label(Context context){
        long time=last(context);
        return time<=0?"No backup saved on this phone":"Last backup: "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(time));
    }
}
