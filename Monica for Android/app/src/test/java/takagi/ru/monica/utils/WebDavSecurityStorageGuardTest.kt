package takagi.ru.monica.utils

import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavSecurityStorageGuardTest {

    @Test
    fun webDavHelper_doesNotPersistSensitiveConfigInPlainSharedPreferences() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt")

        assertFalse(source.contains("putString(KEY_PASSWORD, password)"))
        assertFalse(source.contains("putString(KEY_ENCRYPTION_PASSWORD, encryptionPassword)"))
        assertTrue(source.contains("securityManager.putProtectedString(SECURE_KEY_PASSWORD"))
        assertTrue(source.contains("securityManager.putProtectedString("))
        assertTrue(source.contains("SECURE_KEY_ENCRYPTION_PASSWORD"))
        assertTrue(source.contains("migrateLegacyConfigIfNeeded"))
    }

    @Test
    fun sensitiveLogs_doNotPrintUsernameOrUserEmail() {
        val webDavHelper = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt")
        val autofillAuth = projectFile("app/src/main/java/takagi/ru/monica/autofill_ng/AutofillAuthenticationActivity.kt")
        val securityManager = projectFile("app/src/main/java/takagi/ru/monica/security/SecurityManager.kt")

        assertFalse(webDavHelper.contains("Log.d(\"WebDavHelper\", \"Username: ${'$'}username\")"))
        assertFalse(webDavHelper.contains("user=${'$'}username"))
        assertFalse(autofillAuth.contains("Log.d(TAG, \"Username: ${'$'}usernameValue\")"))
        assertFalse(securityManager.contains("Bitwarden credential saved for user:"))
    }

    @Test
    fun backupCreation_doesNotUseHardcodedFallbackKeyForNewSensitiveExports() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt")

        assertTrue(source.contains("val backupEncryptPassword = BackupEncryptionPolicy.resolvePassword("))
        assertTrue(source.contains("warnings.add(strings.get(R.string.backup_credentials_skipped))"))
        assertFalse(source.contains("val backupEncryptPassword = if (enableEncryption && encryptionPassword.isNotEmpty())"))
    }

    @Test
    fun restoreCompatibility_keepsLegacyFallbackOnlyInRestoreHelpers() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt")

        assertTrue(source.contains("private const val LEGACY_WEBDAV_BACKUP_FALLBACK_KEY = \"Monica_WebDAV_Config_Key\""))
        assertTrue(source.contains("private fun decryptBackupValueWithLegacyFallback"))
        // Whitespace is a valid non-empty archive password, not a legacy fallback request.
        assertTrue(source.contains("if (decryptPassword.isNullOrEmpty())"))
        assertFalse(source.contains("if (decryptPassword.isNullOrBlank())"))
        assertTrue(source.contains("val resolvedDecryptPassword = if (isEncrypted)"))
        assertTrue(source.contains("restoreBitwardenVaultBackups("))
        assertTrue(source.contains("resolvedDecryptPassword,"))
    }

    @Test
    fun webDavHelper_defaultsMissingSchemeToHttps() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt")

        assertTrue(source.contains("\"https://${'$'}trimmed\""))
        assertFalse(source.contains("\"http://${'$'}trimmed\""))
    }

    private fun projectFile(relativePath: String): String {
        val start = Paths.get("").toAbsolutePath()
        var cursor = start
        while (cursor.parent != null) {
            val candidate = cursor.resolve(relativePath).toFile()
            if (candidate.exists()) {
                return candidate.readText()
            }
            cursor = cursor.parent
        }
        error("Project file not found from $start: $relativePath")
    }
}
