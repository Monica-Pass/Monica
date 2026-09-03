package takagi.ru.monica

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.data.AppLauncherIcon
import takagi.ru.monica.data.AppLauncherLabel
import takagi.ru.monica.mdbx.MdbxDiagLogger
import takagi.ru.monica.perf.MainThreadStallMonitor
import takagi.ru.monica.security.AppUpdateSecurityGuard
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.sync.AndroidSyncNetworkGate
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.utils.AppLauncherIconManager
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.webdav.WebDavBackoffState
import takagi.ru.monica.webdav.WebDavCertificateTrustStore
import takagi.ru.monica.workers.KeePassRemoteUploadWorker

/**
 * Monica 应用程序入口
 *
 * 安全关键的锁状态检查仍然在首帧前同步完成；与首帧无关的恢复、清理和诊断则延迟到启动安静期执行。
 */
class MonicaApplication : Application() {

    companion object {
        private const val TAG = "MonicaApplication"
        private const val POST_LAUNCH_DELAY_MS = 1_200L
        private const val HOUSEKEEPING_DELAY_MS = 2_500L
    }

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        SessionManager.attachAppContext(this)

        // Security invariant: an app update must invalidate the previous runtime
        // session before any activity is allowed to restore it. Keep this sync.
        AppUpdateSecurityGuard.enforceLockIfAppUpdated(
            context = this,
            reason = "application_on_create"
        )

        SyncTaskRunner.installNetworkGate(AndroidSyncNetworkGate(this))

        // Logger initialization no longer performs file writes on this thread.
        MdbxDiagLogger.initialize(this)
        WebDavBackoffState.attachPersistence(this)
        WebDavCertificateTrustStore.attach(this)

        schedulePostLaunchMaintenance()
    }

    /**
     * Work that is important for eventual consistency or diagnostics but is not
     * required to draw or authenticate the first screen. Delaying it avoids
     * competing with class loading, Compose startup and database initialization.
     */
    private fun schedulePostLaunchMaintenance() {
        startupScope.launch {
            delay(POST_LAUNCH_DELAY_MS)
            MainThreadStallMonitor.start()
            scheduleKeePassRemoteUploadRecovery()
            syncLauncherEntryPointsWithSettings()
        }

        startupScope.launch(Dispatchers.IO) {
            delay(HOUSEKEEPING_DELAY_MS)
            runAttachmentHousekeeping()
        }
    }

    private fun scheduleKeePassRemoteUploadRecovery() {
        runCatching {
            KeePassRemoteUploadWorker.enqueueIfPending(this)
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule KeePass remote upload recovery", error)
        }
    }

    /**
     * 附件子系统维护：扫描并删除 Room 已不再引用的密文孤儿文件。
     */
    private suspend fun runAttachmentHousekeeping() = withContext(Dispatchers.IO) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isPowerSaveMode) {
            Log.d(TAG, "Attachment housekeeping deferred while battery saver is active")
            return@withContext
        }
        runCatching {
            val facade = AttachmentContainer.facade(this@MonicaApplication)
            facade.purgeOrphanedLocalBlobs()
        }.onFailure { Log.w(TAG, "Attachment housekeeping failed", it) }
    }

    private suspend fun syncLauncherEntryPointsWithSettings() = withContext(Dispatchers.IO) {
        runCatching {
            val settings = SettingsManager(this@MonicaApplication).settingsFlow.first()
            AppLauncherIconManager.repairLaunchEntryPointsAfterUpgrade(
                this@MonicaApplication,
                settings.appLauncherIcon,
                settings.appLauncherLabel
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to sync launcher entry points with settings", error)
            runCatching {
                AppLauncherIconManager.repairLaunchEntryPointsAfterUpgrade(
                    this@MonicaApplication,
                    AppLauncherIcon.MODERN,
                    AppLauncherLabel.MONICA_PASS
                )
            }.onFailure { fallbackError ->
                Log.w(TAG, "Failed to apply fallback launcher entry points", fallbackError)
            }
        }
    }
}
