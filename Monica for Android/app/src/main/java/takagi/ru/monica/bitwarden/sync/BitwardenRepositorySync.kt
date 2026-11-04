package takagi.ru.monica.bitwarden.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext

import takagi.ru.monica.R
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.sync.SyncDiagnostics
import takagi.ru.monica.sync.SyncError
import takagi.ru.monica.sync.SyncErrorKind
import takagi.ru.monica.sync.SyncExecutionResult
import takagi.ru.monica.sync.SyncMode
import takagi.ru.monica.sync.SyncNetworkPolicy
import takagi.ru.monica.sync.SyncPriority
import takagi.ru.monica.sync.SyncRequest
import takagi.ru.monica.sync.SyncTarget
import takagi.ru.monica.sync.SyncTaskAwaitResult
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.sync.SyncTrigger
import takagi.ru.monica.sync.classifySyncFailure

sealed class BitwardenCoordinatedSyncResult {
    data class Completed(val result: BitwardenRepository.SyncResult) : BitwardenCoordinatedSyncResult()
    data object Merged : BitwardenCoordinatedSyncResult()
    data class Skipped(val reason: String) : BitwardenCoordinatedSyncResult()
    data class Blocked(val error: SyncError) : BitwardenCoordinatedSyncResult()
    data class Canceled(val reason: String?) : BitwardenCoordinatedSyncResult()
    data class Failed(val error: Exception) : BitwardenCoordinatedSyncResult()
}

suspend fun BitwardenRepository.syncViaCoordinator(
    vaultId: Long,
    requestIdPrefix: String,
    trigger: SyncTrigger,
    priority: SyncPriority = SyncPriority.forTrigger(trigger),
    mode: SyncMode = SyncMode.BACKGROUND,
    networkPolicy: SyncNetworkPolicy = if (isSyncOnWifiOnly) {
        SyncNetworkPolicy.WIFI_ONLY
    } else {
        SyncNetworkPolicy.REQUIRED
    },
    requiresUnlockedTarget: Boolean = true
): BitwardenCoordinatedSyncResult {
    val request = SyncRequest(
        requestId = SyncDiagnostics.nextTaskId(requestIdPrefix),
        target = SyncTarget.BitwardenVault(vaultId),
        trigger = trigger,
        createdAtMillis = System.currentTimeMillis(),
        priority = priority,
        mode = mode,
        networkPolicy = networkPolicy,
        requiresUnlockedTarget = requiresUnlockedTarget
    )

    @Suppress("DEPRECATION")
    val notificationLease = currentCoroutineContext()[BitwardenNotificationSyncLease]
    return awaitCoordinatedBitwardenSync(request, strings, cancelWhenWaiterCancelled = notificationLease != null) {
        if (notificationLease != null && !notificationLease.isCurrent()) throw CancellationException("Notification request revoked")
        sync(vaultId)
    }
}

internal suspend fun awaitCoordinatedBitwardenSync(
    request: SyncRequest,
    strings: StringResolver,
    cancelWhenWaiterCancelled: Boolean = false,
    sync: suspend () -> BitwardenRepository.SyncResult
): BitwardenCoordinatedSyncResult {
    return when (val result = SyncTaskRunner.requestAndAwait(
        request = request,
        cancelWhenWaiterCancelled = cancelWhenWaiterCancelled,
        resultClassifier = { it.toCoordinatorExecutionResult(strings) },
        block = sync
    )) {
        is SyncTaskAwaitResult.Completed -> BitwardenCoordinatedSyncResult.Completed(result.value)
        is SyncTaskAwaitResult.Merged -> BitwardenCoordinatedSyncResult.Merged
        is SyncTaskAwaitResult.Skipped -> BitwardenCoordinatedSyncResult.Skipped(result.reason)
        is SyncTaskAwaitResult.Blocked -> BitwardenCoordinatedSyncResult.Blocked(result.error)
        is SyncTaskAwaitResult.Canceled -> BitwardenCoordinatedSyncResult.Canceled(result.reason)
        is SyncTaskAwaitResult.Failed -> BitwardenCoordinatedSyncResult.Failed(result.error)
    }
}

