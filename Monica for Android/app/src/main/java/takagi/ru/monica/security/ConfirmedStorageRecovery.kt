package takagi.ru.monica.security

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/** Explicit UI confirmation precedes this operation. Native calls never run on the UI thread. */
internal object ConfirmedStorageRecovery {
    private class Work(context: Context, var password: String?) {
        val guard = StartupStorageGuard(context)
        val result = CompletableDeferred<Boolean>()
        var finished = false
        var released = false
    }
    private val lock = Any()
    private var running: Work? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Monica-confirmed-recovery").apply { isDaemon = true }
    }
    internal val inProgress: Boolean get() = synchronized(lock) { running != null }

    suspend fun recover(context: Context, password: String, timeoutMillis: Long = 15_000): Boolean {
        require(timeoutMillis > 0)
        currentCoroutineContext().ensureActive()
        val work = synchronized(lock) {
            // A timed-out native operation retains its slot until it actually exits.
            if (running != null) return false
            Work(context.applicationContext, password).also { running = it }
        }
        var accepted = false
        try {
            executor.execute {
                try {
                    work.guard.checkActive()
                    val recovery = LocalVaultRecovery(work.guard.context)
                    work.result.complete(recovery.recover(checkNotNull(work.password)) { publish ->
                        work.guard.publishWhileActive(publish)
                    })
                } catch (_: Exception) {
                    work.result.complete(false)
                } finally {
                    synchronized(lock) {
                        work.password = null
                        work.finished = true
                        if (work.released && running === work) running = null
                    }
                }
            }
            val result = withTimeoutOrNull(timeoutMillis) { work.result.await() } ?: return false
            currentCoroutineContext().ensureActive()
            accepted = true
            return result
        } finally {
            if (!accepted) work.guard.abandon()
            synchronized(lock) {
                work.released = true
                if (work.finished && running === work) running = null
            }
        }
    }
}
