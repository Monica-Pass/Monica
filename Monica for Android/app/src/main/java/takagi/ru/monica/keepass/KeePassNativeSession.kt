package takagi.ru.monica.keepass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import java.util.UUID

internal data class KeePassNativeGroupIdentity(
    val databaseId: Long,
    val groupUuid: UUID
)

internal data class KeePassNativeEntryIdentity(
    val databaseId: Long,
    val entryUuid: UUID
)

internal data class KeePassNativeGroupNode(
    val identity: KeePassNativeGroupIdentity,
    val group: Group,
    val parentGroup: KeePassNativeGroupIdentity?,
    val legacyPath: String?,
    val depth: Int,
    val isInRecycleBin: Boolean
)

internal data class KeePassNativeEntryNode(
    val identity: KeePassNativeEntryIdentity,
    val entry: Entry,
    val parentGroup: KeePassNativeGroupIdentity,
    val legacyGroupPath: String?,
    val isInRecycleBin: Boolean
)

internal data class KeePassNativeSession(
    val databaseId: Long,
    val sourceRevision: KeePassSourceRevision,
    val database: KeePassDatabase,
    val groupsByLegacyPath: Map<String, List<KeePassNativeGroupNode>>,
    val groupsByIdentity: Map<KeePassNativeGroupIdentity, List<KeePassNativeGroupNode>>,
    val entriesByIdentity: Map<KeePassNativeEntryIdentity, List<KeePassNativeEntryNode>>,
    val groupNodes: List<KeePassNativeGroupNode>,
    val entryNodes: List<KeePassNativeEntryNode>,
    val duplicateGroupIdentities: Set<KeePassNativeGroupIdentity>,
    val duplicateEntryIdentities: Set<KeePassNativeEntryIdentity>
) {
    val revisionToken: String = sourceRevision.sha256
}

internal object KeePassNativeSessionBuilder {
    fun build(
        databaseId: Long,
        sourceRevision: KeePassSourceRevision,
        database: KeePassDatabase,
        pathKeyBuilder: (String?, String) -> String
    ): KeePassNativeSession {
        require(databaseId > 0) { "Native KeePass session requires a database id" }

        val groupsByLegacyPath = linkedMapOf<String, MutableList<KeePassNativeGroupNode>>()
        val groupsByIdentity = linkedMapOf<KeePassNativeGroupIdentity, MutableList<KeePassNativeGroupNode>>()
        val entriesByIdentity = linkedMapOf<KeePassNativeEntryIdentity, MutableList<KeePassNativeEntryNode>>()
        val groupNodes = mutableListOf<KeePassNativeGroupNode>()
        val entryNodes = mutableListOf<KeePassNativeEntryNode>()
        // The switch controls future deletions; it does not restore existing recycled items.
        val recycleBinUuid = database.content.meta.recycleBinUuid?.takeUnless { it == UUID(0, 0) }

        fun visitGroup(
            group: Group,
            parentGroup: KeePassNativeGroupIdentity?,
            legacyPath: String?,
            depth: Int,
            parentInRecycleBin: Boolean
        ) {
            val identity = KeePassNativeGroupIdentity(databaseId, group.uuid)
            val isInRecycleBin = parentInRecycleBin || recycleBinUuid == group.uuid
            val node = KeePassNativeGroupNode(
                identity = identity,
                group = group,
                parentGroup = parentGroup,
                legacyPath = legacyPath,
                depth = depth,
                isInRecycleBin = isInRecycleBin
            )
            groupNodes += node
            groupsByIdentity.getOrPut(identity) { mutableListOf() }.add(node)
            legacyPath?.let { path ->
                groupsByLegacyPath.getOrPut(path) { mutableListOf() }.add(node)
            }

            group.entries.forEach { entry ->
                val entryIdentity = KeePassNativeEntryIdentity(databaseId, entry.uuid)
                val entryNode = KeePassNativeEntryNode(
                    identity = entryIdentity,
                    entry = entry,
                    parentGroup = identity,
                    legacyGroupPath = legacyPath,
                    isInRecycleBin = isInRecycleBin
                )
                entryNodes += entryNode
                entriesByIdentity.getOrPut(entryIdentity) { mutableListOf() }.add(entryNode)
            }

            group.groups.forEach { child ->
                visitGroup(
                    group = child,
                    parentGroup = identity,
                    legacyPath = pathKeyBuilder(legacyPath, child.name),
                    depth = depth + 1,
                    parentInRecycleBin = isInRecycleBin
                )
            }
        }

        visitGroup(
            group = database.content.group,
            parentGroup = null,
            legacyPath = null,
            depth = -1,
            parentInRecycleBin = false
        )

        val immutableGroupsByIdentity = groupsByIdentity.mapValues { (_, nodes) -> nodes.toList() }
        val immutableEntriesByIdentity = entriesByIdentity.mapValues { (_, nodes) -> nodes.toList() }
        return KeePassNativeSession(
            databaseId = databaseId,
            sourceRevision = sourceRevision,
            database = database,
            groupsByLegacyPath = groupsByLegacyPath.mapValues { (_, nodes) -> nodes.toList() },
            groupsByIdentity = immutableGroupsByIdentity,
            entriesByIdentity = immutableEntriesByIdentity,
            groupNodes = groupNodes.toList(),
            entryNodes = entryNodes.toList(),
            duplicateGroupIdentities = immutableGroupsByIdentity
                .filterValues { nodes -> nodes.size > 1 }
                .keys,
            duplicateEntryIdentities = immutableEntriesByIdentity
                .filterValues { nodes -> nodes.size > 1 }
                .keys
        )
    }
}

internal class KeePassNativeSessionCache(
    private val builder: (
        databaseId: Long,
        sourceRevision: KeePassSourceRevision,
        database: KeePassDatabase
    ) -> KeePassNativeSession = { databaseId, sourceRevision, database ->
        KeePassNativeSessionBuilder.build(
            databaseId = databaseId,
            sourceRevision = sourceRevision,
            database = database,
            pathKeyBuilder = { parent, name ->
                val normalizedParent = parent?.trim().orEmpty()
                if (normalizedParent.isBlank()) name else "$normalizedParent/$name"
            }
        )
    }
) {
    private val sessions = mutableMapOf<Long, KeePassNativeSession>()

    fun deferred(
        databaseId: Long,
        sourceRevision: KeePassSourceRevision,
        database: KeePassDatabase
    ): Lazy<KeePassNativeSession> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        getOrCreate(databaseId, sourceRevision, database)
    }

    @Synchronized
    fun getOrCreate(
        databaseId: Long,
        sourceRevision: KeePassSourceRevision,
        database: KeePassDatabase
    ): KeePassNativeSession {
        sessions[databaseId]
            ?.takeIf { session -> session.sourceRevision == sourceRevision }
            ?.let { return it }

        return builder(databaseId, sourceRevision, database).also { session ->
            sessions[databaseId] = session
        }
    }

    @Synchronized
    fun invalidate(databaseId: Long) {
        sessions.remove(databaseId)
    }

    @Synchronized
    fun clear() {
        sessions.clear()
    }
}
