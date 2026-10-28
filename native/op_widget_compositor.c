/* One instrument compositor, independent of map cadence. Both widget families
 * restore only current clean OP pixels. Unknown alert state exposes OEM pixels
 * without tearing down the CarPlay socket or resending the map session. */
#include "op_widget_compositor.h"
static void copy(NpcU16*out,const NpcU16*base){for(unsigned i=0;i<1920U*720U;i++)out[i]=base[i];}
static void blank(NpcU16*out){for(unsigned i=0;i<1920U*720U;i++)out[i]=0;}
static void suppress(NwState*s){if(npc_active(&s->comfort))npc_clear(&s->comfort);}
int nw_init(NwState*s,int enabled){s->alertsEnabled=!!enabled;s->published=0;s->blanked=0;if(npc_init(&s->comfort))return 1;if(enabled&&na_init(&s->alerts))return 1;return 0;}
int nw_packet(NwState*s,const NpcU32*p,unsigned bytes,NpcU32 now){int r=npc_packet(&s->comfort,p,bytes,now);if(!r&&s->alertsEnabled){na_theme(&s->alerts,p[1]&1);if(!na_safe(&s->alerts,now)||na_active(&s->alerts))suppress(s);}return r;}
int nw_can_publish(const NwState*s,NpcU32 now){return !s->alertsEnabled||na_safe(&s->alerts,now);}
static int safe(NwState*s,NpcU16*out,const NpcU16*base,NpcU32 now,int*fresh){
 if(!s->alertsEnabled)return 1;
 if(!na_safe(&s->alerts,now)){
  suppress(s);if(!s->blanked)blank(out);s->blanked=1;
  /* Acknowledge the readiness change once, so an idle failed source cannot
   * cause a zero-timeout busy loop while original warnings are visible. */
  if(na_needs_draw(&s->alerts,now))na_draw(&s->alerts,out,base,now,0);
  return 0;
 }
 if(s->blanked){copy(out,base);s->blanked=0;*fresh=1;}
 if(na_active(&s->alerts))suppress(s);return 1;
}
static void paint(NwState*s,NpcU16*out,const NpcU16*base,NpcU32 now,int fresh){
 if(!safe(s,out,base,now,&fresh))return;
 if((fresh&&(npc_active(&s->comfort)||s->comfort.dirty))||npc_needs_draw(&s->comfort,now))npc_draw(&s->comfort,out,base,now);
 if(s->alertsEnabled&&(fresh||na_needs_draw(&s->alerts,now))&&na_draw(&s->alerts,out,base,now,fresh)<0){blank(out);s->blanked=1;}
}
void nw_frame(NwState*s,NpcU16*out,const NpcU16*base,NpcU32 now){s->published=1;paint(s,out,base,now,1);}
void nw_tick(NwState*s,NpcU16*out,const NpcU16*base,NpcU32 now){if(s->published)paint(s,out,base,now,0);}
int nw_needs_draw(const NwState*s,NpcU32 now){if(!s->published)return 0;if(s->alertsEnabled){if(na_needs_draw(&s->alerts,now))return 1;if(s->blanked)return 0;}return npc_needs_draw(&s->comfort,now);}
int nw_wait_ms(const NwState*s,NpcU32 now,int ms){if(!s->published)return ms;if(s->alertsEnabled){ms=na_wait_ms(&s->alerts,now,ms);if(s->blanked)return ms;}return npc_wait_ms(&s->comfort,now,ms);}
void nw_pause(NwState*s){npc_clear(&s->comfort);s->published=0;s->blanked=0;}
