package com.shilapi.xcertplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Every input that can change the rendered pixels, including changes without a source frame. */
internal data class InstrumentFrameKey(
    val generation: Long,
    val sequence: Long,
    val transformRevision: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val width: Int,
    val height: Int,
    val crop: P7InstrumentFraming.Crop,
    val amapNavigating: Boolean,
)

/** Immutable once published. The server may submit the same bytes again to keep the OEM lease. */
internal data class InstrumentEncodedFrame(val sequence: Long, val bytes: ByteArray)

internal class InstrumentFrameCache {
    private var key: InstrumentFrameKey? = null
    private var frame: InstrumentEncodedFrame? = null
    fun get(next: InstrumentFrameKey): InstrumentEncodedFrame? = if (key == next) frame else null
    fun put(next: InstrumentFrameKey, value: InstrumentEncodedFrame): InstrumentEncodedFrame {
        key = next
        frame = value
        return value
    }
    fun clear() { key = null; frame = null }
}

/** Allocation counters describe the production pixel pool, not process RSS or fixture copies. */
internal data class InstrumentRenderStats(
    val bufferGroups: Long,
    val pixelAllocationBytes: Long,
    val readbacks: Long,
    val jpegEncodes: Long,
    val cacheHits: Long,
)

/** A timed-out caller relinquishes ownership; a late producer disposes instead of leaking a result. */
internal class InstrumentCaptureRequest<T>(private val dispose: (T) -> Unit = {}) {
    private val complete = CountDownLatch(1)
    private var abandoned = false
    private var value: T? = null
    fun complete(result: T?) {
        synchronized(this) {
            if (abandoned) result?.let(dispose) else value = result
        }
        complete.countDown()
    }
    fun await(timeoutMillis: Long): T? {
        if (!complete.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            abandon()
            return null
        }
        return synchronized(this) { value.also { value = null } }
    }
    fun abandon() {
        synchronized(this) {
            abandoned = true
            value?.let(dispose)
            value = null
        }
    }
}
