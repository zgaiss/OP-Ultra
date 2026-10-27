package com.shilapi.xcertplay.media

import android.util.Log

/** Single-writer timing counters. No payloads, identifiers, keys or per-frame log messages. */
internal class StageTiming(private val label: String) {
    private var since = System.nanoTime()
    private var count = 0L
    private var bytes = 0L
    private var total = 0L
    private var maximum = 0L
    private var slow20 = 0L
    private var slow100 = 0L
    private var failed = 0L

    fun finish(startedNs: Long, transferred: Int = 0) {
        val now = System.nanoTime()
        val duration = (now - startedNs).coerceAtLeast(0L)
        count++; bytes += transferred.coerceAtLeast(0)
        total += duration; maximum = maxOf(maximum, duration)
        if (duration >= 20_000_000) slow20++
        if (duration >= 100_000_000) slow100++
        if (transferred < 0) failed++
        if (now - since < 5_000_000_000) return
        Log.i("OPStagePerf", "stage=$label count=$count bytes=$bytes avgUs=${total/count/1000} " +
            "maxUs=${maximum/1000} slow20=$slow20 slow100=$slow100 negative=$failed")
        since=now; count=0; bytes=0; total=0; maximum=0; slow20=0; slow100=0; failed=0
    }
}
