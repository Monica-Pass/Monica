package takagi.ru.monica.ui.screens

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.autofill_ng.AutofillPreferences.AutofillDefaultSourceFilter
import takagi.ru.monica.autofill_ng.DomainMatchStrategy
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.passkey.hardware.HardwarePasskeyPreferences
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.ui.theme.MonicaTheme
import java.io.File
import java.util.Locale
import java.util.UUID

/** Real settings, DataStore and registered-source metadata; no vault files or remote accounts. */
class AutofillSettingsM3eTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences by lazy { AutofillPreferences(context) }
    private val database by lazy { PasswordDatabase.getDatabase(context) }
    private val keepassIds = mutableListOf<Long>()
    private val bitwardenIds = mutableListOf<Long>()
    private val fixturePrefix = "autofill-m3e-${UUID.randomUUID()}"
    private lateinit var previous: PreferenceSnapshot
    private var visible by mutableStateOf(true)
    private var dark by mutableStateOf(false)
    private var contentInstalled = false

    private data class PreferenceSnapshot(
        val source: AutofillDefaultSourceFilter,
        val keepass: Long?,
        val bitwarden: Long?,
        val domain: DomainMatchStrategy,
        val duration: Int,
        val otpEnabled: Boolean,
    )

    private fun snapshot() = runBlocking {
        PreferenceSnapshot(preferences.v2DefaultSourceFilter.first(), preferences.v2DefaultKeepassDatabaseId.first(),
            preferences.v2DefaultBitwardenVaultId.first(), preferences.domainMatchStrategy.first(),
            preferences.otpNotificationDuration.first(), preferences.isOtpNotificationEnabled.first())
    }

    @Before fun prepare() {
        assertTrue(runBlocking { SecureStorageStartup.prepareForEntry(context) } is SecureStartupResult.Ready)
        previous = snapshot()
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

    @After fun restoreOnlyOwnedFixture() {
        if (contentInstalled) {
            compose.runOnUiThread { visible = false }
            compose.waitForIdle()
        }
        runBlocking {
            if (::previous.isInitialized) {
                preferences.setV2DefaultSourceFilter(previous.source)
                preferences.setV2DefaultKeepassDatabaseId(previous.keepass)
                preferences.setV2DefaultBitwardenVaultId(previous.bitwarden)
                preferences.setDomainMatchStrategy(previous.domain)
                preferences.setOtpNotificationDuration(previous.duration)
                preferences.setOtpNotificationEnabled(previous.otpEnabled)
            }
            keepassIds.forEach { id ->
                val row = database.localKeePassDatabaseDao().getDatabaseById(id)
                check(row == null || row.filePath.startsWith(fixturePrefix))
                database.localKeePassDatabaseDao().deleteDatabaseById(id)
            }
            bitwardenIds.forEach { id ->
                val row = database.bitwardenVaultDao().getVaultById(id)
                check(row == null || row.accountKey.startsWith(fixturePrefix))
                database.bitwardenVaultDao().deleteById(id)
            }
        }
    }

    private fun keepass(name: String = "Synthetic KeePass", order: Int = 0): Long = runBlocking {
        database.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(name = name,
            filePath = "$fixturePrefix-${keepassIds.size}.kdbx", sortOrder = order)).also(keepassIds::add)
    }

    private fun bitwarden(name: String = "Synthetic Bitwarden"): Long = runBlocking {
        val key = "$fixturePrefix-${bitwardenIds.size}"
        database.bitwardenVaultDao().insert(BitwardenVault(email = "$key@example.invalid", canonicalEmail = "$key@example.invalid",
            accountKey = key, displayName = name, serverUrl = "https://example.invalid",
            apiUrl = "https://example.invalid/api", identityUrl = "https://example.invalid/identity",
            isLocked = true, isConnected = false, syncEnabled = false)).also(bitwardenIds::add)
    }

    private fun show(scale: Float = 1f, focus: Int = 0, locale: Locale = Locale.ENGLISH) {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale); fontScale = scale }
        val localized = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalSettingsSearchNavigation provides SettingsSearchNavigation(focus) {}) {
                MonicaTheme(darkTheme = dark) {
                    if (visible) AutofillSettingsV2Screen(onNavigateBack = { visible = false },
                        onNavigateToBlockedFields = {}, onNavigateToSaveBlockedTargets = {})
                    else Text("Autofill fixture closed", Modifier.testTag("autofill-m3e-closed"))
                }
            }
        }
        contentInstalled = true
    }

    private fun reenter() {
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("autofill-m3e-closed").assertIsDisplayed()
        compose.runOnIdle { visible = true }
        compose.onNodeWithTag("autofill-source-setting").assertExists()
    }

    private fun open(tag: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(SemanticsProperties.Disabled) == false
        }
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("autofill-choice-sheet").assertIsDisplayed()
    }

    private fun option(key: String): SemanticsNodeInteraction {
        val tag = "autofill-choice-$key"
        compose.onNodeWithTag("autofill-choice-sheet").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun closeWithoutChoice() {
        compose.onNodeWithTag("autofill-choice-sheet").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("autofill-choice-sheet").assertDoesNotExist()
    }

    private fun <T> awaitValue(flow: Flow<T>, expected: T) {
        assertEquals(expected, runBlocking { withTimeout(5_000) { flow.first { it == expected } } })
        compose.waitForIdle()
    }

    @Test fun explicitSourceSelectionPersistsAndKeepsExistingCrossSourceClearPolicy() {
        val kp = keepass()
        val bw = bitwarden()
        runBlocking {
            preferences.setV2DefaultSourceFilter(AutofillDefaultSourceFilter.ALL)
            preferences.setV2DefaultKeepassDatabaseId(kp)
            preferences.setV2DefaultBitwardenVaultId(bw)
        }
        show()
        open("autofill-source-setting")
        option("KEEPASS").performClick()
        awaitValue(preferences.v2DefaultSourceFilter, AutofillDefaultSourceFilter.KEEPASS)
        assertEquals(kp, snapshot().keepass)
        assertNull(snapshot().bitwarden)
        reenter()
        open("autofill-source-setting")
        option("KEEPASS").assertIsSelected()
        option("LOCAL").performClick()
        awaitValue(preferences.v2DefaultSourceFilter, AutofillDefaultSourceFilter.LOCAL)
        assertNull(snapshot().keepass)
        assertNull(snapshot().bitwarden)
        reenter()
        open("autofill-source-setting")
        option("LOCAL").assertIsSelected()
        option("ALL").assertIsNotSelected()
        closeWithoutChoice()
    }

    @Test fun registeredKeepassAndBitwardenChoicesPersistAcrossPageReentry() {
        val kp = keepass("Synthetic work KeePass")
        val bw = bitwarden("Synthetic personal Bitwarden")
        runBlocking {
            preferences.setV2DefaultSourceFilter(AutofillDefaultSourceFilter.KEEPASS)
            preferences.setV2DefaultKeepassDatabaseId(null)
            preferences.setV2DefaultBitwardenVaultId(null)
        }
        show(locale = Locale.SIMPLIFIED_CHINESE)
        open("autofill-keepass-setting")
        option(kp.toString()).performClick()
        awaitValue(preferences.v2DefaultKeepassDatabaseId, kp)
        reenter()
        open("autofill-keepass-setting")
        option(kp.toString()).assertIsSelected()
        option("all").assertIsNotSelected()
        screenshot("autofill-m3e-keepass-selected.png")
        closeWithoutChoice()
        open("autofill-source-setting")
        option("BITWARDEN").performClick()
        awaitValue(preferences.v2DefaultSourceFilter, AutofillDefaultSourceFilter.BITWARDEN)
        open("autofill-bitwarden-setting")
        option(bw.toString()).performClick()
        awaitValue(preferences.v2DefaultBitwardenVaultId, bw)
        reenter()
        open("autofill-bitwarden-setting")
        option(bw.toString()).assertIsSelected()
        option("all").assertIsNotSelected()
        screenshot("autofill-m3e-bitwarden-selected.png")
        closeWithoutChoice()
        assertNotNull(runBlocking { database.localKeePassDatabaseDao().getDatabaseById(kp) })
        assertNotNull(runBlocking { database.bitwardenVaultDao().getVaultById(bw) })
    }

    @Test fun backDismissesEveryChoiceSheetWithoutWritingPreferences() {
        val kp = keepass()
        val bw = bitwarden()
        runBlocking {
            preferences.setV2DefaultSourceFilter(AutofillDefaultSourceFilter.KEEPASS)
            preferences.setV2DefaultKeepassDatabaseId(kp)
            preferences.setV2DefaultBitwardenVaultId(bw)
            preferences.setOtpNotificationEnabled(true)
        }
        show()
        for (tag in listOf("autofill-source-setting", "autofill-keepass-setting", "autofill-domain-setting", "autofill-otp-duration-setting")) {
            val before = snapshot()
            open(tag)
            closeWithoutChoice()
            assertEquals(before, snapshot())
        }
        runBlocking { preferences.setV2DefaultSourceFilter(AutofillDefaultSourceFilter.BITWARDEN) }
        awaitValue(preferences.v2DefaultSourceFilter, AutofillDefaultSourceFilter.BITWARDEN)
        val before = snapshot()
        open("autofill-bitwarden-setting")
        closeWithoutChoice()
        assertEquals(before, snapshot())
    }

    @Test fun unavailableDefaultIdsSurviveReadReentryAndCancelInsteadOfBecomingAll() {
        val missingKp = Long.MAX_VALUE - 170
        val missingBw = Long.MAX_VALUE - 171
        runBlocking {
            assertNull(database.localKeePassDatabaseDao().getDatabaseById(missingKp))
            assertNull(database.bitwardenVaultDao().getVaultById(missingBw))
            preferences.setV2DefaultKeepassDatabaseId(missingKp)
            preferences.setV2DefaultBitwardenVaultId(missingBw)
            preferences.setV2DefaultSourceFilter(AutofillDefaultSourceFilter.KEEPASS)
        }
        show()
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val unavailable = english.getString(R.string.autofill_selected_source_unavailable)
        for ((source, tag) in listOf(AutofillDefaultSourceFilter.KEEPASS to "autofill-keepass-setting",
            AutofillDefaultSourceFilter.BITWARDEN to "autofill-bitwarden-setting")) {
            runBlocking { preferences.setV2DefaultSourceFilter(source) }
            awaitValue(preferences.v2DefaultSourceFilter, source)
            reenter()
            val unavailableInRow = hasText(unavailable, substring = true) and hasAnyAncestor(hasTestTag(tag))
            compose.waitUntil(5_000) {
                compose.onAllNodes(unavailableInRow, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(unavailableInRow, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            assertEquals(missingKp, snapshot().keepass)
            assertEquals(missingBw, snapshot().bitwarden)
            open(tag)
            option("all").assertIsNotSelected()
            closeWithoutChoice()
            assertEquals(missingKp, snapshot().keepass)
            assertEquals(missingBw, snapshot().bitwarden)
        }
    }

    @Test fun matchingAndOtpDurationsUsePersistedSingleChoiceSheets() {
        runBlocking { preferences.setOtpNotificationEnabled(true); preferences.setDomainMatchStrategy(DomainMatchStrategy.BASE_DOMAIN) }
        show()
        open("autofill-domain-setting")
        option(DomainMatchStrategy.EXACT_MATCH.name).performClick()
        awaitValue(preferences.domainMatchStrategy, DomainMatchStrategy.EXACT_MATCH)
        open("autofill-otp-duration-setting")
        option("120").performClick()
        awaitValue(preferences.otpNotificationDuration, 120)
        reenter()
        open("autofill-domain-setting")
        option(DomainMatchStrategy.EXACT_MATCH.name).assertIsSelected()
        option(DomainMatchStrategy.BASE_DOMAIN.name).assertIsNotSelected()
        closeWithoutChoice()
        open("autofill-otp-duration-setting")
        option("120").assertIsSelected()
        option("30").assertIsNotSelected()
        closeWithoutChoice()
    }

    @Test fun longSourceNamesWrapAndRemainSelectableWithLargeTextInBothThemes() {
        val names = (0..9).map { "Synthetic work archive $it — extended database name for cross-device credentials and personal notes" }
        val ids = names.mapIndexed { index, name -> keepass(name, index) }
        runBlocking {
            preferences.setV2DefaultSourceFilter(AutofillDefaultSourceFilter.KEEPASS)
            preferences.setV2DefaultKeepassDatabaseId(ids.last())
        }
        show(scale = 1.6f)
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            open("autofill-keepass-setting")
            option(ids.last().toString()).assertIsSelected()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(names.last()) and
                hasAnyAncestor(hasTestTag("autofill-choice-${ids.last()}")), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Long name must provide actual text layout evidence", layouts.isNotEmpty())
            assertTrue("Long source name must wrap", layouts.single().lineCount > 1)
            assertFalse("Long source name must not be clipped or ellipsized", layouts.single().hasVisualOverflow)
            screenshot("autofill-m3e-long-name-${if (night) "dark" else "light"}.png")
            option(ids.first().toString()).performClick()
            awaitValue(preferences.v2DefaultKeepassDatabaseId, ids.first())
            open("autofill-keepass-setting")
            option(ids.first().toString()).assertIsSelected()
            option(ids.last().toString()).performClick()
            awaitValue(preferences.v2DefaultKeepassDatabaseId, ids.last())
        }
    }

    @Test fun systemAutofillEntryIsUniqueAndHardwareSearchStillLocatesTheSharedSwitch() {
        val before = HardwarePasskeyPreferences.isEnabled(context)
        show(focus = R.string.hardware_key_enable)
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        compose.onAllNodesWithText(english.getString(R.string.autofill_v2_set_system_service)).assertCountEquals(1)
        compose.waitUntil(5_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() &&
                compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).isDisplayed()
        }
        compose.onNodeWithTag("hardware-passkey-enabled").assertIsDisplayed()
        assertEquals(before, HardwarePasskeyPreferences.isEnabled(context))
        screenshot("autofill-m3e-hardware-search-target.png")
        compose.onNodeWithContentDescription(english.getString(R.string.autofill_settings_back)).performClick()
        compose.onNodeWithTag("autofill-m3e-closed").assertIsDisplayed()
        assertEquals(before, HardwarePasskeyPreferences.isEnabled(context))
    }

    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
