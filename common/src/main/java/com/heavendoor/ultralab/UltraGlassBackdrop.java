package com.heavendoor.ultralab;

/** Soft colour continuation for a glass plate when the phone supplies black safe-area
 * padding. Operates only on its tiny decorative blur buffer, never the actual map.
 */
public final class UltraGlassBackdrop {
 private UltraGlassBackdrop(){}
 private static boolean black(int c){return ((c>>16)&255)<=4&&((c>>8)&255)<=4&&(c&255)<=4;}
 private static boolean padding(int[] p,int w,int h,int from,int to){
  int total=0,dark=0;for(int y=h/6;y<h-h/6;y++)for(int x=from;x<to;x++){total++;if(black(p[y*w+x]))dark++;}
  return total>0&&dark*1000>=total*995;
 }
 private static boolean mapColumn(int[] p,int w,int h,int x){
  int lit=0;for(int y=h/6;y<h-h/6;y++)if(!black(p[y*w+x]))lit++;
  return lit>=(h-2*(h/6))/2;
 }
 public static void softenPadding(int[] p,int w,int h){
  if(w<8||h<6||p.length!=w*h)return;int span=w/4;
  boolean left=padding(p,w,h,0,span),right=padding(p,w,h,w-span,w);
  if(left){int donor=span;while(donor<w/2&&!mapColumn(p,w,h,donor))donor++;
   if(donor<w/2)for(int y=0;y<h;y++)for(int x=0;x<donor;x++)if(black(p[y*w+x]))p[y*w+x]=p[y*w+donor];}
  if(right){int donor=w-span-1;while(donor>=w/2&&!mapColumn(p,w,h,donor))donor--;
   if(donor>=w/2)for(int y=0;y<h;y++)for(int x=donor+1;x<w;x++)if(black(p[y*w+x]))p[y*w+x]=p[y*w+donor];}
 }
 /** Three separable box passes approximate a Gaussian on the decorative buffer.
  * Caller-owned work arrays avoid allocations and RGB565 intermediate banding. */
 public static void frost(int[] pixels,int[] scratch,int w,int h){
  if(w<8||h<6||pixels.length!=w*h||scratch.length!=pixels.length||pixels==scratch)throw new IllegalArgumentException();
  softenPadding(pixels,w,h);
  for(int pass=0;pass<3;pass++){
   blur(pixels,scratch,w,h,true);
   blur(scratch,pixels,w,h,false);
  }
 }
 private static void blur(int[] in,int[] out,int w,int h,boolean horizontal){
  final int radius=3,divisor=radius*2+1;
  int length=horizontal?w:h,lines=horizontal?h:w,step=horizontal?1:w;
  for(int line=0;line<lines;line++){
   int start=horizontal?line*w:line,r=0,g=0,b=0;
   for(int k=-radius;k<=radius;k++){int c=in[start+Math.max(0,Math.min(length-1,k))*step];r+=(c>>>16)&255;g+=(c>>>8)&255;b+=c&255;}
   for(int i=0;i<length;i++){
    out[start+i*step]=0xff000000|((r+divisor/2)/divisor<<16)|((g+divisor/2)/divisor<<8)|(b+divisor/2)/divisor;
    int before=in[start+Math.max(0,i-radius)*step],after=in[start+Math.min(length-1,i+radius+1)*step];
    r+=((after>>>16)&255)-((before>>>16)&255);g+=((after>>>8)&255)-((before>>>8)&255);b+=(after&255)-(before&255);
   }
  }
 }
}
