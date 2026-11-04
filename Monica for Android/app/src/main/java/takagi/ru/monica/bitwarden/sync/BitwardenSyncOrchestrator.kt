package takagi.ru.monica.bitwarden.sync

import takagi.ru.monica.R
import takagi.ru.monica.utils.StringResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.sync.SyncDiagnostics
import kotlin.math.min

enum class SyncTriggerReason {
    PAGE_ENTER,
    LOCAL_MUTATION,
    APP_RESUME,
    MANUAL,
    PERIODIC,
    RETRY,
    REMOTE_NOTIFICATION
}

enum class SyncBlockReason {
    AUTO_SYNC_DISABLED,
    NETWORK_UNAVAILABLE,
    WIFI_REQUIRED,
    VAULT_LOCKED,
    AUTH_REQUIRED
}

sealed class SyncExecutionOutcome {
    data class Success(
        val appliedChangeCount: Int,
        val availableOfflineCount: Int,
        val conflictCount: Int,
        val uploadFailedCount: Int,
        val skippedDueToLocalDirtyCount: Int
    ) : SyncExecutionOutcome()
    data class RetryableError(val message: String) : SyncExecutionOutcome()
    data class Blocked(val reason: SyncBlockReason, val message: String? = null) : SyncExecutionOutcome()
    data class FatalError(val message: String) : SyncExecutionOutcome()
}

enum class NetworkGateResult {
    ALLOWED,
    NETWORK_UNAVAILABLE,
    WIFI_REQUIRED
}

data class SyncManagerConfig(
    val pageEnterThrottleMs: Long = 45_000L,
    val appResumeThrottleMs: Long = 60_000L,
    val localMutationDebounceMs: Long = 700L,
    val retryBaseDelayMs: Long = 5_000L,
    val retryMaxDelayMs: Long = 15 * 60 * 1000L,
    val retryMaxAttempts: Int = 5
)

data class VaultSyncStatus(
    val isRunning: Boolean = false,
    val queuedReason: SyncTriggerReason? = null,
    val lastTriggerReason: SyncTriggerReason? = null,
    val blockedReason: SyncBlockReason? = null,
    val lastError: String? = null,
    val lastSuccessAt: Long? = null,
    val nextRetryAt: Long? = null,
    val retryAttempt: Int = 0,
    // Includes preflight blocks, which may not enqueue a coordinator request.
    val lastOutcomeAt: Long? = null
)

private data class VaultRuntime(
    var isRunning: Boolean = false,
    var pendingReason: SyncTriggerReason? = null,
    var pendingIsStillValid: (() -> Boolean)? = null,
    var lastPageEnterAt: Long = 0L,
    var lastAppResumeAt: Long = 0L,
    var lastSuccessAt: Long = 0L,
    var retryAttempt: Int = 0,
    var mutationDebounceJob: Job? = null,
    var retryJob: Job? = null,
    var delayedRequestJob: Job? = null
)

