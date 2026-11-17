package takagi.ru.monica.autofill_ng.defaultmanager

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

import takagi.ru.monica.autofill_ng.privilege.PrivilegeAccess
import takagi.ru.monica.autofill_ng.privilege.PrivilegeBackend

enum class DefaultManagerAccess { UNSUPPORTED, STOPPED, PERMISSION, READY }

object ShizukuDefaultManager {
    const val PERMISSION_REQUEST = 9532
    private val lock = Mutex()
    private const val BACKUP = "previous"

    fun access(context: Context? = null): DefaultManagerAccess = runCatching {
        if (context != null && PrivilegeAccess.backend(context) == PrivilegeBackend.ROOT) {
            return@runCatching when {
                Build.VERSION.SDK_INT < 34 -> DefaultManagerAccess.UNSUPPORTED
                PrivilegeAccess.rootAllowed(context) -> DefaultManagerAccess.READY
                else -> DefaultManagerAccess.PERMISSION
            }
        }
        when {
            Build.VERSION.SDK_INT < 34 -> DefaultManagerAccess.UNSUPPORTED
            !Shizuku.pingBinder() -> DefaultManagerAccess.STOPPED
            Shizuku.isPreV11() || Shizuku.getVersion() < 13 || Shizuku.getUid() !in setOf(0, 2000) -> DefaultManagerAccess.UNSUPPORTED
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> DefaultManagerAccess.PERMISSION
            else -> DefaultManagerAccess.READY
        }
    }.getOrDefault(DefaultManagerAccess.STOPPED)

    fun requestPermission(): Boolean = runCatching {
        if (access() != DefaultManagerAccess.PERMISSION || Shizuku.shouldShowRequestPermissionRationale()) false
        else { Shizuku.requestPermission(PERMISSION_REQUEST); true }
    }.getOrDefault(false)

    fun observeAccess() = callbackFlow {
        val received = Shizuku.OnBinderReceivedListener { trySend(access()) }
        val dead = Shizuku.OnBinderDeadListener { trySend(access()) }
        val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> trySend(access()) }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        trySend(access())
        awaitClose {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
            Shizuku.removeRequestPermissionResultListener(permission)
        }
    }.distinctUntilChanged()

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("default_password_manager", Context.MODE_PRIVATE)

    fun previous(context: Context): SettingsSnapshot? = runCatching {
        preferences(context).getString(BACKUP, null)?.let(::snapshotFromJson)
            ?.takeIf { it.userId == android.os.Process.myUid() / 100_000 }
    }.getOrNull()

    suspend fun read(context: Context): SettingsSnapshot = withService(context) { it.read().checkedSnapshot() }

    suspend fun apply(context: Context, expected: SettingsSnapshot, includeAutofill: Boolean, keepOthers: Boolean): SettingsSnapshot =
        withService(context) { service ->
            val current = service.read().checkedSnapshot()
            check(current.equivalentTo(expected)) { "System selection changed" }
            val desired = expected.withSelection(MonicaDefaultServices.credential,
                MonicaDefaultServices.autofill.takeIf { includeAutofill }, keepOthers)
            (if (current.equivalentTo(desired)) current
            else {
                // Persist before the first write. Only component names; no vault or credential contents.
                check(preferences(context).edit().putString(BACKUP, current.toJson()).commit())
                check(access(context) == DefaultManagerAccess.READY)
                service.apply(expected.toBundle(), includeAutofill, keepOthers).checkedSnapshot()
            }).also { HyperOsDiagnostics.verified(context, it) }
        }

    suspend fun restore(context: Context, expected: SettingsSnapshot): SettingsSnapshot = withService(context) { service ->
        val target = requireNotNull(previous(context))
        service.restore(expected.toBundle(), target.toBundle()).checkedSnapshot().also {
            check(preferences(context).edit().remove(BACKUP).commit())
            HyperOsDiagnostics.clearVerified(context)
        }
    }

    private fun android.os.Bundle.checkedSnapshot(): SettingsSnapshot {
        check(getBoolean("ok")) { "System settings could not be verified" }
        return toSnapshot()
    }

    private suspend fun <T> withService(context: Context, action: (IMonicaDefaultManager) -> T): T = lock.withLock {
        check(access(context) == DefaultManagerAccess.READY)
        if (PrivilegeAccess.backend(context) == PrivilegeBackend.ROOT) {
            return@withLock withContext(NonCancellable + Dispatchers.IO) {
                PrivilegeAccess.transaction.withLock {
                    check(PrivilegeAccess.backend(context) == PrivilegeBackend.ROOT && access(context) == DefaultManagerAccess.READY)
                    action(RootDefaultManager(context.applicationContext))
                }
            }
        }
        val app = context.applicationContext
        // A fresh connection must not receive the previous transient service's delayed death callback.
        val args = Shizuku.UserServiceArgs(ComponentName(app, DefaultManagerUserService::class.java))
            .tag("monica-default-manager-${android.os.Process.myUid()}-${java.util.UUID.randomUUID()}")
            .version(1).processNameSuffix("default_manager").debuggable(false).daemon(true)
        val ready = CompletableDeferred<IMonicaDefaultManager>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder?.pingBinder() == true) ready.complete(IMonicaDefaultManager.Stub.asInterface(binder))
                else ready.completeExceptionally(IllegalStateException("Helper unavailable"))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                ready.completeExceptionally(IllegalStateException("Helper disconnected"))
            }
        }
        try {
            withContext(Dispatchers.Main.immediate) { Shizuku.bindUserService(args, connection) }
            val helper = withTimeout(10_000) { ready.await() }
            // Finish verification/rollback even if the user closes the settings panel.
            withContext(NonCancellable + Dispatchers.IO) {
                check(PrivilegeAccess.backend(context) == PrivilegeBackend.SHIZUKU && access(context) == DefaultManagerAccess.READY)
                action(helper)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
        }
    }
}
