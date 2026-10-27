package com.shilapi.xcertplay.transport

/** Only throttles repeated near-immediate failed I/O, never successful packets or real timeouts. */
internal class FastUsbFailureBackoff {
    private var consecutive = 0
    internal fun delayMillis(result: Int, elapsedNanos: Long): Long {
        if (result > 0 || elapsedNanos >= 2_000_000L) { consecutive = 0; return 0 }
        consecutive = (consecutive + 1).coerceAtMost(8)
        return if (consecutive < 3) 0 else (1L shl (consecutive - 3)).coerceAtMost(20)
    }
    fun onResult(result: Int, elapsedNanos: Long) {
        val delay = delayMillis(result, elapsedNanos)
        if (delay == 0L) return
        try { Thread.sleep(delay) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IphoneUsbException.DeviceUnavailable("Interrupted during USB failure backoff")
        }
    }
}
