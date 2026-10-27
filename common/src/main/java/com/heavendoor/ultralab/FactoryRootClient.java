package com.heavendoor.ultralab;

import android.content.Context;
import android.net.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** App-private client of the owner's factory service. Only fixed OP operations are exposed. */
public final class FactoryRootClient extends IUltraDataService.Stub implements AutoCloseable {
 public static final int MAGIC=0x4f505252,VERSION=20;
 public static final String PACKAGE="com.heavendoor.opultra.carplay";
 private final LocalSocket socket;private final DataInputStream input;private final DataOutputStream output;
 private FactoryRootClient(LocalSocket socket)throws Exception{this.socket=socket;socket.setSoTimeout(2500);if(socket.getPeerCredentials().getUid()!=0)throw new SecurityException("factory_helper_owner");input=new DataInputStream(socket.getInputStream());output=new DataOutputStream(socket.getOutputStream());output.writeInt(MAGIC);output.flush();if(input.readInt()!=MAGIC||input.readInt()!=VERSION)throw new IOException("factory_helper_version");}
 public static boolean selected(Context c){return c.getSharedPreferences("op-native-authority",0).getBoolean("vehicle_reader",Build.VERSION.SDK_INT==28&&"E28".equals(Build.MODEL));}
 public static void select(Context c,boolean value){c.getSharedPreferences("op-native-authority",0).edit().putBoolean("vehicle_reader",value).apply();}
 public static FactoryRootClient connect(Context context)throws Exception{
  if(!PACKAGE.equals(context.getPackageName())||Build.VERSION.SDK_INT!=28||!"E28".equals(Build.MODEL))throw new IOException("仅支持本车 E28 / Android 9");
  try{return open(android.os.Process.myUid());}catch(IOException ignored){}
  String apk=context.getApplicationInfo().sourceDir;
  if(!apk.startsWith("/data/app/"+PACKAGE+"-")||!apk.endsWith("/base.apk")||!apk.matches("[A-Za-z0-9_./+=~-]+"))throw new SecurityException("own_apk_path");
  String command="umask 077; CLASSPATH='"+apk+"' /system/bin/nohup /system/bin/app_process /system/bin --nice-name=op-ultra-native-root com.heavendoor.ultralab.OwnRootService "+android.os.Process.myUid()+" >/data/local/tmp/op-ultra-native-root-"+android.os.Process.myUid()+".log 2>&1 </dev/null &";
  try(LocalSocket root=new LocalSocket()){
   // FILESYSTEM needs the actual path; RESERVED would also prepend /dev/socket/.
   root.connect(new LocalSocketAddress("/dev/socket/xpaftersalesdw",LocalSocketAddress.Namespace.FILESYSTEM));
   root.getOutputStream().write(("XP_AFTERSALES_REQ~3~1~0#"+command+"\r\n").getBytes(StandardCharsets.UTF_8));root.getOutputStream().flush();SystemClock.sleep(500);
  }
  long until=SystemClock.elapsedRealtime()+7000;Exception failure=null;
  do{try{return open(android.os.Process.myUid());}catch(IOException e){failure=e;SystemClock.sleep(120);}}while(SystemClock.elapsedRealtime()<until);
  throw new IOException("原车授权服务未返回，请检查 xpaftersalesd",failure);
 }
 private static FactoryRootClient open(int uid)throws Exception{LocalSocket socket=new LocalSocket();try{socket.connect(new LocalSocketAddress(name(uid),LocalSocketAddress.Namespace.ABSTRACT));return new FactoryRootClient(socket);}catch(Exception e){try{socket.close();}catch(Exception ignored){}throw e;}}
 public static String name(int uid){return "op.ultra.native."+uid+".v"+VERSION;}
 @Override public synchronized String snapshot()throws RemoteException{try{return text(1);}catch(Exception e){throw new RemoteException(e.toString());}}
 @Override public synchronized void setProjectionActive(boolean active)throws RemoteException{try{output.writeInt(2);output.writeBoolean(active);output.flush();if(input.readInt()!=1)throw new IOException("projection_ack");}catch(Exception e){throw new RemoteException(e.toString());}}
 public synchronized JSONObject authorizeWired()throws Exception{return new JSONObject(text(4));}
 public synchronized JSONObject authorizeHotspot()throws Exception{return new JSONObject(text(5));}
 @Override public synchronized void restartProjection()throws RemoteException{try{output.writeInt(3);output.flush();if(input.readInt()!=1)throw new IOException("restart_ack");}catch(Exception e){throw new RemoteException(e.toString());}}
 @Override public synchronized void setProjectionMode(int mode)throws RemoteException{try{UltraOutputMode.checked(mode);output.writeInt(6);output.writeInt(mode);output.flush();if(input.readInt()!=1)throw new IOException("mode_ack");}catch(Exception e){throw new RemoteException(e.toString());}}
 private String text(int operation)throws Exception{output.writeInt(operation);output.flush();int size=input.readInt();if(size<2||size>98304)throw new IOException("reply_bounds");byte[] raw=new byte[size];input.readFully(raw);return new String(raw,StandardCharsets.UTF_8);}
 @Override public void destroy(){close();}
 @Override public void close(){try{android.system.Os.shutdown(socket.getFileDescriptor(),android.system.OsConstants.SHUT_RDWR);}catch(Exception ignored){}try{socket.close();}catch(Exception ignored){}}
}
