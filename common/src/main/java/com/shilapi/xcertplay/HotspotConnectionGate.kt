package com.shilapi.xcertplay

/** A ready AP is not necessarily discoverable yet. Only an AP started by OP settles. */
internal class HotspotConnectionGate {
    private var checking = false
    private var failed = false
    private var deadline = 0L
    private var requested = false
    private var readyAt: Long? = null

    fun begin(now: Long, ownRequest: Boolean = false, restoredReadyAt: Long? = null) {
        checking = true; failed = false; deadline = now + 60_000
        requested = ownRequest
        readyAt = restoredReadyAt?.takeIf { it <= now }
    }
    fun requested() { requested = true; readyAt = null }
    fun ready(now: Long) {
        checking = false
        if (requested && readyAt == null) readyAt = now
    }
    fun fail() { checking = false; failed = true }
    fun readyTimestamp(): Long? = readyAt
    fun remaining(now: Long): Long {
        if (failed || checking && now >= deadline) return -1
        if (checking) return 500
        return if (requested) readyAt?.let { (SETTLE_MS - (now - it)).coerceAtLeast(0) } ?: -1 else 0
    }
    companion object { const val SETTLE_MS = 18_000L }
}
