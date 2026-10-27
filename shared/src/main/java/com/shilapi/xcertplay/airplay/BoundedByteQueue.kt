package com.shilapi.xcertplay.airplay

/** Single-reader incremental framing storage, bounded before growth or copying. */
internal class BoundedByteQueue(private val limit: Int, initialCapacity: Int = 16 * 1024) {
    var bytes = ByteArray(minOf(initialCapacity, limit))
        private set
    var start = 0
        private set
    private var end = 0
    val size: Int get() = end - start

    fun append(source: ByteArray, offset: Int = 0, count: Int = source.size - offset) {
        require(offset >= 0 && count >= 0 && offset <= source.size - count)
        require(count <= limit - size) { "Incremental frame byte limit exceeded" }
        if (bytes.size - end < count) {
            val required = size + count
            if (required > bytes.size) {
                val next = ByteArray(maxOf(required, minOf(limit, bytes.size * 2)))
                bytes.copyInto(next, 0, start, end)
                end = size
                bytes = next
            } else {
                bytes.copyInto(bytes, 0, start, end)
                end = size
            }
            start = 0
        }
        source.copyInto(bytes, end, offset, offset + count)
        end += count
    }

    fun discard(count: Int) {
        require(count in 0..size)
        start += count
        if (start == end) { start = 0; end = 0 }
    }

    fun copy(offset: Int, count: Int): ByteArray {
        require(offset >= 0 && count >= 0 && offset <= size - count)
        return bytes.copyOfRange(start + offset, start + offset + count)
    }
}
