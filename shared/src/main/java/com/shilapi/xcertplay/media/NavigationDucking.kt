package com.shilapi.xcertplay.media

/** Stream-owned gain control; it never changes the system/phone/navigation volume. */
internal class NavigationDucking {
    private val audibleUntil = mutableMapOf<Any, Long>()
    private val owners = mutableSetOf<Any>()
    @Synchronized fun register(owner: Any) { owners.add(owner) }
    @Synchronized fun audible(owner: Any, nowMs: Long, queuedDurationMs: Long) {
        if (owner !in owners) return
        audibleUntil[owner] = nowMs + queuedDurationMs.coerceIn(0, 1500) + 250
    }
    @Synchronized fun remove(owner: Any) { owners.remove(owner); audibleUntil.remove(owner) }
    @Synchronized fun clear() { owners.clear(); audibleUntil.clear() }
    @Synchronized fun gain(nowMs: Long): Float {
        audibleUntil.entries.removeAll { it.value <= nowMs }
        return if (audibleUntil.isEmpty()) 1f else .25f
    }
    companion object {
        fun hasSound(bytes: ByteArray, offset: Int, length: Int): Boolean {
            var i = offset
            while (i + 1 < offset + length) {
                val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                if (sample > 16 || sample < -16) return true
                i += 2
            }
            return false
        }
    }
}

/** Frame-based gain ramps avoid clicks, without buffering or delaying any PCM. */
internal class PcmGainRamp {
    private var gain = 1f
    private var target = 1f
    private var step = 0f
    private var remaining = 0
    fun apply(bytes: ByteArray, offset: Int, length: Int, channels: Int, sampleRate: Int, wanted: Float) {
        require(channels > 0 && sampleRate > 0 && offset >= 0 && length >= 0 && offset <= bytes.size - length)
        val next = wanted.coerceIn(0f, 1f)
        if (next != target) {
            target = next
            val durationMs = if (target < gain) 35 else 180
            remaining = maxOf(1, sampleRate * durationMs / 1000)
            step = (target - gain) / remaining
        }
        if (gain == 1f && target == 1f) return
        val frameSize = channels * 2
        var frame = offset
        while (frame + frameSize <= offset + length) {
            if (remaining > 0) { gain += step; remaining--; if (remaining == 0) gain = target }
            for (channel in 0 until channels) {
                val at = frame + channel * 2
                val sample = ((bytes[at].toInt() and 255) or (bytes[at + 1].toInt() shl 8)).toShort().toInt()
                val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
                bytes[at] = scaled.toByte(); bytes[at + 1] = (scaled shr 8).toByte()
            }
            frame += frameSize
        }
    }
}
