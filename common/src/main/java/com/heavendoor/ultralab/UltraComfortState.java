package com.heavendoor.ultralab;

/** Pure read-only comfort values. Initial samples seed; only real changes show a popup. */
public final class UltraComfortState {
 public static final int HOLD_MS=2500,STALE_MS=750;
 public static final int MEDIA_STARTUP_MS=2000,MEDIA_STABLE_MS=300;
 private final int[] values={-1,-1,-1},maxes={400,-1,-1},serial=new int[3];
 private final long[] changed=new long[3],fresh=new long[3];
 private final boolean[] seeded=new boolean[3];
 private int counter;private boolean muted,night,mediaArmed;
 private long mediaStarted,mediaStable;
 public synchronized void night(boolean value){night=value;}
 public synchronized void reset(){for(int k=0;k<3;k++){seeded[k]=false;values[k]=-1;serial[k]=0;changed[k]=0;fresh[k]=0;}mediaArmed=false;mediaStarted=mediaStable=0;}
 /** Power is independent of the remembered fan level: HVAC off may still report level 1. */
 public synchronized void sampleFan(int value,int max,int power,long now){
  sample(1,power==0?0:power==1&&value>=1?value:-1,max,false,now);
 }
 public synchronized void sample(int kind,int value,int max,boolean mute,long now){
  if(kind<0||kind>=3)throw new IllegalArgumentException();
  boolean valid=kind==0?value>=100&&value<=400:max>=1&&max<=(kind==1?16:100)&&value>=0&&value<=max;
  // One failed Binder read must not erase a still-fresh popup. Do not renew
  // freshness on failure: the existing 750ms bound still expires the value.
  if(!valid)return;
  boolean wasFresh=seeded[kind]&&now>=fresh[kind]&&now-fresh[kind]<=STALE_MS;
  boolean differs=values[kind]!=value||maxes[kind]!=max||(kind==2&&muted!=mute);
  // Audio routing can restore volume/mute after the first valid read. Establish
  // a quiet, stable baseline once per activation/recovery; do not delay audio.
  boolean baseline=false;
  if(kind==2){
   if(!wasFresh){mediaArmed=false;mediaStarted=mediaStable=now;}
   else if(differs)mediaStable=now;
   if(!mediaArmed){
    baseline=true;serial[kind]=0;changed[kind]=0;
    if(now-mediaStarted>=MEDIA_STARTUP_MS&&now-mediaStable>=MEDIA_STABLE_MS)mediaArmed=true;
   }
  }
  if(!baseline&&wasFresh&&differs){serial[kind]=++counter;changed[kind]=now;}
  else if(!wasFresh){serial[kind]=0;changed[kind]=0;}
  values[kind]=value;maxes[kind]=max;seeded[kind]=true;fresh[kind]=now;if(kind==2)muted=mute;
 }
 public synchronized int[] packet(long now){
  int[] p=new int[16];p[0]=1;p[1]=(night?1:0)|(muted?2:0);
  p[2]=good(0,now)?values[0]:-1;p[3]=good(1,now)?values[1]:-1;p[4]=maxes[1];p[5]=good(2,now)?values[2]:-1;p[6]=maxes[2];
  for(int k=0;k<3;k++){p[7+k]=serial[k];p[10+k]=good(k,now)&&serial[k]!=0?(int)Math.max(0,HOLD_MS-Math.max(0,now-changed[k])):0;}
  return p;
 }
 private boolean good(int k,long now){return seeded[k]&&now>=fresh[k]&&now-fresh[k]<=STALE_MS;}
 public static int signature(int[] p){int h=1;for(int i=0;i<10;i++)h=31*h+p[i];return h;}
 public static byte[] bytes(int[] p){if(p.length!=16)throw new IllegalArgumentException();byte[] b=new byte[64];for(int i=0;i<16;i++)for(int k=0;k<4;k++)b[i*4+k]=(byte)(p[i]>>>(k*8));return b;}
}
