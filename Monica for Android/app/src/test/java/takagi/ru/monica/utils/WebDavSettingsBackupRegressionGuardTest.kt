package takagi.ru.monica.utils

import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavSettingsBackupRegressionGuardTest {

    @Test
    fun portableAppSettingsDoNotDependOnWebDavConnectionBackupOptIn() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt")
        val createBackupBody = source.substringAfter("suspend fun createBackupZip(")
            .substringBefore("// KeePass WebDAV 已下线")
        val portableSettingsCall = createBackupBody.indexOf("writePortableAppSettingsBackup(")
        val connectionConfigGate = createBackupBody.indexOf(
            "if (preferences.includeWebDavConfig && isConfigured())"
        )

        assertTrue(portableSettingsCall >= 0)
        assertTrue(connectionConfigGate >= 0)
        assertTrue(portableSettingsCall < connectionConfigGate)

        val portableBackupHelper = source.substringAfter(
            "private suspend fun writePortableAppSettingsBackup("
        ).substringBefore("private suspend fun writeConnectionConfigBackup(")
        assertTrue(portableBackupHelper.contains("page_adjustment_settings.json"))
        assertTrue(portableBackupHelper.contains("security_questions.json"))
        assertTrue(portableBackupHelper.contains("backupEncryptPassword"))
        assertTrue(portableBackupHelper.contains("SecurityQuestionsBackupEncryptionRequiredException"))
        assertFalse(portableBackupHelper.contains("WebDavConnectionBackupEntry"))
        assertFalse(portableBackupHelper.contains("BitwardenVaultsBackupEntry"))
    }

    @Test
    fun webDavPageSettingsKeepSteamDockAndMiniProfilePreferences() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt")
        val backupModel = source.substringAfter("private data class PageAdjustmentSettingsBackupEntry(")
            .substringBefore("private data class BitwardenVaultBackupEntry(")

        assertTrue(backupModel.contains("val bottomNavVisibilitySteam: Boolean = false"))
        assertTrue(backupModel.contains("val steamMiniProfileBackgroundEnabled: Boolean = false"))
        assertFalse(backupModel.contains("isPlusActivated"))

        val snapshotMapping = source.substringAfter(
            "private fun PageAdjustmentSettingsSnapshot.toBackupEntry()"
        ).substringBefore("private suspend fun writePortableAppSettingsBackup(")
        assertTrue(snapshotMapping.contains("bottomNavVisibilitySteam = bottomNavVisibilitySteam"))
        assertTrue(
            snapshotMapping.contains(
                "steamMiniProfileBackgroundEnabled = steamMiniProfileBackgroundEnabled"
            )
        )
        assertTrue(
            Regex("bottomNavVisibilitySteam\\s*=\\s*pageAdjustmentBackup\\.bottomNavVisibilitySteam").containsMatchIn(source)
        )
        assertTrue(
            Regex("steamMiniProfileBackgroundEnabled\\s*=\\s*pageAdjustmentBackup\\.steamMiniProfileBackgroundEnabled").containsMatchIn(source)
        )
    }

    @Test
    fun restoredPlusPreferencesStayInactiveUntilPlusIsActivatedLocally() {
        val steamSource = projectFile("app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt")
        val totpSource = projectFile("app/src/main/java/takagi/ru/monica/ui/components/TotpCodeCard.kt")
        val totpListSource = projectFile("app/src/main/java/takagi/ru/monica/ui/totp/TotpListContent.kt")
        val mainSource = projectFile("app/src/main/java/takagi/ru/monica/MainActivity.kt")

        assertTrue(
            Regex(
                "appSettings\\.isPlusActivated\\s*&&\\s*" +
                    "appSettings\\.steamMiniProfileBackgroundEnabled"
            ).containsMatchIn(steamSource)
        )
        assertTrue(totpSource.contains("settings.isPlusActivated &&"))
        assertTrue(totpListSource.contains("appSettings.validatorVibrationEnabled"))
        assertTrue(totpSource.contains("settings.copyNextCodeWhenExpiring"))
        assertTrue(mainSource.contains("effectiveColorSchemeForPlusAccess("))
    }

    @Test
    fun webDavBackupRestoresSecurityQuestionsWithoutPlaintextOrDoubleHashing() {
        val webDavSource = projectFile("app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt")
        val securitySource = projectFile("app/src/main/java/takagi/ru/monica/security/SecurityManager.kt")

        assertTrue(webDavSource.contains("private data class SecurityQuestionsBackupEntry("))
        assertTrue(webDavSource.contains("val answer1Hash: String = \"\""))
        assertTrue(webDavSource.contains("val answer2Hash: String = \"\""))
        assertFalse(webDavSource.contains("val answer1: String = \"\""))
        assertFalse(webDavSource.contains("val answer2: String = \"\""))
        assertTrue(webDavSource.contains("exportSecurityQuestionsForBackup()"))
        assertTrue(webDavSource.contains("\"monica_config/security_questions.json\""))
        assertTrue(webDavSource.contains("restoreSecurityQuestionsFromBackup("))
        assertTrue(webDavSource.contains("SecurityQuestionsBackupEncryptionRequiredException"))

        assertTrue(securitySource.contains("fun exportSecurityQuestionsForBackup()"))
        assertTrue(securitySource.contains("fun restoreSecurityQuestionsFromBackup("))
        assertTrue(
            securitySource.contains(
                ".putString(SECURITY_QUESTION_1_ANSWER_KEY, normalized.answer1Hash)"
            )
        )
        assertTrue(
            securitySource.contains(
                ".putString(SECURITY_QUESTION_2_ANSWER_KEY, normalized.answer2Hash)"
            )
        )
    }

    private fun projectFile(relativePath: String): String {
        val start = Paths.get("").toAbsolutePath()
        var cursor = start
        while (cursor.parent != null) {
            val candidate = cursor.resolve(relativePath).toFile()
            if (candidate.exists()) return candidate.readText().replace("\r\n", "\n")
            cursor = cursor.parent
        }
        error("Project file not found from $start: $relativePath")
    }
}
