package takagi.ru.monica.security

import android.content.Context
import android.content.SharedPreferences
import android.util.JsonReader
import java.io.File
import java.io.StringReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Reuses only a caller-accepted startup result, never a timed-out worker's result. */
internal object PreparedSecureStore {
    private const val KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    private const val VALUES = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    @Volatile private var accepted: Entry? = null
    private val mirrorCommits = ConcurrentHashMap<String, MirrorCommit>()
    private val pendingMirrors = ConcurrentHashMap.newKeySet<String>()

    internal fun markMirrorPending(directory: String) { pendingMirrors.add(directory) }
    internal fun clearMirrorPending(directory: String) { pendingMirrors.remove(directory) }
    internal fun isMirrorPending(directory: String): Boolean = directory in pendingMirrors

    internal class MirrorCommit internal constructor(
        val directory: String,
        val oldGeneration: String?,
        val oldRevision: String?,
        val generation: String,
        val revision: String,
    )

    /** Only the final canonical/mirror IO window; KDF work never receives this allowance. */
    internal fun beginMirrorCommit(
        directory: String,
        oldGeneration: String?,
        oldRevision: String?,
        generation: String,
        revision: String,
    ): MirrorCommit = MirrorCommit(directory, oldGeneration, oldRevision, generation, revision).also {
        check(mirrorCommits.putIfAbsent(directory, it) == null) { "Concurrent local mirror commit" }
    }

    internal fun finishMirrorCommit(commit: MirrorCommit) {
        mirrorCommits.remove(commit.directory, commit)
    }

    private class Entry(val directory: String, val opened: SecurePreferencesStore.Opened)

    fun cached(context: Context): SecurePreferencesStore.Opened? {
        val entry = accepted ?: return null
        if (entry.directory != context.applicationInfo.dataDir) return null
        val prefs = entry.opened.preferences as? CheckedPreferences ?: return null
        return if (runCatching { prefs.checkActive(); prefs.revisionCurrent() }.getOrDefault(false)) entry.opened else {
            if (accepted === entry) accepted = null
            null
        }
    }

