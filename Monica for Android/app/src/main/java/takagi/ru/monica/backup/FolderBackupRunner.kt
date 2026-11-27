package takagi.ru.monica.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import takagi.ru.monica.R
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.transfer.DatabaseExportJobs
import takagi.ru.monica.transfer.DataTaskKind
import takagi.ru.monica.utils.BackupContentScope
import takagi.ru.monica.utils.WebDavHelper
import java.io.File

internal class FolderBackupDeferred : CancellationException("Folder backup requires the same foreground session")
internal class FolderBackupIncomplete : Exception()

internal object FolderBackupRunner {
    private val mutex = Mutex()
    private val mutableRunning = MutableStateFlow(false)
    val running = mutableRunning.asStateFlow()
    private val mutableStatus = MutableStateFlow(0)
    val status = mutableStatus.asStateFlow()
    @Volatile var foreground = false
        set(value) {
            if (field && !value) foregroundEpoch.incrementAndGet()
            field = value
        }
    val foregroundEpoch = java.util.concurrent.atomic.AtomicLong()

    fun authorized(generation: Long): Boolean = foreground && SessionManager.isUnlocked.value &&
        SessionManager.lockGeneration.value == generation && !SessionManager.isSessionExpiredForForegroundWork()

    fun startManual(context: Context): Boolean = DatabaseExportJobs.startTask(
        context.applicationContext, "local", DataTaskKind.FOLDER_BACKUP
    ) {
        try {
            run(context.applicationContext, manual = true)
            Result.success(context.getString(mutableStatus.value))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun run(context: Context, manual: Boolean) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) throw IllegalStateException(context.getString(R.string.transfer_task_busy))
        var artifact: File? = null
        try {
            mutableRunning.value = true
            mutableStatus.value = 0
            val generation = SessionManager.lockGeneration.value
            val visibility = foregroundEpoch.get()
            if (!authorized(generation)) throw FolderBackupDeferred()
            val store = FolderBackupStore(context)
            val config = store.config()
            check(config.configured && (manual || config.automatic))
            val dirtyRevision = FolderBackupScheduler.currentRevision(context)
            val job = currentCoroutineContext()
            val checkSession = {
                job.ensureActive()
                if (!authorized(generation) || foregroundEpoch.get() != visibility) throw FolderBackupDeferred()
            }
            val checkRequest = {
                checkSession()
                if (store.config().revision != config.revision) throw FolderBackupDeferred()
            }
            withTimeout(10 * 60 * 1000L) {
                val destination = SafBackupDestination(context, Uri.parse(config.tree))
                val existing = runInterruptible { destination.list() }
                checkRequest()
                val db = PasswordDatabase.getDatabase(context)
                val security = SecurityManager(context)
                val passwords = PasswordRepository(db.passwordEntryDao()).getAllLocalPasswordEntries().map { entry ->
                    checkSession()
                    entry.copy(password = security.decryptData(entry.password))
                }
                val items = SecureItemRepository(db.secureItemDao()).getAllLocalItems()
                checkRequest()
                val (file, report) = WebDavHelper(context).createBackupZip(passwords, items,
                    preferences = BackupPreferences(), contentScope = BackupContentScope.MONICA_LOCAL_ONLY,
                    allowBackupEncryption = true, backupEncryptionPassword = config.password).getOrThrow()
                artifact = file
                checkRequest()
                // Existing archive generation can classify unreadable secondary data as warnings.
                // Rotation requires complete data; informational scope warnings remain allowed.
                if (!report.success || !report.dataComplete || report.failedItems.isNotEmpty() || report.skippedItems.isNotEmpty()) throw FolderBackupIncomplete()
                // A concurrent edit must not allow a mixed archive to replace older backups.
                if (FolderBackupScheduler.currentRevision(context) != dirtyRevision) throw FolderBackupDeferred()
                val size = file.length()
                check(size > 0)
                val digest = runInterruptible { file.inputStream().use { FolderBackupPolicy.digest(it, size, checkSession) } }
                val records = store.records()
                val (uri, name) = runInterruptible {
                    checkRequest()
                    destination.create(manual, existing.map { it.uri }.toSet() + records.map { it.uri })
                }
                var record = FolderBackupRecord(uri.toString(), config.tree, name, size, digest,
                    System.currentTimeMillis(), config.keyId, manual)
                // Persist ownership before the first byte; a process death leaves an incomplete record.
                store.saveRecord(record)
                runInterruptible {
                    checkRequest()
                    destination.write(uri, file, checkSession)
                    destination.verify(record, checkSession)
                }
                checkRequest()
                record = record.copy(complete = true)
                store.saveRecord(record)
                FolderBackupScheduler.completed(context, dirtyRevision)
                mutableStatus.value = R.string.folder_backup_written
                try {
                    val all = store.records()
                    val candidates = FolderBackupPolicy.cleanupCandidates(all, config.tree, config.keyId,
                        config.keep, System.currentTimeMillis(), destination.isLocal)
                    if (candidates.isNotEmpty()) runInterruptible {
                        checkRequest()
                        if (FolderBackupScheduler.currentRevision(context) != dirtyRevision) throw FolderBackupDeferred()
                        val listed = destination.list().map { it.uri }.toSet()
                        // Verify every retained generation first. Missing/corrupt/partial listings fail closed.
                        val retained = all.filter { it.complete && it.tree == config.tree && it.keyId == config.keyId && it !in candidates }
                        check(retained.isNotEmpty() && (retained + candidates).all { it.uri in listed })
                        retained.forEach { destination.verify(it, checkSession) }
                        candidates.forEach { old ->
                            checkRequest()
                            destination.deleteOwned(old, checkSession) {
                                checkRequest()
                                if (FolderBackupScheduler.currentRevision(context) != dirtyRevision) throw FolderBackupDeferred()
                            }
                            store.forgetRecord(old.uri)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableStatus.value = R.string.folder_backup_cleanup_deferred }
            }
        } catch (cancelled: CancellationException) {
            mutableStatus.value = R.string.folder_backup_deferred
            throw cancelled
        } catch (error: Exception) {
            mutableStatus.value = when (error) {
                is SecurityException -> R.string.folder_backup_permission_error
                is FolderBackupIncomplete -> R.string.folder_backup_incomplete
                else -> R.string.folder_backup_failed
            }
            throw error
        } finally {
            artifact?.delete()
            mutableRunning.value = false
            mutex.unlock()
        }
    }
}
