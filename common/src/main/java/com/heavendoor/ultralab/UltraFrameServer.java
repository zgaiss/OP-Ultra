package com.heavendoor.ultralab;

import android.graphics.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** In-memory instrument canvas only. Shell/root peer required, never a screen recorder. */
final class UltraFrameServer implements AutoCloseable {
 static final int MAGIC=0x4f505531,W=960,H=360;
 private final UltraDashboardView view;private final BooleanSupplier enabled;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final Object pixelLock=new Object();
 private volatile long rendered,retries,renderMaxMs,cachedRenders,encodedBytes,rawBytes,encodeNanos,drawNanos,convertNanos,lockNanos,writeNanos;
 private volatile String state="starting";
 String status(){return state;}
 org.json.JSONObject diagnostics(){try{return new org.json.JSONObject().put("rendered",rendered).put("transientRetries",retries).put("maxRenderMs",renderMaxMs).put("mapBuffers",UltraMapBuffers.allocated()).put("cachedRenders",cachedRenders).put("encodedBytes",encodedBytes).put("rawBytes",rawBytes).put("encodeNanos",encodeNanos).put("drawNanos",drawNanos).put("convertNanos",convertNanos).put("lockNanos",lockNanos).put("writeNanos",writeNanos).put("composition","argb8888-single-rgb565").put("transport","lz4-tiles-v1").put("captureIntervalMs",UltraPerformanceBudget.captureIntervalMs());}catch(Exception e){return new org.json.JSONObject();}}
 private volatile boolean closed;private volatile LocalServerSocket server;private final java.util.Set<LocalSocket> peers=java.util.concurrent.ConcurrentHashMap.newKeySet();
 private final Semaphore frames=new Semaphore(1),limit=new Semaphore(3);
 private Bitmap image,imageHD,compositeHD;private Canvas canvasSD,canvasHD,outputHD;private final Paint outputPaint=new Paint(Paint.DITHER_FLAG);private ByteBuffer pixels,pixelsHD;private int sequence;private long hdRevision=-1,hdCapturedAt;
 UltraFrameServer(UltraDashboardView v,BooleanSupplier e){view=v;enabled=e;Thread t=new Thread(this::serve,"ultra-frame-export");t.setDaemon(true);t.start();}
 private void serve(){
  for(int attempt=0;attempt<10&&!closed;attempt++){
   try(LocalServerSocket s=new LocalServerSocket("op.ultra.carplay.frames.v1")){
    server=s;if(closed)return;state="listening";
    while(!closed){LocalSocket c=s.accept();if(closed){shutdown(c);break;}if(!limit.tryAcquire()){shutdown(c);continue;}peers.add(c);Thread t=new Thread(()->handle(c),"ultra-frame-peer");t.setDaemon(true);t.start();}
    return;
   }catch(Exception ignored){if(closed)return;state="binding_retry";SystemClock.sleep(150);}
   finally{server=null;}
  }
  if(!closed)state="bind_failed";
 }
 private static void shutdown(LocalSocket c){try{android.system.Os.shutdown(c.getFileDescriptor(),android.system.OsConstants.SHUT_RDWR);}catch(Exception ignored){}try{c.close();}catch(Exception ignored){}}
 private void handle(LocalSocket c){boolean frameOwner=false;java.util.zip.Deflater deflater=null;byte[] compressed=null;UltraTileCodec tiles=null;
  try(LocalSocket socket=c){int uid=c.getPeerCredentials().getUid();if(uid!=0&&uid!=2000)return;c.setSoTimeout(3500);
    DataInputStream in=new DataInputStream(c.getInputStream());DataOutputStream out=new DataOutputStream(c.getOutputStream());if(in.readInt()!=MAGIC)return;out.writeInt(MAGIC);out.flush();
    long last=0;UltraFrameHealth health=new UltraFrameHealth();UltraPerformanceBudget exportBudget=new UltraPerformanceBudget();int productionInterval=100;
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
    while(!closed){int op=in.readInt();if(op==0)break;if(op==1||op==4||op==5){
     final boolean hd=op!=1;int interval=180,forceKey=0;
     if(op==5){interval=in.readInt();forceKey=in.readInt();if(interval<100||interval>400||forceKey<0||forceKey>1)break;interval=Math.max(interval,productionInterval);UltraPerformanceBudget.requestedInterval(interval);if(tiles==null)tiles=new UltraTileCodec();if(forceKey==1)tiles.reset();}
     if(!frameOwner){if(!frames.tryAcquire())break;frameOwner=true;}
     long delay=interval-(SystemClock.elapsedRealtime()-last);if(delay>0)SystemClock.sleep(delay);last=SystemClock.elapsedRealtime();
     // A reverse-camera transition may occupy the UI looper. The bounded software
     // canvas runs on this single frame-owner thread, not in a posted UI task.
     byte[] b=null;long capturedAt=SystemClock.elapsedRealtime(),begin=capturedAt;
     long lockAt=System.nanoTime();synchronized(pixelLock){synchronized(view){lockNanos+=System.nanoTime()-lockAt;
      if(!closed&&enabled.getAsBoolean()){
       Bitmap target;ByteBuffer data;Canvas canvas;
       if(hd){if(imageHD==null){imageHD=Bitmap.createBitmap(1920,720,Bitmap.Config.RGB_565);compositeHD=Bitmap.createBitmap(1920,720,Bitmap.Config.ARGB_8888);pixelsHD=ByteBuffer.allocate(2764800).order(ByteOrder.LITTLE_ENDIAN);canvasHD=new Canvas(compositeHD);outputHD=new Canvas(imageHD);}target=imageHD;data=pixelsHD;canvas=canvasHD;}
       else{if(image==null){image=Bitmap.createBitmap(W,H,Bitmap.Config.RGB_565);pixels=ByteBuffer.allocate(W*H*2).order(ByteOrder.LITTLE_ENDIAN);canvasSD=new Canvas(image);canvasSD.scale(.5f,.5f);}target=image;data=pixels;canvas=canvasSD;}
       long revision=view.captureRevision();
       boolean reuse=hd&&revision==hdRevision&&!view.captureAnimating()&&capturedAt-hdCapturedAt<150;
       if(!reuse){long phase=System.nanoTime();view.drawDashboard(canvas);drawNanos+=System.nanoTime()-phase;phase=System.nanoTime();if(hd)outputHD.drawBitmap(compositeHD,0,0,outputPaint);data.clear();target.copyPixelsToBuffer(data);convertNanos+=System.nanoTime()-phase;capturedAt=SystemClock.elapsedRealtime();if(hd){hdRevision=revision;hdCapturedAt=capturedAt;}rendered++;}
       else{capturedAt=hdCapturedAt;cachedRenders++;}
       if(enabled.getAsBoolean())b=data.array();
      }
     }}
     long elapsed=SystemClock.elapsedRealtime()-begin;renderMaxMs=Math.max(renderMaxMs,elapsed);
     int n=b==null?0:b.length,frameId=b==null?0:++sequence;
     if(op==5&&b!=null){n=tiles.encode(b,frameId,SystemClock.elapsedRealtime());b=tiles.packet();encodedBytes=tiles.encodedBytes();rawBytes=tiles.rawBytes();encodeNanos=tiles.encodeNanos();}
     else if(hd&&b!=null){if(deflater==null){deflater=new java.util.zip.Deflater(1);compressed=new byte[1048576];}deflater.reset();deflater.setInput(b);deflater.finish();n=deflater.deflate(compressed);if(!deflater.finished()){b=null;n=0;}else b=compressed;}
     if(op==5){productionInterval=exportBudget.observeReceiver((int)Math.min(4500,Math.max(0,SystemClock.elapsedRealtime()-begin)));UltraPerformanceBudget.requestedInterval(Math.max(interval,productionInterval));}
     int code;if(b==null){code=health.unavailable(SystemClock.elapsedRealtime(),view.themeTransitionDeadline());retries++;if(tiles!=null)tiles.reset();}else{health.available();code=frameId;}
     long writeAt=System.nanoTime();out.writeInt(code);out.writeLong(capturedAt);out.writeInt(b==null?0:hd?1920:W);out.writeInt(b==null?0:hd?720:H);out.writeInt(n);if(b!=null)out.write(b,0,n);out.flush();writeNanos+=System.nanoTime()-writeAt;if(code==UltraFrameHealth.STOP)break;
    }else if(op==2){
     int size=in.readInt();if(size<4||size>1048576)break;byte[] raw=new byte[size];in.readFully(raw);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(raw,0,size,o);if(o.outWidth<64||o.outHeight<64||o.outWidth>2048||o.outHeight>2048||o.outWidth*(long)o.outHeight>4194304)break;
     o.inJustDecodeBounds=false;Bitmap map=BitmapFactory.decodeByteArray(raw,0,size,o);if(map==null)break;long seq=SystemClock.elapsedRealtime();main.post(()->{if(closed)map.recycle();else view.map(map,seq);});out.writeInt(1);out.flush();
    }else break;}
  }catch(Exception ignored){}finally{if(deflater!=null)deflater.end();if(frameOwner)frames.release();peers.remove(c);limit.release();}
 }
 @Override public void close(){closed=true;state="closed";for(LocalSocket c:peers)shutdown(c);LocalServerSocket owned=server;if(owned!=null){try{android.system.Os.shutdown(owned.getFileDescriptor(),android.system.OsConstants.SHUT_RDWR);}catch(Exception ignored){}try{owned.close();}catch(Exception ignored){}}synchronized(pixelLock){if(image!=null){image.recycle();image=null;pixels=null;}if(imageHD!=null){imageHD.recycle();imageHD=null;pixelsHD=null;}if(compositeHD!=null){compositeHD.recycle();compositeHD=null;canvasHD=null;outputHD=null;}}}
}
