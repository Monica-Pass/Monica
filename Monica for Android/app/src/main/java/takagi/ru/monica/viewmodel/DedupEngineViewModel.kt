package takagi.ru.monica.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.dedup.DedupMergeExecutionResult
import takagi.ru.monica.data.dedup.DedupMergeExecutionProgress
import takagi.ru.monica.data.dedup.DedupMergePlan
import takagi.ru.monica.data.dedup.DedupMergeSelection
import takagi.ru.monica.data.dedup.DedupMergeOperations
import takagi.ru.monica.data.dedup.DedupMergeSourceOption
import takagi.ru.monica.data.dedup.DedupMergeTarget
import takagi.ru.monica.data.dedup.DedupMergeTargetOption
import takagi.ru.monica.data.dedup.DedupConflictPolicy
import takagi.ru.monica.utils.StringResolver

data class DedupEngineUiState(
    val isLoading: Boolean = true,
    val isAnalyzing: Boolean = false,
    val isExecutingMerge: Boolean = false,
    val sourceOptions: List<DedupMergeSourceOption> = emptyList(),
    val selectedMergeSourceKeys: Set<String> = emptySet(),
    val targetOptions: List<DedupMergeTargetOption> = emptyList(),
    val selectedMergeTarget: DedupMergeTarget? = null,
    val conflictPolicy: DedupConflictPolicy = DedupConflictPolicy.MOST_COMPLETE,
    val mergePlan: DedupMergePlan = DedupMergePlan(),
    val executionProgress: DedupMergeExecutionProgress? = null,
    val executionResult: DedupMergeExecutionResult? = null,
    val error: String? = null,
    val message: String? = null
) {
    val selectedTargetOption: DedupMergeTargetOption?
        get() = targetOptions.firstOrNull { it.target == selectedMergeTarget }

    val selection: DedupMergeSelection
        get() = DedupMergeSelection(selectedMergeSourceKeys, selectedTargetOption)

    val validation
        get() = selection.validate(mergePlan.writableItems)

    val canExecuteMerge: Boolean
        get() = validation.canExecute && !isLoading && !isAnalyzing && !isExecutingMerge && error == null &&
            mergePlan.target == selectedMergeTarget && mergePlan.conflictPolicy == conflictPolicy &&
            mergePlan.selectedSources.map { it.key }.toSet() == selectedMergeSourceKeys
}

