package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PacketCipherReuseTest {
    @Test fun reusableReceiveCipherAuthenticatesRangesAndRecoversAfterBadTag() {
        val key = ByteArray(32) { it.toByte() }
        val decryptor = AirPlayCrypto.PacketDecryptor(key)
        repeat(50) { number ->
            val nonce = AirPlayCrypto.nonce64(number.toLong())
            val aad = byteArrayOf(number.toByte(), 5)
            val plain = ByteArray(number * 13) { (it * 3).toByte() }
            val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
            val wrapped = ByteArray(7) + sealed + ByteArray(3)
            val target = ByteArray(plain.size + 12) { 0x55 }
            assertEquals(plain.size, decryptor.openInto(wrapped, 7, sealed.size, nonce, aad, target, 12))
            assertArrayEquals(plain, target.copyOfRange(12, target.size))
            assertTrue(target.take(12).all { it == 0x55.toByte() })
            val broken = sealed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            try { decryptor.open(broken, 0, broken.size, nonce, aad); fail("Bad tag accepted") }
            catch (_: org.bouncycastle.crypto.InvalidCipherTextException) { }
        }
    }

    @Test fun controlEncodingIsByteIdenticalAndDecodesFragmentation() {
        val key = ByteArray(32) { (it + 3).toByte() }
        val sender = ControlCipher(key, key)
        val receiver = ControlCipher(key, key)
        var counter = 0L
        for (size in listOf(0, 1, 31, 16384, 16385, 40000, 128)) {
            val plain = ByteArray(size) { (it * 7).toByte() }
            val expected = ByteArrayOutputStream()
            var offset = 0
            do {
                val chunk = plain.copyOfRange(offset, minOf(offset + 0x4000, plain.size))
                val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
                expected.write(header)
                expected.write(AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter++), chunk, header))
                offset += 0x4000
            } while (offset < plain.size)
            val wire = sender.encrypt(plain)
            assertArrayEquals(expected.toByteArray(), wire)
            var rest = ByteArray(0)
            val decoded = ByteArrayOutputStream()
            for (chunk in wire.asList().chunked(113)) {
                val result = receiver.decrypt(rest + chunk.toByteArray())
                decoded.write(result.data)
                rest = result.rest
            }
            assertEquals(0, rest.size)
            assertArrayEquals(plain, decoded.toByteArray())
        }
    }
}
