package com.heavendoor.ultralab;

/** Offline-verifiable boundary for the existing E28 getter contract, NOT an E28U/VHAL map.
 * Unknown contracts fail closed even if raw values happen to resemble legacy speed/gear.
 * The legacy service's model/SDK, caller and signer checks remain mandatory upstream.
 */
public final class UltraVehicleReadiness {
 public static final String LEGACY_CONTRACT="legacy-xp-vehicle-e28-v1";
 public static final long FRAME_TTL_MS=1500, SPEED_TTL_MS=750, GEAR_TTL_MS=1000;
 private UltraVehicleReadiness(){}
 public static final class Field {
  public final Object state, raw, ageMs, area, valueType;
  public Field(Object state,Object raw,Object ageMs,Object area,Object valueType){
   this.state=state;this.raw=raw;this.ageMs=ageMs;this.area=area;this.valueType=valueType;
  }
 }
 public static boolean live(Object schema,Object source,Object uid,Object connected,long received,long now){
  return integer(schema,1)&&"vehicle".equals(source)&&(integer(uid,0)||integer(uid,2000))
    &&Boolean.TRUE.equals(connected)&&fresh(0,received,now,FRAME_TTL_MS);
 }
 public static boolean ready(Object contract,boolean live,boolean mapFresh,Field speed,Field gear,long received,long now){
  if(!LEGACY_CONTRACT.equals(contract)||!live||!mapFresh)return false;
  Double s=scalar(speed,received,now,SPEED_TTL_MS),g=scalar(gear,received,now,GEAR_TTL_MS);
  return s!=null&&s>=0&&s<=300&&g!=null&&g>=1&&g<=4&&g==Math.rint(g);
 }
 public static Double scalar(Field field,long received,long now,long ttl){
  if(field==null||!"returned".equals(field.state)||!"number".equals(field.valueType)
    ||!integer(field.area,0)||!validAge(field.ageMs,received,now,ttl)||!(field.raw instanceof Number))return null;
  double n=((Number)field.raw).doubleValue();return Double.isFinite(n)?n:null;
 }
 public static boolean validAge(Object age,long received,long now,long ttl){
  return (age instanceof Integer||age instanceof Long)&&fresh(((Number)age).longValue(),received,now,ttl);
 }
 private static boolean fresh(long age,long received,long now,long ttl){
  // Subtract only after bounding both operands: no overflow/future-anchor coercion.
  return ttl>=0&&age>=0&&age<=ttl&&received>=0&&now>=received&&now-received<=ttl-age;
 }
 private static boolean integer(Object value,int expected){
  return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()==expected;
 }
}
