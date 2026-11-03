package takagi.ru.monica.ui.screens

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.VaultV2LayoutMode
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.PermissionRepository
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PermissionViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel

class SettingsSubpagesInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = SettingsManager(context)
    private val initial = runBlocking { manager.settingsFlow.first() }
    private val settingsModel = SettingsViewModel(manager)
    private val store = ViewModelStore().apply { put("settings", settingsModel) }
    private val navigation = mutableListOf<String>()
    private lateinit var localized: android.content.Context

    @After fun cleanup() {
        runBlocking {
            manager.updateVaultV2LayoutMode(initial.vaultV2LayoutMode)
            manager.updateVaultOverviewEnabled(initial.vaultOverviewEnabled)
            manager.updateWalletStackLoopEnabled(initial.walletStackLoopEnabled)
        }
        compose.runOnIdle { store.clear() }
    }

    private fun show(dark: Boolean = false, scale: Float = 1f, width: Int = 390,
        english: Boolean = false, content: @Composable () -> Unit) {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(if (english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)
        }
        localized = context.createConfigurationContext(config)
        compose.setContent {
            val density = LocalDensity.current.density
            val activityResults = checkNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalActivityResultRegistryOwner provides activityResults,
                LocalDensity provides Density(density, scale)) {
                MonicaTheme(darkTheme = dark) {
                    Box(Modifier.width(width.dp).fillMaxHeight().testTag("settings_subpage_test_frame")) { content() }
                }
            }
        }
    }

    @Composable private fun Customize() {
        PageAdjustmentCustomizationScreen(settingsModel, { navigation += "back" },
            { navigation += "list" }, { navigation += "password" },
            { navigation += "otp" }, { navigation += "fields" }, { navigation += "icons" })
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("settings_subpage_test_frame").captureToImage().asAndroidBitmap()
        File(context.filesDir, "settings-subpages-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun assertNoTextOverflow() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true)
        repeat(nodes.fetchSemanticsNodes().size) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.forEach { layout ->
                val message = "Clipped text: ${layout.layoutInput.text} (${layout.size})"
                // Compose can reconstruct a wrap-content Text paragraph at its parent's max
                // width. Check actual glyph extents, line endings and height, not that container.
                assertEquals(message, layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
                assertFalse(message, (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
                val widestLine = (0 until layout.lineCount).maxOf {
                    layout.getLineRight(it) - layout.getLineLeft(it)
                }
                assertTrue(message, widestLine <= layout.size.width + 1f)
                assertTrue(message, layout.multiParagraph.height <= layout.size.height + 1f)
            }
        }
    }

    private fun row(title: Int) = compose.onNodeWithText(localized.getString(title))

    @Test fun customizationPreservesThreePersistentTogglesAndFiveDestinations() {
        show { Customize() }
        compose.waitUntil(10_000) { settingsModel.settings.value == initial }
        capture("customize-light-top")
        val toggles = listOf(R.string.vault_v2_hierarchical_layout_title,
            R.string.vault_overview_enabled_title, R.string.wallet_stack_loop_title)
        toggles.forEach { row(it).performScrollTo().performClick() }
        compose.waitUntil(10_000) {
            settingsModel.settings.value.let {
                it.vaultOverviewEnabled != initial.vaultOverviewEnabled &&
                    it.walletStackLoopEnabled != initial.walletStackLoopEnabled &&
                    it.vaultV2LayoutMode != initial.vaultV2LayoutMode
            }
        }
        val stored = runBlocking { SettingsManager(context).settingsFlow.first() }
        assertEquals(!initial.vaultOverviewEnabled, stored.vaultOverviewEnabled)
        assertEquals(!initial.walletStackLoopEnabled, stored.walletStackLoopEnabled)
        assertEquals(if (initial.vaultV2LayoutMode == VaultV2LayoutMode.CLASSIC)
            VaultV2LayoutMode.HIERARCHICAL else VaultV2LayoutMode.CLASSIC, stored.vaultV2LayoutMode)
        listOf(R.string.password_list_customization_title, R.string.password_card_adjust_title,
            R.string.authenticator_card_adjust_title, R.string.password_field_customization_title,
            R.string.icon_settings_title).forEach { row(it).performScrollTo().performClick() }
        assertEquals(listOf("list", "password", "otp", "fields", "icons"), navigation)
        capture("customize-light-bottom")
        compose.onNodeWithContentDescription(localized.getString(R.string.back)).performClick()
        assertEquals("back", navigation.last())
        assertNoTextOverflow()
    }

    @Test fun customizationSearchLocatesWalletWithoutChangingPreferenceAtLargeFont() {
        show(dark = true, scale = 2f, width = 320) {
            CompositionLocalProvider(LocalSettingsSearchNavigation provides
                SettingsSearchNavigation(R.string.wallet_stack_loop_title) {}) { Customize() }
        }
        compose.onNodeWithTag("wallet_stack_loop_setting").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), true).assertIsDisplayed()
        assertEquals(initial.walletStackLoopEnabled, runBlocking { manager.settingsFlow.first() }.walletStackLoopEnabled)
        capture("customize-dark-large-wallet")
        row(R.string.icon_settings_title).performScrollTo().assertIsDisplayed()
        capture("customize-dark-large-bottom")
        assertNoTextOverflow()
    }

    private fun fixtures(): List<PermissionInfo> = PermissionRepository(context).getAllPermissions().map {
        it.copy(status = when (it.id) {
            "BIOMETRIC" -> PermissionStatus.UNAVAILABLE
            "AUTOFILL", "CAMERA" -> PermissionStatus.DENIED
            "STORAGE" -> PermissionStatus.UNKNOWN
            else -> PermissionStatus.GRANTED
        })
    }

    @Test fun permissionsExposeDetailsForEveryDeclaredPermissionAndService() {
        val items = fixtures()
        val clicked = mutableListOf<String>()
        var refresh = 0
        var help = 0
        var back = 0
        show {
            PermissionManagementContent(items.groupBy { it.category },
                PermissionStats(0,0,items.size,items.count { it.status == PermissionStatus.GRANTED }),
                false, { back++ }, { refresh++ }, { help++ }, { clicked += it.id })
        }
        capture("permissions-light-top")
        compose.onNodeWithTag("permission_refresh").performClick()
        compose.onNodeWithContentDescription(localized.getString(R.string.help)).performClick()
        compose.onNodeWithContentDescription(localized.getString(R.string.back)).performClick()
        assertEquals(listOf(1,1,1), listOf(refresh,help,back))
        assertFalse("Phone state was removed in both variants", items.any { it.id == "PHONE_STATE" })
        val detailIds = items.map { it.id }
        for (id in detailIds) {
            compose.onNodeWithTag("permission_row_$id").performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).performClick()
        }
        assertEquals(detailIds, clicked)
        compose.onNodeWithTag("permission_row_CAMERA").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, localized.getString(R.string.permission_status_denied)))
        val cameraBounds = compose.onNodeWithTag("permission_row_CAMERA").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue("Compact camera row with visible status", cameraBounds.bottom - cameraBounds.top <= 180.dp)
        val statusRightEdges = listOf("CAMERA", "VIBRATE", "NOTIFICATION").map { id ->
            compose.onNodeWithTag("permission_row_$id").performScrollTo()
            compose.onNodeWithTag("permission_status_$id", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.right
        }
        assertTrue("Permission statuses must align regardless of clickability",
            statusRightEdges.max() - statusRightEdges.min() <= 1f)
        compose.onNodeWithTag("permission_row_CAMERA").performScrollTo()
        capture("permissions-light-camera")
        assertNoTextOverflow()
    }

    @Test fun permissionDescriptionsAndActionsFitNarrowDarkEnglishAtDoubleFontSize() {
        val items = fixtures()
        show(dark = true, scale = 2f, width = 320, english = true) {
            PermissionManagementContent(items.groupBy { it.category },
                PermissionStats(0,0,items.size,items.count { it.status == PermissionStatus.GRANTED }),
                false, {}, {}, {}, {})
        }
        capture("permissions-dark-large-top")
        compose.onNodeWithTag("permission_row_AUTOFILL").performScrollTo().assertIsDisplayed()
        capture("permissions-dark-large-autofill")
        compose.onNodeWithTag("permission_row_CAMERA").performScrollTo().assertIsDisplayed()
        capture("permissions-dark-large-camera")
        assertNoTextOverflow()
    }

    @Test fun permissionRefreshIsDisabledWhileLoading() {
        show {
            PermissionManagementContent(emptyMap(), null, true, {}, { fail("Refresh during loading") }, {}, {})
        }
        compose.onNodeWithTag("permission_refresh").assertIsNotEnabled()
    }

    @Test fun permissionSystemSettingsReturnKeepsPageAndHelpUsable() = takagi.ru.monica.util.PermissionTestSystemUi().use { system ->
        val model = PermissionViewModel(context.applicationContext as Application)
        store.put("permissions", model)
        show { PermissionManagementScreen({ navigation += "back" }, model) }
        compose.onNodeWithTag("permission_row_ACCESSIBILITY").performScrollTo().performClick()
        compose.onNodeWithTag("permission_details_action").performClick()
        val automation = system.automation
        compose.waitUntil(10_000) { system.hasPackage("com.android.settings") }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input keyevent KEYCODE_BACK")
        ).use { it.readBytes() }
        compose.waitUntil(10_000) { system.hasPackage(context.packageName) }
        automation.waitForIdle(500, 5_000)
        compose.onNodeWithTag("permission_refresh").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription(localized.getString(R.string.help)).performClick()
        compose.waitForIdle()
        automation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "settings-subpages-permission-help.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        compose.onNodeWithText(localized.getString(R.string.permission_help_title)).assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription(localized.getString(R.string.back)).performClick()
        assertEquals(listOf("back"), navigation)
    }
}
