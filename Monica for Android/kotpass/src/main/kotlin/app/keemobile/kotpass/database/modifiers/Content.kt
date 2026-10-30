package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.constants.Defaults
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.extensions.isNullOrZero
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

inline fun KeePassDatabase.modifyContent(
    crossinline block: DatabaseContent.() -> DatabaseContent
) = when (this) {
    is KeePassDatabase.Ver3x -> copy(content = block(content))
    is KeePassDatabase.Ver4x -> copy(content = block(content))
}

inline fun KeePassDatabase.withRecycleBin(
    crossinline block: KeePassDatabase.(recycleBinUuid: UUID) -> KeePassDatabase
): KeePassDatabase {
    return if (!content.meta.recycleBinUuid.isNullOrZero()) {
        block(this, content.meta.recycleBinUuid!!)
    } else {
        val recycleBin = Group.createRecycleBin(Defaults.RecycleBinName)

        modifyContent {
            copy(
                meta = meta.copy(
                    recycleBinEnabled = true,
                    recycleBinUuid = recycleBin.uuid,
                    recycleBinChanged = Instant.now()
                )
            )
        }.modifyParentGroup {
            copy(groups = groups + recycleBin)
        }.run {
            block(recycleBin.uuid)
        }
    }
}

fun KeePassDatabase.cleanupHistory() = modifyContent {
    copy(group = group.cleanupChildHistory(meta))
}

private fun Group.cleanupChildHistory(
    meta: Meta
): Group = copy(
    groups = groups.map { it.cleanupChildHistory(meta) },
    entries = entries.map { it.cleanupHistory(meta) }
)

private fun Entry.cleanupHistory(
    meta: Meta
): Entry {
    val now = Instant.now()

    return copy(
        history = history.filter {
            if (it.times?.lastModificationTime != null) {
                val days = ChronoUnit.DAYS
                    .between(it.times.lastModificationTime, now)
                days < meta.maintenanceHistoryDays
            } else {
                true
            }
        }.takeLast(meta.historyMaxItems)
    )
}
