package takagi.ru.monica.localization

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import takagi.ru.monica.R
import takagi.ru.monica.data.Language
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.main.navigation.BottomNavItem
import takagi.ru.monica.ui.main.navigation.DockLabel
import takagi.ru.monica.ui.main.navigation.shortLabelRes
import takagi.ru.monica.ui.screens.LanguageSelectionDialog
import takagi.ru.monica.ui.screens.QuickSetupScreen
import takagi.ru.monica.ui.screens.SecureStorageRecoveryScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache
import takagi.ru.monica.viewmodel.SettingsViewModel

@RunWith(Parameterized::class)
class PortugueseUiInstrumentedTest(private val selectedLanguage: Language, private val localeTag: String) {
    private val nativeName: String get() = if (selectedLanguage == Language.PORTUGUESE_BRAZIL) "Português (Brasil)" else "Português (Portugal)"

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{1}")
        fun regions() = listOf(
            arrayOf<Any>(Language.PORTUGUESE_PORTUGAL, "pt-PT"),
            arrayOf<Any>(Language.PORTUGUESE_BRAZIL, "pt-BR"),
        )
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = SettingsManager(context)
    private val store = ViewModelStore()
    private lateinit var originalLocale: Locale
    private lateinit var originalLanguage: Language
    private var originalConfiguration: Configuration? = null

    @Before fun saveLanguage() = runBlocking {
        originalLocale = Locale.getDefault()
        originalLanguage = manager.settingsFlow.first().language
    }

    @After fun restore() {
        compose.runOnIdle { store.clear() }
        runBlocking { manager.updateLanguage(originalLanguage) }
        originalConfiguration?.let { original ->
            compose.runOnUiThread {
                @Suppress("DEPRECATION")
                compose.activity.resources.updateConfiguration(original, compose.activity.resources.displayMetrics)
            }
        }
        Locale.setDefault(originalLocale)
    }

    @Test fun languageDialogDisplaysAndSelectsPortuguese() {
        var selected: Language? = null
        show {
            LanguageSelectionDialog(Language.FRENCH, { selected = it }, {})
        }
        compose.onNodeWithText("Idioma").assertIsDisplayed()
        compose.onNodeWithTag("language_options").performScrollToNode(hasTestTag("language_option_${selectedLanguage.name}"))
        compose.onNodeWithText(nativeName, useUnmergedTree = true).assertIsDisplayed().assertNoTextOverflow()
        compose.onNodeWithTag("language_option_${selectedLanguage.name}").performClick()
        compose.runOnIdle { assertEquals(selectedLanguage, selected) }
        screenshot("language-dialog-light")
    }

