package takagi.ru.monica.keepass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.*
import java.time.Instant
import java.util.UUID

/** UUID-based two-way synchronization. Import/copy and cross-vault moves use separate code. */
internal object KeePassDatabaseSynchronizer {
    data class Result(val database: KeePassDatabase, val equalTimeConflicts: Int)
    private data class Tree(val groups: Map<UUID, Group>, val entries: Map<UUID, Entry>, val parents: Map<UUID, UUID>)
    private fun modified(times: TimeData?) = times?.lastModificationTime ?: Instant.MIN
    private fun moved(times: TimeData?) = times?.locationChanged ?: modified(times)

    fun merge(target: KeePassDatabase, source: KeePassDatabase): Result {
        val left = index(target.content.group)
        val sourceRoot = source.content.group.uuid
        val root = target.content.group.uuid
        val icons = target.content.meta.customIcons.toMutableMap()
        val iconMap = source.content.meta.customIcons.mapValues { (uuid, icon) ->
            val existing = icons[uuid]
            when {
                existing == null -> uuid.also { icons[it] = icon }
                sameIcon(existing, icon) -> uuid
                else -> icons.entries.firstOrNull { sameIcon(it.value, icon) }?.key
                    ?: UUID.randomUUID().also { icons[it] = icon }
            }
        }
        fun mapParent(uuid: UUID?) = if (uuid == sourceRoot) root else uuid
        fun mapEntry(entry: Entry): Entry = entry.copy(
            customIconUuid = entry.customIconUuid?.let { iconMap[it] ?: it },
            previousParentGroup = mapParent(entry.previousParentGroup),
            history = entry.history.map(::mapEntry))
        fun mapGroup(group: Group): Group = group.copy(
            uuid = if (group.uuid == sourceRoot) root else group.uuid,
            customIconUuid = group.customIconUuid?.let { iconMap[it] ?: it },
            previousParentGroup = mapParent(group.previousParentGroup),
            groups = group.groups.map(::mapGroup), entries = group.entries.map(::mapEntry))
        val right = index(mapGroup(source.content.group))
        val groupIds = (left.groups.keys + right.groups.keys).toSet()
        val entryIds = (left.entries.keys + right.entries.keys).toSet()
        require(groupIds.intersect(entryIds).isEmpty()) { "KeePass object UUID type conflict" }
        val deleted = (target.content.deletedObjects + source.content.deletedObjects)
            .groupBy { it.id }.mapValues { (_, values) -> values.maxOf { it.deletionTime } }.toMutableMap()
        deleted.remove(root)
        var conflicts = 0
        val entries = entryIds.associateWith { uuid ->
            val a = left.entries[uuid]
            val b = right.entries[uuid]
            when {
                a == null -> requireNotNull(b)
                b == null -> a
                else -> {
                    val sourceNewer = modified(b.times) > modified(a.times)
                    val current = if (sourceNewer) b else a
                    val previous = if (sourceNewer) a else b
                    val same = sameEntry(current.copy(history = emptyList()), previous.copy(history = emptyList()))
                    if (!same && modified(a.times) == modified(b.times)) conflicts++
                    val history = mutableListOf<Entry>()
                    (a.history + b.history + if (same) emptyList() else listOf(previous.copy(history = emptyList())))
                        .sortedBy { modified(it.times) }.forEach { version ->
                            val flat = version.copy(history = emptyList())
                            if (!sameEntry(flat, current.copy(history = emptyList())) && history.none { sameEntry(it, flat) }) history += flat
                        }
                    current.copy(history = history)
                }
            }
        }.toMutableMap()
        val groups = groupIds.associateWith { uuid ->
            val a = left.groups[uuid]
            val b = right.groups[uuid]
            val selected = when { a == null -> requireNotNull(b); b == null -> a
                modified(b.times) > modified(a.times) -> b; else -> a }
            val movement = when { a == null -> requireNotNull(b); b == null -> a
                moved(b.times) > moved(a.times) -> b; else -> a }
            selected.copy(groups = emptyList(), entries = emptyList(),
                times = selected.times?.copy(locationChanged = movement.times?.locationChanged),
                previousParentGroup = movement.previousParentGroup,
                customData = mergeCustomData(a?.customData.orEmpty(), b?.customData.orEmpty()))
        }.toMutableMap()
        val parents = (groupIds + entryIds).filter { it != root }.associateWith { uuid ->
            val a = left.parents[uuid]
            val b = right.parents[uuid]
            val at = left.entries[uuid]?.times ?: left.groups[uuid]?.times
            val bt = right.entries[uuid]?.times ?: right.groups[uuid]?.times
            when { a == null -> requireNotNull(b); b == null -> a; moved(bt) > moved(at) -> b; else -> a }
        }.toMutableMap()
        // Keep the winning move with the content before evaluating deletions and future merges.
        entries.replaceAll { uuid, entry ->
            val a = left.entries[uuid]
            val b = right.entries[uuid]
            val movement = when { a == null -> requireNotNull(b); b == null -> a
                moved(b.times) > moved(a.times) -> b; else -> a }
            entry.copy(times = entry.times?.copy(locationChanged = movement.times?.locationChanged),
                previousParentGroup = movement.previousParentGroup)
        }
        val leftBin = target.content.meta.recycleBinUuid?.takeIf { it != root && it in groups }
        val rightBin = source.content.meta.recycleBinUuid?.takeIf { it != root && it in groups }
        // Preserve the source bin UUID and its descendants, but keep them inside the active bin.
        if (leftBin != null && rightBin != null && leftBin != rightBin) parents[rightBin] = leftBin
        fun ancestorDeletion(uuid: UUID): Instant? {
            var parent = parents[uuid]
            var latest = deleted[uuid]
            val visited = mutableSetOf(uuid)
            while (parent != null) {
                require(visited.add(parent)) { "KeePass merge would create a group cycle" }
                require(parent in groups) { "KeePass merge parent is missing" }
                deleted[parent]?.let { latest = latest?.let { old -> maxOf(old, it) } ?: it }
                parent = parents[parent]
            }
            return latest
        }
        groups.keys.forEach { ancestorDeletion(it) }
        entries.entries.removeAll { (uuid, entry) ->
            val deletion = ancestorDeletion(uuid)
            val remove = deletion != null && maxOf(modified(entry.times), moved(entry.times)) <= deletion
            if (remove) deleted[uuid] = requireNotNull(deletion)
            remove
        }
        val childGroups = groupIds.groupBy { parents[it] }
        val childEntries = entryIds.groupBy { parents[it] }
        // Deleted groups only disappear after their surviving (possibly newer) descendants are considered.
        fun rebuild(uuid: UUID): Group? {
            val group = groups.getValue(uuid)
            val children = childGroups[uuid].orEmpty().mapNotNull(::rebuild)
            val childrenEntries = childEntries[uuid].orEmpty().mapNotNull(entries::get)
            val deletion = ancestorDeletion(uuid)
            if (uuid != root && deletion != null && maxOf(modified(group.times), moved(group.times)) <= deletion &&
                children.isEmpty() && childrenEntries.isEmpty()) {
                deleted[uuid] = deletion
                return null
            }
            return group.copy(groups = children, entries = childrenEntries)
        }
        val mergedRoot = requireNotNull(rebuild(root))
        val live = index(mergedRoot)
        val tombstones = deleted.filterKeys { it !in live.entries && it !in live.groups }
            .map { (uuid, time) -> DeletedObject(uuid, time) }
        val tm = target.content.meta
        val sm = if (sourceRoot == root) source.content.meta else tm
        val chosenBin = (leftBin ?: rightBin)?.takeIf { it in live.groups }
        val meta = tm.copy(customIcons = icons, customData = mergeCustomData(tm.customData, sm.customData),
            name = if (newer(sm.nameChanged, tm.nameChanged)) sm.name else tm.name,
            nameChanged = latest(sm.nameChanged, tm.nameChanged),
            description = if (newer(sm.descriptionChanged, tm.descriptionChanged)) sm.description else tm.description,
            descriptionChanged = latest(sm.descriptionChanged, tm.descriptionChanged),
            defaultUser = if (newer(sm.defaultUserChanged, tm.defaultUserChanged)) sm.defaultUser else tm.defaultUser,
            defaultUserChanged = latest(sm.defaultUserChanged, tm.defaultUserChanged),
            recycleBinUuid = chosenBin,
            recycleBinEnabled = if (leftBin == null && rightBin != null) sm.recycleBinEnabled else tm.recycleBinEnabled,
            recycleBinChanged = if (leftBin == null) sm.recycleBinChanged else tm.recycleBinChanged)
        val result = target.modifyContent { copy(group = mergedRoot, deletedObjects = tombstones, meta = meta) }
            .modifyBinaries { pool -> buildMap {
                putAll(pool)
                source.binaries.forEach { (hash, binary) ->
                    val old = pool[hash]
                    require(old == null || (old.javaClass == binary.javaClass && old.rawContent.contentEquals(binary.rawContent))) { "KeePass binary hash collision" }
                    put(hash, old?.let { if (binary.memoryProtection && !it.memoryProtection) binary else it } ?: binary)
                }
            } }
        return Result(result, conflicts)
    }

