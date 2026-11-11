package takagi.ru.monica.security

import org.junit.Assert.*
import org.junit.Test

class RecoveryAuthorizationInstrumentedTest {
    @Test fun questionSaveDoesNotHoldRuntimeLockDuringStorageCommitAndRejectsLockBeforeGate() {
        for (revoke in listOf(false, true)) RecoveryTestFixture().use { f ->
            val field = SecurityManager::class.java.getDeclaredField("sharedPreferences").apply { isAccessible = true }
            val original = field.get(f.manager) as android.content.SharedPreferences
            val runtimeLock = SecurityManager::class.java.getDeclaredField("runtimeKeyLock").apply { isAccessible = true }.get(null)
            var committed = false
            val observed = object : android.content.SharedPreferences by original {
                override fun edit(): android.content.SharedPreferences.Editor {
                    val editor = original.edit()
                    if (revoke) SessionManager.markLocked()
                    return java.lang.reflect.Proxy.newProxyInstance(
                        android.content.SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { proxy, method, args ->
                        if (method.name == "commit" || method.name == "apply") {
                            assertFalse("Runtime key lock must not surround storage writes", Thread.holdsLock(runtimeLock))
                            committed = true
                        }
                        val result = method.invoke(editor, *(args ?: emptyArray()))
                        if (method.returnType == android.content.SharedPreferences.Editor::class.java) proxy else result
                    } as android.content.SharedPreferences.Editor
                }
            }
            val before = original.all.toMap()
            field.set(f.manager, observed)
            try {
                val result = runCatching { f.manager.setSecurityQuestions(1, "new answer", 2, "another answer") }
                assertEquals(!revoke, result.isSuccess)
                assertEquals(!revoke, committed)
                if (revoke) assertEquals(before, original.all)
                else assertTrue(checkNotNull(f.manager.exportSecurityQuestionsForBackup()).answer1Hash.startsWith("sq2$"))
            } finally { field.set(f.manager, original) }
            f.assertRecord(f.password)
        }
    }
    @Test fun initialEnrollmentCannotReplaceExistingPasswordWithCacheOrCompatWrapper() {
        for (cached in listOf(true, false)) RecoveryTestFixture().use { f ->
            if (!cached) SecurityManager.clearRuntimeUnlockCache()
            val before = f.prefs.all.toMap()
            assertFalse(f.manager.setMasterPassword(f.replacement))
            assertEquals(before, f.prefs.all)
            f.assertRecord(f.password)
        }
    }
    @Test fun forgedOrMissingPermitNeverChangesCredentials() = RecoveryTestFixture().use { f ->
        val before = f.prefs.all.toMap()
        assertFalse(f.manager.recoverMasterPassword(f.replacement, null))
        assertFalse(f.manager.recoverMasterPassword(f.replacement, RecoveryPermit()))
        assertEquals(before, f.prefs.all)
        f.assertRecord(f.password)
    }
    @Test fun answersAloneAndCancelledChallengeDoNotAuthorize() = RecoveryTestFixture().use { f ->
        val first = checkNotNull(f.manager.beginRecoveryWithAnswers("answer one", "answer two") {})
        assertFalse(f.manager.recoverMasterPassword(f.replacement, null))
        f.manager.cancelRecovery(first)
        assertNull(f.manager.completeRecoveryDeviceCredential(first))
        f.assertRecord(f.password)
    }
    @Test fun authenticatedRecoveryRetainsMdkCiphertextAndMigratesActualLegacyAnswers() = RecoveryTestFixture().use { f ->
        val beforeWrapper = f.prefs.getString("mdk_keystore_blob", null)
        SecurityManager.clearRuntimeUnlockCache()
        val request = checkNotNull(f.manager.beginRecoveryWithAnswers(" Answer One ", "ANSWER TWO") {})
        val proof = checkNotNull(f.manager.completeRecoveryDeviceCredential(request))
        assertTrue(f.manager.recoverMasterPassword(f.replacement, proof))
        assertFalse(f.manager.recoverMasterPassword("Synthetic-replay", proof))
        assertFalse(f.manager.verifyMasterPassword(f.password))
        assertEquals(beforeWrapper, f.prefs.getString("mdk_keystore_blob", null))
        val backup = checkNotNull(f.manager.exportSecurityQuestionsForBackup())
        assertTrue(backup.answer1Hash.startsWith("sq2$"))
        assertTrue(backup.answer2Hash.startsWith("sq2$"))
        assertTrue(SecurityAnswerHash.verify("answer one", backup.answer1Hash))
        assertFalse(SecurityAnswerHash.verify(backup.answer1Hash, backup.answer1Hash))
        SecurityManager.clearRuntimeUnlockCache()
        f.assertRecord(f.replacement)
        assertEquals(0, RecoveryAttemptLimiter(f.context).status().attempts)
    }
    @Test fun lockOrAnswerReplacementRevokesPermitBeforeCredentialCommit() {
        for (lock in listOf(true, false)) RecoveryTestFixture().use { f ->
            val request = checkNotNull(f.manager.beginRecoveryWithAnswers("answer one", "answer two") {})
            val proof = checkNotNull(f.manager.completeRecoveryDeviceCredential(request))
            if (lock) SessionManager.markLocked() else f.manager.clearSecurityQuestions()
            val before = f.prefs.all.toMap()
            assertFalse(f.manager.recoverMasterPassword(f.replacement, proof))
            assertEquals(before, f.prefs.all)
            f.assertRecord(f.password)
        }
    }
    @Test fun missingWrapperCannotGenerateReplacementMdkDuringRecovery() = RecoveryTestFixture().use { f ->
        assertTrue(f.prefs.edit().remove("mdk_keystore_blob").commit())
        SecurityManager.clearRuntimeUnlockCache()
        val request = checkNotNull(f.manager.beginRecoveryWithAnswers("answer one", "answer two") {})
        val proof = checkNotNull(f.manager.completeRecoveryDeviceCredential(request))
        val before = f.prefs.all.toMap()
        assertFalse(f.manager.recoverMasterPassword(f.replacement, proof))
        assertEquals(before, f.prefs.all)
        f.assertRecord(f.password)
    }
    @Test fun reservationsSurviveReopeningAndWrongAnswersNeverModifyLegacyHashes() = RecoveryTestFixture().use { f ->
        val before = f.manager.exportSecurityQuestionsForBackup()
        repeat(3) { assertNull(f.manager.beginRecoveryWithAnswers("wrong", "wrong") {}) }
        val limiter = RecoveryAttemptLimiter(f.context)
        assertTrue(limiter.status().blocked)
        assertEquals(3, limiter.status().attempts)
        assertFalse(limiter.reserve())
        assertNull(f.manager.beginRecoveryWithAnswers("answer one", "answer two") {})
        assertEquals(before, f.manager.exportSecurityQuestionsForBackup())
        f.assertRecord(f.password)
    }
    @Test fun versionedBackupRestoresVerifiersWithoutTreatingHashesAsUserAnswers() = RecoveryTestFixture().use { f ->
        f.manager.setSecurityQuestions(1, "new answer", 2, "another answer")
        val backup = checkNotNull(f.manager.exportSecurityQuestionsForBackup())
        f.manager.clearSecurityQuestions()
        assertTrue(f.manager.restoreSecurityQuestionsFromBackup(backup))
        assertEquals(backup, f.manager.exportSecurityQuestionsForBackup())
        assertTrue(f.manager.verifySecurityAnswers("new answer", "another answer"))
        assertFalse(f.manager.verifySecurityAnswers(backup.answer1Hash, backup.answer2Hash))
        assertFalse(f.manager.restoreSecurityQuestionsFromBackup(backup.copy(answer1Hash = "sq9$" + "unknown")))
        assertEquals(backup, f.manager.exportSecurityQuestionsForBackup())
    }
}
