package takagi.ru.monica.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/** Cancelling a UI wait never releases the slot of a still-running native key operation. */
internal object BoundedSecureDataRead {
    private val permit = Semaphore(1)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "monica-secure-data-read").apply { isDaemon = true }
    }

    internal class ReadScope(
        private val isCurrent: () -> Boolean,
        private val result: CompletableDeferred<*>,
    ) {
        fun ensureActive() {
            check(result.isActive && isCurrent()) { "Secure read was revoked" }
        }
    }

    suspend fun <T : Any> read(
        manager: SecurityManager,
        generation: Long,
        timeoutMillis: Long = 15_000,
        operation: ReadScope.() -> T,
    ): T? = readChecked(timeoutMillis, { manager.isDataReadCurrent(generation) }, operation)

    internal suspend fun <T : Any> readChecked(
        timeoutMillis: Long = 15_000,
        isCurrent: () -> Boolean,
        operation: ReadScope.() -> T,
    ): T? {
        require(timeoutMillis > 0)
        val result = CompletableDeferred<T?>()
        var acquired = false
        var dispatched = false
        try {
            return withTimeoutOrNull(timeoutMillis) {
                permit.acquire()
                acquired = true
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return@withTimeoutOrNull null
                executor.execute {
                    try {
                        val scope = ReadScope(isCurrent, result)
                        scope.ensureActive()
                        val value = scope.operation()
                        scope.ensureActive()
                        result.complete(value)
                    } catch (_: Exception) {
                        result.complete(null)
                    } finally {
                        permit.release()
                    }
                }
                dispatched = true
                val value = result.await()
                currentCoroutineContext().ensureActive()
                if (isCurrent()) value else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            result.cancel()
            if (acquired && !dispatched) permit.release()
        }
    }
}
