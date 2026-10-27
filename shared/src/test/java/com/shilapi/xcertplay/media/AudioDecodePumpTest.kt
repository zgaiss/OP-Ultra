package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections

class AudioDecodePumpTest {
    @Test fun closedPlaybackCannotStartAgain() {
        val gate = AudioPlaybackGate()
        gate.close {}
        assertFalse(gate.ifOpen { error("Playback must not restart after shutdown") })
    }

    @Test fun shutdownWaitsForInFlightPlayThenPausesLast() {
        val gate = AudioPlaybackGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val play = Thread {
            gate.ifOpen {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                events += "play"
            }
        }
        val close = Thread {
            closeAttempted.countDown()
            gate.close { events += "pause" }
        }
        play.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        close.start()
        assertTrue(closeAttempted.await(2, TimeUnit.SECONDS))
        release.countDown()
        play.join(2_000)
        close.join(2_000)
        assertFalse(play.isAlive)
        assertFalse(close.isAlive)
        assertEquals(listOf("play", "pause"), events)
        assertFalse(gate.ifOpen { events += "late play" })
    }

    @Test fun repeatedCloseDoesNotPauseOrReleaseTwice() {
        val gate = AudioPlaybackGate()
        var closes = 0
        repeat(3) { gate.close { closes++ } }
        assertEquals(1, closes)
    }

    @Test fun drainsNativeOutputBeforeRequestingAnotherInputSlot() {
        // Native decoder has no free inputs until an already-decoded output is released.
        var outputReady = true
        var accepted = false
        var retries = 0
        assertTrue(AudioDecodePump.submit(
            isRunning = { true },
            drain = { outputReady = false },
            tryInput = { (!outputReady).also { accepted = it } },
            onBackpressure = { retries++; error("Draining should free the input") },
        ))
        assertTrue(accepted)
        assertEquals(0, retries)
    }

    @Test fun repeatedBackpressureRetainsInputAndDoesNotAdvancePacketOrder() {
        val decoded = mutableListOf<Int>()
        var attempts = 0
        var drains = 0
        var retries = 0
        for (packet in listOf(11, 12, 13)) {
            assertTrue(AudioDecodePump.submit(
                isRunning = { true }, drain = { drains++ },
                tryInput = {
                    attempts++
                    if (attempts % 4 == 0) { decoded += packet; true } else false
                }, onBackpressure = { retries++ },
            ))
        }
        assertEquals(listOf(11, 12, 13), decoded)
        assertEquals(9, retries)
        assertEquals(15, drains)
    }

    @Test fun stoppingDuringBackpressureDoesNotSubmitMoreInput() {
        var running = true
        var attempts = 0
        assertFalse(AudioDecodePump.submit(
            isRunning = { running }, drain = {},
            tryInput = { attempts++; false }, onBackpressure = { running = false },
        ))
        assertEquals(1, attempts)
    }

    @Test fun stoppingDuringDrainDoesNotFeedReleasedDecoder() {
        var running = true
        assertFalse(AudioDecodePump.submit(
            isRunning = { running }, drain = { running = false },
            tryInput = { error("Stopped decoder must not be used") }, onBackpressure = {},
        ))
    }

    @Test fun immediatelyAvailableInputDoesNotWaitForMorePackets() {
        val steps = mutableListOf<String>()
        assertTrue(AudioDecodePump.submit(
            isRunning = { true }, drain = { steps += "drain" },
            tryInput = { steps += "input"; true }, onBackpressure = { error("No retry needed") },
        ))
        assertEquals(listOf("drain", "input", "drain"), steps)
    }

    @Test fun codecFailureIsReportedRatherThanPretendingPacketWasAccepted() {
        try {
            AudioDecodePump.submit({ true }, {}, { throw IllegalStateException("native failure") }, {})
            fail("Expected native failure")
        } catch (error: IllegalStateException) { assertEquals("native failure", error.message) }
    }

    @Test fun shortAndZeroWritesPreserveEveryPcmByteExactlyOnce() {
        val source = (0 until 24).map(Int::toByte).toByteArray()
        val output = mutableListOf<Byte>()
        val counts = ArrayDeque(listOf(4, 0, 2, 0, 8, 10))
        var reported = 0
        var zeroCount = 0
        val written = AudioPcmWriter.write(source.size, { true }, { offset, remaining ->
            val count = counts.removeFirst()
            assertTrue(count <= remaining)
            output.addAll(source.copyOfRange(offset, offset + count).toList())
            count
        }, { reported += it }, { zeroCount++ })
        assertArrayEquals(source, output.toByteArray())
        assertEquals(source.size, written)
        assertEquals(source.size, reported)
        assertEquals(2, zeroCount)
    }

    @Test fun stoppingDuringZeroWriteExitsWithoutSpinningOrWritingStaleAudio() {
        var running = true
        var attempts = 0
        assertEquals(0, AudioPcmWriter.write(64, { running }, { _, _ -> attempts++; 0 }, {}, { running = false }))
        assertEquals(1, attempts)
    }

    @Test fun emptyPcmDoesNotWriteOrWait() {
        assertEquals(0, AudioPcmWriter.write(0, { true }, { _, _ -> error("No data") }, {}, { error("No wait") }))
    }

    @Test fun nativeWriteErrorIsNotSilentlyTreatedAsSuccessfulPlayback() {
        try {
            AudioPcmWriter.write(8, { true }, { _, _ -> -6 }, {}, {})
            fail("Expected AudioTrack failure")
        } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("-6")) }
    }
}
