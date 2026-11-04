package takagi.ru.monica.localization

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
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
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.model.Statement
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

@RunWith(Parameterized::class)
class LocalizedBitwardenUiInstrumentedTest(private val language: Language) {
    private val compose = createAndroidComposeRule<LocaleTestActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = SettingsManager(context)
    private val viewModels = ViewModelStore()
    private lateinit var localized: Context

    private val localeRule = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val originalLocale = Locale.getDefault()
                val originalLanguage = runBlocking { settings.settingsFlow.first().language }
                runBlocking { settings.updateLanguage(language) }
                try {
                    base.evaluate()
                } finally {
                    runBlocking { settings.updateLanguage(originalLanguage) }
                    Locale.setDefault(originalLocale)
                }
            }
        }
    }

    // The app sets the locale in attachBaseContext before constructing windows.
    // Updating only CompositionLocals leaves Dialog/Popup windows in the host's
    // original language, so persist the setting before the activity rule runs.
    @get:Rule val rules: TestRule = RuleChain.outerRule(localeRule).around(compose)

    @Before fun checkLocalizedHost() {
        assertEquals(language, LocaleHelper.getCurrentLanguage(compose.activity))
        localized = compose.activity
    }

    @After fun clearViewModels() {
        compose.runOnIdle { viewModels.clear() }
    }

    @Test fun settingsAndSelfHostedLoginUseTheSelectedLanguage() {
        val model = BitwardenViewModel(compose.activity.application)
        viewModels.put("bitwarden-all-locales", model)
        var login by mutableStateOf(false)
        show {
            if (login) BitwardenLoginScreen(model, { login = false }, {})
            else BitwardenSettingsScreen(model, {}, { login = true }, {}, {})
        }
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_bitwarden_settings)).assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_connected_vaults)).assertIsDisplayed()
        if (language != Language.ENGLISH) {
            compose.onAllNodesWithText("Bitwarden settings").assertCountEquals(0)
        }
        compose.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText(localized.getString(R.string.legacy_ui_sync_settings)))
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_sync_settings)).assertIsDisplayed()
        screenshot("settings")
        compose.onNodeWithContentDescription(localized.getString(R.string.legacy_ui_add_vault)).performClick()
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_bitwarden_login)).assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_bitwarden_server_us)).performClick()
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_bitwarden_server_self_hosted)).performClick()
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_tls_settings)).performScrollTo().performClick()
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_tls_ca_pem)).performScrollTo().assertIsDisplayed()
        screenshot("self-hosted")
    }

    @Test fun unlockDialogKeepsValidationInTheSelectedLanguage() {
        var submitted: String? = null
        show { UnlockVaultDialog("locale@example.test", { submitted = it }, {}) }
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_unlock_vault)).assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.unlock)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("synthetic-ui-password")
        compose.onNodeWithText(localized.getString(R.string.unlock)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("synthetic-ui-password", submitted) }
        screenshot("unlock")
    }

    @Test fun syncSettingsAndHelpRemainReachableWithLargeText() {
        show(fontScale = 1.4f) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SyncSettingsCard(true, {}, true, {}, true, {})
                AboutCard()
            }
        }
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_auto_sync)).assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_never_lock_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.legacy_ui_supported_servers)).performScrollTo().assertIsDisplayed()
        screenshot("large-text")
    }

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        val configuration = Configuration(localized.resources.configuration).apply { this.fontScale = fontScale }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale),
            ) {
                MonicaTheme { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun screenshot(page: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(context.getExternalFilesDir(null), "bitwarden-${language.name.lowercase(Locale.ROOT)}-$page.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = Language.entries.filter { it != Language.SYSTEM }.map { arrayOf<Any>(it) }
    }
}
