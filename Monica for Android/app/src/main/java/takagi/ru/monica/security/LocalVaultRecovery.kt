package takagi.ru.monica.security

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Independent password recovery. Files here contain authenticated ciphertext, never plaintext keys. */
internal class LocalVaultRecovery(private val context: Context) {
    private val directory = File(context.applicationInfo.dataDir, "files/local-vault-recovery")
    private val envelope = AtomicFile(File(directory, "recovery.json"))
    private val selector = AtomicFile(File(directory, "active-store.json"))
    private val lock = locks.getOrPut(directory.absolutePath) { Any() }

    companion object {
        internal const val KEY = "local_recovery_key_v1"
        internal const val GENERATION = "local_recovery_generation_v1"
        private const val ROUNDS = 600_000
        private const val MAX_BYTES = 64 * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Any>()
        private val credentialKeys = listOf("master_password_hash", "master_password_salt", "mdk_password_blob", "mdk_password_salt")
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
        private fun randomKey() = ByteArray(32).also(SecureRandom()::nextBytes)
        private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, rounds, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        }
        private fun seal(key: ByteArray, plain: ByteArray, aad: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            return encode(cipher.iv + cipher.doFinal(plain))
        }
        private fun unseal(key: ByteArray, value: String, aad: String): ByteArray {
            val bytes = decode(value)
            require(bytes.size in 28..MAX_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(bytes, 12, bytes.size - 12)
        }
        private fun fingerprint(values: Map<String, *>): String = encode(MessageDigest.getInstance("SHA-256")
            .digest(JSONArray(credentialKeys.map { values[it] }).toString().toByteArray(Charsets.UTF_8)))

        private fun serialize(values: Map<String, *>): ByteArray {
            val json = JSONObject()
            values.filterKeys { it != KEY && it != GENERATION }.forEach { (name, value) ->
                val type = when (value) {
                    is String -> "s"; is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"
                    is Set<*> -> "ss"
                    else -> throw IOException("Unsupported secure preference type")
                }
                json.put(name, JSONObject().put("t", type).put("v", if (value is Set<*>) JSONArray(value.toList()) else value))
            }
            return json.toString().toByteArray(Charsets.UTF_8)
        }
        private fun deserialize(bytes: ByteArray): Map<String, Any> {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            return json.keys().asSequence().associateWith { name ->
                val entry = json.getJSONObject(name)
                when (entry.getString("t")) {
                    "s" -> entry.getString("v"); "b" -> entry.getBoolean("v"); "i" -> entry.getInt("v")
                    "l" -> entry.getLong("v"); "f" -> entry.getDouble("v").toFloat()
                    "ss" -> entry.getJSONArray("v").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                    else -> throw IOException("Unsupported recovery preference type")
                }
            }.also { require(KEY !in it && GENERATION !in it) }
        }
        internal fun put(editor: SharedPreferences.Editor, name: String, value: Any?) {
            when (value) {
                null -> editor.remove(name)
                is String -> editor.putString(name, value)
                is Boolean -> editor.putBoolean(name, value)
                is Int -> editor.putInt(name, value)
                is Long -> editor.putLong(name, value)
                is Float -> editor.putFloat(name, value)
                is Set<*> -> editor.putStringSet(name, value.map { it as String }.toSet())
                else -> throw IOException("Unsupported secure preference type")
            }
        }
    }

    private fun read(file: AtomicFile): JSONObject {
        return file.openRead().use { input ->
            // openRead may select the backup file after an interrupted atomic write.
            // Bound the stream itself, not just the size of the primary filename.
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }
    private fun write(file: AtomicFile, json: JSONObject) {
        check(directory.isDirectory || directory.mkdirs()) { "Recovery directory unavailable" }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private fun readEnvelope(): JSONObject = read(envelope).also {
        require(it.getInt("version") == 1 && it.getInt("rounds") == ROUNDS)
        require(decode(it.getString("salt")).size == 32)
        UUID.fromString(it.getString("generation"))
    }
    fun available(): Boolean = synchronized(lock) { runCatching { readEnvelope(); true }.getOrDefault(false) }

    fun activeStore(): Pair<String, String>? = synchronized(lock) {
        if (!exists(selector)) return@synchronized null
        try {
            val id = UUID.fromString(read(selector).getString("id")).toString()
            "monica_recovered_$id" to "monica_recovered_key_$id"
        } catch (error: Exception) {
            throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "INVALID_RECOVERY_SELECTOR", error)
        }
    }

    /** A newer credential transaction must not fall back to an older password verifier. */
    fun validate(prefs: SharedPreferences) = synchronized(lock) {
        (prefs as? MirroredPreferences)?.checkActive()
        if (!exists(envelope)) return@synchronized
        val doc = try { readEnvelope() } catch (_: Exception) { return@synchronized }
        val generation = prefs.getString(GENERATION, null)
        if (generation != doc.getString("generation")) {
            throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "RECOVERY_COMMIT_PENDING")
        }
        // An older APK may have changed credentials without maintaining the envelope.
        // Revoke that stale recovery route; the healthy legacy store can still unlock.
        if (fingerprint(prefs.all) != doc.getString("credentials")) {
            write(envelope, JSONObject().put("version", 0).put("reason", "credential_change_requires_enrollment"))
        }
    }

