package org.stackdocs.app;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

/** One bounded 3,200-edge bitmap, fit-relative matrix; no bitmap accumulation while panning. */
final class ZoomView extends ImageView {
    private Bitmap bitmap;private String pageKey="";private float zoom=1,offsetX,offsetY,lastX,lastY;
    private final ScaleGestureDetector scale;private final GestureDetector gestures;
    ZoomView(Context context){super(context);setScaleType(ScaleType.MATRIX);setFocusable(true);
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){zoomAt(zoom*detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());return true;}});
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public boolean onDoubleTap(MotionEvent e){zoomAt(zoom>1?1:2,e.getX(),e.getY());performClick();return true;}});
    }
    void page(Bitmap value,String key){if(!key.equals(pageKey)){zoom=1;offsetX=offsetY=0;pageKey=key;}Bitmap old=bitmap;bitmap=value;setImageBitmap(value);matrix();if(old!=null&&old!=value)old.recycle();}
    private void zoomAt(float value,float x,float y){float next=Math.max(1,Math.min(4,value)),ratio=next/zoom;float w=getWidth()-getPaddingLeft()-getPaddingRight(),h=getHeight()-getPaddingTop()-getPaddingBottom();offsetX=ratio*offsetX+(1-ratio)*(x-getPaddingLeft()-w/2);offsetY=ratio*offsetY+(1-ratio)*(y-getPaddingTop()-h/2);zoom=next;if(zoom==1)offsetX=offsetY=0;matrix();}
    void changeZoom(float value){zoom=Math.max(1,Math.min(4,value));if(zoom==1)offsetX=offsetY=0;matrix();announceForAccessibility("Zoom "+Math.round(zoom*100)+" percent");}
    void reset(){changeZoom(1);}
    float zoom(){return zoom;}
    private void matrix(){if(bitmap==null||getWidth()==0||getHeight()==0)return;float w=getWidth()-getPaddingLeft()-getPaddingRight(),h=getHeight()-getPaddingTop()-getPaddingBottom();if(w<=0||h<=0)return;
        float fit=Math.min(w/bitmap.getWidth(),h/bitmap.getHeight()),factor=fit*zoom;
        float maxX=Math.max(0,(bitmap.getWidth()*factor-w)/2),maxY=Math.max(0,(bitmap.getHeight()*factor-h)/2);
        offsetX=Math.max(-maxX,Math.min(maxX,offsetX));offsetY=Math.max(-maxY,Math.min(maxY,offsetY));
        Matrix matrix=new Matrix();matrix.setScale(factor,factor);matrix.postTranslate((w-bitmap.getWidth()*factor)/2+offsetX,(h-bitmap.getHeight()*factor)/2+offsetY);setImageMatrix(matrix);
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);matrix();}
    @Override public void setPadding(int l,int t,int r,int b){super.setPadding(l,t,r,b);matrix();}
    @Override public boolean onTouchEvent(MotionEvent event){scale.onTouchEvent(event);gestures.onTouchEvent(event);switch(event.getActionMasked()){
        case MotionEvent.ACTION_DOWN:lastX=event.getX();lastY=event.getY();break;
        case MotionEvent.ACTION_MOVE:if(!scale.isInProgress()&&event.getPointerCount()==1){offsetX+=event.getX()-lastX;offsetY+=event.getY()-lastY;matrix();}lastX=event.getX();lastY=event.getY();break;
        case MotionEvent.ACTION_UP:performClick();break;
    }return true;}
    @Override public boolean performClick(){return super.performClick();}
    @Override public boolean onKeyDown(int key,android.view.KeyEvent e){int step=Ui.dp((android.app.Activity)getContext(),48);if(key==KeyEvent.KEYCODE_DPAD_LEFT)offsetX+=step;else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)offsetX-=step;else if(key==KeyEvent.KEYCODE_DPAD_UP)offsetY+=step;else if(key==KeyEvent.KEYCODE_DPAD_DOWN)offsetY-=step;else return super.onKeyDown(key,e);matrix();return true;}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01010001,"Zoom in"));info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01010002,"Zoom out"));info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01010003,"Reset zoom"));String[] names={"Pan left","Pan right","Pan up","Pan down"};for(int i=0;i<4;i++)info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01010004+i,names[i]));}
    @Override public boolean performAccessibilityAction(int action,Bundle args){switch(action){case 0x01010001:changeZoom(zoom*1.5f);return true;case 0x01010002:changeZoom(zoom/1.5f);return true;case 0x01010003:reset();return true;default:if(action>=0x01010004&&action<=0x01010007){return onKeyDown(new int[]{KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN}[action-0x01010004],null);}return super.performAccessibilityAction(action,args);}}
    void save(Bundle state){state.putString("zoom_page",pageKey);state.putFloat("zoom",zoom);state.putFloat("pan_x",offsetX);state.putFloat("pan_y",offsetY);}
    void restore(Bundle state){pageKey=state.getString("zoom_page","");zoom=state.getFloat("zoom",1);offsetX=state.getFloat("pan_x",0);offsetY=state.getFloat("pan_y",0);matrix();}
    void release(){setImageDrawable(null);if(bitmap!=null){bitmap.recycle();bitmap=null;}}
}
