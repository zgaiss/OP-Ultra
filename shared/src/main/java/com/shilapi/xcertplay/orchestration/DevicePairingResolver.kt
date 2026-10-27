package com.shilapi.xcertplay.orchestration

/** Select and invalidate only the attached phone's record. No implicit global reset. */
internal class DevicePairingResolver<R>(
    private val load: (String) -> R?,
    private val save: (String, R) -> Unit,
    private val clear: (String) -> Unit,
    private val identityOf: (R) -> String,
    private val invalid: (Throwable) -> Boolean,
) {
    fun <C> connect(deviceKey: String, pair: () -> R, open: (R) -> C): C {
        val previous = load(deviceKey)
        if (previous != null) {
            require(identityOf(previous) == deviceKey) { "Cached trust belongs to another device" }
            try { return open(previous) }
            catch (error: Exception) {
                if (!invalid(error)) throw error
                clear(deviceKey)
            }
        }
        val fresh = pair()
        require(identityOf(fresh) == deviceKey) { "New trust belongs to another device" }
        save(deviceKey, fresh)
        // A StartService/transport error after successful Pair must not erase valid trust.
        return open(fresh)
    }

    /** Read-restricted/locked phones may still accept a saved StartSession. Never sends Pair. */
    fun <C> reconnectKnown(records: List<R>, open: (R) -> C, cancelled: () -> Boolean): C? {
        for (record in records.take(8)) {
            if (cancelled()) return null
            try { return open(record) }
            catch (error: Exception) { if (!invalid(error)) throw error }
        }
        return null
    }
}
