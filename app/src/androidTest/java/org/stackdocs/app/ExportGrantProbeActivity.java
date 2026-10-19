package org.stackdocs.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.InputStream;
import java.security.MessageDigest;

/** Separate test-APK UID. Uses platform classes only; never links the target app's repository. */
public final class ExportGrantProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result=new Intent(); Uri uri=getIntent().getData();
        result.putExtra("uid",android.os.Process.myUid());
        try(InputStream input=getContentResolver().openInputStream(uri)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");int bytes=0,count;byte[] buffer=new byte[4096];
            while((count=input.read(buffer))!=-1){bytes+=count;digest.update(buffer,0,count);}
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            result.putExtra("status","read");result.putExtra("bytes",bytes);result.putExtra("sha256",hex.toString());
            boolean readOnly=false;
            try(ParcelFileDescriptor ignored=getContentResolver().openFileDescriptor(uri,"w")){}
            catch(SecurityException | java.io.FileNotFoundException expected){readOnly=true;}
            result.putExtra("read_only",readOnly);
        }catch(SecurityException denied){result.putExtra("status","denied");}
        catch(Exception unavailable){result.putExtra("status","unavailable");}
        setResult(RESULT_OK,result);finish();
    }
}