    @Test fun largeTextDialogKeepsPortugueseAndAllOtherLanguagesReachable() {
        var dismissed = false
        show(dark = true, fontScale = 1.6f) {
            LanguageSelectionDialog(selectedLanguage, {}, { dismissed = true })
        }
        compose.onNodeWithTag("language_options").performScrollToNode(hasTestTag("language_chinese_primary"))
        compose.onNodeWithTag("language_chinese_expand").performClick()
        Language.entries.forEach { language ->
            val tag = "language_option_${language.name}"
            compose.onNodeWithTag("language_options").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        compose.onNodeWithTag("language_options").performScrollToNode(hasTestTag("language_option_${selectedLanguage.name}"))
        compose.onNodeWithTag("language_option_${selectedLanguage.name}").assertIsSelected()
        val layout = compose.onNodeWithText(nativeName, useUnmergedTree = true).assertNoTextOverflow()
        assertEquals(1.6f, layout.layoutInput.density.fontScale, 0.001f)
        compose.onNodeWithText("Idioma", useUnmergedTree = true).assertNoTextOverflow()
        compose.onNodeWithContentDescription("Fechar").assertIsDisplayed()
        screenshot("language-dialog-dark-large")
        compose.onNodeWithContentDescription("Fechar").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun compactDockUsesReadableAbbreviationsAtLargeFontScale() = checkDefaultDock(1.5f)

    @Test fun compactDockKeepsWordsTogetherAtNormalFontScale() = checkDefaultDock(1f)

    private fun checkDefaultDock(fontScale: Float) {
        val tabs = listOf(
            BottomNavItem.Passwords, BottomNavItem.Authenticator, BottomNavItem.CardWallet,
            BottomNavItem.Generator, BottomNavItem.Notes, BottomNavItem.Settings,
        )
        var selected by mutableStateOf<BottomNavItem>(BottomNavItem.Passwords)
        show(dark = fontScale > 1f, fontScale = fontScale) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(360.dp).fillMaxHeight()) {
                    androidx.compose.material3.NavigationBar {
                        tabs.forEach { item ->
                            val label = androidx.compose.ui.res.stringResource(item.shortLabelRes())
                            NavigationBarItem(
                                selected = selected == item, onClick = { selected = item },
                                icon = { androidx.compose.material3.Icon(item.icon, null) },
                                label = { DockLabel(label) },
                            )
                        }
                    }
                }
            }
        }
        val portuguese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(localeTag))
        })
        tabs.forEach { item ->
            val label = portuguese.getString(item.shortLabelRes())
            val node = compose.onNodeWithText(label)
            node.assertIsDisplayed()
            val layout = compose.onNodeWithText(label, useUnmergedTree = true).assertNoTextOverflow()
            assertEquals(fontScale, layout.layoutInput.density.fontScale, 0.001f)
            assertEquals("Dock word must remain on one line: $label", 1, layout.lineCount)
            assertTrue("Dock respects a readable size: $label", layout.layoutInput.style.fontSize.value >= 10f)
            node.performClick()
            compose.runOnIdle { assertEquals(item, selected) }
        }
        screenshot(if (fontScale > 1f) "dock-360dp-large" else "dock-360dp-normal")
    }

    @Test fun optionalDockTabsFitAt320DpAndLargeFontScale() {
        val tabs = listOf(
            BottomNavItem.VaultV2, BottomNavItem.Passwords, BottomNavItem.Send,
            BottomNavItem.Passkey, BottomNavItem.Steam, BottomNavItem.Settings,
        )
        var selected by mutableStateOf<BottomNavItem>(BottomNavItem.Passwords)
        show(dark = true, fontScale = 1.5f) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(320.dp).fillMaxHeight()) {
                    androidx.compose.material3.NavigationBar {
                        tabs.forEach { item ->
                            val label = androidx.compose.ui.res.stringResource(item.shortLabelRes())
                            NavigationBarItem(
                                selected = selected == item, onClick = { selected = item },
                                icon = { androidx.compose.material3.Icon(item.icon, null) },
                                label = { DockLabel(label) },
                            )
                        }
                    }
                }
            }
        }
        val portuguese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(localeTag))
        })
        tabs.forEach { item ->
            val label = portuguese.getString(item.shortLabelRes())
            val node = compose.onNodeWithText(label)
            node.assertIsDisplayed()
            val layout = compose.onNodeWithText(label, useUnmergedTree = true).assertNoTextOverflow()
            assertEquals(1.5f, layout.layoutInput.density.fontScale, 0.001f)
            assertEquals("Dock word must remain on one line: $label", 1, layout.lineCount)
            assertTrue("Dock respects a readable size: $label", layout.layoutInput.style.fontSize.value >= 10f)
            node.performClick()
            compose.runOnIdle { assertEquals(item, selected) }
        }
        screenshot("dock-320dp-optional-large")
    }

    @Test fun recoveryConfirmationKeepsTheFullWarningReadableAtLargeText() {
        var recoveryCalls = 0
        show(dark = true, fontScale = 1.5f) {
            SecureStorageRecoveryScreen({}, {}, {}, onRecover = { recoveryCalls++; false })
        }
        compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput("synthetic-localization-only")
        compose.onNodeWithTag("secure_startup_recover").performClick()
        val localized = LocaleHelper.setLocale(context, selectedLanguage)
        compose.onNodeWithText(localized.getString(R.string.local_recovery_confirm_title), useUnmergedTree = true).assertNoTextOverflow()
        val warning = compose.onNodeWithText(localized.getString(R.string.local_recovery_confirm_description), useUnmergedTree = true)
        warning.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        warning.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertFalse("Recovery warning is truncated: $localeTag", layout.multiParagraph.didExceedMaxLines)
        assertFalse("Recovery warning is ellipsized: $localeTag", (0 until layout.lineCount).any(layout::isLineEllipsized))
        val visible = warning.fetchSemanticsNode().boundsInRoot
        val lineWidth = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
        assertTrue("Recovery warning horizontal clipping: $localeTag", lineWidth <= visible.width + 1f)
        if (layout.multiParagraph.height > visible.height + 1f) {
            val dialogScroll = hasScrollAction() and hasAnyAncestor(isDialog())
            compose.onAllNodes(dialogScroll).assertCountEquals(1)
            compose.onNode(dialogScroll).performTouchInput { swipeUp() }
        }
        compose.onNodeWithTag("secure_startup_recovery_confirm").assertIsDisplayed()
        compose.onNodeWithTag("secure_startup_recovery_cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Opening or cancelling never authorizes recovery", 0, recoveryCalls) }
        compose.onNodeWithTag("secure_startup_recovery_confirm").assertDoesNotExist()
    }

    @Test fun quickSetupPersistsPortugueseAndRefreshesItsWelcomeText() {
        runBlocking { manager.updateLanguage(Language.ENGLISH) }
        val viewModel = SettingsViewModel(manager)
        val securityManager = SecurityManager(context)
        store.put("portuguese-language-settings", viewModel)
        val english = LocaleHelper.setLocale(context, Language.ENGLISH)
        show(language = Language.ENGLISH) {
            val settings by viewModel.settings.collectAsState()
            val localized = remember(settings.language) { LocaleHelper.setLocale(context, settings.language) }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                QuickSetupScreen(
                    settingsViewModel = viewModel, securityManager = securityManager,
                    onSkip = {}, onFinish = {}, onOpenMasterPassword = {}, onOpenSecurityQuestions = {},
                    onOpenAutofillSettings = {}, onOpenBitwardenSettings = {}, onOpenWebDavBackup = {},
                    onOpenLocalKeePass = {}, onOpenImportData = {}, onOpenMonicaPlus = {},
                )
            }
        }
        compose.waitUntil(10_000) { viewModel.settings.value.language == Language.ENGLISH }
        compose.onNodeWithText(english.getString(R.string.qs_change)).performScrollTo().performClick()
        compose.onNodeWithTag("language_options").performScrollToNode(hasTestTag("language_option_${selectedLanguage.name}"))
        compose.onNodeWithTag("language_option_${selectedLanguage.name}").performClick()
        compose.waitUntil(10_000) { viewModel.settings.value.language == selectedLanguage }
        val portuguese = LocaleHelper.setLocale(context, selectedLanguage)
        compose.onNodeWithText(portuguese.getString(R.string.qs_welcome_heading), useUnmergedTree = true).performScrollTo().assertIsDisplayed().assertNoTextOverflow()
        compose.onNodeWithText(nativeName, useUnmergedTree = true).performScrollTo().assertIsDisplayed().assertNoTextOverflow()
        assertEquals(selectedLanguage, StartupLanguageCache.read(context))
        screenshot("quick-setup")
    }

    private fun show(language: Language = selectedLanguage, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val localized = LocaleHelper.setLocale(context, language)
        compose.runOnUiThread {
            val resources = compose.activity.resources
            originalConfiguration = Configuration(resources.configuration)
            val configuration = Configuration(resources.configuration).apply {
                setLocale(localized.resources.configuration.locales[0])
                this.fontScale = fontScale
            }
            // Dialogs and system composition locals must receive the actual Activity configuration.
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        compose.setContent {
            MonicaTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize()) { content() } }
        }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.assertNoTextOverflow(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single().also { layout ->
            // Compose's String semantics rebuilds a paragraph at the parent's maximum width,
            // but keeps the Text node's wrap-content size. Compare the actual line extents
            // with the visible node instead of treating empty paragraph space as overflow.
            val bounds = fetchSemanticsNode().boundsInRoot
            val visibleWidth = minOf(layout.size.width.toFloat(), bounds.width)
            val visibleHeight = minOf(layout.size.height.toFloat(), bounds.height)
            val lineWidth = (0 until layout.lineCount).maxOf { line ->
                layout.getLineRight(line) - layout.getLineLeft(line)
            }
            val truncated = layout.multiParagraph.didExceedMaxLines ||
                (0 until layout.lineCount).any(layout::isLineEllipsized) ||
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) < layout.layoutInput.text.text.trimEnd().length
            val clipped = lineWidth > visibleWidth + 1f || layout.multiParagraph.height > visibleHeight + 1f
            val details = "${testName.methodName}: text=${layout.layoutInput.text}, " +
                "visible=${visibleWidth}x${visibleHeight}, lineWidth=$lineWidth, " +
                "textHeight=${layout.multiParagraph.height}, truncated=$truncated, clipped=$clipped, " +
                "lines=${layout.lineCount}, maxLines=${layout.layoutInput.maxLines}, " +
                "fontScale=${layout.layoutInput.density.fontScale}"
            File(context.filesDir, "portuguese-$localeTag-text-layouts.log").appendText("$details\n")
            if (truncated || clipped) screenshot("overflow-${testName.methodName}")
            assertFalse(details, truncated || clipped)
        }
    }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capturePortugueseUi") != "true") return
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.filesDir, "portuguese-$localeTag-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
