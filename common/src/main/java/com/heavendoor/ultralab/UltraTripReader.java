package com.heavendoor.ultralab;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Normal instrument D-Bus events, selected by trip kind, on independent IO.
 * Every subscription ends after its first startup event or a parent deadline.
 * It never attaches to, stops or writes the original instrument process. */
final class UltraTripReader implements AutoCloseable {
 private static final String ROOT="/tmp/op-ultra-trip-v1/";
 private static final String[] FILES={"ultra_trip_monitor","ultra_trip_extract.awk"};
 private static final String[] HASHES={"58a59bd40e1f44964251defe829bcb2aa84974f19f7128198e6a514f1fc3d0fb","4f24365068f129198a2d525344b8f20023bbdc682f7d3242cb0e345018ca896a"};
 private final Context context;private final UltraInstrumentRpc rpc=new UltraInstrumentRpc();private final UltraStartupTrip.Cache cache=new UltraStartupTrip.Cache();
 private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ultra-trip-read");t.setDaemon(true);return t;});
 private volatile boolean closed;private boolean deployed;private volatile String state="waiting_for_startup_trip";private long nextAttemptAt;private int failures;
 UltraTripReader(Context c){context=c;io.scheduleWithFixedDelay(this::read,0,1200,TimeUnit.MILLISECONDS);}
 private void read(){if(closed||SystemClock.elapsedRealtime()<nextAttemptAt)return;try{
  deploy();if(closed)return;
  // A single final printf also avoids split management replies from pipelines.
  String command="p=$(pidof ECluster); case \"$p\" in ''|*[!0-9]*) exit 1;; esac; export DBUS_SESSION_BUS_ADDRESS=$(tr '\\000' '\\n' < /proc/$p/environ | sed -n 's/^DBUS_SESSION_BUS_ADDRESS=//p'); [ -n \"$DBUS_SESSION_BUS_ADDRESS\" ] || exit 1; boot=$(cat /proc/sys/kernel/random/boot_id); v=$("+ROOT+FILES[0]+" 2>/dev/null | awk -f "+ROOT+FILES[1]+"); printf 'OPTRIP3 %s %s %s\\n' \"$boot\" \"$p\" \"$v\"";
  String result=rpc.call(command);UltraStartupTrip sample=UltraStartupTrip.parse(result);if(closed)return;
  if(sample!=null){cache.accept(sample,SystemClock.elapsedRealtime());state="returned";failures=0;nextAttemptAt=0;}
  else if(UltraStartupTrip.normalEmptySubscription(result)){
   // A bounded 1.8-second subscription can land between kind-0 events.
   // This is not an IO/asset failure: retain the bounded cache and the normal
   // cadence instead of adding 2/4/8-second backoffs and redeploying the reader.
   state="waiting_for_startup_event";failures=0;nextAttemptAt=0;
  }else{state="startup_event_unavailable";backoff();}
 }catch(Exception e){state="instrument_trip_unavailable";backoff();}}
 private void backoff(){failures=Math.min(3,failures+1);if(failures>=3)deployed=false;nextAttemptAt=SystemClock.elapsedRealtime()+(1000L<<failures);}
 private void deploy()throws Exception{
  if(deployed)return;rpc.call("umask 077; mkdir -p "+ROOT+"; chmod 700 "+ROOT);
  for(int f=0;f<FILES.length;f++){
   byte[] bytes;try(InputStream in=context.getAssets().open("ultra/"+FILES[f])){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))>0){out.write(b,0,n);if(out.size()>4096)throw new IOException("trip_asset_bounds");}bytes=out.toByteArray();}
   StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))digest.append(String.format(Locale.ROOT,"%02x",b&255));if(!HASHES[f].equals(digest.toString()))throw new SecurityException("trip_asset_hash");
   String path=ROOT+FILES[f];byte[] existing=null;try{existing=Base64.decode(rpc.call("if [ -f "+path+" ]; then base64 "+path+"; fi"),Base64.DEFAULT);}catch(IllegalArgumentException ignored){}
   if(!Arrays.equals(bytes,existing)){
    String b64=Base64.encodeToString(bytes,Base64.NO_WRAP);rpc.call(": > "+path+".b64");
    for(int i=0;i<b64.length();i+=900){if(closed)throw new InterruptedIOException();rpc.call("printf '%s' '"+b64.substring(i,Math.min(i+900,b64.length()))+"' >> "+path+".b64");}
    rpc.call("base64 -d "+path+".b64 > "+path+".next; chmod 500 "+path+".next; mv -f "+path+".next "+path+"; rm -f "+path+".b64");
    if(!Arrays.equals(bytes,Base64.decode(rpc.call("base64 "+path),Base64.DEFAULT)))throw new IOException("trip_asset_readback");
   }
  }deployed=true;
 }
 JSONObject snapshot(double sdkStartupKm,long now)throws Exception{
  synchronized(cache){
  UltraStartupTrip sample=cache.matching(sdkStartupKm,now);JSONObject out=new JSONObject().put("state",sample==null?("returned".equals(state)?"waiting_for_matching_trip":state):"returned").put("provider","oem_dbus_trip_kind0").put("collectorState",state).put("cacheAgeMs",cache.age(now));
  if(sample!=null)out.put("raw",sample.minutes*60).put("unit","seconds").put("nativeMinutes",sample.minutes).put("sourceDistanceKm",sample.kilometers).put("sourceTimestampNanos",sample.sourceNanos).put("instrumentPid",sample.instrumentPid).put("instrumentBootId",sample.bootId).put("readAtMs",cache.readAt()).put("ageMs",now-cache.readAt());
  return out;
  }
 }
 @Override public void close(){closed=true;rpc.close();io.shutdownNow();}
}
