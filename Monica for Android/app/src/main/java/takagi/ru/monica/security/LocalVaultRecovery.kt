package takagi.ru.monica.security

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.util.Base64
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.xmlpull.v1.XmlPullParser
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independent password recovery. Files here contain authenticated ciphertext, never plaintext keys. */
internal class LocalVaultRecovery(private val context: Context) {
    private val directory = File(context.applicationInfo.dataDir, "files/local-vault-recovery")
    private val envelope = AtomicFile(File(directory, "recovery.json"))
    private val selector = AtomicFile(File(directory, "active-store.json"))
    private val lock = locks.getOrPut(directory.absolutePath) { RecoveryLock() }

    private class RecoveryLock {
        val held = ThreadLocal<Boolean>()
    }

    /** AtomicFile protects replacement, not concurrent writers in the main and :ime processes. */
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        if (lock.held.get() == true) return@synchronized action()
        check(directory.isDirectory || directory.mkdirs()) { "Recovery directory unavailable" }
        RandomAccessFile(File(directory, "recovery.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                lock.held.set(true)
                try { action() } finally { lock.held.remove() }
            }
        }
    }

    companion object {
        internal const val KEY = "local_recovery_key_v1"
        internal const val GENERATION = "local_recovery_generation_v1"
        internal const val REVISION = "local_recovery_revision_v2"
        private const val ROUNDS = 600_000
        private const val MAX_BYTES = 64 * 1024 * 1024
        private val locks = ConcurrentHashMap<String, RecoveryLock>()
        private val legacyCredentialKeys = listOf("master_password_hash", "master_password_salt", "mdk_password_blob", "mdk_password_salt")
        private val credentialKeys = legacyCredentialKeys + PasswordKdf.METADATA_KEY
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
        private fun randomKey() = ByteArray(32).also(SecureRandom()::nextBytes)
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
        private fun fingerprint(values: Map<String, *>, version: Int): String = encode(MessageDigest.getInstance("SHA-256")
            .digest(JSONArray((if (version == 1) legacyCredentialKeys else credentialKeys).map { values[it] })
                .toString().toByteArray(Charsets.UTF_8)))

        private fun sameCredentials(first: Map<String, *>, second: Map<String, *>): Boolean =
            credentialKeys.all { first[it] == second[it] && first.containsKey(it) == second.containsKey(it) }

        private fun outerProfile(values: Map<String, *>): PasswordKdf.Profile =
            PasswordKdf.fromValues(values).let { if (it.encoded == null) PasswordKdf.PBKDF2_600K else it }

        private fun documentProfile(doc: JSONObject): PasswordKdf.Profile =
            if (doc.getInt("version") == 1) PasswordKdf.PBKDF2_600K
            else PasswordKdf.fromMetadata(doc.getString("kdf")).also { require(it.encoded != null) }

        private fun keyAad(doc: JSONObject): String =
            "monica-recovery-key:${doc.getString("generation")}" +
                if (doc.getInt("version") == 1) "" else ":${doc.getString("kdf")}"

        private fun snapshotAad(doc: JSONObject): String =
            "monica-recovery-prefs:${doc.getString("generation")}:${doc.getString("credentials")}" +
                if (doc.getInt("version") == 1) "" else ":${doc.getString("kdf")}:${doc.getString("revision")}"

        private fun serialize(values: Map<String, *>): ByteArray {
            val json = JSONObject()
            values.filterKeys { it != KEY && it != GENERATION && it != REVISION }.forEach { (name, value) ->
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
            }.also { require(KEY !in it && GENERATION !in it && REVISION !in it) }
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
    private fun write(file: AtomicFile, json: JSONObject, beforeWrite: () -> Unit = {}) {
        check(directory.isDirectory || directory.mkdirs()) { "Recovery directory unavailable" }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        beforeWrite()
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private fun currentEnvelope(): JSONObject? {
        if (!exists(envelope)) return null
        val doc = read(envelope)
        when (doc.getInt("version")) {
            0 -> {
                require(doc.getString("reason") in setOf("explicit_reset", "credential_change_requires_enrollment"))
                return null
            }
            1 -> require(doc.getInt("rounds") == ROUNDS)
            2 -> {
                documentProfile(doc)
                UUID.fromString(doc.getString("revision"))
            }
            else -> throw IOException("Unsupported recovery version")
        }
        require(decode(doc.getString("salt")).size == 32)
        UUID.fromString(doc.getString("generation"))
        if (doc.has("revision")) UUID.fromString(doc.getString("revision"))
        return doc
    }
    private fun readEnvelope(): JSONObject = checkNotNull(currentEnvelope()) { "Recovery unavailable" }
    fun available(): Boolean = runCatching { locked { readEnvelope(); true } }.getOrDefault(false)

    fun activeStore(): Pair<String, String>? = locked {
        if (!exists(selector)) return@locked null
        try {
            val id = UUID.fromString(read(selector).getString("id")).toString()
            "monica_recovered_$id" to "monica_recovered_key_$id"
        } catch (error: Exception) {
            throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "INVALID_RECOVERY_SELECTOR", error)
        }
    }

    /** A newer credential transaction must not fall back to an older password verifier. */
    private fun checkMirror(prefs: SharedPreferences, doc: JSONObject) {
        val revision = if (doc.has("revision")) doc.getString("revision") else null
        if (prefs.getString(GENERATION, null) != doc.getString("generation") ||
            prefs.getString(REVISION, null) != revision) {
            throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "RECOVERY_COMMIT_PENDING")
        }
    }

    private fun refreshSameCredentialMirror(prefs: SharedPreferences, doc: JSONObject) {
        val revision = if (doc.has("revision")) doc.getString("revision") else null
        if (prefs.getString(GENERATION, null) == doc.getString("generation") &&
            prefs.getString(REVISION, null) != revision &&
            !PreparedSecureStore.isMirrorPending(context.applicationInfo.dataDir)) {
            refreshPreferenceCache(preserveSessionIfSameCredentials = true)
        }
        checkMirror(prefs, doc)
    }

    fun validate(prefs: SharedPreferences) = locked {
        (PreparedSecureStore.unwrap(prefs) as? MirroredPreferences)?.checkActive()
        val doc = currentEnvelope() ?: return@locked
        refreshSameCredentialMirror(prefs, doc)
        val profile = PasswordKdf.fromValues(prefs.all)
        // An older APK may have changed credentials without maintaining the envelope.
        // This compatibility path applies only to an actual legacy credential format.
        if (fingerprint(prefs.all, doc.getInt("version")) != doc.getString("credentials")) {
            if (doc.getInt("version") != 1 || profile.encoded != null) {
                throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "RECOVERY_CREDENTIALS_CHANGED")
            }
            write(envelope, JSONObject().put("version", 0).put("reason", "credential_change_requires_enrollment"))
        }
    }

    /** SharedPreferences caches are process-local. Reload the exact encrypted disk map under
     * the writer lock; MODE_MULTI_PROCESS's timestamp heuristic is not an integrity check. */
    fun refreshPreferenceCache(preserveSessionIfSameCredentials: Boolean = true) = locked {
        val sourceStore = activeStore()
        val name = sourceStore?.first ?: SecurePreferencesStore.MONICA
        val alias = sourceStore?.second ?: androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS
        val base = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
        val backup = File(base.path + ".bak")
        val file = if (backup.exists()) backup else base
        if (!file.exists()) return@locked
        require(file.length() in 1..MAX_BYTES.toLong()) { "Invalid secure preference file" }
        val disk = linkedMapOf<String, String>()
        file.inputStream().use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(object : java.io.FilterInputStream(stream) {
                private var total = 0L
                override fun read(): Int = super.read().also {
                    if (it >= 0) require(++total <= MAX_BYTES) { "Secure preference file too large" }
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    `in`.read(bytes, offset, length).also {
                        if (it > 0) { total += it; require(total <= MAX_BYTES) { "Secure preference file too large" } }
                    }
            }, "UTF-8")
            require(parser.nextTag() == XmlPullParser.START_TAG && parser.name == "map")
            while (parser.nextTag() == XmlPullParser.START_TAG) {
                require(parser.name == "string" && parser.attributeCount == 1)
                val key = requireNotNull(parser.getAttributeValue(null, "name"))
                require(!disk.containsKey(key)) { "Duplicate secure preference key" }
                disk[key] = parser.nextText()
            }
            require(parser.eventType == XmlPullParser.END_TAG && parser.name == "map")
            var event = parser.next()
            while (event == XmlPullParser.TEXT && parser.isWhitespace) event = parser.next()
            require(event == XmlPullParser.END_DOCUMENT)
        }
        require(disk.isNotEmpty()) { "Empty secure preference file" }
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        var preservedCredentials: Map<String, *>? = null
        if (raw.all != disk) {
            val keysetNames = listOf("__androidx_security_crypto_encrypted_prefs_key_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__")
            val sameKeysets = keysetNames.all { raw.getString(it, null) != null && raw.getString(it, null) == disk[it] }
            if (preserveSessionIfSameCredentials && sameKeysets &&
                !PreparedSecureStore.isMirrorPending(context.applicationInfo.dataDir)) {
                preservedCredentials = runCatching {
                    val doc = readEnvelope()
                    val cached = SecurePreferencesStore.openRaw(context, name, alias).preferences.all
                    check(cached[GENERATION] == doc.getString("generation") &&
                        fingerprint(cached, doc.getInt("version")) == doc.getString("credentials"))
                    val key = decode(cached[KEY] as String)
                    try {
                        val bytes = unseal(key, doc.getString("snapshot"), snapshotAad(doc))
                        try {
                            val canonical = deserialize(bytes)
                            check(fingerprint(canonical, doc.getInt("version")) == doc.getString("credentials") &&
                                sameCredentials(cached, canonical))
                        } finally { bytes.fill(0) }
                    } finally { key.fill(0) }
                    cached
                }.getOrNull()
            }
            if (preservedCredentials == null) {
                SecurityManager.clearRuntimeUnlockCache()
                SessionManager.markLocked()
                SecondarySessionManager.markLocked()
            }
            try {
                val editor = raw.edit().clear()
                disk.forEach { (key, value) -> editor.putString(key, value) }
                check(editor.commit() && raw.all == disk) { "Secure preference cache could not be refreshed" }
                val checked = SecurePreferencesStore.openRaw(context, name, alias).preferences
                val doc = currentEnvelope()
                if (doc != null) checkMirror(checked, doc)
                if (preservedCredentials != null) {
                    check(activeStore() == sourceStore && doc != null &&
                        checked.getString(GENERATION, null) == preservedCredentials[GENERATION] &&
                        sameCredentials(checked.all, preservedCredentials) &&
                        fingerprint(checked.all, doc.getInt("version")) == doc.getString("credentials"))
                }
            } catch (error: Exception) {
                PreparedSecureStore.markMirrorPending(context.applicationInfo.dataDir)
                SessionManager.markLocked()
                throw error
            }
        }
        // commit(false) may still have published SharedPreferences' in-memory map. Only an
        // authenticated disk reload agreeing with the canonical transaction can reopen it.
        if (PreparedSecureStore.isMirrorPending(context.applicationInfo.dataDir)) {
            val checked = SecurePreferencesStore.openRaw(context, name, alias).preferences
            currentEnvelope()?.let { doc ->
                checkMirror(checked, doc)
                check(fingerprint(checked.all, doc.getInt("version")) == doc.getString("credentials"))
            }
            PreparedSecureStore.clearMirrorPending(context.applicationInfo.dataDir)
        }
    }

    fun wrap(prefs: SharedPreferences): SharedPreferences = MirroredPreferences(prefs)

    /** Called only with a verified password and a valid MDK; atomically replaces old recovery credentials. */
    fun enroll(
        prefs: SharedPreferences,
        password: String,
        updates: Map<String, *>,
        expectedCredentials: Map<String, *>? = null,
        beforeCommit: () -> Unit = {},
    ) = locked {
        validate(prefs)
        val unwrapped = PreparedSecureStore.unwrap(prefs)
        val raw = (unwrapped as? MirroredPreferences)?.delegate ?: unwrapped
        val before = raw.all.toMap()
        require(expectedCredentials == null || sameCredentials(before, expectedCredentials)) {
            "Credential change conflicted with another authentication"
        }
        val values = before.toMutableMap().apply { putAll(updates) }
        val profile = outerProfile(values)
        val current = currentEnvelope()
        val source = current?.toString()
        val sourceStore = activeStore()
        val authorizeCommit = {
            check(raw.all == before && activeStore() == sourceStore && currentEnvelope()?.toString() == source) {
                "Secure preferences changed during credential preparation"
            }
            check(expectedCredentials == null || sameCredentials(raw.all, expectedCredentials)) {
                "Credential change conflicted with another authentication"
            }
            beforeCommit()
        }
        val currentKey = raw.getString(KEY, null)?.let { runCatching { decode(it) }.getOrNull() }
        if (current != null && currentKey?.size == 32 &&
            raw.getString(GENERATION, null) == current.getString("generation") &&
            current.getString("credentials") == fingerprint(values, current.getInt("version")) &&
            documentProfile(current).encoded == profile.encoded &&
            (current.getInt("version") == 2 || PasswordKdf.fromValues(values).encoded == null)) {
            try {
                val snapshot = unseal(currentKey, current.getString("snapshot"), snapshotAad(current))
                val plain = serialize(values)
                try {
                    // An older APK can write new protected values without updating this file.
                    if (!MessageDigest.isEqual(snapshot, plain) || before != values) {
                        persist(raw, values, current, currentKey, authorizeCommit)
                    } else authorizeCommit()
                } finally { snapshot.fill(0); plain.fill(0) }
            } finally { currentKey.fill(0) }
            return@locked
        }
        currentKey?.fill(0)
        val key = randomKey()
        val salt = randomKey()
        val generation = UUID.randomUUID().toString()
        val passwordKey = PasswordKdf.derive(password, salt, profile)
        try {
            val doc = JSONObject().put("version", 2).put("kdf", checkNotNull(profile.encoded)).put("salt", encode(salt))
                .put("generation", generation).put("credentials", fingerprint(values, 2))
            doc.put("key", seal(passwordKey, key, keyAad(doc)))
            val verified = unseal(passwordKey, doc.getString("key"), keyAad(doc))
            try { check(MessageDigest.isEqual(key, verified)) } finally { verified.fill(0) }
            persist(raw, values, doc, key, authorizeCommit)
        } finally { key.fill(0); passwordKey.fill(0) }
    }

    private fun persist(
        raw: SharedPreferences,
        values: Map<String, *>,
        source: JSONObject,
        key: ByteArray,
        beforeCommit: () -> Unit = {},
    ) {
        // Keep the bounded publication header before the potentially large snapshot.
        val doc = JSONObject()
        source.keys().asSequence().filter { it != "snapshot" && it != "revision" }
            .forEach { doc.put(it, source.get(it)) }
        val revision = UUID.randomUUID().toString()
        doc.put("revision", revision)
        val plain = serialize(values)
        var inFlight: PreparedSecureStore.MirrorCommit? = null
        try {
            val aad = snapshotAad(doc)
            doc.put("snapshot", seal(key, plain, aad))
            val verified = unseal(key, doc.getString("snapshot"), aad)
            try { check(MessageDigest.isEqual(plain, verified)) } finally { verified.fill(0) }
            // Canonical recovery credentials first. A crash before the mirror commits is
            // detected by validate(), rather than accepting the old master password.
            write(envelope, doc) {
                beforeCommit()
                inFlight = PreparedSecureStore.beginMirrorCommit(
                    context.applicationInfo.dataDir, raw.getString(GENERATION, null), raw.getString(REVISION, null),
                    doc.getString("generation"), revision,
                )
            }
            try {
                val before = raw.all
                val editor = raw.edit()
                before.keys.filter { it !in values }.forEach(editor::remove)
                values.forEach { (name, value) -> if (before[name] != value) put(editor, name, value) }
                editor.putString(KEY, encode(key)).putString(GENERATION, doc.getString("generation"))
                    .putString(REVISION, revision)
                check(editor.commit()) { "Secure preference mirror could not be committed" }
            } catch (error: Exception) {
                PreparedSecureStore.markMirrorPending(context.applicationInfo.dataDir)
                throw error
            }
        } finally {
            inFlight?.let(PreparedSecureStore::finishMirrorCommit)
            plain.fill(0)
        }
    }

    /** Preserve originals; switch only after authenticating and reading back a separate new store. */
    fun recover(password: String, publish: (() -> Unit) -> Unit = { it() }): Boolean = runCatching { locked {
        var recoveryKey: ByteArray? = null
        var passwordKey: ByteArray? = null
        try {
            val doc = readEnvelope()
            val sourceDocument = doc.toString()
            val sourceStore = activeStore()
            val generation = doc.getString("generation")
            passwordKey = PasswordKdf.derive(password, decode(doc.getString("salt")), documentProfile(doc))
            recoveryKey = unseal(passwordKey, doc.getString("key"), keyAad(doc))
            require(recoveryKey.size == 32)
            val bytes = unseal(recoveryKey, doc.getString("snapshot"), snapshotAad(doc))
            val values = try { deserialize(bytes).toMutableMap() } finally { bytes.fill(0) }
            require(fingerprint(values, doc.getInt("version")) == doc.getString("credentials"))
            require(outerProfile(values).encoded == documentProfile(doc).encoded)
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
            if (doc.has("revision")) editor.putString(REVISION, doc.getString("revision"))
            check(editor.commit()) { "Recovered preferences could not be committed" }
            val checked = SecurePreferencesStore.openRaw(context, "monica_recovered_$id", "monica_recovered_key_$id")
            check(values.all { (key, value) -> checked.preferences.all[key] == value })
            check(checked.preferences.getString(KEY, null) == encode(recoveryKey))
            check(checked.preferences.getString(GENERATION, null) == generation)
            check(checked.preferences.getString(REVISION, null) ==
                if (doc.has("revision")) doc.getString("revision") else null)
            // The production caller withdraws this permission on timeout or cancellation.
            publish {
                check(readEnvelope().toString() == sourceDocument && activeStore() == sourceStore) {
                    "Recovery source changed; try again"
                }
                write(selector, JSONObject().put("id", id))
                PreparedSecureStore.invalidate()
            }
            SecurityManager.clearRuntimeUnlockCache()
            SessionManager.markLocked()
            true
        } catch (_: Exception) {
            false
        } finally { recoveryKey?.fill(0); passwordKey?.fill(0) }
    } }.getOrDefault(false)

    private fun validateMdk(password: String, values: Map<String, Any>) {
        val saltHex = values["mdk_password_salt"] as String
        require(saltHex.length == 64 && saltHex.all { it in "0123456789abcdefABCDEF" })
        val profile = PasswordKdf.fromValues(values)
        val key = PasswordKdf.derive(password, saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), profile)
        try {
            val bytes = decode(values["mdk_password_blob"] as String)
            require(bytes.size == 60)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            PasswordKdf.mdkAad(profile)?.let(cipher::updateAAD)
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
            override fun commit(): Boolean = runCatching { locked {
                try {
                    checkActive()
                    val doc = currentEnvelope()
                    if (doc != null) refreshSameCredentialMirror(delegate, doc)
                    val before = delegate.all.toMap()
                    val values = if (clear) mutableMapOf() else before.toMutableMap()
                    changes.forEach { (name, value) -> if (value == null) values.remove(name) else values[name] = value }
                    if (values == before) return@locked true
                    val key = delegate.getString(KEY, null)?.let { decode(it) }
                    if (doc != null) {
                        check(key?.size == 32) { "Recovery key unavailable" }
                        val verifiedKey = checkNotNull(key)
                        try {
                            if ("master_password_hash" !in values) {
                                // Explicit full reset only. Revoke recoverability before clearing credentials.
                                write(envelope, JSONObject().put("version", 0).put("reason", "explicit_reset"))
                                val editor = delegate.edit().clear()
                                values.filterKeys { it != KEY && it != GENERATION && it != REVISION }.forEach { (k, v) -> put(editor, k, v) }
                                return@locked editor.commit()
                            }
                            check(sameCredentials(before, values) &&
                                fingerprint(values, doc.getInt("version")) == doc.getString("credentials")) {
                                "Credential changes require password enrollment"
                            }
                            // Authenticate the existing canonical material before replacing it.
                            unseal(verifiedKey, doc.getString("snapshot"), snapshotAad(doc)).fill(0)
                            persist(delegate, values, doc, verifiedKey) {
                                check(delegate.all == before) { "Secure preference mirror changed" }
                                checkMirror(delegate, doc)
                            }
                            true
                        } finally { verifiedKey.fill(0) }
                    } else {
                        key?.fill(0)
                        val editor = delegate.edit()
                        if (clear) editor.clear()
                        changes.forEach { (k, v) -> put(editor, k, v) }
                        editor.commit()
                    }
                } catch (_: Exception) { false }
            } }.getOrDefault(false)
        }
    }

    fun compareAndSet(prefs: SharedPreferences, name: String, expected: String, replacement: String): Boolean = locked {
        (prefs as? MirroredPreferences)?.checkActive()
        if (prefs.getString(name, null) != expected) return@locked false
        prefs.edit().putString(name, replacement).commit()
    }
}
