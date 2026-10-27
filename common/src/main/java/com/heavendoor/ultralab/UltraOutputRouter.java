package com.heavendoor.ultralab;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.util.concurrent.TimeUnit;

/** Serial ownership handoff between the wide canvas and the OEM map-card writer. */
final class UltraOutputRouter implements AutoCloseable {
 private final Context context;private final UltraProjectionRunner wide;
 private volatile int desired=UltraOutputMode.ULTRA;private volatile boolean active,closed,restart;
 private volatile String state="off";private Process legacy;
 private final Thread worker;
 private static final File JOURNAL=new File("/data/local/tmp/op-ultra-native-lease-v1");
 UltraOutputRouter(Context c,UltraProjectionRunner p){context=c;wide=p;worker=new Thread(this::loop,"ultra-output-mode");worker.setDaemon(true);worker.start();}
 void mode(int n){desired=UltraOutputMode.checked(n);}
 void active(boolean value){active=value;}
 void restart(){restart=true;}
 String status(){return desired==UltraOutputMode.ULTRA?wide.status():state;}
 private void loop(){int current=UltraOutputMode.OFF;
  while(!closed||current!=UltraOutputMode.OFF){
   int next=closed||!active?UltraOutputMode.OFF:desired;
   try{
    if(current==next&&!restart){if(current==UltraOutputMode.MAP&&legacy!=null&&!legacy.isAlive()){legacy=null;current=UltraOutputMode.OFF;state="map_retry";SystemClock.sleep(1500);}else SystemClock.sleep(100);continue;}
    restart=false;
    state="restoring_previous_output";
    wide.active(false);
    if(!wide.awaitInactive(8500)){state="waiting_wide_restore";SystemClock.sleep(500);continue;}
    if(legacy!=null){legacy.destroy();if(!legacy.waitFor(3500,TimeUnit.MILLISECONDS)){legacy.destroyForcibly();legacy.waitFor(1500,TimeUnit.MILLISECONDS);}legacy=null;}
    if(JOURNAL.exists()){state="waiting_native_map_restore";SystemClock.sleep(500);continue;}
    current=UltraOutputMode.OFF;
    if(next!=(closed||!active?UltraOutputMode.OFF:desired))continue;
    if(next==UltraOutputMode.ULTRA){wide.active(true);current=next;state="ultra";}
    else if(next==UltraOutputMode.MAP){
     if(!wide.originalLayerReady()){state="waiting_original_layer";SystemClock.sleep(500);continue;}
     ProcessBuilder b=new ProcessBuilder("/system/bin/app_process","/system/bin","--nice-name=op-ultra-map-only","com.shilapi.xcertplay.P7InstrumentWorker");
     b.environment().put("CLASSPATH",context.getApplicationInfo().sourceDir+":/system/framework/android.car.jar");
     b.redirectInput(new File("/dev/null"));b.redirectOutput(new File("/dev/null"));b.redirectError(new File("/dev/null"));legacy=b.start();current=next;state="native_map_only";
    }else state="off";
   }catch(Exception e){state="output_handoff_retry";SystemClock.sleep(1000);}
  }
 }
 @Override public void close(){closed=true;active=false;}
}