    private fun index(root: Group): Tree {
        val groups = linkedMapOf<UUID, Group>()
        val entries = linkedMapOf<UUID, Entry>()
        val parents = mutableMapOf<UUID, UUID>()
        fun visit(group: Group) {
            require(groups.put(group.uuid, group) == null) { "Ambiguous KeePass group UUID" }
            group.entries.forEach {
                require(entries.put(it.uuid, it) == null) { "Ambiguous KeePass entry UUID" }
                parents[it.uuid] = group.uuid
            }
            group.groups.forEach { parents[it.uuid] = group.uuid; visit(it) }
        }
        visit(root)
        require(groups.keys.intersect(entries.keys).isEmpty()) { "KeePass object UUID type conflict" }
        return Tree(groups, entries, parents)
    }

    private fun sameEntry(a: Entry, b: Entry) = KeePassLosslessTransfer.entriesEquivalent(a, b)
    private fun sameIcon(a: CustomIcon, b: CustomIcon) = a.data.contentEquals(b.data) && a.name == b.name && a.lastModified == b.lastModified
    private fun newer(a: Instant?, b: Instant?) = (a ?: Instant.MIN) > (b ?: Instant.MIN)
    private fun latest(a: Instant?, b: Instant?): Instant? = listOfNotNull(a, b).maxOrNull()
    private fun mergeCustomData(a: Map<String, CustomDataValue>, b: Map<String, CustomDataValue>): Map<String, CustomDataValue> =
        (a.keys + b.keys).associateWith { key ->
            val left = a[key]; val right = b[key]
            when {
                left == null -> requireNotNull(right)
                right == null || left.value == right.value -> left
                newer(right.lastModified, left.lastModified) -> right
                newer(left.lastModified, right.lastModified) -> left
                else -> error("KeePass plugin metadata has conflicting values without distinct timestamps")
            }
        }
}
