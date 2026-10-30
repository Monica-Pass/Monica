package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Group
import okio.ByteString
import java.util.Stack

val KeePassDatabase.binaries
    get() = when (this) {
        is KeePassDatabase.Ver3x -> content.meta.binaries
        is KeePassDatabase.Ver4x -> innerHeader.binaries
    }

inline fun KeePassDatabase.modifyBinaries(
    crossinline block: (Map<ByteString, BinaryData>) -> Map<ByteString, BinaryData>
): KeePassDatabase = when (this) {
    is KeePassDatabase.Ver3x -> modifyMeta {
        copy(binaries = block(content.meta.binaries))
    }
    is KeePassDatabase.Ver4x -> copy(
        innerHeader = innerHeader.copy(
            binaries = block(innerHeader.binaries)
        )
    )
}

fun KeePassDatabase.removeUnusedBinaries(): KeePassDatabase {
    val cleanupList = binaries.keys.toMutableSet()

    with(Stack<Group>()) {
        push(content.group)

        while (!empty()) {
            val group = pop()
            val combinedEntries = group
                .entries
                .flatMap { entry -> entry.history + entry }

            for (entry in combinedEntries) {
                cleanupList.removeAll(
                    entry.binaries.map(BinaryReference::hash)
                )
            }
            group.groups.forEach(::push)
        }
    }

    return modifyBinaries { it - cleanupList }
}
