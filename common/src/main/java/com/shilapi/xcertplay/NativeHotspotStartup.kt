package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Same-process service/Activity rendezvous. Monotonic timestamps survive app recreation. */
internal object NativeHotspotStartup {
    private val gate = HotspotConnectionGate()
    private var initialized = false
    private var beganAt = -60_000L
    private fun prefs(c: Context) = c.getSharedPreferences("op_hotspot_startup", Context.MODE_PRIVATE)
    private fun boot(c: Context) = Settings.Global.getInt(c.contentResolver, "boot_count", -1)
    @Synchronized fun begin(c: Context) {
        val now = SystemClock.elapsedRealtime(); val p = prefs(c)
        if (initialized && now - beganAt in 0..1499) return
        beganAt = now
        val currentBoot = boot(c)
        val own = currentBoot >= 0 && p.getInt("boot", -2) == currentBoot && p.getLong("requested", -1).let { it >= 0 && now - it in 0..60_000 }
        gate.begin(now, own, if (own) p.getLong("ready", -1).takeIf { it >= 0 } else null)
        initialized = true
    }
    @Synchronized fun requested(c: Context) {
        gate.requested()
        prefs(c).edit().putInt("boot", boot(c)).putLong("requested", SystemClock.elapsedRealtime()).remove("ready").commit()
    }
    @Synchronized fun ready(c: Context) {
        gate.ready(SystemClock.elapsedRealtime())
        gate.readyTimestamp()?.let { prefs(c).edit().putLong("ready", it).apply() }
    }
    @Synchronized fun fail() = gate.fail()
    @Synchronized fun remaining(c: Context): Long {
        if (!NativeHotspotPreferences.allowed(c)) return 0
        if (!initialized) begin(c)
        return gate.remaining(SystemClock.elapsedRealtime())
    }
}
