package takagi.ru.monica.localization

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.SyncBlockReason
import takagi.ru.monica.bitwarden.sync.SyncExecutionOutcome
import takagi.ru.monica.bitwarden.sync.classifyBitwardenSyncError
import takagi.ru.monica.data.BackupReport
import takagi.ru.monica.data.ItemCounts
import takagi.ru.monica.steam.service.SteamLoginImportService
import takagi.ru.monica.ui.screens.isPasswordDecryptError
import takagi.ru.monica.ui.screens.isPasswordRequiredError
import takagi.ru.monica.utils.backupExportMessage

class LocalizedOperationMessagesTest {
    private val languages = listOf("en", "zh", "lzh", "vi", "ja", "ru", "ko", "de", "es", "fr", "pl")

    @Test fun bitwardenServerErrorsUseTheSelectedLanguage() {
        val messages = mapOf(
            "invalid_username_or_password" to R.string.bitwarden_message_bad_login,
            "Invalid New Device OTP" to R.string.bitwarden_message_code_expired,
            "Two-step token is invalid" to R.string.bitwarden_message_two_step_invalid,
            "New device verification required" to R.string.bitwarden_message_new_device,
            "captcha required" to R.string.bitwarden_message_challenge_missing,
            "captcha sitekey=example" to R.string.bitwarden_message_captcha,
            "503 service unavailable" to R.string.bitwarden_message_server_unavailable,
            "ARGON2_MEMORY_ALLOCATION_ERROR" to R.string.bitwarden_message_kdf_memory,
        )
        languages.forEach { language ->
            val strings = xmlTestStrings(language)
            messages.forEach { (raw, resource) ->
                assertEquals("$language: $raw", strings.get(resource), BitwardenRepository.parseErrorMessage(raw, strings))
            }
        }
    }

    @Test fun bitwardenUnknownErrorsNeverEchoRemoteBodiesOrSignedUrls() {
        val strings = xmlTestStrings("en")
        val generic = strings.get(R.string.bitwarden_account_operation_failed)
        for (remoteError in listOf("Remote detail ".repeat(30), "HTTP 418",
            "https://example.invalid/?token=synthetic-secret-marker")) {
            assertEquals(generic, BitwardenRepository.parseErrorMessage(remoteError, strings))
        }
        assertEquals(strings.get(R.string.bitwarden_message_unknown), BitwardenRepository.parseErrorMessage(null, strings))
    }

    @Test fun translatedSyncErrorsStillOfferUnlockOrSignIn() {
        languages.forEach { language ->
            val strings = xmlTestStrings(language)
            listOf(R.string.legacy_ui_vault_locked, R.string.bitwarden_message_key_unavailable).forEach { id ->
                val message = strings.get(id)
                assertEquals("$language: $message", SyncExecutionOutcome.Blocked(SyncBlockReason.VAULT_LOCKED, message),
                    classifyBitwardenSyncError(message, strings))
            }
            listOf(R.string.legacy_ui_reauthenticate, R.string.bitwarden_message_token_refresh_failed,
                R.string.bitwarden_message_token_unavailable).forEach { id ->
                val message = strings.get(id)
                assertEquals("$language: $message", SyncExecutionOutcome.Blocked(SyncBlockReason.AUTH_REQUIRED, message),
                    classifyBitwardenSyncError(message, strings))
            }
        }
    }

    @Test fun legacyAndNetworkSyncErrorsRemainRecognizable() {
        val strings = xmlTestStrings("en")
        assertEquals(SyncBlockReason.VAULT_LOCKED,
            (classifyBitwardenSyncError("Vault 未解锁", strings) as SyncExecutionOutcome.Blocked).reason)
        assertEquals(SyncBlockReason.AUTH_REQUIRED,
            (classifyBitwardenSyncError("Token 刷新失败，请重新登录", strings) as SyncExecutionOutcome.Blocked).reason)
        assertTrue(classifyBitwardenSyncError("java.net.SocketTimeoutException: timeout", strings) is SyncExecutionOutcome.RetryableError)
        assertTrue(classifyBitwardenSyncError("Malformed response", strings) is SyncExecutionOutcome.FatalError)
    }

