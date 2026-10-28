#ifndef OP_WIDGET_COMPOSITOR_H
#define OP_WIDGET_COMPOSITOR_H
#include "op_self_popup.h"
#include "op_text_alert.h"
typedef struct {NpcState comfort;NaState alerts;int alertsEnabled,published,blanked;} NwState;
int nw_init(NwState*,int);
int nw_packet(NwState*,const NpcU32*,unsigned,NpcU32);
int nw_can_publish(const NwState*,NpcU32);
void nw_frame(NwState*,NpcU16*,const NpcU16*,NpcU32);
void nw_tick(NwState*,NpcU16*,const NpcU16*,NpcU32);
int nw_needs_draw(const NwState*,NpcU32);
int nw_wait_ms(const NwState*,NpcU32,int);
void nw_pause(NwState*);
#endif
