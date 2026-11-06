package takagi.ru.monica.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.dedup.*
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.viewmodel.*

/** Uses the actual ViewModels and screens; only provider IO is replaced by counted synthetic operations. */
@RunWith(AndroidJUnit4::class)
class DedupEntryNoticeInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var singleVm: SingleVaultDedupViewModel? = null
    private var mergeVm: DedupEngineViewModel? = null

    @After fun disposeModels() {
        instrumentation.runOnMainSync { singleVm?.invalidate(); mergeVm?.invalidate() }
    }

    @Test fun singleEntryCancelDoesNotAnalyzeAndAcceptedReentryUsesNormalReview() {
        val operations = CountedSingle()
        lateinit var vm: SingleVaultDedupViewModel
        instrumentation.runOnMainSync {
            vm = SingleVaultDedupViewModel(operations, Dispatchers.Main.immediate)
            singleVm = vm
        }
        var visible by mutableStateOf(true)
        var exits = 0
        compose.setContent {
            val state by vm.uiState.collectAsState()
            MonicaTheme {
                if (visible) SingleVaultDedupScreen(
                    state, onNavigateBack = { exits++; vm.invalidate(); visible = false },
                    onRefresh = vm::refresh, onSelectSource = vm::selectSource,
                    onIncludeArchived = vm::setIncludeArchived, onAnalyze = vm::analyze,
                    onToggleCandidate = vm::toggleCandidate, onReviewSelection = vm::reviewSelection,
                    onDismissReview = vm::dismissReview, onConfirmCleanup = vm::confirmCleanup,
                    onReviewRecovery = vm::reviewRecovery, onConfirmRecovery = vm::confirmRecovery,
                    onCancel = vm::cancel
                )
            }
        }
        compose.onNodeWithTag("dedup_entry_notice").assertIsDisplayed()
        compose.onNodeWithTag("single_dedup_primary").assertDoesNotExist()
        compose.onNodeWithTag("dedup_entry_cancel").performClick()
        assertEquals(1, exits)
        assertEquals(0, operations.analyses.get())
        assertEquals(0, operations.writes.get())
        compose.runOnIdle { vm.refresh(); visible = true }
        compose.onNodeWithTag("dedup_entry_notice").assertIsDisplayed()
        compose.onNodeWithTag("dedup_entry_confirm").performClick()
        compose.onNodeWithTag("single_dedup_choose_source").performScrollTo().performClick()
        compose.onNodeWithTag("single_dedup_source_local").performClick()
        compose.onNodeWithTag("single_dedup_primary").performClick()
        compose.waitUntil { vm.uiState.value.analysis != null }
        assertEquals(1, operations.analyses.get())
        assertEquals(0, operations.writes.get())
        compose.onNodeWithTag("single_dedup_check_B").performScrollTo().performClick()
        compose.onNodeWithTag("single_dedup_primary").performClick()
        compose.onNodeWithTag("single_dedup_confirmation").assertExists()
        assertEquals(0, operations.writes.get())
        compose.onNodeWithTag("single_dedup_confirm").performClick()
        compose.waitUntil { operations.writes.get() == 1 }
    }

    @Test fun mergeEntryCancelDoesNotAnalyzeAndAcceptedReentryStillNeedsWriteConfirmation() {
        val operations = CountedMerge()
        lateinit var vm: DedupEngineViewModel
        instrumentation.runOnMainSync {
            vm = DedupEngineViewModel(operations,
                StringResolver { id, arguments -> context.getString(id, *arguments) }, Dispatchers.Main.immediate)
            mergeVm = vm
        }
        var visible by mutableStateOf(true)
        var exits = 0
        compose.setContent {
            val state by vm.uiState.collectAsState()
            MonicaTheme {
                if (visible) DedupEngineScreen(
                    uiState = state, onNavigateBack = { exits++; vm.invalidate(); visible = false },
                    onRefresh = vm::refresh, onToggleSource = vm::toggleMergeSource,
                    onSelectAllSources = { vm.selectAllSources(it) }, onClearSources = vm::clearSources,
                    onSelectTarget = vm::selectMergeTarget, onCreateMdbxTarget = {},
                    onConflictPolicyChange = vm::updateConflictPolicy, onExecuteMerge = vm::executeMerge,
                    onCancelMerge = vm::cancelMerge, onConsumeMessage = vm::consumeMessage
                )
            }
        }
        compose.onNodeWithTag("dedup_entry_notice").assertIsDisplayed()
        compose.onNodeWithTag("dedup_primary_action").assertDoesNotExist()
        compose.onNodeWithTag("dedup_entry_cancel").performClick()
        assertEquals(1, exits)
        assertEquals(0, operations.analyses.get())
        assertEquals(0, operations.writes.get())
        compose.runOnIdle { vm.refresh(); visible = true }
        compose.onNodeWithTag("dedup_entry_confirm").performClick()
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNode(hasText("Source database") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(context.getString(R.string.dedup_merge_done)).performClick()
        assertEquals(0, operations.analyses.get())
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNode(hasText("Local target") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil { vm.uiState.value.canExecuteMerge }
        assertEquals(1, operations.analyses.get())
        assertEquals(0, operations.writes.get())
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithTag("dedup_merge_confirmation").assertIsDisplayed()
        assertEquals(0, operations.writes.get())
        compose.onNodeWithText(context.getString(R.string.dedup_merge_start)).performClick()
        compose.waitUntil { operations.writes.get() == 1 }
    }

    private class CountedSingle : SingleVaultDedupOperations {
        val analyses = AtomicInteger()
        val writes = AtomicInteger()
        override suspend fun sources() = listOf(SingleVaultDedupUiSource("local", "Local source", true))
        override suspend fun analyze(sourceKey: String, includeArchived: Boolean): SingleVaultDedupUiAnalysis {
            analyses.incrementAndGet()
            return SingleVaultDedupUiAnalysis("review", sourceKey,
                listOf(SingleVaultDedupUiProject("A", "Keeper", contentCount = 3),
                    SingleVaultDedupUiProject("B", "Covered", contentCount = 2)),
                listOf(SingleVaultDedupUiCandidate("B", "A", 2, 1)))
        }
        override suspend fun clean(planId: String, selectedProjectIds: Set<String>): SingleVaultDedupUiOutcome {
            check(planId == "review" && selectedProjectIds == setOf("B"))
            writes.incrementAndGet()
            return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.SUCCESS, 1, "recovery")
        }
        override suspend fun recoveryBatches() = emptyList<SingleVaultDedupUiRecoveryBatch>()
        override suspend fun restore(batchId: String): SingleVaultDedupUiOutcome = error("No restore expected")
        override fun invalidate() = Unit
    }

    private class CountedMerge : DedupMergeOperations {
        val analyses = AtomicInteger()
        val writes = AtomicInteger()
        val source = DedupMergeSourceOption("keepass:1", DedupMergeSourceKind.KEEPASS, "Source database", 2)
        override suspend fun getSourceOptions() = listOf(source)
        override suspend fun getTargetOptions() = listOf(
            DedupMergeTargetOption(DedupMergeTarget.MonicaLocal, "monica", "Local target", 0))
        override suspend fun buildPlan(selectedSourceKeys: Set<String>, target: DedupMergeTarget?,
            conflictPolicy: DedupConflictPolicy): DedupMergePlan {
            check(selectedSourceKeys == setOf(source.key) && target == DedupMergeTarget.MonicaLocal)
            analyses.incrementAndGet()
            return DedupMergePlan(selectedSources = listOf(source), target = target, conflictPolicy = conflictPolicy,
                totalSourcePasswords = 2, uniquePasswords = 1, duplicateGroups = 1,
                previewPasswords = listOf(DedupResolvedPassword("copy",
                    PasswordEntry(title = "Copied project", website = "https://example.invalid", username = "synthetic", password = "synthetic"),
                    emptyList(), listOf(1, 2), listOf(source.label), emptySet(), preferredSourceLabel = source.label)))
        }
        override suspend fun executePlan(plan: DedupMergePlan,
            onProgress: (DedupMergeExecutionProgress) -> Unit): DedupMergeExecutionResult {
            check(plan.writableItems == 1)
            writes.incrementAndGet()
            return DedupMergeExecutionResult(insertedPasswords = 1, skippedExistingPasswords = 0,
                failedPasswords = 0, targetLabel = "Local target")
        }
    }
}

