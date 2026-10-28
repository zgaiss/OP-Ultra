package com.heavendoor.ultralab;

import android.os.*;
import dalvik.system.PathClassLoader;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Fixed read-only SDK getters. No setting, key injection, root command or OEM image reads. */
final class UltraComfortReader implements AutoCloseable {
 final UltraComfortState state=new UltraComfortState();
 private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"op-comfort-read");t.setDaemon(true);return t;});
 private Object vehicle,audio;private Method temp,fan,fanMax,power,volume,volumeMax,mute;private int maximumFan,maximumVolume;private volatile boolean closed,active;private volatile long generation;private long nextConnect;
 UltraComfortReader(){io.scheduleWithFixedDelay(this::tick,0,100,TimeUnit.MILLISECONDS);}
 private void connect()throws Exception{
  ClassLoader l=new PathClassLoader("/system/framework/android.car.jar",getClass().getClassLoader());Class<?> sm=Class.forName("android.os.ServiceManager");
  IBinder b=(IBinder)sm.getMethod("getService",String.class).invoke(null,"car_service");if(b==null||!"android.car.ICar".equals(b.getInterfaceDescriptor()))throw new IllegalStateException();
  Object car=l.loadClass("android.car.ICar$Stub").getMethod("asInterface",IBinder.class).invoke(null,b);
  IBinder v=(IBinder)l.loadClass("android.car.ICar").getMethod("getCarService",String.class).invoke(car,"xp_vehicle");Class<?> c=l.loadClass("android.car.hardware.XpVehicle.IXpVehicle");if(v==null||!c.getName().equals(v.getInterfaceDescriptor()))throw new IllegalStateException();
  vehicle=l.loadClass(c.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,v);temp=c.getMethod("getHvacDrvSeatTempValue");fan=c.getMethod("getHvacFanSpeedLevel");fanMax=c.getMethod("getHvacMaxWindSpeedLevel");
  try{power=c.getMethod("getHvacPowerState");}catch(NoSuchMethodException absent){power=null;}
  IBinder a=(IBinder)sm.getMethod("getService",String.class).invoke(null,"audio");Class<?> ac=Class.forName("android.media.IAudioService");if(a==null||!ac.getName().equals(a.getInterfaceDescriptor()))throw new IllegalStateException();audio=Class.forName(ac.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,a);
  volume=ac.getMethod("getStreamVolume",int.class);volumeMax=ac.getMethod("getStreamMaxVolume",int.class);mute=ac.getMethod("isStreamMute",int.class);
  maximumFan=((Number)fanMax.invoke(vehicle)).intValue();maximumVolume=((Number)volumeMax.invoke(audio,3)).intValue();
  if(maximumFan<1||maximumFan>16||maximumVolume<1||maximumVolume>100)throw new IllegalStateException("comfort_range");
 }
 synchronized void active(boolean value){if(active==value)return;generation++;active=value;if(!value)state.reset();}
 private synchronized void accept(long owner,int k,int value,int max,boolean mute){if(active&&!closed&&owner==generation)state.sample(k,value,max,mute,SystemClock.elapsedRealtime());}
 private synchronized void acceptFan(long owner,int value,int max,int enabled){if(active&&!closed&&owner==generation)state.sampleFan(value,max,enabled,SystemClock.elapsedRealtime());}
 private void tick(){if(closed||!active)return;long now=SystemClock.elapsedRealtime(),owner=generation;try{if(vehicle==null||audio==null){if(now<nextConnect)return;connect();}
  try{float v=((Number)temp.invoke(vehicle)).floatValue();accept(owner,0,Float.isFinite(v)?Math.round(v*10):-1,400,false);}catch(Throwable e){accept(owner,0,-1,400,false);if(dead(e))throw e;}
  try{int enabled=power==null?-1:((Number)power.invoke(vehicle)).intValue();if(power!=null&&enabled==0)acceptFan(owner,0,maximumFan,0);else{int level=((Number)fan.invoke(vehicle)).intValue();if(power==null)accept(owner,1,level,maximumFan,false);else acceptFan(owner,level,maximumFan,enabled);}}catch(Throwable e){accept(owner,1,-1,-1,false);if(dead(e))throw e;}
  try{accept(owner,2,((Number)volume.invoke(audio,3)).intValue(),maximumVolume,(Boolean)mute.invoke(audio,3));}catch(Throwable e){accept(owner,2,-1,-1,false);if(dead(e))throw e;}
 }catch(Throwable e){vehicle=null;audio=null;nextConnect=now+2000;}}
 private static boolean dead(Throwable e){while(e instanceof InvocationTargetException&&e.getCause()!=null)e=e.getCause();return e instanceof DeadObjectException;}
 @Override public void close(){closed=true;io.shutdownNow();}
}
