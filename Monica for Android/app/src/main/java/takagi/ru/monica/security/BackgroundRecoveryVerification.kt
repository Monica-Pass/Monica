package takagi.ru.monica.security

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Optional, one-slot rehearsal. It never occupies the authentication/autofill worker or queues passwords. */
internal object BackgroundRecoveryVerification {
    private val occupied = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { job ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            job.run()
        }, "monica-recovery-check").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())

    @MainThread
    fun start(manager: SecurityManager, password: String, lifecycle: Lifecycle) {
        if (!SessionManager.isUnlocked.value || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !occupied.compareAndSet(false, true)) return
        val generation = SecurityManager.dataReadGeneration.value
        val active = AtomicBoolean(true)
        val secret = AtomicReference(password.toCharArray())
        val cancel = Runnable { active.set(false); secret.getAndSet(null)?.fill('\u0000') }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) cancel.run()
        }
        lifecycle.addObserver(observer)
        main.postDelayed(cancel, 15_000)
        try {
            executor.execute {
                val chars = secret.getAndSet(null)
                try {
                    if (chars != null && active.get()) manager.rehearseRecoveryProtection(String(chars), generation) {
                        check(active.get() && SessionManager.isUnlocked.value &&
                            SecurityManager.dataReadGeneration.value == generation) { "Recovery check was revoked" }
                    }
                } finally {
                    chars?.fill('\u0000'); cancel.run(); occupied.set(false)
                    main.post { main.removeCallbacks(cancel); lifecycle.removeObserver(observer) }
                }
            }
        } catch (_: Exception) {
            cancel.run(); occupied.set(false); main.removeCallbacks(cancel); lifecycle.removeObserver(observer)
        }
    }
}
