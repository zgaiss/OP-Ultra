package com.heavendoor.ultralab;
import android.content.*;import android.util.Base64;import java.nio.charset.StandardCharsets;import android.hardware.usb.*;import org.json.*;
/** Shell-DUMP-only status; never exports pairing identities, keys, artwork or route text. */
public final class DiagnosticReceiver extends BroadcastReceiver {
 public void onReceive(Context c,Intent i){
  UltraEngine e=UltraEngine.peek();JSONObject result=e==null?new JSONObject():e.snapshot();
  try{result.put("connection",com.shilapi.xcertplay.UltraHostHooks.diagnostics()).put("mediaButtons",com.shilapi.xcertplay.media.CarPlayNowPlayingView.buttonStatus()).put("defaultCard",UltraPreferences.CARDS[UltraPreferences.defaultCard(c)]);
   UsbManager usb=(UsbManager)c.getSystemService(Context.USB_SERVICE);JSONArray devices=new JSONArray();if(usb!=null)for(UsbDevice device:usb.getDeviceList().values())if(device.getVendorId()==0x05ac)devices.put(new JSONObject().put("vendor",device.getVendorId()).put("product",device.getProductId()).put("configurations",device.getConfigurationCount()).put("permission",usb.hasPermission(device)));result.put("usbPhones",devices);
  }catch(Exception ignored){}
  setResultCode(0);setResultData("ULTRA1:"+Base64.encodeToString(result.toString().getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));
 }
}
