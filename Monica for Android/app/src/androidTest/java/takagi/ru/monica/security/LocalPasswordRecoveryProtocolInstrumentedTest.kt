package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import android.util.Xml
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Isolated real cipher/preferences checks; process-cache interleaves are deterministic models. */
class LocalPasswordRecoveryProtocolInstrumentedTest {
    private class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "password-recovery-protocol-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        private val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = root.absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names.add(name)
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        val password = "Synthetic-protocol-password-327!"
        val mdk = random(32)
        val recovery = LocalVaultRecovery(context)
        val prefs = SecurePreferencesStore.openRaw(context, SecurePreferencesStore.MONICA,
            MasterKey.DEFAULT_MASTER_KEY_ALIAS).preferences
        val raw get() = context.getSharedPreferences(SecurePreferencesStore.MONICA, Context.MODE_PRIVATE)
        val envelope get() = File(root, "files/local-vault-recovery/recovery.json")

        fun credentials(profile: PasswordKdf.Profile): Map<String, Any> {
            val salt = random(16)
            val wrapSalt = random(32)
            val verifier = derive(password, salt, profile)
            val wrapKey = derive(password, wrapSalt, profile)
            return try {
                mutableMapOf<String, Any>(
                    "master_password_hash" to hex(verifier), "master_password_salt" to hex(salt),
                    "mdk_password_salt" to hex(wrapSalt),
                    "mdk_password_blob" to seal(wrapKey, mdk, profile.encoded?.let { "monica-local-mdk:$it" }),
                    "mdk_ready" to true, "protocol-sentinel" to "retained-value",
                ).apply { profile.encoded?.let { put(PasswordKdf.METADATA_KEY, it) } }
            } finally { verifier.fill(0); wrapKey.fill(0) }
        }

        fun enroll(profile: PasswordKdf.Profile = PasswordKdf.PBKDF2_600K) {
            recovery.enroll(prefs, password, credentials(profile), expectedCredentials = prefs.all.toMap())
        }

        fun recovered(): SharedPreferences {
            val (name, alias) = checkNotNull(recovery.activeStore())
            return SecurePreferencesStore.openRaw(context, name, alias).preferences
        }

        fun writeDiskMap(name: String, values: Map<String, *>) {
            val disk = File(root, "shared_prefs/$name.xml")
            check(disk.parentFile!!.isDirectory || disk.parentFile!!.mkdirs())
            disk.outputStream().use { output ->
                val xml = Xml.newSerializer()
                xml.setOutput(output, "UTF-8")
                xml.startDocument("UTF-8", true)
                xml.startTag(null, "map")
                values.forEach { (key, value) ->
                    xml.startTag(null, "string").attribute(null, "name", key).text(value as String).endTag(null, "string")
                }
                xml.endTag(null, "map").endDocument()
            }
        }

        fun writeLegacyEnvelope() {
            val values = credentials(PasswordKdf.LEGACY)
            val generation = UUID.randomUUID().toString()
            val key = random(32)
            val salt = random(32)
            val passwordKey = derive(password, salt, PasswordKdf.PBKDF2_600K)
            try {
                val fields = listOf("master_password_hash", "master_password_salt", "mdk_password_blob", "mdk_password_salt")
                val fingerprint = encode(MessageDigest.getInstance("SHA-256")
                    .digest(JSONArray(fields.map { values[it] }).toString().toByteArray(Charsets.UTF_8)))
                val snapshot = JSONObject()
                values.forEach { (name, value) -> snapshot.put(name, JSONObject()
                    .put("t", if (value is Boolean) "b" else "s").put("v", value)) }
                val doc = JSONObject().put("version", 1).put("rounds", 600_000).put("salt", encode(salt))
                    .put("generation", generation).put("credentials", fingerprint)
                    .put("key", seal(passwordKey, key, "monica-recovery-key:$generation"))
                    .put("snapshot", seal(key, snapshot.toString().toByteArray(Charsets.UTF_8),
                        "monica-recovery-prefs:$generation:$fingerprint"))
                check(envelope.parentFile!!.isDirectory || envelope.parentFile!!.mkdirs())
                envelope.writeText(doc.toString())
                val editor = prefs.edit()
                values.forEach { (name, value) -> LocalVaultRecovery.put(editor, name, value) }
                check(editor.putString(LocalVaultRecovery.KEY, encode(key))
                    .putString(LocalVaultRecovery.GENERATION, generation).commit())
            } finally { key.fill(0); passwordKey.fill(0) }
        }