class DedupEngineViewModel internal constructor(
    private val mergeService: DedupMergeOperations,
    private val strings: StringResolver,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {
    private val _uiState = MutableStateFlow(DedupEngineUiState())
    val uiState: StateFlow<DedupEngineUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var analyzeJob: Job? = null
    private var executionJob: Job? = null
    private val invalidationVersion = java.util.concurrent.atomic.AtomicLong()

    fun invalidate() {
        invalidationVersion.incrementAndGet()
        mergeService.invalidate()
        refreshJob?.cancel(); analyzeJob?.cancel(); executionJob?.cancel()
        _uiState.value = DedupEngineUiState(isLoading = false)
    }

    override fun onCleared() { invalidate(); super.onCleared() }

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.isExecutingMerge) return
        refreshJob?.cancel()
        analyzeJob?.cancel()
        mergeService.invalidate()
        val version = invalidationVersion.get()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isAnalyzing = false, mergePlan = DedupMergePlan(), error = null) }
            runCatching {
                withContext(analysisDispatcher) {
                    mergeService.getSourceOptions() to mergeService.getTargetOptions()
                }
            }.onSuccess { (sources, targets) ->
                ensureActive()
                if (invalidationVersion.get() != version) return@onSuccess
                val current = _uiState.value
                val validSourceKeys = sources.map { it.key }.toSet()
                val selectedKeys = current.selectedMergeSourceKeys.intersect(validSourceKeys)
                val selectedTargetSourceKey = current.selectedTargetOption?.sourceKey
                val selectedTargetOption = targets.firstOrNull { it.sourceKey == selectedTargetSourceKey }
                val selectedTarget = selectedTargetOption?.target
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        sourceOptions = sources,
                        selectedMergeSourceKeys = selectedKeys - setOfNotNull(selectedTargetSourceKey),
                        targetOptions = targets,
                        selectedMergeTarget = selectedTarget,
                        error = null
                    )
                }
                rebuildPlan()
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                if (invalidationVersion.get() != version) return@onFailure
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = strings.get(R.string.dedup_merge_load_failed)
                    )
                }
            }
        }
    }

    fun toggleMergeSource(sourceKey: String) {
        if (_uiState.value.isExecutingMerge || _uiState.value.sourceOptions.none { it.key == sourceKey }) return
        _uiState.update { state ->
            val next = state.selection.toggleSource(sourceKey)
            state.copy(
                selectedMergeSourceKeys = next.sourceKeys,
                selectedMergeTarget = next.targetOption?.target,
                executionResult = null,
                message = null,
                error = null
            )
        }
        rebuildPlan()
    }

    fun selectAllSources(visibleKeys: Set<String>? = null) {
        if (_uiState.value.isExecutingMerge) return
        _uiState.update { state ->
            val availableKeys = state.sourceOptions.map { it.key }.toSet()
            val next = state.selection.selectAll(visibleKeys?.intersect(availableKeys) ?: availableKeys)
            state.copy(
                selectedMergeSourceKeys = state.selectedMergeSourceKeys + next.sourceKeys,
                executionResult = null,
                message = null,
                error = null
            )
        }
        rebuildPlan()
    }

    fun clearSources() {
        if (_uiState.value.isExecutingMerge) return
        analyzeJob?.cancel()
        mergeService.invalidate()
        _uiState.update {
            it.copy(
                isAnalyzing = false,
                selectedMergeSourceKeys = emptySet(),
                mergePlan = DedupMergePlan(
                    target = it.selectedMergeTarget,
                    conflictPolicy = it.conflictPolicy
                ),
                executionResult = null,
                message = null,
                error = null
            )
        }
    }

    fun selectMergeTarget(target: DedupMergeTarget) {
        if (_uiState.value.isExecutingMerge) return
        _uiState.update {
            val option = it.targetOptions.firstOrNull { option -> option.target == target }
                ?: return@update it
            val next = it.selection.selectTarget(option)
            it.copy(
                selectedMergeSourceKeys = next.sourceKeys,
                selectedMergeTarget = next.targetOption?.target,
                executionResult = null,
                message = null,
                error = null
            )
        }
        rebuildPlan()
    }

    fun updateConflictPolicy(policy: DedupConflictPolicy) {
        if (_uiState.value.isExecutingMerge || _uiState.value.conflictPolicy == policy) return
        _uiState.update {
            it.copy(
                conflictPolicy = policy,
                executionResult = null,
                message = null,
                error = null
            )
        }
        rebuildPlan()
    }

    fun executeMerge() {
        val state = _uiState.value
        val plan = state.mergePlan
        if (!state.canExecuteMerge) {
            _uiState.update {
                it.copy(message = validationMessage(state, strings))
            }
            return
        }

        executionJob?.cancel()
        val version = invalidationVersion.get()
        executionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExecutingMerge = true,
                    executionProgress = DedupMergeExecutionProgress(0, plan.writableItems, strings.get(R.string.dedup_merge_preparing)),
                    executionResult = null,
                    error = null,
                    message = null
                )
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    mergeService.executePlan(plan) { progress ->
                        if (invalidationVersion.get() == version) _uiState.update { current -> current.copy(executionProgress = progress) }
                    }
                }
                if (invalidationVersion.get() != version) return@launch
                _uiState.update {
                    it.copy(
                        isExecutingMerge = false,
                        executionProgress = null,
                        executionResult = result,
                        message = result.toMessage(strings),
                        error = null
                    )
                }
                refresh()
            } catch (_: CancellationException) {
                if (invalidationVersion.get() != version) return@launch
                _uiState.update {
                    it.copy(
                        isExecutingMerge = false,
                        executionProgress = null,
                        message = strings.get(R.string.dedup_merge_cancelled)
                    )
                }
                refresh()
            } catch (throwable: Throwable) {
                if (invalidationVersion.get() != version) return@launch
                _uiState.update {
                    it.copy(
                        isExecutingMerge = false,
                        executionProgress = null,
                        error = strings.get(R.string.dedup_merge_changed_review)
                    )
                }
            }
        }
    }

    fun cancelMerge() {
        if (!_uiState.value.isExecutingMerge) return
        executionJob?.cancel()
        _uiState.update {
            it.copy(
                message = strings.get(R.string.dedup_merge_cancelling)
            )
        }
    }

    fun consumeMessage() {
        if (_uiState.value.message == null) return
        _uiState.update { it.copy(message = null) }
    }

    private fun rebuildPlan() {
        analyzeJob?.cancel()
        mergeService.invalidate()
        val version = invalidationVersion.get()
        val selection = _uiState.value
        if (!selection.validation.canReview) {
            _uiState.update { it.copy(isAnalyzing = false, mergePlan = DedupMergePlan()) }
            return
        }
        _uiState.update { it.copy(isAnalyzing = true, mergePlan = DedupMergePlan(), error = null) }
        analyzeJob = viewModelScope.launch {
            val selectedKeys = selection.selectedMergeSourceKeys
            val selectedTarget = selection.selectedMergeTarget
            val conflictPolicy = selection.conflictPolicy
            runCatching {
                withContext(analysisDispatcher) {
                    mergeService.buildPlan(selectedKeys, selectedTarget, conflictPolicy)
                }
            }.onSuccess { plan ->
                ensureActive()
                if (invalidationVersion.get() != version) return@onSuccess
                val current = _uiState.value
                if (current.selectedMergeSourceKeys != selectedKeys || current.selectedMergeTarget != selectedTarget ||
                    current.conflictPolicy != conflictPolicy) return@onSuccess
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        mergePlan = plan,
                        error = null
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                if (invalidationVersion.get() != version) return@onFailure
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        mergePlan = DedupMergePlan(),
                        error = strings.get(R.string.dedup_merge_plan_failed)
                    )
                }
            }
        }
    }
}

