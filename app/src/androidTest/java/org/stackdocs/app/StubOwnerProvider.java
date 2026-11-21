package org.stackdocs.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicInteger;

/** A stand-in owner app (`<test package>.stacklink`) whose answers a check sets, for LinkLifecycleChecks. Runs in Stack's own process. */
public final class StubOwnerProvider extends ContentProvider {
    static volatile Bundle statReply, refreshReply;
    static volatile long delayMs;
    static volatile boolean fail;
    static final AtomicInteger calls = new AtomicInteger();
    static volatile String lastMethod, lastArgument;

    static void reset() { statReply = null; refreshReply = null; delayMs = 0; fail = false; calls.set(0); lastMethod = null; lastArgument = null; }

    @Override public boolean onCreate() { return true; }

    /** The stub lives in the test APK's own process, so a check configures and reads it through provider calls: `configure`, `report`. */
    @Override public Bundle call(String method, String argument, Bundle extras) {
        if ("configure".equals(method)) {
            extras.setClassLoader(Bundle.class.getClassLoader());
            reset();
            statReply = extras.getBundle("stat"); refreshReply = extras.getBundle("refresh"); delayMs = extras.getLong("delay"); fail = extras.getBoolean("fail");
            return new Bundle();
        }
        if ("report".equals(method)) {
            Bundle out = new Bundle();
            out.putInt("calls", calls.get()); out.putString("method", lastMethod); out.putString("argument", lastArgument);
            return out;
        }
        calls.incrementAndGet(); lastMethod = method; lastArgument = argument;
        if (delayMs > 0) try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        if (fail) throw new IllegalStateException("stub failure");
        return "stat".equals(method) ? statReply : "refresh".equals(method) ? refreshReply : null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
