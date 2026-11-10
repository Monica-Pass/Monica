package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Real C2/MDK cryptography, isolated from every production preference and key alias. */
class KeystoreDataFailureInstrumentedTest {
    @Test
    fun passwordAttemptPublishesOnlyAfterAcceptedCommit() {
        Fixture().use { fixture ->
            val password = "Synthetic-deferred-password-325!"
            val original = fixture.savePasswordCiphertext(password)
            SecurityManager.clearRuntimeUnlockCache()
            val attempt = SecurityManager.PasswordVerificationAttempt()
            assertTrue(fixture.manager.unlockVaultWithPassword(password, attempt))
            assertFalse("Background verification must not publish the MDK", SecurityManager.hasRuntimeUnlockCache())
            assertTrue(attempt.commit())
            assertTrue(SecurityManager.hasRuntimeUnlockCache())
            assertFalse(attempt.commit())
            attempt.cancel()
            assertTrue("Disposing an accepted attempt must preserve its published key", SecurityManager.hasRuntimeUnlockCache())
            assertEquals(fixture.originalValue, fixture.manager.decryptData(original))
        }
    }

    @Test
    fun cancelledStagedPasswordAttemptCannotEraseNewAuthentication() {
        Fixture().use { fixture ->
            val password = "Synthetic-cancel-password-325!"
            val original = fixture.savePasswordCiphertext(password)
            SecurityManager.clearRuntimeUnlockCache()
            val attempt = SecurityManager.PasswordVerificationAttempt()
            assertTrue(fixture.manager.unlockVaultWithPassword(password, attempt))
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            attempt.cancel()
            assertFalse(attempt.commit())
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            assertTrue(fixture.manager.unlockVaultWithPassword(password))
            attempt.cancel()
            assertFalse(attempt.commit())
            assertTrue(SecurityManager.hasRuntimeUnlockCache())
            assertEquals(fixture.originalValue, fixture.manager.decryptData(original))
        }
    }

    @Test
    fun lockRevokesStagedPasswordAndEmptyAttemptsCannotAuthorize() {
        Fixture().use { fixture ->
            val empty = SecurityManager.PasswordVerificationAttempt()
            assertFalse(empty.commit())
            empty.cancel()
            val password = "Synthetic-lock-password-325!"
            fixture.savePasswordCiphertext(password)
            SecurityManager.clearRuntimeUnlockCache()
            val attempt = SecurityManager.PasswordVerificationAttempt()
            assertTrue(fixture.manager.unlockVaultWithPassword(password, attempt))
            SecurityManager.clearRuntimeUnlockCache()
            assertFalse(attempt.commit())
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            val incorrect = SecurityManager.PasswordVerificationAttempt()
            assertFalse(fixture.manager.unlockVaultWithPassword("wrong", incorrect))
            assertFalse(incorrect.commit())
            incorrect.cancel()
        }
    }

    @Test
    fun deletedC2AliasRejectsOriginalCiphertextWithoutCreatingKeyOrChangingFiles() {
        Fixture().use { fixture ->
            val original = fixture.saveOriginalCiphertext()
            val before = fixture.snapshot()

            fixture.keystore.deleteEntry(fixture.dataAlias)
            SecurityManager.clearRuntimeUnlockCache()
            val failure = readFailure(fixture, original)

            assertTrue("Missing C2 key must be reported as unavailable", failure is UnrecoverableKeyException)
            assertFalse("Reading C2 data must not recreate its missing alias", fixture.keystore.containsAlias(fixture.dataAlias))
            fixture.assertUnchanged(before)
            assertSafeDiagnostic(failure, fixture, original)
        }
    }

