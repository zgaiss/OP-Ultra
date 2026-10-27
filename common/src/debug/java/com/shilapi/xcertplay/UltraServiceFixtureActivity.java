package com.shilapi.xcertplay;
import android.app.Activity;import android.os.*;import com.heavendoor.ultralab.*;import org.json.*;import java.io.*;
/** DEBUG ONLY: real service destruction must not close a newly created generation. */
public final class UltraServiceFixtureActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);UltraService.launch(this);new Handler(Looper.getMainLooper()).postDelayed(()->{
  JSONObject result=new JSONObject();try{UltraEngine old=UltraEngine.peek();if(old==null)throw new AssertionError("no owner");UltraService.halt(this);if(UltraEngine.peek()!=null)throw new AssertionError("exit kept engine");if(UltraPreferences.sessionEnabled(this))throw new AssertionError("exit left auto-resume enabled");result.put("explicitExitDisablesServiceResume",true);UltraEngine replacement=UltraEngine.get(this);new Handler(Looper.getMainLooper()).postDelayed(()->{try{if(UltraEngine.peek()!=replacement)throw new AssertionError("old service destroyed new owner");if(!"listening".equals(replacement.snapshot().optString("frameServer")))throw new AssertionError("new socket not available");result.put("immediateExitClosesOwner",true).put("oldServiceDoesNotCloseReplacement",true).put("newSocketAvailable",true);replacement.close();replacement.close();result.put("doubleCloseSafe",true).put("allPassed",true);write(result);}catch(Throwable e){failure(result,e);}},900);}catch(Throwable e){failure(result,e);}
 },350);}
 private void failure(JSONObject r,Throwable e){try{r.put("allPassed",false).put("error",e.toString());write(r);}catch(Exception ignored){}}
 private void write(JSONObject r){try(FileOutputStream f=new FileOutputStream(new File(getFilesDir(),"service-fixture.json"))){f.write(r.toString().getBytes("UTF-8"));}catch(Exception e){throw new RuntimeException(e);}}
}
