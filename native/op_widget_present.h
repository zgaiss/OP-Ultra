#ifndef OP_WIDGET_PRESENT_H
#define OP_WIDGET_PRESENT_H
#include "op_widget_compositor.h"
typedef struct {NwState*state;NpcU16*visible,*draft;const NpcU16*base;NaCopyFn copy;} Nwp;
void nwp_frame(Nwp*,NpcU32);
void nwp_tick(Nwp*,NpcU32);
#endif
