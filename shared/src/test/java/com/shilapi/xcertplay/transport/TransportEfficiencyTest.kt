package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TransportEfficiencyTest {
    private fun u16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte(); bytes[at + 1] = (value ushr 8).toByte()
    }

    @Test fun selfReferentialNdpRejectsBeforeCreatingDatagrams() {
        val block = Ntb16Codec.build(ByteArray(4068) { 7 }, 1)
        u16(block, 18, 12)
        assertTrue(Ntb16Codec.parse(block).isEmpty())
    }

    @Test fun duplicatedDatagramRangeIsRejected() {
        val block = Ntb16Codec.build(ByteArray(64), 1)
        u16(block, 24, 28); u16(block, 26, 64)
        assertTrue(Ntb16Codec.parse(block).isEmpty())
    }

    @Test fun multipleNonOverlappingDatagramsKeepWireOrder() {
        val block = Ntb16Codec.build(ByteArray(8) { it.toByte() }, 1)
        u16(block, 22, 4); u16(block, 24, 32); u16(block, 26, 4)
        val decoded = Ntb16Codec.parse(block)
        assertEquals(2, decoded.size)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), decoded[0])
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), decoded[1])
    }

    @Test fun legitimateRandomNtbRoundTripsAtOffsetAndPadBoundary() {
        for (length in listOf(1, 484, 485, 1500, 16384, 65507)) {
            val wire = ByteArray(length).also { Random(length.toLong()).nextBytes(it) }
            val block = Ntb16Codec.build(wire, length % 65536)
            val container = ByteArray(7) + block + ByteArray(3)
            assertArrayEquals(wire, Ntb16Codec.parse(container, 7, block.size).single())
        }
    }

    @Test fun malformedTableBoundsRejectWithoutCopy() {
        val block = Ntb16Codec.build(ByteArray(16), 1)
        u16(block, 16, 65532)
        assertTrue(Ntb16Codec.parse(block).isEmpty())
    }

    @Test fun byteBudgetRejectsOverflowAndAllowsReuseAfterRelease() {
        val budget = PendingByteBudget(100)
        assertTrue(budget.reserve(60)); assertFalse(budget.reserve(41))
        assertTrue(budget.reserve(40)); assertEquals(100, budget.used())
        budget.release(60); assertTrue(budget.reserve(60)); assertEquals(100, budget.used())
        budget.release(100); assertEquals(0, budget.used())
    }

    @Test fun byteBudgetIsSharedAcrossConcurrentConsumers() {
        val budget = PendingByteBudget(64)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..4).map { pool.submit<Int> { start.await(); repeat(1000) {
                if (budget.reserve(16)) { assertTrue(budget.used() <= 64); budget.release(16) }
            }; 0 } }
            start.countDown(); jobs.forEach { it.get() }; assertEquals(0, budget.used())
        } finally { pool.shutdownNow() }
    }

    @Test fun realTimeoutsAndSuccessNeverAddUsbDelay() {
        val backoff = FastUsbFailureBackoff()
        repeat(10) { assertEquals(0L, backoff.delayMillis(-1, 100_000_000)) }
        repeat(10) { assertEquals(0L, backoff.delayMillis(512, 1_000)) }
    }

    @Test fun repeatedFastFailuresBackOffWithBoundAndSuccessResets() {
        val backoff = FastUsbFailureBackoff()
        assertEquals(0L, backoff.delayMillis(-1, 0))
        assertEquals(0L, backoff.delayMillis(-1, 0))
        assertEquals(1L, backoff.delayMillis(-1, 0))
        repeat(20) { assertTrue(backoff.delayMillis(-1, 0) <= 20L) }
        assertEquals(0L, backoff.delayMillis(1, 0))
        assertEquals(0L, backoff.delayMillis(-1, 0))
    }

    @Test fun tlsQueueMatchesByteStreamAcrossFragmentAndCompaction() {
        val type = Class.forName("com.shilapi.xcertplay.transport.TlsDuplexChannel\$ByteQueue")
        val queue = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val append = type.getDeclaredMethod("append", ByteArray::class.java).apply { isAccessible = true }
        val take = type.getDeclaredMethod("take", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val view = type.getDeclaredMethod("asByteBuffer").apply { isAccessible = true }
        val expected = ArrayList<Byte>()
        val random = Random(1352)
        repeat(1000) {
            val next = ByteArray(random.nextInt(200)).also(random::nextBytes)
            append.invoke(queue, next); expected.addAll(next.toList())
            val maximum = 1 + random.nextInt(260)
            val actual = take.invoke(queue, maximum) as ByteArray?
            if (expected.isEmpty()) assertNull(actual) else {
                val count = minOf(expected.size, maximum)
                assertArrayEquals(expected.take(count).toByteArray(), actual)
                repeat(count) { expected.removeAt(0) }
            }
            val remaining = view.invoke(queue) as ByteBuffer
            assertArrayEquals(expected.toByteArray(), ByteArray(remaining.remaining()).also(remaining::get))
        }
    }
}
