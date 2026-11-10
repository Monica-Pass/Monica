package takagi.ru.monica.util

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ToolchainEncryptedImportCompatibilityTest {
    private fun cases() = ToolchainEncryptedImportCases(RuntimeEnvironment.getApplication())
    @Test fun aegisIndependentEncryptedBackupImportsAllFields() = cases().aegisIndependentEncryptedBackupImportsAllFields()
    @Test fun aegisWrongPasswordAndAuthenticatedCorruptionReturnNoEntries() = cases().aegisWrongPasswordAndAuthenticatedCorruptionReturnNoEntries()
    @Test fun stratumModernIndependentArgon2BackupImportsAllFields() = cases().stratumModernIndependentArgon2BackupImportsAllFields()
    @Test fun stratumModernWrongPasswordTagAndTruncationReturnNoEntries() = cases().stratumModernWrongPasswordTagAndTruncationReturnNoEntries()
    @Test fun stratumLegacyIndependentPbkdf2BackupAndWrongPassword() = cases().stratumLegacyIndependentPbkdf2BackupAndWrongPassword()
    @Test fun stratumLegacyCbcCannotAuthenticateAnIvWhitespaceChange() = cases().stratumLegacyCbcCannotAuthenticateAnIvWhitespaceChange()
}
