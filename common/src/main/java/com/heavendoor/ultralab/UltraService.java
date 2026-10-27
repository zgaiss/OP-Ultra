package com.heavendoor.ultralab;
import android.app.*;import android.content.*;import android.os.IBinder;
/** Owns this service generation's data/canvas, never a later replacement engine. */
public final class UltraService extends Service {
 private UltraEngine owner;
 public static void launch(Context c){UltraPreferences.sessionEnabled(c,true);c.startForegroundService(new Intent(c,UltraService.class));}
 public static void halt(Context c){UltraPreferences.sessionEnabled(c,false);UltraEngine e=UltraEngine.peek();if(e!=null)e.close();c.stopService(new Intent(c,UltraService.class));}
 @Override public void onCreate(){super.onCreate();if(UltraPreferences.sessionEnabled(this))owner=UltraEngine.get(this);NotificationManager n=getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel("op-ultra","OP Ultra仪表实验",NotificationManager.IMPORTANCE_LOW));PendingIntent open=PendingIntent.getActivity(this,31,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);startForeground(31,new Notification.Builder(this,"op-ultra").setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("OP Ultra仪表实验").setContentText("真实车况 · 自动仪表投放 · 点击查看信息卡").setContentIntent(open).setOngoing(true).build());if(owner!=null)owner.start();}
 @Override public int onStartCommand(Intent i,int f,int id){if(!UltraPreferences.sessionEnabled(this)){stopSelf();return START_NOT_STICKY;}if(owner==null)owner=UltraEngine.get(this);owner.start();return START_STICKY;}
 @Override public void onDestroy(){if(owner!=null){owner.close();owner=null;}stopForeground(true);super.onDestroy();}
 @Override public IBinder onBind(Intent i){return null;}
}
