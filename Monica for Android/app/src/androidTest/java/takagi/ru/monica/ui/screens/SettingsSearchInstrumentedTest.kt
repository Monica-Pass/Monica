package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.printToString
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
            onNavigateToDeveloperSettings = { nav.navigate(Screen.DeveloperSettings.route) },
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
            val resultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides resultRegistryOwner,
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
                            composable(Screen.DeveloperSettings.route) {
                                DeveloperSettingsScreen(model, onNavigateBack = { nav.popBackStack() }, onNavigateToLogs = {})
                            }
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
                            composable(Screen.BottomNavSettings.route) { BottomNavSettingsScreen(model, { nav.popBackStack() }) }
                            composable(Screen.Extensions.route) { ExtensionsScreen(onNavigateBack = { nav.popBackStack() }, isPlusActivated = plus) }
                            composable(Screen.FolderBackup.route) { FolderBackupScreen({ nav.popBackStack() }, {}) }
                            composable(Screen.SyncBackup.route) { SyncBackupScreen(onNavigateBack = { nav.popBackStack() }) }
                            composable(Screen.PageAdjustmentCustomization.route) {
                                PageAdjustmentCustomizationScreen(model, { nav.popBackStack() }, {}, {}, {}, {}, {})
                            }
                            composable(Screen.AddButtonCustomization.route) { AddButtonCustomizationScreen(model, { nav.popBackStack() }) }
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

    private fun recordSearchFailure(label: String, block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            File(context.filesDir, "settings-search-failure-$label.txt").writeText(error.stackTraceToString())
            throw error
        }
    }


    @Test fun everyDefinitionIsSearchableInEverySupportedLanguageWithoutReadingUserData() {
        val tags = listOf("en", "zh-CN", "zh-Hant-HK", "zh-Hant-TW", "lzh", "vi", "ja", "ko", "de", "fr", "es", "it", "ru", "pl", "pt-BR", "pt-PT")
        for (tag in tags) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            })
            val resourcesOnly = object : android.content.ContextWrapper(localized) {
                override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                    error("Search must not read preferences: $name")
                override fun getFilesDir(): File = error("Search must not read stored user data")
                override fun getDatabasePath(name: String): File = error("Search must not read databases: $name")
            }
            for (plus in listOf(false, true)) {
                val entries = settingsSearchEntries(resourcesOnly, plus)
                val definitions = Setting.entries.filter { !it.legacyOnly && (!it.plusOnly || plus) }
                assertEquals("$tag: every UI definition must be indexed", definitions.size, entries.size)
                assertEquals(entries.size, entries.distinctBy { it.id }.size)
                for (setting in definitions) {
                    val id = localized.resources.getResourceEntryName(setting.titleRes)
                    val title = localized.getString(setting.titleRes)
                    val result = entries.single { it.id == id }
                    assertEquals(title, result.title)
                    assertTrue("$tag: missing title $id", searchSettings(entries, title).any { it.id == id })
                    if (result.summary.isNotBlank()) assertTrue("$tag: missing description $id",
                        searchSettings(entries, result.summary).any { it.id == id })
                    assertTrue(result.route.isNotBlank())
                }
            }
        }
    }

    @Test fun folderBackupSearchOpensUnconfiguredPageWithoutChangingSettings() {
        check(context.packageName.contains("folderbackup"))
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val security = takagi.ru.monica.security.SecurityManager(context)
            if (!security.isMasterPasswordSet()) check(security.setMasterPassword("Synthetic local master 332!"))
            check(security.unlockVaultWithPassword("Synthetic local master 332!"))
            takagi.ru.monica.security.SessionManager.markUnlocked()
            takagi.ru.monica.backup.FolderBackupRunner.foreground = true
            security.removeProtectedString("folder_backup_v1")
        }
        show(large = true)
        query("文件夹")
        compose.onNodeWithTag("settings_search_result_folder_backup_title").performClick()
        compose.onNodeWithTag("folder_backup_password").performScrollTo().assertIsDisplayed()
        assertEquals(Screen.FolderBackup.route, nav.currentDestination?.route)
        compose.onNodeWithTag("cloud_backup_back").performClick()
        compose.onNodeWithTag("settings_search_input").assertTextContains("文件夹")
        assertEquals(0, destructiveCalls)
    }

    @Test fun previouslyOmittedSettingsAndProtectedChildHaveTheirOwnResults() {
        val entries = settingsSearchEntries(context)
        for (id in listOf("autofill_keep_unlocked", "autofill_v2_blacklist", "autofill_v2_default_keepass_title",
            "autofill_v2_default_bitwarden_title", "autofill_v2_default_mdbx_title", "lan_title",
            "hyperos_hook_reset", "hyperos_drift_title", "update_auto_check", "icon_settings_password_page_title")) {
            val entry = entries.singleOrNull { it.id == id }
            assertTrue("Missing setting: $id", entry != null)
            assertTrue(searchSettings(entries, entry!!.title).any { it.id == id })
        }
        assertEquals(R.string.developer_settings, entries.single { it.id == "hyperos_hook_reset" }.focusTitleRes)
        assertEquals(Screen.Settings.route, entries.single { it.id == "hyperos_hook_reset" }.route)
    }

    @Test fun hiddenConditionalSettingLocatesItsParentWithoutEnablingIt() {
        val before = runBlocking { manager.settingsFlow.first() }.autofillAuthRequired
        try {
            runBlocking { manager.updateAutofillAuthRequired(false) }
            show()
            val zh = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
            query(zh.getString(R.string.autofill_keep_unlocked))
            compose.onNodeWithTag("settings_search_result_autofill_keep_unlocked").performScrollTo().performClick()
            compose.waitUntil(10_000) { nav.currentDestination?.route == Screen.AutofillSettings.route }
            compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
            assertEquals(false, runBlocking { manager.settingsFlow.first() }.autofillAuthRequired)
            capture("settings-search-hidden-child.png")
            compose.runOnIdle { nav.popBackStack() }
            compose.onNodeWithTag("settings_search_input").assertTextContains(zh.getString(R.string.autofill_keep_unlocked))
        } finally { runBlocking { manager.updateAutofillAuthRequired(before) } }
    }

    @Test fun hyperOsCatalogIncludesAliasesAndLocalizedChildTitles() {
        val childTitles = listOf(R.string.hyperos_diagnostics_title, R.string.hyperos_module_title,
            R.string.default_manager_title, R.string.autofill_hyperos_save_compatibility,
            R.string.reduce_animations, R.string.hyperos_hook_reset, R.string.hyperos_hook_settings,
            R.string.hyperos_hook_chooser, R.string.hyperos_hook_hybrid, R.string.hyperos_drift_title)
        for (tag in listOf("en", "zh-CN", "zh-Hant-HK", "zh-Hant-TW", "lzh", "vi", "ja", "ko", "de", "fr", "es", "it", "ru", "pl", "pt-BR", "pt-PT")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            })
            val entries = settingsSearchEntries(localized)
            val target = entries.singleOrNull { it.id == "hyperos_compatibility_title" }
            assertTrue("$tag: HyperOS compatibility is missing from the search catalog", target != null)
            assertEquals(Screen.Settings.route, target!!.route)
            assertEquals(R.string.developer_settings, target.focusTitleRes)
            assertTrue(target.path.contains(localized.getString(R.string.developer_settings)))
            val queries = listOf("hyperos", "  ＨＹＰＥＲＯＳ  ", "澎湃", "小米", "Xiaomi", "Redmi", "POCO", "LSPosed") +
                childTitles.map(localized::getString)
            for (query in queries) assertTrue("$tag: $query must find the protected HyperOS entry",
                searchSettings(entries, query).any { it.id == target.id })
            assertTrue(searchSettings(entries, "HyperOS nonexistent-setting-329").isEmpty())
        }
    }

    @Test fun hyperOsSearchLocatesProtectedEntryAndCancelPreservesQuery() = recordSearchFailure("cancel") {
        val before = runBlocking { manager.settingsFlow.first() }
        show()
        query("hyperos")
        compose.onNodeWithTag("settings_search_result_hyperos_compatibility_title").assertIsDisplayed()
        compose.onAllNodes(hasToggleableState()).assertCountEquals(0)
        capture("settings-search-hyperos-results.png")
        compose.onNodeWithTag("settings_search_result_hyperos_compatibility_title").performClick()
        compose.waitUntil(10_000) { nav.currentDestination?.route == Screen.Settings.route }
        val target = compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true)
        target.assertIsDisplayed().performClick()
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        compose.onNodeWithText(localized.getString(R.string.verify_identity)).assertIsDisplayed()
        File(context.filesDir, "settings-search-dialog.txt").writeText(compose.onNode(isDialog()).printToString())
        assertEquals(Screen.Settings.route, nav.currentDestination?.route)
        compose.onNodeWithTag("hyperos_compatibility_screen").assertDoesNotExist()
        // Dialog-owned resources use the host Activity locale; the page context is localized above.
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(before, runBlocking { manager.settingsFlow.first() })
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings_search_input").assertTextContains("hyperos")
    }

    @Test fun hyperOsSearchReachesRealPageAfterPasswordVerificationInLargeText() = recordSearchFailure("verified") {
        val adaptiveFixture = context.packageName in setOf(
            "takagi.ru.monica.adaptive20261119", "takagi.ru.monica.adaptivefd20261119")
        check(context.packageName.contains("settingssearchfix") || adaptiveFixture) { "Isolated search fixture required" }
        val fixturePassword = if (adaptiveFixture) "SyntheticAdaptive329" else "SyntheticSearchPassword329"
        val security = takagi.ru.monica.security.SecurityManager(context)
        if (security.isMasterPasswordSet()) {
            assertTrue(security.verifyMasterPassword(fixturePassword))
        } else {
            assertTrue(security.setMasterPassword(fixturePassword))
        }
        val before = runBlocking { manager.settingsFlow.first() }
        val preferences = takagi.ru.monica.autofill_ng.AutofillPreferences(context)
        val saveBefore = runBlocking { preferences.isHyperOsSaveCompatibilityEnabled.first() }
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        show(dark = true, large = true)
        query("澎湃 兼容")
        compose.onNodeWithTag("settings_search_result_hyperos_compatibility_title").assertIsDisplayed().performClick()
        compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.onNodeWithText(localized.getString(R.string.verify_identity)).assertIsDisplayed()
        File(context.filesDir, "settings-search-dialog.txt").writeText(compose.onNode(isDialog()).printToString())
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement(fixturePassword)
        compose.onNodeWithText(localized.getString(R.string.confirm)).performClick()
        compose.waitUntil(20_000) { nav.currentDestination?.route == Screen.DeveloperSettings.route }
        compose.onNodeWithTag("developer_hyperos_compatibility_entry").performScrollTo().performClick()
        compose.onNodeWithTag("hyperos_compatibility_screen").assertIsDisplayed()
        compose.onNodeWithTag("hyperos_save_compatibility_setting").performScrollTo().assertIsDisplayed()
        capture("settings-search-hyperos-dark-large-target.png")
        assertEquals(before, runBlocking { manager.settingsFlow.first() })
        assertEquals(saveBefore, runBlocking { preferences.isHyperOsSaveCompatibilityEnabled.first() })
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithTag("developer_hyperos_compatibility_entry").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings_search_input").assertTextContains("澎湃 兼容")
    }

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
