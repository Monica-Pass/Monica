package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.security.BiometricWrapperRecoveryInstrumentedTest
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.LocalUiSecurityManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.SettingsViewModel

/** Real settings screen; only synthetic, isolated security material and destinations. */
class MasterLockingCardGroupUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = SettingsManager(context)
    private val initial = runBlocking { settings.settingsFlow.first() }
    private val model = SettingsViewModel(settings)
    private val store = ViewModelStore().apply { put("master-locking", model) }
    private lateinit var fixture: BiometricWrapperRecoveryInstrumentedTest.Fixture
    private lateinit var localized: android.content.Context
    private val events = mutableListOf<String>()

    @Before fun prepare() {
        fixture = BiometricWrapperRecoveryInstrumentedTest.Fixture()
        assertTrue(fixture.manager.setMasterPassword(BiometricWrapperRecoveryInstrumentedTest.PASSWORD))
    }

    @After fun cleanup() {
        runBlocking { settings.updateAutoLockMinutes(initial.autoLockMinutes) }
        compose.runOnIdle { store.clear() }
        if (::fixture.isInitialized) fixture.close()
    }

    private fun show(dark: Boolean = false, scale: Float = 1f, width: Int = 390,
        locale: Locale = Locale.SIMPLIFIED_CHINESE) {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        localized = context.createConfigurationContext(config)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources,
                LocalConfiguration provides config, LocalDensity provides Density(density, scale),
                LocalUiSecurityManager provides fixture.manager) {
                MonicaTheme(darkTheme = dark) {
                    Box(Modifier.width(width.dp).fillMaxHeight().testTag("master_group_frame")) {
                        MasterPasswordLockingSettingsScreen(model, { events += "back" },
                            { events += "reset" }, { events += "questions" }, { events += "backup" })
                    }
                }
            }
        }
        compose.waitUntil(5_000) { model.settings.value.autoLockMinutes == initial.autoLockMinutes }
    }

    private fun row(setting: Setting) = compose.onNodeWithText(localized.getString(setting.titleRes))
    private fun capture(name: String, dialog: Boolean = false) {
        val image = (if (dialog) compose.onNode(isDialog()) else compose.onNodeWithTag("master_group_frame"))
            .captureToImage().asAndroidBitmap()
        File(context.filesDir, "master-group-331-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    private fun assertNoTextOverflow() {
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), true)
        repeat(texts.fetchSemanticsNodes().size) { i ->
            val results = mutableListOf<TextLayoutResult>()
            texts[i].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            results.forEach { r ->
                val message = "Clipped text: ${r.layoutInput.text}"
                assertEquals(message, r.layoutInput.text.length, r.getLineEnd(r.lineCount - 1))
                assertFalse(message, (0 until r.lineCount).any { r.isLineEllipsized(it) })
                assertTrue(message, r.multiParagraph.height <= r.size.height + 1f)
                assertTrue(message, (0 until r.lineCount).all {
                    r.getLineRight(it) - r.getLineLeft(it) <= r.size.width + 1f
                })
            }
        }
    }

    @Test fun groupedPageRetainsAllSixEntriesAndOriginalDestinations() {
        show(dark = true)
        capture("zh-dark-top")
        listOf(Setting.BIOMETRIC_UNLOCK, Setting.BIOMETRIC_REPAIR, Setting.RECOVERY_PROTECTION,
            Setting.AUTO_LOCK, Setting.SECURITY_QUESTIONS, Setting.RESET_MASTER_PASSWORD).forEach {
            row(it).performScrollTo().assertIsDisplayed()
        }
        row(Setting.SECURITY_QUESTIONS).performClick()
        row(Setting.RESET_MASTER_PASSWORD).performClick()
        compose.onNodeWithContentDescription(localized.getString(R.string.back)).performClick()
        assertEquals(listOf("questions", "reset", "back"), events)
        assertNoTextOverflow()
    }

    @Test fun autoLockSelectionPersistsAndBackCancelsWithoutChangingIt() {
        show()
        row(Setting.AUTO_LOCK).performScrollTo().performClick()
        val next = if (initial.autoLockMinutes == 10) 5 else 10
        compose.onNodeWithText(getAutoLockDisplayName(next, localized)).performClick()
        compose.waitUntil(5_000) { model.settings.value.autoLockMinutes == next }
        assertEquals(next, runBlocking { SettingsManager(context).settingsFlow.first() }.autoLockMinutes)
        row(Setting.AUTO_LOCK).performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(next, runBlocking { settings.settingsFlow.first() }.autoLockMinutes)
    }

    @Test fun repairEntryStillRequiresPasswordAndCancelPreservesMaterial() {
        show()
        val before = fixture.prefs.all.toMap()
        row(Setting.BIOMETRIC_REPAIR).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNodeWithText(localized.getString(R.string.biometric_repair_confirm)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Synthetic-Cancel-Only")
        compose.onNodeWithText(localized.getString(R.string.cancel)).performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals(before, fixture.prefs.all)
    }

    @Test fun recoveryEntryKeepsVerificationCancelAndBackupRoutes() {
        show()
        val before = fixture.prefs.all.toMap()
        compose.onNodeWithTag("recovery_protection_setting").performScrollTo().performClick()
        compose.onNodeWithText(localized.getString(R.string.recovery_protection_verify)).performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Synthetic-Cancel-Only")
        compose.onNodeWithText(localized.getString(R.string.cancel)).performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals(before, fixture.prefs.all)
        compose.onNodeWithTag("recovery_protection_setting").performClick()
        compose.onNodeWithText(localized.getString(R.string.sync_backup_title)).performScrollTo().performClick()
        assertEquals(listOf("backup"), events)
    }

    @Test fun chineseDarkLargeTextScrollsWithoutClippingAndLockDismissesRepair() {
        show(dark = true, scale = 1.5f, width = 320)
        capture("zh-dark-large-top")
        assertNoTextOverflow()
        row(Setting.RESET_MASTER_PASSWORD).performScrollTo().assertIsDisplayed()
        capture("zh-dark-large-bottom")
        assertNoTextOverflow()
        row(Setting.BIOMETRIC_REPAIR).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Synthetic-Cancel-Only")
        SecurityManager.clearRuntimeUnlockCache()
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        assertTrue(events.isEmpty())
    }

    @Test fun englishLightLargeTextKeepsCompleteDescriptionsAndEntryOrder() {
        show(scale = 1.5f, width = 320, locale = Locale.ENGLISH)
        capture("en-light-large-top")
        assertNoTextOverflow()
        val titles = listOf(Setting.BIOMETRIC_UNLOCK, Setting.BIOMETRIC_REPAIR, Setting.RECOVERY_PROTECTION,
            Setting.AUTO_LOCK, Setting.SECURITY_QUESTIONS, Setting.RESET_MASTER_PASSWORD)
        val y = titles.map { row(it).fetchSemanticsNode().positionInRoot.y }
        assertTrue(y.zipWithNext().all { (a, b) -> a < b })
        row(Setting.RESET_MASTER_PASSWORD).performScrollTo().assertIsDisplayed()
        capture("en-light-large-bottom")
        assertNoTextOverflow()
    }
}
