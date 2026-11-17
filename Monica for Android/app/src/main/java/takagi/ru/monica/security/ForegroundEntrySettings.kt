package takagi.ru.monica.security

import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.withResumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import takagi.ru.monica.data.AppSettings

/** One entry's settings wait. Register before secure startup so an earlier stop also revokes it.
 * This owns no authorization or settings cache; the caller validates its current request.
 */
@MainThread
internal class ForegroundEntrySettings(private val lifecycle: Lifecycle) {
    private val revoked = CompletableDeferred<Unit>()
    private var requested = false
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) revoked.complete(Unit)
    }

    init { lifecycle.addObserver(observer) }

    suspend fun deliver(
        settings: Flow<AppSettings>,
        isCurrent: () -> Boolean,
        timeoutMillis: Long = 15_000,
        onReady: (AppSettings) -> Unit,
    ): Boolean {
        require(timeoutMillis > 0)
        if (requested) return false
        requested = true
        try {
            if (revoked.isCompleted || lifecycle.currentState == Lifecycle.State.DESTROYED || !isCurrent()) return false
            return coroutineScope {
                val loading = async {
                    withTimeoutOrNull(timeoutMillis) {
                        val snapshot = try { settings.first() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { return@withTimeoutOrNull false }
                        lifecycle.withResumed {
                            if (revoked.isCompleted || !isCurrent()) false else {
                                // No suspension between the final foreground check and admission.
                                onReady(snapshot)
                                true
                            }
                        }
                    } ?: false
                }
                try {
                    select {
                        revoked.onAwait { false }
                        loading.onAwait { it }
                    }
                } finally { loading.cancel() }
            }
        } finally { lifecycle.removeObserver(observer) }
    }
}
