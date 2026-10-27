package com.heavendoor.ultralab;
import android.content.Context;
import android.content.pm.*;
import android.os.*;
import dalvik.system.PathClassLoader;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;

/** Fixed read-only getters plus the signed app-owned instrument projection. No vehicle controls. */
public final class UltraDataService extends IUltraDataService.Stub {
 private static final String PKG="com.heavendoor.opultra.carplay",SIGNER="5202f52cb75f7073bb06fe90cd13cc5151a6240bda9ed7e7a6f96352f9935da5";
 private final int clientUid;
 private UltraProjectionRunner projection;
 private UltraOutputRouter outputs;
 private UltraTripReader tripReader;
 private final Object lock=new Object();
 private final Map<String,JSONObject> values=new LinkedHashMap<>();
 private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ultra-car-read");t.setDaemon(true);return t;});
 private volatile boolean connected,closed;
 private volatile String error="等待车辆接口";
 private Object target;private Class<?> api;
 private static final class Field {
  final String name,method;final int wheel,period;long due;boolean disabled;Method getter;
  Field(String n,String m,int w,int p){name=n;method=m;wheel=w;period=p;}
 }
 private final Field[] FIELDS={
  new Field("speed_raw","getVcuRawCarSpeed",0,100),
  new Field("gear","getVcuGearState",0,100),
  new Field("soc","getVcuElectricQuantityPercent",0,1000),
  new Field("range_mode","getVcuEnduranceMileageMode",0,1000),
  new Field("range_nedc","getVcuNedcAvalibleDrivingDistanceFloat",0,1000),
  new Field("range_wltp","getVcuWltpAvailableDrivingDistanceFloat",0,1000),
  new Field("range_cltc","getVcuCltcAvailableDrivingDistanceFloat",0,1000),
  new Field("trip_since_charge_raw","getLastChargeMileage",0,2000),
  new Field("mileage_total_raw","getDriveTotalMileage",0,5000),
  new Field("trip_a_raw","getMeterMileageA",0,2000),
  new Field("trip_b_raw","getMeterMileageB",0,2000),
  new Field("trip_since_start_raw","getLastStartUpMileage",0,2000),
  new Field("tpms_fl_raw","getTpmsTirePressureValue",1,2000),
  new Field("tpms_fr_raw","getTpmsTirePressureValue",2,2000),
  new Field("tpms_rl_raw","getTpmsTirePressureValue",3,2000),
  new Field("tpms_rr_raw","getTpmsTirePressureValue",4,2000),
  new Field("tpms_sensor_status","getTpmsAllSensorStatus",0,2000),
  new Field("tpms_pressure_warnings","getTpmsAllTirePressureWarnings",0,2000),
  new Field("outside_temperature_raw","getHvacExternalTemp",0,2000),
  new Field("charging_state_raw","getVcuChargeStatus",0,1000),
  new Field("charge_discharge_power_raw","getVcuChargeDischargePower",0,2000),
  new Field("battery_current_raw","getBmsBatteryCurrent",0,2000),
  new Field("doors_raw","getBcmDoorsState",0,1000),
  new Field("bonnet_raw","getBcmBonnetStatus",0,1000),
  new Field("trunk_opener_raw","getBcmTrunkOpennerStatus",0,1000),
  new Field("average_consumption_raw","getVcuAvgVehiclePowerConsume",0,2000),
  new Field("tpms_temperature_raw","getTpmsAllTireTemperature",0,2000),
  new Field("battery_voltage_raw","getBmsBatteryTotalVolt",0,2000)
 };

