"""Offline layout + fixed-tier sprite generation. No per-frame text/layout work."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,ImageFilter
import numpy as np
import json,zlib,hashlib,re,collections
from pathlib import Path
import argparse
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description="Regenerate cached popup glyphs from the OFL-licensed Noto Sans CJK SC font.")
parser.add_argument('--font',required=True,type=Path)
font_path=parser.parse_args().font
R=ROOT/'build/font-atlases';R.mkdir(parents=True,exist_ok=True)
S=ROOT
N=S/'native'
P=N/'alert-data/catalogue.json';records=json.loads(P.read_text());sets=json.loads((N/'alert-data/channel-map.json').read_text())['channels'];primary=set(sets['primary']['ids']);auto=set(sets['autopilot']['ids'])
fonts=[ImageFont.truetype(str(font_path),34),ImageFont.truetype(str(font_path),26)]
TIERS=(400,480,620);TRACK=.35;BOUNDARY=4;PAD=32;LH=(42.5,33.8);DETAIL_GAP=6
raw=bytearray();glyphs=[];lookup={};runs=[];table={};proof=[]
for style,font in enumerate(fonts):
 needed=sorted(set(''.join(x['text'].split('\n')[0] if style==0 else ''.join(x['text'].split('\n')[1:]) for x in records)))
 for char in needed:
  l,t,r,b=font.getbbox(char,anchor='ls');w,h=max(1,r-l),max(1,b-t);im=Image.new('L',(w,h));ImageDraw.Draw(im).text((-l,-t),char,font=font,fill=255,anchor='ls');lookup[style,char]=len(glyphs);glyphs.append((w,h,len(raw)));raw.extend(im.tobytes())
def cjk(c):return bool(c) and 0x3400<=ord(c)<=0x9fff
def latin(c):return bool(c) and c.isascii() and c.isalnum()
def before(prev,c,font):
 if not prev:return 0
 n=TRACK
 if (cjk(prev) and latin(c)) or (latin(prev) and cjk(c)):n+=BOUNDARY
 if latin(prev) and latin(c):n+=font.getlength(prev+c)-font.getlength(prev)-font.getlength(c)
 return n
def width(text,font):
 x=0;prev=''
 for c in text:x+=before(prev,c,font)+font.getlength(c);prev=c
 return x
def wrap(text,font,limit):
 units=re.findall(r'[A-Za-z0-9]+(?:[./_-][A-Za-z0-9]+)*|.',text);lines=[];line=[]
 for unit in units:
  if line and width(''.join(line)+unit,font)>limit:
   if unit in '，。、；：？！）》】」』…' and len(line)>1:
    tail=line.pop();lines.append(''.join(line));line=[tail]
   elif line[-1] in '（《【「『' and len(line)>1:
    tail=line.pop();lines.append(''.join(line));line=[tail]
   else:lines.append(''.join(line));line=[]
  line.append(unit)
 if line or not lines:lines.append(''.join(line))
 assert ''.join(lines)==text
 assert all(width(x,font)<=limit+.001 for x in lines),(text,limit,lines)
 return lines
for item in records:
 i=int(item['id']);t=item['template'];special=t.startswith('AutonomousDriving');original_multi='Multi-line' in t or '\n' in item['text'];center=360 if special else (232 if '_ADAS' in t else 376)+(64 if original_multi else 50)
 explicit=item['text'].split('\n');longest=max(width(line,fonts[0 if j==0 else 1]) for j,line in enumerate(explicit));w=next((w for w in TIERS if longest<=w-PAD*2),TIERS[-1]);lines=[]
 for j,line in enumerate(explicit):
  style=0 if j==0 else 1
  for k,value in enumerate(wrap(line,fonts[style],w-PAD*2)):lines.append((value,style,DETAIL_GAP if j and k==0 else 0,bool(j and k==0)))
 h=88 if len(lines)==1 else 120;content_h=sum(LH[style]+gap for value,style,gap,source_break in lines);assert content_h+32<=h+.001,(i,content_h,h)
 top=(h-content_h)/2;begin=len(runs);lp=[]
 for value,style,gap,source_break in lines:
  top+=gap;font=fonts[style];asc,desc=font.getmetrics();baseline=top+(LH[style]-asc-desc)/2+asc;measure=width(value,font);x=(w-measure)/2 if len(lines)==1 else PAD;start=x;prev=''
  for char in value:
   x+=before(prev,char,font);l,tb,r,b=font.getbbox(char,anchor='ls');gx,gy=round(x+l),round(baseline+tb);runs.append((lookup[style,char],gx,gy,style));
   if not char.isspace():assert gx>=PAD-1 and round(x+r)<=w-PAD+1 and gy>=12 and round(baseline+b)<=h-12,(i,char,gx,gy,w,h)
   x+=font.getlength(char);prev=char
  lp.append({'text':value,'style':style,'x':start,'width':measure,'top':top,'lineHeight':LH[style],'gap':gap,'sourceBreak':source_break});top+=LH[style]
 flags=(1 if i in primary else 2 if i in auto else 4 if i==125 else 0)|(0 if special else 8)|(16 if '_Red' in t or i==125 else 0)
 table[i]=[begin,len(runs)-begin,center,w,h,flags];proof.append({'id':i,'text':item['text'],'center':center,'width':w,'height':h,'flags':flags,'align':'center' if len(lines)==1 else 'left','radius':22 if h==88 else 24,'lines':lp,'productionPositionVerified':not special})
# Each texture is an independent compressed, immutable RGB565+alpha sprite.
# Runtime keeps only three decoded templates, matching the three source slots.
def rounded(x,y,w,h,r):
 dx=np.where(x<r,r-x-1,np.where(x>=w-r,x-(w-r),0));dy=np.where(y<r,r-y-1,np.where(y>=h-r,y-(h-r),0));z=dx*dx+dy*dy;inside=(r-1)**2;outside=(r+1)**2
 return np.where((x<0)|(y<0)|(x>=w)|(y>=h),0,np.where((dx==0)|(dy==0),256,np.where(z<=inside,256,np.where(z>=outside,0,(outside-z)*256//(outside-inside))))).clip(0,256).astype(np.float32)
def texture(w,h,night,red):
 ow,oh=w+48,h+46;rad=22 if h==88 else 24;x,y=np.meshgrid(np.arange(w),np.arange(h));m=rounded(x,y,w,h,rad);inner=rounded(x-1,y-1,w-2,h-2,rad-1);edge=(m-inner).clip(0,256)
 t=(x*500+y*866)*256//(w*500+h*866);t=np.where(t>=200,256,t*256//200)
 top=(37,43,54) if night else (255,254,251);bottom=(24,29,38) if night else (251,250,247);color=np.stack([((a*(256-t)+b*t)//256) for a,b in zip(top,bottom)],axis=-1).astype(np.float32)
 ec=(240,138,143) if night and red else (181,43,50) if red else (201,210,226) if night else (255,255,255);ea=(85 if night else 69) if red else (42 if night else 236);q=edge*ea/65536;color=color*(1-q[:,:,None])+np.array(ec)*q[:,:,None]
 if red:color[h//2-20:h//2+20,:3]=ec
 ba=np.zeros((oh,ow),np.float32);ba[14:14+h,24:24+w]=m/256;body=np.zeros((oh,ow,3),np.float32);body[14:14+h,24:24+w]=color
 shadow=np.zeros((oh,ow),np.float32)
 for dy,sigma,alpha in ((8,12,28),(1,1,14)) if night else ((8,10,18),(1,1,6)):
  layer=Image.new('L',(ow,oh));ImageDraw.Draw(layer).rounded_rectangle((24,14+dy,24+w-1,14+dy+h-1),radius=rad,fill=alpha);a=np.asarray(layer.filter(ImageFilter.GaussianBlur(sigma)),dtype=np.float32)/255;shadow=a+shadow*(1-a)
 total=ba+shadow*(1-ba);sc=np.array((0,0,0) if night else (53,68,84),dtype=np.float32);premult=body*ba[:,:,None]+sc*shadow[:,:,None]*(1-ba[:,:,None]);rgb=np.divide(premult,total[:,:,None],out=np.zeros_like(premult),where=total[:,:,None]>0);rgb=np.rint(rgb).clip(0,255).astype(np.uint32)
 yy,xx=np.indices((oh,ow));bayer=np.array([[0,8,2,10],[12,4,14,6],[3,11,1,9],[15,7,13,5]],dtype=np.uint32)[yy%4,xx%4];channels=[((rgb[:,:,k]*levels*16+bayer*255)//4080) for k,levels in enumerate((31,63,31))];pixels=((channels[0]<<11)|(channels[1]<<5)|channels[2]).astype('<u2');alpha=np.rint(total*255).clip(0,255).astype(np.uint8)
 return pixels.tobytes()+alpha.tobytes()
texture_keys=sorted({(v[3],v[4],night,bool(v[5]&16)) for v in table.values() for night in (0,1)});packed=bytearray();tex=[];keymap={}
for key in texture_keys:
 payload=texture(*key);z=zlib.compress(payload,9);keymap[key]=len(tex);tex.append((key[0]+48,key[1]+46,len(payload),len(packed),len(z)));packed.extend(z)
for i,v in table.items():v.extend(keymap[(v[3],v[4],night,bool(v[5]&16))] for night in (0,1))
font_z=zlib.compress(raw,9);head=['/* Generated offline: original words, fixed geometry, cached alpha glyphs and sprites.',' * Noto Sans CJK raster subset, SIL OFL1.1; see third_party/noto-cjk/OFL.txt. */','#ifndef OP_ALERT_ATLAS_H','#define OP_ALERT_ATLAS_H','typedef struct { unsigned short w,h; unsigned offset; } NaGlyph;','typedef struct { unsigned short glyph; short x,y; unsigned char style; } NaGlyphRun;','typedef struct { unsigned short first,count,center,width,height,flags,texture[2]; } NaRecord;','typedef struct {unsigned short w,h;unsigned raw,offset,length;} NaTexture;',f'enum {{NA_ATLAS_BYTES={len(raw)},NA_GLYPH_COUNT={len(glyphs)},NA_RUN_COUNT={len(runs)},NA_TEXTURE_COUNT={len(tex)},NA_TEXTURE_Z_BYTES={len(packed)}}};','static const NaGlyph na_glyphs[]={'+','.join('{%d,%d,%d}'%x for x in glyphs)+'};','static const NaGlyphRun na_runs[]={'+','.join('{%d,%d,%d,%d}'%x for x in runs)+'};','static const NaRecord na_records[256]={'+','.join('[%d]={%d,%d,%d,%d,%d,%d,{%d,%d}}'%(i,*table[i]) for i in sorted(table))+'};','static const NaTexture na_textures[]={'+','.join('{%d,%d,%d,%d,%d}'%t for t in tex)+'};']
for name,data in [('na_atlas_z',font_z),('na_textures_z',packed)]:
 head.append('static const unsigned char '+name+'[]={');head.extend(','.join(map(str,data[i:i+64]))+',' for i in range(0,len(data),64));head.append('};')
head.append('#endif');(N/'op_alert_atlas.h').write_text('\n'.join(head)+'\n')
summary={'sourceCatalogueSha256':hashlib.sha256(P.read_bytes()).hexdigest(),'recordCount':len(table),'actualFonts':[{'name':f.getname(),'size':f.size} for f in fonts],'typography':{'titlePx':34,'detailPx':26,'trackingPx':TRACK,'cjkLatinGapPx':BOUNDARY,'lineHeight':LH,'detailGap':DETAIL_GAP},'geometry':{'widths':TIERS,'heights':[88,120],'align':'single centered, multiple left','buckets':dict(collections.Counter(f'{r["width"]}x{r["height"]}' for r in proof))},'glyphs':len(glyphs),'runs':len(runs),'rawBytes':len(raw),'compressedBytes':len(font_z),'glyphAlphaBytes':len(raw),'templateCount':len(tex),'templateCompressedBytes':len(packed),'templateCacheEntries':3,'records':proof,'heights':[88,120],'notVehicleRenderingProof':True}
(R/'alert-atlas.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));preview={str(p['id']):{k:p[k] for k in ('width','height','center','align','radius','lines')} for p in proof};(R/'preview-layouts.json').write_text(json.dumps(preview,ensure_ascii=False,separators=(',',':')))
print(json.dumps({k:v for k,v in summary.items() if k!='records'},ensure_ascii=False))
