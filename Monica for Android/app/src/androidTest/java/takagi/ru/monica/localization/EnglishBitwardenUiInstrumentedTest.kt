package takagi.ru.monica.localization

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.ui.AboutCard
import takagi.ru.monica.bitwarden.ui.BitwardenLoginScreen
import takagi.ru.monica.bitwarden.ui.BitwardenSettingsScreen
import takagi.ru.monica.bitwarden.ui.SyncSettingsCard
import takagi.ru.monica.bitwarden.ui.UnlockVaultDialog
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.Language
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager

@RunWith(AndroidJUnit4::class)
class EnglishBitwardenUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = SettingsManager(context)
    private val viewModels = ViewModelStore()
    private lateinit var originalLocale: Locale
    private lateinit var originalLanguage: Language

    @Before fun saveLanguage() = runBlocking {
        originalLocale = Locale.getDefault()
        originalLanguage = settings.settingsFlow.first().language
        settings.updateLanguage(Language.ENGLISH)
    }

    @After fun restoreLanguage() {
        compose.runOnIdle { viewModels.clear() }
        runBlocking { settings.updateLanguage(originalLanguage) }
        Locale.setDefault(originalLocale)
    }

    @Test fun settingsAndSelfHostedLoginDisplayEnglish() {
        val model = BitwardenViewModel(compose.activity.application)
        viewModels.put("bitwarden-localization", model)
        var login by mutableStateOf(false)
        show(Language.ENGLISH) {
            if (login) BitwardenLoginScreen(model, { login = false }, {})
            else BitwardenSettingsScreen(model, {}, { login = true }, {}, {})
        }
        compose.onNodeWithText("Bitwarden settings").assertIsDisplayed()
        compose.onNodeWithText("Connected vaults").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Sync settings"))
        compose.onNodeWithText("Sync settings").assertIsDisplayed()
        screenshot("english-bitwarden-settings")
        val english = localized(Language.ENGLISH)
        compose.onNodeWithContentDescription(english.getString(R.string.legacy_ui_add_vault)).performClick()
        compose.onNodeWithText("Log in to Bitwarden").assertIsDisplayed()
        compose.onNodeWithText("United States").performClick()
        compose.onNodeWithText("Self-hosted").performClick()
        compose.onNodeWithText(english.getString(R.string.legacy_ui_tls_settings)).performScrollTo().performClick()
        compose.onNodeWithText(english.getString(R.string.legacy_ui_tls_ca_pem)).performScrollTo().assertIsDisplayed()
        assertNoChineseText()
        screenshot("english-bitwarden-self-hosted")
    }

    @Test fun unlockDialogRetainsValidationAndUsesEnglish() {
        var submitted: String? = null
        show(Language.ENGLISH) {
            UnlockVaultDialog("locale@example.test", { submitted = it }, {})
        }
        val english = localized(Language.ENGLISH)
        compose.onNodeWithText(english.getString(R.string.legacy_ui_unlock_vault)).assertIsDisplayed()
        compose.onNodeWithText(english.getString(R.string.unlock)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("synthetic-ui-password")
        compose.onNodeWithText(english.getString(R.string.unlock)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("synthetic-ui-password", submitted) }
        assertNoChineseText()
        screenshot("english-bitwarden-unlock")
    }

    @Test fun polishSettingsRemainReadableWithLargeText() {
        show(Language.POLISH, fontScale = 1.4f) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SyncSettingsCard(true, {}, true, {}, true, {})
                AboutCard()
            }
        }
        val polish = localized(Language.POLISH)
        compose.onNodeWithText(polish.getString(R.string.legacy_ui_auto_sync)).assertIsDisplayed()
        compose.onNodeWithText(polish.getString(R.string.legacy_ui_never_lock_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(polish.getString(R.string.legacy_ui_supported_servers)).performScrollTo().assertIsDisplayed()
        assertNoChineseText()
        screenshot("polish-bitwarden-large-text")
    }

    private fun localized(language: Language) = LocaleHelper.setLocale(context, language)

    private fun show(language: Language, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val localized = localized(language)
        val configuration = Configuration(localized.resources.configuration).apply { this.fontScale = fontScale }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale)
            ) {
                MonicaTheme {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    private fun assertNoChineseText() {
        val cjk = Regex("[\\u3400-\\u9fff]")
        compose.onAllNodes(SemanticsMatcher("Chinese UI text") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { cjk.containsMatchIn(it.text) } ||
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any(cjk::containsMatchIn)
        }, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
