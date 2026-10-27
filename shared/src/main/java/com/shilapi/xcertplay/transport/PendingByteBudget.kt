package com.shilapi.xcertplay.transport

import java.util.concurrent.atomic.AtomicInteger

/** Shared connection budget with no connection/host lock ordering dependency. */
internal class PendingByteBudget(private val maximum: Int) {
    private val bytes = AtomicInteger()
    init { require(maximum > 0) }
    fun reserve(count: Int): Boolean {
        require(count >= 0)
        while (true) {
            val current = bytes.get()
            if (count > maximum - current) return false
            if (bytes.compareAndSet(current, current + count)) return true
        }
    }
    fun release(count: Int) {
        require(count >= 0)
        val left = bytes.addAndGet(-count)
        check(left >= 0) { "Receive budget released twice" }
    }
    internal fun used(): Int = bytes.get()
}
