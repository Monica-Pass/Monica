package takagi.ru.monica.data.dedup

import kotlinx.coroutines.ensureActive
import takagi.ru.monica.data.PasskeyEntry
import kotlin.coroutines.coroutineContext

/** Display retained credentials without resolving keys, comparing identities or selecting a keeper. */
internal class DedupPasskeyPlanner {
    suspend fun build(sources: List<PasskeyEntry>, sourceLabel: (PasskeyEntry) -> String): List<DedupResolvedPasskey> =
        sources.map { source ->
            coroutineContext.ensureActive()
            DedupResolvedPasskey(
                mergeKey = "retained-passkey:${source.id}", entry = source,
                sourceEntryIds = listOf(source.id), sourceLabels = listOf(sourceLabel(source)),
                preferredSourceLabel = sourceLabel(source), existsInTarget = false,
                skipReason = DedupPasskeySkipReason.PRESERVED
            )
        }
}
