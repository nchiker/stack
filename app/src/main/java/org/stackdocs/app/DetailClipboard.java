package org.stackdocs.app;

import android.content.ClipData;
import android.os.PersistableBundle;

/** Separate construction boundary lets fixtures verify privacy without touching the system clipboard. */
final class DetailClipboard {
    static ClipData clip(String value){ClipData clip=ClipData.newPlainText("Stack detail",value);PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);return clip;}
}
