package com.heavendoor.ultralab;
import org.junit.Test;
import static org.junit.Assert.*;

public class UltraFrameHealthTest {
 @Test public void oneMissIsNotTerminal(){UltraFrameHealth h=new UltraFrameHealth();assertEquals(-2,h.unavailable(0));assertEquals(-2,h.unavailable(400));}
 @Test public void persistentLossIsBounded(){UltraFrameHealth h=new UltraFrameHealth();h.unavailable(100);assertEquals(-2,h.unavailable(1299));assertEquals(-1,h.unavailable(1300));}
 @Test public void successfulFrameResetsGrace(){UltraFrameHealth h=new UltraFrameHealth();h.unavailable(0);h.available();assertEquals(-2,h.unavailable(5000));assertEquals(-1,h.unavailable(6200));}
 @Test public void noProjectionBeforeFirstValidSample(){UltraFrameHealth h=new UltraFrameHealth();assertFalse(h.outputActive(false,0));assertFalse(h.outputActive(false,10000));}
 @Test public void shortTelemetryMissKeepsChannelButLongLossReleases(){UltraFrameHealth h=new UltraFrameHealth();assertTrue(h.outputActive(true,0));assertTrue(h.outputActive(false,400));assertTrue(h.outputActive(false,1599));assertFalse(h.outputActive(false,1600));assertFalse(h.outputActive(false,2000));assertTrue(h.outputActive(true,2500));}
 @Test public void explicitStopHasNoGrace(){UltraFrameHealth h=new UltraFrameHealth();assertTrue(h.outputActive(true,0));h.reset();assertFalse(h.outputActive(false,1));}
 @org.junit.Test public void knownThemeTransitionKeepsRetryBoundedWithoutStartingIdleOutput(){UltraFrameHealth h=new UltraFrameHealth();org.junit.Assert.assertFalse(h.outputActive(false,0,6000));org.junit.Assert.assertTrue(h.outputActive(true,10,6000));org.junit.Assert.assertTrue(h.outputActive(false,100,6000));org.junit.Assert.assertTrue(h.outputActive(false,5999,6000));org.junit.Assert.assertFalse(h.outputActive(false,6000,6000));}
 @org.junit.Test public void transitionExpiresAndExplicitResetRemainsImmediate(){UltraFrameHealth h=new UltraFrameHealth();org.junit.Assert.assertEquals(UltraFrameHealth.RETRY,h.unavailable(100,6000));org.junit.Assert.assertEquals(UltraFrameHealth.RETRY,h.unavailable(5999,6000));org.junit.Assert.assertEquals(UltraFrameHealth.STOP,h.unavailable(6000,6000));h.reset();org.junit.Assert.assertFalse(h.outputActive(false,200,6000));}

}