    fun wrap(prefs: SharedPreferences): SharedPreferences = MirroredPreferences(prefs)

    /** Called only with a verified password and a valid MDK; atomically replaces old recovery credentials. */
    fun enroll(prefs: SharedPreferences, password: String, updates: Map<String, *>) = synchronized(lock) {
        validate(prefs)
        val raw = (prefs as? MirroredPreferences)?.delegate ?: prefs
        val values = raw.all.toMutableMap().apply { putAll(updates) }
        val current = runCatching { readEnvelope() }.getOrNull()
        val currentKey = raw.getString(KEY, null)?.let { runCatching { decode(it) }.getOrNull() }
        if (current != null && currentKey?.size == 32 &&
            raw.getString(GENERATION, null) == current.getString("generation") &&
            current.getString("credentials") == fingerprint(values)) {
            try {
                val aad = "monica-recovery-prefs:${current.getString("generation")}:${current.getString("credentials")}"
                val snapshot = unseal(currentKey, current.getString("snapshot"), aad)
                val plain = serialize(values)
                try {
                    // An older APK can write new protected values without updating this file.
                    if (!MessageDigest.isEqual(snapshot, plain) || raw.all != values) persist(raw, values, current, currentKey)
                } finally { snapshot.fill(0); plain.fill(0) }
            } finally { currentKey.fill(0) }
            return@synchronized
        }
        currentKey?.fill(0)
        val key = randomKey()
        val salt = randomKey()
        val generation = UUID.randomUUID().toString()
        val passwordKey = derive(password, salt, ROUNDS)
        try {
            val doc = JSONObject().put("version", 1).put("rounds", ROUNDS).put("salt", encode(salt))
                .put("generation", generation).put("credentials", fingerprint(values))
                .put("key", seal(passwordKey, key, "monica-recovery-key:$generation"))
            check(MessageDigest.isEqual(key, unseal(passwordKey, doc.getString("key"), "monica-recovery-key:$generation")))
            persist(raw, values, doc, key)
        } finally { key.fill(0); passwordKey.fill(0) }
    }

    private fun persist(raw: SharedPreferences, values: Map<String, *>, doc: JSONObject, key: ByteArray) {
        val plain = serialize(values)
        try {
            val aad = "monica-recovery-prefs:${doc.getString("generation")}:${doc.getString("credentials")}"
            doc.put("snapshot", seal(key, plain, aad))
            val verified = unseal(key, doc.getString("snapshot"), aad)
            try { check(MessageDigest.isEqual(plain, verified)) } finally { verified.fill(0) }
            // Canonical recovery credentials first. A crash before the mirror commits is
            // detected by validate(), rather than accepting the old master password.
            write(envelope, doc)
            val before = raw.all
            val editor = raw.edit()
            before.keys.filter { it !in values }.forEach(editor::remove)
            values.forEach { (name, value) -> if (before[name] != value) put(editor, name, value) }
            editor.putString(KEY, encode(key)).putString(GENERATION, doc.getString("generation"))
            check(editor.commit()) { "Secure preference mirror could not be committed" }
        } finally { plain.fill(0) }
    }

