package takagi.ru.monica

import android.app.Application
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.data.AppLauncherIcon
import takagi.ru.monica.data.AppLauncherLabel
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.mdbx.MdbxDiagLogger
import takagi.ru.monica.perf.MainThreadStallMonitor
import takagi.ru.monica.security.AppUpdateSecurityGuard
import takagi.ru.monica.sync.AndroidSyncNetworkGate
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.utils.AppLauncherIconManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.webdav.WebDavBackoffState
import takagi.ru.monica.webdav.WebDavTlsSettings
import takagi.ru.monica.workers.KeePassRemoteUploadWorker

/**
 * Monica 应用程序入口
 * 
 * 负责初始化全局依赖注入容器（Koin）
 * 
 * 安全设计考量:
 * - Koin 在进程级别初始化，生命周期与应用一致
 * - 敏感依赖使用 single 作用域，避免多实例
 * - 模块化设计便于测试时替换 mock 实现
 */
class MonicaApplication : Application() {
    
    companion object {
        private const val TAG = "MonicaApplication"
    }
    
    override fun onCreate() {
        super.onCreate()

        SessionManager.attachAppContext(this)

        AppUpdateSecurityGuard.enforceLockIfAppUpdated(
            context = this,
            reason = "application_on_create"
        )
        
        initKoin()
        SyncTaskRunner.installNetworkGate(AndroidSyncNetworkGate(this))
        MainThreadStallMonitor.start()
        MdbxDiagLogger.initialize(this)
        syncLauncherEntryPointsWithSettings()
        WebDavBackoffState.attachPersistence(this)
        // 必须早于任何 WebDAV 客户端构造，否则后台备份会用默认档位建连。
        WebDavTlsSettings.attachPersistence(this)
        scheduleKeePassRemoteUploadRecovery()
        scheduleAttachmentHousekeeping()
    }
    
    /**
     * 初始化 Koin 依赖注入框架
     */
    private fun initKoin() {
        startKoin {
            // 关闭日志以提高性能和安全性
            androidLogger(Level.NONE)
            
            // 提供 Android Context
            androidContext(this@MonicaApplication)
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
     * 附件子系统的启动级维护：
     * - 扫描并删除 Room 已不再引用的密文孤儿文件
     *
     * 在独立协程里跑，失败不影响应用启动。
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun scheduleAttachmentHousekeeping() {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val facade = AttachmentContainer.facade(this@MonicaApplication)
                facade.purgeOrphanedLocalBlobs()
            }.onFailure { Log.w(TAG, "Attachment housekeeping failed", it) }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun syncLauncherEntryPointsWithSettings() {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
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

}

