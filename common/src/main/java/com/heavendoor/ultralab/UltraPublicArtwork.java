// SPDX-License-Identifier: GPL-3.0-or-later
package com.heavendoor.ultralab;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Original, startup-only artwork for the public build; no extracted OEM assets. */
final class UltraPublicArtwork {
 private UltraPublicArtwork() {}
 static Bitmap create(String name) {
  boolean dark=name.endsWith("-night.png");
  int ink=dark?0xffe4e9ed:0xff26343d;
  if(name.startsWith("digit-")) {
   int d=name.charAt(6)-'0';
   return d>=0&&d<=9?digit(d,ink):null;
  }
  if(name.startsWith("vehicle-body-"))return vehicle(ink,dark);
  // The body drawing includes its door outlines. No artificial live-door overlay.
  if(name.startsWith("vehicle-front-")||name.startsWith("vehicle-rear-"))return null;
  if(name.startsWith("powerframe-"))return power(0,ink,dark);
  if(name.startsWith("powerempty-"))return power(1,ink,dark);
  if(name.startsWith("powerfull-"))return power(2,ink,dark);
  return null;
 }
 private static Paint paint(int color) {
  Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  p.setColor(color);return p;
 }
 private static Bitmap digit(int value,int color) {
  Bitmap b=Bitmap.createBitmap(72,90,Bitmap.Config.ARGB_8888);
  Canvas c=new Canvas(b);Paint p=paint(color);
  p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(100);
  String text=Integer.toString(value);Rect bounds=new Rect();
  p.getTextBounds(text,0,text.length(),bounds);
  // Retain the existing speed-ink bottom for the energy alignment calculation.
  float scale=Math.min(64f/Math.max(1,bounds.width()),78f/Math.max(1,bounds.height()));
  c.translate((72-bounds.width()*scale)/2f,80);
  c.scale(scale,scale);c.drawText(text,-bounds.left,-bounds.bottom,p);
  return b;
 }
 private static Bitmap vehicle(int ink,boolean dark) {
  Bitmap b=Bitmap.createBitmap(460,374,Bitmap.Config.ARGB_8888);
  Canvas c=new Canvas(b);Paint p=paint(dark?0xff283943:0xffb4c4ce);
  RectF body=new RectF(147,8,313,366);c.drawRoundRect(body,62,62,p);
  p.setColor(ink);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);
  c.drawRoundRect(body,62,62,p);
  p.setStyle(Paint.Style.FILL);p.setColor(dark?0xff111d25:0xff77929f);
  c.drawRoundRect(new RectF(164,65,296,283),35,35,p);
  p.setColor(dark?0xff78909c:0xffd9e6ec);
  Path wind=new Path();wind.moveTo(176,65);wind.lineTo(284,65);wind.lineTo(294,112);wind.lineTo(166,112);wind.close();c.drawPath(wind,p);
  c.drawRoundRect(new RectF(176,250,284,282),9,9,p);
  p.setColor(ink);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);
  c.drawLine(152,178,166,178,p);c.drawLine(294,178,308,178,p);
  c.drawLine(164,116,164,246,p);c.drawLine(296,116,296,246,p);
  p.setStyle(Paint.Style.FILL);
  for(int x:new int[]{134,308})for(int y:new int[]{80,265})c.drawRoundRect(new RectF(x,y,x+18,y+46),5,5,p);
  p.setColor(dark?0xffc3ded2:0xff597b70);
  c.drawRoundRect(new RectF(170,25,200,32),3,3,p);c.drawRoundRect(new RectF(260,25,290,32),3,3,p);
  return b;
 }
 private static Bitmap power(int kind,int ink,boolean dark) {
  Bitmap b=Bitmap.createBitmap(33,337,Bitmap.Config.ARGB_8888);
  Canvas c=new Canvas(b);Paint p=paint(kind==2?(dark?0xffa9d5c6:0xff356d62):(dark?0xff273843:0xffbfced7));
  RectF r=new RectF(3,3,30,334);
  if(kind==0){p.setColor(ink);p.setAlpha(100);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);c.drawRoundRect(r,10,10,p);}
  else c.drawRoundRect(r,9,9,p);
  return b;
 }
}
