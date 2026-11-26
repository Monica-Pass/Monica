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
    private val envelope get() = AtomicFile(selectedEnvelopeFile())
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
        private data class CheckRecord(val digest: String, val selector: RecoveryStoreSelection,
            val session: Long, val verifiedAtMillis: Long?)
        private val checks = ConcurrentHashMap<String, CheckRecord>()
        private val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
        internal val protectionChanges: kotlinx.coroutines.flow.StateFlow<Long> = changes
        internal fun clearProtectionChecks() {
            checks.clear()
            changed()
        }
        private fun changed() { synchronized(changes) { changes.value = changes.value + 1 } }
        internal const val KEY = "local_recovery_key_v1"
        internal const val GENERATION = "local_recovery_generation_v1"
        internal const val REVISION = "local_recovery_revision_v2"
        private const val ROUNDS = 600_000
        private const val MAX_BYTES = 64 * 1024 * 1024
        private val locks = ConcurrentHashMap<String, RecoveryLock>()
        private val passwordPreferences = ConcurrentHashMap<String, PasswordRecoveryPreferences>()
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
        return parseEnvelope(read(envelope))
    }
    private fun parseEnvelope(doc: JSONObject): JSONObject? {
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
    fun available(): Boolean = materialState() == RecoveryMaterialState.AVAILABLE

    /** No password, key generation or secure preference opening is performed by this probe. */
    fun materialState(): RecoveryMaterialState = try { locked { materialStateLocked() } }
        catch (_: Exception) { RecoveryMaterialState.UNREADABLE }

    private fun materialStateLocked(): RecoveryMaterialState {
        if (!exists(envelope)) return RecoveryMaterialState.MISSING
        val doc = try { read(envelope) }
            catch (_: IOException) { return RecoveryMaterialState.UNREADABLE }
            catch (_: SecurityException) { return RecoveryMaterialState.UNREADABLE }
            catch (_: Exception) { return RecoveryMaterialState.INVALID }
        return try {
            when (doc.getInt("version")) {
                0 -> return when (doc.getString("reason")) {
                    "explicit_reset" -> RecoveryMaterialState.REVOKED
                    "credential_change_requires_enrollment" -> RecoveryMaterialState.REENROLLMENT_REQUIRED
                    else -> RecoveryMaterialState.INVALID
                }
                1, 2 -> Unit
                else -> return RecoveryMaterialState.UNSUPPORTED_VERSION
            }
            parseEnvelope(doc)
            require(decode(doc.getString("credentials")).size == 32)
            require(decode(doc.getString("key")).size == 60)
            require(decode(doc.getString("snapshot")).size in 28..MAX_BYTES)
            RecoveryMaterialState.AVAILABLE
        } catch (_: Exception) { RecoveryMaterialState.INVALID }
    }

    private fun digest(doc: JSONObject): String = encode(MessageDigest.getInstance("SHA-256")
        .digest(doc.toString().toByteArray(Charsets.UTF_8)))

    /** Only scalar evidence is retained; every document/selector/session change retires it. */
    fun protectionStatus(session: Long): RecoveryProtectionStatus = locked {
        val material = materialStateLocked()
        if (material != RecoveryMaterialState.AVAILABLE) return@locked RecoveryProtectionStatus(material)
        val record = checks[directory.absolutePath]?.takeIf {
            it.session == session && it.digest == digest(readEnvelope()) && it.selector == selection()
        }
        RecoveryProtectionStatus(material, record?.verifiedAtMillis, record != null && record.verifiedAtMillis == null)
    }

    /** Expensive KDF/decryption runs outside the storage lock, including for optional checks. */
    fun verifyProtection(prefs: SharedPreferences, password: String, expectedMdk: ByteArray,
        session: Long, ensureCurrent: () -> Unit, publish: (() -> Unit) -> Unit): Boolean {
        var passwordKey: ByteArray? = null
        var key: ByteArray? = null
        var bytes: ByteArray? = null
        var recoveredMdk: ByteArray? = null
        var proof: CheckRecord? = null
        try {
            val (doc, current) = locked {
                ensureCurrent()
                checks.remove(directory.absolutePath)
                (PreparedSecureStore.unwrap(prefs) as? MirroredPreferences)?.checkActive()
                check(materialStateLocked() == RecoveryMaterialState.AVAILABLE)
                val doc = readEnvelope()
                checkMirror(prefs, doc)
                check(fingerprint(prefs.all, doc.getInt("version")) == doc.getString("credentials"))
                proof = CheckRecord(digest(doc), selection(), session, null)
                doc to prefs.all.toMap()
            }
            val captured = checkNotNull(proof)
            passwordKey = PasswordKdf.derive(password, decode(doc.getString("salt")), documentProfile(doc))
            ensureCurrent()
            key = unseal(passwordKey, doc.getString("key"), keyAad(doc))
            require(key.size == 32)
            bytes = unseal(key, doc.getString("snapshot"), snapshotAad(doc))
            val values = deserialize(bytes)
            require(fingerprint(values, doc.getInt("version")) == doc.getString("credentials"))
            require(outerProfile(values).encoded == documentProfile(doc).encoded)
            require(values == current.filterKeys { it != KEY && it != GENERATION && it != REVISION })
            recoveredMdk = LocalPasswordCredentials.unwrap(password, values)
            require(MessageDigest.isEqual(expectedMdk, recoveredMdk))
            locked {
                ensureCurrent()
                check(prefs.all == current && digest(readEnvelope()) == captured.digest && selection() == captured.selector)
                val verified = captured.copy(verifiedAtMillis = System.currentTimeMillis())
                publish { checks[directory.absolutePath] = verified }
            }
            return true
        } catch (_: Exception) {
            // A cancelled/retired attempt must not publish either success or failure.
            runCatching { locked {
                ensureCurrent()
                proof?.takeIf { it.digest == digest(readEnvelope()) && it.selector == selection() }
                    ?.let { record -> publish { checks[directory.absolutePath] = record } }
            } }
            return false
        } finally {
            passwordKey?.fill(0); key?.fill(0); bytes?.fill(0); recoveredMdk?.fill(0)
            changed()
        }
    }

    internal fun selection(): RecoveryStoreSelection = locked {
        try {
            RecoveryStoreSelectors.read(directory)
        } catch (error: Exception) {
            throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "INVALID_RECOVERY_SELECTOR", error)
        }
    }

    /** Keystore-only compatibility boundary. A password store must never look like a new install. */
    fun activeStore(): Pair<String, String>? = when (val selected = selection()) {
        RecoveryStoreSelection.Original -> null
        is RecoveryStoreSelection.Keystore -> selected.name to selected.alias
        is RecoveryStoreSelection.Password -> throw SecureStorageUnavailableException(
            SecurePreferencesStore.MONICA, "PASSWORD_RECOVERY_ACTIVE")
    }

    internal fun selectedEnvelopeFile(): File = RecoveryStoreSelectors.envelope(directory, selection())

    internal fun usesPasswordStore(): Boolean = selection() is RecoveryStoreSelection.Password

    internal fun passwordRawPreferences(): PasswordRecoveryPreferences = locked {
        val selected = selection() as? RecoveryStoreSelection.Password
            ?: error("Password recovery store is not selected")
        refreshPasswordPreferences(selected)
    }

    internal fun forgetPasswordSession() { passwordPreferences.remove(directory.absolutePath)?.retire() }

    internal fun passwordSessionIdentity(): Any? = passwordPreferences[directory.absolutePath]

    private fun refreshPasswordPreferences(selected: RecoveryStoreSelection.Password): PasswordRecoveryPreferences {
        val raw = passwordPreferences[directory.absolutePath]?.takeIf { it.selection == selected }
            ?: throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "PASSWORD_RECOVERY_REQUIRED")
        val doc = readEnvelope()
        val before = raw.all
        if (before[GENERATION] != doc.getString("generation") ||
            fingerprint(before, doc.getInt("version")) != doc.getString("credentials")) {
            passwordPreferences.remove(directory.absolutePath, raw)
            raw.retire()
            throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "PASSWORD_RECOVERY_REQUIRED")
        }
        val key = decode(before[KEY] as String)
        try {
            val bytes = unseal(key, doc.getString("snapshot"), snapshotAad(doc))
            val values = try { deserialize(bytes) } finally { bytes.fill(0) }
            check(sameCredentials(before, values)) { "Password recovery credentials changed" }
            check(selection() == selected)
            raw.replace(withRecoveryMetadata(values, doc, key))
            PreparedSecureStore.clearMirrorPending(context.applicationInfo.dataDir)
        } finally { key.fill(0) }
        return raw
    }

    private fun withRecoveryMetadata(values: Map<String, *>, doc: JSONObject, key: ByteArray): Map<String, Any> =
        values.filterValues { it != null }.mapValues { requireNotNull(it.value) }.toMutableMap().apply {
            put(KEY, encode(key)); put(GENERATION, doc.getString("generation"))
            if (doc.has("revision")) put(REVISION, doc.getString("revision")) else remove(REVISION)
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
        val sourceStore = selection()
        if (sourceStore is RecoveryStoreSelection.Password) {
            refreshPasswordPreferences(sourceStore)
            return@locked
        }
        val name = (sourceStore as? RecoveryStoreSelection.Keystore)?.name ?: SecurePreferencesStore.MONICA
        val alias = (sourceStore as? RecoveryStoreSelection.Keystore)?.alias ?: androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS
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
                    check(selection() == sourceStore && doc != null &&
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

    /** Storage lock precedes the short authorization gate, as it does for password enrollment. */
    internal fun commitAuthenticatedSettings(
        prefs: SharedPreferences,
        updates: Map<String, Any?>,
        authorizeCommit: () -> Unit,
    ): Boolean = locked {
        validate(prefs)
        val editor = prefs.edit()
        updates.forEach { (key, value) -> put(editor, key, value) }
        authorizeCommit()
        editor.commit()
    }

    /** A settings check may fill missing protection, but must never refresh existing evidence. */
    fun enrollMissingProtection(prefs: SharedPreferences, password: String,
        expectedCredentials: Map<String, *>, beforeCommit: () -> Unit) = locked {
        when (materialStateLocked()) {
            RecoveryMaterialState.AVAILABLE -> Unit
            RecoveryMaterialState.MISSING, RecoveryMaterialState.REENROLLMENT_REQUIRED ->
                enroll(prefs, password, emptyMap<String, Any>(), expectedCredentials, beforeCommit)
            else -> error("Existing recovery material must be preserved")
        }
    }

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
        val sourceStore = selection()
        val authorizeCommit = {
            check(raw.all == before && selection() == sourceStore && currentEnvelope()?.toString() == source) {
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

    private fun canonicalDocument(values: Map<String, *>, source: JSONObject, key: ByteArray): JSONObject {
        val doc = JSONObject()
        source.keys().asSequence().filter { it != "snapshot" && it != "revision" }
            .forEach { doc.put(it, source.get(it)) }
        doc.put("revision", UUID.randomUUID().toString())
        val plain = serialize(values)
        try {
            val aad = snapshotAad(doc)
            doc.put("snapshot", seal(key, plain, aad))
            val verified = unseal(key, doc.getString("snapshot"), aad)
            try { check(MessageDigest.isEqual(plain, verified)) } finally { verified.fill(0) }
            return doc
        } finally { plain.fill(0) }
    }

    private fun verifyWrittenSnapshot(file: AtomicFile, expected: JSONObject, key: ByteArray, values: Map<String, *>) {
        val stored = read(file)
        check(stored.toString() == expected.toString()) { "Recovery write could not be read back" }
        val bytes = unseal(key, stored.getString("snapshot"), snapshotAad(stored))
        try {
            check(deserialize(bytes) == values.filterKeys { it != KEY && it != GENERATION && it != REVISION })
        } finally { bytes.fill(0) }
    }

    private fun persist(
        raw: SharedPreferences,
        values: Map<String, *>,
        source: JSONObject,
        key: ByteArray,
        beforeCommit: () -> Unit = {},
    ) {
        // Keep the bounded publication header before the potentially large snapshot.
        val doc = canonicalDocument(values, source, key)
        val revision = doc.getString("revision")
        var inFlight: PreparedSecureStore.MirrorCommit? = null
        try {
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
                if (raw is PasswordRecoveryPreferences) {
                    check(selection() == raw.selection)
                    verifyWrittenSnapshot(envelope, doc, key, values)
                    raw.replace(withRecoveryMetadata(values, doc, key))
                    return
                }
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
            checks.remove(directory.absolutePath)
            changed()
        }
    }

    /** Authentication itself needs no AndroidKeyStore. Callers must retain this source check
     * through their final publication; a stale password snapshot is never a recovery permit. */
    internal fun authenticatePasswordMaterial(password: String, ensureActive: () -> Unit = {}): PasswordRecoveryMaterial {
        val (doc, sourceStore) = locked { ensureActive(); readEnvelope() to selection() }
        val sourceDocument = doc.toString()
        fun current() {
            ensureActive()
            locked {
                check(readEnvelope().toString() == sourceDocument && selection() == sourceStore) {
                    "Recovery source changed"
                }
            }
            ensureActive()
        }
        var passwordKey: ByteArray? = null
        var recoveryKey: ByteArray? = null
        var mdk: ByteArray? = null
        try {
            passwordKey = PasswordKdf.derive(password, decode(doc.getString("salt")), documentProfile(doc))
            ensureActive()
            recoveryKey = unseal(passwordKey, doc.getString("key"), keyAad(doc))
            require(recoveryKey.size == 32)
            val bytes = unseal(recoveryKey, doc.getString("snapshot"), snapshotAad(doc))
            val values = try { deserialize(bytes) } finally { bytes.fill(0) }
            require(fingerprint(values, doc.getInt("version")) == doc.getString("credentials"))
            require(outerProfile(values).encoded == documentProfile(doc).encoded)
            mdk = LocalPasswordCredentials.unwrap(password, values)
            current()
            val result = PasswordRecoveryMaterial(values, mdk, recoveryKey, ::current)
            mdk = null; recoveryKey = null // Ownership passes to the closeable material.
            return result
        } finally { passwordKey?.fill(0); recoveryKey?.fill(0); mdk?.fill(0) }
    }

    /** Explicit fallback only. No database, legacy store or alias is overwritten. */
    internal fun recoverWithoutKeystore(password: String, publish: (() -> Unit) -> Unit = { it() }): Boolean =
        runCatching {
            authenticatePasswordMaterial(password).use { material ->
                val key = material.copyRecoveryKey()
                try { locked {
                    material.checkCurrent()
                    val sourceSelection = selection()
                    val source = readEnvelope()
                    val values = material.preferenceSnapshot().toMutableMap()
                    values.remove("mdk_keystore_blob")
                    values.remove(BiometricMdkBinding.ACTIVE)
                    values["biometric_enabled"] = false
                    RecoverableBitwardenSettings.disableRecoveredAutomation(values)
                    val selected = sourceSelection as? RecoveryStoreSelection.Password
                        ?: RecoveryStoreSelection.Password(UUID.randomUUID().toString())
                    // A cold unlock of an existing fallback does not rewrite its credentials.
                    val doc = if (sourceSelection == selected) source else canonicalDocument(values, source, key)
                    val actualValues = if (sourceSelection == selected) material.preferenceSnapshot() else values
                    val target = AtomicFile(RecoveryStoreSelectors.envelope(directory, selected))
                    if (sourceSelection != selected) write(target, doc) { material.checkCurrent() }
                    verifyWrittenSnapshot(target, doc, key, actualValues)
                    val raw = PasswordRecoveryPreferences(selected, withRecoveryMetadata(actualValues, doc, key))
                    publish {
                        material.checkCurrent()
                        if (sourceSelection != selected) write(selector, RecoveryStoreSelectors.encode(selected))
                        passwordPreferences.put(directory.absolutePath, raw)?.retire()
                        PreparedSecureStore.invalidate()
                    }
                    SecurityManager.clearRuntimeUnlockCache()
                    SessionManager.markLocked()
                    true
                } } finally { key.fill(0) }
            }
        }.getOrDefault(false)

    /** Preserve originals; switch only after authenticating and reading back a separate new store. */
    fun recover(password: String, publish: (() -> Unit) -> Unit = { it() }): Boolean = runCatching { locked {
        if (selection() is RecoveryStoreSelection.Password) return@locked recoverWithoutKeystore(password, publish)
        var recoveryKey: ByteArray? = null
        var material: PasswordRecoveryMaterial? = null
        try {
            val doc = readEnvelope()
            val sourceDocument = doc.toString()
            val sourceStore = selection()
            val generation = doc.getString("generation")
            material = authenticatePasswordMaterial(password)
            recoveryKey = material.copyRecoveryKey()
            val values = material.preferenceSnapshot().toMutableMap()
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
                check(readEnvelope().toString() == sourceDocument && selection() == sourceStore) {
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
        } finally { recoveryKey?.fill(0); material?.close() }
    } }.getOrDefault(false)

    private inner class MirroredPreferences(val delegate: SharedPreferences) : SharedPreferences by delegate {
        private val storeAtOpen = selection()
        fun checkActive() {
            check(selection() == storeAtOpen) { "Secure preference store changed; reopen required" }
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
                                check(delegate !is PasswordRecoveryPreferences) {
                                    "A password recovery store cannot revoke its only unlock path"
                                }
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

    /** Read/merge/write under the same cross-process lock as the canonical document. */
    internal fun updateProtectedObject(prefs: SharedPreferences, name: String, update: (JSONObject) -> Unit): Boolean = locked {
        validate(prefs)
        val snapshot = prefs.getString(name, null)?.let(::JSONObject) ?: JSONObject()
        update(snapshot)
        prefs.edit().putString(name, snapshot.toString()).commit()
    }
}