    @Test
    fun replacedC2AliasRejectsOriginalCiphertextWithoutChangingFilesOrReplacementKey() {
        Fixture().use { fixture ->
            val original = fixture.saveOriginalCiphertext()
            val before = fixture.snapshot()

            // Generating the same uniquely owned alias replaces the original real Keystore key.
            val replacement = fixture.generateDataKey()
            val probe = encryptC2(replacement, fixture.probeValue)
            assertTrue("Replacement key must be usable before the failing read",
                decryptC2(fixture.existingDataKey(), probe) == fixture.probeValue)
            SecurityManager.clearRuntimeUnlockCache()
            val failure = readFailure(fixture, original)

            assertTrue("A different real C2 key must fail GCM authentication",
                generateSequence(failure) { it.cause }.take(8).any { it is AEADBadTagException })
            assertTrue("The replacement alias must remain present", fixture.keystore.containsAlias(fixture.dataAlias))
            SecurityManager.clearRuntimeUnlockCache()
            assertTrue("Re-read the alias to prove the failed read retained the replacement key",
                decryptC2(fixture.existingDataKey(), probe) == fixture.probeValue)
            fixture.assertUnchanged(before)
            assertSafeDiagnostic(failure, fixture, original)
        }
    }

    @Test
    fun lockAfterReadingMdkWrapperRejectsLateDecryptWithoutRestoringRuntimeKey() {
        Fixture().use { fixture ->
            val password = "Synthetic-MDK-late-read-325!"
            val original = fixture.savePasswordCiphertext(password)
            val before = fixture.snapshot()
            SecurityManager.clearRuntimeUnlockCache()
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            assertTrue("The real Keystore MDK wrapper must work without the interleaved lock",
                fixture.manager.decryptData(original) == fixture.originalValue)
            assertTrue(SecurityManager.hasRuntimeUnlockCache())
            SecurityManager.clearRuntimeUnlockCache()

            val field = SecurityManager::class.java.getDeclaredField("sharedPreferences").apply { isAccessible = true }
            val preferences = field.get(fixture.manager) as SharedPreferences
            var armed = true
            val interleaved = object : SharedPreferences by preferences {
                override fun getString(key: String?, defValue: String?): String? {
                    val value = preferences.getString(key, defValue)
                    if (armed && key == "mdk_keystore_blob") {
                        check(value?.startsWith("CP|") == true)
                        armed = false
                        // Deterministic lock after capturing the old wrapper. The real Keystore
                        // unwrap still executes afterwards; this does not simulate native latency.
                        SessionManager.markLocked()
                    }
                    return value
                }
            }
            field.set(fixture.manager, interleaved)
            try {
                val result = runCatching { fixture.manager.decryptData(original) }
                assertFalse("The one-shot lock must occur during the MDK-wrapper read", armed)
                assertTrue("A data read crossing a lock must throw instead of returning plaintext or an empty value", result.isFailure)
                assertFalse("A late real Keystore unwrap must not republish the revoked runtime MDK", SecurityManager.hasRuntimeUnlockCache())
                fixture.assertUnchanged(before)
                assertTrue("The original MDK wrapper key must remain present", fixture.keystore.containsAlias(fixture.dataAlias))
                assertSafeDiagnostic(checkNotNull(result.exceptionOrNull()), fixture, original)
            } finally {
                field.set(fixture.manager, preferences)
            }

            assertTrue("The original password must still unlock the preserved vault", fixture.manager.unlockVaultWithPassword(password))
            assertTrue("The preserved original MDK ciphertext must remain readable after authentication",
                fixture.manager.decryptData(original) == fixture.originalValue)
        }
    }

