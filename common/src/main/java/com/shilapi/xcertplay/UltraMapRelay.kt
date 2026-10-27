package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import com.heavendoor.ultralab.UltraEngine
import com.heavendoor.ultralab.UltraMapBuffers
import com.heavendoor.ultralab.UltraPerformanceBudget
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Real decoded ALT map into the same native canvas; no map pixels or route text logged. */
object UltraMapRelay {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"ultra-map-copy").apply { isDaemon=true } }
    @Volatile private var renderer:ClusterMapRenderer?=null
    private var task:ScheduledFuture<*>?=null
    private val pending=AtomicBoolean()
    @Volatile private var generation=0L
    private var lastCaptureAt=0L
    internal fun bind(value:ClusterMapRenderer) { renderer=value;resume() }
    @JvmStatic @Synchronized fun resume() {
        if(task!=null)return
        val owner=++generation
        lastCaptureAt=0L
        task=worker.scheduleAtFixedRate({
            val now=SystemClock.elapsedRealtime()
            if(now-lastCaptureAt<UltraPerformanceBudget.captureIntervalMs())return@scheduleAtFixedRate
            if(!pending.compareAndSet(false,true))return@scheduleAtFixedRate
            var delivered=false
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                val engine=UltraEngine.peek() ?: return@scheduleAtFixedRate
                if(!engine.started()||!engine.ultraMode()||owner!=generation)return@scheduleAtFixedRate
                lastCaptureAt=SystemClock.elapsedRealtime()
                val frame=renderer?.captureUltra(1920,720) ?: return@scheduleAtFixedRate
                delivered=main.post {
                    try { if(owner==generation&&UltraEngine.peek()===engine&&engine.started())engine.map(UltraEngine.BitmapWrapper(frame.second,frame.first)) else UltraMapBuffers.release(frame.second) }
                    finally { pending.set(false) }
                }
                if(!delivered)UltraMapBuffers.release(frame.second)
            } catch (_: Exception) {
                // A missed readback is retryable; it must not cancel the scheduled relay.
            } finally { if(!delivered)pending.set(false) }
        },0,10,TimeUnit.MILLISECONDS)
    }
    @JvmStatic @Synchronized fun pause() {generation++;task?.cancel(false);task=null}
}
