package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PerformanceMediaCoreTest {
    @Test fun malformedLaterNalDoesNotPartiallyMutateTheAccessUnit() {
        val source = byteArrayOf(0, 0, 0, 2, 0x65, 1, 0, 0, 0, 8, 0x41)
        val original = source.copyOf()
        assertEquals(0, MediaCodecSupport.toAnnexBInPlace(source).size)
        assertArrayEquals(original, source)
    }

    @Test fun rangeVisitorMatchesExistingNalCopies() {
        val bytes = byteArrayOf(0, 0, 1, 0x65, 11, 0, 0, 0, 1, 0x41, 22)
        val copies = MediaCodecSupport.annexBNalUnits(bytes)
        val ranges = mutableListOf<ByteArray>()
        assertEquals(2, MediaCodecSupport.visitAnnexBNalUnits(bytes) { start, length ->
            ranges += bytes.copyOfRange(start, start + length)
        })
        copies.zip(ranges).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test fun malformedUnitCannotAdvanceTheRandomAccessGate() {
        val gate = VideoSyncGate(VideoCodec.H265)
        val start = byteArrayOf(0, 0, 0, 1)
        try { gate.accept(start + byteArrayOf(42, 1) + start + byteArrayOf(2)); fail("Bad header accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(gate.waitingForRandomAccess)
        assertFalse(gate.accept(start + byteArrayOf(2, 1)))
        assertTrue(gate.accept(start + byteArrayOf(42, 1)))
        assertFalse(gate.accept(start + byteArrayOf(16, 1)))
        assertTrue(gate.accept(start + byteArrayOf(2, 1)))
        assertTrue(gate.accept(start + byteArrayOf(16, 1)))
    }

    @Test fun adtsOffsetPathIsByteIdenticalToOwnedAccessUnit() {
        val source = ByteArray(100) { (it * 13).toByte() }
        assertArrayEquals(MediaCodecSupport.adtsFrame(source.copyOfRange(12, 87), 48000, 2),
            MediaCodecSupport.adtsFrame(source, 12, 75, 48000, 2))
    }

    @Test fun closingFullQueueUnblocksAProducerWithoutDroppingIntoANewQueue() {
        val queue = VideoWorkQueue<Int>(1)
        queue.frame(1)
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val producer = Thread { entered.countDown(); queue.frame(2); exited.countDown() }
        producer.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        queue.close()
        assertTrue(exited.await(1, TimeUnit.SECONDS))
        producer.join(1000)
        assertEquals(0, queue.size)
    }

    @Test fun microphoneReadsUseTheCaptureRateAndAtMost20ms() {
        assertEquals(640, microphoneReadBytes(20))
        assertEquals(320, microphoneReadBytes(10))
        assertEquals(640, microphoneReadBytes(60))
    }
}
