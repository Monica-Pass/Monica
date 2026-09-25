package takagi.ru.monica.keepass

import takagi.ru.monica.utils.StringResolver

import takagi.ru.monica.R

import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import java.time.Instant
import java.util.Locale
import java.util.UUID
import okio.ByteString

internal enum class KeePassNativeDeleteMode {
    RECYCLE_BIN,
    PERMANENT
}

internal enum class KeePassNativeSortMode {
    NATURAL,
    TITLE_ASCENDING,
    TITLE_DESCENDING,
    MODIFIED_DESCENDING,
    CREATED_DESCENDING
}

internal data class KeePassNativeSortOptions(
    val mode: KeePassNativeSortMode = KeePassNativeSortMode.NATURAL,
    val recycleBinLast: Boolean = true
)

internal data class KeePassNativeSortedChildren(
    val groups: List<KeePassNativeGroupRecord>,
    val entries: List<KeePassNativeEntryRecord>
)

internal data class KeePassNativeGroupUpdate(
    val name: String? = null,
    val notes: String? = null,
    val icon: PredefinedIcon? = null,
    val customIconUuid: UUID? = null,
    val clearCustomIcon: Boolean = false,
    val expanded: Boolean? = null,
    val defaultAutoTypeSequence: String? = null,
    val enableAutoType: GroupOverride? = null,
    val enableSearching: GroupOverride? = null,
    val tags: List<String>? = null,
    val expires: Boolean? = null,
    val expiryTime: Instant? = null,
    val customIcon: KeePassNativeCustomIconPayload? = null,
)

internal data class KeePassNativeCustomIconPayload(
    val bytes: ByteArray,
    val name: String? = null,
)

internal data class KeePassNativeCustomIconPoolItem(
    val uuid: UUID,
    val bytes: ByteArray? = null,
    val name: String? = null,
    val lastModified: Instant? = null,
)

internal data class KeePassNativeCustomIconPoolUpdate(
    val upsert: List<KeePassNativeCustomIconPoolItem> = emptyList(),
    val remove: Set<UUID> = emptySet(),
)

internal data class KeePassNativeEntryPresentationUpdate(
    val predefinedIcon: PredefinedIcon? = null,
    val customIconUuid: UUID? = null,
    val clearCustomIcon: Boolean = false,
    val customIcon: KeePassNativeCustomIconPayload? = null,
    val removeCustomIconUuid: UUID? = null,
    val autoType: KeePassAutoTypePatch? = null,
    val tags: List<String>? = null,
    val expires: Boolean? = null,
    val expiryTime: Instant? = null,
)

internal object KeePassNativeManagement {
    fun createEntry(
        database: KeePassDatabase,
        targetGroupUuid: UUID,
        fields: List<Pair<String, EntryValue>>,
        entryUuid: UUID = UUID.randomUUID()
    ): KeePassDatabase {
        require(findGroup(database.content.group, targetGroupUuid) != null) {
            "KeePass target group not found: $targetGroupUuid"
        }
        require(findEntry(database.content.group, entryUuid) == null) {
            "KeePass entry UUID already exists: $entryUuid"
        }
        val normalizedFields = fields.toMutableList().apply {
            if (none { it.first == "Title" }) add(0, "Title" to EntryValue.Plain(""))
        }
        val entry = KeePassNativeMutation().initializeEntry(
            Entry(uuid = entryUuid, fields = EntryFields.of(*normalizedFields.toTypedArray()))
        )
        return database.modifyParentGroup { insertEntry(this, targetGroupUuid, entry) }
    }

    fun duplicateEntry(
        database: KeePassDatabase,
        sourceEntryUuid: UUID,
        targetGroupUuid: UUID,
        newEntryUuid: UUID = UUID.randomUUID()
    ): KeePassDatabase {
        val payload = KeePassLosslessTransfer.captureEntry(
            sourceDatabase = database,
            sourceEntryUuid = sourceEntryUuid,
            targetEntryUuid = newEntryUuid
        )
        val initialized = payload.copy(
            entry = KeePassNativeMutation().initializeEntry(
                payload.entry.copy(
                    history = emptyList(),
                    previousParentGroup = null
                )
            )
        )
        return KeePassLosslessTransfer.insertEntry(database, targetGroupUuid, initialized)
    }

