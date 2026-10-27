#include "ultra_tile_decode.h"
#include "lz4/lz4.h"
static unsigned get32(const unsigned char*p){return(unsigned)p[0]|((unsigned)p[1]<<8)|((unsigned)p[2]<<16)|((unsigned)p[3]<<24);}
static unsigned get16(const unsigned char*p){return(unsigned)p[0]|((unsigned)p[1]<<8);}
int ultra_tiles_apply(const unsigned char*in,unsigned size,unsigned char*raw,int previous,UltraTileUpdate*out){
 if(size<16||size>ULTRA_MAX_PACKET||get32(in)!=0x31504655U)return 1;
 unsigned id=get32(in+4),base=get32(in+8),count=get32(in+12),key=base==0xffffffffU,pos=16;
 if(id>0x7fffffffU||count>360||(key?count!=1:previous<0||base!=(unsigned)previous||id<=base))return 1;
 unsigned seen[12]={0};unsigned char tile[8192];
 for(unsigned i=0;i<count;i++){
  if(size-pos<12)return 1;
  unsigned x=get16(in+pos),y=get16(in+pos+2),w=get16(in+pos+4),h=get16(in+pos+6),encoded=get32(in+pos+8);pos+=12;
  unsigned stored=encoded&0x7fffffffU,plain=w*h*2U,is_raw=encoded>>31;
  if(!w||!h||x>=1920||y>=720||w>1920-x||h>720-y||!stored||stored>size-pos)return 1;
  if(key){if(x||y||w!=1920||h!=720)return 1;}
  else{
   unsigned ew=1920-x<64?1920-x:64,eh=720-y<64?720-y:64,index=y/64*30+x/64;
   if(x%64||y%64||w!=ew||h!=eh||plain>sizeof(tile)||index>=360||(seen[index/32]&(1U<<(index%32))))return 1;
   seen[index/32]|=1U<<(index%32);
  }
  unsigned char*dest=key?raw:tile;
  if(is_raw){if(stored!=plain)return 1;for(unsigned n=0;n<plain;n++)dest[n]=in[pos+n];}
  else if(LZ4_decompress_safe((const char*)in+pos,(char*)dest,(int)stored,(int)plain)!=(int)plain)return 1;
  if(!key)for(unsigned row=0;row<h;row++)for(unsigned n=0;n<w*2;n++)raw[((y+row)*1920+x)*2+n]=tile[row*w*2+n];
  out->rectangles[i]=(UltraRect){x,y,w,h};pos+=stored;
 }
 if(pos!=size)return 1;
 out->id=id;out->key=key;out->count=count;return 0;
}
