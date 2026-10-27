package com.shilapi.xcertplay.transport

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** Stable index for one phone. Neither a USB bus address nor its user-visible name is stable. */
class LockdownDeviceIdentity private constructor(val key: String, publicKeyPem: ByteArray) {
    private val storedPublicKeyPem = publicKeyPem.copyOf()
    val publicKeyPem: ByteArray get() = storedPublicKeyPem.copyOf()
    override fun toString(): String = "LockdownDeviceIdentity(redacted)"

    companion object {
        /** Normalizes PEM whitespace; strictly validates the existing PKCS#1 RSA key format. */
        fun fromPublicKey(pem: ByteArray): LockdownDeviceIdentity {
            require(pem.size in 1..16_384) { "Invalid device public key size" }
            require(pem.all { (it.toInt() and 255) < 128 }) { "Invalid device public key encoding" }
            val text = pem.toString(StandardCharsets.US_ASCII).trim()
            val begin = "-----BEGIN RSA PUBLIC KEY-----"
            val end = "-----END RSA PUBLIC KEY-----"
            require(text.startsWith(begin) && text.endsWith(end)) { "Expected a PKCS#1 RSA public key" }
            val body = text.substring(begin.length, text.length - end.length).filterNot(Char::isWhitespace)
            val der = try { Base64.getDecoder().decode(body) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid device public key encoding") }
            val outer = Reader(der)
            val values = Reader(outer.value(0x30))
            val modulus = values.positiveInteger()
            val exponent = values.positiveInteger()
            require(outer.finished() && values.finished()) { "Trailing device public key data" }
            require(modulus.bitLength() in 512..16_384 && modulus.testBit(0)) { "Invalid RSA modulus" }
            require(exponent > BigInteger.ONE && exponent.bitLength() <= 64 && exponent.testBit(0)) { "Invalid RSA exponent" }
            KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            // Reader rejects non-minimal INTEGERs and lengths, so this is canonical PKCS#1 DER.
            val digest = MessageDigest.getInstance("SHA-256").digest(der)
            val alphabet = "0123456789abcdef"
            val key = CharArray(digest.size * 2)
            digest.forEachIndexed { index, byte ->
                val value = byte.toInt() and 255
                key[index * 2] = alphabet[value ushr 4]
                key[index * 2 + 1] = alphabet[value and 15]
            }
            val encoded = Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(der)
            val canonicalPem = "$begin\n$encoded\n$end\n".toByteArray(StandardCharsets.US_ASCII)
            return LockdownDeviceIdentity(String(key), canonicalPem)
        }
    }

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0
        fun finished() = offset == bytes.size
        fun positiveInteger(): BigInteger {
            val number = value(0x02)
            require(number.isNotEmpty() && (number[0].toInt() and 128) == 0) { "Invalid RSA integer" }
            require(number.size == 1 || number[0].toInt() != 0 || (number[1].toInt() and 128) != 0) { "Non-minimal RSA integer" }
            return BigInteger(number)
        }
        fun value(tag: Int): ByteArray {
            require(offset < bytes.size && (bytes[offset++].toInt() and 255) == tag) { "Invalid RSA DER tag" }
            require(offset < bytes.size) { "Truncated RSA DER length" }
            val first = bytes[offset++].toInt() and 255
            val length = if (first < 128) first else {
                val count = first and 127
                require(count in 1..3 && count <= bytes.size - offset && bytes[offset].toInt() != 0) { "Invalid RSA DER length" }
                var result = 0
                repeat(count) { result = (result shl 8) or (bytes[offset++].toInt() and 255) }
                val minimum = (32 - Integer.numberOfLeadingZeros(result) + 7) / 8
                require(result >= 128 && count == minimum) { "Non-minimal RSA DER length" }
                result
            }
            require(length <= bytes.size - offset) { "Truncated RSA DER value" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
    }
}

/** The accessory's identity is persisted once; device-specific certificates remain separate. */
data class LockdownHostIdentity(val hostId: String, val systemBuid: String) {
    init { require(hostId.isNotBlank() && systemBuid.isNotBlank()) { "Invalid Lockdown host identity" } }
    companion object {
        fun generate() = LockdownHostIdentity(
            UUID.randomUUID().toString().uppercase(Locale.US),
            UUID.randomUUID().toString().uppercase(Locale.US),
        )
    }
}
