package org.stackdocs.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.widget.*;

/** Device-local opt-ins, never derived from restore or upgrade. */
public final class SettingsActivity extends LibraryActivity {
    private static final int PHOTOS=71,NOTIFICATIONS=72;
    private LinearLayout body,partnerBox;private boolean enablingPhotos;private String partnerNotice="";
    @Override public void onCreate(Bundle state){super.onCreate(state);if(state!=null)enablingPhotos=state.getBoolean("enabling_photos");LinearLayout root=Ui.column(this),bar=Ui.toolbar(this);bar.addView(Ui.iconButton(this,Icons.Symbol.BACK,"Back",this::finish),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));bar.addView(Ui.text(this,"Settings",18,false),new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);ScrollView scroll=new ScrollView(this);body=Ui.column(this);body.setPadding(Ui.dp(this,24),Ui.dp(this,12),Ui.dp(this,24),Ui.dp(this,24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));Ui.install(this,root);}
    @Override protected void onResume(){super.onResume();app().photos.configure();refresh();}
    @Override protected void onLibraryChanged(){refresh();}
    private void refresh(){
        if(body==null)return;body.removeAllViews();
        body.addView(Ui.actionRow(this,Icons.Symbol.DOCUMENTS,"Sync","Connect your computer and sync automatically",0xFFDCECF9,()->startActivity(new Intent(this,SyncActivity.class))));line();
        body.addView(Ui.actionRow(this,Icons.Symbol.DOCUMENTS,"Folders","Filen folders Stack reads",0xFFDCEADB,()->startActivity(new Intent(this,FoldersActivity.class))));line();
        partnerBox=new LinearLayout(this);partnerBox.setOrientation(LinearLayout.VERTICAL);body.addView(partnerBox);loadPartner();
        boolean photos=app().photos.enabled();
        body.addView(Ui.actionRow(this,Icons.Symbol.PHOTO,"Photo import",photos?(app().photos.permission()?"On · New camera photos":"Paused · Photo access needed"):"Off · New camera photos only",0xFFDCECF9,this::photoOptions));
        if(photos){body.addView(Ui.button(this,"Photo access",this::requestPhotos));body.addView(Ui.button(this,"Check now",() -> {app().photos.kick(null);android.widget.Toast.makeText(this,"Checking new photos",android.widget.Toast.LENGTH_SHORT).show();}));}
        line();boolean notifications=getSharedPreferences("local_policy",MODE_PRIVATE).getBoolean("import_notifications",false);
        boolean permitted=android.os.Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        body.addView(Ui.actionRow(this,Icons.Symbol.DOCUMENT,"Import alerts",notifications?(permitted?"On · Documents saved":"On · Notification permission needed"):"Off · Documents saved",0xFFF7E1A3,() -> new AlertDialog.Builder(this).setTitle("Import notifications")
                .setMessage("Get a notification when Stack saves a document. Document names stay hidden on the lock screen.").setNegativeButton("Cancel",null).setPositiveButton(notifications?"Turn off":"Turn on",(d,w) -> {getSharedPreferences("local_policy",MODE_PRIVATE).edit().putBoolean("import_notifications",!notifications).commit();if(!notifications&&Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);refresh();}).show()));
        line();boolean locked=app().access.enabled();
        body.addView(Ui.actionRow(this,Icons.Symbol.DOCUMENTS,"App lock",locked?"On · Unlock when you return":"Off · Android screen lock",0xFFDCEADB,() -> app().access.change(this,!app().access.enabled(),this::refresh)));
        line();body.addView(Ui.actionRow(this,Icons.Symbol.CHECK,"Update check",UpdateCheck.status(this),0xFFF7E1A3,this::updateCheck));
        line();body.addView(Ui.actionRow(this,Icons.Symbol.DOCUMENTS,"Companion apps",companionStatus(),0xFFDCECF9,()->startActivity(new Intent(this,CompanionsActivity.class))));
        Button about=Ui.button(this,"About these settings",() -> new AlertDialog.Builder(this).setTitle("About these settings")
                .setMessage("Photo import checks new Camera photos for documents. Existing photos stay untouched. Android controls timing and which photos Stack can access. You can import missed photos yourself.\n\nApp lock uses Android security and hides Stack in Recents. It does not encrypt backups or copies you have shared.")
                .setPositiveButton("Got it",null).show());
        LinearLayout.LayoutParams help=new LinearLayout.LayoutParams(-2,-2);help.topMargin=Ui.dp(this,16);help.leftMargin=-Ui.dp(this,16);body.addView(about,help);
    }

    /** The Partner row: drawn at once from what Stack knows, then again when Filen answers (partner_status). */
    private int partnerGeneration;
    private FilenPartner.Status statusRemembered(){FilenPartner.Status status=FilenPartner.api(this).status();FilenPartner.remember(status);return status;}
    private void loadPartner(){
        final LinearLayout box=partnerBox;
        final int mine=++partnerGeneration;
        read(() -> app().library.sources.partnerName(),name -> {
            if(box!=partnerBox||mine!=partnerGeneration)return;
            check(() -> {int level=FilenPartner.level(this);return new Object[]{level,FilenPartner.supported(level)&&name==null?statusRemembered():null};},answer -> {
                if(box!=partnerBox||mine!=partnerGeneration)return;
                int level=(Integer)answer[0];
                if(!FilenPartner.supported(level)){showPartnerGate(level);return;}
                showPartner(name,(FilenPartner.Status)answer[1]);
            });
        });
    }
    /** Filen Files is missing or too old (F12): one row, with the button that fixes it. Folder search is not affected. */
    private void showPartnerGate(int level){
        partnerBox.removeAllViews();
        partnerBox.addView(Ui.actionRow(this,Icons.Symbol.SHARE,"Partner",FilenPartner.gateText(level),0xFFF9E8DC,()->FilenPartner.openGate(this,level)));
        partnerBox.addView(Ui.button(this,FilenPartner.gateButton(level),()->FilenPartner.openGate(this,level)));
        partnerBox.addView(Ui.divider(this),new LinearLayout.LayoutParams(-1,Ui.dp(this,1)));
    }
    private void showPartner(String name,FilenPartner.Status status){
        partnerBox.removeAllViews();
        String[] row=PartnerSetup.settingsRow(name,status);
        partnerBox.addView(Ui.actionRow(this,Icons.Symbol.SHARE,row[0],row[1],0xFFF9E8DC,()->partnerTapped(name)));
        if(!partnerNotice.isEmpty()){TextView note=Ui.text(this,partnerNotice,14,false);note.setTextColor(0xFFB3261E);partnerBox.addView(note);}
        partnerBox.addView(Ui.divider(this),new LinearLayout.LayoutParams(-1,Ui.dp(this,1)));
    }
    private void partnerTapped(String name){
        partnerNotice="";
        if(name==null){String[] problem={""};if(!PartnerSetup.start(this,problem)){partnerNotice=problem[0];loadPartner();}return;}
        new AlertDialog.Builder(this).setTitle("Sharing with "+name)
                .setMessage("Stack shows "+name+"'s shared files in search. Nothing is deleted in Filen.")
                .setPositiveButton("Close",null)
                .setNegativeButton("Stop showing "+name+"'s files",(d,w) -> work(() -> {for(SourceStore.Source source:app().library.sources.list())if(SourceStore.PARTNER.equals(source.kind))app().library.sources.remove(source.id);return true;},ok -> loadPartner())).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PartnerSetup.REQUEST)PartnerSetup.finish(this,result,data,problem -> {partnerNotice=problem;loadPartner();});
    }
    private void updateCheck(){
        if(!UpdateCheck.enabled(this)){
            new AlertDialog.Builder(this).setTitle("Look for new versions?")
                    .setMessage("Stack can ask github.com once a day whether a newer version is available. The request carries no documents or identifiers; GitHub sees your network address, as any website does.\n\nStack tells you and offers the update. Android asks you before anything is installed.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Turn on",(d,w) -> {UpdateCheck.setEnabled(this,true);UpdateCheck.checkNow(app(),null);refresh();}).show();
            return;
        }
        boolean available=UpdateCheck.available(this)||(!UpdateCheck.latest(this).isEmpty()&&UpdateCheck.newer(UpdateCheck.latest(this),UpdateCheck.installed(this)));
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Update check").setMessage(UpdateCheck.status(this)+"\n\nYou have version "+UpdateCheck.installed(this)+".")
                .setNeutralButton("Turn off",(d,w) -> {UpdateCheck.setEnabled(this,false);refresh();}).setNegativeButton("Close",null);
        if(available&&!UpdateCheck.download(this).isEmpty())dialog.setPositiveButton("Update",(d,w) -> AppUpdater.start(this));
        else dialog.setPositiveButton("Check now",(d,w) -> {UpdateCheck.checkNow(app(),null);Toast.makeText(this,"Checking for updates",Toast.LENGTH_SHORT).show();});
        dialog.show();
    }
    private String companionStatus(){String n=Companions.notice(this);return n==null?"Filen Files and Ami Pro · installed and up to date":n;}
    private void line(){body.addView(Ui.divider(this),new LinearLayout.LayoutParams(-1,Ui.dp(this,1)));}
    private void photoOptions(){boolean enabled=app().photos.enabled();new AlertDialog.Builder(this).setTitle("Photo import")
            .setMessage(enabled?"Stop adding documents from new camera photos? Your saved documents will stay in Stack.":"Automatically add likely documents from new Camera photos. Existing photos are not imported. You choose which photos Stack can access.")
            .setNegativeButton("Cancel",null).setPositiveButton(enabled?"Turn off":"Choose photo access",(d,w) -> {if(enabled){app().photos.disable();refresh();}else requestPhotos();}).show();}
    private void requestPhotos(){enablingPhotos=true;if(Build.VERSION.SDK_INT>=34)requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED},PHOTOS);else requestPermissions(new String[]{Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE},PHOTOS);}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==PHOTOS&&enablingPhotos){enablingPhotos=false;if(app().photos.permission())app().photos.enable();}app().photos.configure();refresh();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("enabling_photos",enablingPhotos);super.onSaveInstanceState(state);}
}
