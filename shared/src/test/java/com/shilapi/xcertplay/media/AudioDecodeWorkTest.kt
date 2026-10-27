package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioDecodeWorkTest {
    @Test fun splitPcmOutputsKeepTheTailPending() {
        val work = AudioDecodeWork()
        assertFalse(work.hasPending)
        work.input(4096)
        work.output(2048)
        assertTrue(work.hasPending)
        work.output(2048)
        assertFalse(work.hasPending)
    }

    @Test fun multipleInputsAndUnknownFormatsRemainSafe() {
        val work = AudioDecodeWork()
        work.input(1024); work.input(1024)
        work.output(1024)
        assertTrue(work.hasPending)
        work.output(1024)
        assertFalse(work.hasPending)
        work.requirePolling()
        work.output(10000)
        assertTrue(work.hasPending)
        val unknown = AudioDecodeWork()
        unknown.input(null)
        assertTrue(unknown.hasPending)
    }

    @Test fun opusTocDurationsUseTheOutputRateAndFrameCount() {
        assertEquals(960, opusDecodedSamples(byteArrayOf(0xf8.toByte()), 0, 1, 48000))
        assertEquals(2880, opusDecodedSamples(byteArrayOf(0x18), 0, 1, 48000))
        assertEquals(360, opusDecodedSamples(byteArrayOf(0x83.toByte(), 3), 0, 2, 48000))
        assertNull(opusDecodedSamples(byteArrayOf(0x83.toByte(), 63), 0, 2, 48000))
        assertNull(opusDecodedSamples(byteArrayOf(0x83.toByte()), 0, 1, 48000))
        assertNull(opusDecodedSamples(byteArrayOf(0x83.toByte(), 0), 0, 2, 48000))
    }
}