    fun moveEntries(
        database: KeePassDatabase,
        entryUuids: Set<UUID>,
        targetGroupUuid: UUID
    ): KeePassDatabase {
        require(findGroup(database.content.group, targetGroupUuid) != null) {
            "KeePass target group not found: $targetGroupUuid"
        }
        var updated = database
        entryUuids.forEach { uuid ->
            val located = findEntry(updated.content.group, uuid)
                ?: throw IllegalArgumentException("KeePass entry not found: $uuid")
            if (located.parentUuid == targetGroupUuid) return@forEach
            updated = KeePassLosslessTransfer.relocateEntry(updated, uuid, targetGroupUuid)
            val moved = findEntry(updated.content.group, uuid)?.entry
                ?: throw IllegalStateException("KeePass entry missing after move: $uuid")
            val marked = KeePassNativeMutation().markEntryMoved(moved, located.parentUuid)
            updated = updated.modifyParentGroup { replaceEntry(this, uuid, marked) }
        }
        return updated
    }

    /**
     * Moves several folders as one in-memory mutation.
     *
     * The caller writes the returned database once, so a batch drag cannot
     * leave a half-moved tree when a later folder fails.  Parent/child
     * selections and descendant targets are rejected before any mutation is
     * applied.
     */
    fun moveGroups(
        database: KeePassDatabase,
        groupUuids: Set<UUID>,
        targetParentGroupUuid: UUID,
        strings: StringResolver
    ): KeePassDatabase {
        require(groupUuids.isNotEmpty()) { "KeePass folder move requires at least one source" }
        require(findGroup(database.content.group, targetParentGroupUuid) != null) {
            "KeePass target group not found: $targetParentGroupUuid"
        }
        require(database.content.group.uuid !in groupUuids) {
            "KeePass root group cannot be moved"
        }

        val sourceGroups = groupUuids.map { uuid ->
            uuid to (findGroup(database.content.group, uuid)
                ?: throw IllegalArgumentException("KeePass group not found: $uuid"))
        }.toMap()
        require(sourceGroups.keys.none { uuid ->
            containsGroupUuid(sourceGroups.getValue(uuid), targetParentGroupUuid)
        }) {
            "KeePass group cannot be moved into its descendant"
        }
        require(sourceGroups.keys.none { source ->
            sourceGroups.keys.any { other ->
                source != other && containsGroupUuid(sourceGroups.getValue(other), source)
            }
        }) {
            "KeePass parent and child folders cannot be moved together"
        }

        var updated = database
        groupUuids.sortedBy(UUID::toString).forEach { uuid ->
            val current = findGroup(updated.content.group, uuid)
                ?: throw IllegalStateException("KeePass group disappeared during batch move: $uuid")
            if (findParentGroup(updated.content.group, uuid)?.uuid == targetParentGroupUuid) {
                return@forEach
            }
            val removed = removeGroupWithValue(updated.content.group, uuid)
            val group = removed.second
                ?: throw IllegalStateException("KeePass group disappeared during batch move: $uuid")
            val inserted = addGroupToParentUuid(removed.first, targetParentGroupUuid, group, strings = strings)
            require(inserted.inserted) { "KeePass target group not found: $targetParentGroupUuid" }
            updated = updated.modifyParentGroup { inserted.root }
                .modifyParentGroup {
                    replaceGroup(this, uuid, KeePassNativeMutation().markGroupMoved(current))
                }
        }
        return updated
    }

    fun deleteEntries(
        database: KeePassDatabase,
        entryUuids: Set<UUID>,
        mode: KeePassNativeDeleteMode
    ): KeePassDatabase {
        if (entryUuids.isEmpty()) return database
        return when (mode) {
            KeePassNativeDeleteMode.PERMANENT -> entryUuids.fold(database) { current, uuid ->
                KeePassLosslessTransfer.removeEntry(current, uuid)
            }
            KeePassNativeDeleteMode.RECYCLE_BIN -> {
                val recycle = KeePassRecycleBinPolicy().ensure(database)
                moveEntries(recycle.database, entryUuids, recycle.recycleBinUuid)
            }
        }
    }

    fun renameAttachment(
        database: KeePassDatabase,
        entryUuid: UUID,
        hash: ByteString,
        newName: String
    ): KeePassDatabase = renameAttachment(
        database = database,
        entryUuid = entryUuid,
        hash = hash,
        currentName = null,
        newName = newName
    )

