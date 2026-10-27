package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.LockdownDeviceIdentity
import com.shilapi.xcertplay.transport.LockdownHostIdentity
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64

/** Per-phone USB trust material. This store must never expose record contents in diagnostics. */
internal class LockdownRecordStore(
    private val backend: Backend,
    private val lock: Any = backend,
    private val createHostIdentity: () -> LockdownHostIdentity = LockdownHostIdentity::generate,
) {
    interface Backend {
        fun snapshot(): Map<String, String>
        /** One durable transaction; null values remove only the named entries. */
        fun commit(values: Map<String, String?>): Boolean
    }

    fun load(deviceKey: String): LockdownPairRecord? = synchronized(lock) {
        requireKey(deviceKey)
        val values = migratedSnapshot()
        decodeChecked(deviceKey, values[recordKey(deviceKey)])
    }

    fun list(): List<LockdownPairRecord> = synchronized(lock) {
        val values = migratedSnapshot()
        values.keys.asSequence().filter { it.startsWith(RECORD_PREFIX) }.sorted().mapNotNull { name ->
            val key = name.removePrefix(RECORD_PREFIX)
            if (validKey(key)) decodeChecked(key, values[name]) else null
        }.toList()
    }

    fun save(deviceKey: String, record: LockdownPairRecord) = synchronized(lock) {
        requireKey(deviceKey)
        require(identityKey(record) == deviceKey) { "Pairing record belongs to a different device" }
        val values = migratedSnapshot()
        val updates = linkedMapOf<String, String?>(recordKey(deviceKey) to encode(record))
        // Retain a legacy mirror for lossless return to the previously accepted APK.
        // The migration marker prevents this mirror from reviving a cleared v2 entry.
        updates.putAll(legacyValues(record))
        if (readHostIdentity(values) == null) updates.putAll(hostValues(record.hostId, record.systemBuid))
        durable(updates)
    }

    fun clear(deviceKey: String) = synchronized(lock) {
        requireKey(deviceKey)
        val values = migratedSnapshot()
        val updates = linkedMapOf<String, String?>(recordKey(deviceKey) to null)
        val legacy = decodeLegacy(values)
        if (legacy != null && runCatching { identityKey(legacy) }.getOrNull() == deviceKey) {
            LEGACY_KEYS.forEach { updates[it] = null }
        }
        durable(updates)
    }

    fun hostIdentity(): LockdownHostIdentity = synchronized(lock) {
        val values = migratedSnapshot()
        readHostIdentity(values) ?: run {
            val previous = decodeLegacy(values)?.takeIf { runCatching { identityKey(it) }.isSuccess } ?: values.keys.asSequence()
                .filter { it.startsWith(RECORD_PREFIX) }.sorted()
                .mapNotNull { decodeChecked(it.removePrefix(RECORD_PREFIX), values[it]) }.firstOrNull()
            val identity = previous?.let { LockdownHostIdentity(it.hostId, it.systemBuid) } ?: createHostIdentity()
            durable(hostValues(identity.hostId, identity.systemBuid))
            identity
        }
    }

    /** Import the old phone under its own identity, even if another phone is currently attached. */
    private fun migratedSnapshot(): Map<String, String> {
        val values = backend.snapshot()
        if (values[MIGRATED] == "1") return values
        val updates = linkedMapOf<String, String?>(MIGRATED to "1")
        val legacy = decodeLegacy(values)
        if (legacy != null) {
            val key = runCatching { identityKey(legacy) }.getOrNull()
            if (key != null && decodeChecked(key, values[recordKey(key)]) == null) {
                updates[recordKey(key)] = encode(legacy)
            }
            if (key != null && readHostIdentity(values) == null) updates.putAll(hostValues(legacy.hostId, legacy.systemBuid))
        }
        durable(updates)
        return values.toMutableMap().apply { updates.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) } }
    }

    private fun readHostIdentity(values: Map<String, String>): LockdownHostIdentity? {
        val hostId = values[HOST_ID] ?: return null
        val systemBuid = values[SYSTEM_BUID] ?: return null
        if (hostId.length > 256 || systemBuid.length > 256) return null
        return runCatching { LockdownHostIdentity(hostId, systemBuid) }.getOrNull()
    }

    private fun durable(values: Map<String, String?>) {
        if (!backend.commit(values)) throw IOException("Could not persist USB trust records")
    }

    private fun decodeChecked(key: String, encoded: String?): LockdownPairRecord? {
        if (!validKey(key) || encoded == null || encoded.length > MAX_RECORD_BYTES * 2) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size <= MAX_RECORD_BYTES)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 1)
                fun text(): String = input.readUTF().also { require(it.isNotBlank() && it.length <= 256) }
                fun data(): ByteArray {
                    val size = input.readInt()
                    require(size in 1..MAX_RECORD_BYTES && size <= input.available())
                    return ByteArray(size).also(input::readFully)
                }
                val record = LockdownPairRecord.restore(text(), text(), text(), data(), data(), data(), data(), data(), data())
                require(input.available() == 0 && identityKey(record) == key)
                record
            }
        }.getOrNull()
    }

    private fun encode(record: LockdownPairRecord): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(1)
            for (value in listOf(record.hostId, record.systemBuid, record.wifiMacAddress)) {
                require(value.isNotBlank() && value.length <= 256)
                output.writeUTF(value)
            }
            for (value in listOf(record.devicePublicKeyPem, record.deviceCertificatePem, record.hostPrivateKeyPem,
                record.hostCertificatePem, record.rootPrivateKeyPem, record.rootCertificatePem)) {
                require(value.isNotEmpty() && value.size <= MAX_RECORD_BYTES)
                output.writeInt(value.size); output.write(value)
            }
        }
        require(bytes.size() <= MAX_RECORD_BYTES) { "USB trust record is too large" }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun decodeLegacy(values: Map<String, String>): LockdownPairRecord? = runCatching {
        var remaining = MAX_RECORD_BYTES - 4096 // Reserve framing and modified-UTF text fields.
        fun text(name: String): String = requireNotNull(values[name]).also { require(it.isNotBlank() && it.length <= 256) }
        fun data(name: String): ByteArray {
            val encoded = requireNotNull(values[name])
            require(encoded.isNotEmpty() && encoded.length <= remaining * 2 && encoded.length % 2 == 0)
            remaining -= encoded.length / 2
            return ByteArray(encoded.length / 2) { encoded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        LockdownPairRecord.restore(text("lockdown_host_id"), text("lockdown_system_buid"), text("lockdown_wifi_mac"),
            data("lockdown_device_public"), data("lockdown_device_cert"), data("lockdown_host_private"),
            data("lockdown_host_cert"), data("lockdown_root_private"), data("lockdown_root_cert"))
    }.getOrNull()

    companion object {
        private const val RECORD_PREFIX = "lockdown.v2.record."
        private const val HOST_ID = "lockdown.v2.host_id"
        private const val SYSTEM_BUID = "lockdown.v2.system_buid"
        private const val MIGRATED = "lockdown.v2.legacy_migrated"
        private const val MAX_RECORD_BYTES = 512 * 1024
        private val LEGACY_KEYS = listOf("lockdown_host_id", "lockdown_system_buid", "lockdown_wifi_mac", "lockdown_device_public",
            "lockdown_device_cert", "lockdown_host_private", "lockdown_host_cert", "lockdown_root_private", "lockdown_root_cert")
        private fun validKey(key: String): Boolean = key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }
        private fun requireKey(key: String) { require(validKey(key)) { "Invalid USB device identity" } }
        private fun recordKey(key: String): String = RECORD_PREFIX + key
        private fun identityKey(record: LockdownPairRecord): String = LockdownDeviceIdentity.fromPublicKey(record.devicePublicKeyPem).key
        private fun hostValues(hostId: String, systemBuid: String) = mapOf(HOST_ID to hostId, SYSTEM_BUID to systemBuid)
        private fun ByteArray.hex(): String {
            val alphabet = "0123456789abcdef"
            return CharArray(size * 2).also { chars -> for (i in indices) {
                val n = this[i].toInt() and 255; chars[i * 2] = alphabet[n ushr 4]; chars[i * 2 + 1] = alphabet[n and 15]
            } }.concatToString()
        }
        private fun legacyValues(record: LockdownPairRecord): Map<String, String> = mapOf(
            "lockdown_host_id" to record.hostId, "lockdown_system_buid" to record.systemBuid, "lockdown_wifi_mac" to record.wifiMacAddress,
            "lockdown_device_public" to record.devicePublicKeyPem.hex(), "lockdown_device_cert" to record.deviceCertificatePem.hex(),
            "lockdown_host_private" to record.hostPrivateKeyPem.hex(), "lockdown_host_cert" to record.hostCertificatePem.hex(),
            "lockdown_root_private" to record.rootPrivateKeyPem.hex(), "lockdown_root_cert" to record.rootCertificatePem.hex(),
        )
    }
}
