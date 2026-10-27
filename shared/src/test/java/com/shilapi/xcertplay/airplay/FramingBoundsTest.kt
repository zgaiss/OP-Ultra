package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FramingBoundsTest {
    @Test fun rtspRejectsInvalidAndOverflowingLengthsBeforeAllocation() {
        for (length in listOf("2147483647", "-1", "nan", "9223372036854775808")) {
            rejects { RtspMessage.parseMessages("POST / RTSP/1.0\r\nContent-Length: $length\r\n\r\n".toByteArray()) }
        }
    }

    @Test fun rtspHeaderIsBoundedBeforeTerminator() {
        rejects { RtspMessage.parseMessages(ByteArray(RtspMessage.MAX_HEADER_BYTES + 1) { 65 }) }
    }

    @Test fun incrementalRtspParsesFragmentedAndBatchedBodies() {
        val one = "POST /a RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 3\r\n\r\nabc".toByteArray()
        val two = "POST /b RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 2\r\n\r\nde".toByteArray()
        for (split in 0..one.size) {
            val queue = BoundedByteQueue(RtspMessage.MAX_PENDING_BYTES, 8)
            queue.append(one, 0, split)
            val first = RtspMessage.drainMessages(queue)
            queue.append(one, split, one.size - split)
            queue.append(two)
            val messages = first + RtspMessage.drainMessages(queue)
            assertEquals(listOf("/a", "/b"), messages.map { it.path })
            assertArrayEquals("abc".toByteArray(), messages[0].body)
            assertArrayEquals("de".toByteArray(), messages[1].body)
            assertEquals(0, queue.size)
        }
    }

    @Test fun boundedQueueCompactsAndRejectsBeforeGrowth() {
        val queue = BoundedByteQueue(16, 8)
        queue.append(byteArrayOf(1, 2, 3, 4, 5, 6))
        val storage = queue.bytes
        queue.discard(4)
        queue.append(byteArrayOf(7, 8, 9, 10))
        assertSame(storage, queue.bytes)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9, 10), queue.copy(0, queue.size))
        queue.append(ByteArray(10))
        assertEquals(16, queue.size)
        rejects { queue.append(byteArrayOf(1)) }
        assertEquals(16, queue.size)
    }

    @Test fun plistRoundTripKeepsActualControlTypes() {
        val body = ByteArray(65_536) { it.toByte() }
        val value = linkedMapOf("ok" to true, "rate" to 48000, "name" to "小鹏", "raw" to body,
            "streams" to listOf(mapOf("type" to 110), mapOf("type" to 111)))
        val decoded = BplistCodec.decode(BplistCodec.encode(value)) as Map<*, *>
        assertEquals(true, decoded["ok"])
        assertEquals(48000L, decoded["rate"])
        assertEquals("小鹏", decoded["name"])
        assertArrayEquals(body, decoded["raw"] as ByteArray)
        assertEquals(2, (decoded["streams"] as List<*>).size)
    }

    @Test fun plistRejectsSelfReferenceAndInvalidTable() {
        val cycle = ByteArray(43)
        "bplist00".toByteArray().copyInto(cycle)
        cycle[8] = 0xa1.toByte(); cycle[9] = 0; cycle[10] = 8
        val trailer = cycle.size - 32
        cycle[trailer + 6] = 1; cycle[trailer + 7] = 1
        cycle[trailer + 15] = 1; cycle[trailer + 31] = 10
        rejects { BplistCodec.decode(cycle) }
        val invalid = BplistCodec.encode(mapOf("a" to true))
        invalid[invalid.size - 17] = 0x7f
        rejects { BplistCodec.decode(invalid) }
    }

    @Test fun plistDepthHasAnExplicitLimit() {
        var value: Any = 1
        repeat(70) { value = listOf(value) }
        rejects { BplistCodec.decode(BplistCodec.encode(value)) }
    }

    @Test fun naturalScreenEofReleasesListenerBeforeNotification() {
        val ended = CountDownLatch(1)
        val screen = ScreenStream(ByteArray(32))
        val port = screen.listen(object : ScreenStream.Listener {
            override fun onClosed(cause: Throwable?) { ended.countDown() }
        })
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use {}
            assertTrue(ended.await(2, TimeUnit.SECONDS))
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("::"), port))
            }
        } finally { screen.close() }
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Malformed input accepted") } catch (_: IllegalArgumentException) { }
    }
}
