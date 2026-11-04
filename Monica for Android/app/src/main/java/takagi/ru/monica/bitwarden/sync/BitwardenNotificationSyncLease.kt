package takagi.ru.monica.bitwarden.sync

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Propagates only notification ownership through the existing coordinator; holds no credentials. */
internal class BitwardenNotificationSyncLease(private val stillValid: () -> Boolean) : AbstractCoroutineContextElement(Key) {
    fun isCurrent(): Boolean = runCatching(stillValid).getOrDefault(false)
    companion object Key : CoroutineContext.Key<BitwardenNotificationSyncLease>
}
