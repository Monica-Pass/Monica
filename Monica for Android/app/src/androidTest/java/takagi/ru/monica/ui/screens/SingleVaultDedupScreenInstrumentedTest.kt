package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.*
import takagi.ru.monica.R

@RunWith(AndroidJUnit4::class)
class SingleVaultDedupScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(ready())
    private var executions = 0
    private var restores = 0
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var restoreConfiguration: (() -> Unit)? = null
    private var screenVisible by mutableStateOf(true)
    private var navigations = 0

    @After fun restoreLocale() { compose.runOnUiThread { restoreConfiguration?.invoke() } }

    @Test fun entryNoticeCancelsWithoutMountingCleanupControlsOrAcceptingLateClick() {
        show(locale = Locale.SIMPLIFIED_CHINESE, acceptEntryNotice = false)
        compose.onNodeWithTag("single_dedup_primary").assertDoesNotExist()
        assertEquals(0f, compose.onNodeWithTag("dedup_entry_body").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        compose.onNodeWithText(chineseText(R.string.dedup_entry_common)).assertIsDisplayed()
        nativeScreenshot("entry-single-zh-normal-light", entryNotice = true)
        val oldConfirm = compose.onNodeWithTag("dedup_entry_confirm")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("dedup_entry_cancel").performClick()
        assertEquals(1, navigations)
        compose.runOnIdle { oldConfirm() }
        compose.onNodeWithTag("single_dedup_primary").assertDoesNotExist()
        assertEquals(0, executions)
        assertEquals(0, restores)
    }

    @Test fun entryNoticeLargeChineseTextIsShownAgainOnlyAfterReentry() {
        show(fontScale = 1.6f, dark = true, locale = Locale.SIMPLIFIED_CHINESE, acceptEntryNotice = false)
        assertEquals(0f, compose.onNodeWithTag("dedup_entry_body").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        compose.onNodeWithText(chineseText(R.string.dedup_entry_common)).assertIsDisplayed()
        nativeScreenshot("entry-single-zh-large-dark-top", entryNotice = true)
        compose.onNodeWithTag("dedup_entry_scope").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dedup_entry_confirm").assertIsDisplayed()
        nativeScreenshot("entry-single-zh-large-dark", entryNotice = true)
        compose.onNodeWithTag("dedup_entry_confirm").performClick()
        compose.runOnIdle { state = state.copy(includeArchived = true) }
        compose.onNodeWithTag("dedup_entry_notice").assertDoesNotExist()
        compose.runOnIdle { screenVisible = false }
        compose.runOnIdle { screenVisible = true }
        compose.onNodeWithTag("dedup_entry_notice").assertIsDisplayed()
        compose.onNodeWithTag("single_dedup_primary").assertDoesNotExist()
        assertEquals(0, executions)
    }

    @Test fun candidatesStartUncheckedAndRequireExplicitConfirmation() {
        show()
        compose.onNodeWithTag("single_dedup_primary").assertIsNotEnabled()
        compose.onNodeWithTag("single_dedup_check_B").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("single_dedup_primary").assertIsEnabled().performClick()
        compose.onNodeWithTag("single_dedup_confirmation").assertExists()
        assertEquals(0, executions)
        compose.onNodeWithTag("single_dedup_confirm").performClick()
        assertEquals(1, executions)
    }

    @Test fun analysisOnlySourceCannotSelectCandidates() {
        state = ready().copy(sources = listOf(SingleVaultDedupUiSource("local", "Bitwarden", false,
            SingleVaultDedupUiIssue.ANALYSIS_ONLY)))
        show()
        compose.onNodeWithTag("single_dedup_check_B").performScrollTo().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("single_dedup_primary").assertIsNotEnabled()
        assertEquals(0, executions)
    }

    @Test fun passkeyProjectsRemainExcludedAndReminderPrecedesCleanup() {
        state = readyWithPasskeys()
        show(fontScale = 1.6f, dark = true, locale = Locale.SIMPLIFIED_CHINESE)
        compose.onNodeWithTag("single_dedup_check_passkey-project").assertDoesNotExist()
        compose.onNodeWithTag("single_dedup_check_B").performScrollTo().performClick()
        compose.onNodeWithTag("single_dedup_primary").performClick()
        compose.onNodeWithTag("dedup_passkey_dialog").assertIsDisplayed()
        compose.onNodeWithTag("single_dedup_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("dedup_passkey_retention_list")
            .performScrollToNode(hasTestTag("dedup_passkey_retained:passkey-project"))
        compose.onNode(hasText("关联 Passkey 的项目") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithTag("dedup_passkey_continue").assertIsDisplayed()
        nativeScreenshot("passkey-retention-zh-large-dark")
        compose.onNodeWithTag("dedup_passkey_continue").performClick()
        compose.onNodeWithTag("single_dedup_confirmation").assertExists()
        assertEquals(0, executions)
        compose.onNodeWithTag("single_dedup_confirm").performClick()
        assertEquals(1, executions)
    }

    @Test fun allPasskeyProjectsCanOnlyViewAndCloseRetentionExplanation() {
        state = readyWithPasskeys().let { ready ->
            val analysis = checkNotNull(ready.analysis)
            ready.copy(analysis = analysis.copy(projects = analysis.projects.filter { it.issues.isNotEmpty() }, candidates = emptyList()))
        }
        show(locale = Locale.SIMPLIFIED_CHINESE)
        compose.onNodeWithTag("single_dedup_primary").assertIsNotEnabled()
        compose.onNodeWithTag("single_dedup_passkey_notice").performScrollTo().performClick()
        compose.onNodeWithTag("dedup_passkey_dialog").assertIsDisplayed()
        compose.onNodeWithTag("dedup_passkey_continue").assertDoesNotExist()
        compose.onNodeWithTag("dedup_passkey_close").performClick()
        assertEquals(0, executions)
    }

    @Test fun selectionChangeAndLockRejectLatePasskeyReviewCallbacks() {
        state = readyWithPasskeys()
        show()
        compose.onNodeWithTag("single_dedup_check_B").performScrollTo().performClick()
        compose.onNodeWithTag("single_dedup_primary").performClick()
        val oldContinue = compose.onNodeWithTag("dedup_passkey_continue")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(selectedProjectIds = emptySet(), review = null) }
        compose.onNodeWithTag("dedup_passkey_dialog").assertDoesNotExist()
        compose.runOnIdle { oldContinue() }
        compose.onNodeWithTag("single_dedup_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("single_dedup_check_B").performScrollTo().performClick()
        compose.onNodeWithTag("single_dedup_primary").performClick()
        compose.onNodeWithTag("dedup_passkey_continue").performClick()
        val oldCleanup = compose.onNodeWithTag("single_dedup_confirm")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = SingleVaultDedupUiState() }
        compose.onNodeWithTag("single_dedup_confirmation").assertDoesNotExist()
        compose.runOnIdle { oldCleanup() }
        assertEquals(0, executions)
    }

    private fun readyWithPasskeys(): SingleVaultDedupUiState = ready().let { ready ->
        val analysis = checkNotNull(ready.analysis)
        ready.copy(analysis = analysis.copy(projects = analysis.projects + listOf(
            SingleVaultDedupUiProject("passkey-project", "关联 Passkey 的项目", issues = setOf(SingleVaultDedupUiIssue.PASSKEY_REFERENCE)),
            SingleVaultDedupUiProject("standalone-passkey", "独立 Passkey", issues = setOf(SingleVaultDedupUiIssue.PASSKEY_PRESERVED))
        )))
    }

    @Test fun replacingPlanImmediatelyRemovesConfirmationAndSelection() {
        show()
        compose.onNodeWithTag("single_dedup_check_B").performScrollTo().performClick()
        compose.onNodeWithTag("single_dedup_primary").performClick()
        compose.onNodeWithTag("single_dedup_confirmation").assertExists()
        compose.runOnIdle { state = ready().copy(analysis = ready().analysis!!.copy(planId = "replacement")) }
        compose.onNodeWithTag("single_dedup_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("single_dedup_primary").assertIsNotEnabled()
        assertEquals(0, executions)
    }

    @Test fun invalidationRemovesOpenCoverageTitlesImmediately() {
        show()
        compose.onNodeWithTag("single_dedup_candidate_B").performScrollTo().performClick()
        assertTrue(compose.onAllNodesWithText("Synthetic candidate", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle { state = SingleVaultDedupUiState() }
        compose.onAllNodesWithText("Synthetic candidate", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Synthetic keeper", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("single_dedup_primary").assertIsNotEnabled()
    }

    @Test fun recoveryIsReachableAfterScreenReentry() {
        state = ready().copy(analysis = null)
        show()
        compose.onNodeWithTag("single_dedup_recovery").performScrollTo().performClick()
        compose.onNodeWithTag("single_dedup_restore_recovery").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("recovery", state.recoveryToReview) }
        assertEquals(0, restores)
    }

    @Test fun largeTextKeepsSelectionAndConfirmationReachable() {
        show(fontScale = 1.6f)
        val selection = compose.onNodeWithTag("single_dedup_selection").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("single_dedup_primary").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        assertTrue("Large text moves selection above the action instead of shrinking it", selection.bottom <= action.top)
        compose.onNodeWithTag("single_dedup_check_B").performScrollTo().performClick()
        compose.onNodeWithTag("single_dedup_primary").assertIsDisplayed().performClick()
        compose.onNodeWithTag("single_dedup_confirm").assertIsDisplayed().performClick()
        assertEquals(1, executions)
    }

    @Test fun compactFooterAndControlsKeepMinimumTouchTargets() {
        show()
        val selection = compose.onNodeWithTag("single_dedup_selection").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("single_dedup_primary").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        assertTrue("Normal text keeps selection alongside the action", selection.center.y in action.top..action.bottom)
        compose.onNodeWithTag("single_dedup_choose_source").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("single_dedup_rules").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("single_dedup_recovery").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("single_dedup_check_B").performScrollTo().assertHeightIsAtLeast(48.dp).assertIsOff()
        assertEquals(0, executions)
    }

    @Test fun emptyReviewSectionUsesOnlyItsCompactStatistic() {
        show()
        compose.onNodeWithTag("single_dedup_statistics").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(noReviewMessage).assertIsDisplayed()
        compose.onNodeWithTag("single_dedup_blocked_heading").assertDoesNotExist()
        compose.onNodeWithTag("single_dedup_candidate_B").performScrollTo().assertIsDisplayed()
    }

    @Test fun restorationSuccessUsesRestoredMessage() {
        state = ready().copy(analysis = null, outcome = SingleVaultDedupUiOutcome(
            SingleVaultDedupUiOutcomeStatus.SUCCESS, 2, isRestoration = true))
        show()
        compose.onNodeWithTag("single_dedup_outcome").performScrollTo().assertTextEquals(restoredMessage)
    }

    @Test fun copiedRecoveryKeepsProjectCountSeparateFromNativeRecordCount() {
        state = ready().copy(analysis = null, outcome = SingleVaultDedupUiOutcome(
            SingleVaultDedupUiOutcomeStatus.SUCCESS, changedProjects = 1, isRestoration = true, restoredCopyCount = 2))
        show()
        compose.onNodeWithTag("single_dedup_outcome").performScrollTo().assertTextEquals(restoredCopiesMessage)
    }

    @Test fun chineseLightM3ScreenAndConfirmationRemainUsable() {
        chineseVisual("zh-light", confirmScreenshot = true)
    }

    @Test fun chineseDarkM3ScreenRetainsExplicitSelection() {
        chineseVisual("zh-dark", dark = true)
    }

    @Test fun chineseLargeTextM3ScreenScrollsAndStacksActions() {
        chineseVisual("zh-large", fontScale = 1.6f)
    }

    private fun chineseVisual(name: String, dark: Boolean = false, fontScale: Float = 1f,
        confirmScreenshot: Boolean = false) {
        state = ready().copy(
            sources = listOf(SingleVaultDedupUiSource("local", "Monica 本地 · 合成测试", true)),
            analysis = SingleVaultDedupUiAnalysis("plan", "local", listOf(
                SingleVaultDedupUiProject("A", "个人邮箱", "个人 / 登录", 3),
                SingleVaultDedupUiProject("B", "个人邮箱副本", "个人 / 登录", 2),
                SingleVaultDedupUiProject("C", "论坛账号", "个人 / 登录", 3)),
                listOf(SingleVaultDedupUiCandidate("B", "A", 2, 1))))
        show(fontScale = fontScale, dark = dark, locale = Locale.SIMPLIFIED_CHINESE)
        compose.onNodeWithTag("single_dedup_content").assertIsDisplayed().assert(hasScrollAction())
        compose.onNodeWithText(chineseText(R.string.single_dedup_readonly)).assertExists()
        compose.onNodeWithTag("single_dedup_choose_source").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Monica 本地 · 合成测试").assertIsDisplayed()
        compose.onNodeWithTag("single_dedup_primary").assertHeightIsAtLeast(48.dp).assertIsNotEnabled()
        if (fontScale > 1f) nativeScreenshot("zh-large-top")
        compose.onNodeWithTag("single_dedup_check_B").performScrollTo().assertIsOff().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("single_dedup_candidate_B").assertIsDisplayed()
        val selection = compose.onNodeWithTag("single_dedup_selection").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("single_dedup_primary").fetchSemanticsNode().boundsInRoot
        if (fontScale > 1f) {
            assertTrue("Chinese large text must have a stacked footer", selection.bottom <= action.top)
        } else {
            assertTrue("Chinese normal text must keep the compact footer", selection.center.y in action.top..action.bottom)
            compose.onNodeWithTag("single_dedup_content").performScrollToIndex(0)
        }
        nativeScreenshot(name)
        compose.onNodeWithTag("single_dedup_check_B").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("single_dedup_primary").assertIsEnabled().assertHasClickAction().performClick()
        compose.onNodeWithTag("single_dedup_confirmation").assertIsDisplayed()
        compose.onNodeWithText(chineseText(R.string.single_dedup_confirm_title, 1)).assertIsDisplayed()
        assertEquals(0, executions)
        compose.onNodeWithTag("single_dedup_confirm").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        if (confirmScreenshot) nativeScreenshot("zh-confirm", confirmation = true)
        compose.onNodeWithTag("single_dedup_confirm").performClick()
        assertEquals(1, executions)
    }

    private fun chineseText(id: Int, vararg arguments: Any) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }).getString(id, *arguments)

    private fun nativeScreenshot(name: String, confirmation: Boolean = false, entryNotice: Boolean = false) {
        if (entryNotice) compose.onNodeWithTag("dedup_entry_notice").assertIsDisplayed()
        else if (confirmation) compose.onNodeWithTag("single_dedup_confirmation").assertIsDisplayed()
        else compose.onNodeWithTag("single_dedup_content").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native screenshot unavailable" }
        try {
            val directory = File(context.filesDir, "dedup-m3-proof").apply { check(isDirectory || mkdirs()) }
            val output = File(directory, "$name.png")
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(output.length() > 0)
        } finally { bitmap.recycle() }
    }

    private var restoredMessage = ""
    private var restoredCopiesMessage = ""
    private var noReviewMessage = ""

    private fun show(fontScale: Float = 1f, dark: Boolean = false, locale: Locale? = null, acceptEntryNotice: Boolean = true) {
        val requestedFontScale = fontScale
        compose.setContent {
            val density = LocalDensity.current
            val activityContext = LocalContext.current
            val localized = remember(activityContext, locale, fontScale) {
                if (locale == null) activityContext else {
                    val resources = activityContext.resources
                    val original = Configuration(resources.configuration)
                    @Suppress("DEPRECATION")
                    resources.updateConfiguration(Configuration(original).apply { setLocale(locale); this.fontScale = requestedFontScale },
                        resources.displayMetrics)
                    restoreConfiguration = {
                        @Suppress("DEPRECATION")
                        resources.updateConfiguration(original, resources.displayMetrics)
                    }
                    ContextThemeWrapper(activityContext, 0).apply {
                        applyOverrideConfiguration(Configuration(activityContext.resources.configuration).apply { setLocale(locale) })
                    }
                }
            }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, fontScale)) {
                restoredMessage = rememberScreenStrings().get(R.string.single_dedup_restored, 2)
                restoredCopiesMessage = rememberScreenStrings().get(R.string.single_dedup_restored_copies, 1, 2)
                noReviewMessage = rememberScreenStrings().get(R.string.single_dedup_needs_review, 0)
                MonicaTheme(darkTheme = dark) {
                    if (screenVisible) SingleVaultDedupScreen(state, onNavigateBack = { navigations++ }, onRefresh = {}, onSelectSource = {},
                        onIncludeArchived = {}, onAnalyze = {},
                        onToggleCandidate = { key -> state = state.copy(selectedProjectIds =
                            if (key in state.selectedProjectIds) state.selectedProjectIds - key else state.selectedProjectIds + key,
                            review = null) },
                        onReviewSelection = { state = state.copy(review = SingleVaultDedupReview(state.analysis!!.planId,
                            state.selectedProjectIds.toSet())) },
                        onDismissReview = { state = state.copy(review = null, recoveryToReview = null) },
                        onConfirmCleanup = { executions++; state = state.copy(review = null) },
                        onReviewRecovery = { state = state.copy(recoveryToReview = it) },
                        onConfirmRecovery = { restores++; state = state.copy(recoveryToReview = null) },
                        onCancel = {})
                }
            }
        }
        if (acceptEntryNotice) compose.onNodeWithTag("dedup_entry_confirm").performClick()
    }

    private fun ready() = SingleVaultDedupUiState(
        sources = listOf(SingleVaultDedupUiSource("local", "Synthetic database", true)),
        selectedSourceKey = "local",
        analysis = SingleVaultDedupUiAnalysis("plan", "local", listOf(
            SingleVaultDedupUiProject("A", "Synthetic keeper", contentCount = 3),
            SingleVaultDedupUiProject("B", "Synthetic candidate", contentCount = 2),
            SingleVaultDedupUiProject("C", "Synthetic partial overlap", contentCount = 3)),
            listOf(SingleVaultDedupUiCandidate("B", "A", 2, 1))),
        recoveryBatches = listOf(SingleVaultDedupUiRecoveryBatch("recovery", "Synthetic database", 1793913600000L, 1)),
    )
}
