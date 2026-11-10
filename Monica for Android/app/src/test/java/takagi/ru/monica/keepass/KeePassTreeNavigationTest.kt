package takagi.ru.monica.keepass

import android.app.Application
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.utils.KeePassRestoreTarget

// Exercise the actual Android Uri path codec, without starting Monica's Application.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class KeePassTreeNavigationTest {
    @Test
    fun duplicateUuidLookupKeepsFirstEntryAndPathWhileContextIndexKeepsLastGroup() {
        val duplicateEntryUuid = id(10)
        val duplicateGroupUuid = id(20)
        val firstEntry = entry(duplicateEntryUuid, "First occurrence")
        val lastEntry = entry(duplicateEntryUuid, "Last occurrence")
        val firstGroup = group(duplicateGroupUuid, "First", entries = listOf(firstEntry))
        val lastGroup = group(duplicateGroupUuid, "Last", entries = listOf(lastEntry))
        val root = group(id(1), "Root", groups = listOf(firstGroup, lastGroup))
        val database = database(root)

        assertSame(firstEntry, KeePassTreeNavigation.findEntryByUuid(root, duplicateEntryUuid))
        assertEquals("First", KeePassTreeNavigation.findGroupPathByUuid(root, null, duplicateGroupUuid))
        assertEquals(duplicateGroupUuid, KeePassTreeNavigation.findGroupUuidByPath(root, null, "Last"))
        val indexed = KeePassTreeNavigation.buildGroupTraversalContextIndex(database)
        assertEquals("Last", indexed.getValue(duplicateGroupUuid).pathKey)
        assertEquals(2, indexed.size)
        assertFalse(indexed.getValue(duplicateGroupUuid).isInRecycleBinByMeta)

        val contexts = KeePassTreeNavigation.collectEntryContexts(database).first
        assertEquals(listOf("First", "Last"), contexts.map { it.groupPath })
        assertSame(firstEntry, contexts[0].entry)
        assertSame(lastEntry, contexts[1].entry)
        assertEquals(2, KeePassTreeNavigation.countEntries(root))
    }

    @Test
    fun duplicatePathLookupKeepsFirstGroupButCollectionKeepsAllDirectEntriesInTreeOrder() {
        val rootEntry = entry(id(100), "Root entry")
        val firstEntry = entry(id(101), "First sibling")
        val secondEntry = entry(id(102), "Second sibling")
        val nestedEntry = entry(id(103), "Descendant is not a direct match")
        val firstGroup = group(
            id(110), "Duplicate", entries = listOf(firstEntry),
            groups = listOf(group(id(112), "Nested", entries = listOf(nestedEntry)))
        )
        val secondGroup = group(id(111), "Duplicate", entries = listOf(secondEntry))
        val root = group(id(1), "Root", entries = listOf(rootEntry), groups = listOf(firstGroup, secondGroup))

        assertEquals(firstGroup.uuid, KeePassTreeNavigation.findGroupUuidByPath(root, null, "Duplicate"))
        val samePathEntries = KeePassTreeNavigation.collectEntriesInGroupPath(root, "Duplicate")
        assertEquals(2, samePathEntries.size)
        assertSame(firstEntry, samePathEntries[0])
        assertSame(secondEntry, samePathEntries[1])
        assertSame(rootEntry, KeePassTreeNavigation.collectEntriesInGroupPath(root, null).single())
        assertTrue(KeePassTreeNavigation.collectEntriesInGroupPath(root, "Absent").isEmpty())
        assertEquals(
            listOf(rootEntry.uuid, firstEntry.uuid, nestedEntry.uuid, secondEntry.uuid),
            KeePassTreeNavigation.entrySequence(root).map { it.uuid }.toList()
        )
    }

    @Test
    fun rootPathRemainsNullAndEncodedSpecialSegmentsRoundTripThroughEveryLookup() {
        val rootEntry = entry(id(200), "Root entry")
        val specialEntry = entry(id(201), "Nested entry")
        val child = group(id(211), "子 空", entries = listOf(specialEntry))
        val parent = group(id(210), "  Accounts /+%  ", groups = listOf(child))
        val root = group(id(1), "Invisible root name", entries = listOf(rootEntry), groups = listOf(parent))
        val database = database(root)
        // Literal expected encoding prevents the test from merely calling the production codec twice.
        val parentPath = "Accounts%20%2F%2B%25"
        val childPath = "$parentPath/%E5%AD%90%20%E7%A9%BA"

        assertNull(KeePassTreeNavigation.findGroupPathByUuid(root, null, root.uuid))
        assertNull(KeePassTreeNavigation.findGroupUuidByPath(root, null, ""))
        assertEquals(parentPath, KeePassTreeNavigation.findGroupPathByUuid(root, null, parent.uuid))
        assertEquals(childPath, KeePassTreeNavigation.findGroupPathByUuid(root, null, child.uuid))
        assertEquals(child.uuid, KeePassTreeNavigation.findGroupUuidByPath(root, null, childPath))
        assertNull(KeePassTreeNavigation.findGroupUuidByPath(root, null, "Accounts /+%/子 空"))
        assertNull(KeePassTreeNavigation.findGroupPathByUuid(root, null, id(999)))
        assertNull(KeePassTreeNavigation.findEntryByUuid(root, id(999)))
        assertSame(specialEntry, KeePassTreeNavigation.collectEntriesInGroupPath(root, childPath).single())

        val (contexts, hasRecycleBinMeta) = KeePassTreeNavigation.collectEntryContexts(database)
        assertFalse(hasRecycleBinMeta)
        assertEquals(listOf(null, childPath), contexts.map { it.groupPath })
        assertEquals(listOf(root.uuid, child.uuid), contexts.map { it.groupUuid })
        assertSame(rootEntry, contexts[0].entry)
        assertSame(specialEntry, contexts[1].entry)
        val indexed = KeePassTreeNavigation.buildGroupTraversalContextIndex(database)
        assertNull(indexed.getValue(root.uuid).pathKey)
        assertEquals(childPath, indexed.getValue(child.uuid).pathKey)
        assertEquals("  Accounts /+%  ", parent.name)
    }

    @Test
    fun recycleUuidMarksDescendantsEvenWhenDisabledAndNamesOrZeroUuidNeverMarkOrdinaryGroups() {
        val ordinaryEntry = entry(id(300), "Not recycled")
        val recycledEntry = entry(id(301), "Recycled descendant")
        val ordinary = group(id(310), "Recycle Bin", entries = listOf(ordinaryEntry))
        val nested = group(id(312), "Nested", entries = listOf(recycledEntry))
        val recycled = group(id(311), "Renamed archive", groups = listOf(nested))
        val root = group(id(1), "Root", groups = listOf(ordinary, recycled))
        val meta = Meta(recycleBinEnabled = false, recycleBinUuid = recycled.uuid)
        val nativeDatabase = database(root, meta)

        assertEquals(recycled.uuid, KeePassTreeNavigation.resolveRecycleBinUuid(meta))
        assertNull(KeePassTreeNavigation.resolveRecycleBinUuid(meta.copy(recycleBinUuid = null)))
        assertNull(KeePassTreeNavigation.resolveRecycleBinUuid(meta.copy(recycleBinUuid = UUID(0L, 0L))))
        val (contexts, hasRecycleBinMeta) = KeePassTreeNavigation.collectEntryContexts(nativeDatabase)
        assertTrue(hasRecycleBinMeta)
        assertEquals(listOf(false, true), contexts.map { it.isInRecycleBinByMeta })
        val indexed = KeePassTreeNavigation.buildGroupTraversalContextIndex(nativeDatabase)
        assertFalse(indexed.getValue(ordinary.uuid).isInRecycleBinByMeta)
        assertTrue(indexed.getValue(recycled.uuid).isInRecycleBinByMeta)
        assertTrue(indexed.getValue(nested.uuid).isInRecycleBinByMeta)
        assertEquals(
            false,
            KeePassTreeNavigation.isGroupPathInRecycleBinByMeta(root, null, "Recycle%20Bin", recycled.uuid, false)
        )
        assertEquals(
            true,
            KeePassTreeNavigation.isGroupPathInRecycleBinByMeta(
                root, null, "Renamed%20archive/Nested", recycled.uuid, false
            )
        )
        assertNull(
            KeePassTreeNavigation.isGroupPathInRecycleBinByMeta(root, null, "Absent", recycled.uuid, false)
        )
        assertEquals(2, KeePassTreeNavigation.countEntries(root))

        val noMeta = database(root, meta.copy(recycleBinEnabled = true, recycleBinUuid = UUID(0L, 0L)))
        val (unmarked, zeroHasMeta) = KeePassTreeNavigation.collectEntryContexts(noMeta)
        assertFalse(zeroHasMeta)
        assertTrue(unmarked.none { it.isInRecycleBinByMeta })
    }

    @Test
    fun restoreUsesExistingActiveParentIncludingRootAndRejectsMissingOrRecycledPreviousParent() {
        val rootUuid = id(1)
        val activeUuid = id(410)
        val recycledUuid = id(411)
        val nestedRecycledUuid = id(412)
        val activeEntry = entry(id(400), "Still active", previousParent = nestedRecycledUuid)
        val toActive = entry(id(401), "Restore active", previousParent = activeUuid)
        val toRoot = entry(id(402), "Restore root", previousParent = rootUuid)
        val toRecycle = entry(id(403), "Refuse recycled parent", previousParent = nestedRecycledUuid)
        val toMissing = entry(id(404), "Missing parent", previousParent = id(499))
        val withoutParent = entry(id(405), "No previous parent")
        val active = group(activeUuid, "Accounts", entries = listOf(activeEntry))
        val nestedRecycled = group(nestedRecycledUuid, "Nested")
        val recycled = group(
            recycledUuid, "Archive", groups = listOf(nestedRecycled),
            entries = listOf(toActive, toRoot, toRecycle, toMissing, withoutParent)
        )
        val root = group(rootUuid, "Root", groups = listOf(active, recycled))
        val database = database(root, Meta(recycleBinUuid = recycledUuid))
        val (contexts, hasRecycleBinMeta) = KeePassTreeNavigation.collectEntryContexts(database)
        val index = KeePassTreeNavigation.buildGroupTraversalContextIndex(database)
        fun restore(entry: Entry): KeePassRestoreTarget = KeePassTreeNavigation.resolveRestoreTargetFromEntryContext(
            entryContext = contexts.single { it.entry === entry },
            hasRecycleBinMeta = hasRecycleBinMeta,
            groupContextIndex = index
        )

        assertEquals(KeePassRestoreTarget("Accounts", activeUuid.toString()), restore(activeEntry))
        assertEquals(KeePassRestoreTarget("Accounts", activeUuid.toString()), restore(toActive))
        assertEquals(KeePassRestoreTarget(null, rootUuid.toString()), restore(toRoot))
        assertEquals(KeePassRestoreTarget(null, null), restore(toRecycle))
        assertEquals(KeePassRestoreTarget(null, null), restore(toMissing))
        assertEquals(KeePassRestoreTarget(null, null), restore(withoutParent))
    }

    @Test
    fun iterativeCountAndSequenceHandleDeepTreesWithoutVisitingHistoricalEntries() {
        val depth = 6_000
        val historical = entry(id(900_000), "History must not be counted or enumerated")
        val expectedEntries = (0 until depth).map { index ->
            entry(id(10_000L + index), "Level $index").let { entry ->
                if (index == 0) entry.copy(history = listOf(historical)) else entry
            }
        }
        var root = group(id(20_000L + depth - 1), "Level ${depth - 1}", entries = listOf(expectedEntries.last()))
        for (index in depth - 2 downTo 0) {
            root = group(
                id(20_000L + index), "Level $index",
                entries = listOf(expectedEntries[index]), groups = listOf(root)
            )
        }

        // Only these two APIs promise an iterative traversal. Do not exercise recursive path/context APIs here.
        assertEquals(depth, KeePassTreeNavigation.countEntries(root))
        val actual = KeePassTreeNavigation.entrySequence(root).toList()
        assertEquals(depth, actual.size)
        expectedEntries.forEachIndexed { index, entry -> assertSame("Entry at depth $index", entry, actual[index]) }
        assertTrue(actual.none { it === historical })
        val empty = group(id(30_000), "Empty")
        assertEquals(0, KeePassTreeNavigation.countEntries(empty))
        assertFalse(KeePassTreeNavigation.entrySequence(empty).iterator().hasNext())
    }

    private fun id(value: Long): UUID = UUID(0L, value)

    private fun entry(uuid: UUID, title: String, previousParent: UUID? = null): Entry = Entry(
        uuid = uuid,
        fields = EntryFields.of("Title" to EntryValue.Plain(title)),
        previousParentGroup = previousParent,
        times = null
    )

    private fun group(
        uuid: UUID,
        name: String,
        entries: List<Entry> = emptyList(),
        groups: List<Group> = emptyList()
    ): Group = Group(uuid = uuid, name = name, entries = entries, groups = groups, times = null)

    private fun database(root: Group, meta: Meta = Meta()): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Navigation fixture",
        meta = meta,
        credentials = Credentials.from(EncryptedValue.fromString("synthetic-navigation-fixture"))
    ).modifyParentGroup { root }
}