    fun renameAttachment(
        database: KeePassDatabase,
        entryUuid: UUID,
        hash: ByteString,
        currentName: String?,
        newName: String
    ): KeePassDatabase {
        val normalizedName = newName.trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Attachment name cannot be empty")
        val located = findEntry(database.content.group, entryUuid)
            ?: throw IllegalArgumentException("KeePass entry not found: $entryUuid")
        val targetIndex = located.entry.binaries.indexOfFirst { reference ->
            reference.hash == hash && (currentName == null || reference.name == currentName)
        }
        require(targetIndex >= 0) {
            "KeePass attachment not found: $hash"
        }
        val updatedEntry = KeePassNativeMutation().editEntry(
            entry = located.entry,
            meta = database.content.meta,
            binaryPool = database.binaries
        ) { current ->
            current.copy(
                binaries = current.binaries.mapIndexed { index, reference ->
                    if (index == targetIndex) reference.copy(name = normalizedName) else reference
                }
            )
        }
        return database.modifyParentGroup { replaceEntry(this, entryUuid, updatedEntry) }
    }

    fun addAttachment(
        database: KeePassDatabase,
        entryUuid: UUID,
        fileName: String,
        bytes: ByteArray,
        memoryProtection: Boolean = false,
        compressed: Boolean = true
    ): KeePassDatabase {
        val normalizedName = fileName.trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Attachment name cannot be empty")
        val located = findEntry(database.content.group, entryUuid)
            ?: throw IllegalArgumentException("KeePass entry not found: $entryUuid")
        val binary = BinaryData.Uncompressed(memoryProtection, bytes).let { data ->
            if (compressed) data.toCompressed() else data
        }
        val reference = BinaryReference(hash = binary.hash, name = normalizedName)
        val updatedEntry = KeePassNativeMutation().editEntry(
            entry = located.entry,
            meta = database.content.meta,
            binaryPool = database.binaries
        ) { current -> current.copy(binaries = current.binaries + reference) }
        return database
            .modifyParentGroup { replaceEntry(this, entryUuid, updatedEntry) }
            .modifyBinaries { pool ->
                if (pool.containsKey(binary.hash)) pool else pool + (binary.hash to binary)
            }
    }

    fun deleteAttachment(
        database: KeePassDatabase,
        entryUuid: UUID,
        hash: ByteString,
        currentName: String? = null
    ): KeePassDatabase {
        val located = findEntry(database.content.group, entryUuid)
            ?: throw IllegalArgumentException("KeePass entry not found: $entryUuid")
        val targetIndex = located.entry.binaries.indexOfFirst { reference ->
            reference.hash == hash && (currentName == null || reference.name == currentName)
        }
        require(targetIndex >= 0) { "KeePass attachment not found: $hash" }
        val updatedEntry = KeePassNativeMutation().editEntry(
            entry = located.entry,
            meta = database.content.meta,
            binaryPool = database.binaries
        ) { current ->
            current.copy(binaries = current.binaries.filterIndexed { index, _ -> index != targetIndex })
        }
        val withUpdatedEntry = database.modifyParentGroup { replaceEntry(this, entryUuid, updatedEntry) }
        return if (groupReferencesBinary(withUpdatedEntry.content.group, hash)) {
            withUpdatedEntry
        } else {
            withUpdatedEntry.modifyBinaries { pool -> pool - hash }
        }
    }

