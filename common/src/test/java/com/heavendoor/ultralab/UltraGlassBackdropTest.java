package com.heavendoor.ultralab;
import org.junit.Test;import java.util.Arrays;import static org.junit.Assert.*;
public class UltraGlassBackdropTest {
 @Test public void blackSafeAreaUsesNearbyMapColour(){int[] p=new int[80*30];Arrays.fill(p,0xff000000);for(int y=0;y<30;y++)for(int x=27;x<56;x++)p[y*80+x]=0xff263e52;UltraGlassBackdrop.softenPadding(p,80,30);assertEquals(0xff263e52,p[15*80+8]);assertEquals(0xff263e52,p[15*80+73]);assertEquals(0xff263e52,p[15*80+40]);}
 @Test public void actualMapBackgroundIsUntouched(){int[] p=new int[80*30];for(int i=0;i<p.length;i++)p[i]=0xff080c10+i%255;int[] expected=p.clone();UltraGlassBackdrop.softenPadding(p,80,30);assertArrayEquals(expected,p);}
 @Test public void allBlackHasNoInventedMap(){int[] p=new int[80*30];Arrays.fill(p,0xff000000);int[] expected=p.clone();UltraGlassBackdrop.softenPadding(p,80,30);assertArrayEquals(expected,p);}
 @Test public void oneSideAndOriginalFeaturesPreserved(){int[] p=new int[80*30];Arrays.fill(p,0xff263e52);for(int y=0;y<30;y++)Arrays.fill(p,y*80,y*80+26,0xff000000);p[3]=0xffeeeeee;int[] expected=p.clone();UltraGlassBackdrop.softenPadding(p,80,30);assertEquals(expected[3],p[3]);for(int y=0;y<30;y++)for(int x=27;x<80;x++)assertEquals(expected[y*80+x],p[y*80+x]);}
 @Test public void frostedConstantStaysConstantIncludingEdges(){int[] p=new int[160*60],s=new int[p.length];Arrays.fill(p,0xff263e52);UltraGlassBackdrop.frost(p,s,160,60);for(int c:p)assertEquals(0xff263e52,c);}
 @Test public void sharpCheckerboardBecomesSmoothInsteadOfEnlargedBlocks(){int[] p=new int[160*60],s=new int[p.length];for(int y=0;y<60;y++)for(int x=0;x<160;x++)p[y*160+x]=((x/2+y/2)&1)==0?0xffeeeeee:0xff202020;UltraGlassBackdrop.frost(p,s,160,60);int max=0;for(int y=12;y<48;y++)for(int x=12;x<148;x++)max=Math.max(max,Math.abs((p[y*160+x]&255)-(p[y*160+x-1]&255)));assertTrue(max<=2);}
 @Test public void blurMixesColoursWithout565IntermediateQuantisation(){int[] p=new int[160*60],s=new int[p.length];for(int y=0;y<60;y++)for(int x=0;x<160;x++)p[y*160+x]=x<80?0xff203040:0xffa0b0c0;UltraGlassBackdrop.frost(p,s,160,60);int mid=p[30*160+80];assertTrue((mid&255)>64&&(mid&255)<192);assertNotEquals(p[30*160+76],p[30*160+80]);}
 @Test(expected=IllegalArgumentException.class) public void scratchMustBeIndependent(){int[] p=new int[160*60];UltraGlassBackdrop.frost(p,p,160,60);}
}