    @Test fun aLanguageChangeCannotHideSkippedConnectionCredentials() {
        val english = xmlTestStrings("en")
        val report = BackupReport(
            success = true, totalItems = ItemCounts(passwords = 2, images = 3),
            successItems = ItemCounts(passwords = 2, images = 3), failedItems = emptyList(),
            warnings = listOf(english.get(R.string.backup_credentials_skipped)),
            connectionCredentialsSkipped = true
        )
        languages.forEach { language ->
            val strings = xmlTestStrings(language)
            assertEquals(strings.get(R.string.export_message_backup_summary, 2, 3) + "\n" +
                strings.get(R.string.backup_credentials_skipped), backupExportMessage(report, strings))
        }
    }

    @Test fun oldBackupReportsKeepTheirWarningAndReportsWithoutIssuesStayShort() {
        val strings = xmlTestStrings("en")
        val report = BackupReport(true, ItemCounts(), ItemCounts(), emptyList(), emptyList())
        assertEquals(strings.get(R.string.export_message_backup_summary, 0, 0), backupExportMessage(report, strings))
        val oldReport = report.copy(warnings = listOf("未导出 WebDAV 连接凭证，请使用加密备份"))
        assertEquals(strings.get(R.string.export_message_backup_summary, 0, 0) + "\n" +
            strings.get(R.string.backup_credentials_skipped), backupExportMessage(oldReport, strings))
    }

    @Test fun translatedBackupErrorsStillReopenThePasswordPrompt() {
        languages.forEach { language ->
            val strings = xmlTestStrings(language)
            assertTrue(language, isPasswordDecryptError(strings.get(R.string.crypto_message_password_or_corrupt), strings))
            assertTrue(language, isPasswordDecryptError(strings.get(R.string.crypto_message_decrypt_failed, "tag mismatch"), strings))
            assertTrue(language, isPasswordRequiredError(strings.get(R.string.backup_password_required), strings))
            assertFalse(language, isPasswordDecryptError(strings.get(R.string.crypto_message_file_too_small), strings))
            assertFalse(language, isPasswordRequiredError("I/O error", strings))
        }
    }

    @Test fun translatedSteamCodeErrorsDoNotCauseAnUnnecessaryTokenRefresh() {
        languages.forEach { language ->
            val strings = xmlTestStrings(language)
            val service = SteamLoginImportService(strings)
            listOf(65, 88, 89).forEach { code ->
                val error = requireNotNull(service.mapEresultToMessage(code))
                assertTrue("$language: EResult=$code", service.isInvalidCodeError(error))
            }
            assertTrue(language, service.isInvalidCodeError(requireNotNull(service.mapTwoFactorStatusToMessage(89))))
            assertTrue(language, service.isInvalidCodeError(strings.get(R.string.steam_message_activation_code_invalid)))
            assertFalse(language, service.isInvalidCodeError(requireNotNull(service.mapEresultToMessage(5))))
            assertFalse(language, service.isInvalidCodeError(requireNotNull(service.mapTwoFactorStatusToMessage(15))))
        }
    }

    @Test fun steamKeepsProtocolCodesAndSuccessfulResultsIntact() {
        val strings = xmlTestStrings("en")
        val service = SteamLoginImportService(strings)
        assertNull(service.mapEresultToMessage(null))
        assertNull(service.mapEresultToMessage(1))
        assertNull(service.mapTwoFactorStatusToMessage(null))
        assertNull(service.mapTwoFactorStatusToMessage(1))
        assertEquals(strings.get(R.string.steam_message_login_result, 999), service.mapEresultToMessage(999))
        assertEquals(strings.get(R.string.steam_message_request_status, 999), service.mapTwoFactorStatusToMessage(999))
        assertTrue(service.isInvalidCodeError("EResult=65"))
        assertTrue(service.isInvalidCodeError("status=89"))
        assertTrue(service.isInvalidCodeError("验证码无效或已过期"))
        assertFalse(service.isInvalidCodeError("Network unavailable"))
    }
}
