package takagi.ru.monica.backup

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.room.InvalidationTracker
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.steam.data.SteamDatabase
import takagi.ru.monica.utils.ChangeTriggeredBackupScheduler
import java.util.concurrent.TimeUnit

/** Durable counters contain no URI, password or vault content. Disabled installations do no work. */
internal object FolderBackupScheduler {
    private const val PREFS = "folder_backup_schedule"
    private const val WORK = "folder_backup_pending"
    private const val PERIODIC = "folder_backup_periodic"
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observing = false
    @Volatile private var revisionTrackingFailed = false
    private val observers = mutableListOf<InvalidationTracker.Observer>()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun revision(context: Context) = synchronized(lock) { prefs(context).getLong("revision", 0) }
    fun completed(context: Context, revision: Long) = synchronized(lock) {
        check(prefs(context).edit().putLong("completed", revision).putLong("last", System.currentTimeMillis()).commit())
    }

    fun configure(context: Context, config: FolderBackupConfig) {
        synchronized(lock) {
            check(prefs(context).edit().putBoolean("configured", config.configured)
                .putBoolean("automatic", config.automatic).putBoolean("changes", config.afterChanges).commit())
        }
        val work = WorkManager.getInstance(context)
        if (config.automatic) {
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FolderBackupWorker>(12, TimeUnit.HOURS)
                    .setInputData(workDataOf("periodic" to true)).build())
            changed(context)
        } else {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(WORK)
        }
        observe(context)
    }

    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { if (prefs(app).getBoolean("configured", false)) observe(app) }
                .onFailure { android.util.Log.w("FolderBackup", "Observation deferred: ${it.javaClass.simpleName}") }
        }
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FolderBackupRunner.foreground = true
                try {
                    SessionManager.isUnlocked.collectLatest { unlocked ->
                        if (unlocked) while (true) {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    if (prefs(app).getBoolean("configured", false)) observe(app)
                                    if (prefs(app).getBoolean("automatic", false)) enqueue(app)
                                }.onFailure { android.util.Log.w("FolderBackup", "Scheduling deferred: ${it.javaClass.simpleName}") }
                            }
                            delay(60_000)
                        }
                    }
                } finally { FolderBackupRunner.foreground = false }
            }
        }
    }

    private fun observe(context: Context) = synchronized(lock) {
        if (observing) return@synchronized
        val app = context.applicationContext
        fun observer(vararg tables: String) = object : InvalidationTracker.Observer(tables) {
            override fun onInvalidated(tables: Set<String>) {
                // Track every configured vault mutation, including manual-only backup sessions.
                // Invalidation already runs off the UI thread; do not defer the revision update.
                try {
                    if (prefs(app).getBoolean("configured", false)) changed(app)
                } catch (error: Exception) {
                    revisionTrackingFailed = true
                    android.util.Log.w("FolderBackup", "Revision tracking failed: ${error.javaClass.simpleName}")
                }
            }
        }
        val local = observer("password_entries", "secure_items", "custom_fields", "attachments", "passkeys",
            "password_history_entries", "categories")
        val steam = observer("steam_accounts")
        PasswordDatabase.getDatabase(app).invalidationTracker.addObserver(local)
        SteamDatabase.getDatabase(app).invalidationTracker.addObserver(steam)
        observers += local
        observers += steam
        observing = true
    }

    fun changed(context: Context) {
        synchronized(lock) {
            val p = prefs(context)
            check(p.edit().putLong("revision", p.getLong("revision", 0) + 1)
                .putLong("changed", System.currentTimeMillis()).commit())
        }
        if (prefs(context).getBoolean("changes", true) && !ChangeTriggeredBackupScheduler.isSuppressed()) enqueue(context)
    }

    /** Drain Room invalidations before checking the revision around archive generation. */
    @android.annotation.SuppressLint("RestrictedApi")
    fun currentRevision(context: Context): Long {
        observe(context)
        PasswordDatabase.getDatabase(context).invalidationTracker.refreshVersionsSync()
        SteamDatabase.getDatabase(context).invalidationTracker.refreshVersionsSync()
        check(!revisionTrackingFailed) { "Backup revision tracking is unavailable" }
        return revision(context)
    }

    internal fun dueDelay(context: Context): Long? = synchronized(lock) {
        val p = prefs(context)
        if (!p.getBoolean("automatic", false)) return@synchronized null
        val now = System.currentTimeMillis()
        val last = p.getLong("last", 0)
        if ((!p.getBoolean("changes", true) || p.getLong("revision", 0) <= p.getLong("completed", 0)) && now - last < FolderBackupPolicy.PERIOD_MS) return@synchronized null
        FolderBackupPolicy.delayMillis(now, p.getLong("changed", 0), last)
    }

    private fun enqueue(context: Context) {
        val delay = dueDelay(context) ?: return
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FolderBackupWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}

internal class FolderBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val delay = FolderBackupScheduler.dueDelay(applicationContext) ?: return Result.success()
        if (!SecureStorageStartup.readyForMaintenance || !FolderBackupRunner.authorized(SessionManager.lockGeneration.value)) {
            // Keep durable revision/last time untouched. Foreground unlock will enqueue it again.
            return Result.success()
        }
        if (delay > 0) return Result.retry()
        return try {
            FolderBackupRunner.run(applicationContext, manual = false)
            Result.success()
        } catch (_: FolderBackupDeferred) { Result.success() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { Result.failure() }
        catch (_: FolderBackupIncomplete) { Result.failure() }
        catch (_: Exception) { Result.retry() }
    }
}
