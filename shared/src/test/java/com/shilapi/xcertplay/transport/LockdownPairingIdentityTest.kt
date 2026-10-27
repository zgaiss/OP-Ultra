package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class LockdownPairingIdentityTest {
    private class ScriptedChannel(val answer: (LockdownPlistValue.Dictionary) -> LockdownPlistValue.Dictionary) : LockdownPairingChannel {
        val requests = mutableListOf<LockdownPlistValue.Dictionary>()
        var closed = 0
        override fun request(message: LockdownPlistValue.Dictionary, timeoutMillis: Long): LockdownPlistValue.Dictionary {
            assertTrue(timeoutMillis in 1..5000); requests += message; return answer(message)
        }
        override fun close() { closed++ }
    }
    private fun dictionary(vararg pairs: Pair<String, LockdownPlistValue>) = LockdownPlistValue.Dictionary(linkedMapOf(*pairs))
    private fun text(value: String) = LockdownPlistValue.Text(value)
    private fun LockdownPlistValue.Dictionary.text(key: String) = (entries[key] as? LockdownPlistValue.Text)?.value

    @Test fun identityLookupOnlyReadsPublicKeyWithoutPairOrBuidMutation() {
        val key = SyntheticLockdownKeys.publicKeyPem()
        val channel = ScriptedChannel { request ->
            assertEquals("GetValue", request.text("Request")); assertEquals("DevicePublicKey", request.text("Key"))
            dictionary("Value" to LockdownPlistValue.Data(key))
        }
        val identity = LockdownPairingClient { channel }.readDeviceIdentity("fixture")
        assertEquals(LockdownDeviceIdentity.fromPublicKey(key).key, identity.key)
        assertEquals(1, channel.requests.size); assertEquals(1, channel.closed)
    }
    @Test fun probePreservesLockedPhoneErrorWithoutPairing() {
        val channel = ScriptedChannel { dictionary("Error" to text("GetProhibited")) }
        try { LockdownPairingClient { channel }.readDeviceIdentity("fixture"); fail("Expected typed failure") }
        catch (error: LockdownPairingException.RemoteError) { assertEquals("GetProhibited", error.code) }
        assertEquals(listOf("GetValue"), channel.requests.map { it.text("Request") })
        assertEquals(1, channel.closed)
    }
    @Test fun cancelBeforeOpenDoesNotTouchPhone() {
        var opened = false
        val client = LockdownPairingClient { opened = true; error("must not open") }
        try { client.readDeviceIdentity("fixture", isCancelled = { true }); fail("Expected cancellation") }
        catch (_: LockdownPairingException.Cancelled) {}
        assertFalse(opened)
    }
    @Test fun cancelAfterReadClosesChannelBeforeUsingIdentity() {
        var cancelled = false
        val channel = ScriptedChannel { cancelled = true; dictionary("Value" to LockdownPlistValue.Data(SyntheticLockdownKeys.publicKeyPem())) }
        try { LockdownPairingClient { channel }.readDeviceIdentity("fixture", isCancelled = { cancelled }); fail("Expected cancellation") }
        catch (_: LockdownPairingException.Cancelled) {}
        assertEquals(1, channel.closed)
    }
    @Test fun expiredDeadlineClosesOpenedChannelWithoutSending() {
        val channel = ScriptedChannel { error("must not send") }
        val client = LockdownPairingClient { Thread.sleep(15); channel }
        try { client.readDeviceIdentity("fixture", totalTimeoutMillis = 1); fail("Expected timeout") }
        catch (_: LockdownPairingException.TimedOut) {}
        assertEquals(1, channel.closed); assertTrue(channel.requests.isEmpty())
    }
    @Test fun malformedPublicKeyClosesChannelAndDoesNotPair() {
        val channel = ScriptedChannel { dictionary("Value" to LockdownPlistValue.Data(byteArrayOf(1,2,3))) }
        try { LockdownPairingClient { channel }.readDeviceIdentity("fixture"); fail("Expected invalid key") }
        catch (_: IllegalArgumentException) {}
        assertEquals(1, channel.closed)
        assertEquals(listOf("GetValue"), channel.requests.map { it.text("Request") })
    }
    @Test fun pairingReusesProbedKeyAndOnlyPairingRequestsTrust() {
        val identity = LockdownDeviceIdentity.fromPublicKey(SyntheticLockdownKeys.publicKeyPem())
        val channel = ScriptedChannel { request ->
            when (request.text("Request")) {
                "SetValue" -> { assertEquals("UntrustedHostBUID", request.text("Key")); dictionary() }
                "GetValue" -> { assertEquals("WiFiAddress", request.text("Key")); dictionary("Value" to text("02:00:00:00:00:01")) }
                "Pair" -> dictionary()
                else -> error("Unexpected request")
            }
        }
        val host = LockdownHostIdentity.generate()
        val record = LockdownPairingClient { channel }.pair("fixture",host.hostId,host.systemBuid,10_000,deviceIdentity=identity).pairRecord
        assertEquals(identity.key, LockdownDeviceIdentity.fromPublicKey(record.devicePublicKeyPem).key)
        assertEquals(listOf("SetValue", "GetValue", "Pair"), channel.requests.map { it.text("Request") })
        assertEquals(1, channel.closed)
    }
    @Test fun userDenialIsNotRetriedAndPairChannelIsClosed() {
        val identity = LockdownDeviceIdentity.fromPublicKey(SyntheticLockdownKeys.publicKeyPem())
        val channel = ScriptedChannel { request -> when (request.text("Request")) {
            "SetValue" -> dictionary()
            "GetValue" -> dictionary("Value" to text("02:00:00:00:00:01"))
            else -> dictionary("Error" to text("UserDeniedPairing"))
        } }
        val host = LockdownHostIdentity.generate()
        try { LockdownPairingClient { channel }.pair("fixture",host.hostId,host.systemBuid,10_000,deviceIdentity=identity); fail("Expected denial") }
        catch (_: LockdownPairingException.UserDeniedPairing) {}
        assertEquals(1, channel.requests.count { it.text("Request")=="Pair" }); assertEquals(1, channel.closed)
    }
}
