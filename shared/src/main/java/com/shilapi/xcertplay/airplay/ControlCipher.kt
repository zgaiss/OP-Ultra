package com.shilapi.xcertplay.airplay

/**
 * ChaCha20-Poly1305 framing for the CarPlay control channel.
 *
 * Each frame is [2-byte little-endian ciphertext length][ciphertext][16-byte tag]; the length
 * header is the AEAD associated data and each direction uses an 8-byte little-endian nonce
 * counter plus its own key.
 */
class ControlCipher(private val readKey: ByteArray, private val writeKey: ByteArray) {
    data class Decrypted(val data: ByteArray, val rest: ByteArray)

    private var readCounter = 0L
    private var writeCounter = 0L
    private val decryptor = AirPlayCrypto.PacketDecryptor(readKey)
    private val encryptor = AirPlayCrypto.PacketEncryptor(writeKey)
    private val readAad = ByteArray(HEADER_SIZE)
    private val writeAad = ByteArray(HEADER_SIZE)

    fun decrypt(buffer: ByteArray): Decrypted {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            buffer.copyInto(readAad, 0, offset, offset + HEADER_SIZE)
            output.add(decryptor.open(buffer, offset + HEADER_SIZE, length + TAG_SIZE,
                AirPlayCrypto.nonce64(readCounter), readAad))
            readCounter++
            offset = frameEnd
        }
        val plaintext = if (output.size == 1) output[0] else concatBytes(*output.toTypedArray())
        return Decrypted(plaintext, if (offset == 0) buffer else buffer.copyOfRange(offset, buffer.size))
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val frames = maxOf(1L, (plaintext.size.toLong() + MAX_PAYLOAD - 1) / MAX_PAYLOAD).toInt()
        val outputSize = plaintext.size.toLong() + frames.toLong() * (HEADER_SIZE + TAG_SIZE)
        require(outputSize <= Int.MAX_VALUE) { "Control frame too large" }
        val output = ByteArray(outputSize.toInt())
        var offset = 0
        var outputOffset = 0
        do {
            val length = minOf(MAX_PAYLOAD, plaintext.size - offset)
            writeAad[0] = length.toByte()
            writeAad[1] = (length ushr 8).toByte()
            writeAad.copyInto(output, outputOffset)
            outputOffset += HEADER_SIZE
            outputOffset += encryptor.sealInto(plaintext, offset, length,
                AirPlayCrypto.nonce64(writeCounter), writeAad, output, outputOffset)
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return output
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16
        const val MAX_PAYLOAD = 0x4000
    }
}
