package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.navigation.Screen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.SettingsViewModel

class SettingsSearchInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = SettingsManager(context)
    private val model = SettingsViewModel(manager)
    private val store = androidx.lifecycle.ViewModelStore().apply { put("settings-search", model) }
    private lateinit var nav: NavHostController
    private lateinit var searchNavigation: SettingsSearchNavigation
    private var destructiveCalls = 0
    private var parentBackCalls = 0
    private lateinit var backDispatcher: OnBackPressedDispatcher

    @After fun finish() {
        compose.runOnIdle { store.clear() }
    }

    @Composable private fun Root(showTopBar: Boolean = true) {
        SettingsScreen(
            viewModel = model,
            onNavigateBack = { nav.popBackStack() },
            onResetPassword = { destructiveCalls++ },
            onSecurityQuestions = { destructiveCalls++ },
            onNavigateToMasterPasswordLocking = {},
            onClearAllData = { _, _, _, _, _, _ -> destructiveCalls++ },
            showTopBar = showTopBar,
        )
    }

    private fun show(dark: Boolean = false, large: Boolean = false, plus: Boolean = false, showTopBar: Boolean = true) {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            BackHandler { parentBackCalls++ }
            nav = rememberNavController()
            val navigation = rememberSettingsSearchNavigation(nav)
            searchNavigation = navigation
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, if (large) 1.5f else 1f),
                LocalSettingsSearchNavigation provides navigation,
            ) {
                MonicaTheme(darkTheme = dark) {
                    Box(Modifier.width(if (large) 320.dp else 390.dp).fillMaxHeight()) {
                        NavHost(navController = nav, startDestination = "search-test") {
                            composable("search-test") { Root(showTopBar) }
                            composable(Screen.Settings.route) { Root(showTopBar) }
                            composable(Screen.AutofillSettings.route) {
                                AutofillSettingsV2Screen(
                                    onNavigateBack = { nav.popBackStack() },
                                    onNavigateToBlockedFields = {},
                                    onNavigateToSaveBlockedTargets = {},
                                )
                            }
                            composable(Screen.IconSettings.route) { IconSettingsScreen(model, onNavigateBack = { nav.popBackStack() }) }
                            composable(Screen.PasswordFieldCustomization.route) { PasswordFieldCustomizationScreen(model, onNavigateBack = { nav.popBackStack() }) }
                            composable(Screen.MasterPasswordLockingSettings.route) {
                                MasterPasswordLockingSettingsScreen(model, { nav.popBackStack() }, {}, {})
                            }
                            composable(Screen.Extensions.route) { ExtensionsScreen(onNavigateBack = { nav.popBackStack() }, isPlusActivated = plus) }
                            composable(Screen.SyncBackup.route) { SyncBackupScreen(onNavigateBack = { nav.popBackStack() }) }
                            composable(Screen.PageAdjustmentCustomization.route) {
                                PageAdjustmentCustomizationScreen(model, { nav.popBackStack() }, {}, {}, {}, {}, {})
                            }
                            composable(Screen.PasswordListCustomization.route) { PasswordListCustomizationScreen(model, { nav.popBackStack() }) }
                            composable(Screen.PasswordCardAdjustment.route) { PasswordCardAdjustmentScreen(model, { nav.popBackStack() }) }
                            composable(Screen.AuthenticatorCardAdjustment.route) { AuthenticatorCardAdjustmentScreen(model, { nav.popBackStack() }) }
                        }
                    }
                }
            }
        }
    }

    private fun query(text: String) {
        compose.onNodeWithTag("settings_search_input").performScrollTo().performTextReplacement(text)
    }

    private fun hasToggleableState() = SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun keyboardResultNavigatesToSpecificRowWithoutTogglingAndReturnsToQuery() {
        val before = runBlocking { takagi.ru.monica.autofill_ng.AutofillPreferences(context).imeKeyboardOptions.first() }
        show()
        query("键盘 隐藏")
        compose.onNodeWithTag("settings_search_result_ime_hide_pin_preview_title").assertIsDisplayed()
        compose.onAllNodes(hasToggleableState()).assertCountEquals(0)
        capture("settings-search-keyboard-results.png")
        compose.onNodeWithTag("settings_search_result_ime_hide_pin_preview_title").performClick()
        compose.waitUntil(10_000) { nav.currentDestination?.route == Screen.AutofillSettings.route }
        compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
        capture("settings-search-keyboard-target.png")
        assertEquals(before, runBlocking { takagi.ru.monica.autofill_ng.AutofillPreferences(context).imeKeyboardOptions.first() })
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings_search_input").assertTextContains("键盘 隐藏")
        compose.onNodeWithTag("settings_search_result_ime_hide_pin_preview_title").assertIsDisplayed()
    }

    @Test fun destructiveResultOnlyLocatesTheOriginalEntry() {
        val before = runBlocking { manager.settingsFlow.first() }
        show()
        query("清空数据")
        compose.onNodeWithTag("settings_search_result_clear_all_data").performClick()
        compose.waitUntil(10_000) { nav.currentDestination?.route == Screen.Settings.route }
        compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, destructiveCalls)
        assertEquals(before, runBlocking { manager.settingsFlow.first() })
        compose.onNodeWithTag("settings_search_results").assertDoesNotExist()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithTag("settings_search_input").assertTextContains("清空数据")
    }

    @Test fun aliasSearchAndIconNavigationWorkInDarkNarrowLargeText() {
        show(dark = true, large = true)
        query("ＧＲＯＫ bot")
        compose.onAllNodes(hasToggleableState()).assertCountEquals(0)
        compose.onNodeWithTag("settings_search_result_icon_settings_app_icon_title").assertIsDisplayed()
        capture("settings-search-dark-large.png")
        compose.onNodeWithTag("settings_search_result_icon_settings_app_icon_title").performClick()
        compose.waitUntil(10_000) { nav.currentDestination?.route == Screen.IconSettings.route }
        compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun lazyPasswordFieldSettingsLocateTheRequestedField() {
        show()
        query("账号 用户名")
        compose.onNodeWithTag("settings_search_result_separate_username_account_title").performScrollTo().performClick()
        compose.waitUntil(10_000) { nav.currentDestination?.route == Screen.PasswordFieldCustomization.route }
        compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun editorStyleSearchOnlyNavigatesAndPreservesPreferenceInLargeText() {
        val before = runBlocking { manager.settingsFlow.first() }.passwordContentEditorEnabled
        show(dark = true, large = true)
        query("编辑样式")
        compose.onNodeWithTag("settings_search_result_entry_editor_style").assertDoesNotExist()
        assertEquals(before, runBlocking { manager.settingsFlow.first() }.passwordContentEditorEnabled)
    }

    @Test fun firstSystemFieldStillReceivesSearchFocusAfterStylePickerIsInserted() {
        val before = runBlocking { manager.settingsFlow.first() }
        show()
        query("账号 用户名")
        compose.onNodeWithTag("settings_search_result_separate_username_account_title").performScrollTo().performClick()
        compose.waitUntil(10_000) { nav.currentDestination?.route == Screen.PasswordFieldCustomization.route }
        compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
        capture("content-search-system-field.png")
        assertEquals(before, runBlocking { manager.settingsFlow.first() })
    }

    @Test fun localizedCatalogContainsUniqueConcreteItemsAndEmptySearchHasNoControls() {
        val zh = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        val entries = settingsSearchEntries(zh)
        val plusEntries = settingsSearchEntries(zh, isPlusActivated = true)
        val plusOnlyIds = setOf("validator_vibration", "copy_next_code_when_expiring")
        val baseIds = entries.map { it.id }.toSet()
        assertTrue(baseIds.intersect(plusOnlyIds).isEmpty())
        assertEquals(baseIds + plusOnlyIds, plusEntries.map { it.id }.toSet())
        listOf(entries, plusEntries).forEach { catalog ->
            assertEquals(catalog.size, catalog.distinctBy { it.id }.size)
            assertTrue(catalog.none { it.id.startsWith("dedup_") || it.route == "dedup_engine" })
            assertEquals(Screen.Extensions.route, catalog.single { it.id == "smart_deduplication" }.route)
            listOf("webdav_backup", "onedrive_backup_title", "sync_backup_bitwarden_sync_title",
                "local_keepass_database", "mdbx_ui_manager_entry_title", "export_data", "import_data").forEach { id ->
                assertEquals(id, Screen.SyncBackup.route, catalog.single { it.id == id }.route)
            }
        }
        assertEquals(Screen.AutofillSettings.route, entries.single { it.id == "ime_hide_pin_preview_title" }.route)
        assertEquals(Screen.Settings.route, entries.single { it.id == "developer_settings" }.route)
        show()
        query("不可能存在的设置项")
        compose.onAllNodes(hasToggleableState()).assertCountEquals(0)
        capture("settings-search-empty.png")
    }

    @Test fun backClosesSearchBeforeDelegatingToTheParentPage() {
        show(showTopBar = false)
        query("键盘 隐藏")
        compose.onNodeWithTag("settings_search_close").assertIsDisplayed()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings_search_input")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .assertIsNotFocused()
        compose.onNodeWithTag("settings_search_results").assertDoesNotExist()
        compose.onNodeWithTag("settings_search_close").assertDoesNotExist()
        assertEquals("search-test", nav.currentDestination?.route)
        assertEquals(0, parentBackCalls)
        capture("settings-search-back-closed.png")
        compose.runOnIdle { backDispatcher.onBackPressed() }
        assertEquals(1, parentBackCalls)
    }

    @Test fun emptyFocusedSearchAndWhitespaceCanBeClosedWithoutLeavingSettings() {
        show(showTopBar = false)
        compose.onNodeWithTag("settings_search_input").performScrollTo().performClick()
        compose.onNodeWithTag("settings_search_close").assertIsDisplayed()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings_search_input").assertIsNotFocused()
        compose.onNodeWithTag("settings_search_close").assertDoesNotExist()
        query("   ")
        compose.onNodeWithTag("settings_search_close").performClick()
        compose.onNodeWithTag("settings_search_input")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .assertIsNotFocused()
        assertEquals("search-test", nav.currentDestination?.route)
        assertEquals(0, parentBackCalls)
    }

    @Test fun searchBackArrowRemainsReachableInDarkNarrowLargeText() {
        show(dark = true, large = true, showTopBar = false)
        query("ＧＲＯＫ bot")
        capture("settings-search-back-dark-large.png")
        compose.onNodeWithTag("settings_search_close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings_search_input")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .assertIsNotFocused()
        compose.onNodeWithTag("settings_search_results").assertDoesNotExist()
        assertEquals(0, parentBackCalls)
    }

    @Test fun everyIndexedSpecificSettingHasOneVisibleTargetOnItsRealPage() {
        val zh = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        show(plus = true)
        val failures = mutableListOf<String>()
        val entries = settingsSearchEntries(zh, isPlusActivated = true).filter { it.focusTitleRes != 0 }
        for (entry in entries) {
            compose.runOnIdle { searchNavigation.open(entry) }
            try {
                compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
            } catch (error: AssertionError) {
                failures += "${entry.id}: ${error.message}"
                capture("settings-search-failure-${entry.id}.png")
            }
            compose.runOnIdle { nav.popBackStack() }
        }
        assertEquals(0, destructiveCalls)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
