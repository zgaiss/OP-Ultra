package com.heavendoor.ultralab;
import org.junit.Test;
import static org.junit.Assert.*;
public class UltraPerformanceBudgetTest {
 @Test public void fastReceiverDoesNotExceedTenFrames(){UltraPerformanceBudget p=new UltraPerformanceBudget();for(int i=0;i<20;i++)assertEquals(100,p.observeReceiver(8));}
 @Test public void slowReceiverAppliesBackpressureAndRecoversGradually(){UltraPerformanceBudget p=new UltraPerformanceBudget();assertEquals(250,p.observeReceiver(200));int n=0;for(int i=0;i<30;i++)n=p.observeReceiver(10);assertEquals(100,n);}
 @Test public void overloadedReceiverRemainsBounded(){UltraPerformanceBudget p=new UltraPerformanceBudget();assertEquals(400,p.observeReceiver(4500));UltraPerformanceBudget.requestedInterval(-1);assertEquals(100,UltraPerformanceBudget.captureIntervalMs());UltraPerformanceBudget.requestedInterval(9999);assertEquals(400,UltraPerformanceBudget.captureIntervalMs());UltraPerformanceBudget.requestedInterval(100);}
 @Test(expected=IllegalArgumentException.class) public void invalidAckRejected(){new UltraPerformanceBudget().observeReceiver(-1);}
}
