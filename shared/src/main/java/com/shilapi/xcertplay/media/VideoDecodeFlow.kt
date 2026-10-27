package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Apply TCP backpressure to compressed pictures, never discard a reference on queue overflow. */
internal class VideoWorkQueue<T>(capacity: Int) : Closeable {
    private data class Entry<T>(val value: T, val frame: Boolean)
    private val entries = LinkedBlockingQueue<Entry<T>>()
    private val slots = Semaphore(capacity)
    @Volatile private var closed = false
    val size: Int get() = entries.size

    fun frame(value: T) {
        while (!closed) {
            if (!slots.tryAcquire(50, TimeUnit.MILLISECONDS)) continue
            synchronized(this) {
                if (closed) slots.release() else entries.offer(Entry(value, true))
            }
            return
        }
    }

    @Synchronized
    fun control(value: T) {
        if (!closed) entries.offer(Entry(value, false))
    }

    fun poll(timeoutMs: Long): T? {
        val entry = entries.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (entry.frame) slots.release()
        return entry.value
    }

    fun take(): T {
        val entry = entries.take()
        if (entry.frame) slots.release()
        return entry.value
    }

    @Synchronized
    override fun close() {
        closed = true
        while (true) {
            val entry = entries.poll() ?: break
            if (entry.frame) slots.release()
        }
    }
}

/** A tick must run even when the sender is idle. An unavailable input buffer is not a lost frame. */
internal class VideoDecodePump(private val port: Port) {
    interface Port {
        fun drain()
        fun queue(bytes: ByteArray, presentationTimeUs: Long): Boolean
    }
    private data class Input(val bytes: ByteArray, val ptsUs: Long)
    private var pending: Input? = null
    val hasPending: Boolean get() = pending != null

    fun submit(bytes: ByteArray, presentationTimeUs: Long) {
        check(pending == null)
        pending = Input(bytes, presentationTimeUs)
    }

    fun tick() {
        port.drain()
        pending?.let { if (port.queue(it.bytes, it.ptsUs)) pending = null }
        port.drain()
    }
}

/** Every new decoder needs random access. CRA's RASL pictures may refer to the discarded GOP. */
internal class VideoSyncGate(private val codec: VideoCodec) {
    private var waiting = true
    private var skipRasl = false
    val waitingForRandomAccess: Boolean get() = waiting

    fun accept(annexB: ByteArray): Boolean {
        var randomAccess = false
        var rasl = false
        var trailing = false
        val count = MediaCodecSupport.visitAnnexBNalUnits(annexB) { offset, length ->
            require(annexB[offset].toInt() and 0x80 == 0) { "Invalid NAL header" }
            val type = if (codec == VideoCodec.H265) {
                require(length >= 2 && annexB[offset + 1].toInt() and 7 != 0) { "Invalid HEVC NAL header" }
                (annexB[offset].toInt() ushr 1) and 0x3f
            } else annexB[offset].toInt() and 0x1f
            randomAccess = randomAccess || if (codec == VideoCodec.H265) type in 16..21 else type == 5
            rasl = rasl || type == 8 || type == 9
            trailing = trailing || type in 0..5 || type in 10..15
        }
        require(count > 0) { "Empty or malformed video access unit" }
        if (waiting) {
            if (!randomAccess) return false
            waiting = false
            skipRasl = codec == VideoCodec.H265
        }
        if (skipRasl) {
            if (rasl) return false
            if (trailing) skipRasl = false
        }
        return true
    }
}
