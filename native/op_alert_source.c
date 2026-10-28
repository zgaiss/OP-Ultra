#include "op_alert_source.h"
typedef struct {const char*p,*end;} Reader;
static void ws(Reader*r){while(r->p<r->end&&(*r->p==' '||*r->p=='\t'||*r->p=='\r'||*r->p=='\n'))r->p++;}
static int ch(Reader*r,char c){ws(r);if(r->p==r->end||*r->p!=c)return 0;r->p++;return 1;}
static int word(Reader*r,const char*w){const char*p=r->p;while(*w){if(p==r->end||*p++!=*w++)return 0;}r->p=p;return 1;}
static int number(Reader*r,int wide,long long*out){
 ws(r);if(word(r,"int64 ")){if(!wide)return 0;}else if(word(r,"uint64 ")){if(!wide)return 0;}else if(word(r,"int32 ")||word(r,"uint32 ")){}else if(r->p<r->end&&(*r->p<'0'||*r->p>'9')&&*r->p!='-')return 0;
 int neg=0;if(r->p<r->end&&*r->p=='-'){neg=1;r->p++;}AsTime n=0;unsigned digits=0;
 while(r->p<r->end&&*r->p>='0'&&*r->p<='9'){unsigned d=(unsigned)(*r->p++-'0');if(++digits>19||n>(9223372036854775807ULL-d)/10)return 0;n=n*10+d;}
 if(!digits||(!wide&&n>2147483647ULL))return 0;*out=neg?-(long long)n:(long long)n;return 1;
}
static int tuple(Reader*r,AsValue*out){long long v[4];if(!ch(r,'('))return 0;for(unsigned i=0;i<4;i++){if(!number(r,i==3,&v[i])||(i<3&&!ch(r,',')))return 0;}if(!ch(r,')')||v[2]<-1||v[2]>255||v[3]<=0)return 0;AsValue a={(int)v[0],(int)v[1],(int)v[2],(AsTime)v[3]};*out=a;return 1;}
int as_parse(const char*s,unsigned n,int method,AsValue*out){Reader r={s,s+n};AsValue a;long long status;if(!n||n>4096||!ch(&r,'('))return 0;if(!method&&!ch(&r,'<'))return 0;if(!tuple(&r,&a))return 0;if(method){if(!ch(&r,',')||!number(&r,0,&status)||status)return 0;}else{if(!ch(&r,'>')||!ch(&r,','))return 0;}if(!ch(&r,')'))return 0;ws(&r);if(r.p!=r.end)return 0;*out=a;return 1;}
static int same(const char*a,const char*b,unsigned n){for(unsigned i=0;i<n;i++)if(a[i]!=b[i])return 0;return 1;}
int as_property(const char*s,unsigned n,unsigned*channel,AsValue*out){
 static const char*keys[]={"'host_ealarm_id':", "'host_mcu_eautopilot_tips_id':"};
 unsigned wanted=*channel;for(unsigned k=0;k<2;k++){if(wanted<2&&wanted!=k)continue;unsigned len=0;while(keys[k][len])len++;for(unsigned i=0;i+len<=n;i++)if(same(s+i,keys[k],len)){Reader r={s+i+len,s+n};if(!ch(&r,'<')||!tuple(&r,out)||!ch(&r,'>'))return -1;*channel=k;return 1;}}return 0;
}
void as_reset(AsState*s,NaState*d,NaU32 epoch){*s=(AsState){0};s->draw=d;s->epoch=epoch?epoch:1;s->raw[0]=s->raw[1]=255;for(unsigned i=0;i<3;i++)s->ids[i]=-1;na_epoch(d,s->epoch);}
void as_lost(AsState*s){s->known=0;if(s->draw)na_source_lost(s->draw);}
int as_accept(AsState*s,unsigned channel,const AsValue*v,NaU32 now){
 if(channel>1||!s->draw||v->status||v->id<-1||v->id>255||!v->stamp){as_lost(s);return -1;}
 if(s->stamps[channel]&&v->stamp<s->stamps[channel])return 0;
 if(v->stamp==s->stamps[channel]&&(s->known&(1U<<channel))){if(s->raw[channel]!=v->id){as_lost(s);return -1;}return 0;}
 s->stamps[channel]=v->stamp;s->raw[channel]=v->id;s->known|=1U<<channel;if(s->known!=3)return 0;
 int ids[3]={s->raw[0],s->raw[1],-1};if(ids[0]==125){ids[0]=-1;ids[2]=125;}for(unsigned i=0;i<2;i++){if(ids[i]==255)ids[i]=-1;if(ids[i]>=0&&!na_member(ids[i],i)){as_lost(s);return -1;}}
 for(unsigned i=0;i<3;i++)if(ids[i]!=s->ids[i]){s->ids[i]=ids[i];s->orders[i]=++s->order;if(!s->orders[i])s->orders[i]=++s->order;}
 for(unsigned i=0;i<3;i++)if(ids[i]>=0&&!s->orders[i])s->orders[i]=++s->order;
 if(!++s->serial)++s->serial;return na_snapshot(s->draw,ids,s->orders,s->epoch,s->serial,now);
}
void as_health(AsState*s,NaU32 now){if(s->known==3)na_heartbeat(s->draw,s->epoch,now);}
