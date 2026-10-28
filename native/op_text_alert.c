#include "op_text_alert.h"
#include "op_alert_atlas.h"
#include "tinf/tinf.h"
#define MAX_W 668
#define MAX_H 166
#define MAX_AREA (MAX_W*MAX_H)
#ifndef NA_REFERENCE_SLOW
#define NA_REFERENCE_SLOW 0
#endif
_Static_assert(sizeof(NaU32)==4&&sizeof(NaU16)==2,"widget integer ABI");
static unsigned char atlas[NA_ATLAS_BYTES];
typedef struct {int key;NaU32 used;unsigned char data[MAX_AREA*3] __attribute__((aligned(4)));} NaCachedTexture;
typedef struct {int id,key;NaU16 pixels[MAX_AREA];} NaSurface;
static NaCachedTexture templates[3];static NaSurface surfaces[3];static NaU32 cache_clock;static int atlas_ready;static NaCopyFn fast_copy;
#ifdef NA_TEST_DIAGNOSTICS
static NaU32 debug_stats[5];
static int debug_fail_texture;
#define METRIC(i,n) (debug_stats[i]+=(NaU32)(n))
void na_debug_counts(NaU32 out[5]){for(unsigned i=0;i<5;i++)out[i]=debug_stats[i];}
void na_debug_reset(int drop){for(unsigned i=0;i<5;i++)debug_stats[i]=0;if(drop)for(unsigned i=0;i<3;i++){templates[i].key=-1;surfaces[i].id=-1;}}
void na_debug_fail_next_texture(void){debug_fail_texture=1;}
#else
#define METRIC(i,n) ((void)0)
#endif
static void zero(void*p,unsigned n){unsigned char*b=p;while(n--)*b++=0;}
static int newer(NaU32 a,NaU32 b){return (int)(a-b)>0;}
static unsigned progress(NaU32 n,unsigned d){if(n>=d)return 256;return n*n*(3*d-2*n)*256/(d*d*d);}
static unsigned opacity(const NaSlot*p,NaU32 now){return p->target?p->from+((256-p->from)*progress(now-p->changed,NA_ENTER_MS)>>8):p->from*(256-progress(now-p->changed,NA_EXIT_MS))>>8;}
static NaU16 rgb(unsigned r,unsigned g,unsigned b){return (NaU16)(((r>>3)<<11)|((g>>2)<<5)|(b>>3));}
static NaU16 blend(NaU16 a,NaU16 b,unsigned w){unsigned v=256-w;NaU16 c=(NaU16)(((((a>>11)*v+(b>>11)*w+128)>>8)<<11)|(((((a>>5)&63)*v+((b>>5)&63)*w+128)>>8)<<5)|(((a&31)*v+(b&31)*w+128)>>8));return c?c:1;}
void na_set_copy(NaCopyFn fn){fast_copy=fn;}
static void copy(NaU16*out,const NaU16*in,unsigned pixels){if(fast_copy)fast_copy(out,in,pixels*2);else for(unsigned i=0;i<pixels;i++)out[i]=in[i];}
static NaRect rect(int id){NaRect r={0,0,0,0};if(id<0)return r;const NaRecord*p=&na_records[id];r.x=960-p->width/2-24;r.y=p->center-p->height/2-14;r.w=p->width+48;r.h=p->height+46;return r;}
static int overlaps(int a,int b){if(a<0||b<0)return 0;const NaRecord*x=&na_records[a],*y=&na_records[b];return (int)x->center+(int)x->height/2>(int)y->center-(int)y->height/2&&(int)y->center+(int)y->height/2>(int)x->center-(int)x->height/2;}
static int area(NaRect a){return a.w*a.h;}
static NaRect united(NaRect a,NaRect b){int x=a.x<b.x?a.x:b.x,y=a.y<b.y?a.y:b.y,r=a.x+a.w>b.x+b.w?a.x+a.w:b.x+b.w,bt=a.y+a.h>b.y+b.h?a.y+a.h:b.y+b.h;NaRect c={x,y,r-x,bt-y};return c;}
static void restore_damage(NaU16*out,const NaU16*base,const NaRect previous[3],const NaRect current[3]){
 NaRect regions[6];unsigned n=0;for(unsigned i=0;i<3;i++)if(previous[i].w)regions[n++]=previous[i];for(unsigned i=0;i<3;i++)if(current[i].w)regions[n++]=current[i];
 /* Merging is cost bounded: never copy more pixels than the separate boxes. */
 int again=1;while(again){again=0;for(unsigned i=0;i<n&&!again;i++)for(unsigned j=i+1;j<n;j++){NaRect c=united(regions[i],regions[j]);if(area(c)<=area(regions[i])+area(regions[j])){regions[i]=c;regions[j]=regions[--n];again=1;break;}}}
 for(unsigned i=0;i<n;i++){NaRect r=regions[i];METRIC(3,area(r));for(int y=r.y;y<r.y+r.h;y++)copy(out+y*1920+r.x,base+y*1920+r.x,(unsigned)r.w);}
}
static NaCachedTexture*texture(unsigned key){
 for(unsigned i=0;i<3;i++)if(templates[i].key==(int)key){templates[i].used=++cache_clock;METRIC(0,1);return &templates[i];}
 unsigned target=0;for(unsigned i=0;i<3;i++){if(templates[i].key<0){target=i;break;}if((int)(templates[i].used-templates[target].used)<0)target=i;}
 const NaTexture*d=&na_textures[key];NaCachedTexture*t=&templates[target];unsigned n=sizeof(t->data);t->key=-1;
#ifdef NA_TEST_DIAGNOSTICS
 if(debug_fail_texture){debug_fail_texture=0;return 0;}
#endif
 if(tinf_zlib_uncompress(t->data,&n,na_textures_z+d->offset,d->length)||n!=d->raw)return 0;
 t->key=(int)key;t->used=++cache_clock;METRIC(1,1);return t;
}
static NaCachedTexture*surface(unsigned index,int id,int night){
 const NaRecord*r=&na_records[id];unsigned key=r->texture[night];NaCachedTexture*t=texture(key);if(!t)return 0;NaSurface*s=&surfaces[index];if(s->id==id&&s->key==(int)key)return t;
 const NaTexture*d=&na_textures[key];unsigned pixels=(unsigned)d->w*d->h;copy(s->pixels,(const NaU16*)t->data,pixels);
 for(unsigned n=r->first;n<(unsigned)r->first+r->count;n++){
  const NaGlyphRun*q=&na_runs[n];const NaGlyph*g=&na_glyphs[q->glyph];NaU16 ink=q->style?(night?rgb(211,211,214):rgb(102,97,90)):(night?rgb(240,241,245):rgb(51,49,45));
  for(unsigned y=0;y<g->h;y++)for(unsigned x=0;x<g->w;x++){int px=24+q->x+(int)x,py=14+q->y+(int)y;if(px<0||px>=d->w||py<0||py>=d->h)continue;unsigned a=atlas[g->offset+y*g->w+x];if(a)s->pixels[py*d->w+px]=blend(s->pixels[py*d->w+px],ink,a==255?256:a);}
 }
 s->id=id;s->key=(int)key;METRIC(2,1);return t;
}
int na_init(NaState*s){
 zero(s,sizeof(*s));for(unsigned i=0;i<3;i++)s->slot[i].id=-1;s->dirty=1;s->lastSafe=-1;
 if(!atlas_ready){unsigned n=sizeof(atlas);if(tinf_zlib_uncompress(atlas,&n,na_atlas_z,sizeof(na_atlas_z))||n!=sizeof(atlas))return 1;
  for(unsigned i=0;i<NA_GLYPH_COUNT;i++)if(na_glyphs[i].offset>n||na_glyphs[i].w*na_glyphs[i].h>n-na_glyphs[i].offset)return 1;
  for(unsigned i=0;i<NA_TEXTURE_COUNT;i++){const NaTexture*t=&na_textures[i];if(t->w>MAX_W||t->h>MAX_H||t->raw!=(unsigned)t->w*t->h*3||t->offset>NA_TEXTURE_Z_BYTES||t->length>NA_TEXTURE_Z_BYTES-t->offset)return 1;}
  for(unsigned i=0;i<256;i++)if(na_records[i].count){const NaRecord*r=&na_records[i];if(r->first>NA_RUN_COUNT||r->count>NA_RUN_COUNT-r->first||r->texture[0]>=NA_TEXTURE_COUNT||r->texture[1]>=NA_TEXTURE_COUNT||r->center<96+r->height/2||r->center+r->height/2+32>660)return 1;}
  for(unsigned i=0;i<3;i++){templates[i].key=-1;surfaces[i].id=-1;}atlas_ready=1;
 }return 0;
}
void na_epoch(NaState*s,NaU32 epoch){int night=s->night;NaRect old[3];for(unsigned i=0;i<3;i++)old[i]=s->previous[i];zero(s,sizeof(*s));for(unsigned i=0;i<3;i++){s->slot[i].id=-1;s->previous[i]=old[i];}s->epoch=epoch;s->night=night;s->dirty=1;s->lastSafe=-1;}
void na_source_lost(NaState*s){s->known=0;s->healthy=0;s->dirty=1;}
void na_heartbeat(NaState*s,NaU32 epoch,NaU32 now){if(epoch&&s->epoch==epoch&&s->known==7){s->heartbeat=now;s->healthy=1;}}
void na_theme(NaState*s,int night){night=!!night;if(s->night!=night){s->night=night;s->dirty=1;}}
int na_snapshot(NaState*s,const int ids[3],const NaU32 orders[3],NaU32 epoch,NaU32 serial,NaU32 now){
 if(!epoch||epoch!=s->epoch||!serial)return -1;if(s->serial&&!newer(serial,s->serial))return 0;
 for(unsigned i=0;i<3;i++){int id=ids[i];if(id<-1||id>255||(id>=0&&(!(na_records[id].flags&(1U<<i))||!orders[i]))){na_source_lost(s);return -1;}}
 /* After source loss, OEM pixels have been exposed. A fresh full snapshot
  * replaces the old logical slots directly; never replay their exit fades. */
 if(s->known!=7||!s->healthy||now-s->heartbeat>NA_HEALTH_MS){for(unsigned i=0;i<3;i++){s->slot[i].id=-1;s->slot[i].target=0;s->slot[i].from=0;s->slot[i].painted=0;}s->dirty=1;}
 for(unsigned i=0;i<3;i++){
  NaSlot*p=&s->slot[i];int id=ids[i];
  if(id>=0){if(p->id!=id||!p->target){unsigned a=p->id>=0?opacity(p,now):0;if(p->id!=id)p->painted=0;p->id=id;p->target=1;p->changed=now;p->from=a;s->dirty=1;}if(p->order!=orders[i])s->dirty=1;p->order=orders[i];}
  else if(p->target){p->from=opacity(p,now);p->changed=now;p->target=0;if(!p->painted)p->id=-1;s->dirty=1;}
 }
 s->serial=serial;s->known=7;s->healthy=1;s->heartbeat=now;return 1;
}
static int live(const NaSlot*p,NaU32 now){return p->id>=0&&(p->target||now-p->changed<NA_EXIT_MS);}
int na_safe(const NaState*s,NaU32 now){if(!s->epoch||s->known!=7||!s->healthy||now-s->heartbeat>NA_HEALTH_MS)return 0;for(unsigned i=0;i<3;i++)if(live(&s->slot[i],now)&&!(na_records[s->slot[i].id].flags&8))return 0;if(live(&s->slot[0],now)&&live(&s->slot[1],now)&&s->slot[0].order==s->slot[1].order&&overlaps(s->slot[0].id,s->slot[1].id))return 0;return 1;}
int na_active(const NaState*s){for(unsigned i=0;i<3;i++)if(s->slot[i].id>=0)return 1;return 0;}
int na_member(int id,unsigned channel){return id>=0&&id<256&&channel<3&&!!(na_records[id].flags&(1U<<channel));}
static int animating(const NaState*s,NaU32 now){for(unsigned i=0;i<3;i++){const NaSlot*p=&s->slot[i];if(p->id<0||(p->target&&p->from==256))continue;unsigned duration=p->target?NA_ENTER_MS:NA_EXIT_MS;if(now-p->changed<duration||s->drawn-p->changed<duration)return 1;}return 0;}
int na_needs_draw(const NaState*s,NaU32 now){return s->dirty||s->lastSafe!=na_safe(s,now)||(animating(s,now)&&now-s->drawn>=NA_TICK_MS);}
int na_wait_ms(const NaState*s,NaU32 now,int ms){if(ms<0)return 0;if(s->dirty||s->lastSafe!=na_safe(s,now))return 0;if(animating(s,now)){unsigned elapsed=now-s->drawn;int tick=elapsed>=NA_TICK_MS?0:(int)(NA_TICK_MS-elapsed);if(ms>tick)ms=tick;}if(s->known==7&&s->healthy){unsigned age=now-s->heartbeat;if(age<=NA_HEALTH_MS&&ms>(int)(NA_HEALTH_MS+1-age))ms=(int)(NA_HEALTH_MS+1-age);}return ms;}
static void paint(NaState*s,unsigned index,NaU16*out,NaU32 now,NaCachedTexture*t){
 NaSlot*p=&s->slot[index];unsigned a=opacity(p,now);const NaTexture*d=&na_textures[na_records[p->id].texture[s->night]];NaRect r=rect(p->id);const unsigned char*alpha=t->data+d->w*d->h*2;const NaU16*pixels=surfaces[index].pixels;METRIC(4,(unsigned)d->w*d->h);
 for(unsigned y=0;y<d->h;y++)for(unsigned x=0;x<d->w;x++){unsigned at=y*d->w+x,m=alpha[at],v=(m==255?256:m)*a/256;if(!v)continue;unsigned dst=(r.y+y)*1920+r.x+x;if(!NA_REFERENCE_SLOW&&v==256)out[dst]=pixels[at];else out[dst]=blend(out[dst],pixels[at],v);}
 p->painted=1;
}
int na_draw(NaState*s,NaU16*out,const NaU16*base,NaU32 now,int fresh){
 int safe=na_safe(s,now);s->lastSafe=safe;s->drawn=now;s->dirty=0;if(!safe)return -1;
 for(unsigned i=0;i<3;i++){NaSlot*p=&s->slot[i];if(p->id>=0&&!p->target&&now-p->changed>=NA_EXIT_MS)p->id=-1;}
 unsigned order[3]={0,1,2};if(newer(s->slot[0].order,s->slot[1].order)){order[0]=1;order[1]=0;}
 int visible[3]={0,0,0};NaCachedTexture*cached[3]={0,0,0};NaRect current[3]={{0,0,0,0},{0,0,0,0},{0,0,0,0}};
 for(unsigned n=0;n<3;n++){
  unsigned i=order[n];NaSlot*p=&s->slot[i];if(p->id<0||!opacity(p,now))continue;int hidden=0;
  for(unsigned k=n+1;k<3;k++)if(s->slot[order[k]].id>=0&&overlaps(p->id,s->slot[order[k]].id))hidden=1;
  if(!hidden){visible[i]=1;current[i]=rect(p->id);if(!surface(i,p->id,s->night)){na_source_lost(s);return -1;}}
 }
 /* At most three live keys fit the bounded cache. Resolve pointers only after
  * all loads, so eviction during preflight cannot leave a stale alpha pointer. */
 for(unsigned i=0;i<3;i++)if(visible[i]){cached[i]=texture(na_records[s->slot[i].id].texture[s->night]);if(!cached[i]){na_source_lost(s);return -1;}}
 if(!fresh)restore_damage(out,base,s->previous,current);
 for(unsigned n=0;n<3;n++){unsigned i=order[n];if(visible[i])paint(s,i,out,now,cached[i]);}
 for(unsigned i=0;i<3;i++)s->previous[i]=current[i];return 0;
}
