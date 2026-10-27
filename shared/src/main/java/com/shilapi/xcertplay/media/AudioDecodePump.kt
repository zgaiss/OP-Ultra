package com.shilapi.xcertplay.media

/** Serialize the final playback-start check with shutdown, without locking a blocking write. */
internal class AudioPlaybackGate {
    private var closed = false

    @Synchronized
    fun ifOpen(action: () -> Unit): Boolean {
        if (closed) return false
        action()
        return true
    }

    @Synchronized
    fun close(action: () -> Unit) {
        if (closed) return
        closed = true
        action()
    }
}

/** Retain the current packet on backpressure. No queue, prebuffer, or normal-path sleep. */
internal object AudioDecodePump {
    fun submit(
        isRunning: () -> Boolean,
        drain: () -> Unit,
        tryInput: () -> Boolean,
        onBackpressure: () -> Unit,
    ): Boolean {
        while (isRunning()) {
            drain()
            if (!isRunning()) return false
            if (tryInput()) {
                drain()
                return true
            }
            onBackpressure()
        }
        return false
    }
}

/** A decoder may split one access unit into multiple PCM buffers. Count bytes, not callbacks. */
internal class AudioDecodeWork {
    private var pendingBytes = 0L
    private var uncertain = false
    val hasPending: Boolean get() = uncertain || pendingBytes > 0

    fun input(expectedPcmBytes: Long?) {
        if (expectedPcmBytes == null || expectedPcmBytes <= 0) uncertain = true
        else pendingBytes += expectedPcmBytes
    }

    fun output(bytes: Int) {
        pendingBytes = (pendingBytes - bytes.coerceAtLeast(0)).coerceAtLeast(0)
    }

    /** Unknown packet duration/PCM format keeps the previous output polling behavior. */
    fun requirePolling() { uncertain = true }
}

/** Opus TOC duration at the output sample rate; no packet decoding or allocation. */
internal fun opusDecodedSamples(packet: ByteArray, offset: Int, length: Int, sampleRate: Int): Int? {
    if (offset < 0 || length < 1 || offset > packet.size - length || sampleRate <= 0) return null
    val toc = packet[offset].toInt() and 255
    val config = toc ushr 3
    val frames = when (toc and 3) {
        0 -> 1
        1, 2 -> 2
        else -> if (length >= 2) packet[offset + 1].toInt() and 63 else return null
    }
    if (frames == 0) return null
    val samples = when {
        config < 12 -> sampleRate / 100 * when (config and 3) { 0 -> 1; 1 -> 2; 2 -> 4; else -> 6 }
        config < 16 -> sampleRate / 100 * (1 shl (config and 1))
        else -> sampleRate / 400 * (1 shl (config and 3))
    } * frames
    return samples.takeIf { it > 0 && it <= sampleRate * 120 / 1000 }
}

/** Finish positive short writes; retain the same offset across a transient zero write. */
internal object AudioPcmWriter {
    fun write(
        length: Int,
        isRunning: () -> Boolean,
        write: (offset: Int, remaining: Int) -> Int,
        onProgress: (Int) -> Unit,
        onZero: () -> Unit,
    ): Int {
        require(length >= 0)
        var written = 0
        while (written < length && isRunning()) {
            val count = write(written, length - written)
            check(count >= 0) { "AudioTrack write failed: $count" }
            check(count <= length - written) { "AudioTrack write exceeded requested length" }
            if (count == 0) {
                onZero()
                continue
            }
            written += count
            onProgress(count)
        }
        return written
    }
}
