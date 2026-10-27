#ifndef ULTRA_TILE_DECODE_H
#define ULTRA_TILE_DECODE_H
#define ULTRA_PIXEL_BYTES 2764800U
#define ULTRA_MAX_PACKET (ULTRA_PIXEL_BYTES+16384U)
typedef struct { unsigned short x,y,w,h; } UltraRect;
typedef struct { unsigned id,count,key; UltraRect rectangles[360]; } UltraTileUpdate;
/* Returns zero on success. On failure the caller must discard the whole frame and restore OEM output. */
int ultra_tiles_apply(const unsigned char*,unsigned,unsigned char*,int,UltraTileUpdate*);
#endif
