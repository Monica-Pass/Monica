package takagi.ru.monica.credentialexchange

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.transfer.*
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.PortableSecretExportException

class PortableBackupErrorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chineseLargeTextShowsItemAndPreservationMessage() = showFailure("zh-CN")
    @Test fun englishLargeTextShowsItemAndPreservationMessage() = showFailure("en")

    private fun showFailure(language: String) {
        val original = Locale.getDefault()
        val locale = Locale.forLanguageTag(language)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
        try {
            Locale.setDefault(locale)
            val title = "Synthetic archive account"
            val error = PortableSecretExportException(title, AEADBadTagException())
            val message = databaseExportErrorMessage(context, error)
            assertEquals(localized.getString(R.string.backup_sensitive_data_unreadable, title), message)
            assertFalse(error.message.orEmpty().contains(title))
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources,
                    LocalConfiguration provides localized.resources.configuration, LocalDensity provides Density(density.density, 1.5f)) {
                    MonicaTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            BackgroundTaskResultCard(ExportJobState("synthetic", "local", status = ExportJobStatus.FAILED, message = message))
                        }
                    }
                }
            }
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithText(localized.getString(R.string.close)).assertIsDisplayed().assertIsEnabled()
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.filesDir, "local-read-backup-$language.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { Locale.setDefault(original) }
    }

    @Test fun cancelledTaskIsNotLabelledFailed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MonicaTheme {
            BackgroundTaskResultCard(ExportJobState("synthetic", "local", status = ExportJobStatus.CANCELLED))
        } }
        compose.onNodeWithText(context.getString(R.string.transfer_cancelled)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.transfer_task_failed)).assertDoesNotExist()
    }

    @Test fun everySupportedLocaleFormatsTheItemWithoutFallingBackToEnglish() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
            .getString(R.string.backup_sensitive_data_unreadable, "Synthetic title")
        for (tag in listOf("zh", "zh-CN", "zh-TW", "zh-Hant", "lzh", "de", "es", "fr", "id", "it", "ja", "ko", "pl", "pt-PT", "pt-BR", "ru", "tr", "vi", "zh-NY", "zh-XB")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            val message = localized.getString(R.string.backup_sensitive_data_unreadable, "Synthetic title")
            assertTrue(tag, message.contains("Synthetic title"))
            assertNotEquals(tag, english, message)
            assertFalse(tag, message.contains("%1\$s"))
        }
    }
}
