package takagi.ru.monica.localization

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.core.AutofillDiagnostics
import takagi.ru.monica.autofill_ng.core.AutofillServiceChecker
import takagi.ru.monica.data.Language
import takagi.ru.monica.ui.screens.isPasswordDecryptError
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.EncryptionHelper
import takagi.ru.monica.utils.LocaleHelper

@RunWith(AndroidJUnit4::class)
class LocalizedRuntimeMessagesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var originalLocale: Locale
    private val languages = Language.entries.filter { it != Language.SYSTEM }

    @Before fun saveLocale() { originalLocale = Locale.getDefault() }
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }

    @Test fun aLongLivedResolverFollowsLanguageChanges() {
        val strings = AppLocaleStringResolver(context)
        languages.forEach { language ->
            val localized = LocaleHelper.setLocale(context, language)
            assertEquals(localized.getString(R.string.bitwarden_message_key_unavailable),
                strings.get(R.string.bitwarden_message_key_unavailable))
            assertEquals(localized.getString(R.string.steam_message_login_result, 89),
                strings.get(R.string.steam_message_login_result, 89))
        }
    }

    @Test fun autofillDiagnosticsFollowEveryLanguage() {
        val checker = AutofillServiceChecker(context)
        val diagnostics = AutofillDiagnostics(context)
        val cjk = Regex("[\\u3400-\\u9fff]")
        languages.forEach { language ->
            LocaleHelper.setLocale(context, language)
            val status = checker.checkServiceStatus()
            // Japanese legitimately uses kanji; Chinese and Classical Chinese
            // also use this range. Only apply the leftover-Chinese guard elsewhere.
            val usesCjk = language in setOf(Language.CHINESE, Language.TRADITIONAL_CHINESE, Language.NYA, Language.SNOW_LEOPARD, Language.CLASSICAL_CHINESE, Language.JAPANESE)
            (status.compatibilityIssues + status.recommendations).forEach {
                if (!usesCjk) assertFalse("$language: $it", cjk.containsMatchIn(it))
            }
            val report = diagnostics.exportLogs()
            assertTrue(report.contains("Monica"))
            if (!usesCjk) assertFalse("$language: $report", cjk.containsMatchIn(report))
        }
    }

    @Test fun backupEncryptionRoundTripIsIndependentOfTheUiLanguage() {
        val directory = File(context.cacheDir, "localization-backup-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = File(directory, "sample.zip")
            val encrypted = File(directory, "sample.enc.zip")
            val plaintext = "Synthetic backup: \u0000 English / 中文 / Polski / français".toByteArray(Charsets.UTF_8)
            original.writeBytes(plaintext)
            LocaleHelper.setLocale(context, Language.ENGLISH)
            val strings = AppLocaleStringResolver(context)
            EncryptionHelper.encryptFile(original, encrypted, "synthetic-test-password", strings).getOrThrow()
            assertTrue(EncryptionHelper.hasEncryptedFileHeader(encrypted))
            languages.forEach { language ->
                LocaleHelper.setLocale(context, language)
                val restored = File(directory, "restored-${language.name}.zip")
                EncryptionHelper.decryptFile(encrypted, restored, "synthetic-test-password", strings).getOrThrow()
                assertArrayEquals(plaintext, restored.readBytes())
                val wrongPassword = EncryptionHelper.decryptFile(encrypted, File(directory, "wrong-${language.name}.zip"), "wrong-password", strings)
                assertTrue(wrongPassword.isFailure)
                val message = requireNotNull(wrongPassword.exceptionOrNull()?.message)
                assertEquals(strings.get(R.string.crypto_message_password_or_corrupt), message)
                assertTrue(isPasswordDecryptError(message, strings))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