private fun BitwardenRepository.SyncResult.toCoordinatorExecutionResult(
    strings: StringResolver
): SyncExecutionResult {
    val finishedAt = System.currentTimeMillis()
    return when (this) {
        is BitwardenRepository.SyncResult.Success -> SyncExecutionResult.Success(finishedAt)
        is BitwardenRepository.SyncResult.EmptyVaultBlocked -> SyncExecutionResult.Failed(
            finishedAt,
            SyncError(SyncErrorKind.VALIDATION_FAILED, reason)
        )
        is BitwardenRepository.SyncResult.Error -> {
            val outcome = classifyBitwardenSyncError(message, strings)
            if (outcome is SyncExecutionOutcome.Blocked) {
                SyncExecutionResult.Blocked(
                    finishedAt,
                    SyncError(
                        kind = if (outcome.reason == SyncBlockReason.VAULT_LOCKED) {
                            SyncErrorKind.TARGET_LOCKED
                        } else {
                            SyncErrorKind.AUTH_REQUIRED
                        },
                        redactedMessage = message
                    )
                )
            } else {
                val classified = classifySyncFailure(IllegalStateException(message))
                val error = if (
                    outcome is SyncExecutionOutcome.RetryableError &&
                    classified.kind == SyncErrorKind.UNEXPECTED
                ) {
                    classified.copy(kind = SyncErrorKind.NETWORK_UNAVAILABLE, retryable = true)
                } else {
                    classified
                }
                if (error.kind == SyncErrorKind.CONFLICT) {
                    SyncExecutionResult.Conflict(finishedAt, error)
                } else {
                    SyncExecutionResult.Failed(finishedAt, error)
                }
            }
        }
    }
}

suspend fun BitwardenRepository.syncForUserVisibleRequest(
    vaultId: Long,
    requestIdPrefix: String
): BitwardenRepository.SyncResult {
    return syncViaCoordinator(
        vaultId = vaultId,
        requestIdPrefix = requestIdPrefix,
        trigger = SyncTrigger.MANUAL,
        priority = SyncPriority.MANUAL,
        mode = SyncMode.FOREGROUND
    ).toRepositorySyncResultForUi(strings)
}

internal fun BitwardenCoordinatedSyncResult.toRepositorySyncResultForUi(strings: StringResolver): BitwardenRepository.SyncResult {
    return when (this) {
        is BitwardenCoordinatedSyncResult.Completed -> result
        BitwardenCoordinatedSyncResult.Merged -> emptyBitwardenSyncSuccess()
        is BitwardenCoordinatedSyncResult.Skipped -> emptyBitwardenSyncSuccess()
        is BitwardenCoordinatedSyncResult.Blocked -> {
            BitwardenRepository.SyncResult.Error(error.redactedMessage ?: error.kind.name)
        }
        is BitwardenCoordinatedSyncResult.Canceled -> {
            BitwardenRepository.SyncResult.Error(reason ?: strings.get(R.string.legacy_ui_sync_cancelled))
        }
        is BitwardenCoordinatedSyncResult.Failed -> {
            BitwardenRepository.SyncResult.Error(error.message ?: strings.get(R.string.bitwarden_message_sync_failed))
        }
    }
}

fun SyncError.toBitwardenBlockReason(): SyncBlockReason {
    return when (kind) {
        SyncErrorKind.NETWORK_UNAVAILABLE -> SyncBlockReason.NETWORK_UNAVAILABLE
        SyncErrorKind.WIFI_REQUIRED -> SyncBlockReason.WIFI_REQUIRED
        SyncErrorKind.AUTH_REQUIRED -> SyncBlockReason.AUTH_REQUIRED
        SyncErrorKind.TARGET_LOCKED -> SyncBlockReason.VAULT_LOCKED
        else -> SyncBlockReason.NETWORK_UNAVAILABLE
    }
}

private fun emptyBitwardenSyncSuccess(): BitwardenRepository.SyncResult.Success {
    return BitwardenRepository.SyncResult.Success(
        appliedChangeCount = 0,
        remoteAddedCount = 0,
        remoteUpdatedCount = 0,
        uploadedCount = 0,
        deletedCount = 0,
        availableOfflineCount = 0,
        conflictCount = 0,
        uploadFailedCount = 0,
        skippedDueToLocalDirtyCount = 0
    )
}