    /** Preserve originals; switch only after authenticating and reading back a separate new store. */
    fun recover(password: String, publish: (() -> Unit) -> Unit = { it() }): Boolean = synchronized(lock) {
        var recoveryKey: ByteArray? = null
        var passwordKey: ByteArray? = null
        try {
            val doc = readEnvelope()
            val sourceDocument = doc.toString()
            val sourceStore = activeStore()
            val generation = doc.getString("generation")
            passwordKey = derive(password, decode(doc.getString("salt")), ROUNDS)
            recoveryKey = unseal(passwordKey, doc.getString("key"), "monica-recovery-key:$generation")
            require(recoveryKey.size == 32)
            val bytes = unseal(recoveryKey, doc.getString("snapshot"), "monica-recovery-prefs:$generation:${doc.getString("credentials")}")
            val values = try { deserialize(bytes).toMutableMap() } finally { bytes.fill(0) }
            require(fingerprint(values) == doc.getString("credentials"))
            validateMdk(password, values)
            val id = UUID.randomUUID().toString()
            val opened = SecurePreferencesStore.openRaw(context, "monica_recovered_$id", "monica_recovered_key_$id")
            values.remove("mdk_keystore_blob")
            values["biometric_enabled"] = false
            // Stage only in the new store. Rewriting the canonical envelope here would
            // change the user's recovery material even if this write or readback failed.
            val editor = opened.preferences.edit()
            values.forEach { (name, value) -> put(editor, name, value) }
            editor.putString(KEY, encode(recoveryKey)).putString(GENERATION, generation)
            check(editor.commit()) { "Recovered preferences could not be committed" }
            val checked = SecurePreferencesStore.openRaw(context, "monica_recovered_$id", "monica_recovered_key_$id")
            check(values.all { (key, value) -> checked.preferences.all[key] == value })
            check(checked.preferences.getString(KEY, null) == encode(recoveryKey))
            check(checked.preferences.getString(GENERATION, null) == generation)
            // The production caller withdraws this permission on timeout or cancellation.
            publish {
                check(readEnvelope().toString() == sourceDocument && activeStore() == sourceStore) {
                    "Recovery source changed; try again"
                }
                write(selector, JSONObject().put("id", id))
            }
            SecurityManager.clearRuntimeUnlockCache()
            SessionManager.markLocked()
            true
        } catch (_: Exception) {
            false
        } finally { recoveryKey?.fill(0); passwordKey?.fill(0) }
    }

    private fun validateMdk(password: String, values: Map<String, Any>) {
        val saltHex = values["mdk_password_salt"] as String
        require(saltHex.length == 64 && saltHex.all { it in "0123456789abcdefABCDEF" })
        val key = derive(password, saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), 100_000)
        try {
            val bytes = decode(values["mdk_password_blob"] as String)
            require(bytes.size == 60)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val mdk = cipher.doFinal(bytes, 12, bytes.size - 12)
            try { require(mdk.size == 32) } finally { mdk.fill(0) }
        } finally { key.fill(0) }
    }

    private inner class MirroredPreferences(val delegate: SharedPreferences) : SharedPreferences by delegate {
        private val storeAtOpen = activeStore()
        fun checkActive() {
            check(activeStore() == storeAtOpen) { "Secure preference store changed; reopen required" }
        }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = linkedMapOf<String, Any?>()
            private var clear = false
            override fun putString(k: String, v: String?) = apply { changes[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { changes[k] = v?.toSet() }
            override fun putInt(k: String, v: Int) = apply { changes[k] = v }
            override fun putLong(k: String, v: Long) = apply { changes[k] = v }
            override fun putFloat(k: String, v: Float) = apply { changes[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { changes[k] = v }
            override fun remove(k: String) = apply { changes[k] = null }
            override fun clear() = apply { clear = true }
            // SharedPreferences.apply has no failure result. Critical callers use commit;
            // a failed optional setting must not turn a disk error into a startup crash.
            override fun apply() {
                if (!commit()) android.util.Log.w("LocalVaultRecovery", "Secure preference update not committed")
            }
            override fun commit(): Boolean = synchronized(lock) {
                try {
                    checkActive()
                    val values = if (clear) mutableMapOf() else delegate.all.toMutableMap()
                    changes.forEach { (name, value) -> if (value == null) values.remove(name) else values[name] = value }
                    if (values == delegate.all) return@synchronized true
                    val doc = runCatching { readEnvelope() }.getOrNull()
                    val key = delegate.getString(KEY, null)?.let { decode(it) }
                    if (doc != null && key?.size == 32) {
                        try {
                            check(delegate.getString(GENERATION, null) == doc.getString("generation"))
                            if ("master_password_hash" !in values) {
                                // Explicit full reset only. Revoke recoverability before clearing credentials.
                                write(envelope, JSONObject().put("version", 0).put("reason", "explicit_reset"))
                                val editor = delegate.edit().clear()
                                values.filterKeys { it != KEY && it != GENERATION }.forEach { (k, v) -> put(editor, k, v) }
                                return@synchronized editor.commit()
                            }
                            check(fingerprint(values) == doc.getString("credentials")) { "Credential changes require password enrollment" }
                            persist(delegate, values, doc, key)
                            true
                        } finally { key.fill(0) }
                    } else {
                        val editor = delegate.edit()
                        if (clear) editor.clear()
                        changes.forEach { (k, v) -> put(editor, k, v) }
                        editor.commit()
                    }
                } catch (_: Exception) { false }
            }
        }
    }

    fun compareAndSet(prefs: SharedPreferences, name: String, expected: String, replacement: String): Boolean = synchronized(lock) {
        (prefs as? MirroredPreferences)?.checkActive()
        if (prefs.getString(name, null) != expected) return@synchronized false
        prefs.edit().putString(name, replacement).commit()
    }
}
