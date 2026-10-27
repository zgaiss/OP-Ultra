package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

internal object SyntheticLockdownKeys {
    fun publicKeyPem(): ByteArray {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(512) }.generateKeyPair().public as RSAPublicKey
        return pem(der(0x30, der(2, key.modulus.toByteArray()) + der(2, key.publicExponent.toByteArray())))
    }
    fun pem(bytes: ByteArray): ByteArray = ("-----BEGIN RSA PUBLIC KEY-----\n" +
        Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes) + "\n-----END RSA PUBLIC KEY-----\n").toByteArray(Charsets.US_ASCII)
    fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); out.write(tag)
        if (content.size < 128) out.write(content.size)
        else { out.write(0x82); out.write(content.size ushr 8); out.write(content.size) }
        out.write(content); return out.toByteArray()
    }
}

class LockdownDeviceIdentityTest {
    @Test fun samePublicKeyWithDifferentPemWhitespaceHasSameIndex() {
        val pem = SyntheticLockdownKeys.publicKeyPem()
        val a = LockdownDeviceIdentity.fromPublicKey(pem)
        val b = LockdownDeviceIdentity.fromPublicKey((" \r\n" + pem.toString(Charsets.US_ASCII).replace("\n", " \t\r\n") + "\t").toByteArray())
        assertEquals(a.key, b.key)
        assertTrue(a.key.matches(Regex("[0-9a-f]{64}")))
        assertArrayEquals(a.publicKeyPem, b.publicKeyPem)
    }
    @Test fun differentPhonesHaveDifferentIndexes() {
        assertNotEquals(LockdownDeviceIdentity.fromPublicKey(SyntheticLockdownKeys.publicKeyPem()).key,
            LockdownDeviceIdentity.fromPublicKey(SyntheticLockdownKeys.publicKeyPem()).key)
    }
    @Test fun publicKeyInputAndAccessorsCannotMutateStoredIdentity() {
        val input = SyntheticLockdownKeys.publicKeyPem()
        val identity = LockdownDeviceIdentity.fromPublicKey(input)
        val saved = identity.publicKeyPem
        input.fill(0); identity.publicKeyPem.fill(0)
        assertArrayEquals(saved, identity.publicKeyPem)
        assertEquals(identity.key, LockdownDeviceIdentity.fromPublicKey(identity.publicKeyPem).key)
        assertEquals("LockdownDeviceIdentity(redacted)", identity.toString())
    }
    @Test fun malformedAlgorithmAndOversizedInputAreRejected() {
        for (bytes in listOf(ByteArray(16_385), "secret-sensitive-fixture".toByteArray(),
            "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----".toByteArray(),
            SyntheticLockdownKeys.pem(byteArrayOf(0x30, 0x81.toByte(), 0)))) {
            try { LockdownDeviceIdentity.fromPublicKey(bytes); fail("Must reject malformed key") }
            catch (expected: IllegalArgumentException) { assertFalse(expected.message.orEmpty().contains("secret-sensitive-fixture")) }
        }
    }
    @Test fun trailingDerAndInvalidIntegersAreRejected() {
        val key = SyntheticLockdownKeys.publicKeyPem().toString(Charsets.US_ASCII)
        val body = key.substringAfter("-----BEGIN RSA PUBLIC KEY-----").substringBefore("-----END RSA PUBLIC KEY-----").filterNot(Char::isWhitespace)
        val der = Base64.getDecoder().decode(body)
        for (bad in listOf(der + 0, byteArrayOf(0x30, 6, 2, 1, 0, 2, 1, 3))) {
            try { LockdownDeviceIdentity.fromPublicKey(SyntheticLockdownKeys.pem(bad)); fail("Must reject invalid DER") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun newHostIdentityContainsTwoValidDistinctUuids() {
        val identity = LockdownHostIdentity.generate()
        assertNotEquals(identity.hostId, identity.systemBuid)
        assertEquals(identity.hostId, java.util.UUID.fromString(identity.hostId).toString().uppercase(java.util.Locale.US))
        assertEquals(identity.systemBuid, java.util.UUID.fromString(identity.systemBuid).toString().uppercase(java.util.Locale.US))
    }
}
