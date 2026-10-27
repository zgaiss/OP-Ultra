package com.shilapi.xcertplay

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class InstrumentFrameCacheTest {
    private fun key() = InstrumentFrameKey(1, 5, 5, 1328, 664, 660, 660,
        P7InstrumentFraming.crop(1328, 664, false), false)

    @Test fun identicalRequestReusesEncodedFrameButEveryPixelInputInvalidatesIt() {
        val cache = InstrumentFrameCache()
        val key = key()
        val frame = InstrumentEncodedFrame(5, byteArrayOf(1, 2, 3, 4))
        assertSame(frame, cache.put(key, frame))
        assertSame(frame, cache.get(key.copy()))
        val alternatives = listOf(
            key.copy(generation = 2), key.copy(sequence = 6), key.copy(transformRevision = 6),
            key.copy(sourceWidth = 664), key.copy(sourceHeight = 665), key.copy(width = 664),
            key.copy(height = 664), key.copy(crop = key.crop.copy(left = 544)),
            key.copy(amapNavigating = true),
        )
        alternatives.forEach { assertNull(cache.get(it)) }
        cache.clear()
        assertNull(cache.get(key))
    }

    @Test fun navigationChangeWithoutNewSourceFrameInvalidatesFrame() {
        val cache = InstrumentFrameCache()
        val old = key()
        cache.put(old, InstrumentEncodedFrame(old.sequence, byteArrayOf(1)))
        val navigating = old.copy(amapNavigating = true, crop = P7InstrumentFraming.crop(1328, 664, true))
        assertEquals(old.sequence, navigating.sequence)
        assertNull(cache.get(navigating))
    }

    @Test fun timeoutDisposesLateResultAndAcceptedResultIsTransferredOnce() {
        val disposed = AtomicInteger()
        val abandoned = InstrumentCaptureRequest<Any> { disposed.incrementAndGet() }
        assertNull(abandoned.await(1))
        abandoned.complete(Any())
        assertEquals(1, disposed.get())
        val accepted = InstrumentCaptureRequest<Any> { disposed.incrementAndGet() }
        val result = Any()
        accepted.complete(result)
        assertSame(result, accepted.await(100))
        accepted.abandon()
        assertEquals(1, disposed.get())
    }

    @Test fun abandonAfterCompletionReleasesUnclaimedResultExactlyOnce() {
        val disposed = AtomicInteger()
        val request = InstrumentCaptureRequest<Any> { disposed.incrementAndGet() }
        request.complete(Any())
        request.abandon()
        request.abandon()
        assertEquals(1, disposed.get())
        assertNull(request.await(100))
    }
}
