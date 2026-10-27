package com.heavendoor.ultralab;

import java.util.Arrays;

/** Lossless RGB565 transport. Each connection owns its reference; no display pixels are used as a delta base. */
public final class UltraTileCodec {
    public static final int WIDTH=1920, HEIGHT=720, PIXEL_BYTES=WIDTH*HEIGHT*2;
    public static final int MAX_PACKET=PIXEL_BYTES+16384, MAGIC=0x31504655;
    private static final int TILE=64, COLS=(WIDTH+TILE-1)/TILE, ROWS=(HEIGHT+TILE-1)/TILE;
    private final byte[] previous=new byte[PIXEL_BYTES], packet=new byte[MAX_PACKET];
    private final byte[] tile=new byte[TILE*TILE*2];
    private final int[] changed=new int[COLS*ROWS], hash=new int[4096], marks=new int[4096];
    private int position, priorId=-1, count, generation;
    private long keyAt, fullFrames, deltaFrames, unchangedFrames, rawBytes, encodedBytes, encodeNanos;

    public byte[] packet(){return packet;}
    public long fullFrames(){return fullFrames;}
    public long deltaFrames(){return deltaFrames;}
    public long unchangedFrames(){return unchangedFrames;}
    public long rawBytes(){return rawBytes;}
    public long encodedBytes(){return encodedBytes;}
    public long encodeNanos(){return encodeNanos;}
    /** The next successful frame must be a keyframe after a pause or ownership change. */
    public void reset(){priorId=-1;}
    public int encode(byte[] pixels,int id,long nowMs){
        if(pixels.length!=PIXEL_BYTES||id<0||nowMs<0)throw new IllegalArgumentException("frame bounds");
        long started=System.nanoTime();count=0;
        boolean key=priorId<0||nowMs-keyAt>=3000;
        if(!key){
            for(int y=0;y<HEIGHT;y+=TILE)for(int x=0;x<WIDTH;x+=TILE){
                int w=Math.min(TILE,WIDTH-x),h=Math.min(TILE,HEIGHT-y);boolean differs=false;
                for(int row=0;row<h&&!differs;row++){
                    int start=((y+row)*WIDTH+x)*2,end=start+w*2;
                    for(int i=start;i<end;i++)if(pixels[i]!=previous[i]){differs=true;break;}
                }
                if(differs)changed[count++]=(y/TILE)*COLS+x/TILE;
            }
            key=count*100>COLS*ROWS*55;
        }
        position=0;put32(MAGIC);put32(id);put32(key?-1:priorId);put32(key?1:count);
        if(key){record(0,0,WIDTH,HEIGHT,pixels,PIXEL_BYTES);System.arraycopy(pixels,0,previous,0,PIXEL_BYTES);fullFrames++;keyAt=nowMs;}
        else{
            for(int i=0;i<count;i++){
                int index=changed[i],x=index%COLS*TILE,y=index/COLS*TILE,w=Math.min(TILE,WIDTH-x),h=Math.min(TILE,HEIGHT-y);
                for(int row=0;row<h;row++)System.arraycopy(pixels,((y+row)*WIDTH+x)*2,tile,row*w*2,w*2);
                record(x,y,w,h,tile,w*h*2);
                for(int row=0;row<h;row++)System.arraycopy(tile,row*w*2,previous,((y+row)*WIDTH+x)*2,w*2);
            }
            deltaFrames++;if(count==0)unchangedFrames++;
        }
        priorId=id;rawBytes+=PIXEL_BYTES;encodedBytes+=position;encodeNanos+=System.nanoTime()-started;return position;
    }
    private void record(int x,int y,int w,int h,byte[] input,int n){
        put16(x);put16(y);put16(w);put16(h);int sizeAt=position;put32(0);
        int size=compress(input,n,position)-position;boolean raw=size>=n;int bytes=raw?n:size;
        if(position>packet.length-bytes)throw new IllegalStateException("packet bound");
        if(raw)System.arraycopy(input,0,packet,position,n);
        int stored=(raw?0x80000000:0)|bytes;
        for(int i=0;i<4;i++)packet[sizeAt+i]=(byte)(stored>>>(i*8));position+=bytes;
    }
    private void put16(int v){packet[position++]=(byte)v;packet[position++]=(byte)(v>>>8);}
    private void put32(int v){put16(v);put16(v>>>16);}
    private static int word(byte[] b,int p){return(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|(b[p+3]<<24);}
    private int length(int out,int n){while(n>=255){packet[out++]=(byte)255;n-=255;}packet[out++]=(byte)n;return out;}
    /** Standard LZ4 block encoder; last five literals and last-match start follow the upstream block specification. */
    private int compress(byte[] in,int n,int out){
        if(++generation==0){Arrays.fill(marks,0);generation=1;}
        int p=0,anchor=0;
        while(p<=n-12){
            int value=word(in,p),slot=(value*0x9e3779b1)>>>20;
            int match=marks[slot]==generation?hash[slot]:-1;hash[slot]=p;marks[slot]=generation;
            if(match<0||p-match>65535||word(in,match)!=value){p++;continue;}
            int begin=p,ref=match;p+=4;match+=4;
            while(p<n-5&&in[p]==in[match]){p++;match++;}
            int literals=begin-anchor,extra=p-begin-4;
            packet[out++]=(byte)((Math.min(literals,15)<<4)|Math.min(extra,15));
            if(literals>=15)out=length(out,literals-15);
            System.arraycopy(in,anchor,packet,out,literals);out+=literals;
            int offset=begin-ref;packet[out++]=(byte)offset;packet[out++]=(byte)(offset>>>8);
            if(extra>=15)out=length(out,extra-15);
            anchor=p;
            if(p>=2&&p<=n-12){int back=p-2,s=(word(in,back)*0x9e3779b1)>>>20;hash[s]=back;marks[s]=generation;}
        }
        int literals=n-anchor;packet[out++]=(byte)(Math.min(literals,15)<<4);
        if(literals>=15)out=length(out,literals-15);
        System.arraycopy(in,anchor,packet,out,literals);return out+literals;
    }
}
