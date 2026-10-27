package com.shilapi.xcertplay;
import android.app.Activity;import android.os.*;import android.graphics.*;import com.heavendoor.ultralab.*;import org.json.*;import java.io.*;import java.util.concurrent.atomic.AtomicBoolean;import java.util.function.BooleanSupplier;
/** Debug-only real Android transport producer. Synthetic vehicle/map; no car or root service access. */
public final class UltraPerfFixtureActivity extends Activity {
 private final Handler timer=new Handler(Looper.getMainLooper());private final AtomicBoolean available=new AtomicBoolean(true);private AutoCloseable server;private UltraDashboardView view;private Bitmap map;private long began;
 private void write(String name,String value){try(FileOutputStream f=new FileOutputStream(new File(getFilesDir(),name))){f.write(value.getBytes("UTF-8"));}catch(Exception e){throw new RuntimeException(e);}}
 private void ackChecks()throws Exception{
  java.lang.reflect.Method ack=Class.forName("com.heavendoor.ultralab.UltraProjectionRunner").getDeclaredMethod("ack",DataInputStream.class,int.class,UltraPerformanceBudget.class,long.class);ack.setAccessible(true);
  for(int test=0;test<5;test++){
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream wire=new DataOutputStream(bytes);wire.writeInt(Integer.reverseBytes(test==1?8:7));wire.writeInt(Integer.reverseBytes(test==2?4501:10));
   DataInputStream input=new DataInputStream(new ByteArrayInputStream(test==4?new byte[0]:bytes.toByteArray()));
   try{int interval=(Integer)ack.invoke(null,input,7,new UltraPerformanceBudget(),SystemClock.elapsedRealtime()-(test==3?5000:200));if(test!=0||interval<250||interval>400)throw new AssertionError("ack validation "+test);}
   catch(java.lang.reflect.InvocationTargetException e){if(test==0||!(e.getCause() instanceof IOException))throw e;}
  }
  write("ack-checks.json","{\"cases\":5,\"allPassed\":true,\"scope\":\"real Android ACK parser and network-backpressure timing\"}");
 }
 @Override public void onCreate(Bundle b){super.onCreate(b);try{
  ackChecks();
  view=new UltraDashboardView(this);map=Bitmap.createBitmap(1920,720,Bitmap.Config.RGB_565);Canvas c=new Canvas(map);Paint p=new Paint();p.setShader(new LinearGradient(0,0,1920,720,0xff285e94,0xff132c35,Shader.TileMode.CLAMP));c.drawRect(0,0,1920,720,p);view.map(map,1);view.layout(0,0,1920,720);setContentView(view);
  Class<?> cls=Class.forName("com.heavendoor.ultralab.UltraFrameServer");java.lang.reflect.Constructor<?> ctor=cls.getDeclaredConstructor(UltraDashboardView.class,BooleanSupplier.class);ctor.setAccessible(true);server=(AutoCloseable)ctor.newInstance(view,(BooleanSupplier)()->available.get());began=SystemClock.elapsedRealtime();
  Runnable samples=new Runnable(){public void run(){try{
   long elapsed=SystemClock.elapsedRealtime()-began;JSONObject fields=new JSONObject();for(String k:new String[]{"speed_raw","gear","soc","range_wltp","range_mode"})fields.put(k,new JSONObject().put("state","returned").put("raw",k.equals("gear")?4:k.equals("soc")?91:k.equals("range_wltp")?528:k.equals("range_mode")?1:0).put("ageMs",0));
   view.accept(new JSONObject().put("schema",1).put("source","vehicle").put("uid",2000).put("connected",true).put("fields",fields).put("syntheticFixture",true));view.map(map,1);
   available.set(!(elapsed>=2400&&elapsed<2750)&&elapsed<6500);
   if(elapsed<9000)timer.postDelayed(this,80);
  }catch(Exception e){write("perf-fixture-error",e.toString());}}};timer.post(samples);write("perf-fixture-ready","SYNTHETIC_LOCAL_ONLY");
 }catch(Exception e){write("perf-fixture-error",e.toString());}}
 @Override public void onDestroy(){timer.removeCallbacksAndMessages(null);try{if(server!=null)server.close();}catch(Exception ignored){}if(view!=null)view.release();super.onDestroy();}
}
