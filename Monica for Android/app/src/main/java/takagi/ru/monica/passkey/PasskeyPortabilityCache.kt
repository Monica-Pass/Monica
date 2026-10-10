package takagi.ru.monica.passkey

import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import takagi.ru.monica.data.PasskeyEntry

/** Bounded, process-local display cache. Never used to authorize signing or export. */
internal class PasskeyPortabilityCache(
    private val capacity: Int = 512,
    private val ttlMillis: Long = 5 * 60_000L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    data class Key(
        val aliasFingerprint: String,
        val backupEligible: Boolean?,
        val backupState: Boolean?,
        val hasCounterHistory: Boolean,
        val algorithm: Int
    )

    private data class Entry(val status: PasskeyPortability, val checkedAt: Long)
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private val lock = Any()
    private val inspectionMutex = Mutex()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    init {
        require(capacity > 0 && ttlMillis > 0)
    }

    // Keep an expired badge visible while its replacement is inspected off the UI thread.
    fun peek(key: Key): PasskeyPortability? = synchronized(lock) { entries[key]?.status }

    suspend fun getOrLoad(key: Key, load: suspend () -> PasskeyPortability): PasskeyPortability =
        inspectionMutex.withLock {
            val generation = synchronized(lock) {
                entries[key]?.let { entry ->
                    val age = nowMillis() - entry.checkedAt
                    val lifetime = if (entry.status == PasskeyPortability.UNKNOWN) minOf(ttlMillis, 5_000L) else ttlMillis
                    if (age >= 0 && age < lifetime) return@withLock entry.status
                }
                _revision.value
            }
            val status = load()
            synchronized(lock) {
                // A key write, removal or session change during inspection wins over this result.
                if (_revision.value == generation) {
                    entries[key] = Entry(status, nowMillis())
                    while (entries.size > capacity) entries.remove(entries.keys.first())
                }
            }
            status
        }

    fun invalidate(alias: String) {
        val fingerprint = fingerprint(alias)
        synchronized(lock) {
            entries.keys.removeAll { it.aliasFingerprint == fingerprint }
            _revision.value++
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        _revision.value++
    }

    companion object {
        val shared = PasskeyPortabilityCache()

        fun keyFor(entry: PasskeyEntry) = Key(
            fingerprint(entry.privateKeyAlias), entry.backupEligible, entry.backupState,
            entry.signCount > 0, entry.publicKeyAlgorithm
        )

        // Legacy aliases may contain raw private keys; never retain them as cache keys.
        private fun fingerprint(alias: String): String = MessageDigest.getInstance("SHA-256")
            .digest(alias.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
