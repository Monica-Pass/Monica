package takagi.ru.monica.viewmodel

/** Presentation data deliberately excludes values, comparison payloads and fingerprints. */
data class SingleVaultDedupUiSource(
    val key: String,
    val label: String,
    val canClean: Boolean,
    val issue: SingleVaultDedupUiIssue? = null,
)

enum class SingleVaultDedupUiIssue {
    INCOMPLETE_SNAPSHOT, UNREADABLE_CONTENT, UNSUPPORTED_CONTENT, INCONSISTENT_PROJECT,
    EXTERNAL_REFERENCE, EMPTY_CONTENT, DIFFERENT_SOURCE, COMPARISON_LIMIT,
    ANALYSIS_ONLY, LOCKED, SOURCE_UNAVAILABLE, RECOVERY_UNAVAILABLE,
    PASSKEY_PRESERVED, PASSKEY_REFERENCE,
}

data class SingleVaultDedupUiProject(
    val key: String,
    val title: String,
    val path: String = "",
    val contentCount: Int = 0,
    val issues: Set<SingleVaultDedupUiIssue> = emptySet(),
)

data class SingleVaultDedupUiCandidate(
    val candidateKey: String,
    val keeperKey: String,
    val matchedContentCount: Int,
    val additionalContentCount: Int = 0,
)

data class SingleVaultDedupUiAnalysis(
    val planId: String,
    val sourceKey: String,
    val projects: List<SingleVaultDedupUiProject>,
    val candidates: List<SingleVaultDedupUiCandidate>,
    val comparisonLimitReached: Boolean = false,
    val source: SingleVaultDedupUiSource? = null,
)

enum class SingleVaultDedupUiOutcomeStatus {
    SUCCESS, NOTHING_CHANGED, SOURCE_CHANGED, RECOVERY_REQUIRED, FAILED,
}

data class SingleVaultDedupUiOutcome(
    val status: SingleVaultDedupUiOutcomeStatus,
    val changedProjects: Int = 0,
    val recoveryBatchId: String? = null,
    val isRestoration: Boolean = false,
    val restoredCopyCount: Int = 0,
)

data class SingleVaultDedupUiRecoveryBatch(
    val id: String,
    val sourceLabel: String,
    val createdAtEpochMillis: Long,
    val projectCount: Int,
    val canRestore: Boolean = true,
    val issue: SingleVaultDedupUiIssue? = null,
    val missingAsCopy: Boolean = false,
)

interface SingleVaultDedupOperations {
    suspend fun sources(): List<SingleVaultDedupUiSource>
    suspend fun analyze(sourceKey: String, includeArchived: Boolean): SingleVaultDedupUiAnalysis
    suspend fun clean(planId: String, selectedProjectIds: Set<String>): SingleVaultDedupUiOutcome
    suspend fun recoveryBatches(): List<SingleVaultDedupUiRecoveryBatch>
    suspend fun restore(batchId: String): SingleVaultDedupUiOutcome

    /** Revoke authorization synchronously, including work whose cancellation is delayed. */
    fun invalidate()
}
