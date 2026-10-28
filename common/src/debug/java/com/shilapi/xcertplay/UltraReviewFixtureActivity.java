package com.shilapi.xcertplay;
import android.app.Activity;import android.content.*;import android.os.*;import android.graphics.*;
import com.heavendoor.ultralab.*;import java.lang.reflect.*;import java.io.*;import java.util.*;import org.json.*;

/** DEBUG ONLY. Ownership/gating regression and readout validity, without a vehicle. */
public final class UltraReviewFixtureActivity extends Activity {
 private final JSONObject checks=new JSONObject();private UltraEngine engine;
 private void check(String name,boolean ok)throws Exception{checks.put(name,ok);if(!ok)throw new AssertionError(name);}
 private Field f(String name)throws Exception{Field f=UltraEngine.class.getDeclaredField(name);f.setAccessible(true);return f;}
 private ServiceConnection connection(long epoch)throws Exception{Method m=UltraEngine.class.getDeclaredMethod("newConnection",long.class);m.setAccessible(true);return(ServiceConnection)m.invoke(engine,epoch);}
 private JSONObject row(Object value,long age)throws Exception{return new JSONObject().put("state","returned").put("raw",value).put("ageMs",age);}
 @Override public void onCreate(Bundle b){super.onCreate(b);try{
  getSharedPreferences("p7-instrument",0).edit().putBoolean("enabled",true).apply();engine=UltraEngine.get(this);engine.start();
  ServiceConnection old=connection(f("generation").getLong(engine));engine.stop();engine.start();
  IUltraDataService fake=new IUltraDataService.Stub(){public String snapshot(){return "{\"schema\":1,\"source\":\"vehicle\",\"uid\":2000,\"connected\":false}";}public void setProjectionActive(boolean v){}public void setProjectionMode(int mode){}public void setPopupTheme(boolean night){}public void restartProjection(){}public void destroy(){}};
  f("reader").set(engine,fake);old.onServiceConnected(new ComponentName(this,UltraDataService.class),fake.asBinder());
  new Handler(Looper.getMainLooper()).postDelayed(()->{try{
   check("late_old_bind_does_not_replace_reader",f("reader").get(engine)==fake);
   ServiceConnection current=connection(f("generation").getLong(engine));current.onServiceDisconnected(new ComponentName(this,UltraDataService.class));
   new Handler(Looper.getMainLooper()).postDelayed(()->{try{
    check("disconnect_retires_reader",f("reader").get(engine)==null);check("disconnect_enables_bounded_rebind",!f("bound").getBoolean(engine)&&f("nextBindAt").getLong(engine)>SystemClock.elapsedRealtime());
    engine.dashboard.map(Bitmap.createBitmap(64,64,Bitmap.Config.RGB_565),1);engine.setProjectionEnabled(false);
    check("disable_clears_map_immediately",!engine.dashboard.display().getBoolean("mapConnected"));
    Bitmap late=Bitmap.createBitmap(64,64,Bitmap.Config.RGB_565);engine.map(new UltraEngine.BitmapWrapper(late,2));check("disabled_rejects_late_map",late.isRecycled());
    check("disabled_gate_visible",!engine.projectionEnabled());engine.setProjectionEnabled(true);check("enable_restores_gate",engine.projectionEnabled());
    readings();checks.put("allPassed",true);finishResult();
   }catch(Throwable e){failure(e);}},90);
  }catch(Throwable e){failure(e);}},90);
 }catch(Throwable e){failure(e);}}
 private void readings()throws Exception{
  UltraDashboardView view=new UltraDashboardView(this);JSONObject fields=new JSONObject();fields.put("speed_raw",row(48,0)).put("gear",row(4,0)).put("soc",row(68,0)).put("range_wltp",row(397,0)).put("trip_since_start_raw",row(16.6,0)).put("trip_since_charge_raw",row(126.4,0)).put("mileage_total_raw",row(61332,5000)).put("average_consumption_raw",row(20.8,0)).put("charge_discharge_power_raw",row(-12,0)).put("outside_temperature_raw",row(-5,0)).put("tpms_fl_raw",row(230,0)).put("tpms_temperature_raw",row(new JSONArray("[29,30,31,30]"),0)).put("doors_raw",row(new JSONArray("[0,1,0,0]"),0)).put("bonnet_raw",row(0,0));
  JSONObject sample=new JSONObject().put("schema",1).put("source","vehicle").put("uid",2000).put("connected",true).put("fields",fields);view.accept(sample);Map<String,String> r=view.readings();
  check("known_trip_and_consumption_displayed",r.get("trip").equals("16.6 km")&&r.get("average").equals("20.8 kWh/100km"));check("slow_total_keeps_valid_value",r.get("total").equals("61332 km"));
  check("negative_hvac_temperature_supported",r.get("outside").equals("-5 °C"));check("signed_battery_power",r.get("power").equals("-12 kW"));check("tyre_units",r.get("tyre0").equals("2.3 bar · 29°C"));check("doors_independently_displayed",r.get("door0").equals("已关闭")&&r.get("door1").equals("未关闭"));check("missing_trip_minutes_not_invented",r.get("duration").equals("—"));
  view.navigation(new com.shilapi.xcertplay.iap2.message.RouteGuidanceSummary(true,"深圳大学",1080L,12600L,1793018400L,320L));r=view.readings();check("route_readout_units",r.get("distance").equals("12.6 km")&&r.get("remaining").equals("18 分钟")&&r.get("nextTurn").equals("320 m"));check("local_route_does_not_leak_to_diagnostics",r.get("destination").equals("深圳大学")&&!view.display().toString().contains("深圳大学"));
  sample.put("connected",false);view.accept(sample);r=view.readings();check("disconnect_clears_vehicle_readouts",r.get("speed").equals("—")&&r.get("soc").equals("—")&&r.get("tyre0").equals("— · —"));
  view.navigation(new com.shilapi.xcertplay.iap2.message.RouteGuidanceSummary());check("navigation_end_clears_local_destination",view.readings().get("destination").equals("未开始导航"));view.release();
 }
 private void failure(Throwable e){try{checks.put("allPassed",false).put("error",e.toString());finishResult();}catch(Exception ignored){}}
 private void finishResult(){if(engine!=null)engine.close();try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"review-fixture.json"))){out.write(checks.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){throw new RuntimeException(e);}}
}
