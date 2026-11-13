package takagi.ru.monica.passkey.hardware

import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.navigation.Screen
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.ui.screens.AutofillSettingsV2Screen
import takagi.ru.monica.ui.screens.LocalSettingsSearchNavigation
import takagi.ru.monica.ui.screens.SettingsSearchResults
import takagi.ru.monica.ui.screens.SettingsSearchTarget
import takagi.ru.monica.ui.screens.rememberSettingsSearchNavigation
import takagi.ru.monica.ui.screens.searchSettings
import takagi.ru.monica.ui.screens.settingsSearchEntries
import takagi.ru.monica.ui.theme.MonicaTheme
import java.io.File
import java.util.Locale

class HardwarePasskeyOptInUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var preference: HardwarePasskeyPreferenceFixture

    @Before fun setUp() {
        preference = HardwarePasskeyPreferenceFixture(context)
        preference.removeChoice()
        assertTrue(runBlocking { SecureStorageStartup.prepareForEntry(context) } is SecureStartupResult.Ready)
        // Wait out the emulator's launcher-to-app orientation transition before setContent;
        // otherwise Activity recreation removes the composition owned by the previous host.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5_000) {
            var ready = false
            compose.runOnUiThread {
                val host = compose.activity
                ready = host.lifecycle.currentState == Lifecycle.State.RESUMED && !host.isChangingConfigurations &&
                    !host.isDestroyed && !host.isFinishing && host.window.decorView.isAttachedToWindow &&
                    host.hasWindowFocus() && host.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            ready
        }
    }
    @After fun restore() = preference.restore()

    @Composable private fun HardwarePage(back: () -> Unit = {}) {
        val changes = remember { HardwarePasskeyPreferences.observe(context) }
        val enabled by changes.collectAsState(initial = HardwarePasskeyPreferences.isEnabled(context))
        HardwareKeyScreen(HardwareKeyUiState(featureEnabled = enabled), back, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {})
    }

    @Test fun bothRealPagesShareChoiceAndReturningDoesNotUndoIt() {
        var hardwarePage by mutableStateOf(false)
        var backs = 0
        compose.setContent { MonicaTheme {
            if (hardwarePage) HardwarePage { hardwarePage = false; backs++ }
            else AutofillSettingsV2Screen(onNavigateBack = { backs++ }, onNavigateToBlockedFields = {}, onNavigateToSaveBlockedTargets = {})
        } }
        val title = context.getString(R.string.hardware_key_enable)
        compose.onNodeWithText(context.getString(R.string.autofill_passkey_settings_title)).assertIsDisplayed()
        compose.onNodeWithText(title).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText(title).assertIsOn()
        compose.runOnIdle { assertTrue(HardwarePasskeyPreferences.isEnabled(context)); hardwarePage = true }
        compose.onNodeWithText(title).assertIsOn().performClick()
        compose.onNodeWithText(title).assertIsOff()
        compose.onNodeWithText(context.getString(R.string.hardware_key_find_usb)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.hardware_key_back)).performClick()
        compose.onNodeWithText(title).performScrollTo().assertIsOff()
        compose.runOnIdle { assertEquals(1, backs); assertFalse(HardwarePasskeyPreferences.isEnabled(context)) }
    }

    private fun verifyReadable(locale: String, scale: Float) {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(locale)); fontScale = scale
        }
        val localized = context.createConfigurationContext(configuration)
        var hardwarePage by mutableStateOf(true)
        var dark by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MonicaTheme(darkTheme = dark) {
                    if (hardwarePage) HardwarePage()
                    else AutofillSettingsV2Screen(onNavigateBack = {}, onNavigateToBlockedFields = {}, onNavigateToSaveBlockedTargets = {})
                }
            }
        }
        val title = localized.getString(R.string.hardware_key_enable)
        for (night in listOf(false, true)) {
            for (hardware in listOf(true, false)) {
                compose.runOnIdle { dark = night; hardwarePage = hardware }
                compose.onNodeWithText(title).performScrollTo().assertIsDisplayed().assertIsOff()
                compose.onNodeWithText(localized.getString(R.string.hardware_key_enable_description)).assertIsDisplayed()
                val prefix = "hardware-opt-in-$locale-$scale-${if (hardware) "manager" else "autofill"}-${if (night) "dark" else "light"}"
                screenshot("$prefix-disabled.png")
                compose.onNodeWithText(title).performClick().assertIsOn()
                screenshot("$prefix-enabled.png")
                compose.onNodeWithText(title).performClick().assertIsOff()
            }
        }
    }

    @Test fun chineseBothPagesRemainReadableInLightAndDarkThemes() = verifyReadable("zh-CN", 1f)
    @Test fun largeEnglishBothPagesKeepCompleteDescriptionAndReachableSwitch() = verifyReadable("en", 1.6f)

    @Test fun renamedEntryAndHardwareAliasesNavigateToActualSwitchWithoutToggling() {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = context.createConfigurationContext(configuration)
        val entries = settingsSearchEntries(localized)
        val renamed = searchSettings(entries, localized.getString(R.string.autofill_passkey_settings_title))
            .single { it.id == "autofill_passkey_settings_title" }
        assertEquals(Screen.AutofillSettings.route, renamed.route)
        assertEquals(0, renamed.focusTitleRes)
        for (query in listOf("物理密钥", "USB", "NFC", "FIDO", "passkey", "hardware security key")) {
            val result = searchSettings(entries, query).single { it.id == "hardware_key_enable" }
            assertEquals(Screen.AutofillSettings.route, result.route)
            assertEquals(R.string.hardware_key_enable, result.focusTitleRes)
            assertTrue(result.path.contains(localized.getString(R.string.autofill_passkey_settings_title)))
        }
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            val navigation = rememberSettingsSearchNavigation(nav)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources, LocalSettingsSearchNavigation provides navigation) {
                MonicaTheme {
                    NavHost(navController = nav, startDestination = "hardware-search-test") {
                        composable("hardware-search-test") {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                SettingsSearchResults(searchSettings(entries, "USB"), navigation.open)
                            }
                        }
                        composable(Screen.AutofillSettings.route) {
                            AutofillSettingsV2Screen(onNavigateBack = { nav.popBackStack() },
                                onNavigateToBlockedFields = {}, onNavigateToSaveBlockedTargets = {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("settings_search_result_hardware_key_enable").performScrollTo().performClick()
        compose.waitUntil(5_000) { nav.currentDestination?.route == Screen.AutofillSettings.route }
        // No explicit test scroll here: the production search anchor must bring the row into view.
        compose.waitUntil(5_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() &&
                compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).isDisplayed()
        }
        compose.onNodeWithText(localized.getString(R.string.hardware_key_enable)).assertIsDisplayed().assertIsOff()
        assertFalse(HardwarePasskeyPreferences.isEnabled(context))
        screenshot("hardware-opt-in-search-target.png")
        compose.onNodeWithContentDescription(localized.getString(R.string.autofill_settings_back)).performClick()
        compose.onNodeWithTag("settings_search_result_hardware_key_enable").assertIsDisplayed()
        assertFalse(HardwarePasskeyPreferences.isEnabled(context))
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