    fun open(context: Context): SecurePreferencesStore.Opened {
        cached(context)?.let { return it }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "SECURE_STORAGE_NOT_READY")
        }
        return fresh(context)
    }

    fun fresh(context: Context): SecurePreferencesStore.Opened {
        val directory = context.applicationInfo.dataDir
        LocalVaultRecovery(context).refreshPreferenceCache()
        val before = selector(directory)
        val opened = SecurePreferencesStore.open(context, SecurePreferencesStore.MONICA)
        check(before == selector(directory)) { "Secure storage changed while opening" }
        val name = LocalVaultRecovery(context).activeStore()?.first ?: SecurePreferencesStore.MONICA
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        return opened.copy(preferences = CheckedPreferences(opened.preferences, directory, before, raw))
    }

    fun accept(context: Context, opened: SecurePreferencesStore.Opened) {
        (opened.preferences as CheckedPreferences).checkActive()
        accepted = Entry(context.applicationInfo.dataDir, opened)
    }

    fun invalidate() { accepted = null }

    fun unwrap(preferences: SharedPreferences): SharedPreferences {
        val checked = preferences as? CheckedPreferences ?: return preferences
        checked.checkActive()
        return checked.delegate
    }

    // Do not acquire LocalVaultRecovery's monitor here: a slow staged recovery can hold it.
    // Read the small atomic selector itself so another live process cannot keep the old store.
    private fun selector(directory: String): String? {
        val base = File(directory, "files/local-vault-recovery/active-store.json")
        val backup = File(base.path + ".bak")
        val file = if (backup.exists()) backup else base
        if (!file.exists()) return null
        require(file.length() in 1..4096) { "Invalid storage selector" }
        return file.inputStream().use { input ->
            val bytes = ByteArray(4097)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                count += n
            }
            require(count in 1..4096)
            String(bytes, 0, count, Charsets.UTF_8)
        }
    }

    private data class RecoveryHeader(val generation: String?, val revision: String?, val revoked: String? = null)

    /** Inspect only the publication header. Never wait for the recovery writer or read its
     * encrypted snapshot on a foreground access check. Writers keep this header first. */
    private fun recoveryHeader(directory: String): RecoveryHeader? {
        val base = File(directory, "files/local-vault-recovery/recovery.json")
        val backup = File(base.path + ".bak")
        val file = if (backup.exists()) backup else base
        if (!file.exists()) return null
        val text = file.inputStream().use { input ->
            val bytes = ByteArray(4096)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                count += n
            }
            require(count > 0) { "Empty recovery header" }
            String(bytes, 0, count, Charsets.UTF_8)
        }
        return JsonReader(StringReader(text)).use { reader ->
            var version: Int? = null
            var rounds: Int? = null
            var kdf: String? = null
            var generation: String? = null
            var revision: String? = null
            var reason: String? = null
            var snapshot = false
            val names = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(names.add(name)) { "Duplicate recovery header field" }
                if (name == "snapshot") { snapshot = true; break }
                when (name) {
                    "version" -> version = reader.nextInt()
                    "rounds" -> rounds = reader.nextInt()
                    "kdf" -> kdf = reader.nextString()
                    "generation" -> generation = reader.nextString()
                    "revision" -> revision = reader.nextString()
                    "reason" -> reason = reader.nextString()
                    "salt", "credentials", "key" -> reader.nextString()
                    else -> throw IllegalArgumentException("Unknown recovery header field")
                }
            }
            if (version == 0) {
                require(!snapshot && reason in setOf("explicit_reset", "credential_change_requires_enrollment"))
                reader.endObject()
                RecoveryHeader(null, null, reason)
            } else {
                require(snapshot && version in setOf(1, 2)) { "Unsupported recovery header" }
                require(names.containsAll(setOf("salt", "credentials", "key", "generation")))
                UUID.fromString(requireNotNull(generation))
                if (version == 1) require(rounds == 600_000 && kdf == null)
                else {
                    require(PasswordKdf.fromMetadata(requireNotNull(kdf)).encoded != null)
                    require(revision != null)
                }
                revision?.let(UUID::fromString)
                RecoveryHeader(generation, revision)
            }
        }
    }

    private class CheckedPreferences(
        val delegate: SharedPreferences,
        private val directory: String,
        private val selectorAtOpen: String?,
        private val raw: SharedPreferences,
    ) : SharedPreferences by delegate {
        private val keys = raw.getString(KEYSET, null)
        private val values = raw.getString(VALUES, null)

        fun revisionCurrent(): Boolean {
            val header = recoveryHeader(directory) ?: return delegate.getString(LocalVaultRecovery.REVISION, null) == null
            val revision = delegate.getString(LocalVaultRecovery.REVISION, null)
            val inFlight = mirrorCommits[directory]
            return header.revoked != null || header.revision == revision ||
                (inFlight != null && inFlight.oldRevision == revision && header.revision == inFlight.revision &&
                    delegate.getString(LocalVaultRecovery.GENERATION, null) == inFlight.oldGeneration)
        }

        fun checkActive() {
            fun current(): Boolean = runCatching {
                if (directory in pendingMirrors) return@runCatching false
                val beforeRead = mirrorCommits[directory]
                val generation = delegate.getString(LocalVaultRecovery.GENERATION, null)
                val revision = delegate.getString(LocalVaultRecovery.REVISION, null)
                val inFlight = (beforeRead ?: mirrorCommits[directory])?.takeIf {
                    it.oldGeneration == generation && it.oldRevision == revision
                }
                val header = runCatching { recoveryHeader(directory) }
                val credentialsCurrent = header.fold(onSuccess = { value ->
                    when {
                        value == null -> generation == null && revision == null
                        value.revoked == "explicit_reset" -> !delegate.contains("master_password_hash")
                        value.revoked == "credential_change_requires_enrollment" ->
                            !delegate.contains(PasswordKdf.METADATA_KEY)
                        // A settings revision is a write-conflict boundary, not a fresh
                        // authentication requirement. Credential changes always change generation.
                        else -> value.generation == generation ||
                            (inFlight != null && value.generation == inFlight.generation && value.revision == inFlight.revision)
                    }
                }, onFailure = {
                    // Older AtomicFile implementations may expose a partially written primary
                    // during initial publication. Only this process's exact IO window qualifies.
                    inFlight != null
                })
                selectorAtOpen == selector(directory) && keys == raw.getString(KEYSET, null) &&
                    values == raw.getString(VALUES, null) && credentialsCurrent
            }.getOrDefault(false)
            // A commit may finish between the separate preference/header reads. One fresh
            // bounded observation avoids treating that completed local publication as a lock.
            if (!current() && !current()) {
                invalidate()
                SecurityManager.clearRuntimeUnlockCache()
                SessionManager.markLocked()
                SecondarySessionManager.markLocked()
                throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "SECURE_STORAGE_CHANGED")
            }
        }
        private inline fun <T> checked(action: () -> T): T {
            checkActive()
            return action().also { checkActive() }
        }
        override fun getAll(): MutableMap<String, *> = checked { delegate.all }
        override fun getString(key: String?, defValue: String?) = checked { delegate.getString(key, defValue) }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = checked { delegate.getStringSet(key, defValues) }
        override fun getBoolean(key: String?, defValue: Boolean) = checked { delegate.getBoolean(key, defValue) }
        override fun getInt(key: String?, defValue: Int) = checked { delegate.getInt(key, defValue) }
        override fun getLong(key: String?, defValue: Long) = checked { delegate.getLong(key, defValue) }
        override fun getFloat(key: String?, defValue: Float) = checked { delegate.getFloat(key, defValue) }
        override fun contains(key: String?) = checked { delegate.contains(key) }
        override fun edit(): SharedPreferences.Editor = checked {
            val editor = delegate.edit()
            object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?) = apply { checkActive(); editor.putString(key, value) }
                override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { checkActive(); editor.putStringSet(key, values) }
                override fun putBoolean(key: String?, value: Boolean) = apply { checkActive(); editor.putBoolean(key, value) }
                override fun putInt(key: String?, value: Int) = apply { checkActive(); editor.putInt(key, value) }
                override fun putLong(key: String?, value: Long) = apply { checkActive(); editor.putLong(key, value) }
                override fun putFloat(key: String?, value: Float) = apply { checkActive(); editor.putFloat(key, value) }
                override fun remove(key: String?) = apply { checkActive(); editor.remove(key) }
                override fun clear() = apply { checkActive(); editor.clear() }
                override fun commit(): Boolean = checked { editor.commit() }
                override fun apply() { checked { editor.apply() } }
            }
        }
    }
}
