package takagi.ru.monica.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperVerificationIntegrationGuardTest {
    private fun projectFile(path: String): File {
        val workingDirectory = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(workingDirectory) { it.parentFile }
            .flatMap { directory ->
                sequenceOf(
                    File(directory, path),
                    File(directory, "Monica for Android/$path"),
                )
            }
            .firstOrNull(File::isFile)
            ?: error("Unable to resolve project file '$path' from $workingDirectory")
    }

    @Test
    fun `autofill and ime entry points apply developer bypass policy`() {
        val files = listOf(
            "app/src/main/java/takagi/ru/monica/autofill_ng/MonicaAutofillServiceNg.kt",
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillUnlockActivity.kt",
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillCipherCallbackActivity.kt",
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPickerActivityV2.kt",
            "app/src/main/java/takagi/ru/monica/autofill_ng/BiometricAuthActivity.kt",
            "app/src/main/java/takagi/ru/monica/ime/ImeUnlockActivity.kt",
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt",
        )

        files.forEach { path ->
            val source = projectFile(path).readText()
            assertTrue("Missing developer verification policy in $path", source.contains("DeveloperVerificationPolicy"))
        }
    }

    @Test
    fun `shared confirmation dialog can omit identity controls`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/M3IdentityVerifyDialog.kt"
        ).readText()

        assertTrue(source.contains("requireIdentityVerification: Boolean = true"))
        assertTrue(source.contains("if (requireIdentityVerification)"))
    }

    @Test
    fun `secondary password confirmation stages bounded verification before foreground commit`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/SecondaryPasswordVerificationDialog.kt"
        ).readText().replace("\r\n", "\n")
        val worker = source.substringAfter("work = scope.launch {").substringBefore("confirmText = confirmText")
        assertTrue("Secondary authentication must use a cancellable bounded worker and staged credentials",
            worker.contains("BoundedSecureDataRead.readChecked(isCurrent = {") &&
                worker.contains("current() && attempt.isCurrent()") &&
                worker.contains("manager.verifyMasterPassword(submitted, attempt) { ensureActive() }"))
        assertTrue("A successful worker cannot grant a dismissed, replaced or background request",
            worker.contains("if (!current() || pending !== attempt) return@launch") &&
                worker.contains("if (!foreground() || !requestCurrent.value()) { close(); return@launch }") &&
                worker.contains("if (verified == true && attempt.commit())"))
        assertTrue("The current request is consumed before its action and every attempt is cleaned up",
            worker.indexOf("active.set(false)") < worker.indexOf("success.value(entryGeneration)") &&
                worker.contains("finally {") && worker.contains("attempt.cancel()"))
        assertFalse("Secondary confirmation cannot enter the no-password unlock shortcut",
            source.contains("unlockVaultWithPassword("))
    }

    @Test
    fun `secondary confirmation revokes on lifecycle lock and disposal without saving password text`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/SecondaryPasswordVerificationDialog.kt"
        ).readText()
        assertTrue(source.contains("active.get() && manager.isDataReadCurrent(entryGeneration)"))
        assertTrue(source.contains("Lifecycle.State.RESUMED") && source.contains("?.isInteractive == true") &&
            source.contains("?.isKeyguardLocked == false"))
        assertTrue(source.contains("Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP") &&
            source.contains("onDispose { lifecycle.removeObserver(observer); revoke() }") &&
            source.contains("if (generation != entryGeneration || !current()) close()"))
        val revoke = source.substringAfter("fun revoke() {").substringBefore("fun close()")
        assertTrue(revoke.contains("active.set(false)") && revoke.contains("pending?.cancel()") &&
            revoke.contains("work?.cancel()") && revoke.contains("password = \"\""))
        assertTrue(source.contains("requireIdentityVerification = requirePassword && !busy") &&
            source.contains("savePasswordTextState = false"))
        assertFalse(source.contains("rememberSaveable"))
    }

    @Test
    fun `only an explicit skip policy bypasses password work and it keeps the final request gate`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/SecondaryPasswordVerificationDialog.kt"
        ).readText()
        assertTrue(source.contains("requirePassword: Boolean = true"))
        assertTrue(source.contains("confirmEnabled && !busy && (!requirePassword || password.isNotEmpty()) && current() && foreground() && requestCurrent.value()"))
        val skip = source.substringAfter("if (!requirePassword) {").substringBefore("} else {")
        assertTrue(skip.contains("active.set(false)") && skip.contains("ended = true") &&
            skip.contains("success.value(entryGeneration)"))
        assertFalse(skip.contains("unlockVault") || skip.contains("attempt.commit()"))
        assertFalse("A malformed or unreadable store cannot silently choose the skip branch",
            source.contains("isMasterPasswordSet("))
    }

    @Test
    fun `migrated confirmation callers do not run password verification directly`() {
        val paths = listOf(
            "ui/screens/SettingsScreen.kt",
            "ui/screens/ExportDataScreen.kt",
            "ui/screens/ForgotPasswordScreen.kt",
            "ui/screens/KeePassNativeDatabaseSettingsScreen.kt",
            "steam/ui/SteamScreen.kt",
            "ui/screens/AddEditNoteScreen.kt",
            "ui/screens/PasswordDetailScreen.kt",
            "ui/common/dialog/DeleteConfirmDialog.kt",
            "passkey/PasskeyCreateActivity.kt",
            "passkey/PasskeyAuthActivity.kt",
        )
        paths.forEach { path ->
            val source = projectFile("app/src/main/java/takagi/ru/monica/$path").readText()
            assertTrue("Missing shared cancellable confirmation in $path", source.contains("SecondaryPasswordVerificationDialog("))
            assertFalse("Synchronous password work returned to $path",
                Regex("(?:verifyMasterPassword|unlockVaultWithPassword)\\s*\\(").containsMatchIn(source))
        }
    }
}
