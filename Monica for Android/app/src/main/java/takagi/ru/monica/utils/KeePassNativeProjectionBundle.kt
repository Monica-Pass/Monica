package takagi.ru.monica.utils

import app.keemobile.kotpass.models.Entry
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import takagi.ru.monica.keepass.KeePassNativeSession

internal data class KeePassNativeProjectionBundle(
    val session: KeePassNativeSession,
    private val deferredEntries: Lazy<List<EntryTraversalContext>>,
    private val deferredResolutionContext: Lazy<KeePassEntryResolutionContext?>,
    val hasRecycleBinMeta: Boolean,
    private val deferredGroupsIncludingRecycleBin: Lazy<List<KeePassGroupInfo>>,
    private val deferredGroupsExcludingRecycleBin: Lazy<List<KeePassGroupInfo>>
) {
    val revisionToken: String = session.revisionToken
    val entries: List<EntryTraversalContext>
        get() = deferredEntries.value
    val resolutionContext: KeePassEntryResolutionContext?
        get() = deferredResolutionContext.value

    fun groups(includeRecycleBin: Boolean): List<KeePassGroupInfo> {
        return if (includeRecycleBin) {
            deferredGroupsIncludingRecycleBin.value
        } else {
            deferredGroupsExcludingRecycleBin.value
        }
    }
}

internal object KeePassNativeProjectionBundleBuilder {
    fun build(
        session: KeePassNativeSession,
        resolutionContextBuilder: (Iterable<Entry>) -> KeePassEntryResolutionContext =
            KeePassFieldReferenceResolver::buildContext
    ): KeePassNativeProjectionBundle {
        val entries = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            session.entryNodes.map { node ->
                EntryTraversalContext(
                    entry = node.entry,
                    groupPath = node.legacyGroupPath,
                    groupUuid = node.parentGroup.groupUuid,
                    isInRecycleBinByMeta = node.isInRecycleBin
                )
            }
        }
        val resolutionContext = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            entries.value
                .asSequence()
                .map { context -> context.entry }
                .takeIf { entrySequence -> entrySequence.any(::containsReferenceToken) }
                ?.let {
                    resolutionContextBuilder(
                        entries.value.asSequence().map { context -> context.entry }.asIterable()
                    )
                }
        }
        val allGroups = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            session.groupNodes
                .asSequence()
                .filter { node -> node.depth >= 0 && node.legacyPath != null }
                .map { node ->
                    val name = node.group.name.ifBlank { "(未命名)" }
                    KeePassGroupInfo(
                        name = name,
                        path = node.legacyPath.orEmpty(),
                        uuid = node.identity.groupUuid.toString(),
                        depth = node.depth,
                        displayPath = decodeLegacyPathForProjection(node.legacyPath)
                    ) to node.isInRecycleBin
                }
                .sortedBy { (group, _) -> group.displayPath }
                .toList()
        }

        return KeePassNativeProjectionBundle(
            session = session,
            deferredEntries = entries,
            deferredResolutionContext = resolutionContext,
            hasRecycleBinMeta = session.database.content.meta.recycleBinUuid?.let { it != java.util.UUID(0, 0) } == true,
            deferredGroupsIncludingRecycleBin = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                allGroups.value.map { (group, _) -> group }
            },
            deferredGroupsExcludingRecycleBin = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                allGroups.value
                    .filterNot { (_, isInRecycleBin) -> isInRecycleBin }
                    .map { (group, _) -> group }
            }
        )
    }

    private fun decodeLegacyPathForProjection(path: String?): String {
        return path
            ?.split('/')
            ?.filter { segment -> segment.isNotBlank() }
            ?.joinToString(KEEPASS_DISPLAY_PATH_SEPARATOR) { segment ->
                URLDecoder.decode(segment, StandardCharsets.UTF_8.name())
            }
            .orEmpty()
    }

    private fun containsReferenceToken(entry: Entry): Boolean {
        return entry.fields.values.any { value ->
            runCatching { value.content.contains("{REF:", ignoreCase = true) }
                .getOrDefault(false)
        }
    }
}

internal class KeePassNativeProjectionBundleCache(
    private val builder: (KeePassNativeSession) -> KeePassNativeProjectionBundle =
        KeePassNativeProjectionBundleBuilder::build
) {
    private val bundles = mutableMapOf<Long, KeePassNativeProjectionBundle>()

    fun deferred(session: KeePassNativeSession): Lazy<KeePassNativeProjectionBundle> =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) { getOrCreate(session) }

    fun deferred(session: Lazy<KeePassNativeSession>): Lazy<KeePassNativeProjectionBundle> =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) { getOrCreate(session.value) }

    @Synchronized
    fun getOrCreate(session: KeePassNativeSession): KeePassNativeProjectionBundle {
        bundles[session.databaseId]
            ?.takeIf { bundle -> bundle.revisionToken == session.revisionToken }
            ?.let { return it }
        return builder(session).also { bundle ->
            bundles[session.databaseId] = bundle
        }
    }

    @Synchronized
    fun invalidate(databaseId: Long) {
        bundles.remove(databaseId)
    }

    @Synchronized
    fun clear() {
        bundles.clear()
    }
}
