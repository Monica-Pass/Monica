package takagi.ru.monica.data.dedup

internal interface DedupMergeOperations {
    /** Synchronous authorization revocation; late readers must not republish a plan. */
    fun invalidate() = Unit
    suspend fun getSourceOptions(): List<DedupMergeSourceOption>
    suspend fun getTargetOptions(): List<DedupMergeTargetOption>
    suspend fun buildPlan(
        selectedSourceKeys: Set<String>, target: DedupMergeTarget?,
        conflictPolicy: DedupConflictPolicy = DedupConflictPolicy.MOST_COMPLETE
    ): DedupMergePlan
    suspend fun executePlan(
        plan: DedupMergePlan, onProgress: (DedupMergeExecutionProgress) -> Unit = {}
    ): DedupMergeExecutionResult
}
