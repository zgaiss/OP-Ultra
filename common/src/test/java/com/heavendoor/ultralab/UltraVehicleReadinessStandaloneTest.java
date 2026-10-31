package com.heavendoor.ultralab;

/** Synthetic contract/replay tests: no vehicle properties are probed or inferred. */
public final class UltraVehicleReadinessStandaloneTest {
 private static int checks;
 private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
 private static UltraVehicleReadiness.Field f(Object raw,Object age,Object area,Object type,Object state){return new UltraVehicleReadiness.Field(state,raw,age,area,type);}
 private static UltraVehicleReadiness.Field f(Object raw,Object age){return f(raw,age,0,"number","returned");}
 private static boolean ready(Object contract,UltraVehicleReadiness.Field s,UltraVehicleReadiness.Field g,long received,long now){return UltraVehicleReadiness.ready(contract,true,true,s,g,received,now);}
 public static void main(String[] args){
  String legacy=UltraVehicleReadiness.LEGACY_CONTRACT;
  UltraVehicleReadiness.Field speed=f(32.5,0),gear=f(1,0);
  check(ready(legacy,speed,gear,100,100));
  for(int i=1;i<=4;i++)check(ready(legacy,speed,f(i,0),100,100));
  for(Object c:new Object[]{null,"","e28u-vhal","unknown",1})check(!ready(c,speed,gear,100,100));
  for(Object value:new Object[]{null,"32.5",true,Double.NaN,Double.POSITIVE_INFINITY,-1,301,new int[]{32}})check(!ready(legacy,f(value,0),gear,100,100));
  for(Object value:new Object[]{null,"1",true,Double.NaN,Double.NEGATIVE_INFINITY,0,5,1.5,new int[]{1}})check(!ready(legacy,speed,f(value,0),100,100));
  for(Object age:new Object[]{null,"0",true,0.0,-1,Long.MAX_VALUE,751})check(!ready(legacy,f(32,age),gear,100,100));
  for(Object area:new Object[]{null,"0",0.0,1,-1,true})check(!ready(legacy,f(32,0,area,"number","returned"),gear,100,100));
  for(Object type:new Object[]{null,"","string","array",1})check(!ready(legacy,f(32,0,0,type,"returned"),gear,100,100));
  for(Object state:new Object[]{null,"unknown","permission_denied","property_no_data","api_unavailable"})check(!ready(legacy,f(32,0,0,"number",state),gear,100,100));
  check(!ready(legacy,null,gear,100,100));check(!ready(legacy,speed,null,100,100));
  check(ready(legacy,f(0,750),f(4,1000),100,100));
  check(!ready(legacy,f(0,750),f(4,1000),100,101));
  check(!ready(legacy,speed,f(4,1001),100,100));
  check(!ready(legacy,speed,gear,101,100));
  check(!ready(legacy,speed,gear,-1,100));
  check(!ready(legacy,speed,gear,0,Long.MAX_VALUE));
  check(!UltraVehicleReadiness.ready(legacy,false,true,speed,gear,100,100));
  check(!UltraVehicleReadiness.ready(legacy,true,false,speed,gear,100,100));
  check(UltraVehicleReadiness.live(1,"vehicle",2000,true,100,1600));
  check(UltraVehicleReadiness.live(1L,"vehicle",0L,true,100,100));
  check(!UltraVehicleReadiness.live(1,"vehicle",2000,true,100,1601));
  check(!UltraVehicleReadiness.live(1,"vehicle",2000,true,101,100));
  check(!UltraVehicleReadiness.live(1,"vehicle",2000,false,100,100));
  check(!UltraVehicleReadiness.live("1","vehicle",2000,true,100,100));
  check(!UltraVehicleReadiness.live(1,"vehicle","2000",true,100,100));
  check(!UltraVehicleReadiness.live(1,"vehicle",2000,"true",100,100));
  check(!UltraVehicleReadiness.live(1,"fixture",2000,true,100,100));
  check(!UltraVehicleReadiness.live(1,"vehicle",12345,true,100,100));
  // Replay: connected -> speed expires -> gear expires -> disconnected -> replacement source.
  for(long now:new long[]{100,850,851,1100,1600,1601}){
   boolean live=UltraVehicleReadiness.live(1,"vehicle",2000,true,100,now);
   check(UltraVehicleReadiness.ready(legacy,live,true,speed,gear,100,now)==(now<=850));
  }
  check(!UltraVehicleReadiness.ready("e28u-vhal",true,true,f(0,0),f(4,0),1700,1700));
  System.out.println("Vehicle readiness: "+checks+" assertions passed");
 }
}
