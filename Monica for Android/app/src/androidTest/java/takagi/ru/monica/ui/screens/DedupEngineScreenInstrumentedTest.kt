package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.dedup.*
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.DedupEngineUiState

@RunWith(AndroidJUnit4::class)
class DedupEngineScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var screenLocale = Locale.SIMPLIFIED_CHINESE
    private val sources = (1..45).map { DedupMergeSourceOption("keepass:$it", DedupMergeSourceKind.KEEPASS, "Source $it", 2) }
    private val target = DedupMergeTarget.MdbxDatabase(7, "Personal")
    private val targets = listOf(DedupMergeTargetOption(target, "mdbx:7", "Personal", 0)) +
        (8..50).map { DedupMergeTargetOption(DedupMergeTarget.MdbxDatabase(it.toLong(), "Archive $it"), "mdbx:$it", "Archive $it", 0) }
    private var state by mutableStateOf(DedupEngineUiState(isLoading = false, sourceOptions = sources, targetOptions = targets))
    private var selectedSearchKeys = emptySet<String>()
    private var created = 0
    private var executions = 0
    private var cancellations = 0
    private var back = 0
    private var restoreConfiguration: (() -> Unit)? = null
    private var screenVisible by mutableStateOf(true)
    private var keyboard: SoftwareKeyboardController? = null

    @After fun restoreLocale() { compose.runOnUiThread { restoreConfiguration?.invoke() } }

    @Test fun entryNoticeCancelsWithoutMountingMergeControlsOrAcceptingLateClick() {
        state = ready()
        show(acceptEntryNotice = false)
        compose.onNodeWithTag("dedup_primary_action").assertDoesNotExist()
        assertEquals(0f, compose.onNodeWithTag("dedup_entry_body").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        compose.onNodeWithText(text(R.string.dedup_entry_common)).assertIsDisplayed()
        screenshot("entry-merge-zh-normal-light.png")
        val oldConfirm = compose.onNodeWithTag("dedup_entry_confirm")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("dedup_entry_cancel").performClick()
        assertEquals(1, back)
        compose.runOnIdle { oldConfirm() }
        compose.onNodeWithTag("dedup_primary_action").assertDoesNotExist()
        assertEquals(0, executions)
    }

    @Test fun entryNoticeLargeDarkTextReturnsOnlyOnReentry() {
        state = ready()
        show(textScale = 1.6f, dark = true, acceptEntryNotice = false)
        assertEquals(0f, compose.onNodeWithTag("dedup_entry_body").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        compose.onNodeWithText(text(R.string.dedup_entry_common)).assertIsDisplayed()
        screenshot("entry-merge-zh-large-dark-top.png")
        compose.onNodeWithTag("dedup_entry_scope").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dedup_entry_confirm").assertIsDisplayed()
        screenshot("entry-merge-zh-large-dark.png")
        compose.onNodeWithTag("dedup_entry_confirm").performClick()
        compose.runOnIdle { state = state.copy(isLoading = true) }
        compose.onNodeWithTag("dedup_entry_notice").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(isLoading = false); screenVisible = false }
        compose.runOnIdle { screenVisible = true }
        compose.onNodeWithTag("dedup_entry_notice").assertIsDisplayed()
        compose.onNodeWithTag("dedup_primary_action").assertDoesNotExist()
        assertEquals(0, executions)
    }

    @Test fun searchSelectionAndNewTargetRemainUsableWithManyDatabases() {
        show()
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithTag("dedup_source_search").performTextInput("42")
        compose.onNodeWithText(text(R.string.dedup_merge_select_results)).performClick()
        assertEquals(setOf("keepass:42"), selectedSearchKeys)
        compose.runOnIdle { keyboard?.hide() }
        File(context.filesDir, "dedup-validation").apply { mkdirs() }
            .resolve("source-selection-tree.txt").writeText(compose.onNode(isDialog(), useUnmergedTree = true).printToString(20))
        compose.onNode(hasText("Source 42") and hasAnyAncestor(isSelected()), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_done)).performClick()
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_create_target)).assertIsDisplayed().performClick()
        assertEquals(1, created)
        compose.onNodeWithText(text(R.string.dedup_merge_target_title)).performClick()
        compose.onNodeWithTag("dedup_target_search").performTextInput("Personal")
        compose.runOnIdle { keyboard?.hide() }
        compose.onNode(hasText("Personal") and !hasSetTextAction()).performClick()
        compose.runOnIdle { assertEquals(target, state.selectedMergeTarget) }
        screenshot("selection.png")
    }

    @Test fun previewFiltersExpandAndConfirmationDoesNotWriteUntilAccepted() {
        state = ready()
        show()
        screenshot("overview-zh.png")
        compose.onNodeWithText(text(R.string.dedup_merge_preview_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_filter_conflict, 1)).performClick()
        compose.onNodeWithText("Example login").performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_kept_from, "Source 1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_target_variant)).assertExists()
        compose.onAllNodesWithText("synthetic secret", substring = true).assertCountEquals(0)
        screenshot("preview-zh.png")
        compose.onNodeWithTag("dedup_preview_search").performTextInput("unmatched")
        compose.onNodeWithText(text(R.string.dedup_merge_filter_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_done)).performClick()
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        assertEquals(0, executions)
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_start)).performClick()
        assertEquals(1, executions)
    }

    @Test fun passkeyPreviewShowsPreservedEntriesOnly() {
        val key = takagi.ru.monica.data.PasskeyEntry(credentialId = "synthetic", rpId = "example.invalid", rpName = "Example",
            userId = "AQID", userName = "alice", userDisplayName = "Ready key", publicKey = "public", privateKeyAlias = "synthetic")
        state = ready().let { ready -> ready.copy(mergePlan = ready.mergePlan.copy(totalSourcePasskeys = 2,
            unsupportedSourcePasskeys = 2, previewPasskeys = listOf(
                DedupResolvedPasskey("ready-passkey", key, listOf(5), listOf("Source 1"), "Source 1"),
                DedupResolvedPasskey("skipped-passkey", key.copy(userDisplayName = "Old counter"), listOf(6), listOf("Source 2"),
                    "Source 2", skipReason = DedupPasskeySkipReason.PRESERVED)))) }
        show()
        compose.onNodeWithText(text(R.string.dedup_merge_preview_title)).performScrollTo().performClick()
        compose.onNodeWithTag("dedup_preview_search").performTextInput("Old counter")
        compose.runOnIdle { keyboard?.hide() }
        compose.onNodeWithText(text(R.string.dedup_passkey_preserved)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_filter_skip, state.mergePlan.skippedItems)).performClick()
        compose.onNode(hasText("Old counter") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_filter_write, state.mergePlan.writableItems)).performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_filter_empty)).assertIsDisplayed()
    }

    @Test fun passkeyReminderContinuesOnlyToTheNormalMergeConfirmation() {
        state = readyWithPasskeys()
        show()
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithTag("dedup_passkey_dialog").assertIsDisplayed()
        compose.onNodeWithTag("dedup_passkey_retention_list")
            .performScrollToNode(hasTestTag("dedup_passkey_retained:project:keepass:1:linked-project"))
        compose.onNode(hasText("Linked source project") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onAllNodes(hasText("Hidden bound key") and hasAnyAncestor(isDialog())).assertCountEquals(0)
        compose.onNodeWithTag("dedup_passkey_retention_list")
            .performScrollToNode(hasTestTag("dedup_passkey_retained:passkey:standalone-key"))
        compose.onNode(hasText("Standalone source key") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        screenshot("passkey-retention-zh.png")
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithTag("dedup_merge_confirmation").assertDoesNotExist()
        assertEquals(0, executions)
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithTag("dedup_passkey_continue").performClick()
        compose.onNodeWithTag("dedup_merge_confirmation").assertIsDisplayed()
        assertEquals(0, executions)
        compose.onNodeWithText(text(R.string.dedup_merge_start)).performClick()
        assertEquals(1, executions)
    }

    @Test fun allPasskeysHaveOnlyAReadOnlyExplanation() {
        state = readyWithPasskeys().let { ready -> ready.copy(mergePlan = ready.mergePlan.copy(
            uniquePasswords = 0, totalSourcePasswords = 0, previewPasswords = emptyList(),
            duplicateGroups = 0, passwordConflictGroups = 0
        )) }
        show(textScale = 1.6f, dark = true)
        compose.onNodeWithTag("dedup_primary_action").assertIsNotEnabled()
        compose.onNodeWithTag("dedup_passkey_notice").performScrollTo().performClick()
        compose.onNodeWithTag("dedup_passkey_close").assertIsDisplayed()
        compose.onNodeWithTag("dedup_passkey_continue").assertDoesNotExist()
        compose.onNodeWithTag("dedup_passkey_close").performClick()
        assertEquals(0, executions)
    }

    @Test fun replacedPlanAndLockRejectLatePasskeyAndMergeCallbacks() {
        state = readyWithPasskeys()
        show()
        compose.onNodeWithTag("dedup_primary_action").performClick()
        val oldContinue = compose.onNodeWithTag("dedup_passkey_continue")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(mergePlan = state.mergePlan.copy(reviewToken = "new-plan")) }
        compose.onNodeWithTag("dedup_passkey_dialog").assertDoesNotExist()
        compose.runOnIdle { oldContinue() }
        compose.onNodeWithTag("dedup_merge_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithTag("dedup_passkey_continue").performClick()
        val oldExecute = compose.onNode(hasText(text(R.string.dedup_merge_start)) and hasAnyAncestor(isDialog()))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = DedupEngineUiState(isLoading = false) }
        compose.onNodeWithTag("dedup_merge_confirmation").assertDoesNotExist()
        compose.runOnIdle { oldExecute() }
        assertEquals(0, executions)
    }

    private fun readyWithPasskeys(): DedupEngineUiState {
        val key = takagi.ru.monica.data.PasskeyEntry(credentialId = "retained", rpId = "example.invalid", rpName = "Example",
            userId = "AQID", userName = "alice", userDisplayName = "Hidden bound key", publicKey = "public",
            privateKeyAlias = "synthetic", boundPasswordId = 5)
        return ready().let { ready -> ready.copy(mergePlan = ready.mergePlan.copy(
            totalSourcePasskeys = 2, unsupportedSourcePasskeys = 2,
            passkeyRetainedProjects = listOf(DedupPasskeyRetainedProject("keepass:1", "Source 1", "linked-project", "Linked source project", listOf(5))),
            previewPasskeys = listOf(
                DedupResolvedPasskey("bound-key", key, listOf(5), listOf("Source 1"), "Source 1"),
                DedupResolvedPasskey("standalone-key", key.copy(credentialId = "standalone", boundPasswordId = null,
                    userDisplayName = "Standalone source key"), listOf(6), listOf("Source 2"), "Source 2")
            )
        )) }
    }

    @Test fun incompatibleSourceProjectsRemainSearchableInSkippedPreview() {
        state = ready().let { ready -> ready.copy(mergePlan = ready.mergePlan.copy(
            unsupportedItems = listOf(DedupMergeUnsupportedItem("Native database", "Retained native project"))
        )) }
        show()
        compose.onNodeWithText(text(R.string.dedup_merge_preview_title)).performScrollTo().performClick()
        compose.onNodeWithTag("dedup_preview_search").performTextInput("Retained native project")
        compose.runOnIdle { keyboard?.hide() }
        compose.onNodeWithText(text(R.string.dedup_merge_filter_skip, state.mergePlan.skippedItems)).performClick()
        compose.onNode(hasText("Retained native project") and !hasSetTextAction()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Native database").assertExists()
        compose.onNodeWithText(text(R.string.single_dedup_unsupported)).assertExists()
        assertEquals(0, executions)
    }

    @Test fun runningMergeShowsProgressAndRequiresConfirmationToStop() {
        state = ready().copy(isExecutingMerge = true,
            executionProgress = DedupMergeExecutionProgress(1, 2, "Synthetic entry"))
        show()
        compose.onNodeWithText(text(R.string.dedup_merge_progress, 1, 2)).assertIsDisplayed()
        compose.onNodeWithTag("dedup_primary_action").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.dedup_merge_stop_action)).performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_continue_action)).performClick()
        assertEquals(0, cancellations)
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNode(hasText(text(R.string.dedup_merge_stop_action)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, cancellations)
        assertEquals(0, back)
    }

    @Test fun retainedDatabasesUseTheirOwnListWithoutCreatingSkippedProjects() {
        val retained = (101..125).map {
            DedupMergeSourceOption("mdbx:$it", DedupMergeSourceKind.MDBX, "Retained database $it", 98765)
        }
        state = ready().let { ready -> ready.copy(
            sourceOptions = ready.sourceOptions + retained,
            selectedMergeSourceKeys = ready.selectedMergeSourceKeys + retained.map { it.key },
            mergePlan = ready.mergePlan.copy(
                selectedSources = ready.mergePlan.selectedSources + retained,
                retainedSources = retained
            )
        ) }
        show()
        compose.onNodeWithTag("dedup_retained_sources_notice").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_retained_source_reason)).assertIsDisplayed()
        compose.onNodeWithTag("dedup_retained_sources_list")
            .performScrollToNode(hasTestTag("dedup_retained_source:mdbx:125"))
        compose.onNodeWithText("Retained database 125").assertIsDisplayed()
        compose.onAllNodesWithText("98765", substring = true).assertCountEquals(0)
        screenshot("retained-databases-zh.png")
        compose.onNodeWithText(text(R.string.dedup_merge_done)).performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_preview_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_filter_skip, 0)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_filter_empty)).assertIsDisplayed()
        compose.onAllNodes(hasText("Retained database", substring = true) and hasAnyAncestor(isDialog())).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.dedup_merge_done)).performClick()
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithTag("dedup_confirm_retained_sources").performScrollTo()
            .assertTextEquals(text(R.string.dedup_merge_retained_sources_count, retained.size)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        assertEquals(0, executions)
    }

    @Test fun mdbxIndependentCopyNoticeIsVisibleBeforeConfirmingAtLargeText() {
        state = ready()
        show(textScale = 1.6f, dark = true)
        compose.onNodeWithTag("dedup_mdbx_target_notice").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(mergePlan = state.mergePlan.copy(mdbxTargetAddsIndependentCopies = true))
        }
        compose.onNodeWithTag("dedup_mdbx_target_notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_mdbx_target_copies)).assertIsDisplayed()
        screenshot("mdbx-copy-notice-zh-large-dark.png")
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithTag("dedup_confirm_mdbx_target").performScrollTo()
            .assertTextEquals(text(R.string.dedup_merge_mdbx_target_copies)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.dedup_merge_start)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        screenshot("mdbx-copy-confirm-zh-large-dark.png")
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        assertEquals(0, executions)
    }

    @Test fun polishLargeTextAndDarkThemeKeepActionsReachable() {
        screenLocale = Locale.forLanguageTag("pl")
        state = ready()
        show(textScale = 1.5f, dark = true)
        compose.onNodeWithTag("dedup_primary_action").assertIsDisplayed().assertIsEnabled()
        screenshot("overview-pl-large-dark.png")
        compose.onNodeWithText(text(R.string.dedup_merge_target_title)).performScrollTo().performClick()
        compose.onNodeWithTag("dedup_target_search").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_create_target)).assertIsDisplayed()
        screenshot("targets-pl-large-dark.png")
        compose.onNodeWithText(text(R.string.dedup_merge_done)).performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_policy_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_policy_newest)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(DedupConflictPolicy.NEWEST, state.conflictPolicy) }
    }

    private fun ready(): DedupEngineUiState {
        val password = PasswordEntry(id = 0, title = "Example login", username = "alice", website = "https://example.invalid", password = "synthetic secret")
        val plan = DedupMergePlan(selectedSources = sources.take(2), target = target,
            totalSourcePasswords = 4, uniquePasswords = 2, duplicateGroups = 2, passwordConflictGroups = 1,
            previewPasswords = listOf(
                DedupResolvedPassword("first", password, emptyList(), listOf(1, 2), listOf("Source 1", "Source 2"),
                    setOf("Password"), targetHasDifferentContent = true, preferredSourceLabel = "Source 1"),
                DedupResolvedPassword("second", password.copy(title = "Second login"), emptyList(), listOf(3, 4),
                    listOf("Source 1", "Source 2"), emptySet(), preferredSourceLabel = "Source 2")))
        return state.copy(selectedMergeSourceKeys = sources.take(2).map { it.key }.toSet(), selectedMergeTarget = target, mergePlan = plan)
    }

    private fun show(textScale: Float = 1f, dark: Boolean = false, acceptEntryNotice: Boolean = true) {
        compose.setContent {
            val activityContext = LocalContext.current
            keyboard = LocalSoftwareKeyboardController.current
            val localized = remember(activityContext) {
                // Dialogs create their own context from the Activity. Exercise the real
                // locale there too, instead of localising only the screen composition.
                val resources = activityContext.resources
                val original = Configuration(resources.configuration)
                @Suppress("DEPRECATION")
                resources.updateConfiguration(Configuration(original).apply {
                    setLocale(screenLocale)
                    fontScale = textScale
                }, resources.displayMetrics)
                restoreConfiguration = {
                    @Suppress("DEPRECATION")
                    resources.updateConfiguration(original, resources.displayMetrics)
                }
                ContextThemeWrapper(activityContext, 0).apply {
                    applyOverrideConfiguration(Configuration(activityContext.resources.configuration).apply { setLocale(screenLocale) })
                }
            }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, textScale)) {
                MonicaTheme(darkTheme = dark) {
                    if (screenVisible) DedupEngineScreen(uiState = state, onNavigateBack = { back++ }, onRefresh = {},
                        onToggleSource = { key -> state = state.copy(selectedMergeSourceKeys =
                            if (key in state.selectedMergeSourceKeys) state.selectedMergeSourceKeys - key else state.selectedMergeSourceKeys + key) },
                        onSelectAllSources = { keys -> selectedSearchKeys = keys; state = state.copy(selectedMergeSourceKeys = state.selectedMergeSourceKeys + keys) },
                        onClearSources = { state = state.copy(selectedMergeSourceKeys = emptySet()) },
                        onSelectTarget = { state = state.copy(selectedMergeTarget = it) }, onCreateMdbxTarget = { created++ },
                        onConflictPolicyChange = { state = state.copy(conflictPolicy = it, mergePlan = state.mergePlan.copy(conflictPolicy = it)) },
                        onExecuteMerge = { executions++ }, onCancelMerge = { cancellations++ }, onConsumeMessage = {})
                }
            }
        }
        if (acceptEntryNotice) compose.onNodeWithTag("dedup_entry_confirm").performClick()
    }

    private fun text(id: Int, vararg args: Any): String = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(screenLocale) }).getString(id, *args)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(context.filesDir, "dedup-validation").apply { mkdirs() }
        val dialogs = compose.onAllNodes(isDialog())
        val node = if (dialogs.fetchSemanticsNodes().isEmpty()) compose.onRoot() else dialogs.onFirst()
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
