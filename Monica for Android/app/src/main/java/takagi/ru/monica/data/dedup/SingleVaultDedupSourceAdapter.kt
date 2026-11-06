package takagi.ru.monica.data.dedup

import takagi.ru.monica.viewmodel.SingleVaultDedupUiOutcome
import takagi.ru.monica.viewmodel.SingleVaultDedupUiSource

/** Captured records belong to one authenticated read. Never expose them to UI or saved state. */
internal abstract class SingleVaultDedupCapture(val projects: List<SingleVaultDedupProject>) {
    final override fun toString(): String = "SingleVaultDedupCapture(<redacted>)"
}

internal interface SingleVaultDedupSourceAdapter {
    val source: SingleVaultDedupUiSource
    suspend fun capture(includeArchived: Boolean, checkActive: () -> Unit): SingleVaultDedupCapture
    suspend fun clean(
        captured: SingleVaultDedupCapture,
        selected: List<SingleVaultDedupCandidate>,
        checkActive: () -> Unit,
    ): SingleVaultDedupUiOutcome
    suspend fun restore(batch: SingleVaultDedupRecoveryEntity, checkActive: () -> Unit): SingleVaultDedupUiOutcome
}
