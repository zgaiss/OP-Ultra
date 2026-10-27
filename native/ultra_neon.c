#include <arm_neon.h>
void ultra_copy_neon(void*to,const void*from,unsigned bytes){
 unsigned char*d=to;const unsigned char*s=from;unsigned i=0;
 for(;i+64<=bytes;i+=64){uint8x16_t a=vld1q_u8(s+i),b=vld1q_u8(s+i+16),c=vld1q_u8(s+i+32),e=vld1q_u8(s+i+48);vst1q_u8(d+i,a);vst1q_u8(d+i+16,b);vst1q_u8(d+i+32,c);vst1q_u8(d+i+48,e);}
 for(;i<bytes;i++)d[i]=s[i];
}
void ultra_opaque_neon(unsigned short*p,unsigned count){
 uint16x8_t one=vdupq_n_u16(1);unsigned i=0;for(;i+8<=count;i+=8)vst1q_u16(p+i,vmaxq_u16(vld1q_u16(p+i),one));for(;i<count;i++)if(!p[i])p[i]=1;
}
/* Only reached after the Linux ELF HWCAP_NEON test. Keep the scalar ELF entry and recovery path ARMv7 compatible. */
void ultra_blend_band_neon(unsigned short*dst,const unsigned*src,unsigned width,unsigned alpha,unsigned y){
 static const unsigned short dither[16]={8,136,40,168,200,72,232,104,56,184,24,152,248,120,216,88};
 unsigned short row[8];for(unsigned i=0;i<8;i++)row[i]=dither[(y&3)*4+(i&3)];
 uint16x8_t a=vdupq_n_u16((unsigned short)alpha),b=vdupq_n_u16((unsigned short)(256-alpha)),d=vld1q_u16(row),mask31=vdupq_n_u16(31),mask63=vdupq_n_u16(63),one=vdupq_n_u16(1);
 for(unsigned x=0;x<width;x+=8){
  uint32x4_t l=vld1q_u32(src+x),h=vld1q_u32(src+x+4);uint16x8_t f=vld1q_u16(dst+x);
  uint16x8_t br=vandq_u16(vcombine_u16(vmovn_u32(vshrq_n_u32(l,19)),vmovn_u32(vshrq_n_u32(h,19))),mask31);
  uint16x8_t bg=vandq_u16(vcombine_u16(vmovn_u32(vshrq_n_u32(l,10)),vmovn_u32(vshrq_n_u32(h,10))),mask63);
  uint16x8_t bb=vandq_u16(vcombine_u16(vmovn_u32(vshrq_n_u32(l,3)),vmovn_u32(vshrq_n_u32(h,3))),mask31);
  uint16x8_t r=vshrq_n_u16(vaddq_u16(vaddq_u16(vmulq_u16(vshrq_n_u16(f,11),a),vmulq_u16(br,b)),d),8);
  uint16x8_t g=vshrq_n_u16(vaddq_u16(vaddq_u16(vmulq_u16(vandq_u16(vshrq_n_u16(f,5),mask63),a),vmulq_u16(bg,b)),d),8);
  uint16x8_t blue=vshrq_n_u16(vaddq_u16(vaddq_u16(vmulq_u16(vandq_u16(f,mask31),a),vmulq_u16(bb,b)),d),8);
  uint16x8_t result=vorrq_u16(vorrq_u16(vshlq_n_u16(r,11),vshlq_n_u16(g,5)),blue);
  vst1q_u16(dst+x,vmaxq_u16(result,one));
 }
}
