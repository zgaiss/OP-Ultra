/* One lower-centre, app-owned popup. Only clean OP pixels and cached own
 * fonts/icons are inputs. No OEM popup readback, holes or log interception. */
#include "op_self_popup.h"
#include "op_self_atlas.h"
#include "tinf/tinf.h"
static unsigned char atlas[NPC_ATLAS_BYTES],body_mask[NPC_W*NPC_H];
static unsigned char shadow_mask[NPC_DAMAGE_W*NPC_DAMAGE_H];
static NpcU16 skins[2][NPC_W*NPC_H];
static int ready;
static int newer(NpcU32 a,NpcU32 b){return (int)(a-b)>0;}
static unsigned progress(NpcU32 n,unsigned d){if(n>=d)return 256;return n*n*(3*d-2*n)*256/(d*d*d);}
static unsigned entry(const NpcSlot*p,NpcU32 now){return p->enter_from+((256-p->enter_from)*progress(now-p->shown,NPC_ENTER_MS)>>8);}
static unsigned opacity(const NpcSlot*p,NpcU32 now){
 if(!p->active)return 0;
 if((int)(now-p->until)>=0)return entry(p,p->until)*(256-progress(now-p->until,NPC_EXIT_MS))>>8;
 return entry(p,now);
}
static NpcU16 rgb(unsigned r,unsigned g,unsigned b){return (NpcU16)(((r>>3)<<11)|((g>>2)<<5)|(b>>3));}
static NpcU16 rgb_dither(unsigned r,unsigned g,unsigned b,unsigned x,unsigned y){
 static const unsigned char bayer[16]={0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5};
 unsigned d=bayer[(y&3)*4+(x&3)];
 return (NpcU16)((((r*31*16+d*255)/4080)<<11)|(((g*63*16+d*255)/4080)<<5)|((b*31*16+d*255)/4080));
}
static NpcU16 blend(NpcU16 a,NpcU16 b,unsigned w){
 unsigned inv=256-w;
 unsigned r=((a>>11)*inv+(b>>11)*w)>>8,g=(((a>>5)&63)*inv+((b>>5)&63)*w)>>8,bl=((a&31)*inv+(b&31)*w)>>8;
 NpcU16 c=(NpcU16)((r<<11)|(g<<5)|bl);return c?c:1;
}
static void pixel(NpcU16*p,int x,int y,NpcU16 color,unsigned a){
 if(x>=NPC_DAMAGE_X&&x<NPC_DAMAGE_X+NPC_DAMAGE_W&&y>=NPC_DAMAGE_Y&&y<NPC_DAMAGE_Y+NPC_DAMAGE_H&&a)
  p[y*1920+x]=blend(p[y*1920+x],color,a);
}
static unsigned rounded(int x,int y,int w,int h,int r){
 if(x<0||y<0||x>=w||y>=h)return 0;
 int dx=x<r?r-x-1:x>=w-r?x-(w-r):0,dy=y<r?r-y-1:y>=h-r?y-(h-r):0;
 if(!dx||!dy)return 256;
 int z=dx*dx+dy*dy,inside=(r-1)*(r-1),outside=(r+1)*(r+1);
 if(z<=inside)return 256;if(z>=outside)return 0;
 return (unsigned)(outside-z)*256/(unsigned)(outside-inside);
}
static void cache_skins(void){
 for(int y=0;y<NPC_H;y++)for(int x=0;x<NPC_W;x++){
  unsigned i=y*NPC_W+x,m=rounded(x,y,NPC_W,NPC_H,27);
  unsigned inner=rounded(x-1,y-1,NPC_W-2,NPC_H-2,26),edge=m>inner?m-inner:0;
  body_mask[i]=(unsigned char)(m==256?255:m);
  unsigned t=(unsigned)(x*342+y*940)*256/(NPC_W*342+NPC_H*940);
  t=t>=192?256:t*256/192;
  NpcU16 night=rgb_dither((37*(256-t)+24*t)>>8,(43*(256-t)+29*t)>>8,(54*(256-t)+38*t)>>8,(unsigned)x,(unsigned)y);
  skins[1][i]=blend(night,rgb(201,210,226),edge*31/256);
  skins[0][i]=blend(rgb_dither(251,250,247,(unsigned)x,(unsigned)y),rgb(255,255,255),edge*221/256);
 }
 for(int y=0;y<NPC_DAMAGE_H;y++)for(int x=0;x<NPC_DAMAGE_W;x++){
  int px=x+NPC_DAMAGE_X-NPC_X,py=y+NPC_DAMAGE_Y-NPC_Y;
  unsigned a=rounded(px+14,py+8,NPC_W+28,NPC_H+28,41)*3/256;
  a+=rounded(px+9,py+3,NPC_W+18,NPC_H+18,36)*4/256;
  a+=rounded(px+4,py-2,NPC_W+8,NPC_H+8,31)*6/256;
  shadow_mask[y*NPC_DAMAGE_W+x]=(unsigned char)a;
 }
}
void npc_clear(NpcState*s){s->slot.active=0;s->dirty=1;}
int npc_init(NpcState*s){
 unsigned char*p=(unsigned char*)s;for(unsigned i=0;i<sizeof(*s);i++)p[i]=0;
 if(!ready){unsigned n=sizeof(atlas);if(tinf_zlib_uncompress(atlas,&n,npc_atlas_z,sizeof(npc_atlas_z))||n!=sizeof(atlas))return 1;
  for(unsigned k=0;k<NPC_GLYPH_COUNT;k++)if(npc_glyphs[k].off>n||npc_glyphs[k].w*npc_glyphs[k].h>n-npc_glyphs[k].off)return 1;
  cache_skins();ready=1;
 }return 0;
}
int npc_packet(NpcState*s,const NpcU32*p,unsigned bytes,NpcU32 now){
 if(bytes!=NPC_PACKET_BYTES||p[0]!=1||(p[1]&~3U)||p[13]||p[14]||p[15])return -1;
 int values[3]={(int)p[2],(int)p[3],(int)p[5]},maxes[3]={400,(int)p[4],(int)p[6]};
 if((values[0]!=-1&&(values[0]<100||values[0]>400))||(values[1]!=-1&&(maxes[1]<1||maxes[1]>16||values[1]<0||values[1]>maxes[1]))||(values[2]!=-1&&(maxes[2]<1||maxes[2]>100||values[2]<0||values[2]>maxes[2])))return -1;
 for(unsigned k=0;k<3;k++)if(p[10+k]>2500)return -1;
 if(s->night!=(int)(p[1]&1)){s->night=p[1]&1;s->dirty=1;}
 NpcSlot*q=&s->slot;int selected=-1;NpcU32 newest=s->newest;
 for(unsigned k=0;k<3;k++)if(p[7+k]&&newer(p[7+k],s->seen[k])){
  s->seen[k]=p[7+k];if(newer(p[7+k],newest)){newest=p[7+k];selected=(int)k;}
 }
 if(selected>=0){
  unsigned k=(unsigned)selected;s->newest=newest;
  if(values[k]>=0&&p[10+k]){
   unsigned a=opacity(q,now);int changed=q->active&&q->kind!=k+1;
   if(!q->active||(int)(now-q->until)>=0){q->enter_from=a;q->shown=now;}
   if(changed){q->crossfade=(now-q->swapped)>100;q->swapped=now;}
   q->active=1;q->kind=k+1;q->value=values[k];q->max=maxes[k];q->muted=k==2&&!!(p[1]&2);
   q->serial=newest;q->until=now+p[10+k];s->dirty=1;
  }else if(q->active&&(int)(q->until-now)>0){q->until=now;s->dirty=1;}
 }
 if(q->active&&values[q->kind-1]<0&&(int)(q->until-now)>0){q->until=now;s->dirty=1;}
 return 0;
}
int npc_active(const NpcState*s){return s->slot.active;}
int npc_needs_draw(const NpcState*s,NpcU32 now){
 if(s->dirty)return 1;if(now-s->drawn<NPC_TICK_MS||!s->slot.active)return 0;
 const NpcSlot*q=&s->slot;
 return (q->enter_from<256&&(now-q->shown<NPC_ENTER_MS||s->drawn-q->shown<NPC_ENTER_MS))||(int)(now-q->until)>=0||(q->crossfade&&(now-q->swapped<NPC_SWAP_MS||s->drawn-q->swapped<NPC_SWAP_MS));
}
int npc_wait_ms(const NpcState*s,NpcU32 now,int max){
 if(s->dirty)return 0;if(!s->slot.active)return max;
 const NpcSlot*q=&s->slot;
 if((q->enter_from<256&&(now-q->shown<NPC_ENTER_MS||s->drawn-q->shown<NPC_ENTER_MS))||(int)(now-q->until)>=0||(q->crossfade&&(now-q->swapped<NPC_SWAP_MS||s->drawn-q->swapped<NPC_SWAP_MS)))return max>NPC_TICK_MS?NPC_TICK_MS:max;
 unsigned remaining=q->until-now;return remaining<(unsigned)max?(int)remaining:max;
}
static void glyph(NpcU16*p,int x,int baseline,unsigned id,NpcU16 color,unsigned a){
 if(id>=NPC_GLYPH_COUNT)return;NpcGlyph g=npc_glyphs[id];const unsigned char*m=atlas+g.off;
 for(unsigned yy=0;yy<g.h;yy++)for(unsigned xx=0;xx<g.w;xx++){
  unsigned c=m[yy*g.w+xx];if(c)pixel(p,x+g.xoff+(int)xx,baseline+g.yoff+(int)yy,color,((c+(c==255))*a)>>8);
 }
}
static int number(NpcU16*p,int x,int baseline,int value,int decimal,int small,NpcU16 color,unsigned a){
 char b[12];int n=0;unsigned v=(unsigned)value;do{b[n++]=(char)('0'+v%10);v/=10;}while(v&&n<10);
 for(int k=n-1;k>=0;k--){unsigned id=(unsigned)(b[k]-'0')+(small?NPC_SMALL_0:NPC_NUM_0);glyph(p,x,baseline,id,color,a);x+=npc_glyphs[id].advance;
  if(decimal&&k==1){glyph(p,x,baseline,NPC_DOT,color,a);x+=npc_glyphs[NPC_DOT].advance;}}
 return x;
}
static void volume_bar(NpcU16*p,int x,int y,int width,int value,int max,NpcU16 ink,NpcU16 track,unsigned a){
 int fill=width*value/max;
 for(int yy=0;yy<4;yy++)for(int xx=0;xx<width;xx++)pixel(p,x+xx,y+yy,xx<fill?ink:track,rounded(xx,yy,width,4,2)*a>>8);
}
static void fan_bar(NpcU16*p,int x,int y,int width,int value,int max,NpcU16 ink,NpcU16 track,unsigned a){
 int active=(value*10+max-1)/max;
 for(int i=0;i<10;i++){int lo=i*width/10,hi=(i+1)*width/10-5;
  for(int yy=0;yy<5;yy++)for(int xx=lo;xx<hi;xx++)pixel(p,x+xx,y+yy,i<active?ink:track,rounded(xx-lo,yy,hi-lo,5,2)*a>>8);}
}
static void ruler(NpcU16*p,int x,int y,int width,int value,NpcU16 accent,NpcU16 muted,unsigned a){
 glyph(p,x,y+4,NPC_SNOW_SMALL,muted,a);glyph(p,x+width-15,y+4,NPC_SUN_SMALL,muted,a);
 int lo=x+25,w=width-50;for(int i=0;i<9;i++){int h=i%2?6:10,xx=lo+i*(w-1)/8;for(int yy=0;yy<h;yy++)pixel(p,xx,y+(24-h)/2+yy,muted,a*123>>8);}
 int value_clamped=value<160?160:value>300?300:value,pos=(value_clamped-160)*(w-2)/140;
 for(int yy=0;yy<18;yy++)for(int xx=0;xx<2;xx++)pixel(p,lo+pos+xx,y+3+yy,accent,a);
}
void npc_draw(NpcState*s,NpcU16*front,const NpcU16*base,NpcU32 now){
 for(int y=NPC_DAMAGE_Y;y<NPC_DAMAGE_Y+NPC_DAMAGE_H;y++){
  unsigned offset=y*1920+NPC_DAMAGE_X;
  for(unsigned x=0;x<NPC_DAMAGE_W;x++)front[offset+x]=base[offset+x];
 }
 NpcSlot*q=&s->slot;unsigned a=opacity(q,now);
 if(!a){if(q->active&&(int)(now-q->until)>=NPC_EXIT_MS)q->active=0;s->dirty=0;s->drawn=now;return;}
 int dy=(int)((256-a)*4>>8),x0=NPC_X,y0=NPC_Y+dy;
 for(int y=0;y<NPC_DAMAGE_H;y++)for(int x=0;x<NPC_DAMAGE_W;x++){
  unsigned mask=shadow_mask[y*NPC_DAMAGE_W+x];if(mask)pixel(front,NPC_DAMAGE_X+x,NPC_DAMAGE_Y+y+dy,1,mask*a>>8);
 }
 for(int y=0;y<NPC_H;y++)for(int x=0;x<NPC_W;x++){
  unsigned i=y*NPC_W+x,m=body_mask[i];if(m)pixel(front,x0+x,y0+y,skins[s->night?1:0][i],(m+(m==255))*a>>8);
 }
 NpcU16 ink=s->night?rgb(240,241,245):rgb(51,49,45),muted=s->night?rgb(211,211,214):rgb(102,97,90);
 NpcU16 accent=s->night?rgb(194,201,215):rgb(116,108,96),track=s->night?rgb(63,69,81):rgb(226,223,218);
 int warm=q->kind==1&&q->value>=270;
 NpcU16 climate=warm?(s->night?rgb(232,177,132):rgb(172,96,51)):ink;
 unsigned content=a;if(q->crossfade&&now-q->swapped<NPC_SWAP_MS)content=content*(166+(90*progress(now-q->swapped,NPC_SWAP_MS)>>8))>>8;
 int hvac_off=q->kind==2&&q->value==0;
 unsigned label=q->kind==1?NPC_TEMP:q->kind==2?(hvac_off?NPC_HVAC:NPC_FAN):NPC_VOLUME;
 unsigned icon=q->kind==1?(warm?NPC_SUN:NPC_SNOW):q->kind==2?NPC_FAN_ICON:q->muted?NPC_MUTE_ICON:NPC_SPEAKER;
 glyph(front,x0+26,y0+21,icon,q->kind==1?climate:muted,content);
 glyph(front,x0+78,y0+46,label,muted,content);
 int end=x0+208;
 if(hvac_off){glyph(front,end,y0+49,NPC_OFF,ink,content);end+=npc_glyphs[NPC_OFF].advance;}
 else if(q->muted){glyph(front,end,y0+49,NPC_MUTED,ink,content);end+=npc_glyphs[NPC_MUTED].advance;}
 else{
  end=number(front,end,y0+51,q->value,q->kind==1,0,q->kind==1?climate:ink,content)+5;
  if(q->kind==1)glyph(front,end,y0+51,NPC_CELSIUS,climate,content);
  else if(q->kind==2)glyph(front,end,y0+51,NPC_FAN_UNIT,muted,content);
  else{glyph(front,end,y0+51,NPC_SLASH,muted,content);end+=npc_glyphs[NPC_SLASH].advance+4;end=number(front,end,y0+51,q->max,0,1,muted,content);}
 }
 int ix=x0+326;if(q->kind==3&&end+18>ix)ix=end+18;
 int width=x0+594-ix;
 if(q->kind==1)ruler(front,ix,y0+26,width,q->value,warm?climate:accent,muted,content);
 else if(q->kind==2)fan_bar(front,ix,y0+36,width,q->value,q->max,accent,track,content);
 else if(width>0)volume_bar(front,ix,y0+36,width,q->muted?0:q->value,q->max,accent,track,content);
 s->dirty=0;s->drawn=now;
}
