# -*- coding: utf-8 -*-
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import zlib,json,math
from pathlib import Path
import argparse
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description="Regenerate cached popup glyphs from the OFL-licensed Noto Sans CJK SC font.")
parser.add_argument('--font',required=True,type=Path)
font_path=parser.parse_args().font
R=ROOT/'build/font-atlases';R.mkdir(parents=True,exist_ok=True)
S=ROOT
items=[];raw=bytearray()
def add(name,img,advance=None,xoff=0,yoff=0):
 off=len(raw);raw.extend(img.tobytes());items.append((name,img.width,img.height,off,img.width if advance is None else advance,xoff,yoff))
def text(name,value,size,bold=False):
 f=ImageFont.truetype(str(font_path),size)
 left,top,right,bottom=f.getbbox(value,anchor='ls');im=Image.new('L',(max(1,right-left),max(1,bottom-top)))
 ImageDraw.Draw(im).text((-left,-top),value,font=f,fill=255,anchor='ls');add(name,im,round(f.getlength(value)),left,top)
for x in range(10):text('NUM_'+str(x),str(x),35,True)
for x in range(10):text('SMALL_'+str(x),str(x),18)
text('DOT','.',35,True)
for name,value,size in [('CELSIUS','℃',18),('FAN_UNIT','挡',18),('SLASH','/',18),('TEMP','空调温度',22),('FAN','空调风量',22),('VOLUME','媒体音量',22),('MUTED','已静音',25),('HVAC','空调',22),('OFF','已关闭',25)]:text(name,value,size)
def icon(name,kind,size):
 scale=size*4/24;im=Image.new('L',(size*4,size*4));d=ImageDraw.Draw(im);width=round(scale*1.8)
 def points(ps):return [(round(x*scale),round(y*scale)) for x,y in ps]
 def line(ps):
  ps=points(ps);d.line(ps,fill=255,width=width,joint='curve');r=width/2
  for x,y in [ps[0],ps[-1]]:d.ellipse((x-r,y-r,x+r,y+r),fill=255)
 def circle(cx,cy,r):d.ellipse(tuple(round(x*scale) for x in (cx-r,cy-r,cx+r,cy+r)),outline=255,width=width)
 def cubic(start,*curves):
  ps=[start];p=start
  for p1,p2,p3 in curves:
   for j in range(1,25):
    t=j/24;v=1-t;ps.append((v**3*p[0]+3*v*v*t*p1[0]+3*v*t*t*p2[0]+t**3*p3[0],v**3*p[1]+3*v*v*t*p1[1]+3*v*t*t*p2[1]+t**3*p3[1]))
   p=p3
  line(ps)
 if kind=='SNOW':
  for ps in [[(12,2),(12,22)],[(3.34,7),(20.66,17)],[(3.34,17),(20.66,7)],[(9,4),(12,7),(15,4)],[(9,20),(12,17),(15,20)],[(3.5,10),(7.6,8.9),(6.5,4.8)],[(20.5,14),(16.4,15.1),(17.5,19.2)],[(6.5,19.2),(7.6,15.1),(3.5,14)],[(17.5,4.8),(16.4,8.9),(20.5,10)]]:line(ps)
 elif kind=='SUN':
  circle(12,12,4.5)
  for angle in range(0,360,45):
   a=math.radians(angle);line([(12+8*math.cos(a),12+8*math.sin(a)),(12+10*math.cos(a),12+10*math.sin(a))])
 elif kind=='FAN_ICON':
  circle(12,12,2)
  cubic((11,9),((7,3),(14,1),(16,5)),((17,7),(15,10),(14,10)))
  cubic((15,11),((21,7),(23,14),(19,16)),((17,17),(14,15),(14,14)))
  cubic((13,15),((17,21),(10,23),(8,19)),((7,17),(9,14),(10,14)))
  cubic((9,13),((3,17),(1,10),(5,8)),((7,7),(10,9),(10,10)))
 else:
  line([(11,4),(6,8),(3,8),(3,16),(6,16),(11,20),(11,4)])
  if kind=='MUTE_ICON':line([(16,9),(21,15)]);line([(21,9),(16,15)])
  else:
   for x,y,r in [(15,8,6),(18,4,11)]:
    cy=12;cx=x-math.sqrt(r*r-(12-y)**2);a=math.asin((12-y)/r)
    line([(cx+r*math.cos(-a+2*a*j/32),cy+r*math.sin(-a+2*a*j/32)) for j in range(33)])
 add(name,im.resize((size,size),Image.Resampling.LANCZOS))
for name in ['SNOW','SUN','FAN_ICON','SPEAKER','MUTE_ICON']:icon(name,name,34)
icon('SNOW_SMALL','SNOW',15);icon('SUN_SMALL','SUN',15)
packed=zlib.compress(raw,9)
out=['/* Generated from Noto Sans CJK (SIL OFL1.1) and our own approved icon shapes. Alpha only. */','#ifndef OP_SELF_ATLAS_H','#define OP_SELF_ATLAS_H','typedef struct { unsigned w,h,off; int advance,xoff,yoff; } NpcGlyph;','enum { '+','.join('NPC_'+x[0]+'='+str(i) for i,x in enumerate(items))+', NPC_GLYPH_COUNT='+str(len(items))+' };','static const NpcGlyph npc_glyphs[]={'+','.join('{%d,%d,%d,%d,%d,%d}'%(w,h,o,a,x,y) for _,w,h,o,a,x,y in items)+'};','enum { NPC_ATLAS_BYTES='+str(len(raw))+' };','static const unsigned char npc_atlas_z[]={']
out+= [','.join(str(x) for x in packed[i:i+64])+',' for i in range(0,len(packed),64)];out+=['};','#endif']
(S/'native/op_self_atlas.h').write_text('\n'.join(out)+'\n');(R/'atlas.json').write_text(json.dumps({'rawBytes':len(raw),'compressedBytes':len(packed),'glyphs':items,'source':'stable original fonts at approved HTML 35/22/18/25px sizes, cached own icons'},ensure_ascii=False,indent=2));print('ATLAS',len(raw),len(packed))
