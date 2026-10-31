package com.heavendoor.ultralab;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.*;
import com.shilapi.xcertplay.iap2.message.RouteGuidanceSummary;
import com.shilapi.xcertplay.iap2.message.RouteTurnIcon;
import org.json.*;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/** P7 native 1920x720 composition. The instrument receiver preserves OEM status strips. */
public final class UltraDashboardView extends View {
 private final TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
 private final Typeface ui,bold,number;
 private final UltraLyricsState lyrics=new UltraLyricsState();
 private java.util.List<UltraLyricsState.Line> sourceLyrics=java.util.Collections.emptyList();
 private final UltraMusicCardRenderer musicRenderer;
 private final Runnable lyricPulse=new Runnable(){public void run(){synchronized(UltraDashboardView.this){if(!lyricUiVisible())return;postInvalidate();scheduleLyrics();}}};
 private final Bitmap[][] digits=new Bitmap[2][10],vehicle=new Bitmap[2][3],gauge=new Bitmap[2][3];
 private final Path path=new Path();private final Matrix plateMatrix=new Matrix();private final Rect src=new Rect();private final RectF dst=new RectF();
 private JSONObject frame=new JSONObject();private RouteGuidanceSummary navigation=new RouteGuidanceSummary();
 private long received,sequence,mapSequence,mapReceived;private volatile long themeTransitionUntil;
 public void beginThemeTransition(){themeTransitionUntil=SystemClock.elapsedRealtime()+6000;}
 public long themeTransitionDeadline(){return themeTransitionUntil;}
 synchronized long captureRevision(){long visual=lyrics.visualRevision(SystemClock.elapsedRealtime(),card==2);return((sequence+visual)<<2)|(mapFresh()?1:0)|(live()?2:0);}
 synchronized boolean captureAnimating(){long now=SystemClock.elapsedRealtime();return(previousCard!=card&&now-cardChangedAt<CARD_ANIMATION_MS)||lyrics.animating(now,card==2);}
 private static final int GLASS_W=160,GLASS_H=60;
 private Bitmap map,glassMap,cover;private Canvas glassCanvas;private final int[] glassPixels=new int[GLASS_W*GLASS_H],glassScratch=new int[GLASS_W*GLASS_H];private int coverTone=0xff263d43;
 private String description="",songTitle="",songArtist="";private long songPosition,songDuration;private boolean songPlaying;
 private int card,previousCard,themeMode=-1;private long cardChangedAt;private float downX,downY;private boolean night,themeReady,cardsSwapped;
 private int base,ink,muted,line,accent;private Shader leftFade,rightFade,glassTopFade,glassBottomFade,plate,coverGlow;
 private final double[] powerHistory=new double[48];private int historySize,historyWrite;private long powerReadAt=-1;
 private static final float LEFT=62,RIGHT=1378,TOP=112,CARD_W=480,CARD_H=512,CARD_ANIMATION_MS=280;

