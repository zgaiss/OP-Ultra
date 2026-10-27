package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.LockdownDeviceIdentity
import com.shilapi.xcertplay.transport.LockdownHostIdentity
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LockdownRecordStoreTest {
    private class MemoryBackend : LockdownRecordStore.Backend {
        val values = mutableMapOf<String, String>()
        var failCommit = false
        override fun snapshot(): Map<String, String> = values.toMap()
        override fun commit(values: Map<String, String?>): Boolean {
            if (failCommit) return false
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
            return true
        }
    }

    private fun store(backend: MemoryBackend) = LockdownRecordStore(backend)
    private fun key(record: LockdownPairRecord) = LockdownDeviceIdentity.fromPublicKey(record.devicePublicKeyPem).key
    private fun assertRecord(expected: LockdownPairRecord, actual: LockdownPairRecord?) {
        assertNotNull(actual)
        assertEquals(expected.hostId, actual!!.hostId)
        assertEquals(expected.systemBuid, actual.systemBuid)
        assertArrayEquals(expected.devicePublicKeyPem, actual.devicePublicKeyPem)
        assertArrayEquals(expected.hostPrivateKeyPem, actual.hostPrivateKeyPem)
    }

    @Test fun switchingAToBToAReusesEachStoredRecord() {
        val backend = MemoryBackend(); val records = store(backend)
        records.save(key(a), a); records.save(key(b), b)
        assertRecord(a, records.load(key(a)))
        assertRecord(b, records.load(key(b)))
        assertRecord(a, records.load(key(a)))
        assertEquals(2, records.list().size)
    }

    @Test fun recordsAndEachPhonesOwnHostIdsSurviveStoreRecreation() {
        val backend = MemoryBackend()
        store(backend).save(key(a), a); store(backend).save(key(b), b)
        assertRecord(a, store(backend).load(key(a)))
        assertRecord(b, store(backend).load(key(b)))
        assertNotEquals(a.hostId, b.hostId)
    }

    @Test fun invalidatingBCannotDeleteOrOverwriteA() {
        val backend = MemoryBackend(); val records = store(backend)
        records.save(key(a), a); records.save(key(b), b); records.clear(key(b))
        assertNull(records.load(key(b)))
        assertNull(store(backend).load(key(b)))
        assertRecord(a, records.load(key(a)))
        assertEquals(listOf(key(a)), records.list().map(::key))
    }

    @Test fun invalidatingTheLegacyMirroredPhoneDoesNotResurrectIt() {
        val backend = MemoryBackend(); backend.values.putAll(legacy(a))
        val records = store(backend)
        assertRecord(a, records.load(key(a)))
        records.clear(key(a))
        repeat(3) { assertNull(store(backend).load(key(a))) }
        assertTrue(records.list().isEmpty())
    }

    @Test fun firstConnectOfBDuringMigrationPreservesExistingA() {
        val backend = MemoryBackend(); backend.values.putAll(legacy(a))
        val records = store(backend)
        assertNull(records.load(key(b)))
        records.save(key(b), b)
        assertRecord(a, store(backend).load(key(a)))
        assertRecord(b, store(backend).load(key(b)))
        assertEquals(a.hostId, records.hostIdentity().hostId)
    }

    @Test fun migrationDoesNotOverwriteANewerIndexedRecord() {
        val backend = MemoryBackend(); val records = store(backend)
        val newer = record(publicA, HOST_C, SYSTEM_C)
        records.save(key(newer), newer)
        backend.values.remove("lockdown.v2.legacy_migrated")
        backend.values.putAll(legacy(a))
        assertRecord(newer, records.load(key(a)))
    }

    @Test fun corruptAIsIsolatedFromBAndCanBeReplacedIndependently() {
        val backend = MemoryBackend(); val records = store(backend)
        records.save(key(a), a); records.save(key(b), b)
        backend.values["lockdown.v2.record.${key(a)}"] = "bad encoding"
        assertNull(records.load(key(a)))
        assertRecord(b, records.load(key(b)))
        assertEquals(listOf(key(b)), records.list().map(::key))
        records.save(key(a), a)
        assertRecord(a, records.load(key(a))); assertRecord(b, records.load(key(b)))
    }

    @Test fun aValidBlobStoredUnderAnotherPhonesKeyIsRejected() {
        val backend = MemoryBackend(); val records = store(backend)
        records.save(key(a), a)
        backend.values["lockdown.v2.record.${key(b)}"] = backend.values.getValue("lockdown.v2.record.${key(a)}")
        assertNull(records.load(key(b)))
        assertRecord(a, records.load(key(a)))
    }

    @Test fun savingAMismatchedIdentityDoesNotMutateAnyRecord() {
        val backend = MemoryBackend(); val records = store(backend)
        records.save(key(a), a); val snapshot = backend.snapshot()
        assertThrows(IllegalArgumentException::class.java) { records.save(key(a), b) }
        assertEquals(snapshot, backend.snapshot())
    }

    @Test fun corruptLegacyRecordDoesNotBlockUnrelatedPhones() {
        val backend = MemoryBackend(); backend.values.putAll(legacy(a)); backend.values["lockdown_host_private"] = "not-hex"
        val records = store(backend)
        assertNull(records.load(key(a)))
        records.save(key(b), b)
        assertRecord(b, records.load(key(b)))
        assertEquals(1, records.list().size)
    }

    @Test fun oversizedLegacyRecordDoesNotBlockAnotherPhone() {
        val backend = MemoryBackend(); backend.values.putAll(legacy(a))
        backend.values["lockdown_host_private"] = "ab".repeat(512 * 1024)
        val records = store(backend)
        assertNull(records.load(key(a)))
        records.save(key(b), b)
        assertRecord(b, records.load(key(b)))
    }

    @Test fun invalidPersistentHostIdsAreRecoveredFromAnExistingValidPhone() {
        val backend = MemoryBackend(); val records = store(backend)
        records.save(key(a), a)
        backend.values["lockdown.v2.host_id"] = "x".repeat(1024)
        backend.values["lockdown.v2.system_buid"] = ""
        assertEquals(LockdownHostIdentity(a.hostId, a.systemBuid), records.hostIdentity())
        assertEquals(a.hostId, backend.values["lockdown.v2.host_id"])
    }

    @Test fun failedDurableCommitIsNotReportedAsSuccessfulSave() {
        val backend = MemoryBackend(); val records = store(backend)
        records.save(key(a), a); val snapshot = backend.snapshot(); backend.failCommit = true
        assertThrows(IOException::class.java) { records.save(key(b), b) }
        assertEquals(snapshot, backend.snapshot())
        backend.failCommit = false
        assertRecord(a, records.load(key(a))); assertNull(records.load(key(b)))
    }

    @Test fun failedMigrationDoesNotPartiallyMarkOrDiscardLegacy() {
        val backend = MemoryBackend(); backend.values.putAll(legacy(a)); val snapshot = backend.snapshot(); backend.failCommit = true
        assertThrows(IOException::class.java) { store(backend).load(key(b)) }
        assertEquals(snapshot, backend.snapshot())
        backend.failCommit = false
        assertRecord(a, store(backend).load(key(a)))
    }

    @Test fun invalidatingAnUnknownPhoneLeavesAllExistingRecords() {
        val backend = MemoryBackend(); val records = store(backend)
        records.save(key(a), a); records.save(key(b), b)
        records.clear("0".repeat(64))
        assertRecord(a, records.load(key(a))); assertRecord(b, records.load(key(b)))
    }

    @Test fun hostIdentitySurvivesRestartAndRecordInvalidation() {
        val backend = MemoryBackend(); val records = store(backend)
        records.save(key(a), a)
        val host = records.hostIdentity(); records.clear(key(a))
        assertEquals(host, store(backend).hostIdentity())
    }

    @Test fun hostIdentityUsesTheExistingTrustBeforeGeneratingAnyNewIdentity() {
        val backend = MemoryBackend(); backend.values.putAll(legacy(a)); val creates = AtomicInteger()
        val records = LockdownRecordStore(backend, backend) { creates.incrementAndGet(); LockdownHostIdentity(HOST_C, SYSTEM_C) }
        assertEquals(LockdownHostIdentity(a.hostId, a.systemBuid), records.hostIdentity())
        assertEquals(0, creates.get())
    }

    @Test fun concurrentColdStartsGenerateExactlyOnePersistentHostIdentity() {
        val backend = MemoryBackend(); val sharedLock = Any(); val creates = AtomicInteger()
        val values = Collections.synchronizedList(mutableListOf<LockdownHostIdentity>())
        val start = CountDownLatch(1); val done = CountDownLatch(16)
        repeat(16) {
            Thread {
                try {
                    start.await()
                    values += LockdownRecordStore(backend, sharedLock) { creates.incrementAndGet(); LockdownHostIdentity(HOST_C, SYSTEM_C) }.hostIdentity()
                } finally { done.countDown() }
            }.start()
        }
        start.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, creates.get()); assertEquals(1, values.toSet().size)
    }

    @Test fun concurrentPhoneSavesAcrossStoreInstancesCannotLoseEitherRecord() {
        val backend = MemoryBackend(); val sharedLock = Any(); val start = CountDownLatch(1); val done = CountDownLatch(20)
        repeat(20) { index ->
            Thread {
                try { start.await(); val value = if (index % 2 == 0) a else b; LockdownRecordStore(backend, sharedLock).save(key(value), value) }
                finally { done.countDown() }
            }.start()
        }
        start.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
        assertRecord(a, store(backend).load(key(a))); assertRecord(b, store(backend).load(key(b)))
    }

    @Test fun malformedDeviceKeysAreRejectedBeforeWriting() {
        val backend = MemoryBackend(); val records = store(backend)
        for (key in listOf("", "../other", "A".repeat(64), "a".repeat(63))) {
            assertThrows(IllegalArgumentException::class.java) { records.load(key) }
            assertThrows(IllegalArgumentException::class.java) { records.clear(key) }
        }
        assertTrue(backend.values.isEmpty())
    }

    @Test fun unrelatedPreferencesAreNotChangedBySaveClearOrMigration() {
        val backend = MemoryBackend(); backend.values["music_buffer"] = "120"; val records = store(backend)
        records.save(key(a), a); records.save(key(b), b); records.clear(key(b))
        assertEquals("120", backend.values["music_buffer"])
    }

    companion object {
        private const val HOST_A = "11111111-1111-4111-8111-111111111111"
        private const val HOST_B = "22222222-2222-4222-8222-222222222222"
        private const val HOST_C = "33333333-3333-4333-8333-333333333333"
        private const val SYSTEM_A = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
        private const val SYSTEM_B = "BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB"
        private const val SYSTEM_C = "CCCCCCCC-CCCC-4CCC-8CCC-CCCCCCCCCCCC"
        private fun der(tag: Int, body: ByteArray): ByteArray {
            val length = if (body.size < 128) byteArrayOf(body.size.toByte())
                else if (body.size < 256) byteArrayOf(0x81.toByte(), body.size.toByte())
                else byteArrayOf(0x82.toByte(), (body.size ushr 8).toByte(), body.size.toByte())
            return byteArrayOf(tag.toByte()) + length + body
        }
        private fun publicKey(): ByteArray {
            val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val key = generator.generateKeyPair().public as RSAPublicKey
            val body = der(0x30, der(2, key.modulus.toByteArray()) + der(2, key.publicExponent.toByteArray()))
            return ("-----BEGIN RSA PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(body) + "\n-----END RSA PUBLIC KEY-----\n").toByteArray()
        }
        private val publicA by lazy(::publicKey)
        private val publicB by lazy(::publicKey)
        private fun record(publicKey: ByteArray, host: String, system: String): LockdownPairRecord = LockdownPairRecord.restore(
            host, system, "02:00:00:00:00:01", publicKey, byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4), byteArrayOf(5),
        )
        private val a by lazy { record(publicA, HOST_A, SYSTEM_A) }
        private val b by lazy { record(publicB, HOST_B, SYSTEM_B) }
        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun legacy(record: LockdownPairRecord): Map<String, String> = mapOf(
            "lockdown_host_id" to record.hostId, "lockdown_system_buid" to record.systemBuid, "lockdown_wifi_mac" to record.wifiMacAddress,
            "lockdown_device_public" to record.devicePublicKeyPem.hex(), "lockdown_device_cert" to record.deviceCertificatePem.hex(),
            "lockdown_host_private" to record.hostPrivateKeyPem.hex(), "lockdown_host_cert" to record.hostCertificatePem.hex(),
            "lockdown_root_private" to record.rootPrivateKeyPem.hex(), "lockdown_root_cert" to record.rootCertificatePem.hex(),
        )
    }
}
