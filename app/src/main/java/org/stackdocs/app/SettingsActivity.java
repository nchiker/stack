package org.stackdocs.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.widget.*;

/** Device-local opt-ins, never derived from restore or upgrade. */
public final class SettingsActivity extends LibraryActivity {
    private static final int PHOTOS=71,NOTIFICATIONS=72;
    private LinearLayout body;private boolean enablingPhotos;
    @Override public void onCreate(Bundle state){super.onCreate(state);if(state!=null)enablingPhotos=state.getBoolean("enabling_photos");LinearLayout root=Ui.column(this),bar=Ui.toolbar(this);bar.addView(Ui.iconButton(this,Icons.Symbol.BACK,"Back",this::finish),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));bar.addView(Ui.text(this,"Settings",18,false),new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);ScrollView scroll=new ScrollView(this);body=Ui.column(this);body.setPadding(Ui.dp(this,24),Ui.dp(this,12),Ui.dp(this,24),Ui.dp(this,24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));Ui.install(this,root);}
    @Override protected void onResume(){super.onResume();app().photos.configure();refresh();}
    @Override protected void onLibraryChanged(){refresh();}
    private void refresh(){
        if(body==null)return;body.removeAllViews();
        boolean photos=app().photos.enabled();
        body.addView(Ui.actionRow(this,Icons.Symbol.PHOTO,"Photo import",photos?(app().photos.permission()?"On · New camera photos":"Paused · Photo access needed"):"Off · New camera photos only",0xFFDCECF9,this::photoOptions));
        if(photos){body.addView(Ui.button(this,"Photo access",this::requestPhotos));body.addView(Ui.button(this,"Check now",() -> {app().photos.kick(null);android.widget.Toast.makeText(this,"Checking new photos",android.widget.Toast.LENGTH_SHORT).show();}));}
        line();boolean notifications=getSharedPreferences("local_policy",MODE_PRIVATE).getBoolean("import_notifications",false);
        boolean permitted=android.os.Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        body.addView(Ui.actionRow(this,Icons.Symbol.DOCUMENT,"Import alerts",notifications?(permitted?"On · Documents saved":"On · Notification permission needed"):"Off · Documents saved",0xFFF7E1A3,() -> new AlertDialog.Builder(this).setTitle("Import notifications")
                .setMessage("Get a notification when Stack saves a document. Document names stay hidden on the lock screen.").setNegativeButton("Cancel",null).setPositiveButton(notifications?"Turn off":"Turn on",(d,w) -> {getSharedPreferences("local_policy",MODE_PRIVATE).edit().putBoolean("import_notifications",!notifications).commit();if(!notifications&&Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);refresh();}).show()));
        line();boolean locked=app().access.enabled();
        body.addView(Ui.actionRow(this,Icons.Symbol.DOCUMENTS,"App lock",locked?"On · Unlock when you return":"Off · Android screen lock",0xFFDCEADB,() -> app().access.change(this,!app().access.enabled(),this::refresh)));
        Button about=Ui.button(this,"About these settings",() -> new AlertDialog.Builder(this).setTitle("About these settings")
                .setMessage("Photo import checks new Camera photos for documents. Existing photos stay untouched. Android controls timing and which photos Stack can access. You can import missed photos yourself.\n\nApp lock uses Android security and hides Stack in Recents. It does not encrypt backups or copies you have shared.")
                .setPositiveButton("Got it",null).show());
        LinearLayout.LayoutParams help=new LinearLayout.LayoutParams(-2,-2);help.topMargin=Ui.dp(this,16);help.leftMargin=-Ui.dp(this,16);body.addView(about,help);
    }
    private void line(){body.addView(Ui.divider(this),new LinearLayout.LayoutParams(-1,Ui.dp(this,1)));}
    private void photoOptions(){boolean enabled=app().photos.enabled();new AlertDialog.Builder(this).setTitle("Photo import")
            .setMessage(enabled?"Stop adding documents from new camera photos? Your saved documents will stay in Stack.":"Automatically add likely documents from new Camera photos. Existing photos are not imported. You choose which photos Stack can access.")
            .setNegativeButton("Cancel",null).setPositiveButton(enabled?"Turn off":"Choose photo access",(d,w) -> {if(enabled){app().photos.disable();refresh();}else requestPhotos();}).show();}
    private void requestPhotos(){enablingPhotos=true;if(Build.VERSION.SDK_INT>=34)requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED},PHOTOS);else requestPermissions(new String[]{Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE},PHOTOS);}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==PHOTOS&&enablingPhotos){enablingPhotos=false;if(app().photos.permission())app().photos.enable();}app().photos.configure();refresh();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("enabling_photos",enablingPhotos);super.onSaveInstanceState(state);}
}
