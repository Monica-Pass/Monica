package takagi.ru.monica.security

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecoveryProtectionInstrumentedTest {
    private val password = BiometricWrapperRecoveryInstrumentedTest.PASSWORD
    private fun envelope(f: BiometricWrapperRecoveryInstrumentedTest.Fixture) = File(f.root, "files/local-vault-recovery/recovery.json")
    private fun verify(f: BiometricWrapperRecoveryInstrumentedTest.Fixture, value: String = password): RecoveryProtectionCheck {
        val attempt = SecurityManager.PasswordVerificationAttempt()
        return try { f.manager.verifyRecoveryProtection(value, attempt) } finally { attempt.cancel() }
    }

    @Test fun independentRehearsalPreservesBytesKeysAndDoesNotGrantUnlock() = BiometricWrapperRecoveryInstrumentedTest.Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(password))
        val cipher = f.manager.encryptData("synthetic-old-record")
        val before = f.prefs.all.toMap(); val bytes = envelope(f).readBytes(); val aliases = f.keystore.aliases().toList().toSet()
        SecurityManager.clearRuntimeUnlockCache()
        assertEquals(RecoveryProtectionCheck.VERIFIED, verify(f))
        assertNotNull(f.manager.recoveryProtectionStatus().verifiedAtMillis)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
        assertEquals(before, f.prefs.all); assertArrayEquals(bytes, envelope(f).readBytes())
        assertEquals(aliases, f.keystore.aliases().toList().toSet())
        assertNull(LocalVaultRecovery(f.context).activeStore())
        assertTrue(f.manager.unlockVaultWithPassword(password))
        assertEquals("synthetic-old-record", f.manager.decryptData(cipher))
    }

    @Test fun badIndependentPasswordWrapperDoesNotBreakNormalLoginOrWrites() = BiometricWrapperRecoveryInstrumentedTest.Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(password))
        val cipher = f.manager.encryptData("normal-user-old")
        val file = envelope(f); val doc = JSONObject(file.readText())
        val bytes = Base64.decode(doc.getString("key"), Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        doc.put("key", Base64.encodeToString(bytes, Base64.NO_WRAP)); file.writeText(doc.toString())
        val damaged = file.readBytes()
        assertEquals(RecoveryMaterialState.AVAILABLE, LocalVaultRecovery(f.context).materialState())
        assertEquals(RecoveryProtectionCheck.UNAVAILABLE, verify(f))
        assertNull(f.manager.recoveryProtectionStatus().verifiedAtMillis)
        assertTrue(f.manager.recoveryProtectionStatus().verificationFailed)
        assertArrayEquals(damaged, file.readBytes())
        SecurityManager.clearRuntimeUnlockCache()
        assertTrue("Optional protection failure must not deny valid normal login", f.manager.unlockVaultWithPassword(password))
        assertEquals("normal-user-old", f.manager.decryptData(cipher))
        val fresh = f.manager.encryptData("normal-user-new")
        f.manager.putProtectedString("normal-user-setting", "kept")
        SecurityManager.clearRuntimeUnlockCache()
        assertTrue(f.manager.unlockVaultWithPassword(password))
        assertEquals("normal-user-new", f.manager.decryptData(fresh))
        assertEquals("kept", f.manager.getProtectedString("normal-user-setting"))
    }

    @Test fun materialStatesDistinguishMissingInvalidRevokedAndUnsupported() = BiometricWrapperRecoveryInstrumentedTest.Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(password))
        val file = envelope(f); val saved = file.readBytes(); val recovery = LocalVaultRecovery(f.context)
        try {
            assertTrue(file.delete())
            assertEquals(RecoveryMaterialState.MISSING, recovery.materialState())
            file.writeText("{")
            assertEquals(RecoveryMaterialState.INVALID, recovery.materialState())
            file.writeText("{\"version\":999}")
            assertEquals(RecoveryMaterialState.UNSUPPORTED_VERSION, recovery.materialState())
            for ((reason, expected) in listOf("explicit_reset" to RecoveryMaterialState.REVOKED,
                "credential_change_requires_enrollment" to RecoveryMaterialState.REENROLLMENT_REQUIRED)) {
                file.writeText(JSONObject().put("version",0).put("reason",reason).toString())
                assertEquals(expected, recovery.materialState())
            }
            val doc = JSONObject(saved.toString(Charsets.UTF_8)); doc.remove("key");file.writeText(doc.toString())
            assertEquals(RecoveryMaterialState.INVALID, recovery.materialState())
        } finally { file.writeBytes(saved) }
    }

    @Test fun wrongPasswordAndCancelledAttemptCannotPublishOrMutate() = BiometricWrapperRecoveryInstrumentedTest.Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(password))
        val before = f.prefs.all.toMap();val bytes = envelope(f).readBytes()
        assertEquals(RecoveryProtectionCheck.INVALID_PASSWORD, verify(f,"wrong"))
        val attempt = SecurityManager.PasswordVerificationAttempt().apply { cancel() }
        assertEquals(RecoveryProtectionCheck.UNAVAILABLE, f.manager.verifyRecoveryProtection(password, attempt))
        assertNull(f.manager.recoveryProtectionStatus().verifiedAtMillis)
        assertEquals(before,f.prefs.all);assertArrayEquals(bytes,envelope(f).readBytes())
    }

    @Test fun lockRevisionAndPasswordChangeRetireVerification() = BiometricWrapperRecoveryInstrumentedTest.Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(password));assertEquals(RecoveryProtectionCheck.VERIFIED,verify(f))
        f.manager.putProtectedString("changed-after-check","value")
        assertNull(f.manager.recoveryProtectionStatus().verifiedAtMillis)
        assertEquals(RecoveryProtectionCheck.VERIFIED,verify(f))
        SecurityManager.clearRuntimeUnlockCache()
        assertNull(f.manager.recoveryProtectionStatus().verifiedAtMillis)
        assertEquals(RecoveryProtectionCheck.VERIFIED,verify(f))
        assertTrue(f.manager.resetMasterPassword(password,"Synthetic-New-Protection331"))
        assertNull(f.manager.recoveryProtectionStatus().verifiedAtMillis)
        assertEquals(RecoveryProtectionCheck.INVALID_PASSWORD,verify(f))
        assertEquals(RecoveryProtectionCheck.VERIFIED,verify(f,"Synthetic-New-Protection331"))
    }

    @Test fun changedRecoveryCiphertextCannotReusePreviousSuccess() = BiometricWrapperRecoveryInstrumentedTest.Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(password));assertEquals(RecoveryProtectionCheck.VERIFIED,verify(f))
        val file=envelope(f);val before=file.readBytes()
        try {
            val doc=JSONObject(file.readText());val bytes=Base64.decode(doc.getString("key"),Base64.NO_WRAP)
            bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
            doc.put("key",Base64.encodeToString(bytes,Base64.NO_WRAP));file.writeText(doc.toString())
            assertNull(f.manager.recoveryProtectionStatus().verifiedAtMillis)
        } finally { file.writeBytes(before) }
    }

    @Test fun lockDuringIndependentKdfCannotPublishSuccess() = BiometricWrapperRecoveryInstrumentedTest.Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(password))
        val mdk=LocalPasswordCredentials.unwrap(password,f.prefs.all);val session=SecurityManager.dataReadGeneration.value
        var checkpoints=0
        try {
            assertFalse(LocalVaultRecovery(f.context).verifyProtection(f.prefs,password,mdk,session,
                ensureCurrent={
                    if (++checkpoints==2) SecurityManager.clearRuntimeUnlockCache()
                    check(SecurityManager.dataReadGeneration.value==session)
                },publish={it()}))
            assertNull(f.manager.recoveryProtectionStatus().verifiedAtMillis)
        } finally { mdk.fill(0) }
    }

    @Test fun diagnosticReportsOnlyMaterialClassification() {
        val text=SecureStorageStartup.diagnostic(SecureStorageUnavailableException("monica_secure_prefs","MISSING_MASTER_KEY",
            phase=SecureStoragePhase.PREFLIGHT),1,RecoveryMaterialState.INVALID)
        assertTrue(text.contains("Recovery material: INVALID"));assertTrue(text.contains("Attempts: 1"))
        assertFalse(text.contains("recovery.json"));assertFalse(text.contains(password))
    }

    @Test fun explicitCheckEnrollsMissingMaterialAndPreservesRevokedMaterial() = BiometricWrapperRecoveryInstrumentedTest.Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(password))
        val cipher = f.manager.encryptData("synthetic-existing")
        val file = envelope(f)
        assertTrue(file.delete())
        // Model a healthy pre-enrollment installation. Deleting only a modern envelope
        // while retaining its publication markers must continue to fail closed.
        val raw = SecurePreferencesStore.openRaw(f.context, SecurePreferencesStore.MONICA,
            androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS).preferences
        assertTrue(raw.edit().remove(LocalVaultRecovery.KEY).remove(LocalVaultRecovery.GENERATION)
            .remove(LocalVaultRecovery.REVISION).commit())
        assertEquals(RecoveryProtectionCheck.VERIFIED, verify(f))
        assertEquals("synthetic-existing", f.manager.decryptData(cipher))
        val saved = file.readBytes()
        try {
            file.writeText("{\"version\":0,\"reason\":\"explicit_reset\"}")
            val revoked = file.readBytes()
            assertEquals(RecoveryProtectionCheck.UNAVAILABLE, verify(f))
            assertArrayEquals(revoked, file.readBytes())
        } finally { file.writeBytes(saved) }
    }

    @Test fun missingModernEnvelopeCannotBypassPublicationMarkers() = BiometricWrapperRecoveryInstrumentedTest.Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(password))
        val file = envelope(f); val saved = file.readBytes()
        try {
            assertTrue(file.delete())
            assertEquals(RecoveryProtectionCheck.UNAVAILABLE, verify(f))
            assertFalse(file.exists())
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
        } finally { file.writeBytes(saved) }
    }
}