internal class BitwardenSyncOrchestrator(
    private val strings: StringResolver,
    private val scope: CoroutineScope,
    private val config: SyncManagerConfig = SyncManagerConfig(),
    private val isAutoSyncEnabled: () -> Boolean,
    private val checkNetwork: () -> NetworkGateResult,
    private val isVaultUnlocked: (Long) -> Boolean,
    private val executeSync: suspend (vaultId: Long, silent: Boolean) -> SyncExecutionOutcome,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()
    private val passiveAutoSyncMutex = Mutex()
    private val runtimes = mutableMapOf<Long, VaultRuntime>()
    private val _statusByVault = MutableStateFlow<Map<Long, VaultSyncStatus>>(emptyMap())
    val statusByVault: StateFlow<Map<Long, VaultSyncStatus>> = _statusByVault.asStateFlow()

    fun requestSync(vaultId: Long, reason: SyncTriggerReason, force: Boolean = false, isStillValid: (() -> Boolean)? = null): Job {
        return scope.launch {
            processRequest(vaultId = vaultId, reason = reason, force = force, isStillValid = isStillValid)
        }
    }

    fun requestSyncWithDelay(
        vaultId: Long,
        reason: SyncTriggerReason,
        delayMs: Long,
        force: Boolean = false,
        isStillValid: (() -> Boolean)? = null,
    ) {
        if (delayMs <= 0L) {
            requestSync(vaultId = vaultId, reason = reason, force = force, isStillValid = isStillValid)
            return
        }
        scope.launch {
            scheduleDelayedRequest(vaultId = vaultId, reason = reason, delayMs = delayMs, force = force, isStillValid = isStillValid)
        }
    }

    fun clearVault(vaultId: Long) {
        scope.launch {
            mutex.withLock {
                runtimes.remove(vaultId)?.let { runtime ->
                    runtime.retryJob?.cancel()
                    runtime.mutationDebounceJob?.cancel()
                    runtime.delayedRequestJob?.cancel()
                }
                updateStatus(vaultId) { null }
            }
        }
    }

    private suspend fun processRequest(vaultId: Long, reason: SyncTriggerReason, force: Boolean, isStillValid: (() -> Boolean)?) {
        val taskId = SyncDiagnostics.nextTaskId("bw")
        val target = "bitwarden:$vaultId"
        val trigger = reason.name
        var runNow = false
        var silent = reason != SyncTriggerReason.MANUAL
        var startedAt: Long? = null

        SyncDiagnostics.queued(
            taskId = taskId,
            target = target,
            trigger = trigger,
            detail = "force=$force"
        )

        mutex.withLock {
            val runtime = runtimeOf(vaultId)
            if (!requestIsCurrent(isStillValid)) {
                if (!runtime.isRunning && runtime.pendingReason == null) {
                    updateStatus(vaultId) { it?.copy(queuedReason = null, nextRetryAt = null) }
                }
                SyncDiagnostics.skipped(taskId, target, trigger, "request_revoked")
                return
            }

            if (force) {
                runtime.delayedRequestJob?.cancel()
                runtime.delayedRequestJob = null
                runtime.pendingReason = null
                runtime.pendingIsStillValid = null
            }

            if (reason == SyncTriggerReason.LOCAL_MUTATION && !force) {
                runtime.mutationDebounceJob?.cancel()
                runtime.mutationDebounceJob = scope.launch {
                    delay(config.localMutationDebounceMs)
                    requestSync(vaultId, SyncTriggerReason.LOCAL_MUTATION, force = true, isStillValid = isStillValid)
                }
                setQueued(vaultId, runtime, reason)
                SyncDiagnostics.queued(
                    taskId = taskId,
                    target = target,
                    trigger = trigger,
                    detail = "debounceMs=${config.localMutationDebounceMs}"
                )
                return
            }

            if (runtime.isRunning) {
                queueRequest(runtime, reason, isStillValid)
                setQueued(vaultId, runtime, runtime.pendingReason)
                SyncDiagnostics.queued(
                    taskId = taskId,
                    target = target,
                    trigger = trigger,
                    detail = "coalesced=true pending=${runtime.pendingReason}"
                )
                return
            }

            if (isAutoReason(reason) && !force) {
                if (!isAutoSyncEnabled()) {
                    setBlocked(vaultId, runtime, SyncBlockReason.AUTO_SYNC_DISABLED, strings.get(R.string.legacy_ui_auto_sync_disabled))
                    SyncDiagnostics.blocked(taskId, target, trigger, "auto_sync_disabled")
                    return
                }
                if (!passesThrottle(runtime, reason, nowProvider())) {
                    SyncDiagnostics.skipped(taskId, target, trigger, "throttle")
                    return
                }
            }

            when (checkNetwork()) {
                NetworkGateResult.ALLOWED -> Unit
                NetworkGateResult.NETWORK_UNAVAILABLE -> {
                    setBlocked(vaultId, runtime, SyncBlockReason.NETWORK_UNAVAILABLE, strings.get(R.string.legacy_ui_network_unavailable))
                    SyncDiagnostics.blocked(taskId, target, trigger, "network_unavailable")
                    return
                }
                NetworkGateResult.WIFI_REQUIRED -> {
                    setBlocked(vaultId, runtime, SyncBlockReason.WIFI_REQUIRED, strings.get(R.string.legacy_ui_wifi_required))
                    SyncDiagnostics.blocked(taskId, target, trigger, "wifi_required")
                    return
                }
            }

            if (!isVaultUnlocked(vaultId)) {
                setBlocked(vaultId, runtime, SyncBlockReason.VAULT_LOCKED, strings.get(R.string.legacy_ui_vault_locked))
                SyncDiagnostics.blocked(taskId, target, trigger, "vault_locked")
                return
            }

            runtime.retryJob?.cancel()
            runtime.retryJob = null
            runtime.isRunning = true
            silent = reason != SyncTriggerReason.MANUAL
            updateStatus(vaultId) { old ->
                val existing = old ?: VaultSyncStatus()
                existing.copy(
                    isRunning = true,
                    queuedReason = null,
                    lastTriggerReason = reason,
                    blockedReason = null,
                    nextRetryAt = null
                )
            }
            startedAt = SyncDiagnostics.start(
                taskId = taskId,
                target = target,
                trigger = trigger,
                detail = "silent=$silent"
            )
            runNow = true
        }

        if (!runNow) return

        suspend fun executeCurrent(): SyncExecutionOutcome? = withContext(Dispatchers.IO) {
            if (!requestIsCurrent(isStillValid)) return@withContext null
            if (isStillValid == null) return@withContext executeSync(vaultId, silent)
            coroutineScope {
                val work = async(BitwardenNotificationSyncLease(isStillValid)) {
                    if (!requestIsCurrent(isStillValid)) throw CancellationException("Notification request revoked")
                    executeSync(vaultId, silent)
                }
                val guard = launch {
                    while (work.isActive) {
                        if (!requestIsCurrent(isStillValid)) { work.cancel(); break }
                        delay(100)
                    }
                }
                try { work.await() } catch (cancelled: CancellationException) {
                    if (!requestIsCurrent(isStillValid)) null else throw cancelled
                } finally { guard.cancel() }
            }
        }
        val outcome: SyncExecutionOutcome? = try {
            // Passive auto reasons share one global lock across vaults.
            // Manual / mutation / forced retries stay free to run immediately.
            if (!force && isPassiveAutoReason(reason)) {
                passiveAutoSyncMutex.withLock {
                    executeCurrent()
                }
            } else {
                executeCurrent()
            }
        } catch (error: CancellationException) {
            SyncExecutionOutcome.RetryableError(error.message ?: strings.get(R.string.legacy_ui_sync_cancelled))
        } catch (error: Exception) {
            SyncExecutionOutcome.RetryableError(error.message ?: strings.get(R.string.bitwarden_message_sync_failed))
        }

        var pendingReasonToReplay: SyncTriggerReason? = null
        var pendingValidityToReplay: (() -> Boolean)? = null
        mutex.withLock {
            val runtime = runtimeOf(vaultId)
            runtime.isRunning = false

            when (outcome) {
                null -> {
                    runtime.retryJob?.cancel()
                    runtime.retryJob = null
                    runtime.retryAttempt = 0
                    updateStatus(vaultId) { it?.copy(isRunning = false, queuedReason = runtime.pendingReason,
                        nextRetryAt = null, retryAttempt = 0, lastOutcomeAt = nowProvider()) }
                }
                is SyncExecutionOutcome.Success -> {
                    runtime.retryAttempt = 0
                    runtime.lastSuccessAt = nowProvider()
                    updateStatus(vaultId) { old ->
                        val existing = old ?: VaultSyncStatus()
                        existing.copy(
                            isRunning = false,
                            queuedReason = null,
                            blockedReason = null,
                            lastError = null,
                            lastSuccessAt = runtime.lastSuccessAt,
                            lastOutcomeAt = runtime.lastSuccessAt,
                            retryAttempt = 0,
                            nextRetryAt = null
                        )
                    }
                }

                is SyncExecutionOutcome.Blocked -> {
                    updateStatus(vaultId) { old ->
                        val existing = old ?: VaultSyncStatus()
                        existing.copy(
                            isRunning = false,
                            blockedReason = outcome.reason,
                            lastError = outcome.message ?: existing.lastError,
                            lastOutcomeAt = nowProvider(),
                            queuedReason = runtime.pendingReason
                        )
                    }
                }

                is SyncExecutionOutcome.RetryableError -> {
                    if (requestIsCurrent(isStillValid) && runtime.retryAttempt < config.retryMaxAttempts) {
                        runtime.retryAttempt += 1
                        scheduleRetryLocked(vaultId, runtime, outcome.message, reason, isStillValid)
                    } else {
                        updateStatus(vaultId) { old ->
                            val existing = old ?: VaultSyncStatus()
                            existing.copy(
                                isRunning = false,
                                lastError = outcome.message,
                                lastOutcomeAt = nowProvider(),
                                nextRetryAt = null,
                                retryAttempt = runtime.retryAttempt
                            )
                        }
                    }
                }

                is SyncExecutionOutcome.FatalError -> {
                    runtime.retryAttempt = 0
                    updateStatus(vaultId) { old ->
                        val existing = old ?: VaultSyncStatus()
                        existing.copy(
                            isRunning = false,
                            lastError = outcome.message,
                            lastOutcomeAt = nowProvider(),
                            blockedReason = null,
                            retryAttempt = 0,
                            nextRetryAt = null
                        )
                    }
                }
            }

            pendingReasonToReplay = runtime.pendingReason
            pendingValidityToReplay = runtime.pendingIsStillValid
            runtime.pendingReason = null
            runtime.pendingIsStillValid = null
        }

        if (pendingReasonToReplay != null) {
            val replayReason = pendingReasonToReplay ?: SyncTriggerReason.RETRY
            requestSync(
                vaultId = vaultId,
                reason = replayReason,
                force = shouldForceQueuedReplay(replayReason),
                isStillValid = pendingValidityToReplay,
            )
        }

        val executionStartedAt = startedAt ?: SyncDiagnostics.now()
        when (outcome) {
            null -> SyncDiagnostics.skipped(taskId, target, trigger, "request_revoked")
            is SyncExecutionOutcome.Success -> SyncDiagnostics.success(
                taskId = taskId,
                target = target,
                trigger = trigger,
                startedAt = executionStartedAt,
                detail = "applied=${outcome.appliedChangeCount} conflicts=${outcome.conflictCount} uploadFailed=${outcome.uploadFailedCount} skippedDirty=${outcome.skippedDueToLocalDirtyCount}"
            )
            is SyncExecutionOutcome.Blocked -> SyncDiagnostics.blocked(
                taskId = taskId,
                target = target,
                trigger = trigger,
                reason = outcome.reason.name,
                startedAt = executionStartedAt
            )
            is SyncExecutionOutcome.RetryableError -> SyncDiagnostics.failed(
                taskId = taskId,
                target = target,
                trigger = trigger,
                startedAt = executionStartedAt,
                error = IllegalStateException(outcome.message),
                detail = "retryable=true"
            )
            is SyncExecutionOutcome.FatalError -> SyncDiagnostics.failed(
                taskId = taskId,
                target = target,
                trigger = trigger,
                startedAt = executionStartedAt,
                error = IllegalStateException(outcome.message),
                detail = "retryable=false"
            )
        }
    }

    private fun runtimeOf(vaultId: Long): VaultRuntime {
        return runtimes.getOrPut(vaultId) { VaultRuntime() }
    }

    private suspend fun scheduleDelayedRequest(
        vaultId: Long,
        reason: SyncTriggerReason,
        delayMs: Long,
        force: Boolean,
        isStillValid: (() -> Boolean)?,
    ) {
        mutex.withLock {
            if (!requestIsCurrent(isStillValid)) return
            val runtime = runtimeOf(vaultId)
            queueRequest(runtime, reason, isStillValid)
            runtime.delayedRequestJob?.cancel()
            runtime.delayedRequestJob = scope.launch {
                delay(delayMs)
                var reasonToRun: SyncTriggerReason? = null
                var validityToRun: (() -> Boolean)? = null
                mutex.withLock {
                    val delayedRuntime = runtimeOf(vaultId)
                    reasonToRun = delayedRuntime.pendingReason
                    validityToRun = delayedRuntime.pendingIsStillValid
                    delayedRuntime.pendingReason = null
                    delayedRuntime.pendingIsStillValid = null
                    delayedRuntime.delayedRequestJob = null
                }
                if (reasonToRun == null) return@launch
                requestSync(
                    vaultId = vaultId,
                    reason = reasonToRun ?: reason,
                    force = force,
                    isStillValid = validityToRun,
                )
            }
            setQueued(vaultId, runtime, runtime.pendingReason)
        }
    }

    private fun isAutoReason(reason: SyncTriggerReason): Boolean {
        return reason != SyncTriggerReason.MANUAL
    }

    private fun passesThrottle(runtime: VaultRuntime, reason: SyncTriggerReason, now: Long): Boolean {
        if (isPassiveAutoReason(reason) && runtime.lastSuccessAt > 0L) {
            val quietWindowMs = passiveAutoQuietWindowMs(reason)
            if (now - runtime.lastSuccessAt < quietWindowMs) {
                return false
            }
        }
        return when (reason) {
            SyncTriggerReason.PAGE_ENTER -> {
                if (now - runtime.lastPageEnterAt < config.pageEnterThrottleMs) {
                    false
                } else {
                    runtime.lastPageEnterAt = now
                    true
                }
            }

            SyncTriggerReason.APP_RESUME -> {
                if (now - runtime.lastAppResumeAt < config.appResumeThrottleMs) {
                    false
                } else {
                    runtime.lastAppResumeAt = now
                    true
                }
            }

            else -> true
        }
    }

    private fun shouldForceQueuedReplay(reason: SyncTriggerReason): Boolean {
        return when (reason) {
            SyncTriggerReason.LOCAL_MUTATION,
            SyncTriggerReason.MANUAL,
            SyncTriggerReason.RETRY -> true

            SyncTriggerReason.PAGE_ENTER,
            SyncTriggerReason.APP_RESUME,
            SyncTriggerReason.PERIODIC,
            SyncTriggerReason.REMOTE_NOTIFICATION -> false
        }
    }

    private fun isPassiveAutoReason(reason: SyncTriggerReason): Boolean {
        return reason == SyncTriggerReason.PAGE_ENTER ||
            reason == SyncTriggerReason.APP_RESUME ||
            reason == SyncTriggerReason.PERIODIC
    }

    private fun passiveAutoQuietWindowMs(reason: SyncTriggerReason): Long {
        return when (reason) {
            SyncTriggerReason.APP_RESUME -> config.appResumeThrottleMs
            SyncTriggerReason.PERIODIC -> config.appResumeThrottleMs
            else -> config.pageEnterThrottleMs
        }
    }

    private fun setQueued(vaultId: Long, runtime: VaultRuntime, reason: SyncTriggerReason?) {
        updateStatus(vaultId) { old ->
            val existing = old ?: VaultSyncStatus()
            existing.copy(
                isRunning = runtime.isRunning,
                queuedReason = reason,
                blockedReason = null
            )
        }
    }

    private fun setBlocked(
        vaultId: Long,
        runtime: VaultRuntime,
        reason: SyncBlockReason,
        message: String
    ) {
        updateStatus(vaultId) { old ->
            val existing = old ?: VaultSyncStatus()
            existing.copy(
                isRunning = false,
                blockedReason = reason,
                lastError = message,
                lastOutcomeAt = nowProvider(),
                queuedReason = runtime.pendingReason
            )
        }
    }

    private fun scheduleRetryLocked(vaultId: Long, runtime: VaultRuntime, message: String, origin: SyncTriggerReason, isStillValid: (() -> Boolean)?) {
        val delayMs = min(
            config.retryMaxDelayMs,
            config.retryBaseDelayMs * (1L shl (runtime.retryAttempt - 1))
        )
        val failedAt = nowProvider()
        val nextRetryAt = failedAt + delayMs
        runtime.retryJob?.cancel()
        runtime.retryJob = scope.launch {
            delay(delayMs)
            if (origin == SyncTriggerReason.REMOTE_NOTIFICATION) {
                requestSync(vaultId, origin, isStillValid = isStillValid)
            } else {
                requestSync(vaultId, SyncTriggerReason.RETRY, force = true, isStillValid = isStillValid)
            }
        }
        updateStatus(vaultId) { old ->
            val existing = old ?: VaultSyncStatus()
            existing.copy(
                isRunning = false,
                lastError = message,
                lastOutcomeAt = failedAt,
                nextRetryAt = nextRetryAt,
                retryAttempt = runtime.retryAttempt
            )
        }
    }

    private fun requestIsCurrent(predicate: (() -> Boolean)?): Boolean =
        predicate == null || runCatching(predicate).getOrDefault(false)

    private fun queueRequest(runtime: VaultRuntime, incoming: SyncTriggerReason, predicate: (() -> Boolean)?) {
        val current = runtime.pendingReason
        if (current == null || !requestIsCurrent(runtime.pendingIsStillValid) || priorityOf(incoming) >= priorityOf(current)) {
            runtime.pendingReason = incoming
            runtime.pendingIsStillValid = predicate
        }
    }

    private fun priorityOf(reason: SyncTriggerReason): Int = when (reason) {
        SyncTriggerReason.MANUAL -> 6
        SyncTriggerReason.LOCAL_MUTATION -> 5
        SyncTriggerReason.RETRY -> 4
        SyncTriggerReason.APP_RESUME -> 3
        SyncTriggerReason.PAGE_ENTER -> 2
        SyncTriggerReason.PERIODIC -> 1
        SyncTriggerReason.REMOTE_NOTIFICATION -> 4
    }

    private fun updateStatus(
        vaultId: Long,
        transform: (VaultSyncStatus?) -> VaultSyncStatus?
    ) {
        val current = _statusByVault.value.toMutableMap()
        val updated = transform(current[vaultId])
        if (updated == null) {
            current.remove(vaultId)
        } else {
            current[vaultId] = updated
        }
        _statusByVault.value = current
    }
}