    @Test
    fun lockAfterReadingPasswordMdkRejectsLateUnlockWithoutRestoringRuntimeKey() {
        Fixture().use { fixture ->
            val password = "Synthetic-password-MDK-late-325!"
            val original = fixture.savePasswordCiphertext(password)
            SessionManager.markLocked()
            assertTrue("The original password must authenticate before fault injection",
                fixture.manager.unlockVaultWithPassword(password))
            assertTrue("The password-derived MDK must read the original ciphertext",
                fixture.manager.decryptData(original) == fixture.originalValue)
            SessionManager.markLocked()
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            val before = fixture.snapshot()

            val field = SecurityManager::class.java.getDeclaredField("sharedPreferences").apply { isAccessible = true }
            val preferences = field.get(fixture.manager) as SharedPreferences
            var armed = true
            val interleaved = object : SharedPreferences by preferences {
                override fun getAll(): MutableMap<String, *> {
                    val snapshot = preferences.all
                    return object : MutableMap<String, Any?> by snapshot.toMutableMap() {
                        override fun get(key: String): Any? {
                            val value = snapshot[key]
                            if (armed && key == "mdk_password_blob" && Thread.currentThread().stackTrace.any {
                                it.className == LocalPasswordCredentials::class.java.name && it.methodName == "unwrap"
                            }) {
                                check(value is String && value.isNotEmpty())
                                armed = false
                                // Interleave at the real credential wrapper read, after its KDF.
                                // Fingerprint/validation reads of the same key do not arm the lock.
                                SessionManager.markLocked()
                            }
                            return value
                        }
                    }
                }
            }
            field.set(fixture.manager, interleaved)
            try {
                val unlocked = fixture.manager.unlockVaultWithPassword(password)
                assertFalse("The one-shot lock must occur after the password-wrapped MDK is read", armed)
                assertFalse("Password authentication crossing a lock must report failure", unlocked)
                assertFalse("A late password operation must not republish the revoked MDK", SecurityManager.hasRuntimeUnlockCache())
                assertFalse("A late password operation must leave the session locked", SessionManager.isUnlocked.value)
                fixture.assertUnchanged(before)
            } finally {
                field.set(fixture.manager, preferences)
            }

            assertTrue("The rejected late result must preserve original-password authentication",
                fixture.manager.unlockVaultWithPassword(password))
            assertTrue("The original ciphertext must remain readable after a fresh authentication",
                fixture.manager.decryptData(original) == fixture.originalValue)
        }
    }

    private fun readFailure(fixture: Fixture, original: String): Throwable {
        val result = runCatching { fixture.manager.decryptData(original) }
        assertTrue("Unreadable C2 data must throw, including instead of returning an empty value", result.isFailure)
        return checkNotNull(result.exceptionOrNull())
    }

    private fun assertSafeDiagnostic(failure: Throwable, fixture: Fixture, original: String) {
        val diagnostic = SafeExceptionDiagnostic.lines(failure).joinToString("\n")
        assertTrue("Failure diagnostics must retain the exception type", diagnostic.contains(failure.javaClass.name))
        assertFalse(diagnostic.contains(fixture.originalValue))
        assertFalse(diagnostic.contains(fixture.probeValue))
        assertFalse(diagnostic.contains(original))
        assertFalse(diagnostic.contains(fixture.dataAlias))
        // Preserve actual provider type/code evidence without printing exception messages or data.
        Log.i("KeystoreDataFailureTest", diagnostic)
    }

    internal class Snapshot(
        val ciphertext: ByteArray,
        val selector: ByteArray,
        val preferenceFiles: Map<String, ByteArray>,
        val rawPreferences: Map<String, *>,
    )

    internal class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val id = UUID.randomUUID().toString()
        private val prefix = "keystore-data-failure-$id-"
        private val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        private val names = mutableSetOf<String>()
        private val storeName = "monica_recovered_$id"
        private val masterAlias = "monica_recovered_key_$id"
        val dataAlias = prefix + "C2"
        private val authenticatedAlias = prefix + "V2"
        private val selector = File(root, "files/local-vault-recovery/active-store.json").apply {
            check(parentFile!!.mkdirs())
            writeText(JSONObject().put("id", id).toString(), Charsets.UTF_8)
        }
        private val ciphertextFile = File(root, "files/original-ciphertext.txt")
        private val preferenceDirectory = File(base.applicationInfo.dataDir, "shared_prefs")
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = ApplicationInfo(base.applicationInfo).apply {
                dataDir = root.absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names.add(name)
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val manager: SecurityManager
        val originalValue = "synthetic-c2-original-$id"
        val probeValue = "synthetic-c2-replacement-probe-$id"
        private val raw get() = context.getSharedPreferences(storeName, Context.MODE_PRIVATE)

        init {
            SecurityManager.clearRuntimeUnlockCache()
            check(LocalVaultRecovery(context).activeStore() == (storeName to masterAlias))
            manager = SecurityManager(context)
            // The public constructor opens only the fixture's selected encrypted preference store.
            // Map both data fields defensively; no default data alias is used by this fixture.
            for ((field, alias) in mapOf("KEY_ALIAS_DATA_COMPAT" to dataAlias, "KEY_ALIAS_DATA" to authenticatedAlias)) {
                val declared = SecurityManager::class.java.getDeclaredField(field).apply { isAccessible = true }
                declared.set(manager, alias)
                check(declared.get(manager) == alias)
            }
            val preferences = SecurePreferencesStore.open(context, SecurePreferencesStore.MONICA).preferences
            check(preferences.edit().putString("fixture-preserved-preference", "synthetic-setting-$id").commit())
            check(keystore.containsAlias(masterAlias))
            check(!keystore.containsAlias(dataAlias))
            check(!keystore.containsAlias(authenticatedAlias))
        }

        fun saveOriginalCiphertext(): String {
            val original = encryptC2(generateDataKey(), originalValue)
            check(original.startsWith("C2|"))
            assertTrue("The unchanged original key must decrypt the fixture before fault injection",
                manager.decryptData(original) == originalValue)
            ciphertextFile.writeText(original, Charsets.UTF_8)
            assertTrue("The persisted ciphertext must match the real C2 fixture",
                ciphertextFile.readText(Charsets.UTF_8) == original)
            return original
        }

        fun savePasswordCiphertext(password: String): String {
            assertTrue("The isolated fixture must enroll its original password", manager.setMasterPassword(password))
            val original = manager.encryptData(originalValue)
            assertTrue("The fixture must exercise the real MDK format", original.startsWith("MDK|"))
            assertTrue("The enrolled vault must read the original ciphertext before locking",
                manager.decryptData(original) == originalValue)
            ciphertextFile.writeText(original, Charsets.UTF_8)
            assertTrue("The persisted ciphertext must match the real MDK fixture",
                ciphertextFile.readText(Charsets.UTF_8) == original)
            return original
        }

        fun generateDataKey(): SecretKey = KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(dataAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build())
        }.generateKey()

