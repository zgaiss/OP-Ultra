package com.heavendoor.ultralab;

/** Bounded ten-frame target; receiver backpressure slows both capture and output, never queues old frames. */
public final class UltraPerformanceBudget {
    private static volatile int captureInterval=100;
    private double processingMs;
    public static int captureIntervalMs(){return captureInterval;}
    public static void requestedInterval(int ms){captureInterval=Math.max(100,Math.min(400,ms));}
    public int observeReceiver(int ms){
        if(ms<0||ms>4500)throw new IllegalArgumentException("receiver timing");
        processingMs=processingMs==0?ms:processingMs*.8+ms*.2;
        return Math.max(100,Math.min(400,(int)Math.ceil(processingMs*1.25)));
    }
}
