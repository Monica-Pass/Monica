package takagi.ru.monica.passkey

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PasskeyUserVerificationGuardTest {
    private fun projectSource(path: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (root.parentFile != null && !File(root, "settings.gradle.kts").exists() && !File(root, "settings.gradle").exists()) root = root.parentFile!!
        return File(root, "app/src/main/java/takagi/ru/monica/$path.kt").readText().replace("\r\n", "\n")
    }
    private fun source(name: String) = projectSource("passkey/$name")
    @Test fun authenticationAndCreationRequireVerificationAndRetainPasswordFallback() {
        for (name in listOf("PasskeyAuthActivity", "PasskeyCreateActivity")) {
            val text = source(name)
            val biometric = text.substringAfter("private fun requestBiometricAuth(").substringBefore("private fun ")
            assertTrue(biometric.contains("biometricHelper.authenticate("))
            assertTrue(biometric.contains("!biometricHelper.isBiometricAvailable()"))
            assertTrue(biometric.contains("showMasterPasswordDialog.value = true"))
            val fallback = text.substringAfter("if (showMasterPasswordDialog.value) {")
                .substringBefore("private fun ")
            assertTrue("$name must use cancellable bounded password verification", fallback.contains("SecondaryPasswordVerificationDialog("))
            assertTrue(fallback.contains("manager = securityManager"))
            assertTrue(fallback.contains("isRequestCurrent = {") && fallback.contains("!isFinishing && !isDestroyed"))
            assertTrue(fallback.contains("showMasterPasswordDialog.value") && fallback.contains("requestAtOpen"))
            assertFalse("Passkey password fallback must not opt out of verification", fallback.contains("requirePassword = false"))
            val success = fallback.substringAfter("onSuccess = { generation ->")
            assertTrue("$name must reject stale successful verification", success.contains("if (securityManager.isDataReadCurrent(generation))"))
            val action = if (name == "PasskeyAuthActivity") "authenticateWithPasskey(requestAtOpen, currentPasskey)"
                else "createPasskey(requestAtOpen, pendingRpId, pendingUserName, pendingUserDisplayName)"
            assertTrue(success.indexOf("if (securityManager.isDataReadCurrent(generation))") < success.indexOf(action))
            assertTrue(success.contains(action))
            assertFalse("Synchronous password verification must not return to $name",
                Regex("(?:verifyMasterPassword|unlockVaultWithPassword)\\s*\\(").containsMatchIn(text))
            assertFalse(text.contains("shouldBypassBiometric"))
            assertFalse(text.contains("BIOMETRIC_BYPASSED"))
        }
        assertTrue(source("PasskeyAuthActivity").contains("requestBiometricAuth(currentPasskey)"))
        val create = source("PasskeyCreateActivity")
        assertEquals(2, Regex("(?m)^ +requestBiometricAuth\\(\\)$").findAll(create).count())
        val error = create.substringAfter("onError = biometricError@").substringBefore("onCancel =")
        assertTrue(error.contains("val masterPasswordSet = masterPasswordSetForCurrentRequest() ?: return@biometricError"))
        assertTrue(error.contains("if (masterPasswordSet)"))
        assertTrue(error.contains("showMasterPasswordDialog.value = true"))
        assertTrue(error.contains("return@biometricError"))
        val passwordState = create.substringAfter("private fun masterPasswordSetForCurrentRequest()").substringBefore("private fun requestBiometricAuth()")
        assertTrue(passwordState.contains("securityManager.isMasterPasswordSet()"))
        assertTrue(passwordState.contains("SecureStorageUnavailableException") && passwordState.contains("showSecureStorageBlocked(") && passwordState.contains("null"))
    }

    @Test fun sharedPasswordFallbackGrantsOnlyCurrentVerifiedCommittedAttemptAndRejectsFailures() {
        val text = projectSource("ui/components/SecondaryPasswordVerificationDialog")
        assertTrue(text.contains("requirePassword: Boolean = true"))
        assertTrue(text.contains("current() && foreground() && requestCurrent.value()"))
        val worker = text.substringAfter("work = scope.launch {").substringBefore("confirmText = confirmText")
        assertTrue(worker.contains("BoundedSecureDataRead.readChecked(isCurrent = {"))
        assertTrue(worker.contains("current() && attempt.isCurrent()"))
        assertTrue(worker.contains("manager.verifyMasterPassword(submitted, attempt) { ensureActive() }"))
        assertTrue(worker.contains("if (!current() || pending !== attempt) return@launch"))
        assertTrue(worker.contains("if (!foreground() || !requestCurrent.value()) { close(); return@launch }"))
        val verified = worker.substringAfter("if (verified == true && attempt.commit()) {").substringBefore("} else failed = true")
        assertTrue(verified.contains("success.value(entryGeneration)"))
        assertTrue(verified.indexOf("active.set(false)") < verified.indexOf("success.value(entryGeneration)"))
        assertTrue("Failed or timed-out verification cannot authorize", worker.contains("} else failed = true"))
        assertEquals("There must be exactly one success call in the password worker", 1,
            Regex("success\\.value\\(entryGeneration\\)").findAll(worker).count())
        assertTrue(worker.contains("finally {") && worker.contains("attempt.cancel()"))
        assertTrue(text.contains("onDispose { lifecycle.removeObserver(observer); revoke() }"))
        assertTrue(text.contains("Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP"))
    }
}
