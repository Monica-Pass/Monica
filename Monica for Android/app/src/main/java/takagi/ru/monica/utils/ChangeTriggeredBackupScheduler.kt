package takagi.ru.monica.utils

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.workers.AutoBackupWorker
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 数据改动后延迟触发 WebDAV 备份。
 *
 * 监听 Room 表失效而不是在各 repository 的写入方法里打标记：写入路径分散在十余个
 * repository 上，逐个改动既易漏又会持续腐化。这两张表正是 [AutoBackupWorker] 实际
 * 打包的范围（KeePass / MDBX 由各自的同步入口负责，不在 WebDAV 主备份内）。
 */
class ChangeTriggeredBackupScheduler(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)
    private val lastEnqueueUptime = AtomicLong(0L)

    private val observer = object : InvalidationTracker.Observer(OBSERVED_TABLES) {
        override fun onInvalidated(tables: Set<String>) {
            onDataChanged()
        }
    }

    /**
     * 开始监听。重复调用是安全的，Room 会忽略已注册的同一 observer 实例。
     */
    fun start() {
        val database = PasswordDatabase.getDatabase(context)
        database.invalidationTracker.addObserver(observer)
    }

    fun stop() {
        val database = PasswordDatabase.getDatabase(context)
        database.invalidationTracker.removeObserver(observer)
    }

    private fun onDataChanged() {
        if (isSuppressed()) return

        val helper = WebDavHelper(context)
        if (!helper.isConfigured()) return

        val config = helper.getChangeTriggeredBackupConfig()
        if (!helper.isAutoBackupEnabled() || !config.enabled) return

        // 导入或恢复会在短时间内写入大量行，每次失效都入队会让 WorkManager 自身的
        // 数据库承受成百上千次事务。先在内存里合并，再交给 WorkManager 的延迟去防抖。
        val now = android.os.SystemClock.uptimeMillis()
        val previous = lastEnqueueUptime.get()
        if (previous != 0L && now - previous < ENQUEUE_THROTTLE_MS) return
        if (!lastEnqueueUptime.compareAndSet(previous, now)) return

        enqueue(config)
    }

    /**
     * 用 [ExistingWorkPolicy.REPLACE] 配合 initialDelay 实现静默期：新的改动会替换掉
     * 上一个待执行任务，计时随之重置，因此只有真正停止改动之后才会上传。
     */
    private fun enqueue(config: WebDavHelper.ChangeTriggeredBackupConfig) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val inputData = androidx.work.Data.Builder()
            .putBoolean(AutoBackupWorker.KEY_CHANGE_TRIGGERED, true)
            .build()

        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInitialDelay(
                ChangeTriggeredBackupTiming.requiredDelayMillis(
                    nowMillis = System.currentTimeMillis(),
                    lastBackupTimeMillis = WebDavHelper(context).getLastBackupTime(),
                    quietMinutes = config.quietMinutes,
                    minIntervalMinutes = config.minIntervalMinutes
                ),
                TimeUnit.MILLISECONDS
            )
            .setConstraints(constraints)
            .setInputData(inputData)
            // The delay is calculated from the current backup timestamp. A
            // retry is still needed for the small race where another backup
            // finishes after this request is enqueued.
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                30,
                TimeUnit.SECONDS
            )
            .addTag(TAG)
            .build()

        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val WORK_NAME = "change_triggered_webdav_backup"
        private const val TAG = "change_triggered_backup"

        private val OBSERVED_TABLES = arrayOf("password_entries", "secure_items")

        /** 批量写入时的入队合并窗口。 */
        private const val ENQUEUE_THROTTLE_MS = 5_000L

        private val suppressed = AtomicBoolean(false)

        fun isSuppressed(): Boolean = suppressed.get()

        /**
         * 在恢复备份等批量写入期间暂停触发。
         *
         * 恢复会写入大量行；若因此触发上传，刚恢复的数据会被整包传回远端，
         * 覆盖掉那个可能更完整的备份。
         */
        suspend fun <T> withoutTriggering(block: suspend () -> T): T {
            suppressed.set(true)
            try {
                return block()
            } finally {
                suppressed.set(false)
            }
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
