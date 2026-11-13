package takagi.ru.monica.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.utils.AppLocaleStringResolver
import java.util.UUID

enum class DataTaskKind { EXPORT, IMPORT, WEBDAV_BACKUP }

enum class ExportJobStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class ExportJobState(
    val id: String,
    val sourceKey: String,
    val formatKey: String = "ZIP_BACKUP",
    val progress: TransferProgress = TransferProgress(),
    val status: ExportJobStatus = ExportJobStatus.RUNNING,
    val message: String? = null,
    val kind: DataTaskKind = DataTaskKind.EXPORT,
    val importSummary: takagi.ru.monica.credentialexchange.ImportResultSummary? = null,
    val backupReport: takagi.ru.monica.data.BackupReport? = null,
)

/** Only this process owns the request. Passwords are never put in an Intent or a WorkManager database. */
object DatabaseExportJobs {
    internal class Request(val id: String, val uri: Uri?,
        val context: Context,
        val onFinished: (Throwable?) -> Unit,
        val run: suspend (TransferProgressReporter) -> Result<String>)
    private val mutableState = MutableStateFlow<ExportJobState?>(null)
    val state = mutableState.asStateFlow()
    private var pending: Request? = null
    private var lastProgressUpdate = 0L

    fun start(context: Context, sourceKey: String, uri: Uri, formatKey: String = "ZIP_BACKUP",
        uriGrantFlags: Int = 0,
        run: suspend (TransferProgressReporter) -> Result<String>): Boolean =
        startTask(context, sourceKey, DataTaskKind.EXPORT, uri, formatKey, uriGrantFlags, run = run)

