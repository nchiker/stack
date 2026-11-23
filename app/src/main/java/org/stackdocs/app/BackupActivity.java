package org.stackdocs.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Local storage-access flows. Selecting/validating a backup never mutates the library. */
public final class BackupActivity extends LibraryActivity {
    private static final int BACKUP=71,RESTORE=72,AUTO_FOLDER=73,FILEN_FOLDER=74,KEY_SETUP=75,KEY_RESTORE=76;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=() -> {render();schedule();};
    private BackupScreen screen;
    private boolean plainLinks;
    private String pendingPassphrase;private android.net.Uri pendingRestore;
    private boolean resumed;
    private Runnable removeBack;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen=new BackupScreen(this,this::finish,this::backupClicked,this::restoreClicked,
                this::restoreConfirmed,() -> {app().archives.cancel();render();},this::aboutBackups);
        screen.automatic(AutoBackup.label(this),() -> whenAllowed(this::autoBackup));
        screen.keep(() -> whenAllowed(this::keepFor));
        screen.encryption(() -> whenAllowed(this::encryptionClicked));
        screen.reset(() -> whenAllowed(() -> startActivity(new Intent(this,LibraryResetActivity.class))));
        Ui.install(this,screen.root);removeBack=Ui.backHandler(this,this::finish);
        if(state!=null && state.getBoolean("active") && app().archives.state.equals("idle")) app().archives.message="The previous operation was interrupted. Existing data was kept; select the file or destination again. Startup recovery removes uncommitted restored files.";
        render();
    }
    @Override protected void onResume(){super.onResume();resumed=true;render();schedule();AutoBackup.verifyPendingAsync(app());}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);if(removeBack!=null)removeBack.run();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("active",app().archives.busy()||app().archives.state.equals("review"));super.onSaveInstanceState(state);}
    private void schedule(){if(resumed)handler.postDelayed(tick,300);}
    private void render() {
        ArchiveController controller=app().archives;
        screen.history(BackupHistory.label(this));
        screen.encryptionState(BackupKeys.configured(this));
        screen.automaticCaption(AutoBackup.label(this));
        screen.keepCaption(AutoBackup.enabled(this),AutoBackup.keepLabel(this));
        screen.render(controller.state,controller.progress.label,controller.message,controller.review(),controller.progress.canceled);
    }
    private void aboutBackups() {
        new AlertDialog.Builder(this).setTitle("About library backups")
                .setMessage("Your saved originals, page order and edits, names, notes, typed details, organization tags, stacks, recognized text and review choices are included. No account is needed.\n\nUnfinished scans and local consolidation recovery archives are excluded and stay on this device. Save an unfinished scan first to include it.\n\nLinked documents (their text and preview) are included when backup encryption is on, or when you agree to an unencrypted backup. Without encryption, backup files are not protected: choose a private location. Automatic Android cloud backup and device transfer are disabled.\n\nRestore adds documents and keeps your existing library. Selecting the same backup again imports another copy.\n\nIf saving fails or is canceled, your storage provider may leave an incomplete file. Delete that file and choose a destination again to retry.")
                .setPositiveButton("Got it",null).show();
    }
    private void autoBackup() {
        if(!AutoBackup.enabled(this)) {chooseDestination();return;}
        String error=AutoBackup.lastError(this);
        new AlertDialog.Builder(this).setTitle("Automatic backup")
                .setMessage(AutoBackup.label(this)+(error.isEmpty()?"":"\n\n"+error))
                .setPositiveButton("Back up now",(d,w) -> {AutoBackup.run(app(),null);render();})
                .setNeutralButton("Change folder",(d,w) -> {if(FilenFolder.available(this))chooseDestination();else pickFolder();})
                .setNegativeButton("Turn off",(d,w) -> {AutoBackup.disable(this);render();}).show();
    }
    /** Filen Drive in one tap when Filen Files offers it; otherwise the system folder picker. */
    private void chooseDestination() {
        String kept=AutoBackup.keepDays(this)<0?"kept forever":"kept for "+AutoBackup.keepLabel(this);
        if(FilenFolder.available(this)) {
            new AlertDialog.Builder(this).setTitle("Automatic backup")
                    .setMessage("A backup every week in Filen Drive, each "+kept+".")
                    .setPositiveButton("Use Filen Drive",(d,w) -> filenFolder())
                    .setNeutralButton("Other folder",(d,w) -> pickFolder())
                    .setNegativeButton("Cancel",null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Automatic backup")
                .setMessage("Choose a folder and Stack saves a new backup there about once a week, each "+kept+". The folder can be on this phone, a USB drive or a cloud app such as Filen Files or Google Drive.\n\n"+(BackupKeys.configured(this)?"Backups are encrypted.":"Stack does not encrypt backup files, so choose a private folder.")+"")
                .setNegativeButton("Cancel",null).setPositiveButton("Choose folder",(d,w) -> pickFolder()).show();
    }
    private void pickFolder() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{startActivityForResult(intent,AUTO_FOLDER);}catch(ActivityNotFoundException error){new AlertDialog.Builder(this).setMessage("No folder picker is available on this phone.").setPositiveButton("OK",null).show();}
    }
    /** One choice, applied at the next backup. The newest backup is never removed by this. */
    private void keepFor() {
        int current=0;for(int i=0;i<AutoBackup.KEEP_DAYS.length;i++)if(AutoBackup.KEEP_DAYS[i]==AutoBackup.keepDays(this))current=i;
        new AlertDialog.Builder(this).setTitle("Keep backups for")
                .setSingleChoiceItems(AutoBackup.KEEP_LABELS,current,(d,which) -> {AutoBackup.setKeepDays(this,AutoBackup.KEEP_DAYS[which]);d.dismiss();render();})
                .setNegativeButton("Cancel",null).show();
    }
    /** Asks Filen Files for its "Stack backups" folder: one Allow tap there, no browsing. */
    private void filenFolder() {
        try{if(!FilenFolder.available(this))throw new ActivityNotFoundException();startActivityForResult(FilenFolder.request(),FILEN_FOLDER);}catch(ActivityNotFoundException error){pickFolder();}
    }
    /** Keeps the folder permission (the provider may be slow, so off the UI thread), then makes the first backup. */
    private void folderChosen(Intent data) {
        android.net.Uri tree=data.getData();int flags=data.getFlags();
        new Thread(() -> {
            String name="";
            try(android.database.Cursor c=getContentResolver().query(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,android.provider.DocumentsContract.getTreeDocumentId(tree)),new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) {
                if(c!=null&&c.moveToFirst()&&!c.isNull(0))name=c.getString(0);
            } catch(RuntimeException ignored) {}
            String shown=name;
            try {
                AutoBackup.enable(this,tree,flags,shown);
                handler.post(() -> {AutoBackup.run(app(),null);render();});
            } catch(RuntimeException error) {
                handler.post(() -> new AlertDialog.Builder(this).setMessage("Stack could not keep access to that folder. Choose another folder.").setPositiveButton("OK",null).show());
            }
        },"Stack backup folder").start();
    }
    private void backupClicked() {
        plainLinks=false;
        if(BackupKeys.configured(this)){pick(true);return;}
        new Thread(() -> {
            int links;try{links=app().io.submit(() -> app().library.links.list("").size()).get();}catch(Exception error){links=0;}
            int count=links;handler.post(() -> {
                if(count==0){pick(true);return;}
                new AlertDialog.Builder(this).setTitle("Linked documents")
                        .setMessage("You have "+count+" linked "+(count==1?"document":"documents")+". Backups with encryption off leave them out, because their text and previews would be readable by anyone who gets the file.")
                        .setPositiveButton("Set up encryption",(d,w) -> whenAllowed(this::setupEncryption))
                        .setNeutralButton("Leave them out",(d,w) -> pick(true))
                        .setNegativeButton("Include, not encrypted",(d,w) -> {plainLinks=true;pick(true);}).show();
            });
        },"Stack link count").start();
    }
    private void restoreClicked() {pick(false);}
    /** A backup without encryption cannot be checked for tampering, so adding it asks first. */
    private void restoreConfirmed() {
        if(!app().archives.reviewUnencrypted()){app().archives.restore();render();return;}
        new AlertDialog.Builder(this).setTitle("Not encrypted")
                .setMessage("This backup is not encrypted, so Stack cannot tell whether someone changed it. Add it only if you made it and kept it private.")
                .setPositiveButton("Add it",(d,w) -> {app().archives.restore();render();}).setNegativeButton("Cancel",null).show();
    }
    /** Plain backups go straight to review; an encrypted one first asks for the passphrase or the recovery key. */
    private void restoreChosen(android.net.Uri uri) {
        new Thread(() -> {
            boolean encrypted=false;
            try(java.io.InputStream in=getContentResolver().openInputStream(uri)) {
                byte[] head=new byte[BackupEnvelope.MAGIC.length];int got=0;while(in!=null&&got<head.length){int n=in.read(head,got,head.length-got);if(n<0)break;got+=n;}
                encrypted=java.util.Arrays.equals(head,BackupEnvelope.MAGIC);
            } catch(java.io.IOException|RuntimeException ignored) {}
            boolean ask=encrypted;handler.post(() -> {if(ask)askSecret(uri,"");else {app().archives.validate(uri);render();}});
        },"Stack backup peek").start();
    }
    private void askSecret(android.net.Uri uri,String problem) {
        android.widget.EditText field=secret("Passphrase or recovery key");
        new SecureBuilder().setTitle("Encrypted backup").setMessage((problem.isEmpty()?"":problem+"\n\n")+"Enter the passphrase, or scan the recovery key: in Key Vault on your computer, open \"stack backup recovery key\" and choose Show QR code.")
                .setView(field).setNegativeButton("Cancel",null)
                .setNeutralButton("Scan key",(d,w) -> {pendingRestore=uri;scanKey(KEY_RESTORE);})
                .setPositiveButton("Open",(d,w) -> {
                    String text=field.getText().toString();String stripped=text.replaceAll("[\\s-]","");
                    byte[] recovery=null;if(stripped.matches("[0-9a-fA-F]{64}"))recovery=BackupEnvelope.parseRecoveryKey(stripped);
                    app().archives.validate(uri,recovery==null?text:null,recovery);render();
                }).show();
    }
    /** Dialogs in the key flows never appear in screenshots, recordings or the recents thumbnail (whatever the app-lock setting), and are skipped if the screen is gone. */
    private final class SecureBuilder extends AlertDialog.Builder {
        SecureBuilder(){super(BackupActivity.this);}
        @Override public AlertDialog show() {
            AlertDialog dialog=create();dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            if(!isFinishing()&&!isDestroyed())dialog.show();return dialog;
        }
    }
    private android.widget.EditText secret(String hint) {
        android.widget.EditText field=new android.widget.EditText(this);field.setHint(hint);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);return field;
    }
    private void encryptionClicked() {
        if(!BackupKeys.configured(this)){setupEncryption();return;}
        new SecureBuilder().setTitle("Backup encryption")
                .setMessage("Backups are encrypted. Older encrypted backups still need the passphrase or recovery key they were made with.")
                .setPositiveButton("Set up again",(d,w) -> setupEncryption())
                .setNeutralButton("Turn off",(d,w) -> new SecureBuilder().setMessage("New backups will not be encrypted and will leave out linked documents.")
                        .setPositiveButton("Turn off",(d2,w2) -> {if(app().archives.busy())return;BackupKeys.clear(this);render();}).setNegativeButton("Keep on",null).show())
                .setNegativeButton("Cancel",null).show();
    }
    /** The easy path: one scan of the key that the Key Vault app shows. Nothing to type. */
    private void setupEncryption() {
        new SecureBuilder().setTitle("Encrypt backups")
                .setMessage("In the Key Vault app on your computer, click \"Stack backup key (QR)\". Then tap Scan key and point the camera at the code. That is all: nothing to type.")
                .setPositiveButton("Scan key",(d,w) -> {pendingPassphrase=null;scanKey(KEY_SETUP);})
                .setNeutralButton("Other options",(d,w) -> setupWithPassphrase())
                .setNegativeButton("Cancel",null).show();
    }
    private void setupWithPassphrase() {
        android.widget.LinearLayout form=new android.widget.LinearLayout(this);form.setOrientation(android.widget.LinearLayout.VERTICAL);form.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),0);
        android.widget.EditText one=secret("Passphrase (at least "+BackupKeys.MIN_PASSPHRASE+" characters)"),two=secret("Passphrase again");form.addView(one);form.addView(two);
        new SecureBuilder().setTitle("Encrypt backups")
                .setMessage("Choose a long passphrase. Stack will then show a recovery key once. You need the passphrase or the recovery key to open any encrypted backup; if you lose both, the backup is gone.")
                .setView(form).setNegativeButton("Cancel",null)
                .setPositiveButton("Continue",(d,w) -> {
                    String a=one.getText().toString();
                    if(a.length()<BackupKeys.MIN_PASSPHRASE||!a.equals(two.getText().toString())){new SecureBuilder().setMessage("The passphrases must match and have at least "+BackupKeys.MIN_PASSPHRASE+" characters.").setPositiveButton("OK",null).show();return;}
                    pendingPassphrase=a;
                    new SecureBuilder().setTitle("Recovery key")
                            .setMessage("Stack needs a recovery key as a second way to open backups. Best: create it in Key Vault on your computer (it saves the key and shows a QR code), then scan the code here. Or let Stack make one now and show it to you once.")
                            .setPositiveButton("Scan key",(d2,w2) -> scanKey(KEY_SETUP))
                            .setNeutralButton("Make one here",(d2,w2) -> startKeys(a,null))
                            .setNegativeButton("Cancel setup",(d2,w2) -> pendingPassphrase=null).show();
                }).show();
    }
    /** First 12 hex characters of SHA-256 over the lowercase hex key text; keyvault prints the same under its QR code. */
    private static String fingerprint(String hex) {
        try{byte[] d=java.security.MessageDigest.getInstance("SHA-256").digest(hex.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII));StringBuilder out=new StringBuilder();for(int i=0;i<6;i++)out.append(String.format(Locale.ROOT,"%02x",d[i]&0xff));return out.toString();}
        catch(java.security.GeneralSecurityException impossible){return "";}
    }
    private void scanKey(int request) {
        try{startActivityForResult(new Intent(this,PairingQrActivity.class).putExtra("key_mode",true),request);}
        catch(ActivityNotFoundException error){new SecureBuilder().setMessage("The scanner is not available.").setPositiveButton("OK",null).show();}
    }
    /** Derives the keys off the UI thread; {@code scanned} is a Key Vault key, or null to make one here and show it once. */
    private void startKeys(String passphrase,byte[] scanned) {
        android.widget.Toast.makeText(this,"Preparing keys…",android.widget.Toast.LENGTH_SHORT).show();
        pendingPassphrase=null;pendingRestore=null;
        new Thread(() -> {
            try{
                BackupKeys.Pending pending=BackupKeys.begin(passphrase,scanned);
                if(scanned==null){handler.post(() -> showRecoveryKey(pending));return;}
                // The key came from Key Vault, which only shows it after it is sealed and copied: no confirm-back needed.
                String problem="";try{if(app().archives.busy())throw new IllegalStateException("A backup is running. Try again when it has finished.");BackupKeys.save(this,pending.slots);}catch(Exception error){problem=error.getMessage()==null?"unknown error":error.getMessage();}
                String shown=problem;handler.post(() -> {render();new SecureBuilder().setMessage(shown.isEmpty()?"Backup encryption is on. Check that this fingerprint matches the one Key Vault showed under the QR code: "+fingerprint(pending.recoveryHex)+"\n\nIf it differs, turn encryption off and set it up again. ":"Encryption could not be saved: "+shown).setPositiveButton("OK",null).show();});
            }
            catch(Exception error){handler.post(() -> new SecureBuilder().setMessage("Encryption could not be set up: "+error.getMessage()).setPositiveButton("OK",null).show());}
            finally{if(scanned!=null)java.util.Arrays.fill(scanned,(byte)0);}
        },"Stack backup keys").start();
    }
    private void showRecoveryKey(BackupKeys.Pending pending) {
        StringBuilder grouped=new StringBuilder();for(int i=0;i<64;i+=8){if(i>0)grouped.append(i==32?"\n":" ");grouped.append(pending.recoveryHex,i,i+8);}
        android.widget.TextView text=new android.widget.TextView(this);text.setText(grouped);text.setTypeface(android.graphics.Typeface.MONOSPACE);text.setTextSize(18);text.setPadding(Ui.dp(this,24),Ui.dp(this,8),Ui.dp(this,24),0);
        new SecureBuilder().setTitle("Your recovery key").setView(text).setCancelable(false)
                .setMessage("This is shown once. Save it somewhere safe, for example in Key Vault on your computer as \"stack backup recovery key\".")
                .setPositiveButton("I have saved it",(d,w) -> confirmRecoveryKey(pending))
                .setNegativeButton("Cancel setup",null).show();
    }
    private void confirmRecoveryKey(BackupKeys.Pending pending) {
        android.widget.EditText field=secret("First 6 and last 6 characters");
        new SecureBuilder().setTitle("Check the key").setView(field).setCancelable(false)
                .setMessage("Type the first 6 and the last 6 characters of the recovery key, with no spaces.")
                .setPositiveButton("Confirm",(d,w) -> {
                    String expected=pending.recoveryHex.substring(0,6)+pending.recoveryHex.substring(58);
                    if(!field.getText().toString().trim().toLowerCase(Locale.ROOT).equals(expected)){
                        new SecureBuilder().setMessage("That does not match. Check what you saved.").setPositiveButton("Show the key again",(d2,w2) -> showRecoveryKey(pending)).setNegativeButton("Cancel setup",null).show();return;
                    }
                    new Thread(() -> {
                        String problem="";try{if(app().archives.busy())throw new IllegalStateException("A backup is running. Try again when it has finished.");BackupKeys.save(this,pending.slots);}catch(Exception error){problem=error.getMessage()==null?"unknown error":error.getMessage();}
                        String shown=problem;handler.post(() -> {render();new SecureBuilder().setMessage(shown.isEmpty()?"Backup encryption is on. Do the recovery drill once: store the key, then check you can read it back before you trust backups.":"Encryption could not be saved: "+shown).setPositiveButton("OK",null).show();});
                    },"Stack backup keys").start();
                }).setNegativeButton("Cancel setup",null).show();
    }
    private void pick(boolean saving) {
        Intent intent=new Intent(saving?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream");
        if(saving) intent.putExtra(Intent.EXTRA_TITLE,"Stack-"+new SimpleDateFormat("yyyy-MM-dd-HHmmss",Locale.ROOT).format(new Date(Clock.now()))+".stackbackup");
        else intent.setType("*/*");
        try{startActivityForResult(intent,saving?BACKUP:RESTORE);}catch(ActivityNotFoundException error){new AlertDialog.Builder(this).setMessage("No document provider is available. Install or enable a local document picker, then retry.").setPositiveButton("OK",null).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==FILEN_FOLDER && (result!=RESULT_OK||data==null||data.getData()==null)) {
            String why=FilenFolder.reason(data);
            if(!why.isEmpty()) new AlertDialog.Builder(this).setTitle("Filen Drive").setMessage(why)
                    .setPositiveButton("OK",null).setNeutralButton("Other folder",(d,w) -> pickFolder()).show();
            return;
        }
        if(request==KEY_SETUP||request==KEY_RESTORE) {
            String hex=result==RESULT_OK&&data!=null?data.getStringExtra("stack_key"):null;
            String passphrase=pendingPassphrase;android.net.Uri uri=pendingRestore;pendingPassphrase=null;pendingRestore=null;
            if(hex==null||!hex.matches("[0-9a-fA-F]{64}"))return;
            byte[] key=BackupEnvelope.parseRecoveryKey(hex);
            if(request==KEY_SETUP){
                startKeys(passphrase,key);
            } else if(uri!=null){app().archives.validate(uri,null,key);render();}
            return;
        }
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==FILEN_FOLDER&&!FilenFolder.treeTrusted(this,data)){new AlertDialog.Builder(this).setTitle("Filen Drive").setMessage("Filen Files is not available. Choose the folder another way.").setPositiveButton("OK",null).setNeutralButton("Other folder",(d,w) -> pickFolder()).show();return;}
        if(request==AUTO_FOLDER||request==FILEN_FOLDER){whenAllowed(() -> folderChosen(data));return;}
        whenAllowed(() -> {if(request==BACKUP)app().archives.export(data.getData(),plainLinks);if(request==RESTORE)restoreChosen(data.getData());render();});
    }
}
