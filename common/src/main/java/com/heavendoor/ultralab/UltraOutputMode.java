package com.heavendoor.ultralab;

/** Persisted user choice; exactly one instrument writer may be active. */
public final class UltraOutputMode {
 public static final int OFF=0,ULTRA=1,MAP=2;
 public static int checked(int mode){if(mode<OFF||mode>MAP)throw new IllegalArgumentException("output mode");return mode;}
 private UltraOutputMode(){}
}
