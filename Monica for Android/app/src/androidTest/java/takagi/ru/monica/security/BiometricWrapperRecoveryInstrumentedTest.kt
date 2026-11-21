package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class BiometricWrapperRecoveryInstrumentedTest {
    @Test fun unreadableExistingWrapperNeverReportsRebuiltEvenWithPasswordCache() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        val cipher = f.manager.encryptData(SECRET)
        val original = checkNotNull(f.prefs.getString("mdk_keystore_blob", null))
        val bytes = Base64.decode(original.removePrefix("CP|"), Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val broken = "CP|" + Base64.encodeToString(bytes, Base64.NO_WRAP)
        assertTrue(f.prefs.edit().putString("mdk_keystore_blob", broken).commit())
        SecurityManager.clearRuntimeUnlockCache()
        assertFalse(f.manager.unlockVaultWithBiometric())
        assertTrue(f.manager.unlockVaultWithPassword(PASSWORD))
        assertEquals(SECRET, f.manager.decryptData(cipher))
        val before = f.prefs.all.toMap()
        assertFalse("An existing unreadable blob is not a successful repair", f.manager.rebuildKeystoreWrapperFromRuntimeCacheIfNeeded())
        assertEquals(before, f.prefs.all)
        assertEquals(broken, f.prefs.getString("mdk_keystore_blob", null))
    }

    @Test fun missingKeyNeverReportsRebuiltOrGeneratesReplacementDuringRead() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        f.keystore.deleteEntry(f.alias("KEY_ALIAS_DATA_COMPAT"))
        SecurityManager.clearRuntimeUnlockCache()
        val before = f.prefs.all.toMap()
        assertFalse("Missing key cannot be repaired by a read", f.manager.rebuildKeystoreWrapperFromRuntimeCacheIfNeeded())
        assertEquals(before, f.prefs.all)
        assertFalse(f.keystore.containsAlias(f.alias("KEY_ALIAS_DATA_COMPAT")))
    }

    @Test fun confirmedRebindRetainsPasswordOldWrapperAliasesAndColdData() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        val cipher = f.manager.encryptData(SECRET)
        val old = f.prefs.all.toMap()
        val key = f.keystore.getKey(f.alias("KEY_ALIAS_DATA_COMPAT"), null)
        assertTrue(f.prefs.edit().putString("mdk_keystore_blob", "CP|broken").commit())
        assertEquals(SecurityManager.BiometricRebindResult.SUCCESS,
            f.manager.rebindBiometricWithPassword(PASSWORD, SecurityManager.BiometricRebindAttempt()))
        val repaired = f.prefs.all
        assertEquals("CP|broken", repaired["mdk_keystore_blob"])
        LocalPasswordCredentials.keys.forEach { assertEquals(old[it], repaired[it]) }
        assertEquals(key, f.keystore.getKey(f.alias("KEY_ALIAS_DATA_COMPAT"), null))
        assertNotNull(repaired[BiometricMdkBinding.ACTIVE])
        SecurityManager.clearRuntimeUnlockCache()
        assertTrue(f.manager.unlockVaultWithBiometric())
        assertEquals(SECRET, f.manager.decryptData(cipher))
        val fresh = f.manager.encryptData("new synthetic record")
        SecurityManager.clearRuntimeUnlockCache()
        assertTrue(f.manager.unlockVaultWithBiometric())
        assertEquals("new synthetic record", f.manager.decryptData(fresh))
        assertFalse(f.manager.unlockVaultWithPassword("wrong password"))
        assertTrue(f.manager.unlockVaultWithPassword(PASSWORD))
    }

    @Test fun wrongPasswordCannotUseWarmCacheOrGenerateKeys() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        val before = f.prefs.all.toMap(); val aliases = f.keystore.aliases().toList().toSet()
        assertEquals(SecurityManager.BiometricRebindResult.INVALID_PASSWORD,
            f.manager.rebindBiometricWithPassword("wrong", SecurityManager.BiometricRebindAttempt()))
        assertEquals(before, f.prefs.all)
        assertEquals(aliases, f.keystore.aliases().toList().toSet())
    }

    @Test fun damagedPasswordWrapperCannotUseWarmCache() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        // Ordinary preference writes correctly reject credential changes. Seed this damaged
        // authenticated snapshot through the fixture's recovery layer, without bypassing the reader.
        LocalVaultRecovery(f.context).enroll(f.prefs, PASSWORD, mapOf("mdk_password_blob" to "broken"), f.prefs.all)
        val before = f.prefs.all.toMap()
        assertEquals(SecurityManager.BiometricRebindResult.UNAVAILABLE,
            f.manager.rebindBiometricWithPassword(PASSWORD, SecurityManager.BiometricRebindAttempt()))
        assertEquals(before, f.prefs.all)
    }

    @Test fun cancellationAfterCandidateCreationPreservesBindingAndDiscardsOnlyCandidate() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        val before = f.prefs.all.toMap(); val aliases = f.keystore.aliases().toList().toSet()
        val attempt = SecurityManager.BiometricRebindAttempt()
        assertEquals(SecurityManager.BiometricRebindResult.UNAVAILABLE,
            f.manager.rebindBiometricWithPassword(PASSWORD, attempt) {
                if (f.keystore.aliases().toList().toSet() != aliases) attempt.cancel()
            })
        assertEquals(before, f.prefs.all)
        assertEquals(aliases, f.keystore.aliases().toList().toSet())
    }

    @Test fun lockAfterCandidateCreationCannotPublish() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        val before = f.prefs.all.toMap(); val aliases = f.keystore.aliases().toList().toSet()
        val attempt = SecurityManager.BiometricRebindAttempt()
        assertEquals(SecurityManager.BiometricRebindResult.UNAVAILABLE,
            f.manager.rebindBiometricWithPassword(PASSWORD, attempt) {
                if (f.keystore.aliases().toList().toSet() != aliases) SecurityManager.clearRuntimeUnlockCache()
            })
        assertEquals(before, f.prefs.all)
        assertEquals(aliases, f.keystore.aliases().toList().toSet())
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
    }

    @Test fun selectedBindingFailureNeverFallsBackToHealthyLegacyWrapper() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        assertEquals(SecurityManager.BiometricRebindResult.SUCCESS,
            f.manager.rebindBiometricWithPassword(PASSWORD, SecurityManager.BiometricRebindAttempt()))
        val binding = requireNotNull(BiometricMdkBinding.selected(f.prefs.all))
        f.keystore.deleteEntry(binding.alias)
        SecurityManager.clearRuntimeUnlockCache()
        assertFalse(f.manager.unlockVaultWithBiometric())
        assertEquals(SecurityManager.BiometricBindingHealth.REPAIR_REQUIRED, f.manager.biometricBindingHealth())
        assertTrue(f.manager.unlockVaultWithPassword(PASSWORD))
        assertFalse(f.manager.rebuildKeystoreWrapperFromRuntimeCacheIfNeeded())
    }

    @Test fun secondRebindRetainsPreviousSlotAndAlias() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        repeat(2) { assertEquals(SecurityManager.BiometricRebindResult.SUCCESS,
            f.manager.rebindBiometricWithPassword(PASSWORD, SecurityManager.BiometricRebindAttempt())) }
        val slots = f.prefs.all.filterKeys { it.startsWith("mdk_biometric_binding_v1_") }
        assertEquals(2, slots.size)
        slots.keys.forEach { assertTrue(f.keystore.containsAlias("monica_biometric_mdk_v1_" + it.removePrefix("mdk_biometric_binding_v1_"))) }
    }

    @Test fun competingBindingChangeRejectsPreparedCandidate() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        val aliases = f.keystore.aliases().toList().toSet()
        var changed = false
        assertEquals(SecurityManager.BiometricRebindResult.UNAVAILABLE,
            f.manager.rebindBiometricWithPassword(PASSWORD, SecurityManager.BiometricRebindAttempt()) {
                if (!changed && f.keystore.aliases().toList().toSet() != aliases) {
                    changed = true
                    assertTrue(f.prefs.edit().putString("mdk_keystore_blob", "CP|concurrent").commit())
                }
            })
        assertTrue(changed)
        assertNull(f.prefs.getString(BiometricMdkBinding.ACTIVE, null))
        assertEquals("CP|concurrent", f.prefs.getString("mdk_keystore_blob", null))
        assertEquals(aliases, f.keystore.aliases().toList().toSet())
    }

    @Test fun repairedBindingDoesNotReplaceLegacyFieldEncryptionKey() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, f.keystore.getKey(f.alias("KEY_ALIAS_DATA_COMPAT"), null))
        val legacy = "C2|" + Base64.encodeToString(cipher.iv + cipher.doFinal(SECRET.toByteArray()), Base64.NO_WRAP)
        assertEquals(SecurityManager.BiometricRebindResult.SUCCESS,
            f.manager.rebindBiometricWithPassword(PASSWORD, SecurityManager.BiometricRebindAttempt()))
        SecurityManager.clearRuntimeUnlockCache()
        assertEquals(SECRET, f.manager.decryptData(legacy))
    }

    @Test fun disablingDeveloperBypassCannotLeaveRepairedCompatBindingSelected() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        assertEquals(SecurityManager.BiometricRebindResult.SUCCESS,
            f.manager.rebindBiometricWithPassword(PASSWORD, SecurityManager.BiometricRebindAttempt()))
        val binding = requireNotNull(BiometricMdkBinding.selected(f.prefs.all))
        assertTrue(f.manager.configureDeveloperVerificationBypass(false))
        assertNull(f.prefs.getString(BiometricMdkBinding.ACTIVE, null))
        assertTrue(f.prefs.contains("mdk_biometric_binding_v1_" + binding.id))
        assertTrue(f.keystore.containsAlias(binding.alias))
        SecurityManager.clearRuntimeUnlockCache()
        assertFalse(f.manager.unlockVaultWithBiometric())
        assertTrue(f.manager.unlockVaultWithPassword(PASSWORD))
    }

    @Test fun biometricReadStagesKeyAndCancellationPreventsPublication() = Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(PASSWORD))
        SecurityManager.clearRuntimeUnlockCache()
        val cancelled = SecurityManager.PasswordVerificationAttempt()
        assertTrue(f.manager.unlockVaultWithBiometric(cancelled) { })
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
        cancelled.cancel()
        assertFalse(cancelled.commit())
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
        val accepted = SecurityManager.PasswordVerificationAttempt()
        assertTrue(f.manager.unlockVaultWithBiometric(accepted) { })
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
        assertTrue(accepted.commit())
        assertTrue(SecurityManager.hasRuntimeUnlockCache())
    }

    internal class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "biometric-rebind-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply { dataDir = root.absolutePath }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(prefix + name, mode)
        }
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val manager = SecurityManager(context)
        init {
            SecurityManager.clearRuntimeUnlockCache()
            for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT")) {
                SecurityManager::class.java.getDeclaredField(field).apply { isAccessible = true }.set(manager, alias(field))
            }
        }
        val prefs: SharedPreferences get() = SecurePreferencesStore.open(context, SecurePreferencesStore.MONICA).preferences
        fun alias(field: String) = prefix + field
        override fun close() {
            SecurityManager.clearRuntimeUnlockCache()
            prefs.all.keys.filter { it.startsWith("mdk_biometric_binding_v1_") }.forEach {
                BiometricMdkBinding.discardUncommitted(it.removePrefix("mdk_biometric_binding_v1_"))
            }
            for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT")) if (keystore.containsAlias(alias(field))) keystore.deleteEntry(alias(field))
            base.deleteSharedPreferences(prefix + SecurePreferencesStore.MONICA)
            base.deleteSharedPreferences(prefix + SecurePreferencesStore.BITWARDEN)
            check(root.parentFile == base.cacheDir && root.name.startsWith("biometric-rebind-"))
            root.deleteRecursively()
        }
    }

    companion object {
        internal const val PASSWORD = "Synthetic-Biometric-Rebind-331!"
        internal const val SECRET = "synthetic-original-record-331"
    }
}
