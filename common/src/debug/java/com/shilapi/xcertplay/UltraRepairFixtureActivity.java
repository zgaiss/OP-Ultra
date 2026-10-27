package com.shilapi.xcertplay;
import android.app.Activity;import android.os.*;import android.graphics.*;
import com.heavendoor.ultralab.*;import java.io.*;import java.lang.reflect.*;
import java.util.function.BooleanSupplier;import org.json.*;

/** DEBUG ONLY: real abstract socket and bitmap lease checks; no vehicle interfaces. */
public final class UltraRepairFixtureActivity extends Activity {
 private Object server;
 private File file(String n){return new File(getFilesDir(),"repair-"+n);}
 private void write(String n,String v){try(FileOutputStream f=new FileOutputStream(file(n))){f.write(v.getBytes("UTF-8"));}catch(Exception e){throw new RuntimeException(e);}}
 @Override public void onCreate(Bundle b){super.onCreate(b);try{
  if("service-start".equals(getIntent().getStringExtra("mode"))){UltraService.launch(this);write("restart-ready","1");finish();return;}
  if("service-stop".equals(getIntent().getStringExtra("mode"))){UltraService.halt(this);write("restart-stopped","1");finish();return;}
  for(String n:new String[]{"ready","unavailable","block","blocked","unblocked"})file(n).delete();
  JSONObject checks=new JSONObject();UltraMapBuffers.clear();UltraMapBuffers.begin();
  Bitmap a=UltraMapBuffers.acquire(1920,720),c=UltraMapBuffers.acquire(1920,720),d=UltraMapBuffers.acquire(1920,720);
  if(a==null||c==null||d==null||UltraMapBuffers.acquire(1920,720)!=null)throw new AssertionError("pool bound");
  checks.put("three_leases_bound",true);UltraMapBuffers.release(a);
  for(int i=0;i<100;i++){Bitmap reused=UltraMapBuffers.acquire(1920,720);if(reused!=a)throw new AssertionError("reallocated map");UltraMapBuffers.release(reused);}
  checks.put("hundred_frames_reuse_same_bitmap",true);UltraMapBuffers.clear();
  if(!a.isRecycled()||c.isRecycled()||d.isRecycled())throw new AssertionError("lease disposed early");
  UltraMapBuffers.release(c);UltraMapBuffers.release(d);
  if(!c.isRecycled()||!d.isRecycled()||UltraMapBuffers.allocated()!=0)throw new AssertionError("retired lease retained");
  checks.put("clear_waits_for_outstanding_leases",true);if(UltraMapBuffers.acquire(1920,720)!=null)throw new AssertionError("late capture after stop");checks.put("stop_rejects_late_capture",true);
  UltraDashboardView view=new UltraDashboardView(this);Bitmap map=Bitmap.createBitmap(1920,720,Bitmap.Config.RGB_565);map.eraseColor(0xff344f5a);view.map(map,1);
  Bitmap duplicate=Bitmap.createBitmap(1920,720,Bitmap.Config.RGB_565);
  Field stamp=UltraDashboardView.class.getDeclaredField("mapReceived");stamp.setAccessible(true);stamp.setLong(view,SystemClock.elapsedRealtime()-3000);view.map(duplicate,1);
  if(!view.display().getBoolean("mapConnected")||!duplicate.isRecycled()||map.isRecycled())throw new AssertionError("duplicate liveness");
  checks.put("duplicate_refreshes_liveness_and_releases_copy",true);
  Class<?> type=Class.forName("com.heavendoor.ultralab.UltraFrameServer");Constructor<?> ctor=type.getDeclaredConstructor(UltraDashboardView.class,BooleanSupplier.class);ctor.setAccessible(true);
  java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
  server=ctor.newInstance(view,(BooleanSupplier)()->{int n=requests.incrementAndGet();if(n==1)new Handler(Looper.getMainLooper()).post(()->{write("blocked",Long.toString(SystemClock.elapsedRealtime()));SystemClock.sleep(1100);write("unblocked",Long.toString(SystemClock.elapsedRealtime()));});return getIntent().getBooleanExtra("expire",false)?n==1:n<=4||(n>=7&&n<=12);});
  write("checks.json",checks.put("allPassed",true).toString());
  Method status=type.getDeclaredMethod("status");status.setAccessible(true);new Thread(()->{try{long end=SystemClock.elapsedRealtime()+3000;while(!"listening".equals(status.invoke(server))&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);if(!"listening".equals(status.invoke(server)))throw new AssertionError("bind");write("ready","1");}catch(Exception e){write("failed",e.toString());}},"debug-wait-listen").start();

 }catch(Throwable e){write("checks.json","{\"allPassed\":false,\"error\":\""+e.getClass().getSimpleName()+"\"}");throw new RuntimeException(e);}}
}