        override fun close() {
            PreparedSecureStore.invalidate()
            SessionManager.markLocked()
            PreparedSecureStore.clearMirrorPending(context.applicationInfo.dataDir)
            mdk.fill(0)
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            names.filter { it.startsWith("monica_recovered_") }.forEach {
                val alias = it.replace("monica_recovered_", "monica_recovered_key_")
                if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
            }
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.parentFile == base.cacheDir && root.name == prefix)
            root.deleteRecursively()
        }
    }

    @Test fun bothNewProfilesRecoverTheOriginalPasswordWrappedMdk() {
        for (profile in listOf(PasswordKdf.PBKDF2_600K, PasswordKdf.ARGON2ID)) Fixture().use { f ->
            f.enroll(profile)
            val before = f.prefs.all.toMap()
            val doc = JSONObject(f.envelope.readText())
            assertEquals(2, doc.getInt("version"))
            assertEquals(profile.encoded, doc.getString("kdf"))
            assertFalse(doc.has("rounds"))
            assertFalse(f.recovery.recover("wrong-synthetic-password"))
            assertNull(f.recovery.activeStore())
            assertTrue(f.recovery.recover(f.password))
            val restored = f.recovered()
            assertEquals(before["mdk_password_blob"], restored.getString("mdk_password_blob", null))
            assertEquals(profile.encoded, restored.getString(PasswordKdf.METADATA_KEY, null))
            assertEquals("retained-value", restored.getString("protocol-sentinel", null))
            val salt = unhex(restored.getString("mdk_password_salt", null)!!)
            val key = derive(f.password, salt, profile)
            try { assertArrayEquals(f.mdk, unseal(key, restored.getString("mdk_password_blob", null)!!,
                "monica-local-mdk:${profile.encoded}")) } finally { key.fill(0) }
            assertEquals(before, f.prefs.all)
        }
    }

    @Test fun genuineVersionOneRecoveryRetainsLegacyKdfAndOldApkProtectedValues() = Fixture().use { f ->
        f.writeLegacyEnvelope()
        assertTrue(f.prefs.edit().putString("legacy-added-setting", "preserved").commit())
        f.recovery.enroll(f.prefs, f.password, emptyMap<String, Any>(), expectedCredentials = f.prefs.all.toMap())
        assertEquals(1, JSONObject(f.envelope.readText()).getInt("version"))
        assertNotNull(f.prefs.getString(LocalVaultRecovery.REVISION, null))
        assertTrue(f.recovery.recover(f.password))
        assertFalse(f.recovered().contains(PasswordKdf.METADATA_KEY))
        assertEquals("preserved", f.recovered().getString("legacy-added-setting", null))
    }

    @Test fun revokedCommitLeavesBothCanonicalAndMirrorUntouched() = Fixture().use { f ->
        f.enroll()
        val before = f.prefs.all.toMap()
        val envelope = f.envelope.readBytes()
        val result = runCatching {
            f.recovery.enroll(f.prefs, f.password, f.credentials(PasswordKdf.ARGON2ID), before) {
                throw kotlinx.coroutines.CancellationException("Synthetic revoked credential request")
            }
        }
        assertTrue(result.isFailure)
        assertEquals(before, f.prefs.all)
        assertArrayEquals(envelope, f.envelope.readBytes())
    }

    @Test fun credentialCasIncludesKdfMetadata() = Fixture().use { f ->
        f.enroll()
        val before = f.prefs.all.toMap()
        val envelope = f.envelope.readBytes()
        val stale = before.toMutableMap().apply { remove(PasswordKdf.METADATA_KEY) }
        assertTrue(runCatching { f.recovery.enroll(f.prefs, f.password, emptyMap<String, Any>(), stale) }.isFailure)
        assertEquals(before, f.prefs.all)
        assertArrayEquals(envelope, f.envelope.readBytes())
    }

    @Test fun staleSettingsWriterRefreshesAndMergesWithoutLosingConcurrentValues() = Fixture().use { f ->
        f.enroll()
        val prepared = PreparedSecureStore.fresh(f.context)
        PreparedSecureStore.accept(f.context, prepared)
        val manager = SecurityManager.fromPrepared(f.context, prepared)
        assertTrue(manager.unlockVaultWithPassword(f.password))
        SessionManager.markUnlocked()
        val generation = SecurityManager.dataReadGeneration.value
        val oldRaw = f.raw.all.toMap()
        val staleMirror = f.recovery.wrap(f.prefs)
        assertTrue(f.recovery.wrap(f.prefs).edit().putString("other-process-value", "current").commit())
        f.writeDiskMap(SecurePreferencesStore.MONICA, f.raw.all)
        val editor = f.raw.edit().clear()
        oldRaw.forEach { (name, value) -> editor.putString(name, value as String) }
        assertTrue(editor.commit())
        // A settings revision invalidates reuse for new entries, not the existing grant.
        assertEquals("retained-value", manager.getProtectedString("protocol-sentinel"))
        assertNull(PreparedSecureStore.cached(f.context))
        assertEquals(generation, SecurityManager.dataReadGeneration.value)
        assertTrue(staleMirror.edit().putString("stale-process-value", "merged").commit())
        assertEquals("current", f.prefs.getString("other-process-value", null))
        assertEquals("merged", f.prefs.getString("stale-process-value", null))
        assertEquals(generation, SecurityManager.dataReadGeneration.value)
        assertTrue(SessionManager.isUnlocked.value)
    }

    @Test fun staleCredentialGenerationCannotWriteOrReenroll() = Fixture().use { f ->
        f.enroll()
        val old = f.prefs.all.toMap()
        val stale = object : SharedPreferences by f.prefs {
            override fun getAll(): MutableMap<String, *> = old.toMutableMap()
            override fun getString(key: String?, defValue: String?): String? = old[key] as? String ?: defValue
        }
        val staleMirror = f.recovery.wrap(stale)
        f.recovery.enroll(f.prefs, f.password, f.credentials(PasswordKdf.ARGON2ID), old)
        val current = f.prefs.all.toMap()
        val envelope = f.envelope.readBytes()
        assertFalse(staleMirror.edit().putString("stale-process-value", "must-not-write").commit())
        assertTrue(runCatching { f.recovery.enroll(stale, f.password, emptyMap<String, Any>(), old) }.isFailure)
        assertEquals(current, f.prefs.all)
        assertArrayEquals(envelope, f.envelope.readBytes())
    }

    @Test fun failedMirrorCommitKeepsCanonicalRecoverableAndBlocksOldMirror() = Fixture().use { f ->
        f.enroll()
        val old = f.prefs.all.toMap()
        val denied = object : SharedPreferences by f.prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = f.prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean = false
                }
            }
        }
        assertTrue(runCatching {
            f.recovery.enroll(denied, f.password, mapOf("after-interruption" to "recoverable"), old)
        }.isFailure)
        assertEquals(old, f.prefs.all)
        assertTrue(runCatching { f.recovery.validate(f.prefs) }.isFailure)
        assertTrue(f.recovery.recover(f.password))
        assertEquals("recoverable", f.recovered().getString("after-interruption", null))
    }

    @Test fun unknownEnvelopeVersionIsNotTreatedAsAbsentRecovery() = Fixture().use { f ->
        f.enroll()
        f.envelope.writeText(JSONObject(f.envelope.readText()).put("version", 99).toString())
        val before = f.prefs.all.toMap()
        val envelope = f.envelope.readBytes()
        assertFalse(f.recovery.available())
        assertTrue(runCatching { f.recovery.validate(f.prefs) }.isFailure)
        assertFalse(f.recovery.wrap(f.prefs).edit().putString("no-fallback", "rejected").commit())
        assertEquals(before, f.prefs.all)
        assertArrayEquals(envelope, f.envelope.readBytes())
    }

    @Test fun failedCommitWithPublishedMemoryStaysBlockedUntilDiskAndCanonicalAgree() = Fixture().use { f ->
        f.enroll()
        val prepared = PreparedSecureStore.fresh(f.context)
        val before = f.prefs.all.toMap()
        val beforeRaw = f.raw.all.toMap()
        val actualDisk = File(f.base.applicationInfo.dataDir,
            "shared_prefs/${f.prefix}${SecurePreferencesStore.MONICA}.xml")
        val oldDiskBytes = actualDisk.readBytes()
        f.writeDiskMap(SecurePreferencesStore.MONICA, beforeRaw)
        val memoryOnly = object : SharedPreferences by f.prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = f.prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        // Publish through real SharedPreferences, then restore only the disk
                        // snapshot. The live mMap deliberately retains the attempted new values.
                        check(editor.commit())
                        actualDisk.writeBytes(oldDiskBytes)
                        return false
                    }
                }
            }
        }
        assertTrue(runCatching { f.recovery.enroll(memoryOnly, f.password,
            mapOf("memory-before-disk-failure" to "recoverable"), before) }.isFailure)
        assertEquals("recoverable", f.prefs.getString("memory-before-disk-failure", null))
        assertNotEquals(before[LocalVaultRecovery.REVISION], f.prefs.getString(LocalVaultRecovery.REVISION, null))
        assertTrue(runCatching { prepared.preferences.getString("protocol-sentinel", null) }.isFailure)
        assertTrue(PreparedSecureStore.isMirrorPending(f.context.applicationInfo.dataDir))
        assertTrue(runCatching { PreparedSecureStore.fresh(f.context) }.isFailure)
        assertEquals(beforeRaw, f.raw.all)
        assertTrue(PreparedSecureStore.isMirrorPending(f.context.applicationInfo.dataDir))
        assertTrue(f.recovery.recover(f.password))
        val name = f.recovery.activeStore()!!.first
        f.writeDiskMap(name, f.context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
        val recovered = PreparedSecureStore.fresh(f.context)
        assertFalse(PreparedSecureStore.isMirrorPending(f.context.applicationInfo.dataDir))
        assertEquals("recoverable", recovered.preferences.getString("memory-before-disk-failure", null))
    }

    @Test fun readDuringOwnCanonicalCommitDoesNotRevokeTheExistingSession() = Fixture().use { f ->
        f.enroll()
        val prepared = PreparedSecureStore.fresh(f.context)
        val manager = SecurityManager.fromPrepared(f.context, prepared)
        assertTrue(manager.unlockVaultWithPassword(f.password))
        SessionManager.markUnlocked()
        val runtimeGeneration = SecurityManager.dataReadGeneration.value
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val blocking = object : SharedPreferences by f.prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = f.prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        return editor.commit()
                    }
                }
            }
        }
        val before = f.prefs.all.toMap()
        val future = executor.submit<Boolean> {
            f.recovery.enroll(blocking, f.password, mapOf("in-flight-value" to "committed"), before)
            true
        }
        try {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            repeat(3) { assertEquals("retained-value", manager.getProtectedString("protocol-sentinel")) }
            assertEquals(runtimeGeneration, SecurityManager.dataReadGeneration.value)
            assertTrue(SecurityManager.hasRuntimeUnlockCache())
            assertTrue(SessionManager.isUnlocked.value)
            release.countDown()
            assertTrue(future.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("committed", manager.getProtectedString("in-flight-value"))
            assertEquals(runtimeGeneration, SecurityManager.dataReadGeneration.value)
        } finally {
            release.countDown()
            executor.shutdownNow()
            SessionManager.markLocked()
        }
    }

    @Test fun freshPreparationReloadsExactEncryptedDiskValuesAfterStaleCache() = Fixture().use { f ->
        f.enroll()
        val oldRaw = f.raw.all.toMap()
        assertTrue(f.recovery.wrap(f.prefs).edit().putString("fresh-process-value", "current").commit())
        val currentRaw = f.raw.all.toMap()
        // The ContextWrapper maps SharedPreferences to a unique fixture name; write the
        // independent disk view at its declared dataDir to model another live process.
        f.writeDiskMap(SecurePreferencesStore.MONICA, currentRaw)
        val editor = f.raw.edit().clear()
        oldRaw.forEach { (name, value) -> editor.putString(name, value as String) }
        assertTrue(editor.commit())
        assertNotEquals(currentRaw, f.raw.all)
        val fresh = PreparedSecureStore.fresh(f.context)
        assertEquals(currentRaw, f.raw.all)
        assertEquals("current", fresh.preferences.getString("fresh-process-value", null))
    }

    companion object {
        private fun random(size: Int) = ByteArray(size).also(SecureRandom()::nextBytes)
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun unhex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun derive(password: String, salt: ByteArray, profile: PasswordKdf.Profile): ByteArray {
            if (profile.algorithm == LocalPasswordAlgorithm.ARGON2ID) return PasswordKdf.derive(password, salt, profile)
            val spec = PBEKeySpec(password.toCharArray(), salt, if (profile.encoded == null) 100_000 else 600_000, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        }
        private fun seal(key: ByteArray, bytes: ByteArray, aad: String?): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            aad?.let { cipher.updateAAD(it.toByteArray(Charsets.UTF_8)) }
            return encode(cipher.iv + cipher.doFinal(bytes))
        }
        private fun unseal(key: ByteArray, value: String, aad: String?): ByteArray {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            aad?.let { cipher.updateAAD(it.toByteArray(Charsets.UTF_8)) }
            return cipher.doFinal(bytes, 12, bytes.size - 12)
        }
    }
}
