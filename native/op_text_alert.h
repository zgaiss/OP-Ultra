#ifndef OP_TEXT_ALERT_H
#define OP_TEXT_ALERT_H
typedef unsigned int NaU32;typedef unsigned short NaU16;
typedef struct { int id,target;NaU32 order,changed,from;int painted; } NaSlot;
typedef struct { int x,y,w,h; } NaRect;
typedef struct {NaSlot slot[3];NaRect previous[3];NaU32 epoch,serial,heartbeat,drawn;int known,healthy,night,dirty,lastSafe;} NaState;
enum {NA_PRIMARY=0,NA_AUTOPILOT=1,NA_BATTERY=2,NA_HEALTH_MS=1500,NA_ENTER_MS=120,NA_EXIT_MS=80,NA_TICK_MS=16};
typedef void(*NaCopyFn)(void*,const void*,unsigned);
void na_set_copy(NaCopyFn);
int na_init(NaState*);
#ifdef NA_TEST_DIAGNOSTICS
void na_debug_counts(NaU32[5]);
void na_debug_reset(int);
void na_debug_fail_next_texture(void);
#endif
void na_epoch(NaState*,NaU32);
/* Only verified OEM-selected state may enter this API. IDs are not priorities.
 * -1 means confirmed clear. Orders are original source arrival/stack order. */
int na_snapshot(NaState*,const int[3],const NaU32[3],NaU32,NaU32,NaU32);
void na_heartbeat(NaState*,NaU32,NaU32);
void na_source_lost(NaState*);
void na_theme(NaState*,int);
int na_safe(const NaState*,NaU32);
int na_active(const NaState*);
int na_member(int,unsigned);
int na_needs_draw(const NaState*,NaU32);
int na_wait_ms(const NaState*,NaU32,int);
/* Returns -1 when the caller must expose the original instrument layer.
 * It never creates transparent holes or samples original popup pixels. */
int na_draw(NaState*,NaU16*,const NaU16*,NaU32,int);
#endif