    fun updateGroup(
        database: KeePassDatabase,
        groupUuid: UUID,
        update: KeePassNativeGroupUpdate
    ): KeePassDatabase {
        val current = findGroup(database.content.group, groupUuid)
            ?: throw IllegalArgumentException("KeePass group not found: $groupUuid")
        val generatedIcon = update.customIcon?.let { payload ->
            KeePassCustomIconEditor.newIcon(payload.bytes, payload.name.orEmpty())
        }
        val mutation = KeePassNativeMutation()
        val initialized = mutation.initializeGroup(current)
        val updatedGroup = mutation.editGroup(initialized) { group ->
            val currentTimes = group.times
            group.copy(
                name = update.name?.trim()?.takeIf(String::isNotEmpty) ?: group.name,
                notes = update.notes ?: group.notes,
                icon = update.icon ?: group.icon,
                customIconUuid = when {
                    update.clearCustomIcon -> null
                    generatedIcon != null -> generatedIcon.first
                    update.customIconUuid != null -> update.customIconUuid
                    else -> group.customIconUuid
                },
                expanded = update.expanded ?: group.expanded,
                defaultAutoTypeSequence = update.defaultAutoTypeSequence ?: group.defaultAutoTypeSequence,
                enableAutoType = update.enableAutoType ?: group.enableAutoType,
                enableSearching = update.enableSearching ?: group.enableSearching,
                tags = update.tags ?: group.tags,
                times = currentTimes?.copy(
                    expires = update.expires ?: currentTimes.expires,
                    expiryTime = update.expiryTime ?: currentTimes.expiryTime,
                ),
            )
        }
        val updatedDatabase = database.modifyParentGroup { replaceGroup(this, groupUuid, updatedGroup) }
        return generatedIcon?.let { (uuid, icon) ->
            updatedDatabase.modifyCustomIcons { pool -> pool + (uuid to icon) }
        } ?: updatedDatabase
    }

    fun sortChildren(
        groups: List<KeePassNativeGroupRecord>,
        entries: List<KeePassNativeEntryRecord>,
        options: KeePassNativeSortOptions
    ): KeePassNativeSortedChildren {
        val groupComparator = Comparator<KeePassNativeGroupRecord> { left, right ->
            if (options.recycleBinLast && left.isInRecycleBin != right.isInRecycleBin) {
                return@Comparator if (left.isInRecycleBin) 1 else -1
            }
            compareTextByMode(left.name, right.name, left.times, right.times, options.mode)
        }
        val entryComparator = Comparator<KeePassNativeEntryRecord> { left, right ->
            compareTextByMode(left.title, right.title, left.times, right.times, options.mode)
        }
        return KeePassNativeSortedChildren(
            groups = groups.sortedWith(groupComparator),
            entries = entries.sortedWith(entryComparator)
        )
    }

    private fun compareTextByMode(
        left: String,
        right: String,
        leftTimes: app.keemobile.kotpass.models.TimeData?,
        rightTimes: app.keemobile.kotpass.models.TimeData?,
        mode: KeePassNativeSortMode
    ): Int = when (mode) {
        KeePassNativeSortMode.NATURAL -> naturalCompare(left, right)
        KeePassNativeSortMode.TITLE_ASCENDING -> left.compareTo(right, ignoreCase = true)
        KeePassNativeSortMode.TITLE_DESCENDING -> right.compareTo(left, ignoreCase = true)
        KeePassNativeSortMode.MODIFIED_DESCENDING -> compareInstantDescending(
            leftTimes?.lastModificationTime,
            rightTimes?.lastModificationTime,
            left,
            right
        )
        KeePassNativeSortMode.CREATED_DESCENDING -> compareInstantDescending(
            leftTimes?.creationTime,
            rightTimes?.creationTime,
            left,
            right
        )
    }

    private fun compareInstantDescending(
        left: Instant?,
        right: Instant?,
        leftTitle: String,
        rightTitle: String
    ): Int {
        if (left != right) return when {
            left == null -> 1
            right == null -> -1
            else -> right.compareTo(left)
        }
        return naturalCompare(leftTitle, rightTitle)
    }

    private fun naturalCompare(left: String, right: String): Int {
        val leftParts = NATURAL_PART.findAll(left.lowercase(Locale.ROOT)).map { it.value }.toList()
        val rightParts = NATURAL_PART.findAll(right.lowercase(Locale.ROOT)).map { it.value }.toList()
        val count = minOf(leftParts.size, rightParts.size)
        repeat(count) { index ->
            val leftPart = leftParts[index]
            val rightPart = rightParts[index]
            val comparison = if (leftPart.firstOrNull()?.isDigit() == true && rightPart.firstOrNull()?.isDigit() == true) {
                compareNumericParts(leftPart, rightPart)
            } else {
                leftPart.compareTo(rightPart)
            }
            if (comparison != 0) return comparison
        }
        return leftParts.size.compareTo(rightParts.size).takeIf { it != 0 }
            ?: left.compareTo(right, ignoreCase = true)
    }