 public UltraDashboardView(Context context){
  super(context);card=previousCard=UltraPreferences.defaultCard(context);
  ui=Typeface.create(UltraMusicCardStyle.LYRIC_TYPEFACE_FAMILY,Typeface.NORMAL);
  bold=Typeface.create("sans-serif",Typeface.BOLD);
  number=Typeface.create("sans-serif",Typeface.NORMAL);
  musicRenderer=new UltraMusicCardRenderer(ui,bold);
  for(int t=0;t<2;t++){String theme=t==0?"day":"night";
   for(int d=0;d<10;d++)digits[t][d]=asset("digit-"+d+"-"+theme+".png");
   String[] cars={"vehicle-body","vehicle-front-closed","vehicle-rear-closed"},powers={"powerframe","powerempty","powerfull"};
   for(int i=0;i<3;i++){vehicle[t][i]=asset(cars[i]+"-"+theme+".png");gauge[t][i]=asset(powers[i]+"-"+theme+".png");}
  }
  setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);refreshTheme();
 }
 private Bitmap asset(String name){return UltraPublicArtwork.create(name);}
 private static int alpha(int color,int a){return(color&0xffffff)|(a<<24);}
 public synchronized void refreshTheme(){
  boolean swap=UltraPreferences.cardsSwapped(getContext());if(swap!=cardsSwapped){cardsSwapped=swap;if(themeReady)rebuildGlow();sequence++;postInvalidate();describe();}
  int mode=UltraPreferences.themeMode(getContext());boolean dark=mode==2||(mode==0&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
  if(themeReady&&mode==themeMode&&dark==night)return;if(themeReady&&dark!=night)beginThemeTransition();themeReady=true;themeMode=mode;night=dark;
  base=night?0xff080a0e:0xffdeebf7;ink=night?0xffe4e9ed:0xff26343d;muted=night?0xffb4c0c9:0xff526676;
  line=night?0x24d3e5f0:0x296e8a9f;accent=night?0xffa9d5c6:0xff356d62;
  float[] stops={0,.22f,.49f,.77f,1};int[] colors={alpha(base,night?247:250),alpha(base,night?232:237),alpha(base,night?196:204),alpha(base,night?97:107),alpha(base,0)};
  leftFade=new LinearGradient(0,0,560,0,colors,stops,Shader.TileMode.CLAMP);rightFade=new LinearGradient(1920,0,1300,0,colors,stops,Shader.TileMode.CLAMP);
  // Preserve v0.6.3's existing glass material inside the cards only.
  glassTopFade=new LinearGradient(0,0,0,226,new int[]{alpha(base,255),alpha(base,160),alpha(base,0)},new float[]{0,.30f,1},Shader.TileMode.CLAMP);
  glassBottomFade=new LinearGradient(0,530,0,720,new int[]{alpha(base,0),alpha(base,160),alpha(base,255)},new float[]{0,.70f,1},Shader.TileMode.CLAMP);
  plate=new LinearGradient(0,TOP,400,TOP+CARD_H,night?0x66121d26:0x8ceef6fb,night?0x54101a22:0x74eef6fb,Shader.TileMode.CLAMP);
  rebuildGlow();sequence++;postInvalidate();
 }
 private void rebuildGlow(){coverGlow=new RadialGradient(cardX()+240,TOP+220,330,new int[]{alpha(coverTone,night?58:24),alpha(coverTone,0)},null,Shader.TileMode.CLAMP);}
 public synchronized void accept(JSONObject value){frame=value;received=SystemClock.elapsedRealtime();sequence++;
  JSONObject fields=frame.optJSONObject("fields"),power=fields==null?null:fields.optJSONObject("charge_discharge_power_raw");Double n=field("charge_discharge_power_raw",3500);long at=power==null?-1:power.optLong("readAtMs",-1);
  if(n!=null&&Math.abs(n)<=500&&at>=0&&at!=powerReadAt){powerReadAt=at;powerHistory[historyWrite]=n;historyWrite=(historyWrite+1)%powerHistory.length;historySize=Math.min(powerHistory.length,historySize+1);}
  if(!live()){historySize=historyWrite=0;powerReadAt=-1;}postInvalidate();describe();
 }
 public synchronized void navigation(RouteGuidanceSummary value){if(!navigation.equals(value)){navigation=value;sequence++;postInvalidate();}}
 public synchronized void map(Bitmap b,long seq){
  // A valid duplicate is a liveness sample, not a stale/disconnected map.
  if(b!=null&&map!=null&&seq==mapSequence){mapReceived=SystemClock.elapsedRealtime();if(b!=map)UltraMapBuffers.release(b);return;}
  mapReceived=SystemClock.elapsedRealtime();Bitmap old=map;map=b;mapSequence=seq;sequence++;
  if(b!=null&&!b.isRecycled()){
   if(glassMap==null){glassMap=Bitmap.createBitmap(GLASS_W,GLASS_H,Bitmap.Config.ARGB_8888);glassCanvas=new Canvas(glassMap);}
   resetPaint();dst.set(0,0,GLASS_W,GLASS_H);glassCanvas.drawBitmap(b,null,dst,paint);glassMap.getPixels(glassPixels,0,GLASS_W,0,0,GLASS_W,GLASS_H);UltraGlassBackdrop.frost(glassPixels,glassScratch,GLASS_W,GLASS_H);glassMap.setPixels(glassPixels,0,GLASS_W,0,0,GLASS_W,GLASS_H);
  }
  if(old!=null&&old!=b)UltraMapBuffers.release(old);postInvalidate();
 }
 public synchronized void media(String title,String artist,long position,long duration,boolean playing){
  title=title==null?"":title;artist=artist==null?"":artist;
  if(songTitle.equals(title)&&songArtist.equals(artist)&&songPosition==Math.max(0,position)&&songDuration==Math.max(0,duration)&&songPlaying==playing)return;
  boolean changed=!songTitle.equals(title)||!songArtist.equals(artist);
  sequence++;songTitle=title;songArtist=artist;songPosition=Math.max(0,position);songDuration=Math.max(0,duration);songPlaying=playing;
  // The current CarPlay bridge has metadata, not a lyrics source or stable media ID.
  // Never invent lyric text or word timing from the song title or total duration.
  lyrics.updateMedia(title.isEmpty()&&artist.isEmpty()?"":title+"\u0000"+artist,songPosition,songDuration,playing,SystemClock.elapsedRealtime());
  if(changed)musicRenderer.clear();scheduleLyrics();postInvalidate();
 }
 /** One atomic source snapshot; title/art/clock/lyrics never borrow from a different player. */
 public synchronized void media(UltraMusicSources.Snapshot media,long sourceGeneration,long now){
  if(media==null){sourceLyrics=java.util.Collections.emptyList();media("","",0,0,false);long prior=lyrics.generation();lyrics.updateClock("",0,0,false,0,now,false,now);if(prior!=lyrics.generation()){sequence++;musicRenderer.clear();postInvalidate();}scheduleLyrics();return;}
  long position=media.positionAt(now);boolean playing=media.advancing(now);
  boolean changed=!songTitle.equals(media.title)||!songArtist.equals(media.artist)||songPosition!=position||songDuration!=media.durationMs||songPlaying!=playing;
  songTitle=media.title;songArtist=media.artist;songPosition=position;songDuration=Math.max(0,media.durationMs);songPlaying=playing;
  long oldGeneration=lyrics.generation();
  lyrics.updateClock(sourceGeneration+":"+media.identity.key(),position,songDuration,playing,media.speed,
   media.observedMs>Long.MAX_VALUE-UltraMusicSources.MAX_AGE_MS?Long.MAX_VALUE:media.observedMs+UltraMusicSources.MAX_AGE_MS,media.clockKnown(now),now);
  boolean reset=oldGeneration!=lyrics.generation();
  boolean timelineChanged=!sourceLyrics.equals(media.lyrics);sourceLyrics=media.lyrics;
  // Invalid supplied timelines must clear old lyrics rather than preserve stale text for this item.
  if(!lyrics.accept(lyrics.generation(),media.lyrics,now))lyrics.accept(lyrics.generation(),java.util.Collections.emptyList(),now);
  if(changed||reset||timelineChanged){sequence++;musicRenderer.clear();postInvalidate();}scheduleLyrics();
 }
 /** Bind future authorized lyric requests to this generation; late results are rejected. */
 public synchronized long lyricsGeneration(){return lyrics.generation();}
 /** A future source must call this on a new item/session that happens to share the same metadata. */
 public synchronized long resetLyrics(){long generation=lyrics.resetLyrics();musicRenderer.clear();sequence++;scheduleLyrics();postInvalidate();return generation;}
 public synchronized boolean lyrics(long generation,java.util.List<UltraLyricsState.Line> lines){
  boolean accepted=lyrics.accept(generation,lines,SystemClock.elapsedRealtime());
  if(accepted){musicRenderer.clear();sequence++;scheduleLyrics();postInvalidate();}return accepted;
 }
 private boolean lyricUiVisible(){return card==2&&isAttachedToWindow()&&isShown()&&getWindowVisibility()==VISIBLE;}
 private void scheduleLyrics(){removeCallbacks(lyricPulse);if(lyricUiVisible()){long delay=lyrics.nextDelayMillis(SystemClock.elapsedRealtime(),true);if(delay>0)postDelayed(lyricPulse,delay);}}
 @Override protected void onAttachedToWindow(){super.onAttachedToWindow();synchronized(this){scheduleLyrics();}}
 @Override protected void onDetachedFromWindow(){synchronized(this){removeCallbacks(lyricPulse);lyrics.frame(SystemClock.elapsedRealtime(),false);}super.onDetachedFromWindow();}
 @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);synchronized(this){if(musicRenderer!=null)scheduleLyrics();}}
 @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);synchronized(this){if(musicRenderer!=null)scheduleLyrics();}}
 public synchronized void cover(Bitmap value){Bitmap old=cover;cover=value;if(old!=null&&old!=value)old.recycle();coverTone=0xff263d43;if(value!=null&&!value.isRecycled())coverTone=value.getPixel(value.getWidth()/2,value.getHeight()/2);rebuildGlow();sequence++;postInvalidate();}
 public synchronized int currentCard(){return card;}
 public synchronized void selectCard(int id){if(!UltraPreferences.available(id)||id==card)return;previousCard=card;card=id;cardChangedAt=SystemClock.elapsedRealtime();lyrics.frame(cardChangedAt,card==2);scheduleLyrics();sequence++;postInvalidate();describe();}
 public synchronized void nextCard(){selectCard(UltraPreferences.adjacentCard(card,1));}
 public synchronized void release(){removeCallbacks(lyricPulse);musicRenderer.clear();lyrics.updateMedia("",0,0,false,SystemClock.elapsedRealtime());if(map!=null){UltraMapBuffers.release(map);map=null;}if(glassMap!=null){glassMap.recycle();glassMap=null;}glassCanvas=null;cover(null);for(Bitmap[][] group:new Bitmap[][][]{digits,vehicle,gauge})for(Bitmap[] row:group)for(Bitmap b:row)if(b!=null&&!b.isRecycled())b.recycle();}
 private boolean mapFresh(){return map!=null&&!map.isRecycled()&&SystemClock.elapsedRealtime()-mapReceived<=2500;}
 private boolean live(){return UltraVehicleReadiness.live(frame.opt("schema"),frame.opt("source"),frame.opt("uid"),frame.opt("connected"),received,SystemClock.elapsedRealtime());}
 private JSONObject rawField(String key,long ttl){if(!live())return null;JSONObject fields=frame.optJSONObject("fields"),f=fields==null?null:fields.optJSONObject(key);if(f==null||!"returned".equals(f.opt("state")))return null;return UltraVehicleReadiness.validAge(f.opt("ageMs"),received,SystemClock.elapsedRealtime(),ttl)?f:null;}
 private UltraVehicleReadiness.Field readinessField(String key){JSONObject fields=frame.optJSONObject("fields"),f=fields==null?null:fields.optJSONObject(key);return f==null?null:new UltraVehicleReadiness.Field(f.opt("state"),f.opt("raw"),f.opt("ageMs"),f.opt("area"),f.opt("valueType"));}
 private Double field(String key,long ttl){JSONObject f=rawField(key,ttl);Object value=f==null?null:f.opt("raw");if(!(value instanceof Number))return null;double n=((Number)value).doubleValue();return Double.isFinite(n)?n:null;}
 private String value(String key,int places){Double n=field(key,"mileage_total_raw".equals(key)?6500:3500);return n==null||n<0||("mileage_total_raw".equals(key)&&n>9999999)||(("trip_since_start_raw".equals(key)||"trip_since_charge_raw".equals(key))&&n>99999)?"—":String.format(Locale.ROOT,"%."+places+"f",n);}
 private String soc(){Double n=field("soc",3000);return n!=null&&n>=0&&n<=100?Math.round(n)+"%":"—";}
 private String range(){Double mode=field("range_mode",3000),n=field("range_wltp",3000);return mode!=null&&mode==1&&n!=null&&n>=0&&n<1023?Math.round(n)+" km":"—";}
 private String gear(){Double n=field("gear",1000);return n==null?"—":n==1?"D":n==2?"N":n==3?"R":n==4?"P":"—";}
 private String pressure(String key){Double n=field(key,3500);return n!=null&&n>=0&&n<=450?String.format(Locale.ROOT,"%.1f",n/100):"—";}
 private String closureStatus(){
  JSONObject f=rawField("doors_raw",2000);JSONArray doors=f==null?null:f.optJSONArray("raw");
  Double bonnet=field("bonnet_raw",2000),trunk=field("trunk_opener_raw",2000);
  if(doors==null||doors.length()!=4||bonnet==null||trunk==null)return "—";
  boolean open=false;
  for(int i=0;i<4;i++){double n=doors.optDouble(i,Double.NaN);if(n!=0&&n!=1)return "—";open|=n==1;}
  if(bonnet<0||bonnet>3||bonnet!=Math.rint(bonnet)||trunk<1||trunk>10||trunk!=Math.rint(trunk))return "—";
  open|=bonnet!=0||trunk!=1;
  return open?"有未关闭项":"已关闭";
 }
 private String temperature(int wheel){JSONObject f=rawField("tpms_temperature_raw",3500);JSONArray a=f==null?null:f.optJSONArray("raw");if(a==null||a.length()!=4)return "—";double n=a.optDouble(wheel,Double.NaN);return Double.isFinite(n)&&n>=-40&&n<=125?Math.round(n)+"°C":"—";}
 public synchronized boolean projectionReady(){return UltraVehicleReadiness.ready(frame.opt("vehicleContract"),live(),mapFresh(),readinessField("speed_raw"),readinessField("gear"),received,SystemClock.elapsedRealtime());}
 public synchronized boolean parked(){Double n=field("gear",1000);return n!=null&&n==4;}
 public synchronized boolean nightMode(){return night;}
 public synchronized JSONObject display(){try{return new JSONObject().put("source","vehicle").put("valid",live()).put("projectionReady",projectionReady()).put("speedKmh",speedText()).put("soc",soc()).put("range",range()).put("gear",gear()).put("averageConsumption",averageConsumption()).put("sinceChargeKm",value("trip_since_charge_raw",1)).put("tripDurationMinutes",tripMinutes()).put("card",UltraPreferences.CARDS[card]).put("cardIndex",card).put("cardsSwapped",cardsSwapped).put("edgeBlendPercent",40).put("edgeBlendRevision",3).put("verticalColorWash",false).put("oemTopBandHeight",96).put("oemBottomBandHeight",60).put("theme",night?"night":"day").put("themeMode",themeMode).put("mapConnected",mapFresh()).put("mapSequence",mapSequence).put("sequence",sequence).put("srDrawn",false).put("coverAvailable",cover!=null&&!cover.isRecycled()).put("mediaMetadataPresent",!songTitle.isEmpty()).put("mediaPlaying",songPlaying).put("tpmsFL",pressure("tpms_fl_raw")).put("tpmsFR",pressure("tpms_fr_raw")).put("tpmsRL",pressure("tpms_rl_raw")).put("tpmsRR",pressure("tpms_rr_raw")).put("outsideInterface",outsideTemperature()).put("navigationActive",navigation.getActive()).put("destinationAvailable",navigation.getDestinationName()!=null).put("destinationStatus",com.shilapi.xcertplay.media.CarPlayRouteGuidanceView.destinationStatus()).put("remainingDistanceAvailable",navigation.getRemainingMeters()!=null).put("remainingTimeAvailable",navigation.getRemainingSeconds()!=null).put("arrivalAvailable",navigation.getArrivalEpochSeconds()!=null).put("powerHistoryPoints",historySize).put("closures",closureStatus()).put("turnTextAvailable",navigation.getTurnText()!=null).put("turnRoadAvailable",navigation.getTurnRoad()!=null).put("nextTurnAvailable",navigation.getNextTurnMeters()!=null);}catch(Exception ignored){return new JSONObject();}}
 /** Local settings only. Route and song text are intentionally absent from diagnostic exports. */
 public synchronized java.util.Map<String,String> readings(){
  java.util.Map<String,String> r=new java.util.LinkedHashMap<>();
  r.put("speed",withUnit(speedText(),"km/h"));r.put("gear",gear());r.put("soc",soc());Double range=field("range_wltp",3500);r.put("range",range!=null&&range>=0&&range<1023?String.format(Locale.ROOT,"%.0f km",range):"—");
  r.put("total",withUnit(value("mileage_total_raw",0),"km"));r.put("trip",withUnit(value("trip_since_start_raw",1),"km"));r.put("sinceCharge",withUnit(value("trip_since_charge_raw",1),"km"));r.put("duration",withUnit(tripMinutes(),"分钟"));r.put("average",withUnit(averageConsumption(),"kWh/100km"));
  Double power=field("charge_discharge_power_raw",3500);r.put("power",power!=null&&Math.abs(power)<=500?String.format(Locale.ROOT,"%.0f kW",power):"—");
  r.put("outside",outsideTemperature());
  String[] tyres={"tpms_fl_raw","tpms_fr_raw","tpms_rl_raw","tpms_rr_raw"};for(int i=0;i<4;i++){String p=pressure(tyres[i]),t=temperature(i);r.put("tyre"+i,("—".equals(p)?"—":p+" bar")+" · "+t);r.put("door"+i,door(i));}
  Double bonnet=field("bonnet_raw",2000);r.put("bonnet",bonnet==null?"—":bonnet==0?"已关闭":bonnet>=1&&bonnet<=3&&bonnet==Math.rint(bonnet)?"未关闭":"—");
  r.put("destination",navigation.getActive()?(navigation.getDestinationName()==null?"正在导航":navigation.getDestinationName()):"未开始导航");
  Long m=navigation.getRemainingMeters(),seconds=navigation.getRemainingSeconds(),eta=navigation.getArrivalEpochSeconds(),turn=navigation.getNextTurnMeters();
  r.put("distance",navigation.getActive()&&m!=null?distance(m):"—");r.put("remaining",navigation.getActive()&&seconds!=null?(seconds+59)/60+" 分钟":"—");r.put("arrival",navigation.getActive()&&eta!=null?new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(eta*1000)):"—");r.put("nextTurn",navigation.getActive()&&turn!=null?distance(turn):"—");
  r.put("song",songTitle.isEmpty()?"等待音乐":songTitle);r.put("artist",songArtist.isEmpty()?"—":songArtist);r.put("progress",songDuration>0?time(songPosition)+" / "+time(songDuration):"—");r.put("cover",cover!=null&&!cover.isRecycled()?"已接收":"—");return r;
 }
 private String outsideTemperature(){Double n=field("outside_temperature_raw",3500);return n!=null&&n>=-60&&n<=100?String.format(Locale.ROOT,"%.0f °C",n):"—";}
 private String door(int wheel){JSONObject f=rawField("doors_raw",2000);JSONArray a=f==null?null:f.optJSONArray("raw");if(a==null||a.length()!=4)return "—";double n=a.optDouble(wheel,Double.NaN);return n==0?"已关闭":n==1?"未关闭":"—";}
 private static String withUnit(String n,String unit){return "—".equals(n)?n:n+" "+unit;}
 private static String distance(long m){return m<1000?m+" m":String.format(Locale.ROOT,"%.1f km",m/1000d);}
 private void describe(){String text="仪表；电量 "+soc()+"，WLTP "+range()+"，挡位 "+gear()+"；"+UltraPreferences.CARDS[card]+"卡片；"+(mapFresh()?"CarPlay独立地图":"等待地图");if(!text.equals(description)){description=text;setContentDescription(text);}}
 @Override protected void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);refreshTheme();}
 @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float scale=Math.min(getWidth()/1920f,getHeight()/720f);canvas.save();canvas.translate((getWidth()-1920*scale)/2,(getHeight()-720*scale)/2);canvas.scale(scale,scale);drawDashboard(canvas);canvas.restore();}
 public synchronized void drawDashboard(Canvas canvas){canvas.drawColor(base);resetPaint();if(mapFresh())drawMap(canvas,map);
  // Native receiver owns the 40% vertical OEM/map blend. A second vertical
  // color wash here would hide the map again and diverge from the HTML design.
  // Keep v0.6.3's lateral fades and glass cards exactly as before.
  shade(canvas,leftFade,0,0,560,720);shade(canvas,rightFade,1300,0,1920,720);
  if(!mapFresh()){centerText(canvas,"等待 CarPlay 地图",960,355,27,muted,ui);centerText(canvas,"连接 iPhone 后自动显示",960,394,19,muted,ui);}
  float speedX=UltraCardLayout.speedX(cardsSwapped),infoX=cardX();
  glass(canvas,speedX,false);canvas.save();canvas.translate(speedX-LEFT,0);drawLeft(canvas);canvas.restore();
  glass(canvas,infoX,card==2);canvas.save();canvas.translate(infoX-RIGHT,0);float t=Math.min(1,Math.max(0,SystemClock.elapsedRealtime()-cardChangedAt)/CARD_ANIMATION_MS);t=t*t*(3-2*t);
  if(t<1&&previousCard!=card){int save=canvas.saveLayerAlpha(RIGHT,TOP,RIGHT+CARD_W,TOP+CARD_H,Math.round(255*(1-t)));drawCard(canvas,previousCard);canvas.restoreToCount(save);save=canvas.saveLayerAlpha(RIGHT,TOP,RIGHT+CARD_W,TOP+CARD_H,Math.round(255*t));drawCard(canvas,card);canvas.restoreToCount(save);if(isAttachedToWindow())postInvalidateOnAnimation();}else drawCard(canvas,card);
  canvas.restore();
 }
 private float cardX(){return UltraCardLayout.infoX(cardsSwapped);}
 private void drawMap(Canvas canvas,Bitmap bitmap){float s=Math.max(1920f/bitmap.getWidth(),720f/bitmap.getHeight()),w=bitmap.getWidth()*s,h=bitmap.getHeight()*s;dst.set((1920-w)/2,(720-h)/2,(1920+w)/2,(720+h)/2);resetPaint();canvas.drawBitmap(bitmap,null,dst,paint);}
 private void glass(Canvas canvas,float x,boolean music){canvas.save();path.reset();dst.set(x,TOP,x+CARD_W,TOP+CARD_H);path.addRoundRect(dst,26,26,Path.Direction.CW);canvas.clipPath(path);
  if(mapFresh()&&glassMap!=null){drawMap(canvas,glassMap);shade(canvas,leftFade,0,0,560,720);shade(canvas,rightFade,1300,0,1920,720);shade(canvas,glassTopFade,0,0,1920,226);shade(canvas,glassBottomFade,0,530,1920,720);}plateMatrix.setTranslate(x,0);plate.setLocalMatrix(plateMatrix);shade(canvas,plate,x,TOP,x+CARD_W,TOP+CARD_H);if(music)shade(canvas,coverGlow,x,TOP,x+CARD_W,TOP+CARD_H);canvas.restore();
  resetPaint();paint.setColor(night?0x12e1f1fb:0x50ffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);dst.set(x+.5f,TOP+.5f,x+CARD_W-.5f,TOP+CARD_H-.5f);canvas.drawRoundRect(dst,26,26,paint);paint.setStyle(Paint.Style.FILL);
 }
 private String speedText(){Double speed=field("speed_raw",750);return speed!=null&&speed>=0&&speed<=300?Long.toString(Math.round(speed)):"—";}
 private void drawLeft(Canvas canvas){String speed=speedText();float x=LEFT+30,numberWidth="—".equals(speed)?92:speed.length()*88+8;
  if(!"—".equals(speed)){for(int i=0;i<speed.length();i++){Bitmap b=digits[night?1:0][speed.charAt(i)-'0'];if(b!=null)bitmap(canvas,b,x+i*88,TOP+26,96,120);else text(canvas,speed.substring(i,i+1),x+i*88,TOP+146,144,ink,number);}}else text(canvas,"—",x,TOP+137,126,ink,number);
  text(canvas,"km/h",x+numberWidth+18,TOP+124,20,muted,ui);
  // OEM digit assets have visible ink through row80 of90; align unit ink,
  // rather than its font baseline, to the bottom of the 120px speed glyph.
  String energyUnit="kWh/100km";resetPaint();paint.setTypeface(ui);paint.setTextSize(12);paint.getTextBounds(energyUnit,0,energyUnit.length(),src);
  float energyOffset=26+120*(80f/90f)-src.bottom-120;
  float energyRight=LEFT+454;rightText(canvas,"平均能耗",energyRight,TOP+48+energyOffset,16,muted,ui);rightText(canvas,averageConsumption(),energyRight,TOP+91+energyOffset,29,ink,bold);rightText(canvas,energyUnit,energyRight,TOP+120+energyOffset,12,muted,ui);
  float tripY=TOP+228;stroke(canvas,x,tripY-39,LEFT+450,tripY-39,line);
  metric(canvas,"本次行程",value("trip_since_start_raw",1),"km",x,tripY,130);String minutes=tripMinutes();
  metric(canvas,"行程时间",minutes,"分钟",x+144,tripY,130);metric(canvas,"充电后里程",value("trip_since_charge_raw",1),"km",x+288,tripY,132);stroke(canvas,x,TOP+313,LEFT+450,TOP+313,line);
  float navY=TOP+347;drawTurnIcon(canvas,x+9,navY-9);fitted(canvas,turnInstruction(),x+32,navY,382,22,ink,bold);
  String distance="—",unit="km";Long meters=navigation.getRemainingMeters(),seconds=navigation.getRemainingSeconds();if(navigation.getActive()&&meters!=null){if(meters<1000){distance=meters.toString();unit="m";}else distance=String.format(Locale.ROOT,"%.1f",meters/1000d);}
  metric(canvas,"到目的地",distance,unit,x,TOP+386,190,36);String remaining=seconds==null?"—":Long.toString((seconds+59)/60);metric(canvas,"剩余时间",navigation.getActive()?remaining:"—","分钟",x+220,TOP+386,190,36);
  text(canvas,"预计到达",x,TOP+484,17,muted,ui);String eta="—";Long epoch=navigation.getArrivalEpochSeconds();if(navigation.getActive()&&epoch!=null)eta=new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(epoch*1000));text(canvas,eta,x+88,TOP+484,20,ink,bold);
 }
 private String turnInstruction(){
  if(!navigation.getActive())return "未开始导航";
  String turn=navigation.getTurnText(),road=navigation.getTurnRoad();Long meters=navigation.getNextTurnMeters();
  if(navigation.getTurnIcon()!=null){
   String label=meters==null?"前方":distance(meters);
   if(navigation.getTurnIcon()==RouteTurnIcon.ROUNDABOUT&&turn!=null&&turn.startsWith("环岛第"))label+=" · "+turn.substring(2);
   if(navigation.getTurnIcon()==RouteTurnIcon.ARRIVAL&&road==null)label+=" · 目的地";
   return label+(road==null?"":" · "+road);
  }
  String prefix=meters==null?"":distance(meters)+" 后";
  if(turn!=null)return prefix+turn+(road==null?"":" · "+road);
  return meters==null?"正在导航":distance(meters)+" 后下一步";
 }
 private void drawTurnIcon(Canvas canvas,float x,float y){
  RouteTurnIcon icon=navigation.getActive()?navigation.getTurnIcon():null;
  if(icon==null){pin(canvas,x,y);return;}
  canvas.save();canvas.translate(x-13,y-13);resetPaint();paint.setColor(accent);
  paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);path.reset();
  boolean mirror=icon==RouteTurnIcon.LEFT||icon==RouteTurnIcon.SLIGHT_LEFT||icon==RouteTurnIcon.SHARP_LEFT||icon==RouteTurnIcon.U_TURN_RIGHT;
  if(mirror)canvas.scale(-1,1,13,13);
  switch(icon){
   case LEFT:case RIGHT:
    path.moveTo(5,25);path.lineTo(5,13);path.quadTo(5,6,12,6);path.lineTo(24,6);path.moveTo(18,0);path.lineTo(24,6);path.lineTo(18,12);break;
   case SLIGHT_LEFT:case SLIGHT_RIGHT:
    path.moveTo(5,25);path.lineTo(5,19);path.quadTo(5,15,9,11);path.lineTo(23,1);path.moveTo(13,1);path.lineTo(23,1);path.lineTo(23,11);break;
   case SHARP_LEFT:case SHARP_RIGHT:
    path.moveTo(5,25);path.lineTo(5,10);path.cubicTo(5,1,9,0,16,5);path.lineTo(24,12);path.moveTo(22,3);path.lineTo(24,12);path.lineTo(15,13);break;
   case U_TURN_LEFT:case U_TURN_RIGHT:
    path.moveTo(22,25);path.lineTo(22,9);path.cubicTo(22,-1,5,-1,5,9);path.lineTo(5,21);path.moveTo(0,16);path.lineTo(5,21);path.lineTo(10,16);break;
   case ROUNDABOUT:
    canvas.drawCircle(12,13,7,paint);path.moveTo(12,26);path.lineTo(12,20);path.moveTo(19,13);path.lineTo(26,13);path.moveTo(22,9);path.lineTo(26,13);path.lineTo(22,17);break;
   case ARRIVAL:
    canvas.drawCircle(13,13,10,paint);canvas.drawCircle(13,13,3,paint);break;
   default:
    path.moveTo(13,26);path.lineTo(13,1);path.moveTo(5,9);path.lineTo(13,1);path.lineTo(21,9);break;
  }
  canvas.drawPath(path,paint);canvas.restore();paint.setStrokeCap(Paint.Cap.BUTT);paint.setStrokeJoin(Paint.Join.MITER);paint.setStyle(Paint.Style.FILL);
 }
 private void metric(Canvas canvas,String label,String value,String unit,float x,float y,float width){metric(canvas,label,value,unit,x,y,width,28);}
 private void metric(Canvas canvas,String label,String value,String unit,float x,float y,float width,float size){text(canvas,label,x,y,17,muted,ui);float rawWidth=measure(value,bold,size),unitWidth=measure(unit,ui,14);float fit=Math.max(18,Math.min(size,(width-unitWidth-6)/Math.max(1,rawWidth)*size));text(canvas,value,x,y+39,fit,ink,bold);float w=measure(value,bold,fit);text(canvas,unit,x+w+6,y+39,14,muted,ui);}
 private void drawCard(Canvas canvas,int type){if(type==2)drawMusic(canvas);else if(type==0)drawVehicle(canvas);else drawEnergy(canvas);}
 private void drawMusic(Canvas canvas){
  canvas.save();canvas.translate(RIGHT,TOP);
  musicRenderer.draw(canvas,cover,songTitle,songArtist,songPosition,songDuration,lyrics.frame(SystemClock.elapsedRealtime(),card==2),ink,muted,line,accent);
  canvas.restore();
 }
 private void drawVehicle(Canvas canvas){text(canvas,"车辆信息",RIGHT+32,TOP+44,19,muted,ui);int t=night?1:0;float carX=RIGHT+110,carY=TOP+106,scale=.565f;bitmap(canvas,vehicle[t][0],carX,carY,460*scale,374*scale);
  // These original closed-door pieces are decorative body detail, not live door-state assertions.
  for(int side=0;side<2;side++)for(int door=0;door<2;door++){float dx=carX+(side==0?145:278)*scale,dy=carY+(door==0?110:199)*scale;canvas.save();if(side==1)canvas.scale(-1,1,dx+18.5f*scale,dy);bitmap(canvas,vehicle[t][door+1],dx,dy,37*scale,(door==0?101:107)*scale);canvas.restore();}
  String[] names={"tpms_fl_raw","tpms_fr_raw","tpms_rl_raw","tpms_rr_raw"};for(int i=0;i<4;i++){float x=RIGHT+(i%2==0?46:368),y=TOP+(i<2?135:345);text(canvas,pressure(names[i]),x,y,38,ink,bold);text(canvas,"bar",x,y+25,17,muted,ui);text(canvas,temperature(i),x,y+51,17,muted,ui);}
  stroke(canvas,RIGHT+32,TOP+439,RIGHT+448,TOP+439,line);text(canvas,"车门与盖板",RIGHT+34,TOP+478,18,muted,ui);rightText(canvas,closureStatus(),RIGHT+446,TOP+478,18,"有未关闭项".equals(closureStatus())?0xffe4ab6f:muted,ui);
 }
 private String tripMinutes(){Double n=field("trip_duration_seconds_raw",UltraStartupTrip.TTL_MS);return n!=null&&n>=0&&n<=604800?Long.toString(Math.round(n/60)):"—";}
 private String averageConsumption(){Double avg=field("average_consumption_raw",3500);return avg!=null&&avg>=0&&avg<=100?String.format(Locale.ROOT,"%.1f",avg):"—";}
 private void drawEnergy(Canvas canvas){text(canvas,"能耗",RIGHT+32,TOP+44,19,muted,ui);int t=night?1:0;float gx=RIGHT+42,gy=TOP+92,gw=33,gh=337;bitmap(canvas,gauge[t][0],gx,gy,gw,gh);bitmap(canvas,gauge[t][1],gx,gy,gw,gh);
  Double power=field("charge_discharge_power_raw",3500);boolean validPower=power!=null&&Math.abs(power)<=500;if(validPower){canvas.save();float height=Math.min(gh/2,(float)Math.abs(power)*gh/200);canvas.clipRect(gx,gy+gh/2-height,gx+gw,gy+gh/2);bitmap(canvas,gauge[t][2],gx,gy,gw,gh);canvas.restore();}
  text(canvas,"功率",RIGHT+38,TOP+74,16,muted,ui);text(canvas,"kW",RIGHT+40,TOP+459,16,muted,ui);float x=RIGHT+126,end=RIGHT+446;Double avg=field("average_consumption_raw",3500);String average=avg!=null&&avg>=0&&avg<=100?String.format(Locale.ROOT,"%.1f",avg):"—";
  text(canvas,"平均能耗",x,TOP+102,23,muted,ui);text(canvas,average,x,TOP+193,77,ink,number);text(canvas,"kWh/100km",x,TOP+226,18,muted,ui);text(canvas,"实时电池功率",x,TOP+274,16,muted,ui);stroke(canvas,x,TOP+341,end,TOP+341,line);
  if(historySize>1){double max=20;for(int i=0;i<historySize;i++)max=Math.max(max,Math.abs(powerHistory[i]));path.reset();for(int i=0;i<historySize;i++){int at=(historyWrite-historySize+i+powerHistory.length)%powerHistory.length;float px=x+(end-x)*i/(historySize-1),py=TOP+341-(float)(powerHistory[at]/max)*45;if(i==0)path.moveTo(px,py);else path.lineTo(px,py);}resetPaint();paint.setColor(accent);paint.setStrokeWidth(2.5f);paint.setStyle(Paint.Style.STROKE);canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);}
  else centerText(canvas,validPower?"正在记录":"等待车辆数据",(x+end)/2,TOP+340,18,muted,ui);
  text(canvas,"较早",x,TOP+404,15,muted,ui);rightText(canvas,"当前",end,TOP+404,15,muted,ui);stroke(canvas,x,TOP+439,end,TOP+439,line);text(canvas,"电池功率",x,TOP+478,18,muted,ui);rightText(canvas,(validPower?String.format(Locale.ROOT,"%.0f",power):"—")+" kW",end,TOP+478,25,ink,bold);
 }
 private void resetPaint(){paint.setShader(null);paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);paint.clearShadowLayer();paint.setTextScaleX(1);paint.setLetterSpacing(0);}
 private void shade(Canvas canvas,Shader shader,float l,float t,float r,float b){resetPaint();paint.setShader(shader);canvas.drawRect(l,t,r,b,paint);paint.setShader(null);}
 private void text(Canvas canvas,String value,float x,float y,float size,int color,Typeface face){resetPaint();paint.setColor(color);paint.setTextSize(size);paint.setTypeface(face);canvas.drawText(value,x,y,paint);}
 private float measure(String value,Typeface face,float size){paint.setTypeface(face);paint.setTextSize(size);return paint.measureText(value);}
 private void fitted(Canvas canvas,String value,float x,float y,float width,float size,int color,Typeface face){resetPaint();paint.setTypeface(face);paint.setTextSize(size);text(canvas,TextUtils.ellipsize(value,paint,width,TextUtils.TruncateAt.END).toString(),x,y,size,color,face);}
 private void centerFitted(Canvas canvas,String value,float x,float y,float width,float size,int color,Typeface face){resetPaint();paint.setTypeface(face);paint.setTextSize(size);String shown=TextUtils.ellipsize(value,paint,width,TextUtils.TruncateAt.END).toString();centerText(canvas,shown,x,y,size,color,face);}
 private void centerText(Canvas canvas,String value,float x,float y,float size,int color,Typeface face){text(canvas,value,x-measure(value,face,size)/2,y,size,color,face);}
 private void rightText(Canvas canvas,String value,float x,float y,float size,int color,Typeface face){text(canvas,value,x-measure(value,face,size),y,size,color,face);}
 private void round(Canvas canvas,float l,float t,float r,float b,float radius,int color){resetPaint();paint.setColor(color);dst.set(l,t,r,b);canvas.drawRoundRect(dst,radius,radius,paint);}
 private void stroke(Canvas canvas,float x,float y,float x2,float y2,int color){resetPaint();paint.setColor(color);paint.setStrokeWidth(1);canvas.drawLine(x,y,x2,y2,paint);}
 private void bitmap(Canvas canvas,Bitmap b,float x,float y,float w,float h){if(b==null||b.isRecycled())return;resetPaint();dst.set(x,y,x+w,y+h);canvas.drawBitmap(b,null,dst,paint);}
 private void pin(Canvas canvas,float x,float y){resetPaint();paint.setColor(accent);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);path.reset();path.moveTo(x,y+12);path.cubicTo(x-17,y-2,x-10,y-15,x,y-15);path.cubicTo(x+10,y-15,x+17,y-2,x,y+12);canvas.drawPath(path,paint);canvas.drawCircle(x,y-5,3.5f,paint);paint.setStyle(Paint.Style.FILL);}
 private static String time(long ms){return Math.max(0,ms)/60000+":"+String.format(Locale.ROOT,"%02d",Math.max(0,ms)/1000%60);}
 @Override public boolean onTouchEvent(MotionEvent e){float s=Math.min(getWidth()/1920f,getHeight()/720f),ox=(getWidth()-1920*s)/2,oy=(getHeight()-720*s)/2;float x=(e.getX()-ox)/s,y=(e.getY()-oy)/s;if(e.getAction()==MotionEvent.ACTION_DOWN){downX=x;downY=y;return true;}if(e.getAction()==MotionEvent.ACTION_UP){if(UltraCardLayout.isInfoCard(cardsSwapped,downX,downY)){int delta=UltraCardLayout.swipeDelta(cardsSwapped,downX,x);selectCard(UltraPreferences.adjacentCard(card,delta));performClick();}return true;}return true;}
 @Override public boolean performClick(){super.performClick();return true;}
}
