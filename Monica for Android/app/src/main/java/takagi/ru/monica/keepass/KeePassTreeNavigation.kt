package takagi.ru.monica.keepass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.util.UUID
import takagi.ru.monica.utils.EntryTraversalContext
import takagi.ru.monica.utils.KeePassRestoreTarget
import takagi.ru.monica.utils.buildKeePassPathKey

/** Queries the supplied tree only; owns no sessions, caches, credentials or persisted state.
 * Traversal order and duplicate UUID/path handling follow the legacy service contracts.
 */
internal object KeePassTreeNavigation {
    fun findEntryByUuid(group: Group, uuid: UUID): Entry? {
        group.entries.firstOrNull { it.uuid == uuid }?.let { return it }
        for (child in group.groups) {
            findEntryByUuid(child, uuid)?.let { return it }
        }
        return null
    }

    fun anyEntryReferencesHash(group: Group, hash: okio.ByteString): Boolean {
        if (group.entries.any { entry -> entry.binaries.any { it.hash == hash } }) return true
        return group.groups.any { anyEntryReferencesHash(it, hash) }
    }

    fun resolveRecycleBinUuid(meta: Meta): UUID? {
        return meta.recycleBinUuid?.takeUnless { it == UUID(0, 0) }
    }

    fun entrySequence(group: Group): Sequence<Entry> = sequence {
        val pending = ArrayDeque<Group>()
        pending.addLast(group)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            yieldAll(current.entries)
            current.groups.asReversed().forEach(pending::addLast)
        }
    }

    fun buildGroupTraversalContextIndex(
        keePassDatabase: KeePassDatabase
    ): Map<UUID, GroupTraversalContext> {
        val recycleBinUuid = resolveRecycleBinUuid(keePassDatabase.content.meta)
        val result = mutableMapOf<UUID, GroupTraversalContext>()
        collectGroupTraversalContext(
            group = keePassDatabase.content.group,
            currentPathKey = null,
            recycleBinUuid = recycleBinUuid,
            parentInRecycleBin = false,
            result = result
        )
        return result
    }

    private fun collectGroupTraversalContext(
        group: Group,
        currentPathKey: String?,
        recycleBinUuid: UUID?,
        parentInRecycleBin: Boolean,
        result: MutableMap<UUID, GroupTraversalContext>
    ) {
        val inRecycleBin = parentInRecycleBin || (recycleBinUuid != null && group.uuid == recycleBinUuid)
        result[group.uuid] = GroupTraversalContext(
            pathKey = currentPathKey,
            groupUuid = group.uuid,
            isInRecycleBinByMeta = inRecycleBin
        )
        group.groups.forEach { child ->
            val childPathKey = buildKeePassPathKey(currentPathKey, child.name)
            collectGroupTraversalContext(
                group = child,
                currentPathKey = childPathKey,
                recycleBinUuid = recycleBinUuid,
                parentInRecycleBin = inRecycleBin,
                result = result
            )
        }
    }

    fun resolveRestoreTargetFromEntryContext(
        entryContext: EntryTraversalContext,
        hasRecycleBinMeta: Boolean,
        groupContextIndex: Map<UUID, GroupTraversalContext>
    ): KeePassRestoreTarget {
        val inRecycleBin = entryContext.isInRecycleBinByMeta
        if (!inRecycleBin) {
            return KeePassRestoreTarget(
                groupPath = entryContext.groupPath,
                groupUuid = entryContext.groupUuid?.toString()
            )
        }

        val previousParentUuid = entryContext.entry.previousParentGroup
        if (previousParentUuid != null) {
            val previousParentContext = groupContextIndex[previousParentUuid]
            if (previousParentContext != null) {
                val previousInRecycleBin = previousParentContext.isInRecycleBinByMeta
                if (!previousInRecycleBin) {
                    return KeePassRestoreTarget(
                        groupPath = previousParentContext.pathKey,
                        groupUuid = previousParentContext.groupUuid.toString()
                    )
                }
            }
        }

        return KeePassRestoreTarget(groupPath = null, groupUuid = null)
    }

    fun isGroupPathInRecycleBinByMeta(
        group: Group,
        currentPathKey: String?,
        targetPathKey: String,
        recycleBinUuid: UUID,
        parentInRecycleBin: Boolean
    ): Boolean? {
        val inRecycle = parentInRecycleBin || group.uuid == recycleBinUuid
        if (currentPathKey == targetPathKey) {
            return inRecycle
        }
        group.groups.forEach { child ->
            val childPathKey = buildKeePassPathKey(currentPathKey, child.name)
            val childResult = isGroupPathInRecycleBinByMeta(
                group = child,
                currentPathKey = childPathKey,
                targetPathKey = targetPathKey,
                recycleBinUuid = recycleBinUuid,
                parentInRecycleBin = inRecycle
            )
            if (childResult != null) {
                return childResult
            }
        }
        return null
    }

    fun collectEntryContexts(
        keePassDatabase: KeePassDatabase
    ): Pair<List<EntryTraversalContext>, Boolean> {
        val recycleBinUuid = resolveRecycleBinUuid(keePassDatabase.content.meta)
        val hasRecycleBinMeta = recycleBinUuid != null
        val entries = mutableListOf<EntryTraversalContext>()
        collectEntriesWithGroupPath(
            group = keePassDatabase.content.group,
            currentPathKey = null,
            recycleBinUuid = recycleBinUuid,
            parentInRecycleBin = false,
            entries = entries
        )
        return entries to hasRecycleBinMeta
    }

    private fun collectEntriesWithGroupPath(
        group: Group,
        currentPathKey: String?,
        recycleBinUuid: UUID?,
        parentInRecycleBin: Boolean,
        entries: MutableList<EntryTraversalContext>
    ) {
        val inRecycleBin = parentInRecycleBin || (recycleBinUuid != null && group.uuid == recycleBinUuid)
        group.entries.forEach { entry ->
            entries.add(
                EntryTraversalContext(
                    entry = entry,
                    groupPath = currentPathKey,
                    groupUuid = group.uuid,
                    isInRecycleBinByMeta = inRecycleBin
                )
            )
        }
        group.groups.forEach { child ->
            val nextPathKey = buildKeePassPathKey(currentPathKey, child.name)
            collectEntriesWithGroupPath(
                group = child,
                currentPathKey = nextPathKey,
                recycleBinUuid = recycleBinUuid,
                parentInRecycleBin = inRecycleBin,
                entries = entries
            )
        }
    }

    fun findGroupPathByUuid(
        group: Group,
        currentPathKey: String?,
        targetUuid: UUID
    ): String? {
        if (group.uuid == targetUuid) {
            return currentPathKey
        }
        group.groups.forEach { child ->
            val childPathKey = buildKeePassPathKey(currentPathKey, child.name)
            val childResult = findGroupPathByUuid(
                group = child,
                currentPathKey = childPathKey,
                targetUuid = targetUuid
            )
            if (childResult != null) {
                return childResult
            }
        }
        return null
    }

    fun findGroupUuidByPath(
        group: Group,
        currentPathKey: String?,
        targetPathKey: String
    ): UUID? {
        if (currentPathKey == targetPathKey) {
            return group.uuid
        }
        group.groups.forEach { child ->
            val childPathKey = buildKeePassPathKey(currentPathKey, child.name)
            val childResult = findGroupUuidByPath(
                group = child,
                currentPathKey = childPathKey,
                targetPathKey = targetPathKey
            )
            if (childResult != null) return childResult
        }
        return null
    }

    fun countEntries(group: Group): Int {
        var count = 0
        val pending = ArrayDeque<Group>()
        pending.addLast(group)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            count += current.entries.size
            current.groups.forEach(pending::addLast)
        }
        return count
    }

    fun collectEntriesInGroupPath(
        rootGroup: Group,
        groupPath: String?
    ): List<Entry> {
        val collected = mutableListOf<Entry>()
        fun walk(group: Group, currentPath: String?) {
            if (currentPath == groupPath) {
                collected += group.entries
            }
            group.groups.forEach { child ->
                val nextPath = buildKeePassPathKey(currentPath, child.name)
                walk(child, nextPath)
            }
        }
        walk(rootGroup, null)
        return collected
    }
}

internal data class GroupTraversalContext(
    val pathKey: String?,
    val groupUuid: UUID,
    val isInRecycleBinByMeta: Boolean
)
