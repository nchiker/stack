package org.stackdocs.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.EnumMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated ephemeral camera scan. Frames never become library documents. */
public final class PairingQrActivity extends LibraryActivity {
    private CameraController camera;private SurfaceView preview;private TextView status;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService decoder=Executors.newSingleThreadExecutor();
    private boolean active,decoding;private int generation;private Runnable removeBack;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout root=Ui.column(this);LinearLayout toolbar=Ui.toolbar(this);toolbar.addView(Ui.iconButton(this,Icons.Symbol.BACK,"Back",this::finish));toolbar.addView(Ui.text(this,"Scan computer QR code",18,false));root.addView(toolbar);
        status=Ui.text(this,"Point the camera at the QR code in Stack on your computer.",16,false);status.setPadding(Ui.dp(this,24),Ui.dp(this,16),Ui.dp(this,24),Ui.dp(this,16));root.addView(status);
        preview=new SurfaceView(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));root.addView(Ui.button(this,"Retry camera",this::open));Ui.install(this,root);removeBack=Ui.backHandler(this,this::finish);
        camera=new CameraController(this,preview,new CameraController.Listener(){
            public void ready(boolean flash){capture();}
            public void photo(byte[] jpeg){decode(jpeg);}
            public void error(String message){status.setText(message+" You can also use Find my computer.");}
        });
        preview.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder holder){open();}public void surfaceChanged(SurfaceHolder holder,int f,int w,int h){}public void surfaceDestroyed(SurfaceHolder holder){camera.close();}});
    }
    @Override protected void onResume(){super.onResume();active=true;if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},91);else open();}
    @Override protected void onPause(){active=false;generation++;handler.removeCallbacksAndMessages(null);if(camera!=null)camera.close();super.onPause();}
    @Override protected void onDestroy(){decoder.shutdownNow();if(removeBack!=null)removeBack.run();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);if(code==91&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)open();else status.setText("Camera permission is needed to scan. You can go back and use Find my computer.");}
    private void open(){if(active&&camera!=null&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)whenAllowed(camera::open);}
    private void capture(){if(active&&!decoding)camera.capture();}
    private void decode(byte[] jpeg){if(decoding||!active)return;decoding=true;int ticket=generation;decoder.execute(()->{
        String result=null;try{BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>1600)sample*=2;options.inJustDecodeBounds=false;options.inSampleSize=sample;Bitmap bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);if(bitmap!=null)try{result=decodeBitmap(bitmap);NearbyPairing.qr(result);}finally{bitmap.recycle();}}catch(Exception ignored){}
        String found=result;handler.post(()->{decoding=false;if(!active||ticket!=generation)return;if(found!=null){try{NearbyPairing.qr(found);setResult(RESULT_OK,new Intent().putExtra("stack_pair_qr",found));finish();return;}catch(Exception ignored){status.setText("That QR code is not an active Stack connection code. Point at the code in Stack on your computer.");}}handler.postDelayed(this::capture,500);});
    });}
    static String decodeBitmap(Bitmap bitmap)throws Exception {int w=bitmap.getWidth(),h=bitmap.getHeight();int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);EnumMap<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,true);return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w,h,pixels))),hints).getText();}
}
