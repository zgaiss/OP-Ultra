/* Compose in ordinary RAM, then publish the completed frame/regions. Never
 * erase a live widget on scanout before its replacement has finished drawing. */
#include "op_widget_present.h"
static void cp(Nwp*p,NpcU16*out,const NpcU16*in,unsigned pixels){if(p->copy)p->copy(out,in,pixels*2);else for(unsigned i=0;i<pixels;i++)out[i]=in[i];}
static void region(Nwp*p,NaRect r){if(r.w<=0||r.h<=0||r.x<0||r.y<0||r.x+r.w>1920||r.y+r.h>720)return;for(int y=r.y;y<r.y+r.h;y++)cp(p,p->visible+y*1920+r.x,p->draft+y*1920+r.x,(unsigned)r.w);}
void nwp_frame(Nwp*p,NpcU32 now){if(nw_can_publish(p->state,now))cp(p,p->draft,p->base,1382400);nw_frame(p->state,p->draft,p->base,now);cp(p,p->visible,p->draft,1382400);}
void nwp_tick(Nwp*p,NpcU32 now){
 if(!nw_needs_draw(p->state,now))return;NaRect before[3];for(unsigned i=0;i<3;i++)before[i]=p->state->alertsEnabled?p->state->alerts.previous[i]:(NaRect){0,0,0,0};int blank=p->state->blanked;
 nw_tick(p->state,p->draft,p->base,now);
 if(blank!=p->state->blanked){cp(p,p->visible,p->draft,1382400);return;}
 region(p,(NaRect){NPC_DAMAGE_X,NPC_DAMAGE_Y,NPC_DAMAGE_W,NPC_DAMAGE_H});
 for(unsigned i=0;i<3;i++){region(p,before[i]);if(p->state->alertsEnabled){NaRect after=p->state->alerts.previous[i];if(after.x!=before[i].x||after.y!=before[i].y||after.w!=before[i].w||after.h!=before[i].h)region(p,after);}}
}
