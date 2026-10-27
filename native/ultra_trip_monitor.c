/* Read-only, bounded normal D-Bus subscription. No display/vehicle writes.
 * The parent supplies ECluster's current session-bus environment. The command,
 * service and object path are fixed; no arbitrary program/arguments accepted. */
static long s3(long n,long a,long b,long c){
 register long r0 __asm__("r0")=a,r1 __asm__("r1")=b,r2 __asm__("r2")=c,r3 __asm__("r3")=0,r7 __asm__("r7")=n;
 __asm__ volatile("svc 0":"+r"(r0):"r"(r1),"r"(r2),"r"(r3),"r"(r7):"memory","cc");return r0;
}
static unsigned long nowms(void){long t[2];if(s3(263,1,(long)t,0)<0)return 0;return (unsigned long)t[0]*1000+(unsigned long)t[1]/1000000;}
int run(long *stack){
 long argc=stack[0];if(argc!=1)return 2;
 char **env=(char **)(stack+argc+2);
 char *argv[]={"/usr/bin/gdbus","monitor","--session","--dest","com.xiaopeng.DomainController.Cluster","--object-path","/com/xiaopeng/DomainController/Cluster",0};
 /* The management daemon may ignore/block signals. Reset both explicitly;
  * an ignored SIGALRM would otherwise survive exec and defeat the deadline. */
 unsigned long action[4]={0,0,0,0},unblock=(1UL<<13)|(1UL<<12);
 s3(67,14,(long)action,0);s3(67,13,(long)action,0);s3(126,1,(long)&unblock,0);
 /* A parent deadline cannot be cancelled by gdbus/libc changing SIGALRM. */
 long child=s3(2,0,0,0);if(child<0)return 3;
 if(child==0){s3(11,(long)argv[0],(long)argv,(long)env);return 127;}
 unsigned long start=nowms();int status=0;unsigned int tries=0;
 for(;;){long done=s3(114,child,(long)&status,1);if(done==child||done==-10)return 0;if(done<0&&done!=-4)return 4;
  if(++tries>=90||(unsigned long)(nowms()-start)>=1800){s3(37,child,9,0);while(s3(114,child,(long)&status,0)==-4){}return 124;}
  long pause[2]={0,20000000};s3(162,(long)pause,0,0);
 }
}
__asm__(".global _start\n_start:\nmov r0, sp\nbl run\nmov r7, #1\nsvc #0\n");
