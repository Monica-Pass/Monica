package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class KeePassNativeMutationTest {
    @Test fun rewritingIdenticalProtectedFieldsKeepsOriginalHistoryAndTimes() {
        val original = entry(title = "Unchanged", history = listOf(entry(title = "Retained")))
        val updated = KeePassNativeMutation().editEntry(original, Meta(), emptyMap()) { current ->
            current.copy(fields = EntryFields.of(*current.fields.entries.reversed().map { (name, value) ->
                name to if (value is EntryValue.Encrypted) EntryValue.Encrypted(EncryptedValue.fromString(value.content)) else value
            }.toTypedArray()))
        }
        org.junit.Assert.assertSame(original, updated)
    }

    @Test fun replayingAnIdenticalProtectedFieldPatchDoesNotAddHistory() {
        val original = entry(title = "Unchanged", history = listOf(entry(title = "Retained")))
        var db = database(entries = listOf(original))
        val patch = KeePassChangeSet(databaseId = DATABASE_ID, target = KeePassChangeTarget.PASSWORD,
            operation = KeePassChangeOperation.FIELD_PATCH, entryUuid = original.uuid.toString(), baseFingerprint = "base",
            fieldPatch = KeePassFieldChangePatch(managedScope = KeePassManagedFieldScope.EXPLICIT_ONLY,
                replacementFields = listOf(KeePassFieldChange("Password", "secret", true)),
                baseFields = listOf(KeePassFieldBaseValue("Password", "secret", true))))
        repeat(4) { db = KeePassChangeSetApplier().apply(db, patch).updatedDatabase }
        val actual = findEntry(db.content.group, original.uuid)!!
        assertEquals(original.times, actual.times)
        assertEquals(original.history.size, actual.history.size)
    }

    @Test fun changingProtectionIsStillARealEditEvenWithIdenticalText() {
        val original = entry(title = "Protected")
        val updated = KeePassNativeMutation().editEntry(original, Meta(), emptyMap()) { current ->
            current.copy(fields = current.fields + ("Password" to EntryValue.Plain("secret")))
        }
        assertEquals(1, updated.history.size)
        assertTrue(updated.history.single().fields.getValue("Password") is EntryValue.Encrypted)
        assertTrue(updated.fields.getValue("Password") is EntryValue.Plain)
    }

    @Test
    fun fieldEditCreatesFlatHistoryAndTouchesKeePassTimes() {
        val entryUuid = UUID.randomUUID()
        val originalTimes = timeData(
            modifiedAt = Instant.parse("2026-08-17T01:00:00Z"),
            locationChangedAt = Instant.parse("2026-08-17T00:30:00Z"),
            usageCount = 7
        )
        val entry = entry(
            uuid = entryUuid,
            title = "Before",
            times = originalTimes,
            history = listOf(entry(title = "Older"))
        )
        val database = database(
            meta = Meta(
                generator = "Monica native mutation test",
                name = "History fixture",
                historyMaxItems = 10,
                historyMaxSize = -1
            ),
            entries = listOf(entry)
        )
        val beforeMutation = Instant.now()

        val updatedDatabase = KeePassChangeSetApplier().apply(
            database,
            titlePatch(entryUuid, oldTitle = "Before", newTitle = "After")
        ).updatedDatabase
        val afterMutation = Instant.now()
        val updated = findEntry(updatedDatabase.content.group, entryUuid)!!

        assertEquals("After", updated.fields.getValue("Title").content)
        assertEquals(2, updated.history.size)
        val snapshot = updated.history.last()
        assertEquals("Before", snapshot.fields.getValue("Title").content)
        assertTrue("A history snapshot must not recursively contain history", snapshot.history.isEmpty())
        assertEquals(originalTimes, snapshot.times)

        val updatedTimes = assertNotNull(updated.times).let { updated.times!! }
        val lastAccessTime = updatedTimes.lastAccessTime!!
        val lastModificationTime = updatedTimes.lastModificationTime!!
        assertFalse(lastAccessTime.isBefore(beforeMutation))
        assertFalse(lastModificationTime.isBefore(beforeMutation))
        assertFalse(afterMutation.isBefore(lastAccessTime))
        assertFalse(afterMutation.isBefore(lastModificationTime))
        assertEquals(originalTimes.creationTime, updatedTimes.creationTime)
        assertEquals(originalTimes.locationChanged, updatedTimes.locationChanged)
        assertEquals(8, updatedTimes.usageCount)
    }

    @Test
    fun historyItemLimitKeepsNewestSnapshots() {
        val entryUuid = UUID.randomUUID()
        val database = database(
            meta = Meta(
                generator = "Monica native mutation test",
                name = "Item limit fixture",
                historyMaxItems = 1,
                historyMaxSize = -1
            ),
            entries = listOf(
                entry(
                    uuid = entryUuid,
                    title = "Current",
                    history = listOf(entry(title = "Oldest"), entry(title = "Newer"))
                )
            )
        )

        val updated = KeePassChangeSetApplier().apply(
            database,
            titlePatch(entryUuid, oldTitle = "Current", newTitle = "Edited")
        ).updatedDatabase.let { findEntry(it.content.group, entryUuid)!! }

        assertEquals(1, updated.history.size)
        assertEquals("Current", updated.history.single().fields.getValue("Title").content)
    }

    @Test
    fun zeroHistorySizeRemovesAllSnapshots() {
        val entryUuid = UUID.randomUUID()
        val database = database(
            meta = Meta(
                generator = "Monica native mutation test",
                name = "Size limit fixture",
                historyMaxItems = -1,
                historyMaxSize = 0
            ),
            entries = listOf(
                entry(
                    uuid = entryUuid,
                    title = "Current",
                    history = listOf(entry(title = "Older"))
                )
            )
        )

        val updated = KeePassChangeSetApplier().apply(
            database,
            titlePatch(entryUuid, oldTitle = "Current", newTitle = "Edited")
        ).updatedDatabase.let { findEntry(it.content.group, entryUuid)!! }

        assertTrue(updated.history.isEmpty())
    }

    @Test
    fun unlimitedHistoryKeepsExistingSnapshotsAndNewSnapshot() {
        val entryUuid = UUID.randomUUID()
        val database = database(
            meta = Meta(
                generator = "Monica native mutation test",
                name = "Unlimited fixture",
                historyMaxItems = -1,
                historyMaxSize = -1
            ),
            entries = listOf(
                entry(
                    uuid = entryUuid,
                    title = "Current",
                    history = listOf(entry(title = "Oldest"), entry(title = "Newer"))
                )
            )
        )

        val updated = KeePassChangeSetApplier().apply(
            database,
            titlePatch(entryUuid, oldTitle = "Current", newTitle = "Edited")
        ).updatedDatabase.let { findEntry(it.content.group, entryUuid)!! }

        assertEquals(listOf("Oldest", "Newer", "Current"), updated.history.map(::titleOf))
    }

    @Test
    fun movingEntryUpdatesLocationChangedWithoutCreatingHistory() {
        val sourceUuid = UUID.randomUUID()
        val targetUuid = UUID.randomUUID()
        val entryUuid = UUID.randomUUID()
        val previousLocationChanged = Instant.parse("2026-08-17T00:30:00Z")
        val original = entry(
            uuid = entryUuid,
            title = "Move me",
            times = timeData(
                modifiedAt = Instant.parse("2026-08-17T01:00:00Z"),
                locationChangedAt = previousLocationChanged,
                usageCount = 2
            )
        )
        val database = database(
            groups = listOf(
                Group(uuid = sourceUuid, name = "Source", entries = listOf(original)),
                Group(uuid = targetUuid, name = "Target")
            )
        )
        val beforeMove = Instant.now()

        val movedDatabase = KeePassChangeSetApplier().apply(
            database,
            KeePassChangeSet(
                changeId = "move-entry",
                databaseId = DATABASE_ID,
                target = KeePassChangeTarget.PASSWORD,
                operation = KeePassChangeOperation.MOVE_ENTRY,
                entryUuid = entryUuid.toString(),
                baseFingerprint = "base",
                structurePatch = KeePassStructureChangePatch(
                    sourceGroupUuid = sourceUuid.toString(),
                    targetGroupUuid = targetUuid.toString()
                )
            )
        ).updatedDatabase
        val moved = findEntry(movedDatabase.content.group, entryUuid)!!
        val locationChanged = moved.times!!.locationChanged!!

        assertTrue(locationChanged >= beforeMove)
        assertTrue(locationChanged > previousLocationChanged)
        assertTrue(moved.history.isEmpty())
        assertEquals("Move me", titleOf(moved))
    }

    @Test
    fun permanentEntryDeleteWritesDeletedObjectTombstone() {
        val entryUuid = UUID.randomUUID()
        val database = database(entries = listOf(entry(uuid = entryUuid, title = "Delete me")))
        val beforeDelete = Instant.now()

        val updated = KeePassChangeSetApplier().apply(
            database,
            permanentDeleteChange(entryUuid)
        ).updatedDatabase
        val afterDelete = Instant.now()

        assertEquals(null, findEntry(updated.content.group, entryUuid))
        val tombstone = updated.content.deletedObjects.single { it.id == entryUuid }
        assertFalse(tombstone.deletionTime.isBefore(beforeDelete))
        assertFalse(afterDelete.isBefore(tombstone.deletionTime))
    }

    @Test
    fun permanentGroupDeleteWritesTombstonesForWholeTree() {
        val parentUuid = UUID.randomUUID()
        val childUuid = UUID.randomUUID()
        val parentEntryUuid = UUID.randomUUID()
        val childEntryUuid = UUID.randomUUID()
        val database = database(
            groups = listOf(
                Group(
                    uuid = parentUuid,
                    name = "Parent",
                    entries = listOf(entry(uuid = parentEntryUuid, title = "Parent entry")),
                    groups = listOf(
                        Group(
                            uuid = childUuid,
                            name = "Child",
                            entries = listOf(entry(uuid = childEntryUuid, title = "Child entry"))
                        )
                    )
                )
            )
        )

        val updated = KeePassChangeSetApplier().apply(
            database,
            KeePassChangeSet(
                changeId = "delete-group",
                databaseId = DATABASE_ID,
                target = KeePassChangeTarget.GROUP,
                operation = KeePassChangeOperation.DELETE_GROUP,
                entryUuid = null,
                baseFingerprint = null,
                structurePatch = KeePassStructureChangePatch(sourceGroupUuid = parentUuid.toString())
            )
        ).updatedDatabase

        assertEquals(null, findGroup(updated.content.group, parentUuid))
        assertEquals(
            setOf(parentUuid, childUuid, parentEntryUuid, childEntryUuid),
            updated.content.deletedObjects.map { it.id }.toSet()
        )
    }

    private fun database(
        meta: Meta = Meta(generator = "Monica native mutation test", name = "Fixture"),
        entries: List<Entry> = emptyList(),
        groups: List<Group> = emptyList()
    ): KeePassDatabase {
        return KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = meta,
            credentials = Credentials.from(EncryptedValue.fromString("fixture-password"))
        ).modifyParentGroup {
            copy(entries = entries, groups = groups)
        }
    }

    private fun entry(
        uuid: UUID = UUID.randomUUID(),
        title: String,
        times: TimeData = timeData(),
        history: List<Entry> = emptyList()
    ): Entry {
        return Entry(
            uuid = uuid,
            fields = EntryFields.of(
                "Title" to EntryValue.Plain(title),
                "UserName" to EntryValue.Plain("octocat"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("secret")),
                "External Field" to EntryValue.Plain("must stay")
            ),
            times = times,
            history = history,
            tags = listOf("work")
        )
    }

    private fun timeData(
        modifiedAt: Instant = Instant.parse("2026-08-17T01:00:00Z"),
        locationChangedAt: Instant = Instant.parse("2026-08-17T00:30:00Z"),
        usageCount: Int = 1
    ): TimeData {
        return TimeData(
            creationTime = Instant.parse("2026-08-16T00:00:00Z"),
            lastAccessTime = modifiedAt.minusSeconds(60),
            lastModificationTime = modifiedAt,
            locationChanged = locationChangedAt,
            expiryTime = Instant.parse("2030-01-01T00:00:00Z"),
            expires = false,
            usageCount = usageCount
        )
    }

    private fun titlePatch(entryUuid: UUID, oldTitle: String, newTitle: String): KeePassChangeSet {
        return KeePassChangeSet(
            changeId = "edit-title-$newTitle",
            databaseId = DATABASE_ID,
            target = KeePassChangeTarget.PASSWORD,
            operation = KeePassChangeOperation.FIELD_PATCH,
            entryUuid = entryUuid.toString(),
            baseFingerprint = "base",
            fieldPatch = KeePassFieldChangePatch(
                managedScope = KeePassManagedFieldScope.EXPLICIT_ONLY,
                replacementFields = listOf(KeePassFieldChange("Title", newTitle)),
                baseFields = listOf(KeePassFieldBaseValue("Title", oldTitle))
            )
        )
    }

    private fun permanentDeleteChange(entryUuid: UUID): KeePassChangeSet {
        return KeePassChangeSet(
            changeId = "permanent-delete",
            databaseId = DATABASE_ID,
            target = KeePassChangeTarget.PASSWORD,
            operation = KeePassChangeOperation.PERMANENT_DELETE,
            entryUuid = entryUuid.toString(),
            baseFingerprint = "base",
            structurePatch = KeePassStructureChangePatch()
        )
    }

    private fun titleOf(entry: Entry): String = entry.fields.getValue("Title").content

    private fun findEntry(group: Group, uuid: UUID): Entry? {
        group.entries.firstOrNull { it.uuid == uuid }?.let { return it }
        group.groups.forEach { child ->
            findEntry(child, uuid)?.let { return it }
        }
        return null
    }

    private fun findGroup(group: Group, uuid: UUID): Group? {
        if (group.uuid == uuid) return group
        group.groups.forEach { child ->
            findGroup(child, uuid)?.let { return it }
        }
        return null
    }

    private companion object {
        const val DATABASE_ID = 42L
    }
}
