package com.heavendoor.ultralab;
import android.content.Context;
/** Display-only choices; never affect the phone session or map negotiation. */
public final class UltraPreferences {
 public static int outputMode(Context c){int fallback=c.getSharedPreferences("p7-instrument",0).getBoolean("enabled",true)?UltraOutputMode.ULTRA:UltraOutputMode.OFF;int n=c.getSharedPreferences("op-ultra-display",0).getInt("output_mode",fallback);return n>=0&&n<=2?n:fallback;}
 public static void saveOutputMode(Context c,int n){UltraOutputMode.checked(n);c.getSharedPreferences("op-ultra-display",0).edit().putInt("output_mode",n).apply();}
 public static final String[] CARDS={"车辆信息","行程","音乐","能耗"};
 public static final int[] CARD_ORDER={2,0,3};
 public static boolean available(int id){for(int n:CARD_ORDER)if(n==id)return true;return false;}
 public static int cardPosition(int id){for(int i=0;i<CARD_ORDER.length;i++)if(CARD_ORDER[i]==id)return i;return 0;}
 public static int adjacentCard(int id,int delta){int n=CARD_ORDER.length;return CARD_ORDER[(cardPosition(id)+delta%n+n)%n];}
 public static int defaultCard(Context c){int n=c.getSharedPreferences("op-ultra-display",0).getInt("default_card",2);return available(n)?n:2;}
 public static void saveDefaultCard(Context c,int n){if(!available(n))throw new IllegalArgumentException();c.getSharedPreferences("op-ultra-display",0).edit().putInt("default_card",n).apply();}
 public static boolean cardsSwapped(Context c){return c.getSharedPreferences("op-ultra-display",0).getBoolean("cards_swapped",false);}
 public static void saveCardsSwapped(Context c,boolean swapped){c.getSharedPreferences("op-ultra-display",0).edit().putBoolean("cards_swapped",swapped).apply();}
 public static int themeMode(Context c){int n=c.getSharedPreferences("op-ultra-display",0).getInt("theme_mode",0);return n>=0&&n<=2?n:0;}
 public static void saveThemeMode(Context c,int n){if(n<0||n>2)throw new IllegalArgumentException();c.getSharedPreferences("op-ultra-display",0).edit().putInt("theme_mode",n).apply();}
 public static boolean sessionEnabled(Context c){return c.getSharedPreferences("op-ultra-display",0).getBoolean("session_enabled",false);}
 public static void sessionEnabled(Context c,boolean value){if(sessionEnabled(c)!=value)c.getSharedPreferences("op-ultra-display",0).edit().putBoolean("session_enabled",value).commit();}
}
