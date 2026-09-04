package takagi.ru.monica.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.sync.SyncBackupProvider
import takagi.ru.monica.sync.SyncDiagnostics
import takagi.ru.monica.sync.SyncMode
import takagi.ru.monica.sync.SyncNetworkPolicy
import takagi.ru.monica.sync.SyncPriority
import takagi.ru.monica.sync.SyncRequest
import takagi.ru.monica.sync.SyncTarget
import takagi.ru.monica.sync.SyncTaskAwaitResult
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.sync.SyncTrigger
import takagi.ru.monica.utils.BackupContentScope
import takagi.ru.monica.utils.WebDavHelper
import takagi.ru.monica.webdav.WebDavBackoffState
import takagi.ru.monica.webdav.WebDavErrorClassifier
import takagi.ru.monica.webdav.WebDavErrorKind
import takagi.ru.monica.webdav.WebDavGateway

/**
 * 自动 WebDAV 备份工作器。
 *
 * 与 OpenList 等有速率限制的 WebDAV 服务兼容的关键在于：
 * - 在调用前查询 [WebDavBackoffState]，若目标主机仍处于 backoff 或临时禁用期，
 *   直接返回 Result.success() 跳过本轮，避免持续冲击服务器。
 * - 业务调用失败后按 [WebDavErrorClassifier] 分类映射结果：
 *   - 速率限制 / 鉴权失败 / 方法不被支持 / 响应格式错误 → success（重试无意义）
 *   - 网络不可达 / 超时 → retry
 *   - 成功 → success
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        android.util.Log.d(TAG, "Starting auto backup work...")

        val isManualTrigger = inputData.getBoolean(KEY_MANUAL_TRIGGER, false)
        val isChangeTriggered = inputData.getBoolean(KEY_CHANGE_TRIGGERED, false)
        val taskId = SyncDiagnostics.nextTaskId("backup-webdav")
        val target = SyncTarget.Backup(SyncBackupProvider.WEBDAV)
        val targetLabel = target.stableKey.value
        val trigger = when {
            isManualTrigger -> SyncTrigger.MANUAL
            isChangeTriggered -> SyncTrigger.LOCAL_MUTATION
            else -> SyncTrigger.BACKUP_SCHEDULE
        }
        val triggerLabel = when {
            isManualTrigger -> "WEBDAV_MANUAL_WORKER"
            isChangeTriggered -> "WEBDAV_CHANGE_WORKER"
            else -> "WEBDAV_AUTO_WORKER"
        }
        android.util.Log.d(TAG, "Manual trigger: $isManualTrigger, change triggered: $isChangeTriggered")

        val request = SyncRequest(
            requestId = taskId,
            target = target,
            trigger = trigger,
            createdAtMillis = System.currentTimeMillis(),
            priority = SyncPriority.forTrigger(trigger),
            mode = if (isManualTrigger) SyncMode.FOREGROUND else SyncMode.BACKGROUND,
            networkPolicy = SyncNetworkPolicy.REQUIRED
        )

        return when (val result = SyncTaskRunner.requestAndAwait(request) {
            runBackup(isManualTrigger, isChangeTriggered, taskId, targetLabel, triggerLabel)
        }) {
            is SyncTaskAwaitResult.Completed -> result.value
            is SyncTaskAwaitResult.Merged -> {
                SyncDiagnostics.skipped(taskId, targetLabel, triggerLabel, "merged_with_running_backup")
                androidx.work.ListenableWorker.Result.success()
            }
            is SyncTaskAwaitResult.Skipped -> {
                SyncDiagnostics.skipped(taskId, targetLabel, triggerLabel, result.reason)
                androidx.work.ListenableWorker.Result.success()
            }
            is SyncTaskAwaitResult.Blocked -> {
                SyncDiagnostics.blocked(
                    taskId = taskId,
                    target = targetLabel,
                    trigger = triggerLabel,
                    reason = result.error.redactedMessage ?: result.error.kind.name,
                    detail = "coordinator_blocked retryable=${result.error.retryable}"
                )
                if (result.error.retryable) {
                    androidx.work.ListenableWorker.Result.retry()
                } else {
                    androidx.work.ListenableWorker.Result.success()
                }
            }
            is SyncTaskAwaitResult.Canceled -> {
                SyncDiagnostics.skipped(
                    taskId = taskId,
                    target = targetLabel,
                    trigger = triggerLabel,
                    reason = result.reason ?: "coordinator_canceled"
                )
                androidx.work.ListenableWorker.Result.retry()
            }
            is SyncTaskAwaitResult.Failed -> backupFailureResult(
                taskId = taskId,
                target = targetLabel,
                trigger = triggerLabel,
                error = result.error
            )
        }
    }

    private suspend fun runBackup(
        isManualTrigger: Boolean,
        isChangeTriggered: Boolean,
        taskId: String,
        target: String,
        trigger: String
    ): androidx.work.ListenableWorker.Result {
        SyncDiagnostics.queued(taskId, target, trigger)
        val startedAt = SyncDiagnostics.start(taskId, target, trigger)

        try {
            val webDavHelper = WebDavHelper(applicationContext)

            if (!webDavHelper.isConfigured()) {
                android.util.Log.w(TAG, "WebDAV not configured, skipping backup")
                SyncDiagnostics.skipped(taskId, target, trigger, "not_configured", startedAt)
                return androidx.work.ListenableWorker.Result.success()
            }

            if (!isManualTrigger && !webDavHelper.isAutoBackupEnabled()) {
                android.util.Log.w(TAG, "Auto backup disabled, skipping backup")
                SyncDiagnostics.skipped(taskId, target, trigger, "auto_backup_disabled", startedAt)
                return androidx.work.ListenableWorker.Result.success()
            }

            // 改动触发有自己的节流（静默期 + 最小上传间隔），不受周期备份的 12 小时闸门限制，
            // 否则「改动后同步」会被上一次周期备份挡掉，失去意义。
            if (isChangeTriggered) {
                val waitMinutes = webDavHelper.minutesUntilChangeTriggeredBackupAllowed()
                if (waitMinutes > 0) {
                    // Retries are expected while the minimum interval is
                    // active. Keep the first attempt visible for diagnosis,
                    // but avoid emitting identical Info events on every
                    // backoff attempt.
                    if (runAttemptCount == 0) {
                        android.util.Log.d(TAG, "Change-triggered backup throttled, " + waitMinutes + "min remaining")
                        SyncDiagnostics.skipped(taskId, target, trigger, "min_interval_not_elapsed", startedAt)
                    }
                    // Do not acknowledge the work while the interval is
                    // still active. Result.success() would permanently drop
                    // the mutation because no later event is guaranteed to
                    // arrive. The request has a linear backoff and will be
                    // retried until the interval expires.
                    return androidx.work.ListenableWorker.Result.retry()
                }
            } else if (!isManualTrigger && !webDavHelper.shouldAutoBackup()) {
                android.util.Log.d(TAG, "Backup not needed yet (< 12 hours since last backup)")
                SyncDiagnostics.skipped(taskId, target, trigger, "not_due", startedAt)
                return androidx.work.ListenableWorker.Result.success()
            }

            val host = WebDavGateway.hostOf(
                webDavHelper.getCurrentConfig()?.serverUrl.orEmpty()
            )
            if (host.isNotEmpty()) {
                if (WebDavBackoffState.isTemporarilyDisabled(host)) {
                    val waitMs = WebDavBackoffState.suggestedWaitMillis(host)
                    android.util.Log.i(
                        TAG,
                        "Host $host temporarily disabled (${waitMs}ms remaining); skip."
                    )
                    SyncDiagnostics.blocked(taskId, target, trigger, "host_temporarily_disabled", startedAt)
                    return androidx.work.ListenableWorker.Result.success()
                }
                if (WebDavBackoffState.shouldBlock(host)) {
                    val waitMs = WebDavBackoffState.suggestedWaitMillis(host)
                    android.util.Log.i(
                        TAG,
                        "Host $host backoff until +${waitMs}ms; skip."
                    )
                    SyncDiagnostics.blocked(taskId, target, trigger, "host_backoff", startedAt)
                    return androidx.work.ListenableWorker.Result.success()
                }
            }

            android.util.Log.d(TAG, "Proceeding with backup (manual=$isManualTrigger)")

            val database = PasswordDatabase.getDatabase(applicationContext)
            val passwordRepo = PasswordRepository(database.passwordEntryDao())
            val secureItemRepo = SecureItemRepository(database.secureItemDao())

            val passwords = passwordRepo.getAllLocalPasswordEntries()
            val secureItems = secureItemRepo.getAllLocalItems()

            val securityManager = takagi.ru.monica.security.SecurityManager(applicationContext)
            var failedPasswordDecryptCount = 0
            val decryptedPasswords = passwords.map { entry ->
                try {
                    entry.copy(password = securityManager.decryptData(entry.password))
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "无法解密密码条目: ${e.message}")
                    failedPasswordDecryptCount++
                    entry.copy(password = "")
                }
            }
            if (failedPasswordDecryptCount > 0) {
                android.util.Log.w(
                    TAG,
                    "Auto backup postponed: $failedPasswordDecryptCount password secrets could not be decrypted"
                )
                SyncDiagnostics.blocked(
                    taskId = taskId,
                    target = target,
                    trigger = trigger,
                    reason = "decrypt_failed",
                    startedAt = startedAt,
                    detail = "passwords=$failedPasswordDecryptCount"
                )
                return androidx.work.ListenableWorker.Result.retry()
            }

            val backupPreferences = webDavHelper.getBackupPreferences()

            android.util.Log.d(
                TAG,
                "Creating Monica-local backup with ${passwords.size} passwords and ${secureItems.size} secure items"
            )

            val backupResult = webDavHelper.createAndUploadBackup(
                passwords = decryptedPasswords,
                secureItems = secureItems,
                preferences = backupPreferences,
                isPermanent = false,
                isManualTrigger = isManualTrigger,
                contentScope = BackupContentScope.MONICA_LOCAL_ONLY
            )

            if (backupResult.isSuccess) {
                if (host.isNotEmpty()) {
                    WebDavBackoffState.recordSuccess(host)
                }
                val report = backupResult.getOrNull()
                android.util.Log.d(TAG, "Auto backup completed: ${report?.getSummary()}")
                if (report != null && report.hasIssues()) {
                    android.util.Log.w(TAG, "Backup has issues but completed")
                }
                SyncDiagnostics.success(
                    taskId = taskId,
                    target = target,
                    trigger = trigger,
                    startedAt = startedAt,
                    detail = "passwords=${passwords.size} secureItems=${secureItems.size} hasIssues=${report?.hasIssues() == true}"
                )
                return androidx.work.ListenableWorker.Result.success()
            }

            val error = backupResult.exceptionOrNull()
            val classified = WebDavErrorClassifier.classify(error)
            if (error != null) {
                SyncDiagnostics.failed(taskId, target, trigger, startedAt, error, detail = "kind=${classified.kind}")
            } else {
                SyncDiagnostics.blocked(taskId, target, trigger, "unknown_backup_failure", startedAt, detail = "kind=${classified.kind}")
            }
            android.util.Log.e(
                TAG,
                "Auto backup failed: kind=${classified.kind}, msg=${error?.message}",
                error
            )
            return when (classified.kind) {
                WebDavErrorKind.RateLimited,
                WebDavErrorKind.AuthFailed,
                WebDavErrorKind.MethodNotAllowed,
                WebDavErrorKind.MalformedResponse -> androidx.work.ListenableWorker.Result.success()
                WebDavErrorKind.Timeout,
                WebDavErrorKind.NetworkUnreachable -> androidx.work.ListenableWorker.Result.retry()
                else -> androidx.work.ListenableWorker.Result.retry()
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Auto backup error", e)
            return backupFailureResult(taskId, target, trigger, e, startedAt)
        }
    }

    private fun backupFailureResult(
        taskId: String,
        target: String,
        trigger: String,
        error: Exception,
        startedAt: Long? = null
    ): androidx.work.ListenableWorker.Result {
        val classified = WebDavErrorClassifier.classify(error)
        SyncDiagnostics.failed(taskId, target, trigger, startedAt, error, detail = "kind=${classified.kind}")
        return when (classified.kind) {
            WebDavErrorKind.RateLimited,
            WebDavErrorKind.AuthFailed,
            WebDavErrorKind.MethodNotAllowed,
            WebDavErrorKind.MalformedResponse -> androidx.work.ListenableWorker.Result.success()
            else -> androidx.work.ListenableWorker.Result.retry()
        }
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
        const val WORK_NAME = "auto_webdav_backup"
        const val KEY_MANUAL_TRIGGER = "manual_trigger"
        const val KEY_CHANGE_TRIGGERED = "change_triggered"
    }
}
