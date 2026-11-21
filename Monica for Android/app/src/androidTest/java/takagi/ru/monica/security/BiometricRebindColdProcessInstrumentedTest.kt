package takagi.ru.monica.security

import android.content.Context
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import javax.crypto.KeyGenerator

/** Explicitly driven in four separate processes; only isolated synthetic packages may run it. */
class BiometricRebindColdProcessInstrumentedTest {
    @Test fun retainedVaultAcrossProcesses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.startsWith("takagi.ru.monica.biometricdebug"))
        val args = InstrumentationRegistry.getArguments()
        val phase = requireNotNull(args.getString("rebindPhase"))
        val failure = requireNotNull(args.getString("rebindFailure"))
        check(failure in listOf("corrupt", "missing", "replaced"))
        val fixture = context.getSharedPreferences("biometric_cold_331", Context.MODE_PRIVATE)
        val manager = SecurityManager.openFresh(context)
        val prefs = SecurePreferencesStore.open(context, SecurePreferencesStore.MONICA).preferences
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val password = "Synthetic-Biometric-Cold-331!"
        val record = "original-cold-record-331"
        val pid = Process.myPid()
        when (phase) {
            "seed" -> {
                if (!manager.isMasterPasswordSet()) assertTrue(manager.setMasterPassword(password))
                else assertTrue(manager.unlockVaultWithPassword(password))
                // Each later case damages the currently selected binding, keeping all earlier slots.
                val binding = BiometricMdkBinding.selected(prefs.all)
                val key = binding?.alias ?: "monica_data_key_v2_compat"
                val slot = binding?.let { "mdk_biometric_binding_v1_" + it.id } ?: "mdk_keystore_blob"
                val old = requireNotNull(prefs.getString(slot, null))
                check(old.startsWith("CP|"))
                val cipher = manager.encryptData(record)
                assertTrue(fixture.edit().clear().putString("cipher", cipher).putString("slot", slot)
                    .putString("old", old).putString("alias", key).putString("passwordBlob", prefs.getString("mdk_password_blob", null))
                    .putInt("seedPid", pid).commit())
                when (failure) {
                    "corrupt" -> assertTrue(prefs.edit().putString(slot, "CP|broken").commit())
                    "missing" -> store.deleteEntry(key)
                    "replaced" -> {
                        store.deleteEntry(key)
                        KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                            init(KeyGenParameterSpec.Builder(key, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256).setUserAuthenticationRequired(false).build())
                            generateKey()
                        }
                    }
                }
                SecurityManager.clearRuntimeUnlockCache()
                assertFalse(manager.unlockVaultWithBiometric())
            }
            "repair" -> {
                assertNotEquals(fixture.getInt("seedPid", 0), pid)
                assertFalse(SecurityManager.hasRuntimeUnlockCache())
                assertFalse(manager.unlockVaultWithBiometric())
                val before = prefs.all.toMap()
                assertEquals(SecurityManager.BiometricRebindResult.INVALID_PASSWORD,
                    manager.rebindBiometricWithPassword("wrong", SecurityManager.BiometricRebindAttempt()))
                assertEquals(before, prefs.all)
                assertEquals(SecurityManager.BiometricRebindResult.SUCCESS,
                    manager.rebindBiometricWithPassword(password, SecurityManager.BiometricRebindAttempt()))
                assertFalse("Repair does not unlock the runtime", SecurityManager.hasRuntimeUnlockCache())
                assertEquals(fixture.getString("passwordBlob", null), prefs.getString("mdk_password_blob", null))
                val slot = requireNotNull(fixture.getString("slot", null))
                assertEquals(if (failure == "corrupt") "CP|broken" else fixture.getString("old", null), prefs.getString(slot, null))
                assertTrue(fixture.edit().putInt("repairPid", pid).commit())
            }
            "cold" -> {
                assertNotEquals(fixture.getInt("repairPid", 0), pid)
                assertFalse(SecurityManager.hasRuntimeUnlockCache())
                assertTrue(manager.unlockVaultWithBiometric())
                assertEquals(record, manager.decryptData(requireNotNull(fixture.getString("cipher", null))))
                val next = manager.encryptData("new-cold-record-331")
                assertTrue(fixture.edit().putString("next", next).putInt("coldPid", pid).commit())
            }
            "verifyNew" -> {
                assertNotEquals(fixture.getInt("coldPid", 0), pid)
                assertFalse(SecurityManager.hasRuntimeUnlockCache())
                assertTrue(manager.unlockVaultWithBiometric())
                assertEquals(record, manager.decryptData(requireNotNull(fixture.getString("cipher", null))))
                assertEquals("new-cold-record-331", manager.decryptData(requireNotNull(fixture.getString("next", null))))
                assertTrue(manager.unlockVaultWithPassword(password))
            }
            else -> error("Unknown cold process phase")
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("rebindProof", "$failure/$phase pid=$pid uid=${Process.myUid()}")
        })
    }
}
