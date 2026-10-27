package com.heavendoor.ultralab;

import android.graphics.Bitmap;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/** Three explicit bitmap leases: renderer, pending delivery, and current map.
 * A map is returned only after both the UI and offscreen canvas release it.
 */
public final class UltraMapBuffers {
 private static final IdentityHashMap<Bitmap,Integer> owned=new IdentityHashMap<>();
 private static final ArrayDeque<Bitmap> free=new ArrayDeque<>();
 private static int generation;private static boolean accepting;
 private UltraMapBuffers(){}
 public static synchronized void begin(){accepting=true;}
 public static synchronized Bitmap acquire(int w,int h){
  if(!accepting)return null;
  while(!free.isEmpty()){
   Bitmap b=free.removeFirst();
   if(!b.isRecycled()&&b.getWidth()==w&&b.getHeight()==h)return b;
   owned.remove(b);if(!b.isRecycled())b.recycle();
  }
  if(owned.size()>=3)return null;
  // Keep decoded pixels in the compositor's native 32-bit format. Quantizing
  // every map copy and again at each translucent layer wastes CPU and precision.
  Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);owned.put(b,generation);return b;
 }
 public static synchronized void release(Bitmap b){
  if(b==null)return;
  Integer owner=owned.get(b);
  if(owner!=null&&owner==generation&&!b.isRecycled()){
   if(!free.contains(b))free.addLast(b);
  }else{owned.remove(b);if(!b.isRecycled())b.recycle();}
 }
 public static synchronized void clear(){
  accepting=false;generation++;
  while(!free.isEmpty()){Bitmap b=free.removeFirst();owned.remove(b);if(!b.isRecycled())b.recycle();}
 }
 public static synchronized int allocated(){return owned.size();}
}
