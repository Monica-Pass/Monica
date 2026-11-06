package takagi.ru.monica.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SingleVaultDedupReview(val planId: String, val selectedProjectIds: Set<String>)

data class SingleVaultDedupUiState(
    val sources: List<SingleVaultDedupUiSource> = emptyList(),
    val selectedSourceKey: String? = null,
    val includeArchived: Boolean = false,
    val analysis: SingleVaultDedupUiAnalysis? = null,
    val selectedProjectIds: Set<String> = emptySet(),
    val review: SingleVaultDedupReview? = null,
    val recoveryBatches: List<SingleVaultDedupUiRecoveryBatch> = emptyList(),
    val recoveryToReview: String? = null,
    val outcome: SingleVaultDedupUiOutcome? = null,
    val isLoading: Boolean = false,
    val isAnalyzing: Boolean = false,
    val isExecuting: Boolean = false,
) {
    val busy: Boolean get() = isLoading || isAnalyzing || isExecuting
    val selectedSource get() = sources.singleOrNull { it.key == selectedSourceKey }
    val canAnalyze: Boolean get() = !busy && selectedSource?.issue !in setOf(
        SingleVaultDedupUiIssue.LOCKED, SingleVaultDedupUiIssue.SOURCE_UNAVAILABLE,
    ) && selectedSource != null
    val canReview: Boolean get() = !busy && selectedSource?.canClean == true &&
        selectedSource?.issue !in setOf(SingleVaultDedupUiIssue.LOCKED, SingleVaultDedupUiIssue.SOURCE_UNAVAILABLE) &&
        analysis?.comparisonLimitReached == false && analysis?.sourceKey == selectedSourceKey && selectedProjectIds.isNotEmpty() &&
        selectedProjectIds.all { id -> analysis?.candidates?.any { it.candidateKey == id } == true }
}

