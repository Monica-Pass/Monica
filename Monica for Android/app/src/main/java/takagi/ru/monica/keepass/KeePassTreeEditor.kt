package takagi.ru.monica.keepass

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import java.util.UUID
import takagi.ru.monica.R
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.utils.KeePassGroupInfo
import takagi.ru.monica.utils.buildKeePassPathKey
import takagi.ru.monica.utils.decodeKeePassPathForDisplay
import takagi.ru.monica.utils.decodeKeePassPathSegments

/** Edits already-loaded trees in memory. The caller retains locking, validation and file writes.
 * Reuses the service's initializer and resolves error text only when the original branch does.
 */
internal class KeePassTreeEditor(
    private val strings: StringResolver,
    private val nativeMutation: KeePassNativeMutation,
) {
    data class ExtractedGroupResult(
        val updatedGroup: Group,
        val removedGroup: Group?
    )

    data class InsertedGroupResult(
        val updatedGroup: Group,
        val groupInfo: KeePassGroupInfo
    )

    fun updateEntryInGroup(group: Group, targetUuid: UUID, replacement: Entry): Group {
        val newEntries = group.entries.map { if (it.uuid == targetUuid) replacement else it }
        val newGroups = group.groups.map { updateEntryInGroup(it, targetUuid, replacement) }
        return group.copy(entries = newEntries, groups = newGroups)
    }

    fun removeEntryInGroup(
        group: Group,
        matcher: (Entry) -> Boolean
    ): Pair<Group, Int> {
        val filteredEntries = group.entries.filterNot { matcher(it) }
        var removedCount = group.entries.size - filteredEntries.size
        val newGroups = group.groups.map { sub ->
            val result = removeEntryInGroup(sub, matcher)
            removedCount += result.second
            result.first
        }
        return group.copy(entries = filteredEntries, groups = newGroups) to removedCount
    }

    fun addEntryToGroupPath(
        rootGroup: Group,
        groupPath: String?,
        entry: Entry
    ): Group {
        val segments = decodeKeePassPathSegments(groupPath)
        if (segments.isEmpty()) {
            return rootGroup.copy(entries = rootGroup.entries + entry)
        }
        return addEntryToGroupPathSegments(rootGroup, segments, entry)
    }

    private fun addEntryToGroupPathSegments(
        group: Group,
        segments: List<String>,
        entry: Entry
    ): Group {
        if (segments.isEmpty()) {
            return group.copy(entries = group.entries + entry)
        }

        val childName = segments.first()
        val childIndex = group.groups.indexOfFirst { it.name == childName }
        val childGroup = if (childIndex >= 0) {
            group.groups[childIndex]
        } else {
            nativeMutation.initializeGroup(Group(uuid = UUID.randomUUID(), name = childName))
        }

        val updatedChild = addEntryToGroupPathSegments(
            group = childGroup,
            segments = segments.drop(1),
            entry = entry
        )

        val updatedGroups = group.groups.toMutableList()
        if (childIndex >= 0) {
            updatedGroups[childIndex] = updatedChild
        } else {
            updatedGroups.add(updatedChild)
        }
        return group.copy(groups = updatedGroups)
    }

    fun addGroupToPath(
        group: Group,
        parentSegments: List<String>,
        newGroupName: String,
        currentPathKey: String
    ): Pair<Group, KeePassGroupInfo> {
        if (parentSegments.isEmpty()) {
            val existing = group.groups.firstOrNull { it.name.equals(newGroupName, ignoreCase = true) }
            if (existing != null) {
                val existingPath = buildKeePassPathKey(currentPathKey, existing.name)
                return group to KeePassGroupInfo(
                    name = existing.name,
                    path = existingPath,
                    uuid = existing.uuid.toString(),
                    depth = decodeKeePassPathSegments(existingPath).size - 1,
                    displayPath = decodeKeePassPathForDisplay(existingPath)
                )
            }

            val newGroup = nativeMutation.initializeGroup(
                Group(
                    uuid = UUID.randomUUID(),
                    name = newGroupName
                )
            )
            val newPath = buildKeePassPathKey(currentPathKey, newGroupName)
            return group.copy(groups = group.groups + newGroup) to KeePassGroupInfo(
                name = newGroupName,
                path = newPath,
                uuid = newGroup.uuid.toString(),
                depth = decodeKeePassPathSegments(newPath).size - 1,
                displayPath = decodeKeePassPathForDisplay(newPath)
            )
        }

        val nextSegment = parentSegments.first()
        val childIndex = group.groups.indexOfFirst { it.name == nextSegment }
        if (childIndex < 0) {
            throw IllegalArgumentException(strings.get(R.string.keepass_error_parent_group, nextSegment))
        }

        val child = group.groups[childIndex]
        val childPath = buildKeePassPathKey(currentPathKey, child.name)
        val childResult = addGroupToPath(
            group = child,
            parentSegments = parentSegments.drop(1),
            newGroupName = newGroupName,
            currentPathKey = childPath
        )

        val updatedGroups = group.groups.toMutableList()
        updatedGroups[childIndex] = childResult.first
        return group.copy(groups = updatedGroups) to childResult.second
    }

    fun renameGroupByPath(
        group: Group,
        pathSegments: List<String>,
        newName: String,
        currentPathKey: String
    ): Pair<Group, KeePassGroupInfo> {
        val targetName = pathSegments.firstOrNull()
            ?: throw IllegalArgumentException(strings.get(R.string.keepass_error_group_path))
        val childIndex = group.groups.indexOfFirst { it.name == targetName }
        if (childIndex < 0) {
            throw IllegalArgumentException(strings.get(R.string.keepass_error_group_missing, targetName))
        }

        val child = group.groups[childIndex]
        val updatedGroups = group.groups.toMutableList()

        return if (pathSegments.size == 1) {
            val conflict = group.groups.anyIndexed { index, sibling ->
                index != childIndex && sibling.name.equals(newName, ignoreCase = true)
            }
            if (conflict) {
                throw IllegalArgumentException(strings.get(R.string.keepass_error_group_exists))
            }

            val renamed = child.copy(name = newName)
            updatedGroups[childIndex] = renamed
            val newPath = buildKeePassPathKey(currentPathKey, newName)
            group.copy(groups = updatedGroups) to KeePassGroupInfo(
                name = newName,
                path = newPath,
                uuid = renamed.uuid.toString(),
                depth = decodeKeePassPathSegments(newPath).size - 1,
                displayPath = decodeKeePassPathForDisplay(newPath)
            )
        } else {
            val childPath = buildKeePassPathKey(currentPathKey, child.name)
            val childResult = renameGroupByPath(
                group = child,
                pathSegments = pathSegments.drop(1),
                newName = newName,
                currentPathKey = childPath
            )
            updatedGroups[childIndex] = childResult.first
            group.copy(groups = updatedGroups) to childResult.second
        }
    }

    fun removeGroupByPath(
        group: Group,
        pathSegments: List<String>
    ): Pair<Group, Boolean> {
        val targetName = pathSegments.firstOrNull() ?: return group to false
        val childIndex = group.groups.indexOfFirst { it.name == targetName }
        if (childIndex < 0) return group to false

        val updatedGroups = group.groups.toMutableList()
        return if (pathSegments.size == 1) {
            updatedGroups.removeAt(childIndex)
            group.copy(groups = updatedGroups) to true
        } else {
            val child = group.groups[childIndex]
            val childResult = removeGroupByPath(child, pathSegments.drop(1))
            if (!childResult.second) return group to false
            updatedGroups[childIndex] = childResult.first
            group.copy(groups = updatedGroups) to true
        }
    }

    fun extractGroupByPath(
        group: Group,
        pathSegments: List<String>
    ): ExtractedGroupResult {
        val targetName = pathSegments.firstOrNull() ?: return ExtractedGroupResult(group, null)
        val childIndex = group.groups.indexOfFirst { it.name == targetName }
        if (childIndex < 0) return ExtractedGroupResult(group, null)

        val updatedGroups = group.groups.toMutableList()
        return if (pathSegments.size == 1) {
            val removedGroup = updatedGroups.removeAt(childIndex)
            ExtractedGroupResult(
                updatedGroup = group.copy(groups = updatedGroups),
                removedGroup = removedGroup
            )
        } else {
            val child = group.groups[childIndex]
            val childResult = extractGroupByPath(child, pathSegments.drop(1))
            if (childResult.removedGroup == null) {
                return ExtractedGroupResult(group, null)
            }
            updatedGroups[childIndex] = childResult.updatedGroup
            ExtractedGroupResult(
                updatedGroup = group.copy(groups = updatedGroups),
                removedGroup = childResult.removedGroup
            )
        }
    }

    fun buildGroupInfoFromPath(
        group: Group,
        pathKey: String
    ): KeePassGroupInfo {
        return KeePassGroupInfo(
            name = group.name,
            path = pathKey,
            uuid = group.uuid.toString(),
            depth = decodeKeePassPathSegments(pathKey).size - 1,
            displayPath = decodeKeePassPathForDisplay(pathKey)
        )
    }

    fun insertGroupToPath(
        group: Group,
        parentSegments: List<String>,
        groupToInsert: Group,
        currentPathKey: String
    ): InsertedGroupResult {
        if (parentSegments.isEmpty()) {
            val conflict = group.groups.any { sibling ->
                sibling.uuid != groupToInsert.uuid && sibling.name.equals(groupToInsert.name, ignoreCase = true)
            }
            if (conflict) {
                throw IllegalArgumentException(strings.get(R.string.keepass_error_group_exists))
            }
            val newPath = buildKeePassPathKey(currentPathKey, groupToInsert.name)
            return InsertedGroupResult(
                updatedGroup = group.copy(groups = group.groups + groupToInsert),
                groupInfo = KeePassGroupInfo(
                    name = groupToInsert.name,
                    path = newPath,
                    uuid = groupToInsert.uuid.toString(),
                    depth = decodeKeePassPathSegments(newPath).size - 1,
                    displayPath = decodeKeePassPathForDisplay(newPath)
                )
            )
        }

        val nextSegment = parentSegments.first()
        val childIndex = group.groups.indexOfFirst { it.name == nextSegment }
        if (childIndex < 0) {
            throw IllegalArgumentException(strings.get(R.string.keepass_error_parent_group, nextSegment))
        }

        val child = group.groups[childIndex]
        val childPath = buildKeePassPathKey(currentPathKey, child.name)
        val childResult = insertGroupToPath(
            group = child,
            parentSegments = parentSegments.drop(1),
            groupToInsert = groupToInsert,
            currentPathKey = childPath
        )
        val updatedGroups = group.groups.toMutableList()
        updatedGroups[childIndex] = childResult.updatedGroup
        return InsertedGroupResult(
            updatedGroup = group.copy(groups = updatedGroups),
            groupInfo = childResult.groupInfo
        )
    }

    private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
        for (index in indices) {
            if (predicate(index, this[index])) return true
        }
        return false
    }
}
