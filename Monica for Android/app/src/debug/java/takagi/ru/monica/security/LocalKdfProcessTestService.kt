package takagi.ru.monica.security

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process

/** Non-exported debug-only service. Commands select only fixed synthetic behavior, never arbitrary keys or paths. */
class LocalKdfProcessTestService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger
    private var fixture: LocalKdfProcessFixture? = null
    private var manager: SecurityManager? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("local-kdf-process-fixture").apply { start() }
        messenger = Messenger(Handler(worker.looper) { message ->
            if (message.sendingUid != Process.myUid()) return@Handler true
            val reply = Bundle().apply {
                putInt("pid", Process.myPid())
                putInt("uid", Process.myUid())
                putString("process", java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000'))
            }
            try {
                val id = checkNotNull(message.data.getString("fixture"))
                if (fixture?.id != id) { manager = null; fixture = LocalKdfProcessFixture(this, id) }
                val f = checkNotNull(fixture)
                val success = when (message.what) {
                    OPEN -> {
                        manager = f.freshManager()
                        val current = checkNotNull(manager)
                        current.unlockVaultWithPassword(f.password) && f.verifyContents(current)
                    }
                    READ_EXISTING -> f.verifyContents(checkNotNull(manager))
                    RACE -> {
                        val current = checkNotNull(manager)
                        var callbacks = 0
                        current.resetMasterPassword(f.password, f.password,
                            SecurityManager.LocalPasswordChangeAttempt()) {
                            if (++callbacks == 2) f.waitForOtherWriter("ime")
                        }
                    }
                    CLOSE -> { manager = null; fixture = null; SecurityManager.clearRuntimeUnlockCache(); true }
                    else -> false
                }
                reply.putBoolean("success", success)
            } catch (error: Exception) {
                reply.putBoolean("success", false)
                reply.putString("errorClass", error.javaClass.simpleName)
            }
            message.replyTo?.send(Message.obtain(null, message.what).apply { arg1 = message.arg1; data = reply })
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { worker.quitSafely(); super.onDestroy() }

    companion object {
        const val OPEN = 1
        const val READ_EXISTING = 2
        const val RACE = 3
        const val CLOSE = 4
    }
}