        fun existingDataKey(): SecretKey = keystore.getKey(dataAlias, null) as SecretKey

        private fun preferenceFiles(): Map<String, ByteArray> = preferenceDirectory.listFiles()
            .orEmpty().filter { it.isFile && it.name.startsWith(prefix) }
            .associate { it.name to it.readBytes() }

        fun snapshot(): Snapshot {
            // A synchronous commit also drains any earlier apply for this actual preference file.
            check(raw.edit().commit())
            val files = preferenceFiles()
            assertTrue("Snapshot must include the actual encrypted SharedPreferences XML",
                files.containsKey("$prefix$storeName.xml"))
            return Snapshot(ciphertextFile.readBytes(), selector.readBytes(), files, raw.all.toMap())
        }

        fun assertUnchanged(before: Snapshot) {
            check(raw.edit().commit())
            assertArrayEquals("Original ciphertext file must remain byte-for-byte unchanged", before.ciphertext, ciphertextFile.readBytes())
            assertArrayEquals("Recovery selector must remain byte-for-byte unchanged", before.selector, selector.readBytes())
            assertFalse("Read failure must not stage a new recovery selector", File(selector.path + ".new").exists())
            assertFalse("Read failure must not create a selector backup", File(selector.path + ".bak").exists())
            val after = preferenceFiles()
            assertEquals("Read failure must not create or remove preference files", before.preferenceFiles.keys, after.keys)
            before.preferenceFiles.forEach { (name, bytes) ->
                assertArrayEquals("Encrypted preference XML must remain byte-for-byte unchanged", bytes, after.getValue(name))
            }
            assertEquals("Read failure must preserve the raw encrypted preferences", before.rawPreferences, raw.all)
            assertTrue("The fixture's storage master key must remain present", keystore.containsAlias(masterAlias))
            assertFalse("A read failure must not generate another data-key alias", keystore.containsAlias(authenticatedAlias))
        }

        override fun close() {
            SecurityManager.clearRuntimeUnlockCache()
            for (alias in listOf(dataAlias, authenticatedAlias, masterAlias)) {
                if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
            }
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile && root.name == prefix)
            check(root.deleteRecursively())
        }
    }

    companion object {
        private fun encryptC2(key: SecretKey, value: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return "C2|" + Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }

        private fun decryptC2(key: SecretKey, value: String): String {
            check(value.startsWith("C2|"))
            val combined = Base64.decode(value.removePrefix("C2|"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, combined.copyOfRange(0, 12)))
            }
            return cipher.doFinal(combined.copyOfRange(12, combined.size)).toString(Charsets.UTF_8)
        }
    }
}
