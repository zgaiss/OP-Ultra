package com.heavendoor.ultralab;

/** A startup-trip event, never a charge/Trip A/Trip B snapshot or app uptime. */
final class UltraStartupTrip {
 // The OEM property rotates through startup/charge/A/B. A normal kind-0
 // sample can be four seconds apart, plus bounded subscription/RPC latency.
 static final long TTL_MS=10000;
 static boolean normalEmptySubscription(String text){
  if(text==null||text.length()>128)return false;
  String[] p=text.trim().split("\\s+");
  if(p.length!=3||!p[0].equals("OPTRIP3")||!p[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))return false;
  try{int pid=Integer.parseInt(p[2]);return pid>=1&&pid<=4194304;}catch(NumberFormatException e){return false;}
 }
 final String bootId;final int instrumentPid,minutes;final double kilometers;final long sourceNanos;
 private UltraStartupTrip(String epoch,int p,double k,int m,long n){bootId=epoch;instrumentPid=p;kilometers=k;minutes=m;sourceNanos=n;}
 static UltraStartupTrip parse(String text){
  if(text==null||text.length()>256)return null;String[] p=text.trim().split("\\s+");
  if(p.length!=7||!p[0].equals("OPTRIP3")||!p[3].equals("OPTRIP1")||!p[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))return null;
  try{int pid=Integer.parseInt(p[2]);double km=Double.parseDouble(p[4]),min=Double.parseDouble(p[5]);long ns=Long.parseLong(p[6]);
   if(pid<1||pid>4194304||!Double.isFinite(km)||km<0||km>99999||!Double.isFinite(min)||min<0||min>10080||min!=Math.rint(min)||ns<=0)return null;
   return new UltraStartupTrip(p[1],pid,km,(int)min,ns);
  }catch(NumberFormatException e){return null;}
 }
 static final class Cache {
  private UltraStartupTrip sample;private long at;
  synchronized boolean accept(UltraStartupTrip next,long receivedAt){
   if(next==null||receivedAt<0)return false;
   if(sample!=null&&next.bootId.equals(sample.bootId)&&next.instrumentPid==sample.instrumentPid&&next.sourceNanos<=sample.sourceNanos)return false;
   sample=next;at=receivedAt;return true;
  }
  synchronized UltraStartupTrip matching(double sdkKm,long now){
   return sample!=null&&now>=at&&now-at<=TTL_MS&&Double.isFinite(sdkKm)&&sdkKm>=0&&Math.abs(sample.kilometers-sdkKm)<=.200001?sample:null;
  }
  synchronized long readAt(){return at;}
  synchronized long age(long now){return sample==null||now<at?-1:now-at;}
 }
}
