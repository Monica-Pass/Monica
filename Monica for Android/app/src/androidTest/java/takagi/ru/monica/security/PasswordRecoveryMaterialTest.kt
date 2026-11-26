package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.security.Security
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PasswordRecoveryMaterialTest {
    private inline fun withoutDeviceKeyProvider(block: () -> Unit) {
        val provider = requireNotNull(Security.getProvider("AndroidKeyStore"))
        val position = Security.getProviders().indexOf(provider) + 1
        try {
            Security.removeProvider(provider.name)
            assertThrows(java.security.KeyStoreException::class.java) { KeyStore.getInstance("AndroidKeyStore") }
            block()
        } finally {
            assertEquals(position, Security.insertProviderAt(provider, position))
        }
    }

    @Test fun passwordMaterialWorksWithoutKeystoreOrAnySharedPreferenceAccess() {
        RecoveryTestFixture().use { f ->
            val original = File(f.root, "files/local-vault-recovery/recovery.json").readBytes()
            val context = object : ContextWrapper(f.context) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    error("Recovery must not open the inaccessible secure preference store")
            }
            SecurityManager.clearRuntimeUnlockCache()
            withoutDeviceKeyProvider {
                LocalVaultRecovery(context).authenticatePasswordMaterial(f.password).use { material ->
                    assertEquals("Synthetic retained password/OTP/证件", material.decryptMdk(f.record))
                    assertEquals("Synthetic retained password/OTP/证件", material.decryptMdk(" \t${f.record}\r\n"))
                    assertThrows(IllegalArgumentException::class.java) { material.decryptMdk("C2|unavailable") }
                    assertThrows(IllegalArgumentException::class.java) { material.decryptMdk("V2|unavailable") }
                    assertThrows(IllegalArgumentException::class.java) { material.decryptMdk("unprefixed ambiguous data") }
                    assertThrows(IllegalArgumentException::class.java) { material.decryptMdk("MDK|AA==") }
                }
            }
            assertArrayEquals(original, File(f.root, "files/local-vault-recovery/recovery.json").readBytes())
            assertNull(f.recovery.activeStore())
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
        }
    }

    @Test fun wrongPasswordAndCorruptDataNeverProducePlaintextOrChangeSources() {
        RecoveryTestFixture().use { f ->
            val file = File(f.root, "files/local-vault-recovery/recovery.json")
            val before = file.readBytes()
            assertThrows(Exception::class.java) { f.recovery.authenticatePasswordMaterial("incorrect") }
            assertArrayEquals(before, file.readBytes())
            f.recovery.authenticatePasswordMaterial(f.password).use { material ->
                val bytes = java.util.Base64.getDecoder().decode(f.record.removePrefix("MDK|"))
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                val damaged = "MDK|" + java.util.Base64.getEncoder().encodeToString(bytes)
                assertThrows(javax.crypto.AEADBadTagException::class.java) { material.decryptMdk(damaged) }
            }
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun changedRecoverySourceRetiresAlreadyAuthenticatedMaterial() {
        RecoveryTestFixture().use { f ->
            val file = File(f.root, "files/local-vault-recovery/recovery.json")
            val before = file.readBytes()
            f.recovery.authenticatePasswordMaterial(f.password).use { material ->
                try {
                    val doc = JSONObject(before.toString(Charsets.UTF_8))
                    doc.put("revision", java.util.UUID.randomUUID().toString())
                    file.writeText(doc.toString())
                    assertThrows(IllegalStateException::class.java) { material.decryptMdk(f.record) }
                    assertThrows(IllegalStateException::class.java) { material.preferenceSnapshot() }
                } finally { file.writeBytes(before) }
            }
        }
    }

    @Test fun cancelledOrClosedMaterialCannotBeUsedForDataReads() {
        RecoveryTestFixture().use { f ->
            var cancelled = false
            val material = f.recovery.authenticatePasswordMaterial(f.password) {
                if (cancelled) throw kotlinx.coroutines.CancellationException("Synthetic cancellation")
            }
            try {
                cancelled = true
                assertThrows(kotlinx.coroutines.CancellationException::class.java) { material.decryptMdk(f.record) }
                cancelled = false
                material.close()
                assertThrows(IllegalStateException::class.java) { material.decryptMdk(f.record) }
                assertThrows(IllegalStateException::class.java) { material.copyRecoveryKey() }
            } finally { material.close() }
        }
    }

    @Test fun confirmedFallbackPersistsAndColdUnlocksWithoutKeystoreWhilePreservingOriginals() {
        RecoveryTestFixture().use { f ->
            val file = File(f.root, "files/local-vault-recovery/recovery.json")
            val before = file.readBytes()
            val oldPreferences = f.prefs.all.toMap()
            withoutDeviceKeyProvider {
                assertFalse(f.recovery.recoverWithoutKeystore("wrong password"))
                assertEquals(RecoveryStoreSelection.Original, f.recovery.selection())
                assertTrue(f.recovery.recoverWithoutKeystore(f.password))
                assertTrue(f.recovery.selection() is RecoveryStoreSelection.Password)
                assertArrayEquals(before, file.readBytes())
                assertEquals(oldPreferences, f.prefs.all)

                var manager = SecurityManager.openFresh(f.context)
                manager.acceptPreparedStore()
                assertTrue(manager.unlockVaultWithPassword(f.password))
                manager.markVaultAuthenticated()
                assertEquals("Synthetic retained password/OTP/证件", manager.decryptData(f.record))
                val created = manager.encryptData("after recovery")
                manager.putProtectedString("synthetic_protected_config", "retained config")
                assertTrue(manager.resetMasterPassword(f.password, f.replacement))
                assertFalse(manager.unlockVaultWithPassword(f.password))
                assertTrue(manager.unlockVaultWithPassword(f.replacement))
                assertEquals("after recovery", manager.decryptData(created))

                f.recovery.forgetPasswordSession()
                SessionManager.markLocked()
                PreparedSecureStore.invalidate()
                val blocked = SecureStorageStartup.prepare(f.context)
                assertTrue(blocked is SecureStartupResult.Blocked)
                assertEquals("PASSWORD_RECOVERY_REQUIRED", (blocked as SecureStartupResult.Blocked).failure.reason)
                assertFalse(f.recovery.recoverWithoutKeystore(f.password))
                assertTrue(f.recovery.recoverWithoutKeystore(f.replacement))
                manager = SecurityManager.openFresh(f.context)
                manager.acceptPreparedStore()
                assertTrue(manager.unlockVaultWithPassword(f.replacement))
                assertEquals("after recovery", manager.decryptData(created))
                assertEquals("retained config", manager.getProtectedString("synthetic_protected_config"))
                assertArrayEquals(before, file.readBytes())
                assertEquals(oldPreferences, f.prefs.all)
            }
            f.recovery.forgetPasswordSession()
        }
    }

    @Test fun rejectedFallbackPublicationLeavesOriginalSelectedAndReadable() {
        RecoveryTestFixture().use { f ->
            val source = File(f.root, "files/local-vault-recovery/recovery.json").readBytes()
            withoutDeviceKeyProvider {
                assertFalse(f.recovery.recoverWithoutKeystore(f.password) {
                    throw kotlinx.coroutines.CancellationException("Synthetic abandoned request")
                })
            }
            assertEquals(RecoveryStoreSelection.Original, f.recovery.selection())
            assertArrayEquals(source, File(f.root, "files/local-vault-recovery/recovery.json").readBytes())
            f.assertRecord(f.password)
        }
    }

    @Test fun passwordSettingsAndBitwardenOptionsUseOnlyTheCanonicalEncryptedDocument() {
        RecoveryTestFixture().use { f ->
            val before = File(f.root, "files/local-vault-recovery/recovery.json").readBytes()
            withoutDeviceKeyProvider {
                assertTrue(f.recovery.recoverWithoutKeystore(f.password))
                val manager = SecurityManager.openFresh(f.context).also { it.acceptPreparedStore() }
                assertTrue(manager.unlockVaultWithPassword(f.password))
                val prefs = RecoverableBitwardenSettings.open(f.context)
                assertFalse(prefs.getBoolean("auto_sync_enabled", true))
                assertFalse(prefs.getBoolean("never_lock_bitwarden", true))
                val first = prefs.edit().putLong("active_vault_id", 123L)
                val second = prefs.edit().putBoolean("sync_on_wifi_only", true)
                assertTrue(first.commit()); assertTrue(second.commit())
                assertEquals(123L, prefs.getLong("active_vault_id", -1L))
                assertTrue(prefs.getBoolean("sync_on_wifi_only", false))
                manager.setBiometricEnabled(true)
                assertFalse(manager.isBiometricEnabled())
                assertEquals(SecurityManager.BiometricRebindResult.UNAVAILABLE,
                    manager.rebindBiometricWithPassword(f.password, SecurityManager.BiometricRebindAttempt()))
                val canonical = f.recovery.selectedEnvelopeFile().readBytes()
                assertThrows(IllegalStateException::class.java) { manager.clearSecurityData() }
                assertArrayEquals(canonical, f.recovery.selectedEnvelopeFile().readBytes())
                f.recovery.forgetPasswordSession()
                PreparedSecureStore.invalidate()
                assertTrue(f.recovery.recoverWithoutKeystore(f.password))
                SecurityManager.openFresh(f.context).acceptPreparedStore()
                val cold = RecoverableBitwardenSettings.open(f.context)
                assertEquals(123L, cold.getLong("active_vault_id", -1L))
                assertTrue(cold.getBoolean("sync_on_wifi_only", false))
                assertFalse(f.recovery.selectedEnvelopeFile().readText().contains("sync_on_wifi_only"))
                assertArrayEquals(before, File(f.root, "files/local-vault-recovery/recovery.json").readBytes())
            }
            f.recovery.forgetPasswordSession()
        }
    }

    @Test fun failedCanonicalWriteAndDamagedEnvelopeNeverFallBackToOriginalCredentials() {
        RecoveryTestFixture().use { f ->
            assertTrue(f.recovery.recoverWithoutKeystore(f.password))
            val manager = SecurityManager.openFresh(f.context).also { it.acceptPreparedStore() }
            assertTrue(manager.unlockVaultWithPassword(f.password))
            val selected = f.recovery.selectedEnvelopeFile()
            val before = selected.readBytes()
            val obstruction = File(selected.path + ".new")
            assertTrue(obstruction.mkdir())
            val marker = File(obstruction, "synthetic-write-obstruction").apply { writeText("owned fixture") }
            try {
                assertThrows(Exception::class.java) { manager.putProtectedString("must_not_commit", "value") }
                assertFalse(manager.resetMasterPassword(f.password, f.replacement))
                assertArrayEquals(before, selected.readBytes())
            } finally { assertTrue(marker.delete()); assertTrue(obstruction.delete()) }
            f.recovery.forgetPasswordSession(); PreparedSecureStore.invalidate()
            selected.writeText("{\"version\":2")
            try {
                assertFalse(f.recovery.recoverWithoutKeystore(f.password))
                assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Blocked)
                assertTrue(f.recovery.selection() is RecoveryStoreSelection.Password)
            } finally { selected.writeBytes(before) }
            assertTrue(f.recovery.recoverWithoutKeystore(f.password))
            val reopened = SecurityManager.openFresh(f.context)
            assertTrue(reopened.unlockVaultWithPassword(f.password))
            assertNull(reopened.getProtectedString("must_not_commit"))
            f.recovery.forgetPasswordSession()
        }
    }
}
