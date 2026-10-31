package com.heavendoor.ultralab;

import android.content.*;
import android.content.pm.*;
import android.hardware.usb.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.json.*;

/** Fixed, signed-OP-only helper. No arbitrary shell endpoint or vehicle controls. */
public final class OwnRootService {
 private static final String SIGNER="5202f52cb75f7073bb06fe90cd13cc5151a6240bda9ed7e7a6f96352f9935da5";
 private final Context context;private final int clientUid;private volatile long last=SystemClock.elapsedRealtime();
 private volatile UltraDataService data;private volatile LocalSocket dataOwner;private volatile int peers;
 private OwnRootService(Context context,int uid)throws Exception{
  this.context=context;clientUid=uid;PackageInfo info=context.getPackageManager().getPackageInfo(FactoryRootClient.PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES);
  if(uid!=context.getApplicationInfo().uid||info.signingInfo.getApkContentsSigners().length!=1)throw new SecurityException();
  byte[] digest=MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));if(!SIGNER.equals(s.toString()))throw new SecurityException();
 }
 public static void main(String[] args)throws Exception{
  if(android.os.Process.myUid()!=0||args.length!=1||Build.VERSION.SDK_INT!=28||!"E28".equals(Build.MODEL))throw new SecurityException();
  if(Looper.getMainLooper()==null)Looper.prepareMainLooper();Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);Context system=(Context)at.getMethod("getSystemContext").invoke(thread);
  Context context=system.createPackageContext(FactoryRootClient.PACKAGE,Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
  new OwnRootService(context,Integer.parseInt(args[0])).serve();
 }
 private void serve()throws Exception{
  try(LocalServerSocket server=new LocalServerSocket(FactoryRootClient.name(clientUid))){
   Thread guard=new Thread(()->{while(true){SystemClock.sleep(500);if(peers==0&&SystemClock.elapsedRealtime()-last>8000){stopData();System.exit(0);}}},"op-root-idle");guard.setDaemon(true);guard.start();
   while(true){LocalSocket c=server.accept();if(c.getPeerCredentials().getUid()!=clientUid&&c.getPeerCredentials().getUid()!=0){c.close();continue;}synchronized(this){if(peers>=3){c.close();continue;}peers++;}new Thread(()->handle(c),"op-root-peer").start();}
  }
 }
 private synchronized UltraDataService reader(LocalSocket owner)throws Exception{if(dataOwner!=null&&dataOwner!=owner)throw new IOException("reader_already_owned");if(data==null)data=new UltraInstrumentDataService(context);dataOwner=owner;return data;}
 private synchronized void stopData(){UltraDataService old=data;data=null;dataOwner=null;if(old!=null)old.shutdown();}
 private void handle(LocalSocket c){
  try(LocalSocket socket=c){c.setSoTimeout(5000);DataInputStream in=new DataInputStream(c.getInputStream());DataOutputStream out=new DataOutputStream(c.getOutputStream());if(in.readInt()!=FactoryRootClient.MAGIC)return;out.writeInt(FactoryRootClient.MAGIC);out.writeInt(FactoryRootClient.VERSION);out.flush();
   while(true){int op=in.readInt();last=SystemClock.elapsedRealtime();if(op==1)reply(out,reader(c).snapshot());else if(op==2){reader(c).setProjectionActive(in.readBoolean());out.writeInt(1);}else if(op==3){reader(c).restartProjection();out.writeInt(1);}else if(op==4)reply(out,wired().toString());else if(op==5)reply(out,hotspot().toString());else if(op==6){reader(c).setProjectionMode(in.readInt());out.writeInt(1);}else if(op==7){reader(c).setPopupTheme(in.readBoolean());out.writeInt(1);}else break;out.flush();}
  }catch(Exception ignored){}finally{synchronized(this){if(dataOwner==c)stopData();peers--;}last=SystemClock.elapsedRealtime();}
 }
 private static void reply(DataOutputStream out,String text)throws IOException{byte[] b=text.getBytes(StandardCharsets.UTF_8);if(b.length>98304)throw new IOException();out.writeInt(b.length);out.write(b);}
 private JSONObject hotspot()throws Exception{String before=command("/system/bin/cmd","appops","get",FactoryRootClient.PACKAGE,"WRITE_SETTINGS");command("/system/bin/cmd","appops","set",FactoryRootClient.PACKAGE,"WRITE_SETTINGS","allow");String after=command("/system/bin/cmd","appops","get",FactoryRootClient.PACKAGE,"WRITE_SETTINGS");return new JSONObject().put("writeSettings",after.contains("WRITE_SETTINGS: allow")).put("previous",before).put("current",after);}
 private JSONObject wired()throws Exception{
  String before=command("/system/bin/cmd","appops","get",FactoryRootClient.PACKAGE,"ACTIVATE_VPN");command("/system/bin/cmd","appops","set",FactoryRootClient.PACKAGE,"ACTIVATE_VPN","allow");String after=command("/system/bin/cmd","appops","get",FactoryRootClient.PACKAGE,"ACTIVATE_VPN");
  command("/system/bin/pm","grant",FactoryRootClient.PACKAGE,"android.permission.RECORD_AUDIO");
  IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"usb");Class<?> api=Class.forName("android.hardware.usb.IUsbManager");Object usb=Class.forName(api.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);Bundle devices=new Bundle();api.getMethod("getDeviceList",Bundle.class).invoke(usb,devices);int phones=0,grants=0;
  for(String key:devices.keySet()){Object value=devices.get(key);if(!(value instanceof UsbDevice))continue;UsbDevice device=(UsbDevice)value;if(device.getVendorId()!=0x05ac||device.getProductId()<0x1290||device.getProductId()>0x12af)continue;phones++;api.getMethod("grantDevicePermission",String.class,int.class).invoke(usb,device.getDeviceName(),clientUid);grants++;}
  return new JSONObject().put("vpn",after.contains("ACTIVATE_VPN: allow")).put("previousVpn",before).put("currentVpn",after).put("microphone",context.getPackageManager().checkPermission("android.permission.RECORD_AUDIO",FactoryRootClient.PACKAGE)==PackageManager.PERMISSION_GRANTED).put("usbPhones",phones).put("usbGrants",grants).put("systemUsbCompatibility","existing_firmware_required").put("phoneTrust","must_be_confirmed_on_iPhone");
 }
 private static String command(String...args)throws Exception{java.lang.Process p=new ProcessBuilder(args).redirectErrorStream(true).start();if(!p.waitFor(3,TimeUnit.SECONDS)){p.destroy();throw new IOException("permission_command_timeout");}ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[512];int n;try(InputStream in=p.getInputStream()){while((n=in.read(b))>0){bytes.write(b,0,n);if(bytes.size()>4096)throw new IOException("command_result_bounds");}}if(p.exitValue()!=0)throw new IOException(bytes.toString("UTF-8"));return bytes.toString("UTF-8");}
}
