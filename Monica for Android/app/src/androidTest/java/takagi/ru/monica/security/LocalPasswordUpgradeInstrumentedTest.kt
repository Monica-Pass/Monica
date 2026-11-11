package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.rustcore.RustBitwardenKdfCore

/** Isolated synthetic legacy credentials, seeded independently of all new enrollment/KDF helpers. */
class LocalPasswordUpgradeInstrumentedTest {
    private class Fixture(profile: PasswordKdf.Profile = PasswordKdf.LEGACY) : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "local-kdf-upgrade-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        private val names = linkedSetOf<String>()
        private val masterAlias = prefix + "master"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply { dataDir = root.absolutePath }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        val recovery = LocalVaultRecovery(context)
        val password = "  Synthetic 旧密码🔑e\u0301  "
        val mdk = ByteArray(32) { (it + 0x41).toByte() }
        val recordPlain = "Synthetic existing record\u0000密码/OTP"
        val record = File(root, "record.ciphertext")
        val originalStore = SecurePreferencesStore.openRaw(context, SecurePreferencesStore.MONICA, masterAlias)
        val originalPlain = originalStore.preferences
        val envelope get() = File(root, "files/local-vault-recovery/recovery.json")

        init {
            SecurityManager.clearRuntimeUnlockCache()
            val hashSalt = ByteArray(16) { (it + 1).toByte() }
            val wrapSalt = ByteArray(32) { (it + 0x21).toByte() }
            val hash = legacyDerive(password, hashSalt)
            val wrappingKey = legacyDerive(password, wrapSalt)
            try {
                val editor = originalPlain.edit()
                    .putString("master_password_hash", hex(hash)).putString("master_password_salt", hex(hashSalt))
                    .putString("mdk_password_blob", seal(wrappingKey, mdk, ByteArray(12) { (it + 0x61).toByte() }))
                    .putString("mdk_password_salt", hex(wrapSalt)).putBoolean("mdk_ready", true)
                check(editor.commit())
            } finally { hash.fill(0); wrappingKey.fill(0) }
            record.writeText("MDK|" + seal(mdk, recordPlain.toByteArray(Charsets.UTF_8), ByteArray(12) { (it + 0x71).toByte() }))
            if (profile != PasswordKdf.LEGACY) {
                val editor = originalPlain.edit()
                LocalPasswordCredentials.create(password, mdk, profile).forEach { (key, value) ->
                    editor.putString(key, value as String)
                }
                check(editor.commit())
            }
            check(PasswordKdf.fromValues(originalPlain.all) == profile)
            check(!envelope.exists())
        }

