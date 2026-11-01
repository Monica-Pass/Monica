package takagi.ru.monica.keepass

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only scalar totals survive decoded LRU eviction; no credentials or native objects are retained. */
internal class KeePassRecordCountCache(
    private val isAppUnlocked: () -> Boolean,
    private val appLockGeneration: () -> Long,
    private val databaseLockVersion: (Long) -> Long,
) {
    internal data class Scope(
        val databaseId: Long,
        val appGeneration: Long,
        val lockVersion: Long,
        val invalidationVersion: Long,
        val authorized: Boolean,
    )
    internal data class Record(val scope: Scope, val count: Long)

    private val invalidations = mutableMapOf<Long, Long>()
    private val records = MutableStateFlow<Map<Long, Record>>(emptyMap())
    val state = records.asStateFlow()

    @Synchronized fun capture(databaseId: Long) = Scope(
        databaseId, appLockGeneration(), databaseLockVersion(databaseId),
        invalidations[databaseId] ?: 0L, isAppUnlocked(),
    )

    private fun valid(scope: Scope): Boolean = scope.authorized && isAppUnlocked() &&
        scope.appGeneration == appLockGeneration() &&
        scope.lockVersion == databaseLockVersion(scope.databaseId) &&
        scope.invalidationVersion == (invalidations[scope.databaseId] ?: 0L)

    @Synchronized fun publish(scope: Scope, count: Long): Boolean {
        require(count >= 0)
        if (!valid(scope)) return false
        records.value = records.value + (scope.databaseId to Record(scope, count))
        return true
    }

    @Synchronized fun count(databaseId: Long): Long? = records.value[databaseId]
        ?.takeIf { valid(it.scope) }?.count

    /** Explicit locks, credential/source invalidation and removal revoke late publications too. */
    @Synchronized fun invalidate(databaseId: Long) {
        invalidations[databaseId] = (invalidations[databaseId] ?: 0L) + 1L
        records.value = records.value - databaseId
    }
}