 public UltraDataService(Context context)throws Exception{
  if(!PKG.equals(context.getPackageName()))throw new SecurityException();
  clientUid=context.getApplicationInfo().uid;
  if(android.os.Process.myUid()!=0 && android.os.Process.myUid()!=2000)throw new SecurityException();
  PackageInfo p=context.getPackageManager().getPackageInfo(PKG,PackageManager.GET_SIGNING_CERTIFICATES);
  android.content.pm.Signature[] signs=p.signingInfo.getApkContentsSigners();
  if(signs.length!=1)throw new SecurityException();
  StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(signs[0].toByteArray()))hash.append(String.format("%02x",b&255));
  if(!SIGNER.equals(hash.toString()))throw new SecurityException();
  if(Build.VERSION.SDK_INT!=28 || !"E28".equals(Build.MODEL)){error="仅支持老P7 E28 / Android 9，当前只能模拟";return;}
  projection=new UltraProjectionRunner(context,clientUid);
  outputs=new UltraOutputRouter(context,projection);
  tripReader=new UltraTripReader(context);
  io.scheduleWithFixedDelay(this::tick,0,50,TimeUnit.MILLISECONDS);
 }
 private void connect()throws Exception{
  ClassLoader loader=new PathClassLoader("/system/framework/android.car.jar",getClass().getClassLoader());
  IBinder car=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"car_service");
  if(car==null || !"android.car.ICar".equals(car.getInterfaceDescriptor()))throw new IllegalStateException("car_service unavailable");
  Object c=loader.loadClass("android.car.ICar$Stub").getMethod("asInterface",IBinder.class).invoke(null,car);
  IBinder vehicle=(IBinder)loader.loadClass("android.car.ICar").getMethod("getCarService",String.class).invoke(c,"xp_vehicle");
  api=loader.loadClass("android.car.hardware.XpVehicle.IXpVehicle");
  if(vehicle==null || !api.getName().equals(vehicle.getInterfaceDescriptor()))throw new IllegalStateException();
  target=loader.loadClass(api.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,vehicle);
  vehicle.linkToDeath(()->{connected=false;error="车辆服务断开";synchronized(lock){values.clear();}},0);
  for(Field f:FIELDS){try{f.getter=f.wheel==0?api.getMethod(f.method):api.getMethod(f.method,int.class);}catch(NoSuchMethodException e){f.getter=null;}}
  connected=true;error="";
 }
 private void tick(){
  if(closed)return;
  try{
   if(!connected){connect();for(Field f:FIELDS){f.due=0;f.disabled=false;}}
   long now=SystemClock.elapsedRealtime();
   for(Field f:FIELDS){
    if(closed || f.disabled || now<f.due)continue;
    long start=SystemClock.elapsedRealtime();JSONObject row=new JSONObject();
    try{
     if(f.getter==null)throw new NoSuchMethodException();
     Object x=f.wheel==0?f.getter.invoke(target):f.getter.invoke(target,f.wheel);
     row.put("raw",numeric(x)).put("state","returned");
    }catch(Throwable t){String status=kind(t);row.put("state",status);f.disabled=status.equals("permission_denied") || status.equals("api_unavailable") || status.equals("property_not_supported");}
    long done=SystemClock.elapsedRealtime();row.put("readAtMs",done).put("latencyMs",done-start);
    synchronized(lock){values.put(f.name,row);}f.due=done+f.period;
   }
  }catch(Throwable t){connected=false;error=kind(t);synchronized(lock){values.clear();}SystemClock.sleep(1500);}
 }
 private static Object numeric(Object x)throws Exception{
  if(x instanceof Number){if(!Double.isFinite(((Number)x).doubleValue()))throw new IllegalArgumentException();return x;}
  if(x!=null && x.getClass().isArray() && Array.getLength(x)<=8){JSONArray a=new JSONArray();for(int i=0;i<Array.getLength(x);i++)a.put(numeric(Array.get(x,i)));return a;}
  throw new IllegalArgumentException();
 }
 private static String kind(Throwable t){
  while(t instanceof InvocationTargetException && t.getCause()!=null)t=t.getCause();
  if(t instanceof SecurityException)return "permission_denied";
  if(t instanceof ClassNotFoundException || t instanceof NoSuchMethodException)return "api_unavailable";
  if(t instanceof RemoteException)return "binder_error";
  String s=t.getMessage();
  if("Failed to get the value due to the property is unsupported".equals(s))return "property_not_supported";
  if("Failed to get the value due to the ECU not sending related data".equals(s))return "property_no_data";
  if("Failed to get the value due to the signal lost".equals(s))return "property_signal_lost";
  if("Failed to get the value due to the the vehicle hal is dead".equals(s))return "property_hal_dead";
  return "read_failed";
 }
 @Override public String snapshot(){
  if(Binder.getCallingUid()!=clientUid&&Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException();
  try{
   long now=SystemClock.elapsedRealtime();JSONObject fields=new JSONObject();
   synchronized(lock){for(Map.Entry<String,JSONObject> n:values.entrySet()){JSONObject row=new JSONObject();JSONObject cached=n.getValue();for(java.util.Iterator<String> keys=cached.keys();keys.hasNext();){String k=keys.next();row.put(k,cached.get(k));}row.put("ageMs",Math.max(0,now-row.getLong("readAtMs")));fields.put(n.getKey(),row);}}
   if(tripReader!=null){JSONObject startup=fields.optJSONObject("trip_since_start_raw");double km=startup!=null&&"returned".equals(startup.optString("state"))&&startup.optLong("ageMs",-1)>=0&&startup.optLong("ageMs",3501)<=3500?startup.optDouble("raw",Double.NaN):Double.NaN;fields.put("trip_duration_seconds_raw",tripReader.snapshot(km,now));}
   return new JSONObject().put("schema",1).put("source","vehicle").put("uid",android.os.Process.myUid()).put("connected",connected && !closed).put("error",error).put("fields",fields).put("projection",outputs==null?"unsupported":outputs.status()).put("projectionTiming",projection==null?new JSONObject():projection.diagnostics()).toString();
  }catch(Exception e){return "{\"schema\":1,\"source\":\"vehicle\",\"connected\":false,\"error\":\"snapshot_failed\"}";}
 }
 @Override public void setProjectionActive(boolean active){if(Binder.getCallingUid()!=clientUid&&Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException();if(outputs!=null)outputs.active(active);}
 @Override public void setProjectionMode(int mode){if(Binder.getCallingUid()!=clientUid&&Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException();if(outputs!=null)outputs.mode(mode);}
 void shutdown(){closed=true;if(outputs!=null)outputs.close();if(projection!=null)projection.close();if(tripReader!=null)tripReader.close();io.shutdownNow();}
 public void restartProjection(){if(Binder.getCallingUid()!=clientUid&&Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException();if(outputs!=null)outputs.restart();}
 @Override public void destroy(){int uid=Binder.getCallingUid();if(uid!=clientUid && uid!=android.os.Process.myUid() && uid!=0)throw new SecurityException();shutdown();System.exit(0);}
}
