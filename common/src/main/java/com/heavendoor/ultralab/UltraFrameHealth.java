package com.heavendoor.ultralab;

/** A missed canvas is retryable; persistent loss still releases the OEM layer.
 * This clock is independent of phone/session lifetime and never renews an ICM lease.
 */
public final class UltraFrameHealth {
 public static final int STOP=-1,RETRY=-2;
 public static final long GRACE_MS=1200;
 private long missingAt=-1;private boolean active;
 public int unavailable(long now){return unavailable(now,0);}
 public int unavailable(long now,long transitionUntil){if(missingAt<0)missingAt=now;return now<transitionUntil||now-missingAt<GRACE_MS?RETRY:STOP;}
 public void available(){missingAt=-1;}
 public boolean outputActive(boolean ready,long now){return outputActive(ready,now,0);}
 public boolean outputActive(boolean ready,long now,long transitionUntil){if(ready){available();active=true;}else if(active&&unavailable(now,transitionUntil)==STOP)active=false;return active;}
 public void reset(){missingAt=-1;active=false;}
}
