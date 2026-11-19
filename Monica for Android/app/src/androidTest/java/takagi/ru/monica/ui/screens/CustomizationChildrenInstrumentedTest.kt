package takagi.ru.monica.ui.screens

import android.app.Application
import androidx.compose.material.icons.filled.Tune
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
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.PermissionRepository
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PermissionViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel

class CustomizationChildrenInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = SettingsManager(context)
    private val initial = runBlocking { manager.settingsFlow.first() }
    private val settingsModel = SettingsViewModel(manager)
    private val store = ViewModelStore().apply { put("settings", settingsModel) }
    private val navigation = mutableListOf<String>()
    private lateinit var localized: android.content.Context
    private val fixtureName = "UI preset " + java.util.UUID.randomUUID().toString()

    @After fun cleanup() {
        runBlocking {
            manager.presetCustomFieldsFlow.first().filter { it.fieldName == fixtureName || it.fieldName == "$fixtureName edited" }
                .forEach { manager.deletePresetCustomField(it.id) }
            manager.updateSeparateUsernameAccountEnabled(initial.separateUsernameAccountEnabled)
            manager.updatePasswordCardShowAuthenticator(initial.passwordCardShowAuthenticator)
            manager.updateIconCardsEnabled(initial.iconCardsEnabled)
            manager.updateAddButtonBehaviorMode(initial.addButtonBehaviorMode)
            manager.updateAddButtonMenuOrder(initial.addButtonMenuOrder)
            manager.updateAddButtonMenuEnabledActions(initial.addButtonMenuEnabledActions)
            manager.updatePasswordFieldVisibility("securityVerification", initial.passwordFieldVisibility.securityVerification)
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
        File(context.filesDir, "customization-children-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun captureDialog(name: String) {
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(context.filesDir, "customization-children-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun assertNoTextOverflow() {
        // Card previews deliberately ellipsize long values, just like the real list.
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            !hasAnyAncestor(hasTestTag("customization_preview")),
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


    @Test fun passwordPreviewAndAuthenticatorDependencyPersist() {
        show { PasswordCardAdjustmentScreen(settingsModel) {} }
        compose.waitUntil(10_000) { settingsModel.settings.value == initial }
        capture("password-light-preview")
        row(R.string.password_card_show_authenticator_switch_label).performScrollTo().performClick()
        compose.waitUntil(10_000) { settingsModel.settings.value.passwordCardShowAuthenticator != initial.passwordCardShowAuthenticator }
        assertEquals(!initial.passwordCardShowAuthenticator, runBlocking { manager.settingsFlow.first() }.passwordCardShowAuthenticator)
        row(R.string.password_card_hide_other_content_when_authenticator_title).performScrollTo().apply {
            if (initial.passwordCardShowAuthenticator) assertIsNotEnabled() else assertIsEnabled()
        }
        row(R.string.website_stack_match_mode_relaxed).performScrollTo().assertIsDisplayed()
        capture("password-light-bottom")
    }

    @Test fun addMenuOrderAndRequiredPasswordSurviveReopeningSettings() {
        runBlocking { manager.updateAddButtonBehaviorMode(AddButtonBehaviorMode.EXPANDABLE_MENU) }
        show { AddButtonCustomizationScreen(settingsModel) {} }
        compose.waitUntil(10_000) { settingsModel.settings.value.addButtonBehaviorMode == AddButtonBehaviorMode.EXPANDABLE_MENU }
        compose.onNodeWithTag("customization_toggle_PASSWORD").performScrollTo().assertIsNotEnabled().assertIsOn()
        val before = settingsModel.settings.value.addButtonMenuOrder
        val first = before.first()
        val moving = compose.onNodeWithTag("customization_order_$first").performScrollTo()
        val moveDown = moving.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == localized.getString(R.string.move_down) }
        compose.runOnIdle { assertTrue(moveDown.action()) }
        compose.waitUntil(10_000) { settingsModel.settings.value.addButtonMenuOrder != before }
        assertEquals(before.toMutableList().apply { add(1, removeAt(0)) }, runBlocking { SettingsManager(context).settingsFlow.first() }.addButtonMenuOrder)
        capture("add-order")
    }

    @Test fun iconDependenciesAndCollapsedSearchWork() {
        show(scale = 2f, width = 320, dark = true) {
            CompositionLocalProvider(LocalSettingsSearchNavigation provides SettingsSearchNavigation(R.string.icon_settings_priority_title) {}) {
                IconSettingsScreen(settingsModel) {}
            }
        }
        row(R.string.icon_settings_priority_title).performScrollTo().assertIsDisplayed()
        capture("icons-dark-large-source")
        row(R.string.icon_settings_master_switch).performScrollTo().performClick()
        compose.waitUntil(10_000) { settingsModel.settings.value.iconCardsEnabled != initial.iconCardsEnabled }
        row(R.string.icon_settings_password_page_title).performScrollTo().apply {
            if (initial.iconCardsEnabled) assertIsNotEnabled() else assertIsEnabled()
        }
        compose.onNodeWithTag("launcher_icon_BLUE_STAR").performScrollTo().assertIsDisplayed()
        capture("icons-dark-large-launcher")
    }

    @Test fun fieldsHavePersistentTogglesAndScrollablePresetDialog() {
        show(scale = 2f, width = 320, dark = true, english = true) { PasswordFieldCustomizationScreen(settingsModel) {} }
        row(R.string.separate_username_account_title).performScrollTo().performClick()
        compose.waitUntil(10_000) { settingsModel.settings.value.separateUsernameAccountEnabled != initial.separateUsernameAccountEnabled }
        capture("fields-dark-large")
        row(R.string.add).performScrollTo().performClick()
        compose.onNodeWithTag("preset_more_options").performScrollTo().performClick()
        compose.onNodeWithTag("preset_placeholder").performScrollTo().assertIsDisplayed()
        captureDialog("preset-dialog-large")
        row(R.string.cancel).assertIsDisplayed().performClick()
        row(R.string.password_field_customization_reset_system_fields).assertDoesNotExist()
    }

    @Test fun allChildrenRenderAtNarrowDoubleFontWithoutClipping() {
        var page by mutableStateOf(0)
        show(dark = true, scale = 2f, width = 320, english = true) {
            key(page) {
                when(page) {
                    0 -> PasswordListCustomizationScreen(settingsModel) {}
                    1 -> PasswordCardAdjustmentScreen(settingsModel) {}
                    2 -> AuthenticatorCardAdjustmentScreen(settingsModel) {}
                    3 -> PasswordFieldCustomizationScreen(settingsModel) {}
                    4 -> IconSettingsScreen(settingsModel) {}
                    else -> AddButtonCustomizationScreen(settingsModel) {}
                }
            }
        }
        for (index in 0..5) {
            compose.runOnIdle { page = index }
            compose.waitForIdle()
            capture("page-$index-dark-large-top")
            // Visit the full scroll range, checking glyph geometry at each viewport.
            repeat(5) {
                assertNoTextOverflow()
                compose.onNodeWithTag("settings_subpage_test_frame").performTouchInput { swipeUp() }
            }
            capture("page-$index-dark-large-bottom")
        }
    }

    @Test fun presetCanBeCreatedEditedAndDeletedWithoutExposingSensitiveDefault() {
        show(english = true) { PasswordFieldCustomizationScreen(settingsModel) {} }
        row(R.string.add).performScrollTo().performClick()
        row(R.string.password_field_customization_field_name_required).performTextInput(fixtureName)
        row(R.string.custom_field_sensitive).performScrollTo().performClick()
        compose.onNodeWithTag("preset_more_options").performScrollTo().performClick()
        row(R.string.password_field_customization_default_value_optional).performScrollTo().performTextInput("synthetic-secret")
        compose.onNodeWithTag("preset_save").performClick()
        compose.waitUntil(10_000) { settingsModel.presetCustomFields.value.any { it.fieldName == fixtureName } }
        compose.onNodeWithText(fixtureName).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("synthetic-secret", substring = true).assertDoesNotExist()
        capture("preset-field")
        val id = settingsModel.presetCustomFields.value.single { it.fieldName == fixtureName }.id
        compose.onNodeWithTag("preset_edit_$id").performScrollTo().performClick()
        row(R.string.password_field_customization_field_name_required).performTextReplacement("$fixtureName edited")
        row(R.string.save).performClick()
        compose.waitUntil(10_000) { settingsModel.presetCustomFields.value.any { it.fieldName == "$fixtureName edited" } }
        compose.onNodeWithTag("preset_delete_$id").performScrollTo().performClick()
        compose.onNode(hasText(localized.getString(R.string.delete)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { settingsModel.presetCustomFields.value.none { it.fieldName == "$fixtureName edited" } }
    }

    @Test fun wholeRowReordersNaturalHeightRows() {
        var order by mutableStateOf(listOf("First", "Second", "Third"))
        show {
            takagi.ru.monica.ui.components.CustomizationOrderGroup(takagi.ru.monica.ui.screens.Setting.ADD_BUTTON_ACTIONS_TITLE, order, order,
                label = { it }, icon = { androidx.compose.material.icons.Icons.Default.Tune },
                onOrder = { order = it }, onToggle = { _, _ -> })
        }
        val first = compose.onNodeWithTag("customization_order_First")
        val second = compose.onNodeWithTag("customization_order_Second")
        val distance = second.fetchSemanticsNode().boundsInRoot.center.y - first.fetchSemanticsNode().boundsInRoot.center.y
        first.performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(androidx.compose.ui.geometry.Offset(0f, distance), delayMillis = 300)
            advanceEventTime(300)
        }
        compose.mainClock.advanceTimeBy(240)
        capture("sorting-lifted")
        first.performTouchInput { up() }
        compose.waitUntil(5_000) { order == listOf("Second", "First", "Third") }
        compose.waitForIdle()
        capture("sorting-settled")
    }

    @Test fun presetSheetEditsAllPropertiesWithoutLosingIdentity() {
        var saved: PresetCustomField? = null
        val original = PresetCustomField(id = "ui-only", fieldName = "Example", fieldType = PresetFieldType.EMAIL,
            defaultValue = "sample@example.invalid", placeholder = "Work account", isRequired = true, order = 7)
        show(english = true) { PresetFieldDialog(original, {}, { saved = it }) }
        compose.onNodeWithTag("preset_type").performClick()
        compose.onNodeWithText(localized.getString(R.string.password_field_customization_type_password)).performClick()
        compose.onNodeWithTag("preset_default").performScrollTo().assertTextContains("sample@example.invalid")
        compose.onNodeWithTag("preset_placeholder").performScrollTo().assertTextContains("Work account")
        val bitmap = compose.onNodeWithTag("preset_field_sheet").captureToImage().asAndroidBitmap()
        File(context.filesDir, "preset-sheet-light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.onNodeWithTag("preset_save").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(original.copy(fieldType = PresetFieldType.PASSWORD, isSensitive = true), saved)
        }
    }

    @Test fun presetSheetLargeTextKeepsSaveReachableAndRejectsBlankName() {
        var saved: PresetCustomField? = null
        show(dark = true, scale = 1.5f, width = 320) { PresetFieldDialog(null, {}, { saved = it }) }
        val compact = compose.onNodeWithTag("preset_field_sheet").captureToImage().asAndroidBitmap()
        File(context.filesDir, "preset-sheet-compact.png").outputStream().use { compact.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compact.recycle()
        compose.onNodeWithTag("preset_save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("preset_name").performScrollTo().performTextInput("Sample")
        compose.onNodeWithTag("preset_more_options").performScrollTo().performClick()
        compose.onNodeWithTag("preset_default").performScrollTo().performTextInput("value")
        compose.onNodeWithTag("preset_placeholder").performScrollTo().performTextInput("hint")
        val bitmap = compose.onNodeWithTag("preset_field_sheet").captureToImage().asAndroidBitmap()
        File(context.filesDir, "preset-sheet-dark-large.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.onNodeWithTag("preset_save").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Sample", saved?.fieldName)
            assertEquals("value", saved?.defaultValue)
            assertEquals("hint", saved?.placeholder)
        }
    }

    @Test fun allChildrenRenderWithNormalChineseText() {
        var page by mutableStateOf(0)
        show {
            key(page) {
                when(page) {
                    0 -> PasswordListCustomizationScreen(settingsModel) {}
                    1 -> PasswordCardAdjustmentScreen(settingsModel) {}
                    2 -> AuthenticatorCardAdjustmentScreen(settingsModel) {}
                    3 -> PasswordFieldCustomizationScreen(settingsModel) {}
                    4 -> IconSettingsScreen(settingsModel) {}
                    else -> AddButtonCustomizationScreen(settingsModel) {}
                }
            }
        }
        for (index in 0..5) {
            compose.runOnIdle { page = index }
            compose.waitForIdle()
            capture("page-$index-light-top")
            assertNoTextOverflow()
        }
    }
}
