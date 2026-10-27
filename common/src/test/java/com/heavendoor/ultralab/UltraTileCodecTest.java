package com.heavendoor.ultralab;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;
public class UltraTileCodecTest {
 private static int u(byte[] b,int p){return(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|(b[p+3]<<24);}
 @Test public void unchangedFrameIsSixteenBytes(){UltraTileCodec c=new UltraTileCodec();byte[] f=new byte[UltraTileCodec.PIXEL_BYTES];c.encode(f,1,0);assertEquals(16,c.encode(f,2,100));assertEquals(1,u(c.packet(),8));assertEquals(0,u(c.packet(),12));}
 @Test public void changedLastPixelUsesOneBoundedEdgeTile(){UltraTileCodec c=new UltraTileCodec();byte[] f=new byte[UltraTileCodec.PIXEL_BYTES];c.encode(f,1,0);f[f.length-1]=7;c.encode(f,2,100);byte[] b=c.packet();assertEquals(1,u(b,12));assertEquals(1856,(b[16]&255)|((b[17]&255)<<8));assertEquals(704,(b[18]&255)|((b[19]&255)<<8));assertEquals(16,b[22]&255);}
 @Test public void resetAndPeriodicKeyframeDoNotDependOnOldPixels(){UltraTileCodec c=new UltraTileCodec();byte[] f=new byte[UltraTileCodec.PIXEL_BYTES];c.encode(f,1,0);c.encode(f,2,3000);assertEquals(-1,u(c.packet(),8));c.reset();c.encode(f,3,3100);assertEquals(-1,u(c.packet(),8));}
 @Test public void incompressibleKeyframeFallsBackToRawWithinBound(){UltraTileCodec c=new UltraTileCodec();byte[] f=new byte[UltraTileCodec.PIXEL_BYTES];new Random(55).nextBytes(f);int n=c.encode(f,1,0);assertTrue(n<=UltraTileCodec.MAX_PACKET);assertEquals(0x80000000|f.length,u(c.packet(),24));assertEquals(f.length+28,n);}
 @Test(expected=IllegalArgumentException.class) public void malformedCanvasRejected(){new UltraTileCodec().encode(new byte[5],1,0);}
}
