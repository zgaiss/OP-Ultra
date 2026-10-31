package com.heavendoor.ultralab;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Bitmap;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Production JSON -> readiness boundary; synthetic data, never starts any vehicle service. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class)
public final class UltraVehicleReadinessDashboardTest {
 private JSONObject frame()throws Exception{
  JSONObject fields=new JSONObject();
  fields.put("speed_raw",new JSONObject().put("raw",40).put("state","returned").put("ageMs",0).put("area",0).put("valueType","number"));
  fields.put("gear",new JSONObject().put("raw",1).put("state","returned").put("ageMs",0).put("area",0).put("valueType","number"));
  return new JSONObject().put("schema",1).put("source","vehicle").put("uid",2000).put("connected",true)
    .put("vehicleContract",UltraVehicleReadiness.LEGACY_CONTRACT).put("fields",fields);
 }
 @Test public void readinessRejectsUnknownIncompleteAndCoercedJson()throws Exception{
  UltraDashboardView view=new UltraDashboardView(RuntimeEnvironment.getApplication());
  try{
   view.map(Bitmap.createBitmap(1920,720,Bitmap.Config.ARGB_8888),1);
   view.accept(frame());assertTrue(view.projectionReady());
   JSONObject missing=frame();missing.remove("vehicleContract");view.accept(missing);assertFalse(view.projectionReady());
   JSONObject unknown=frame().put("vehicleContract","e28u-vhal");view.accept(unknown);assertFalse(view.projectionReady());
   for(String field:new String[]{"speed_raw","gear"}){
    for(String missingKey:new String[]{"raw","state","ageMs","area","valueType"}){
     JSONObject f=frame();f.getJSONObject("fields").getJSONObject(field).remove(missingKey);view.accept(f);assertFalse(missingKey,view.projectionReady());
    }
    for(Object area:new Object[]{1,"0"}){
     JSONObject f=frame();f.getJSONObject("fields").getJSONObject(field).put("area",area);view.accept(f);assertFalse(view.projectionReady());
    }
    JSONObject f=frame();f.getJSONObject("fields").getJSONObject(field).put("ageMs",1001);view.accept(f);assertFalse(view.projectionReady());
    f=frame();f.getJSONObject("fields").getJSONObject(field).put("raw","1");view.accept(f);assertFalse(view.projectionReady());
   }
   view.accept(frame().put("connected","true"));assertFalse(view.projectionReady());
   view.accept(frame().put("schema","1"));assertFalse(view.projectionReady());
   view.accept(frame().put("uid","2000"));assertFalse(view.projectionReady());
   view.accept(frame());assertTrue(view.projectionReady());
   view.map(null,2);assertFalse(view.projectionReady());
  }finally{view.release();}
 }
}
