package takagi.ru.monica.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SingleVaultDedupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun analysisDefaultsToNoSelectionAndDoesNotCleanWithoutReview() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = ready(operations)
        assertTrue(vm.uiState.value.selectedProjectIds.isEmpty())
        assertFalse(vm.uiState.value.includeArchived)
        vm.confirmCleanup()
        vm.toggleCandidate("A")
        vm.toggleCandidate("missing")
        assertTrue(vm.uiState.value.selectedProjectIds.isEmpty())
        vm.toggleCandidate("B")
        vm.confirmCleanup()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(operations.cleaned.isEmpty())
        assertEquals(setOf("B"), vm.uiState.value.selectedProjectIds)
    }

    @Test fun exactReviewedSelectionExecutesOnceAndPublishesRecovery() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = ready(operations)
        vm.toggleCandidate("B")
        vm.reviewSelection()
        assertEquals(SingleVaultDedupReview("plan", setOf("B")), vm.uiState.value.review)
        vm.confirmCleanup()
        vm.confirmCleanup()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("plan" to setOf("B")), operations.cleaned)
        assertNull(vm.uiState.value.analysis)
        assertEquals("recovery", vm.uiState.value.recoveryBatches.single().id)
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, vm.uiState.value.outcome?.status)
    }

    @Test fun changedSelectionRevokesReview() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = ready(operations)
        vm.toggleCandidate("B"); vm.reviewSelection(); vm.toggleCandidate("B")
        assertNull(vm.uiState.value.review)
        vm.confirmCleanup()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(operations.cleaned.isEmpty())
    }

    @Test fun limitedOrLockedPlanCannotReviewEvenWithAStaleSelection() {
        val state = SingleVaultDedupUiState(sources = listOf(SingleVaultDedupUiSource("local", "Local", true)),
            selectedSourceKey = "local", analysis = plan("local"), selectedProjectIds = setOf("B"))
        assertTrue(state.canReview)
        assertFalse(state.copy(analysis = state.analysis!!.copy(comparisonLimitReached = true)).canReview)
        assertFalse(state.copy(sources = listOf(state.sources.single().copy(issue = SingleVaultDedupUiIssue.LOCKED))).canReview)
    }

    @Test fun sourceAndArchiveChangesRemoveOldPlan() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = ready(operations)
        vm.toggleCandidate("B"); vm.reviewSelection()
        vm.setIncludeArchived(true)
        assertNull(vm.uiState.value.analysis)
        assertNull(vm.uiState.value.review)
        vm.analyze(); dispatcher.scheduler.advanceUntilIdle()
        vm.selectSource("bitwarden")
        assertNull(vm.uiState.value.analysis)
        assertTrue(vm.uiState.value.selectedProjectIds.isEmpty())
        assertTrue(operations.invalidations >= 4)
    }

    @Test fun analysisOnlySourceNeverAllowsSelectionOrCleanup() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = SingleVaultDedupViewModel(operations, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectSource("bitwarden"); vm.analyze(); dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.uiState.value.analysis)
        vm.toggleCandidate("B"); vm.reviewSelection(); vm.confirmCleanup()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.canReview)
        assertTrue(vm.uiState.value.selectedProjectIds.isEmpty())
        assertTrue(operations.cleaned.isEmpty())
    }

    @Test fun sourceCapabilityDowngradeDuringCaptureDisablesCleanup() = runTest(dispatcher) {
        val operations = FakeOperations().apply { scan = { source -> plan(source).copy(source =
            SingleVaultDedupUiSource(source, "Local", false, SingleVaultDedupUiIssue.RECOVERY_UNAVAILABLE)) } }
        val vm = ready(operations)
        assertNotNull(vm.uiState.value.analysis)
        assertFalse(vm.uiState.value.selectedSource!!.canClean)
        vm.toggleCandidate("B"); vm.reviewSelection(); vm.confirmCleanup()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.selectedProjectIds.isEmpty())
        assertTrue(operations.cleaned.isEmpty())
    }

    @Test fun mismatchedSourceCapabilityCannotAuthorizeAnotherVault() = runTest(dispatcher) {
        val operations = FakeOperations().apply { scan = { source -> plan(source).copy(source =
            SingleVaultDedupUiSource("other", "Other", true)) } }
        val vm = ready(operations)
        assertNull(vm.uiState.value.analysis)
        assertEquals(SingleVaultDedupUiOutcomeStatus.FAILED, vm.uiState.value.outcome?.status)
    }

    @Test fun lateUncancellableAnalysisCannotRestoreAfterLock() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        val operations = FakeOperations().apply {
            scan = { source -> withContext(NonCancellable) { finish.await(); plan(source) } }
        }
        val vm = SingleVaultDedupViewModel(operations, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectSource("local"); vm.analyze(); dispatcher.scheduler.runCurrent()
        val before = operations.invalidations
        vm.invalidate()
        assertEquals(before + 1, operations.invalidations)
        assertEquals(SingleVaultDedupUiState(), vm.uiState.value)
        finish.complete(Unit); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SingleVaultDedupUiState(), vm.uiState.value)
    }

    @Test fun failedRescanCannotReuseOldApproval() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = ready(operations)
        vm.toggleCandidate("B"); vm.reviewSelection()
        operations.scan = { error("synthetic provider payload must not reach UI") }
        vm.analyze(); dispatcher.scheduler.advanceUntilIdle()
        vm.confirmCleanup(); dispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.analysis)
        assertNull(vm.uiState.value.review)
        assertEquals(SingleVaultDedupUiOutcomeStatus.FAILED, vm.uiState.value.outcome?.status)
        assertTrue(operations.cleaned.isEmpty())
    }

    @Test fun candidateCannotDependOnAnotherDeletedCandidate() = runTest(dispatcher) {
        val operations = FakeOperations().apply {
            scan = { source -> plan(source).copy(candidates = listOf(
                SingleVaultDedupUiCandidate("B", "A", 2), SingleVaultDedupUiCandidate("A", "C", 3))) }
        }
        val vm = ready(operations)
        assertNull(vm.uiState.value.analysis)
        assertEquals(SingleVaultDedupUiOutcomeStatus.FAILED, vm.uiState.value.outcome?.status)
    }

    @Test fun unknownExecutionOutcomeDoesNotAutomaticallyRetry() = runTest(dispatcher) {
        val operations = FakeOperations().apply { failClean = true }
        val vm = ready(operations)
        vm.toggleCandidate("B"); vm.reviewSelection(); vm.confirmCleanup()
        dispatcher.scheduler.advanceUntilIdle()
        vm.confirmCleanup(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, operations.cleaned.size)
        assertEquals(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED, vm.uiState.value.outcome?.status)
        assertFalse(vm.uiState.value.isExecuting)
        assertNull(vm.uiState.value.analysis)
    }

    @Test fun restoreRequiresReviewOfAnAvailableBatch() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = ready(operations)
        vm.confirmRecovery(); vm.reviewRecovery("missing"); vm.confirmRecovery()
        assertTrue(operations.restored.isEmpty())
        vm.reviewRecovery("recovery"); vm.dismissReview(); vm.confirmRecovery()
        assertTrue(operations.restored.isEmpty())
        vm.reviewRecovery("recovery"); vm.confirmRecovery(); vm.confirmRecovery()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("recovery"), operations.restored)
        assertEquals(true, vm.uiState.value.outcome?.isRestoration)
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, vm.uiState.value.outcome?.status)
    }

    @Test fun cancellingExecutionRevokesProviderAndDiscardsLateResult() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        val operations = FakeOperations().apply { cleanWait = finish }
        val vm = ready(operations)
        vm.toggleCandidate("B"); vm.reviewSelection(); vm.confirmCleanup()
        dispatcher.scheduler.runCurrent()
        val before = operations.invalidations
        vm.cancel()
        assertEquals(before + 1, operations.invalidations)
        finish.complete(Unit); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED, vm.uiState.value.outcome?.status)
        assertNull(vm.uiState.value.analysis)
        assertFalse(vm.uiState.value.busy)
    }

    private fun ready(operations: FakeOperations): SingleVaultDedupViewModel {
        val vm = SingleVaultDedupViewModel(operations, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectSource("local"); vm.analyze(); dispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    private class FakeOperations : SingleVaultDedupOperations {
        var scan: suspend (String) -> SingleVaultDedupUiAnalysis = { plan(it) }
        var invalidations = 0
        var failClean = false
        var cleanWait: CompletableDeferred<Unit>? = null
        val cleaned = mutableListOf<Pair<String, Set<String>>>()
        val restored = mutableListOf<String>()
        override suspend fun sources() = listOf(SingleVaultDedupUiSource("local", "Local", true),
            SingleVaultDedupUiSource("bitwarden", "Bitwarden", false, SingleVaultDedupUiIssue.ANALYSIS_ONLY))
        override suspend fun analyze(sourceKey: String, includeArchived: Boolean) = scan(sourceKey)
        override suspend fun clean(planId: String, selectedProjectIds: Set<String>): SingleVaultDedupUiOutcome {
            cleaned += planId to selectedProjectIds
            cleanWait?.let { withContext(NonCancellable) { it.await() } }
            if (failClean) error("synthetic unconfirmed response")
            return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.SUCCESS, 1, "recovery")
        }
        override suspend fun recoveryBatches() = listOf(SingleVaultDedupUiRecoveryBatch("recovery", "Local", 1, 1))
        override suspend fun restore(batchId: String): SingleVaultDedupUiOutcome {
            restored += batchId
            return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.SUCCESS, 1, isRestoration = true)
        }
        override fun invalidate() { invalidations++ }
    }

    companion object {
        private fun plan(source: String) = SingleVaultDedupUiAnalysis("plan", source, listOf(
            SingleVaultDedupUiProject("A", "Example", contentCount = 3),
            SingleVaultDedupUiProject("B", "Example", contentCount = 2),
            SingleVaultDedupUiProject("C", "Example", contentCount = 3)),
            listOf(SingleVaultDedupUiCandidate("B", "A", 2, 1)))
    }
}
