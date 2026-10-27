package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioStreamReceiveTest {
    @Test fun normalReceiveAvoidsCaptureAndResetsReusedDatagramLength() {
        val key = ByteArray(32) { (it + 4).toByte() }
        val stream = AudioStream(key, 100, capturePackets = false)
        val packets = Collections.synchronizedList(mutableListOf<ByteArray>())
        val captured = AtomicInteger()
        val arrived = CountDownLatch(2)
        val ports = stream.listen(object : AudioStream.Listener {
            override fun onPacket(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) { captured.incrementAndGet() }
            override fun onRtp(rtp: ByteArray, sample: Int) { packets += rtp; arrived.countDown() }
        })
        try {
            DatagramSocket().use { sender ->
                val peer = InetAddress.getLoopbackAddress()
                fun send(bytes: ByteArray) = sender.send(DatagramPacket(bytes, bytes.size, peer, ports.first))
                send(ByteArray(4))
                send(packet(key, 1, ByteArray(7) { 0x11 }))
                send(packet(key, 2, ByteArray(1800) { 0x22 }))
                assertTrue(arrived.await(3, TimeUnit.SECONDS))
            }
            assertEquals(0, captured.get())
            assertArrayEquals(ByteArray(7) { 0x11 }, packets[0].copyOfRange(12, packets[0].size))
            assertArrayEquals(ByteArray(1800) { 0x22 }, packets[1].copyOfRange(12, packets[1].size))
        } finally { stream.close() }
    }

    private fun packet(key: ByteArray, count: Long, payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte(); header[1] = 100; header[7] = count.toByte()
        val nonce = AirPlayCrypto.nonce64(count)
        return header + AirPlayCrypto.chachaSeal(key, nonce, payload, header.copyOfRange(4, 12)) + nonce.copyOfRange(4, 12)
    }
}