/** A UI approval is tied to one plan and exact selection; the provider verifies it again at commit. */
class SingleVaultDedupViewModel(
    private val operations: SingleVaultDedupOperations,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SingleVaultDedupUiState())
    val uiState: StateFlow<SingleVaultDedupUiState> = mutableState.asStateFlow()
    private var generation = 0L
    private var work: Job? = null

    init { refresh() }

    fun refresh() {
        if (mutableState.value.isExecuting) return
        revoke()
        val token = generation
        mutableState.value = mutableState.value.copy(
            analysis = null, selectedProjectIds = emptySet(), review = null,
            recoveryToReview = null, outcome = null, isLoading = true, isAnalyzing = false,
        )
        work = viewModelScope.launch {
            try {
                val sources = withContext(dispatcher) { operations.sources() }
                val batches = withContext(dispatcher) { operations.recoveryBatches() }
                if (generation != token) return@launch
                mutableState.value = mutableState.value.copy(
                    sources = sources, recoveryBatches = batches,
                    selectedSourceKey = mutableState.value.selectedSourceKey
                        ?.takeIf { key -> sources.any { it.key == key } },
                    isLoading = false,
                )
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { fail(token) }
        }
    }

    fun selectSource(key: String) {
        val state = mutableState.value
        if (state.isExecuting || state.sources.none { it.key == key } || state.selectedSourceKey == key) return
        revoke()
        mutableState.value = state.copy(selectedSourceKey = key, analysis = null,
            selectedProjectIds = emptySet(), review = null, outcome = null,
            isAnalyzing = false, isLoading = false, recoveryToReview = null)
    }

    fun setIncludeArchived(include: Boolean) {
        val state = mutableState.value
        if (state.isExecuting || state.includeArchived == include) return
        revoke()
        mutableState.value = state.copy(includeArchived = include, analysis = null,
            selectedProjectIds = emptySet(), review = null, outcome = null,
            isAnalyzing = false, isLoading = false, recoveryToReview = null)
    }

    fun analyze() {
        val state = mutableState.value
        if (!state.canAnalyze) return
        val sourceKey = state.selectedSourceKey ?: return
        revoke()
        val token = generation
        mutableState.value = state.copy(analysis = null, selectedProjectIds = emptySet(),
            review = null, outcome = null, isAnalyzing = true, recoveryToReview = null)
        work = viewModelScope.launch {
            try {
                val result = withContext(dispatcher) { operations.analyze(sourceKey, state.includeArchived) }
                if (generation != token) return@launch
                require(result.sourceKey == sourceKey && result.planId.isNotBlank())
                require(result.source == null || result.source.key == sourceKey)
                require(result.projects.map { it.key }.distinct().size == result.projects.size)
                val projects = result.projects.associateBy { it.key }
                val candidateIds = result.candidates.map { it.candidateKey }.toSet()
                require(candidateIds.size == result.candidates.size)
                require(result.candidates.all {
                    it.candidateKey != it.keeperKey && it.keeperKey !in candidateIds &&
                        projects[it.candidateKey]?.issues?.isEmpty() == true &&
                        projects[it.keeperKey]?.issues?.isEmpty() == true &&
                        it.matchedContentCount > 0 && it.additionalContentCount >= 0
                })
                mutableState.value = mutableState.value.copy(analysis = result, isAnalyzing = false,
                    sources = mutableState.value.sources.map { old ->
                        if (old.key == sourceKey) result.source ?: old else old
                    })
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { fail(token) }
        }
    }

    fun toggleCandidate(key: String) {
        val state = mutableState.value
        if (state.busy || state.selectedSource?.canClean != true ||
            state.analysis?.candidates?.none { it.candidateKey == key } != false) return
        mutableState.value = state.copy(selectedProjectIds = if (key in state.selectedProjectIds)
            state.selectedProjectIds - key else state.selectedProjectIds + key, review = null)
    }

    fun reviewSelection() {
        val state = mutableState.value
        if (!state.canReview) return
        mutableState.value = state.copy(review = SingleVaultDedupReview(
            state.analysis!!.planId, state.selectedProjectIds.toSet()))
    }

    fun dismissReview() { mutableState.value = mutableState.value.copy(review = null, recoveryToReview = null) }

    fun confirmCleanup() {
        val state = mutableState.value
        val review = state.review ?: return
        if (!state.canReview || review.planId != state.analysis?.planId ||
            review.selectedProjectIds != state.selectedProjectIds) return
        execute { operations.clean(review.planId, review.selectedProjectIds) }
    }

    fun reviewRecovery(id: String) {
        val state = mutableState.value
        if (state.busy || state.recoveryBatches.none { it.id == id && it.canRestore }) return
        mutableState.value = state.copy(recoveryToReview = id, review = null)
    }

    fun confirmRecovery() {
        val state = mutableState.value
        val id = state.recoveryToReview ?: return
        if (state.busy || state.recoveryBatches.none { it.id == id && it.canRestore }) return
        execute { operations.restore(id) }
    }

    private fun execute(action: suspend () -> SingleVaultDedupUiOutcome) {
        val token = ++generation
        mutableState.value = mutableState.value.copy(isExecuting = true, analysis = null,
            selectedProjectIds = emptySet(), review = null, recoveryToReview = null, outcome = null)
        work = viewModelScope.launch {
            try {
                val result = withContext(dispatcher) { action() }
                if (generation != token) return@launch
                mutableState.value = mutableState.value.copy(outcome = result, isExecuting = false)
                val batches = withContext(dispatcher) { operations.recoveryBatches() }
                if (generation == token) mutableState.value = mutableState.value.copy(recoveryBatches = batches)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (generation == token) mutableState.value = mutableState.value.copy(isExecuting = false,
                    outcome = SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED))
            }
        }
    }

    /** Call for screen departure, background and vault lock. No delayed exit animation may retain titles. */
    fun invalidate() {
        revoke()
        mutableState.value = SingleVaultDedupUiState()
    }

    fun cancel() {
        val wasExecuting = mutableState.value.isExecuting
        revoke()
        mutableState.value = mutableState.value.copy(analysis = null, selectedProjectIds = emptySet(),
            review = null, recoveryToReview = null, isLoading = false, isAnalyzing = false,
            isExecuting = false, outcome = if (wasExecuting)
                SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.RECOVERY_REQUIRED) else null)
    }

    private fun revoke() { generation++; operations.invalidate(); work?.cancel(); work = null }
    private fun fail(token: Long) {
        if (generation != token) return
        operations.invalidate()
        mutableState.value = mutableState.value.copy(isLoading = false, isAnalyzing = false,
            analysis = null, review = null, selectedProjectIds = emptySet(),
            outcome = SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.FAILED))
    }

    override fun onCleared() { revoke(); super.onCleared() }
}
