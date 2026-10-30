package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.getGroup
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import java.time.Instant
import java.util.Stack
import java.util.UUID

fun KeePassDatabase.moveGroup(
    uuid: UUID,
    parentGroup: UUID
): KeePassDatabase {
    if (content.group.uuid == uuid) {
        return this
    }
    val (previousParent, item) = getGroup { it.uuid == uuid } ?: return this

    return modifyParentGroup {
        removeChildGroup(uuid)
    }.modifyGroup(parentGroup) {
        copy(
            groups = groups + item.copy(
                times = item.times
                    ?.copy(locationChanged = Instant.now())
                    ?: TimeData.create(),
                previousParentGroup = previousParent?.uuid
            )
        )
    }
}

fun KeePassDatabase.modifyParentGroup(
    block: Group.() -> Group
) = modifyContent {
    copy(group = group.modifyGroup(group.uuid, block))
}

fun KeePassDatabase.modifyGroup(
    uuid: UUID,
    block: Group.() -> Group
) = modifyContent {
    copy(group = group.modifyGroup(uuid, block))
}

fun KeePassDatabase.modifyGroups(
    block: Group.() -> Group
) = modifyContent {
    copy(group = group.modifyGroups(block))
}

fun KeePassDatabase.removeGroup(
    uuid: UUID
): KeePassDatabase {
    val now = Instant.now()
    val deletedUuids = (findGroupChildIds(uuid) + uuid)
        .map { DeletedObject(it, now) }
    return modifyContent {
        copy(
            group = group.removeChildGroup(uuid),
            deletedObjects = deletedUuids
        )
    }
}

private fun KeePassDatabase.findGroupChildIds(
    uuid: UUID
): List<UUID> {
    val uuids = mutableListOf<UUID>()

    getGroup { it.uuid == uuid }?.let { (_, foundGroup) ->
        with(Stack<Group>()) {
            uuids.addAll(foundGroup.entries.map(Entry::uuid))
            foundGroup.groups.forEach(::push)

            while (!empty()) {
                val currentGroup = pop()
                uuids.add(currentGroup.uuid)
                uuids.addAll(currentGroup.entries.map(Entry::uuid))
                currentGroup.groups.forEach(::push)
            }
        }
    }

    return uuids
}

private fun Group.removeChildGroup(
    uuid: UUID
): Group {
    return if (groups.find { it.uuid == uuid } != null) {
        copy(groups = groups.filter { it.uuid != uuid })
    } else {
        copy(groups = groups.map { it.removeChildGroup(uuid) })
    }
}

private fun Group.modifyGroup(
    uuid: UUID,
    block: Group.() -> Group
): Group {
    return if (this.uuid == uuid) {
        val now = Instant.now()
        block(this).copy(
            times = times?.copy(
                lastAccessTime = now,
                lastModificationTime = now
            ) ?: TimeData.create()
        )
    } else {
        copy(groups = groups.map { it.modifyGroup(uuid, block) })
    }
}

private fun Group.modifyGroups(
    block: Group.() -> Group
): Group {
    val newGroup = block(this)

    return when {
        newGroup != this -> {
            val now = Instant.now()
            newGroup.copy(
                times = times?.copy(
                    lastAccessTime = now,
                    lastModificationTime = now
                ) ?: TimeData.create()
            )
        }
        else -> newGroup
    }.copy(
        groups = groups.map { it.modifyGroups(block) }
    )
}