    @Synchronized
    fun startTask(context: Context, sourceKey: String, kind: DataTaskKind,
        uri: Uri? = null, formatKey: String = "ZIP_BACKUP",
        uriGrantFlags: Int = 0,
        onFinished: (Throwable?) -> Unit = {},
        run: suspend (TransferProgressReporter) -> Result<String>): Boolean {
        if (mutableState.value?.status == ExportJobStatus.RUNNING) return false
        val app = context.applicationContext
        val id = UUID.randomUUID().toString()
        pending = Request(id, uri, app, onFinished, run)
        mutableState.value = ExportJobState(id, sourceKey, formatKey, kind = kind)
        try {
            // Only an operation marker is persisted; never a URI, credential, or replayable request.
            app.getSharedPreferences("data_task_marker", Context.MODE_PRIVATE).edit()
                .putString("id", id).putString("kind", kind.name)
                .putString("source", sourceKey).putString("format", formatKey).apply()
            val serviceIntent = Intent(app, DatabaseExportService::class.java).putExtra("operation", id)
            if (kind == DataTaskKind.EXPORT && uri?.scheme == ContentResolver.SCHEME_CONTENT) {
                // Transfer only the exact document and access already granted by the picker.
                // The service owns these temporary permissions until it finishes its work.
                serviceIntent.data = uri
                serviceIntent.clipData = ClipData.newRawUri("export", uri)
                serviceIntent.addFlags(uriGrantFlags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            }
            ContextCompat.startForegroundService(app, serviceIntent)
        } catch (error: Exception) {
            pending = null
            app.getSharedPreferences("data_task_marker", Context.MODE_PRIVATE).edit().clear().apply()
            mutableState.value = mutableState.value?.copy(status = ExportJobStatus.FAILED,
                message = AppLocaleStringResolver(app).get(R.string.transfer_background_start_failed))
            return false
        }
        return true
    }

    /** Cancelling the observing page never cancels the service-owned work. */
    suspend fun <T> await(context: Context, sourceKey: String, kind: DataTaskKind,
        describe: (T) -> String,
        run: suspend (TransferProgressReporter) -> Result<T>): Result<T> {
        val completion = CompletableDeferred<Result<T>>()
        var result: Result<T>? = null
        val started = startTask(context, sourceKey, kind, onFinished = { error ->
            completion.complete(if (error != null) Result.failure(error)
                else result ?: Result.failure(IllegalStateException("Missing task result")))
        }) { reporter ->
            val value = run(reporter)
            result = value
            value.map(describe)
        }
        if (!started) return Result.failure(IllegalStateException(AppLocaleStringResolver(context)
            .get(if (state.value?.status == ExportJobStatus.RUNNING) R.string.transfer_task_busy
                else R.string.transfer_background_start_failed)))
        return completion.await()
    }

    @Synchronized fun recoverInterrupted(context: Context) {
        if (mutableState.value != null) return
        val prefs = context.getSharedPreferences("data_task_marker", Context.MODE_PRIVATE)
        val id = prefs.getString("id", null) ?: return
        val kind = runCatching { DataTaskKind.valueOf(prefs.getString("kind", "EXPORT")!!) }
            .getOrDefault(DataTaskKind.EXPORT)
        mutableState.value = ExportJobState(id, prefs.getString("source", "LOCAL:0")!!,
            formatKey = prefs.getString("format", "ZIP_BACKUP")!!, kind = kind, status = ExportJobStatus.FAILED,
            message = AppLocaleStringResolver(context).get(R.string.transfer_interrupted_check_result))
        prefs.edit().clear().apply()
    }

    @Synchronized fun reportImportProgress(progress: TransferProgress) {
        mutableState.value?.takeIf { it.kind == DataTaskKind.IMPORT }?.let { update(it.id, progress) }
    }
    @Synchronized fun reportImportSummary(summary: takagi.ru.monica.credentialexchange.ImportResultSummary) {
        mutableState.value?.takeIf { it.kind == DataTaskKind.IMPORT && it.status == ExportJobStatus.RUNNING }
            ?.let { mutableState.value = it.copy(importSummary = summary) }
    }
    @Synchronized fun reportBackup(report: takagi.ru.monica.data.BackupReport) {
        mutableState.value?.takeIf { it.kind == DataTaskKind.WEBDAV_BACKUP && it.status == ExportJobStatus.RUNNING }
            ?.let { mutableState.value = it.copy(backupReport = report) }
    }

    @Synchronized internal fun take(id: String?): Request? = pending?.takeIf { it.id == id }?.also { pending = null }
    @Synchronized internal fun update(id: String, progress: TransferProgress) {
        mutableState.value?.takeIf { it.id == id && it.status == ExportJobStatus.RUNNING }?.let {
            val now = System.nanoTime()
            if (it.progress.phase == progress.phase && progress.completed != progress.total &&
                now - lastProgressUpdate < 100_000_000L) return
            lastProgressUpdate = now
            mutableState.value = it.copy(progress = progress)
        }
    }
    @Synchronized internal fun finish(id: String, status: ExportJobStatus, message: String?) {
        mutableState.value?.takeIf { it.id == id }?.let { mutableState.value = it.copy(status = status, message = message) }
    }
    @Synchronized fun dismiss() {
        if (mutableState.value?.status != ExportJobStatus.RUNNING) mutableState.value = null
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class DatabaseExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private val strings by lazy { AppLocaleStringResolver(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = DatabaseExportJobs.take(intent?.getStringExtra("operation"))
        if (request == null) {
            if (work?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        val startupError = runCatching {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL,
                strings.get(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW))
            startForeground(NOTIFICATION_ID, notification(TransferProgress(), running = true))
        }.exceptionOrNull()
        work = scope.launch {
            var status = ExportJobStatus.FAILED
            var message: String? = null
            var failure: Throwable? = null
            val progressObserver = launch {
                DatabaseExportJobs.state.sample(500L).collect { state ->
                    if (state?.id == request.id && state.status == ExportJobStatus.RUNNING) {
                        runCatching { notifications.notify(NOTIFICATION_ID, notification(state.progress, running = true)) }
                    }
                }
            }
            try {
                if (startupError != null) {
                    throw IllegalStateException(strings.get(R.string.transfer_background_start_failed), startupError)
                }
                val result = request.run(TransferProgressReporter { progress ->
                    ensureActive()
                    DatabaseExportJobs.update(request.id, progress)
                })
                message = result.getOrThrow()
                ensureActive()
                status = ExportJobStatus.SUCCEEDED
            } catch (cancelled: CancellationException) {
                failure = cancelled
                status = ExportJobStatus.CANCELLED
                message = strings.get(R.string.transfer_cancelled)
                throw cancelled
            } catch (error: Exception) {
                failure = error
                message = if (DatabaseExportJobs.state.value?.kind == DataTaskKind.EXPORT)
                    databaseExportErrorMessage(this@DatabaseExportService, error)
                else error.message ?: strings.get(R.string.import_data_unknown_error)
            } finally {
                progressObserver.cancel()
                if (status != ExportJobStatus.SUCCEEDED && request.uri != null && !removeIncompleteDocument(request.uri)) {
                    message = listOfNotNull(message, strings.get(R.string.transfer_incomplete_export_file))
                        .joinToString("\n\n")
                }
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    runCatching {
                        notifications.notify(NOTIFICATION_ID, notification(TransferProgress(), running = false,
                            succeeded = status == ExportJobStatus.SUCCEEDED))
                    }
                    // Publish completion only after cleaning up the old foreground notification.
                    // A newly started export can then safely reuse the same service/notification.
                    work = null
                    request.context.getSharedPreferences("data_task_marker", Context.MODE_PRIVATE).edit().clear().apply()
                    DatabaseExportJobs.finish(request.id, status, message)
                    request.onFinished(failure)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun removeIncompleteDocument(uri: Uri): Boolean {
        val removed = try {
            android.provider.DocumentsContract.isDocumentUri(this, uri) &&
                android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (_: Exception) { false }
        if (removed) return true
        // Some file pickers return a content URI or do not implement deleteDocument.
        // Only touch the exact document created for this export, never its parent.
        return try { contentResolver.delete(uri, null, null) > 0 } catch (_: Exception) { false }
    }

    private fun notification(progress: TransferProgress, running: Boolean, succeeded: Boolean = false): android.app.Notification {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra(DataTaskNavigation.EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val kind = DatabaseExportJobs.state.value?.kind ?: DataTaskKind.EXPORT
        val title = if (running) when (kind) {
            DataTaskKind.EXPORT -> R.string.exporting
            DataTaskKind.IMPORT -> R.string.importing
            DataTaskKind.WEBDAV_BACKUP -> R.string.webdav_backup_in_progress
        } else if (succeeded) R.string.transfer_task_done else R.string.transfer_task_failed
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_key)
            .setContentTitle(strings.get(title))
            .setContentText(if (running) strings.get(progress.phase.labelRes()) + (progress.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "") else strings.get(R.string.transfer_open_result))
            .setContentIntent(intent).setOnlyAlertOnce(true).setOngoing(running).setAutoCancel(!running)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setSilent(true)
            .apply { if (running) setProgress(100, ((progress.fraction ?: 0f) * 100).toInt(), progress.fraction == null) }
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        work?.cancel()
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "database_transfers"
        private const val NOTIFICATION_ID = 4318
    }
}