    private fun compareNumericParts(left: String, right: String): Int {
        val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
        val normalizedRight = right.trimStart('0').ifEmpty { "0" }
        return normalizedLeft.length.compareTo(normalizedRight.length).takeIf { it != 0 }
            ?: normalizedLeft.compareTo(normalizedRight).takeIf { it != 0 }
            ?: left.length.compareTo(right.length)
    }

    private data class LocatedEntry(val entry: Entry, val parentUuid: UUID)

    private fun findEntry(group: Group, uuid: UUID): LocatedEntry? {
        group.entries.firstOrNull { it.uuid == uuid }?.let { return LocatedEntry(it, group.uuid) }
        group.groups.forEach { child -> findEntry(child, uuid)?.let { return it } }
        return null
    }

    private fun findGroup(group: Group, uuid: UUID): Group? {
        if (group.uuid == uuid) return group
        group.groups.forEach { child -> findGroup(child, uuid)?.let { return it } }
        return null
    }

    private fun findParentGroup(group: Group, childUuid: UUID): Group? {
        if (group.groups.any { child -> child.uuid == childUuid }) return group
        group.groups.forEach { child -> findParentGroup(child, childUuid)?.let { return it } }
        return null
    }

    private fun removeGroupWithValue(root: Group, uuid: UUID): Pair<Group, Group?> {
        val index = root.groups.indexOfFirst { child -> child.uuid == uuid }
        if (index >= 0) {
            val removed = root.groups[index]
            return root.copy(groups = root.groups.filterIndexed { i, _ -> i != index }) to removed
        }
        root.groups.forEachIndexed { indexInParent, child ->
            val result = removeGroupWithValue(child, uuid)
            if (result.second != null) {
                val groups = root.groups.toMutableList()
                groups[indexInParent] = result.first
                return root.copy(groups = groups) to result.second
            }
        }
        return root to null
    }

    private data class InsertedGroup(val root: Group, val inserted: Boolean)

    private fun addGroupToParentUuid(
        root: Group,
        parentUuid: UUID,
        groupToInsert: Group,
        strings: StringResolver
    ): InsertedGroup {
        if (root.uuid == parentUuid) {
            if (root.groups.any { child ->
                    child.uuid != groupToInsert.uuid && child.name.equals(groupToInsert.name, ignoreCase = true)
                }) {
                throw IllegalArgumentException(strings.get(R.string.cloud_message_folder_exists))
            }
            return InsertedGroup(root.copy(groups = root.groups + groupToInsert), true)
        }
        root.groups.forEachIndexed { index, child ->
            val result = addGroupToParentUuid(child, parentUuid, groupToInsert, strings = strings)
            if (result.inserted) {
                val groups = root.groups.toMutableList()
                groups[index] = result.root
                return InsertedGroup(root.copy(groups = groups), true)
            }
        }
        return InsertedGroup(root, false)
    }

    private fun containsGroupUuid(root: Group, uuid: UUID): Boolean {
        if (root.uuid == uuid) return true
        return root.groups.any { child -> containsGroupUuid(child, uuid) }
    }

    private fun groupReferencesBinary(group: Group, hash: ByteString): Boolean =
        group.entries.any { entry -> entryReferencesBinary(entry, hash) } ||
            group.groups.any { child -> groupReferencesBinary(child, hash) }

    private fun entryReferencesBinary(entry: Entry, hash: ByteString): Boolean =
        entry.binaries.any { reference -> reference.hash == hash } ||
            entry.history.any { version -> entryReferencesBinary(version, hash) }

    private fun insertEntry(group: Group, targetGroupUuid: UUID, entry: Entry): Group {
        if (group.uuid == targetGroupUuid) return group.copy(entries = group.entries + entry)
        return group.copy(groups = group.groups.map { child -> insertEntry(child, targetGroupUuid, entry) })
    }

    private fun replaceEntry(group: Group, entryUuid: UUID, replacement: Entry): Group = group.copy(
        entries = group.entries.map { entry -> if (entry.uuid == entryUuid) replacement else entry },
        groups = group.groups.map { child -> replaceEntry(child, entryUuid, replacement) }
    )

    private fun replaceGroup(group: Group, groupUuid: UUID, replacement: Group): Group {
        if (group.uuid == groupUuid) return replacement
        return group.copy(groups = group.groups.map { child -> replaceGroup(child, groupUuid, replacement) })
    }

    private val NATURAL_PART = Regex("\\d+|\\D+")
}