        fun manager(): SecurityManager {
            val active = recovery.activeStore()
            val opened = SecurePreferencesStore.openRaw(context, active?.first ?: SecurePreferencesStore.MONICA,
                active?.second ?: masterAlias)
            recovery.validate(opened.preferences)
            return SecurityManager.fromPrepared(context, opened.copy(preferences = recovery.wrap(opened.preferences))).also { manager ->
                for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT")) {
                    val declared = SecurityManager::class.java.getDeclaredField(field).apply { isAccessible = true }
                    declared.set(manager, prefix + field)
                    assertEquals(prefix + field, declared.get(manager))
                }
            }
        }

        fun assertRecordAndMdk(manager: SecurityManager, password: String = this.password) {
            assertEquals(recordPlain, manager.decryptData(record.readText()))
            val active = recovery.activeStore()
            val values = SecurePreferencesStore.openRaw(context, active?.first ?: SecurePreferencesStore.MONICA,
                active?.second ?: masterAlias).preferences.all
            val opened = LocalPasswordCredentials.unwrap(password, values)
            try { assertArrayEquals(mdk, opened) } finally { opened.fill(0) }
        }

        /** Hash actual encrypted XML, the fixed ciphertext and recovery files, not only decoded values. */
        fun bytesSnapshot(): Map<String, String> {
            val files = root.walkTopDown().filter { it.isFile && it.name != "recovery.lock" }
                .associate { "root/" + it.relativeTo(root).invariantSeparatorsPath to digest(it.readBytes()) }.toMutableMap()
            val prefs = File(base.applicationInfo.dataDir, "shared_prefs")
            prefs.listFiles().orEmpty().filter { it.name.startsWith(prefix) }.forEach {
                files["prefs/" + it.name] = digest(it.readBytes())
            }
            return files
        }

        override fun close() {
            SecurityManager.clearRuntimeUnlockCache()
            mdk.fill(0)
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val aliases = listOf(masterAlias, prefix + "KEY_ALIAS_DATA", prefix + "KEY_ALIAS_DATA_COMPAT") +
                names.filter { it.startsWith("monica_recovered_") }.map { it.replace("monica_recovered_", "monica_recovered_key_") }
            aliases.forEach { if (keystore.containsAlias(it)) keystore.deleteEntry(it) }
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.parentFile == base.cacheDir && root.name.startsWith("local-kdf-upgrade-"))
            root.deleteRecursively()
        }

        private fun legacyDerive(password: String, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        }
        private fun seal(key: ByteArray, plain: ByteArray, nonce: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            return Base64.encodeToString(nonce + cipher.doFinal(plain), Base64.NO_WRAP)
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }


    @Test fun missingVerifierWithRemainingCredentialMaterialNeverBecomesAnUnprotectedVault() {
        for (upgradeFirst in listOf(false, true)) Fixture().use { f ->
            val manager = f.manager()
            if (upgradeFirst) assertTrue(manager.unlockVaultWithPassword(f.password))
            SecurityManager.clearRuntimeUnlockCache()
            assertTrue(f.originalPlain.edit().remove(LocalPasswordCredentials.HASH).commit())
            val before = f.bytesSnapshot()
            assertTrue("A missing verifier is damaged protection, not first-time setup", manager.isMasterPasswordSet())
            assertFalse(manager.unlockVaultWithPassword(f.password))
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            assertEquals(before, f.bytesSnapshot())
        }
    }

    @Test fun malformedUtf16KeepsHistoricalJceOutcomeForBothPbkdf2Costs() {
        val salt = ByteArray(16) { it.toByte() }
        val cases = listOf("\uD800", "\uDC00", "prefix\uD800x")
        for ((index, password) in cases.withIndex()) {
            for (profile in listOf(PasswordKdf.LEGACY, PasswordKdf.PBKDF2_600K)) {
                val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, profile.iterations, 256)
                val expected = try {
                    runCatching { javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).encoded }
                } finally { spec.clearPassword() }
                val actual = runCatching { PasswordKdf.derive(password, salt, profile) }
                val label = "UTF-16 case $index at ${profile.iterations} iterations"
                try {
                    assertEquals(label, expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
                    if (expected.isSuccess) assertArrayEquals(label, expected.getOrThrow(), actual.getOrThrow())
                } finally { expected.getOrNull()?.fill(0); actual.getOrNull()?.fill(0) }
            }
        }
    }

    @Test fun legacyLoginAutomaticallyUpgradesAndFreshManagerReadsTheSameMdkAndCiphertext() {
        Fixture().use { f ->
            val cipherBefore = f.record.readBytes()
            assertNull(f.originalPlain.getString(PasswordKdf.METADATA_KEY, null))
            val manager = f.manager()
            assertTrue(manager.unlockVaultWithPassword(f.password))
            assertEquals(PasswordKdf.PBKDF2_600K.encoded, f.originalPlain.getString(PasswordKdf.METADATA_KEY, null))
            f.assertRecordAndMdk(manager)
            assertArrayEquals(cipherBefore, f.record.readBytes())
            SecurityManager.clearRuntimeUnlockCache()
            val fresh = f.manager()
            assertTrue(fresh.unlockVaultWithPassword(f.password))
            f.assertRecordAndMdk(fresh)
            assertArrayEquals(cipherBefore, f.record.readBytes())
        }
    }

    @Test fun wrongLegacyPasswordChangesNoEncryptedFileBytes() {
        Fixture().use { f ->
            val manager = f.manager()
            val before = f.bytesSnapshot()
            assertFalse(manager.unlockVaultWithPassword(f.password.trim()))
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            assertEquals(before, f.bytesSnapshot())
            assertNull(f.originalPlain.getString(PasswordKdf.METADATA_KEY, null))
        }
    }

    @Test fun brokenPasswordWrapperIsNotRepairedFromAWarmRuntimeKey() {
        Fixture().use { f ->
            val manager = f.manager()
            assertTrue(manager.unlockVaultWithPassword(f.password))
            assertTrue(SecurityManager.hasRuntimeUnlockCache())
            val blob = Base64.decode(f.originalPlain.getString("mdk_password_blob", null), Base64.NO_WRAP)
            blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
            assertTrue(f.originalPlain.edit().putString("mdk_password_blob", Base64.encodeToString(blob, Base64.NO_WRAP)).commit())
            val before = f.bytesSnapshot()
            assertFalse(manager.verifyMasterPassword(f.password))
            assertFalse(manager.resetMasterPassword(f.password, f.password,
                SecurityManager.LocalPasswordChangeAttempt()))
            assertEquals(before, f.bytesSnapshot())
        }
    }

    @Test fun unknownStoredProfileCannotFallBackToLegacyOrWriteReplacement() {
        Fixture().use { f ->
            assertTrue(f.originalPlain.edit().putString(PasswordKdf.METADATA_KEY, "1|argon2id|2147483647|3|1").commit())
            val manager = f.manager()
            val before = f.bytesSnapshot()
            assertFalse(manager.unlockVaultWithPassword(f.password))
            assertFalse(manager.resetMasterPassword(f.password, f.password,
                SecurityManager.LocalPasswordChangeAttempt()))
            assertEquals(before, f.bytesSnapshot())
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
        }
    }

    @Test fun historicalProfilesMigrateTo600kAndResetRetainsMdkButRevokesOldPassword() {
        for (profile in listOf(PasswordKdf.LEGACY, PasswordKdf.ARGON2ID)) Fixture(profile).use { f ->
            val cipherBefore = f.record.readBytes()
            var manager = f.manager()
            assertTrue(manager.unlockVaultWithPassword(f.password))
            assertEquals(PasswordKdf.PBKDF2_600K, PasswordKdf.fromValues(f.originalPlain.all))
            f.assertRecordAndMdk(manager)
            SecurityManager.clearRuntimeUnlockCache()
            manager = f.manager()
            assertTrue(manager.unlockVaultWithPassword(f.password))
            f.assertRecordAndMdk(manager)
            assertArrayEquals(cipherBefore, f.record.readBytes())
            val newPassword = "New synthetic 主密码🔒 with spaces "
            assertTrue(manager.resetMasterPassword(f.password, newPassword))
            val afterReset = f.bytesSnapshot()
            assertFalse(manager.unlockVaultWithPassword(f.password))
            assertFalse(f.recovery.recover(f.password))
            assertEquals(afterReset, f.bytesSnapshot())
            SecurityManager.clearRuntimeUnlockCache()
            assertTrue(f.manager().unlockVaultWithPassword(newPassword))
            assertTrue(f.recovery.recover(newPassword))
            val recovered = f.manager()
            assertFalse(recovered.unlockVaultWithPassword(f.password))
            assertTrue(recovered.unlockVaultWithPassword(newPassword))
            f.assertRecordAndMdk(recovered, newPassword)
            assertArrayEquals(cipherBefore, f.record.readBytes())
        }
    }

    @Test fun argonWrongPasswordCancellationAndLockKeepOriginalBytes() {
        for (mode in listOf("wrong", "cancel", "lock")) Fixture(PasswordKdf.ARGON2ID).use { f ->
            val manager = f.manager()
            val before = f.bytesSnapshot()
            val attempt = SecurityManager.PasswordVerificationAttempt()
            var checks = 0
            val accepted = manager.unlockVaultWithPassword(if (mode == "wrong") "wrong" else f.password, attempt) {
                if (++checks == 2) {
                    if (mode == "cancel") attempt.cancel()
                    if (mode == "lock") SecurityManager.clearRuntimeUnlockCache()
                }
            }
            assertFalse(mode, accepted)
            assertFalse(attempt.commit())
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            assertEquals(mode, before, f.bytesSnapshot())
        }
    }

    @Test fun argonPasswordResetAndAuthorizedResetAlwaysWrite600k() {
        for (authorized in listOf(false, true)) Fixture(PasswordKdf.ARGON2ID).use { f ->
            val manager = f.manager()
            if (authorized) {
                // Independent old Argon credentials and COMPAT material do not authorize reset.
                val seedWrapper = SecurityManager::class.java.getDeclaredMethod(
                    "persistCompatKeystoreWrappedMdk", ByteArray::class.java, Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                assertTrue(seedWrapper.invoke(manager, f.mdk, true) as Boolean)
                fun legacyAnswer(value: String) = PasswordKdf.hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
                assertTrue(manager.restoreSecurityQuestionsFromBackup(SecurityQuestionsBackupSnapshot(
                    1, null, legacyAnswer("first answer"), 2, null, legacyAnswer("second answer"),
                )))
                assertEquals(PasswordKdf.ARGON2ID, PasswordKdf.fromValues(f.originalPlain.all))
                assertFalse(manager.setMasterPassword("Replacement synthetic password"))
                val challenge = checkNotNull(manager.beginRecoveryWithAnswers("first answer", "second answer") {})
                val permit = checkNotNull(manager.completeRecoveryDeviceCredential(challenge))
                assertTrue(manager.recoverMasterPassword("Replacement synthetic password", permit))
            } else assertTrue(manager.resetMasterPassword(f.password, "Replacement synthetic password"))
            assertEquals(PasswordKdf.PBKDF2_600K, PasswordKdf.fromValues(f.originalPlain.all))
            assertTrue(manager.unlockVaultWithPassword("Replacement synthetic password"))
            f.assertRecordAndMdk(manager, "Replacement synthetic password")
        }
    }

    @Test fun current600kLoginDoesNotRewrapPasswordCredentials() {
        Fixture(PasswordKdf.PBKDF2_600K).use { f ->
            val manager = f.manager()
            val before = LocalPasswordCredentials.snapshot(f.originalPlain.all)
            assertTrue(manager.unlockVaultWithPassword(f.password))
            SecurityManager.clearRuntimeUnlockCache()
            assertTrue(f.manager().unlockVaultWithPassword(f.password))
            assertTrue(LocalPasswordCredentials.matches(f.originalPlain.all, before))
            f.assertRecordAndMdk(f.manager())
        }
    }

    @Test fun cancelledChangeAndCancelledPasswordAttemptCannotWriteOrUnlock() {
        Fixture().use { f ->
            val manager = f.manager()
            val before = f.bytesSnapshot()
            val change = SecurityManager.LocalPasswordChangeAttempt().apply { cancel() }
            assertFalse(manager.resetMasterPassword(f.password, f.password, change))
            val verification = SecurityManager.PasswordVerificationAttempt().apply { cancel() }
            assertFalse(manager.unlockVaultWithPassword(f.password, verification))
            assertFalse(verification.commit())
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            assertEquals(before, f.bytesSnapshot())
        }
    }

    @Test fun activityCancellationAfterAuthenticationKeepsAllOriginalBytes() {
        Fixture().use { f ->
            val manager = f.manager()
            val before = f.bytesSnapshot()
            var calls = 0
            assertFalse(manager.resetMasterPassword(f.password, f.password,
                SecurityManager.LocalPasswordChangeAttempt()) {
                calls++
                if (calls == 2) throw CancellationException("Synthetic caller cancelled after password authentication")
            })
            assertEquals(2, calls)
            assertEquals(before, f.bytesSnapshot())
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
        }
    }

    @Test fun lockAtTheFinalCommitGatePreventsCredentialAndRecoveryWrites() {
        Fixture().use { f ->
            val manager = f.manager()
            val before = f.bytesSnapshot()
            var injected = false
            assertFalse(manager.resetMasterPassword(f.password, f.password,
                SecurityManager.LocalPasswordChangeAttempt()) {
                // Inject inside enrollment's final gate, independently of extra caller checks.
                if (!injected && Thread.currentThread().stackTrace.any {
                    it.className.startsWith(LocalVaultRecovery::class.java.name) && it.methodName.startsWith("enroll")
                }) {
                    injected = true
                    SecurityManager.clearRuntimeUnlockCache()
                    SessionManager.markLocked()
                }
            })
            assertTrue("The injection must reach the final publication gate", injected)
            assertEquals(before, f.bytesSnapshot())
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
        }
    }

    @Test fun stagedPasswordAttemptsCannotCommitAcrossAlgorithmOrPasswordReplacement() {
        Fixture().use { f ->
            val manager = f.manager()
            assertTrue(manager.unlockVaultWithPassword(f.password))
            SecurityManager.clearRuntimeUnlockCache()
            val first = SecurityManager.PasswordVerificationAttempt()
            assertTrue(manager.unlockVaultWithPassword(f.password, first))
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            val lockGeneration = SecurityManager.dataReadGeneration.value
            assertTrue(manager.resetMasterPassword(f.password, f.password,
                SecurityManager.LocalPasswordChangeAttempt()))
            assertEquals("This checks credential epoch, without a separate lock invalidating the attempt",
                lockGeneration, SecurityManager.dataReadGeneration.value)
            assertFalse(first.isCurrent())
            assertFalse(first.commit())
            f.assertRecordAndMdk(manager)
            val second = SecurityManager.PasswordVerificationAttempt()
            assertTrue(manager.unlockVaultWithPassword(f.password, second))
            val newPassword = "New-password-for-staged-attempt-327"
            assertTrue(manager.resetMasterPassword(f.password, newPassword))
            assertFalse(second.isCurrent())
            assertFalse(second.commit())
            assertTrue(manager.unlockVaultWithPassword(newPassword))
            f.assertRecordAndMdk(manager, newPassword)
        }
    }

    @Test fun androidAndNativeDerivationsMatchAllIndependentVectorsWithMeasuredDurations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val vectors = JSONObject(instrumentation.context.assets.open("local-password-kdf-vectors.json")
            .bufferedReader().use { it.readText() }).getJSONArray("vectors")
        val results = JSONArray()
        for (index in 0 until vectors.length()) {
            val v = vectors.getJSONObject(index)
            fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val profile = PasswordKdf.fromMetadata(if (v.isNull("metadata")) null else v.getString("metadata"))
            val password = v.getString("password")
            val salt = hex(v.getString("saltHex"))
            val expected = hex(v.getString("expectedKeyHex"))
            val start = SystemClock.elapsedRealtime()
            val actual = PasswordKdf.derive(password, salt, profile)
            val helperMs = SystemClock.elapsedRealtime() - start
            val nativeStart = SystemClock.elapsedRealtime()
            val native = if (profile.algorithm == LocalPasswordAlgorithm.ARGON2ID)
                RustBitwardenKdfCore.deriveArgon2id(password.toByteArray(Charsets.UTF_8), salt, 3, 65_536, 1)
            else RustBitwardenKdfCore.derivePbkdf2Sha256(password.toByteArray(Charsets.UTF_8), salt, profile.iterations)
            val nativeMs = SystemClock.elapsedRealtime() - nativeStart
            try {
                assertArrayEquals(v.getString("id"), expected, actual)
                assertNotNull("The packaged native KDF must be loaded for this native comparison", native)
                assertArrayEquals(v.getString("id") + " native", expected, native)
                results.put(JSONObject().put("id", v.getString("id")).put("helperMs", helperMs).put("nativeMs", nativeMs))
            } finally { actual.fill(0); native?.fill(0) }
        }
        // Fixed 30 cases and fixed reviewed costs; timings are observations, not performance promises.
        val folder = File(instrumentation.targetContext.filesDir, "local-password-kdf-327").apply { mkdirs() }
        File(folder, "vector-timings.json").writeText(JSONObject().put("passed", results.length()).put("results", results).toString(2))
    }
}
