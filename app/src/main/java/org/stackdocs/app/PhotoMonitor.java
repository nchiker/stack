package org.stackdocs.app;

import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Only future completed local Camera photos after explicit consent. No cloud photo picker/provider. */
final class PhotoMonitor {
    static final int PERIODIC=7101,TRIGGER=7102;
    final Object consent=new Object();
    private final StackApplication app;private final SharedPreferences policy;
    private final ExecutorService photos=Executors.newSingleThreadExecutor();private final java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean();
    private ContentObserver observer;volatile String status="Off";
    PhotoMonitor(StackApplication app){this.app=app;policy=app.getSharedPreferences("local_policy",Context.MODE_PRIVATE);}
    boolean enabled(){return policy.getBoolean("photos",false);}
    boolean permission(){return app.checkSelfPermission(Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED||(Build.VERSION.SDK_INT>=34&&app.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED);}
    String scope(){if(!permission())return "Photo permission unavailable";return Build.VERSION.SDK_INT>=34&&app.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)!=PackageManager.PERMISSION_GRANTED?"Selected photos only":"Local Camera photos";}
    void enable(){synchronized(consent){policy.edit().putBoolean("photos",true).putLong("photo_cursor",0).putLong("photo_since",System.currentTimeMillis()/1000).putLong("photo_epoch",policy.getLong("photo_epoch",0)+1).putString("media_version",version()).commit();}configure();}
    void disable(){synchronized(consent){policy.edit().putBoolean("photos",false).putLong("photo_epoch",policy.getLong("photo_epoch",0)+1).commit();}configure();}
    private String version(){return Build.VERSION.SDK_INT>=29?MediaStore.getVersion(app):"legacy";}
    void configure(){JobScheduler scheduler=app.getSystemService(JobScheduler.class);
        if(!enabled()||!permission()){scheduler.cancel(PERIODIC);scheduler.cancel(TRIGGER);if(observer!=null){app.getContentResolver().unregisterContentObserver(observer);observer=null;}status=enabled()?"Paused · photo access unavailable":"Off";return;}
        status=scope()+" · future photos; Android controls background timing";
        try {
            scheduler.schedule(new JobInfo.Builder(PERIODIC,new ComponentName(app,PhotoImportJob.class)).setPeriodic(15*60*1000L).setPersisted(true).build());
            scheduleTrigger();
            if(observer==null){observer=new ContentObserver(new Handler(Looper.getMainLooper())){@Override public void onChange(boolean self){kick(null);}};app.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,observer);}
        }catch(RuntimeException unavailable){status="Background scheduling unavailable; check while Stack is open";}
    }
    private void scheduleTrigger(){if(!enabled()||!permission())return;app.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(TRIGGER,new ComponentName(app,PhotoImportJob.class)).addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS)).setTriggerContentUpdateDelay(5000).setTriggerContentMaxDelay(30000).build());}
    void kick(Runnable complete){if(!enabled()||!permission()||!app.access.allowed()||!active.compareAndSet(false,true)){if(complete!=null)complete.run();return;}
        long epoch=policy.getLong("photo_epoch",0),since=policy.getLong("photo_since",Long.MAX_VALUE);
        photos.execute(() -> {try{scan(epoch,since);}catch(Exception unavailable){status="Photo import paused · access or storage unavailable";}finally{active.set(false);new Handler(Looper.getMainLooper()).post(() -> {scheduleTrigger();app.notifyLibraryChanged();if(complete!=null)complete.run();});}});
    }
    private boolean current(long epoch){return enabled()&&permission()&&app.access.allowed()&&policy.getLong("photo_epoch",0)==epoch;}
    private void scan(long epoch,long since)throws Exception {
        if(!version().equals(policy.getString("media_version",""))){status="Media collection changed. Disable and enable to start with future photos.";return;}
        Uri collection=MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] columns={"_id","date_added","date_modified","_size","mime_type",Build.VERSION.SDK_INT>=29?"relative_path":"bucket_display_name"};
        String selection="date_added>? AND _size>0 AND date_modified<? AND _id>?"+(Build.VERSION.SDK_INT>=29?" AND is_pending=0":"")+(Build.VERSION.SDK_INT>=30?" AND is_trashed=0":"");
        String[] arguments={Long.toString(since),Long.toString(System.currentTimeMillis()/1000-3),Long.toString(policy.getLong("photo_cursor",0))};
        // At most 100 future rows per sweep; oldest unseen rows remain eligible on the next sweep.
        try(Cursor rows=query(collection,columns,selection,arguments)) {
            if(rows==null)return;int examined=0;long cursor=0;
            while(rows.moveToNext()&&examined<100&&current(epoch)) {
                long mediaId=rows.getLong(0);cursor=mediaId;examined++;Uri uri=ContentUris.withAppendedId(collection,mediaId);String key=uri.toString();
                if(app.io.submit(() -> new PhotoLedger(app.library).decided(key)).get())continue;
                String path=rows.getString(5),mime=rows.getString(4);
                if(path==null||!(Build.VERSION.SDK_INT>=29?path.equalsIgnoreCase("DCIM/Camera/"):path.equalsIgnoreCase("Camera"))||!Arrays.asList("image/jpeg","image/png","image/webp").contains(mime)||rows.getLong(3)>256L*1024*1024){app.io.submit(() -> record(key,"","ignored","")).get();continue;}
                File directory=new File(app.getFilesDir(),"photo_staging");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException();File file=new File(directory,UUID.randomUUID().toString());
                try {
                    MessageDigest hash=MessageDigest.getInstance("SHA-256");
                    try(InputStream in=app.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(file)){if(in==null)throw new IOException();byte[] buffer=new byte[65536];long size=0;int n;while((n=in.read(buffer))!=-1){if(!current(epoch))return;if(n==0||((size+=n)>256L*1024*1024))throw new IOException();hash.update(buffer,0,n);out.write(buffer,0,n);}out.getFD().sync();}
                    String digest=hex(hash.digest());
                    boolean duplicate=app.io.submit(() -> new PhotoLedger(app.library).duplicate(key,digest)).get();
                    if(duplicate){app.io.submit(() -> record(key,digest,"duplicate","")).get();continue;}
                    Bitmap bitmap=PageRenderer.preview(file,mime,0,1000);boolean document;try{document=candidate(bitmap);}finally{bitmap.recycle();}
                    if(!document){app.io.submit(() -> record(key,digest,"ignored","")).get();continue;}
                    String id=app.io.submit(() -> new PhotoLedger(app.library).reserve(key,digest)).get();if(id==null)continue;
                    boolean saved=app.io.submit(() -> {
                        if(!current(epoch))return false;
                        app.library.importDocument(Uri.fromFile(file),id,"Photo document",() -> current(epoch),consent);
                        new PhotoLedger(app.library).complete(key);return true;
                    }).get();
                    if(saved){status="Imported a future document photo";notifyImport(id);app.notifyLibraryChanged();}
                }catch(Exception failure){if(current(epoch))app.io.submit(() -> {PhotoLedger ledger=new PhotoLedger(app.library);ledger.record(key,"","failed","");ledger.failed(key);}).get();status="A photo couldn't be imported. Use Photo import to select it manually.";}
                finally{file.delete();}
            }
            if(current(epoch))policy.edit().putLong("photo_cursor",examined<100?0:cursor).apply();
        }
    }
    private Cursor query(Uri collection,String[] columns,String selection,String[] arguments){
        if(Build.VERSION.SDK_INT>=26){Bundle query=new Bundle();query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,selection);query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,arguments);query.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,new String[]{"_id"});query.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,ContentResolver.QUERY_SORT_DIRECTION_ASCENDING);query.putInt(ContentResolver.QUERY_ARG_LIMIT,100);return app.getContentResolver().query(collection,columns,query,null);}
        return app.getContentResolver().query(collection,columns,selection,arguments,"_id ASC LIMIT 100");
    }
    /** Conservative visual page candidate: reliable quadrilateral plus paper-like center and text-like dark strokes. */
    static boolean candidate(Bitmap bitmap) {
        if(bitmap.getWidth()<300||bitmap.getHeight()<300)return false;
        float[] corners=BoundaryDetector.suggest(bitmap);if(corners==null)return false;
        double area=0;for(int i=0;i<4;i++){int n=(i+1)%4;area+=corners[i*2]*corners[n*2+1]-corners[n*2]*corners[i*2+1];}area=Math.abs(area)/2;
        if(area<.35||area>.96)return false;
        int light=0,dark=0,total=0,changes=0;for(int y=bitmap.getHeight()/5;y<bitmap.getHeight()*4/5;y+=4){boolean previous=false;for(int x=bitmap.getWidth()/5;x<bitmap.getWidth()*4/5;x+=4){int c=bitmap.getPixel(x,y),r=(c>>16)&255,g=(c>>8)&255,b=c&255;int gray=(r+g+b)/3;boolean ink=gray<100;if(ink!=previous)changes++;previous=ink;if(gray>170&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<55)light++;if(ink)dark++;total++;}}
        return total>0&&(double)light/total>.70&&(double)dark/total>.012&&(double)dark/total<.25&&changes>60;
    }
    private void record(String source,String digest,String state,String doc){new PhotoLedger(app.library).record(source,digest,state,doc);}
    private static String hex(byte[] hash){StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    void notifyImport(String id){if(!policy.getBoolean("import_notifications",false)||!app.access.allowed())return;if(Build.VERSION.SDK_INT>=33&&app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        NotificationManager manager=app.getSystemService(NotificationManager.class);if(!manager.areNotificationsEnabled())return;
        String channel="imports";if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(channel,"Document imports",NotificationManager.IMPORTANCE_DEFAULT));
        Intent open=new Intent(app,DocumentActivity.class).setData(Uri.parse("stack-import:"+id)).putExtra("document_id",id);
        PendingIntent pending=PendingIntent.getActivity(app,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(app,channel):new Notification.Builder(app);
        builder.setSmallIcon(R.drawable.ic_stack).setContentTitle("Document imported").setContentText("Open Stack to review it").setVisibility(Notification.VISIBILITY_PRIVATE).setGroup("stack.imports").setContentIntent(pending).setAutoCancel(true);
        manager.notify(id.hashCode(),builder.build());
        Notification.Builder summary=Build.VERSION.SDK_INT>=26?new Notification.Builder(app,channel):new Notification.Builder(app);summary.setSmallIcon(R.drawable.ic_stack).setContentTitle("Stack imports").setContentText("Open the latest saved document").setGroup("stack.imports").setGroupSummary(true).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true);manager.notify(7100,summary.build());
    }
}
