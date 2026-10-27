package com.heavendoor.ultralab;
import android.graphics.*;import android.os.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
/** Latest-cover-only decoder, off the UI/data threads, with bounded bytes and decoded pixels. */
final class UltraCoverLoader implements AutoCloseable {
 private static final class Request {final long key,version;final byte[] data;Request(long k,long v,byte[] d){key=k;version=v;data=d;}}
 private final UltraDashboardView view;private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ultra-cover");t.setDaemon(true);return t;});
 private final AtomicReference<Request> pending=new AtomicReference<>();private final AtomicBoolean busy=new AtomicBoolean();
 private volatile boolean closed;private volatile long version;private long key=Long.MIN_VALUE;
 UltraCoverLoader(UltraDashboardView v){view=v;}
 void submit(long nextKey,byte[] data){if(closed||key==nextKey)return;key=nextKey;long v=++version;view.cover(null);pending.set(new Request(nextKey,v,data));schedule();}
 private synchronized void schedule(){if(!closed&&busy.compareAndSet(false,true))worker.execute(()->{try{Request q;while(!closed&&(q=pending.getAndSet(null))!=null){Bitmap image=decode(q.data);long v=q.version;main.post(()->{if(closed||version!=v){if(image!=null)image.recycle();}else view.cover(image);});}}finally{busy.set(false);if(!closed&&pending.get()!=null)schedule();}});}
 private static Bitmap decode(byte[] data){if(data==null||data.length<4||data.length>4194304)return null;try{BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,o);if(o.outWidth<1||o.outHeight<1||o.outWidth>4096||o.outHeight>4096||(long)o.outWidth*o.outHeight>16777216)return null;int sample=1;while(Math.max(o.outWidth,o.outHeight)/sample>512)sample*=2;o.inSampleSize=sample;o.inJustDecodeBounds=false;o.inPreferredConfig=Bitmap.Config.ARGB_8888;return BitmapFactory.decodeByteArray(data,0,data.length,o);}catch(RuntimeException ignored){return null;}}
 void clear(){if(closed)return;version++;key=Long.MIN_VALUE;pending.set(null);view.cover(null);}
 public synchronized void close(){closed=true;version++;pending.set(null);worker.shutdownNow();view.cover(null);}
}