private fun DedupMergeExecutionResult.toMessage(strings: StringResolver): String {
    val details = buildList {
        if (insertedPasswords > 0) add(strings.get(R.string.dedup_merge_password_count, insertedPasswords))
        if (insertedSecureItems > 0) add(strings.get(R.string.dedup_merge_secure_item_count, insertedSecureItems))
        if (insertedPasskeys > 0) add(strings.get(R.string.dedup_merge_passkey_count, insertedPasskeys))
        if (failedItems > 0) add(strings.get(R.string.dedup_merge_failed_count, failedItems))
        if (skippedExistingItems > 0) add(strings.get(R.string.dedup_merge_skipped_existing_count, skippedExistingItems))
        if (skippedUnsupportedPasskeys > 0) add(strings.get(R.string.dedup_merge_skipped_passkey_count, skippedUnsupportedPasskeys))
    }.joinToString(strings.get(R.string.dedup_merge_list_separator))
    val summary = when {
        failedItems > 0 && insertedItems == 0 -> strings.get(R.string.dedup_merge_message_failed, targetLabel)
        failedItems > 0 -> strings.get(R.string.dedup_merge_message_partial, targetLabel, insertedItems)
        else -> strings.get(R.string.dedup_merge_message_success, targetLabel, insertedItems)
    }
    return if (details.isBlank()) summary else strings.get(R.string.dedup_merge_message_details, summary, details)
}

private fun validationMessage(state: DedupEngineUiState, strings: StringResolver): String = strings.get(
    when {
        state.selectedMergeSourceKeys.size < DedupMergeSelection.MINIMUM_SOURCE_DATABASES -> R.string.dedup_merge_need_sources
        state.selectedMergeTarget == null -> R.string.dedup_merge_need_target
        state.isAnalyzing -> R.string.dedup_merge_preview_pending
        state.mergePlan.writableItems <= 0 -> R.string.dedup_merge_target_contains_all
        else -> R.string.dedup_merge_plan_invalid
    }
)
