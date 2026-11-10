package takagi.ru.monica.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ToolchainEncryptedImportCompatibilityInstrumentedTest {
    private lateinit var cases: ToolchainEncryptedImportCases

    @Before fun requireOwnedSyntheticProfileAndExactDebugArtifact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("toolchainSyntheticImport") == "yes")
        check(context.packageName in setOf("takagi.ru.monica.bwsync326", "takagi.ru.monica.bwsync326fd"))
        check(android.os.Process.myUid() / 100000 == 11)
        check(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val expected = requireNotNull(args.getString("toolchainTargetApkSha256"))
        check(expected.matches(Regex("[0-9a-f]{64}")))
        val digest = MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected)
        cases = ToolchainEncryptedImportCases(context)
    }

    @Test fun aegisIndependentEncryptedBackupImportsAllFields() = cases.aegisIndependentEncryptedBackupImportsAllFields()
    @Test fun aegisWrongPasswordAndAuthenticatedCorruptionReturnNoEntries() = cases.aegisWrongPasswordAndAuthenticatedCorruptionReturnNoEntries()
    @Test fun stratumModernIndependentArgon2BackupImportsAllFields() = cases.stratumModernIndependentArgon2BackupImportsAllFields()
    @Test fun stratumModernWrongPasswordTagAndTruncationReturnNoEntries() = cases.stratumModernWrongPasswordTagAndTruncationReturnNoEntries()
    @Test fun stratumLegacyIndependentPbkdf2BackupAndWrongPassword() = cases.stratumLegacyIndependentPbkdf2BackupAndWrongPassword()
    @Test fun stratumLegacyCbcCannotAuthenticateAnIvWhitespaceChange() = cases.stratumLegacyCbcCannotAuthenticateAnIvWhitespaceChange()
}
