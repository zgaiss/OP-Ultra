#ifndef OP_ALERT_SOURCE_H
#define OP_ALERT_SOURCE_H
#include "op_text_alert.h"
typedef unsigned long long AsTime;
typedef struct {int status,extra,id;AsTime stamp;} AsValue;
typedef struct {NaState *draw;NaU32 epoch,serial,order,known,orders[3];int raw[2],ids[3];AsTime stamps[2];} AsState;
/* Actual vendor wire type: method result ((iiix),i), property variant (iiix).
 * Extra data is not validity. The SDK checks the first and method statuses. */
int as_parse(const char*,unsigned,int,AsValue*);
int as_property(const char*,unsigned,unsigned*,AsValue*);
void as_reset(AsState*,NaState*,NaU32);
void as_lost(AsState*);
int as_accept(AsState*,unsigned,const AsValue*,NaU32);
void as_health(AsState*,NaU32);
void asl_start(NaState*,NaU32);
void asl_tick(NaU32);
void asl_stop(void);
#endif
