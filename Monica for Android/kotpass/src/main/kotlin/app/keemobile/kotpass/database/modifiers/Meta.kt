package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Meta
import java.time.Instant

/**
 * Modifies [Meta] field in [KeePassDatabase] with result of [block] lambda.
 * Timestamps are updated accordingly.
 */
inline fun KeePassDatabase.modifyMeta(
    crossinline block: Meta.() -> Meta
) = modifyContent {
    copy(meta = block(meta).updateTimestamps(meta))
}

@PublishedApi
internal fun Meta.updateTimestamps(compareWith: Meta): Meta {
    val now = Instant.now()

    return copy(
        settingsChanged = now
            .takeIf {
                recycleBinEnabled != compareWith.recycleBinEnabled ||
                    maintenanceHistoryDays != compareWith.maintenanceHistoryDays ||
                    memoryProtection != compareWith.memoryProtection ||
                    historyMaxItems != compareWith.historyMaxItems ||
                    historyMaxSize != compareWith.historyMaxSize ||
                    masterKeyChangeRec != compareWith.masterKeyChangeRec ||
                    masterKeyChangeForce != compareWith.masterKeyChangeForce
            }
            ?: compareWith.settingsChanged,
        nameChanged = now
            .takeIf { name != compareWith.name }
            ?: compareWith.nameChanged,
        descriptionChanged = now
            .takeIf { description != compareWith.description }
            ?: compareWith.descriptionChanged,
        defaultUserChanged = now
            .takeIf { defaultUser != compareWith.defaultUser }
            ?: compareWith.defaultUserChanged,
        recycleBinChanged = now
            .takeIf { recycleBinUuid != compareWith.recycleBinUuid }
            ?: compareWith.recycleBinChanged,
        entryTemplatesGroupChanged = now
            .takeIf { entryTemplatesGroup != compareWith.entryTemplatesGroup }
            ?: compareWith.entryTemplatesGroupChanged
    )
}
