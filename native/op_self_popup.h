#ifndef OP_SELF_POPUP_H
#define OP_SELF_POPUP_H
typedef unsigned int NpcU32;
typedef unsigned short NpcU16;
typedef struct {
 unsigned kind; int value,max,muted;
 NpcU32 serial,shown,until,enter_from,swapped;
 int active,crossfade;
} NpcSlot;
typedef struct { NpcSlot slot; NpcU32 seen[3],newest,drawn; int night,dirty; } NpcState;
enum {
 NPC_PACKET_BYTES=64, NPC_WIRE_MAGIC=0x5043504f,
 NPC_ENTER_MS=120, NPC_EXIT_MS=80, NPC_SWAP_MS=65, NPC_TICK_MS=16,
 NPC_X=650, NPC_Y=566, NPC_W=620, NPC_H=76,
 NPC_DAMAGE_X=634, NPC_DAMAGE_Y=554, NPC_DAMAGE_W=652, NPC_DAMAGE_H=106
};
int npc_init(NpcState*);
int npc_packet(NpcState*,const NpcU32*,unsigned,NpcU32);
int npc_active(const NpcState*);
int npc_needs_draw(const NpcState*,NpcU32);
int npc_wait_ms(const NpcState*,NpcU32,int);
void npc_draw(NpcState*,NpcU16*,const NpcU16*,NpcU32);
void npc_clear(NpcState*);
#endif
