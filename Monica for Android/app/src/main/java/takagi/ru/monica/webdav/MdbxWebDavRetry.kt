package takagi.ru.monica.webdav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Resume a failed MDBX transport operation, preserving its immutable/conditional write intent. */
internal class MdbxWebDavRetry(
    private val host: String,
    private val remainingBackoff: (String) -> Long = { WebDavBackoffState.suggestedWaitMillis(it) },
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {
    // One instance belongs to one transport/sync. Many files must not each
    // restart the retry allowance and repeatedly hit the same throttled host.
    private var retries = 0
    private var waited = 0L
    suspend fun <T> run(operation: suspend () -> T): T {
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val classified = WebDavErrorClassifier.classify(error)
                if (classified.kind != WebDavErrorKind.RateLimited || retries >= 2) throw error
                val backoff = maxOf(classified.retryAfterMillis ?: 0L, remainingBackoff(host), 1_000L)
                // Never shorten Retry-After or park an interactive sync for an unbounded period.
                if (backoff > 30_000L - waited) throw error
                wait(backoff)
                waited += backoff
                retries++
            }
        }
    }
}
