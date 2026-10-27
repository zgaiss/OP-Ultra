package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class IapTunnelFramingTest {
    @Test fun fragmentedEncryptedFramesDeliverOneCompletePackage() {
        val key = ByteArray(32) { it.toByte() }
        val tunnel = IapTunnel(key)
        val received = AtomicReference<ByteArray>()
        val arrived = CountDownLatch(1)
        val port = tunnel.listen(object : IapTunnel.Listener {
            override fun onIap(bytes: ByteArray) { received.set(bytes); arrived.countDown() }
        })
        try {
            val payload = ByteArray(2048) { (it * 7).toByte() }
            val pkg = ByteArray(32) + payload
            writeU32(pkg, 0, pkg.size); writeU32(pkg, 16, 0x636f6d6d)
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                val wire = frame(key, pkg.copyOfRange(0, 12), 0) + frame(key, pkg.copyOfRange(12, pkg.size), 1)
                for (offset in wire.indices step 17) socket.getOutputStream().write(wire, offset, minOf(17, wire.size - offset))
                socket.getOutputStream().flush()
                assertTrue(arrived.await(3, TimeUnit.SECONDS))
            }
            assertArrayEquals(payload, received.get())
        } finally { tunnel.close() }
    }

    @Test fun invalidPackageLengthTerminatesWithoutAccumulating() {
        val key = ByteArray(32)
        val tunnel = IapTunnel(key)
        val failed = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val port = tunnel.listen(object : IapTunnel.Listener {
            override fun onClosed(cause: Throwable?) { if (cause != null) { error.set(cause); failed.countDown() } }
        })
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().write(frame(key, ByteArray(32), 0))
                socket.getOutputStream().flush()
                assertTrue(failed.await(3, TimeUnit.SECONDS))
                assertTrue(error.get() is IllegalArgumentException)
            }
        } finally { tunnel.close() }
    }

    private fun frame(key: ByteArray, bytes: ByteArray, counter: Long): ByteArray {
        val header = byteArrayOf(bytes.size.toByte(), (bytes.size ushr 8).toByte())
        return header + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), bytes, header)
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { bytes[offset + it] = (value ushr (24 - 8 * it)).toByte() }
    }
}
