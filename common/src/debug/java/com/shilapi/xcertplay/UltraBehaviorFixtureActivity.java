package com.shilapi.xcertplay;
import android.app.Activity;import android.os.*;import android.graphics.*;import com.heavendoor.ultralab.*;import org.json.*;import java.io.*;import java.lang.reflect.*;
/** DEBUG ONLY. Android runtime verification with explicit synthetic inputs, no car APIs. */
public final class UltraBehaviorFixtureActivity extends Activity {
 private final JSONObject checks=new JSONObject();
 private void check(String name,boolean ok)throws Exception{checks.put(name,ok);if(!ok)throw new AssertionError(name);}
 private JSONObject sample(int gear,long age,boolean connected)throws Exception{JSONObject f=new JSONObject();f.put("gear",new JSONObject().put("state","returned").put("raw",gear).put("ageMs",age));return new JSONObject().put("schema",1).put("source","vehicle").put("uid",2000).put("connected",connected).put("fields",f).put("syntheticFixture",true);}
 @Override public void onCreate(Bundle b){super.onCreate(b);try{
  getSharedPreferences("op-ultra-display",0).edit().clear().apply();UltraDashboardView v=new UltraDashboardView(this);check("fresh_default_music",v.currentCard()==2);
  check("default_layout_speed_left",!v.display().getBoolean("cardsSwapped"));
  for(boolean swap:new boolean[]{true,false}){
   UltraPreferences.saveCardsSwapped(this,swap);v.refreshTheme();v.layout(0,0,1920,720);v.selectCard(2);
   check("layout_live_"+swap,v.display().getBoolean("cardsSwapped")==swap);
   UltraDashboardView recreated=new UltraDashboardView(this);check("layout_persists_"+swap,recreated.display().getBoolean("cardsSwapped")==swap);recreated.release();
   float x=UltraCardLayout.infoX(swap)+400;long at=SystemClock.uptimeMillis();
   android.view.MotionEvent down=android.view.MotionEvent.obtain(at,at,0,x,300,0),up=android.view.MotionEvent.obtain(at,at+1,1,x,300,0);
   v.onTouchEvent(down);v.onTouchEvent(up);down.recycle();up.recycle();check("touch_follows_card_"+swap,v.currentCard()==0);
   x=UltraCardLayout.speedX(swap)+400;down=android.view.MotionEvent.obtain(at,at,0,x,300,0);up=android.view.MotionEvent.obtain(at,at+1,1,x,300,0);v.onTouchEvent(down);v.onTouchEvent(up);down.recycle();up.recycle();check("speed_touch_does_not_switch_"+swap,v.currentCard()==0);
  }
  check("gradient40_reported",v.display().getInt("edgeBlendPercent")==40);
  v.selectCard(0);check("quick_selection_applies",v.currentCard()==0);check("quick_selection_does_not_overwrite_startup",UltraPreferences.defaultCard(this)==2);v.selectCard(-1);v.selectCard(4);check("invalid_card_ignored",v.currentCard()==0);
  for(int i=0;i<40;i++)v.selectCard(UltraPreferences.CARD_ORDER[i%3]);v.selectCard(3);check("rapid_latest_card_wins",v.currentCard()==3);check("music_next_is_tyres",UltraPreferences.adjacentCard(2,1)==0);check("vehicle_next_is_energy",UltraPreferences.adjacentCard(0,1)==3);check("energy_wraps_music",UltraPreferences.adjacentCard(3,1)==2);check("music_previous_is_energy",UltraPreferences.adjacentCard(2,-1)==3);
  UltraPreferences.saveDefaultCard(this,0);UltraDashboardView next=new UltraDashboardView(this);check("new_view_loads_persisted_default",next.currentCard()==0);next.release();
  getSharedPreferences("op-ultra-display",0).edit().putInt("default_card",99).apply();check("corrupt_default_falls_back_music",UltraPreferences.defaultCard(this)==2);
  getSharedPreferences("op-ultra-display",0).edit().putInt("default_card",1).apply();check("removed_trip_default_migrates_music",UltraPreferences.defaultCard(this)==2);
  v.accept(sample(4,0,true));check("fresh_parked_allows_frame",v.parked());v.accept(sample(1,0,true));check("D_denies_frame",!v.parked());v.accept(sample(4,2001,true));check("stale_gear_denies_frame",!v.parked());v.accept(sample(4,0,false));check("disconnected_denies_frame",!v.parked());
  UltraPreferences.saveThemeMode(this,1);v.refreshTheme();check("explicit_day",v.display().getString("theme").equals("day"));UltraPreferences.saveThemeMode(this,2);v.refreshTheme();check("explicit_night",v.display().getString("theme").equals("night"));UltraPreferences.saveThemeMode(this,0);v.refreshTheme();boolean dark=(getResources().getConfiguration().uiMode&48)==32;check("auto_matches_resolved_config",v.display().getString("theme").equals(dark?"night":"day"));
  Bitmap a=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888),c=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888),d=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);v.map(a,1);v.map(c,2);check("replaced_map_recycled",a.isRecycled());v.map(d,2);check("duplicate_map_copy_recycled",d.isRecycled());check("current_map_not_recycled",!c.isRecycled());
  for(int gear=1;gear<=4;gear++){JSONObject vehicle=sample(gear,0,true);vehicle.getJSONObject("fields").put("speed_raw",new JSONObject().put("state","returned").put("raw",42.3).put("ageMs",0));v.accept(vehicle);check("full_layout_gear_"+gear,v.projectionReady());check("speed_kmh_"+gear,"42".equals(v.display().getString("speedKmh")));}
  JSONObject stale=sample(1,0,true);stale.getJSONObject("fields").put("speed_raw",new JSONObject().put("state","returned").put("raw",42.3).put("ageMs",1000));v.accept(stale);check("stale_speed_restores_oem",!v.projectionReady());check("stale_speed_never_shows_zero","—".equals(v.display().getString("speedKmh")));
  stale.getJSONObject("fields").getJSONObject("speed_raw").put("ageMs",0).put("raw",1023);v.accept(stale);check("invalid_speed_restores_oem",!v.projectionReady());
  v.accept(sample(1,0,false));check("data_disconnect_restores_oem",!v.projectionReady());

  v.navigation(new com.shilapi.xcertplay.iap2.message.RouteGuidanceSummary(true,"深圳大学",1080L,12600L,1793018400L,320L));
  check("navigation_destination_bound",v.display().getBoolean("navigationActive")&&v.display().getBoolean("destinationAvailable"));
  check("navigation_diagnostics_no_route_text",!v.display().toString().contains("深圳大学"));
  check("all_route_fields_bound",v.display().getBoolean("remainingTimeAvailable")&&v.display().getBoolean("remainingDistanceAvailable")&&v.display().getBoolean("arrivalAvailable")&&v.display().getBoolean("nextTurnAvailable"));
  v.navigation(new com.shilapi.xcertplay.iap2.message.RouteGuidanceSummary());check("navigation_end_clears_display",!v.display().getBoolean("navigationActive")&&!v.display().getBoolean("destinationAvailable"));
  JSONObject vehicle=sample(4,0,true);JSONObject fs=vehicle.getJSONObject("fields");
  fs.put("doors_raw",new JSONObject().put("state","returned").put("raw",new JSONArray("[0,0,0,0]")).put("ageMs",0));
  fs.put("bonnet_raw",new JSONObject().put("state","returned").put("raw",0).put("ageMs",0));fs.put("trunk_opener_raw",new JSONObject().put("state","returned").put("raw",1).put("ageMs",0));
  v.accept(vehicle);check("oem_closure_enum_closed",v.display().getString("closures").equals("已关闭"));
  fs.getJSONObject("trunk_opener_raw").put("raw",5);v.accept(vehicle);check("oem_trunk_open_is_not_closed",v.display().getString("closures").equals("有未关闭项"));
  fs.getJSONObject("trunk_opener_raw").put("raw",0);v.accept(vehicle);check("oem_unknown_closure_never_closed",v.display().getString("closures").equals("—"));
  fs.getJSONObject("trunk_opener_raw").put("raw",1);fs.getJSONObject("doors_raw").put("raw",new JSONArray("[0,7,0,0]"));v.accept(vehicle);check("invalid_door_vector_never_closed",v.display().getString("closures").equals("—"));
  fs.put("average_consumption_raw",new JSONObject().put("state","returned").put("raw",20.8).put("ageMs",0));fs.put("trip_since_charge_raw",new JSONObject().put("state","returned").put("raw",132.4).put("ageMs",0));fs.put("trip_duration_seconds_raw",new JSONObject().put("state","returned").put("raw",7620).put("ageMs",0));v.accept(vehicle);
  check("speed_header_uses_real_average",v.display().getString("averageConsumption").equals("20.8"));check("since_charge_uses_charge_field",v.display().getString("sinceChargeKm").equals("132.4"));check("duration_seconds_converts_to_minutes",v.display().getString("tripDurationMinutes").equals("127"));fs.remove("trip_duration_seconds_raw");v.accept(vehicle);check("duration_without_collector_stays_unknown",v.display().getString("tripDurationMinutes").equals("—"));
  JSONObject power=new JSONObject().put("state","returned").put("raw",12).put("ageMs",0).put("readAtMs",1);fs.put("charge_discharge_power_raw",power);v.accept(vehicle);v.accept(vehicle);check("same_power_sample_not_repeated",v.display().getInt("powerHistoryPoints")==1);
  for(int n=2;n<70;n++){power.put("readAtMs",n).put("raw",n%30);v.accept(vehicle);}check("power_history_is_bounded",v.display().getInt("powerHistoryPoints")==48);
  v.accept(sample(4,0,false));check("disconnection_clears_power_history",v.display().getInt("powerHistoryPoints")==0);
  Method decode=Class.forName("com.heavendoor.ultralab.UltraCoverLoader").getDeclaredMethod("decode",byte[].class);decode.setAccessible(true);check("bad_cover_rejected",decode.invoke(null,(Object)new byte[64])==null);check("oversized_cover_rejected",decode.invoke(null,(Object)new byte[4194305])==null);
  Bitmap large=Bitmap.createBitmap(2048,2048,Bitmap.Config.ARGB_8888);large.eraseColor(0xff4a7b88);ByteArrayOutputStream raw=new ByteArrayOutputStream();large.compress(Bitmap.CompressFormat.PNG,100,raw);large.recycle();Bitmap small=(Bitmap)decode.invoke(null,(Object)raw.toByteArray());check("large_cover_downsampled",small!=null&&small.getWidth()<=512&&small.getHeight()<=512);v.cover(small);v.release();check("owned_cover_recycled",small.isRecycled());check("owned_map_recycled",c.isRecycled());
  checks.put("syntheticFixture",true);checks.put("allPassed",true);try(FileOutputStream f=new FileOutputStream(new File(getFilesDir(),"behavior-fixture.json"))){f.write(checks.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }catch(Throwable e){try{checks.put("allPassed",false).put("error",e.getClass().getSimpleName()+":"+e.getMessage());try(FileOutputStream f=new FileOutputStream(new File(getFilesDir(),"behavior-fixture.json"))){f.write(checks.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception ignored){}throw new RuntimeException(e);}}
}
