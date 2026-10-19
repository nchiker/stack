#!/usr/bin/env python3
"""Compare authored fixtures with production Java enhancement and flatten estimation.

Host pixel adapter only; Android decoding, Canvas/mesh rasterization and lifecycle
are excluded. Production Java bodies are copied unchanged and hashed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

BITMAP = '''package android.graphics;
public final class Bitmap {
 public enum Config { ARGB_8888 }
 private final int w,h; private final int[] p;
 private Bitmap(int[] p,int w,int h){this.p=p.clone();this.w=w;this.h=h;}
 public static Bitmap createBitmap(int[] p,int w,int h,Config c){return new Bitmap(p,w,h);}
 public static Bitmap createBitmap(int w,int h,Config c){return new Bitmap(new int[w*h],w,h);}
 public static Bitmap createScaledBitmap(Bitmap b,int w,int h,boolean filter){
  if(w==b.w&&h==b.h)return b;throw new UnsupportedOperationException("Host fixture forbids scaling");
 }
 public int getWidth(){return w;} public int getHeight(){return h;}
 public void recycle(){}
 public void getPixels(int[] out,int offset,int stride,int x,int y,int w,int h){
  for(int row=0;row<h;row++)System.arraycopy(p,(y+row)*this.w+x,out,offset+row*stride,w);
 }
}'''
HARNESS = '''package org.stackdocs.app;
import android.graphics.Bitmap;
import java.nio.file.*;
import javax.imageio.ImageIO;
public final class AppearanceHost {
 static Bitmap read(Path p)throws Exception {
  var b=ImageIO.read(p.toFile());int w=b.getWidth(),h=b.getHeight();
  return Bitmap.createBitmap(b.getRGB(0,0,w,h,null,0,w),w,h,Bitmap.Config.ARGB_8888);
 }
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]),out=Path.of(args[1]);
  Bitmap result=PageEnhancement.blackAndWhite(read(input.resolve("paper-input.png")));
  int[] pixels=new int[result.getWidth()*result.getHeight()];
  result.getPixels(pixels,0,result.getWidth(),0,0,result.getWidth(),result.getHeight());
  byte[] rgba=new byte[pixels.length*4];
  for(int i=0;i<pixels.length;i++){int p=pixels[i];rgba[4*i]=(byte)(p>>16);rgba[4*i+1]=(byte)(p>>8);rgba[4*i+2]=(byte)p;rgba[4*i+3]=(byte)(p>>24);}
  Files.write(out.resolve("paper-black-white.rgba"),rgba);
  for(String name:new String[]{"bowed","straight","slanted"}){
   PageFlattening.Model m=PageFlattening.estimate(read(input.resolve(name+"-input.png")));
   Files.writeString(out.resolve(name+"-model.txt"),m==null?"none":m.quadratic+" "+m.linear);
  }
 }
}'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    out = args.output.absolute()
    if not out.is_relative_to('/tmp'):
        parser.error('Use a fresh disposable /tmp output directory')
    out.mkdir(mode=0o700)
    desktop = Path(__file__).resolve().parents[1]
    production = desktop.parent / 'app/src/main/java/org/stackdocs/app'
    rust = out / 'rust'
    subprocess.run([desktop / 'target/debug/stack-cli', 'appearance-smoke', rust], check=True)
    sources = out / 'sources'
    graphics = sources / 'android/graphics'
    app = sources / 'org/stackdocs/app'
    graphics.mkdir(parents=True)
    app.mkdir(parents=True)
    (graphics / 'Bitmap.java').write_text(BITMAP)
    (graphics / 'Paint.java').write_text('package android.graphics; public class Paint { public static final int FILTER_BITMAP_FLAG=2; public Paint(int flags){} }')
    (graphics / 'Color.java').write_text('package android.graphics; public class Color { public static final int WHITE=-1; }')
    (graphics / 'Canvas.java').write_text('''package android.graphics; public class Canvas {
 public Canvas(Bitmap b){throw new UnsupportedOperationException("Canvas excluded from host evidence");}
 public void drawColor(int c){} public void drawBitmapMesh(Bitmap b,int w,int h,float[] v,int off,int[] c,int co,Paint p){}
}''')
    hashes = {}
    for name in ['PageEnhancement.java', 'PageFlattening.java']:
        source = production / name
        (app / name).write_bytes(source.read_bytes())
        hashes[name] = digest(source)
    (app / 'AppearanceHost.java').write_text(HARNESS)
    classes = out / 'classes'
    classes.mkdir()
    subprocess.run(['javac', '-d', classes, *sorted(sources.rglob('*.java'))], check=True)
    java = out / 'java'
    java.mkdir()
    subprocess.run(['java', '-cp', classes, 'org.stackdocs.app.AppearanceHost', rust, java], check=True)
    assert (rust / 'paper-black-white.rgba').read_bytes() == (java / 'paper-black-white.rgba').read_bytes(), 'Production Java enhancement differs'
    models = json.loads((rust / 'models.json').read_text())
    for label, model in models.items():
        actual = (java / f'{label}-model.txt').read_text()
        if model is None:
            assert actual == 'none', label
        else:
            quadratic, linear = map(float, actual.split())
            assert abs(quadratic - model['quadratic']) < 1e-6, label
            assert abs(linear - model['linear']) < 1e-6, label
    report = {'synthetic_only': True, 'fixture_version': 1,
              'production_java_sha256': hashes, 'black_white_rgba_equal': True,
              'black_white_rgba_sha256': digest(rust / 'paper-black-white.rgba'),
              'models': models, 'model_absolute_tolerance': 1e-6,
              'limits': ['host pixel adapter; authored fixtures only', 'no Android Canvas/Bitmap runtime',
                         'no resampling golden; no Android mesh rasterization proof']}
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS production Java enhancement pixels and flatten model decisions')


if __name__ == '__main__':
    main()
