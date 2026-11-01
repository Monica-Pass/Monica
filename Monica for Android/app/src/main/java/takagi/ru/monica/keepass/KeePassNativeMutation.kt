package takagi.ru.monica.keepass

import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import okio.ByteString
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal class KeePassNativeMutation(
    private val nowProvider: () -> Instant = Instant::now
) {
    fun initializeEntry(entry: Entry): Entry {
        if (entry.times != null) return entry
        return entry.copy(times = newTimeData(nowProvider()))
    }

    fun initializeGroup(group: Group): Group {
        if (group.times != null) return group
        return group.copy(times = newTimeData(nowProvider()))
    }

    fun editEntry(
        entry: Entry,
        meta: Meta,
        binaryPool: Map<ByteString, BinaryData>,
        transform: (Entry) -> Entry
    ): Entry {
        val transformed = transform(entry)
        // Protected values use fresh random salts when rebuilt or decoded. Compare
        // their content and protection bits while retaining every native property.
        if (KeePassLosslessTransfer.entriesEquivalent(entry, transformed)) return entry

        val now = nowProvider()
        val originalTimes = entry.times
        val updatedTimes = (transformed.times ?: originalTimes ?: newTimeData(now)).copy(
            lastAccessTime = now,
            lastModificationTime = now,
            usageCount = incrementUsageCount(originalTimes?.usageCount ?: 0)
        )
        val snapshot = entry.copy(history = emptyList())
        val maintainedHistory = maintainHistory(
            history = entry.history + snapshot,
            meta = meta,
            binaryPool = binaryPool,
            now = now
        )
        return transformed.copy(
            times = updatedTimes,
            history = maintainedHistory
        )
    }

    fun editGroup(group: Group, transform: (Group) -> Group): Group {
        val transformed = transform(group)
        if (transformed == group) return group

        val now = nowProvider()
        val updatedTimes = (transformed.times ?: group.times ?: newTimeData(now)).copy(
            lastAccessTime = now,
            lastModificationTime = now
        )
        return transformed.copy(times = updatedTimes)
    }

    fun markEntryMoved(entry: Entry, previousParentGroup: UUID? = entry.previousParentGroup): Entry {
        val now = nowProvider()
        val updatedTimes = (entry.times ?: newTimeData(now)).copy(locationChanged = now)
        return entry.copy(
            times = updatedTimes,
            previousParentGroup = previousParentGroup
        )
    }

    fun markGroupMoved(group: Group, previousParentGroup: UUID? = group.previousParentGroup): Group {
        val now = nowProvider()
        val updatedTimes = (group.times ?: newTimeData(now)).copy(locationChanged = now)
        return group.copy(
            times = updatedTimes,
            previousParentGroup = previousParentGroup
        )
    }

    fun recordPermanentDeletion(content: DatabaseContent, entry: Entry): DatabaseContent {
        return appendDeletedObjects(content, listOf(entry.uuid))
    }

    fun recordPermanentDeletion(content: DatabaseContent, group: Group): DatabaseContent {
        return appendDeletedObjects(content, collectTreeUuids(group))
    }

    private fun appendDeletedObjects(
        content: DatabaseContent,
        deletedUuids: Iterable<UUID>
    ): DatabaseContent {
        val uniqueUuids = deletedUuids.toCollection(linkedSetOf())
        if (uniqueUuids.isEmpty()) return content

        val deletionTime = nowProvider()
        return content.copy(
            deletedObjects = content.deletedObjects.filterNot { it.id in uniqueUuids } +
                uniqueUuids.map { uuid -> DeletedObject(uuid, deletionTime) }
        )
    }

    private fun collectTreeUuids(group: Group): List<UUID> {
        val uuids = mutableListOf<UUID>()
        group.uuid?.let(uuids::add)
        group.entries.forEach { uuids += it.uuid }
        group.groups.forEach { uuids += collectTreeUuids(it) }
        return uuids
    }

    private fun maintainHistory(
        history: List<Entry>,
        meta: Meta,
        binaryPool: Map<ByteString, BinaryData>,
        now: Instant
    ): List<Entry> {
        val maintained = history
            .filter { snapshot -> isWithinMaintenanceWindow(snapshot, meta, now) }
            .toMutableList()

        if (meta.historyMaxItems >= 0) {
            while (maintained.size > meta.historyMaxItems) {
                removeOldest(maintained)
            }
        }

        if (meta.historyMaxSize >= 0) {
            val binarySizes = binaryPool.mapValues { (_, binary) -> binary.rawContent.size.toLong() }
            while (maintained.isNotEmpty() && maintained.sumOf { estimateSize(it, binarySizes) } > meta.historyMaxSize) {
                removeOldest(maintained)
            }
        }
        return maintained
    }

    private fun isWithinMaintenanceWindow(snapshot: Entry, meta: Meta, now: Instant): Boolean {
        if (meta.maintenanceHistoryDays < 0) return true
        val modifiedAt = snapshot.times?.lastModificationTime ?: return true
        return ChronoUnit.DAYS.between(modifiedAt, now) < meta.maintenanceHistoryDays
    }

    private fun removeOldest(history: MutableList<Entry>) {
        if (history.isEmpty()) return
        val oldestIndex = history.indices.minByOrNull { index ->
            history[index].times?.lastModificationTime ?: Instant.MIN
        } ?: 0
        history.removeAt(oldestIndex)
    }

    private fun estimateSize(entry: Entry, binarySizes: Map<ByteString, Long>): Long {
        var size = FIXED_ENTRY_SIZE_BYTES
        entry.fields.forEach { (name, value) ->
            size += encodedLength(name)
            size += encodedLength(value.content)
        }
        entry.binaries.forEach { reference ->
            size += encodedLength(reference.name)
            size += binarySizes[reference.hash] ?: 0L
        }
        entry.autoType?.let { autoType ->
            size += encodedLength(autoType.defaultSequence.orEmpty())
            autoType.items.forEach { item ->
                size += encodedLength(item.window)
                size += encodedLength(item.keystrokeSequence)
            }
        }
        size += encodedLength(entry.overrideUrl.orEmpty())
        size += encodedLength(entry.foregroundColor.orEmpty())
        size += encodedLength(entry.backgroundColor.orEmpty())
        entry.tags.forEach { size += encodedLength(it) }
        entry.customData.forEach { (key, value) ->
            size += encodedLength(key)
            size += encodedLength(value.value)
        }
        return size
    }

    private fun encodedLength(value: String): Long {
        return value.toByteArray(StandardCharsets.UTF_8).size.toLong()
    }

    private fun incrementUsageCount(current: Int): Int {
        return if (current == Int.MAX_VALUE) Int.MAX_VALUE else current + 1
    }

    private fun newTimeData(now: Instant): TimeData {
        return TimeData(
            creationTime = now,
            lastAccessTime = now,
            lastModificationTime = now,
            locationChanged = now,
            expiryTime = now,
            expires = false,
            usageCount = 0
        )
    }

    private companion object {
        const val FIXED_ENTRY_SIZE_BYTES = 128L
    }
}
