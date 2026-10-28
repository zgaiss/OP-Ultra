/* Instrument-side read-only adapter. No shell, setters, injection or network
 * listener: one normal gdbus subscription plus two bounded startup getters. */
#include "op_alert_source.h"
#ifndef ASL_TEST_OS
#include <fcntl.h>
_Static_assert(O_DIRECTORY==16384 && O_DIRECT==65536 && O_CLOEXEC==524288,"ARM Linux open flags");
#endif
#ifdef ASL_TEST_OS
extern long asl_os(long,long,long,long);
#else
static long asl_os(long n,long a,long b,long c){register long r0 __asm__("r0")=a,r1 __asm__("r1")=b,r2 __asm__("r2")=c,r3 __asm__("r3")=0,r4 __asm__("r4")=0,r5 __asm__("r5")=0,r7 __asm__("r7")=n;__asm__ volatile("svc 0":"+r"(r0):"r"(r1),"r"(r2),"r"(r3),"r"(r4),"r"(r5),"r"(r7):"memory","cc");return r0;}
#endif
typedef struct {int fd,pid,killed,mode,used,drop,have,extra;NaU32 started;AsValue value;char line[8192];} Input;
static struct {AsState state;NaState*draw;Input in[3];NaU32 pid,epoch,retry,checked,queries,events,errors;AsTime birth;int enabled,live,reported,monitorReady;char environment[640];} source;
static int eq(const char*a,const char*b){while(*a&&*a==*b){a++;b++;}return *a==*b;}
static int starts(const char*a,const char*b){while(*b)if(*a++!=*b++)return 0;return 1;}
static int contains(const char*a,const char*b){for(;*a;a++)if(starts(a,b))return 1;return 0;}
static void report(const char*s){if(source.reported++>=128)return;unsigned n=0;while(s[n])n++;asl_os(4,1,(long)s,n);}
static void closefd(int fd){if(fd>=0)asl_os(6,fd,0,0);}
static int readfile(const char*p,char*b,unsigned n){int f=asl_os(5,(long)p,524288,0);if(f<0)return -1;long v=asl_os(3,f,(long)b,n-1);closefd(f);if(v<0)return -1;b[v]=0;return (int)v;}
static void path(char*out,NaU32 pid,const char*tail){unsigned at=0;const char*p="/proc/";while(*p)out[at++]=*p++;char n[12];unsigned k=0;do{n[k++]=(char)('0'+pid%10);pid/=10;}while(pid);while(k)out[at++]=n[--k];while(*tail)out[at++]=*tail++;out[at]=0;}
static AsTime identity(NaU32 pid){char p[64],b[1024];path(p,pid,"/comm");if(readfile(p,b,sizeof(b))!=9||!eq(b,"ECluster\n"))return 0;path(p,pid,"/exe");long n=asl_os(85,(long)p,(long)b,sizeof(b)-1);if(n<0)return 0;b[n]=0;if(!eq(b,"/app/hmi/bin/ECluster"))return 0;path(p,pid,"/stat");if(readfile(p,b,sizeof(b))<0)return 0;unsigned at=0,last=0;while(b[at]){if(b[at]==')')last=at;at++;}if(!last)return 0;at=last+1;for(unsigned f=3;f<22;f++){while(b[at]==' ')at++;if(!b[at])return 0;while(b[at]&&b[at]!=' ')at++;}while(b[at]==' ')at++;AsTime birth=0;unsigned digits=0;while(b[at]>='0'&&b[at]<='9'){unsigned d=(unsigned)(b[at++]-'0');if(++digits>19||birth>(9223372036854775807ULL-d)/10)return 0;birth=birth*10+d;}return digits?birth:0;}
static NaU32 decimal(const char*p){NaU32 v=0;unsigned d=0;while(*p>='0'&&*p<='9'){if(++d>7)return 0;v=v*10+(unsigned)(*p++-'0');}return d&&!*p?v:0;}
static int discover(void){
 int fd=asl_os(5,(long)"/proc",524288,0);if(fd<0){report("ALERT_DISCOVERY_OPEN_FAILED\n");return 0;}unsigned entries=0;char b[4096];NaU32 pid=0;AsTime birth=0;
 for(unsigned loops=0;loops<64&&!pid;loops++){long n=asl_os(217,fd,(long)b,sizeof(b));if(n<0)report("ALERT_DISCOVERY_READ_FAILED\n");if(n<=0||n>(long)sizeof(b))break;for(unsigned at=0;at+20<=(unsigned)n;){unsigned size=(unsigned char)b[at+16]|(unsigned)(unsigned char)b[at+17]<<8;if(size<20||size>(unsigned)n-at)break;unsigned end=at+19;while(end<at+size&&b[end])end++;if(end==at+size)break;NaU32 p=decimal(b+at+19);if(p){AsTime t=identity(p);if(t){pid=p;birth=t;break;}}at+=size;if(++entries>4096)break;}if(entries>4096)break;}closefd(fd);if(!pid){report("ALERT_DISCOVERY_NO_PROCESS\n");return 0;}
 char p[64],env[8192];path(p,pid,"/environ");int n=readfile(p,env,sizeof(env));if(n<=0||n==(int)sizeof(env)-1){report("ALERT_DISCOVERY_ENV_FAILED\n");return 0;}const char*key="DBUS_SESSION_BUS_ADDRESS=unix:";for(unsigned at=0;at<(unsigned)n;){unsigned end=at;while(end<(unsigned)n&&env[end])end++;if(end==(unsigned)n)break;if(starts(env+at,key)){unsigned len=end-at;if(len>=sizeof(source.environment))return 0;for(unsigned k=0;k<=len;k++)source.environment[k]=env[at+k];source.pid=pid;source.birth=birth;return identity(pid)==birth;}at=end+1;}report("ALERT_DISCOVERY_NO_BUS\n");return 0;
}
static void reap(Input*i){if(i->pid>0){int status;long r=asl_os(114,i->pid,(long)&status,1);if(r==i->pid||r==-10)i->pid=0;}}
static void stop(Input*i){closefd(i->fd);i->fd=-1;if(i->pid>0&&!i->killed){asl_os(37,i->pid,9,0);i->killed=1;}reap(i);}
static void invalid(void){source.errors++;as_lost(&source.state);if(source.live){source.live=0;report("ALERT_SOURCE_FALLBACK_OEM\n");}}
static void lost_monitor(NaU32 now){report("ALERT_MONITOR_LOST\n");source.monitorReady=0;invalid();for(unsigned k=0;k<3;k++)stop(&source.in[k]);source.retry=now+5000;}
static int spawn(Input*i,int mode,NaU32 now){
 reap(i);if(i->pid)return 0;int pipes[2];if(asl_os(42,(long)pipes,0,0)<0){report("ALERT_PIPE_FAILED\n");return 0;}
 if(asl_os(55,pipes[0],2,1)<0||asl_os(55,pipes[1],2,1)<0||asl_os(55,pipes[0],4,2048)<0){closefd(pipes[0]);closefd(pipes[1]);return 0;}
 long parent=asl_os(20,0,0,0),child=asl_os(2,0,0,0);if(child<0){closefd(pipes[0]);closefd(pipes[1]);return 0;}
 if(!child){
  /* The worker is the sole owner; SIGKILL also works when inherited signals
   * from the manufacturer's management process happen to be ignored. */
  if(asl_os(172,1,9,0)<0||asl_os(64,0,0,0)!=parent){asl_os(1,126,0,0);return 0;}
  if(asl_os(63,pipes[1],1,0)<0||asl_os(63,pipes[1],2,0)<0){asl_os(1,126,0,0);return 0;}for(int fd=3;fd<1024;fd++)closefd(fd);
  char*env[]={source.environment,"LC_ALL=C",0};char*method=mode==0?"com.xiaopeng.DomainController.Cluster.get_host_ealarm_id":"com.xiaopeng.DomainController.Cluster.get_host_mcu_eautopilot_tips_id";
  char*get[]={"/usr/bin/gdbus","call","--session","--timeout","1","--dest","com.xiaopeng.DomainController.Cluster","--object-path","/com/xiaopeng/DomainController/Cluster","--method",method,0};
  char*monitor[]={"/usr/bin/gdbus","monitor","--session","--dest","com.xiaopeng.DomainController.Cluster","--object-path","/com/xiaopeng/DomainController/Cluster",0};
  asl_os(11,(long)get[0],(long)(mode==2?monitor:get),(long)env);asl_os(1,127,0,0);return 0;
 }
 closefd(pipes[1]);*i=(Input){0};i->fd=pipes[0];i->pid=(int)child;i->mode=mode;i->started=now;return 1;
}
static void line(Input*i,NaU32 now){
 i->line[i->used]=0;if(i->mode<2){AsValue v;if(i->have||!as_parse(i->line,(unsigned)i->used,1,&v)){i->extra=1;report("ALERT_GETTER_PARSE_FAILED\n");}else{i->have=1;i->value=v;}}
 else{
  if(contains(i->line,"error while loading shared libraries")||starts(i->line,"Error ")||starts(i->line,"Error:")){report("ALERT_MONITOR_ERROR\n");}
  if(contains(i->line,"does not have an owner")||contains(i->line,"lost its owner")||contains(i->line,"is not owned")){lost_monitor(now);}
  else if(contains(i->line,"com.xiaopeng.DomainController.Cluster")&&contains(i->line,"is owned by")){source.monitorReady=1;}
  else if(starts(i->line,"/com/xiaopeng/DomainController/Cluster: org.freedesktop.DBus.Properties.PropertiesChanged ")&&contains(i->line,"'com.xiaopeng.DomainController.Cluster'")){
   source.monitorReady=1;for(unsigned k=0;k<2;k++){unsigned wanted=k;AsValue v;int r=as_property(i->line,(unsigned)i->used,&wanted,&v);if(r<0)invalid();else if(r>0){if(as_accept(&source.state,k,&v,now)<0)invalid();else source.events++;}}
  }
 }
 i->used=0;
}
static void drain(Input*i,NaU32 now){
 if(i->fd<0)return;char b[1024];for(unsigned loop=0;loop<8;loop++){long n=asl_os(3,i->fd,(long)b,sizeof(b));if(n==-11||n==-4)break;if(n<=0){if(i->mode==2){lost_monitor(now);}else{if(i->used)line(i,now);closefd(i->fd);i->fd=-1;}break;}
  for(long at=0;at<n;at++){char c=b[at];if(c=='\n'){if(!i->drop)line(i,now);i->used=0;i->drop=0;}else if(!i->drop){if(i->used+1<(int)sizeof(i->line))i->line[i->used++]=c;else{i->line[i->used]=0;if(i->mode<2||contains(i->line,"host_ealarm_id")||contains(i->line,"host_mcu_eautopilot_tips_id"))invalid();i->drop=1;i->used=0;i->extra=1;}}}
  if(i->fd<0)break;
 }
}
void asl_start(NaState*draw,NaU32 now){source.draw=draw;source.enabled=1;source.epoch=1;source.retry=now;source.checked=now;source.queries=now;source.live=0;source.reported=0;source.pid=0;for(unsigned k=0;k<3;k++){source.in[k]=(Input){0};source.in[k].fd=-1;}as_reset(&source.state,draw,source.epoch);report("ALERT_SOURCE_TEST_NORMAL_DBUS\n");}
void asl_tick(NaU32 now){
 if(!source.enabled)return;for(unsigned k=0;k<3;k++)if(source.in[k].killed)reap(&source.in[k]);
 if(source.in[2].fd<0){if((int)(now-source.retry)<0)return;source.retry=now+5000;for(unsigned k=0;k<3;k++)if(source.in[k].pid)return;
  if(!discover()){invalid();return;}as_reset(&source.state,source.draw,++source.epoch);source.checked=now;source.queries=now-5000;source.monitorReady=0;
  if(!spawn(&source.in[2],2,now)){invalid();return;}
 }
 if(now-source.checked>=500){source.checked=now;if(identity(source.pid)!=source.birth){invalid();for(unsigned k=0;k<3;k++)stop(&source.in[k]);source.retry=now+5000;return;}}
 drain(&source.in[2],now);
 if(source.in[2].fd<0)return;
 int monitorStatus=0;long alive=asl_os(114,source.in[2].pid,(long)&monitorStatus,1);if(alive&&alive!=-4){if(alive>0||alive==-10)source.in[2].pid=0;lost_monitor(now);return;}
 if(source.monitorReady&&source.state.known!=3&&now-source.queries>=5000){source.queries=now;for(unsigned k=0;k<2;k++)if(source.in[k].fd<0&&!source.in[k].pid)spawn(&source.in[k],(int)k,now);}
 for(unsigned k=0;k<2;k++){Input*i=&source.in[k];drain(i,now);if(i->pid>0&&!i->killed){int status=0;long done=asl_os(114,i->pid,(long)&status,1);if(done==i->pid){i->pid=0;drain(i,now);closefd(i->fd);i->fd=-1;if(!status&&i->have&&!i->extra){if(as_accept(&source.state,k,&i->value,now)<0)invalid();}else invalid();}
   else if(done<0||now-i->started>=1500){invalid();stop(i);}
  }}
 if(source.monitorReady&&source.state.known==3){as_health(&source.state,now);if(!source.live){source.live=1;report("ALERT_SOURCE_READY\n");}}
}
void asl_stop(void){if(!source.enabled)return;for(unsigned k=0;k<3;k++)stop(&source.in[k]);source.enabled=0;source.live=0;as_lost(&source.state);report("ALERT_SOURCE_STOPPED\n");}

void asl_diagnostic(unsigned out[8]){out[0]=source.pid;out[1]=source.in[2].pid;out[2]=source.in[2].fd;out[3]=source.monitorReady;out[4]=source.state.known;out[5]=source.in[2].used;out[6]=source.in[0].pid;out[7]=source.in[1].pid;}
