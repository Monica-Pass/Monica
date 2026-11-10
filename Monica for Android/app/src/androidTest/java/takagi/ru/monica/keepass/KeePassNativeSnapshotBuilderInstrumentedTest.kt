package takagi.ru.monica.keepass

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android is needed only for the existing Base64 codec; fixtures stay entirely in memory. */
@RunWith(AndroidJUnit4::class)
class KeePassNativeSnapshotBuilderInstrumentedTest {
    @Test
    fun entryAndGroupCaptureReplayHistoryOnlyCompressedAttachmentAndIcon() {
        val fixture = Fixture()
        val originalRaw = fixture.historical.rawContent.copyOf()
        val entryChange = fixture.entryChange(fixture.source)
        val groupChange = fixture.groupChange(fixture.source)
        val entryPatch = requireNotNull(entryChange.entryPatch)
        val groupPatch = requireNotNull(groupChange.groupTreePatch)
        for (pool in listOf(entryPatch.binaryPool, groupPatch.binaryPool)) {
            assertEquals(listOf(fixture.current.hash.hex(), fixture.historical.hash.hex()), pool.map { it.hash })
            val historical = pool.single { it.hash == fixture.historical.hash.hex() }
            assertTrue(historical.compressed)
            assertTrue(historical.protected)
            assertTrue(historical.rawStorageContent)
            assertArrayEquals(originalRaw, Base64.decode(historical.contentBase64, Base64.NO_WRAP))
        }
        for (icons in listOf(entryPatch.customIconPool, groupPatch.customIconPool)) {
            assertEquals(listOf(fixture.iconId.toString()), icons.map { it.uuid })
            assertArrayEquals(fixture.iconBytes, Base64.decode(icons.single().dataBase64, Base64.NO_WRAP))
        }
        for (change in listOf(entryChange, groupChange)) {
            val decoded = KeePassChangeSetCodec.decode(KeePassChangeSetCodec.encode(change))
            val replayed = KeePassChangeSetApplier().apply(fixture.destination, decoded).updatedDatabase
            val restored = if (change.operation == KeePassChangeOperation.CREATE_ENTRY) {
                replayed.content.group.entries.single()
            } else {
                val restoredGroup = replayed.content.group.groups.single()
                assertEquals(fixture.group.uuid, restoredGroup.uuid)
                assertEquals(fixture.group.name, restoredGroup.name)
                restoredGroup.entries.single()
            }
            assertEquals(fixture.entry.uuid, restored.uuid)
            assertEquals("current.bin", restored.binaries.single().name)
            assertNull(restored.customIconUuid)
            val history = restored.history.single()
            assertEquals(fixture.entry.history.single().uuid, history.uuid)
            assertEquals("{REF:P@I:00000000000000000000000000000001}", history.fields.getValue("Foreign protected reference").content)
            assertTrue(history.fields.getValue("Foreign protected reference") is EntryValue.Encrypted)
            assertEquals("history-only.gz", history.binaries.single().name)
            assertEquals(fixture.historical.hash, history.binaries.single().hash)
            assertEquals(fixture.iconId, history.customIconUuid)
            val restoredBinary = replayed.binaries.getValue(fixture.historical.hash)
            assertTrue(restoredBinary is BinaryData.Compressed)
            assertTrue(restoredBinary.memoryProtection)
            assertArrayEquals(originalRaw, restoredBinary.rawContent)
            assertArrayEquals(fixture.historyBytes, restoredBinary.getContent())
            val restoredIcon = replayed.content.meta.customIcons.getValue(fixture.iconId)
            assertArrayEquals(fixture.iconBytes, restoredIcon.data)
            assertEquals("History-only icon", restoredIcon.name)
            assertEquals(fixture.iconModified, restoredIcon.lastModified)
        }
        assertArrayEquals("Capturing must not rewrite the source resource", originalRaw, fixture.historical.rawContent)
        assertEquals(1, fixture.source.content.group.groups.single().entries.single().history.size)
    }

    @Test
    fun bothCaptureEntrypointsRejectMissingHistoryResources() {
        val fixture = Fixture()
        val missingBinary = fixture.source.modifyBinaries { it - fixture.historical.hash }
        val missingIcon = fixture.source.modifyCustomIcons { it - fixture.iconId }
        val captures: List<(KeePassDatabase) -> KeePassChangeSet> = listOf(fixture::entryChange, fixture::groupChange)
        for (capture in captures) {
            val binaryFailure = runCatching { capture(missingBinary) }.exceptionOrNull()
            assertTrue("Missing history binary must reject the whole capture", binaryFailure is IllegalStateException)
            assertTrue(binaryFailure!!.message.orEmpty().contains("missing"))
            assertTrue(binaryFailure.message.orEmpty().contains(fixture.historical.hash.hex()))
            val iconFailure = runCatching { capture(missingIcon) }.exceptionOrNull()
            assertTrue("Missing history icon must reject the whole capture", iconFailure is IllegalStateException)
            assertTrue(iconFailure!!.message.orEmpty().contains("missing"))
            assertTrue(iconFailure.message.orEmpty().contains(fixture.iconId.toString()))
        }
        assertTrue(fixture.destination.content.group.entries.isEmpty())
        assertTrue(fixture.destination.content.group.groups.isEmpty())
        assertTrue(fixture.source.binaries.containsKey(fixture.historical.hash))
        assertTrue(fixture.source.content.meta.customIcons.containsKey(fixture.iconId))
    }

    private class Fixture {
        val current = BinaryData.Uncompressed(false, "current attachment".toByteArray())
        val historyBytes = "historical compressed resource with repeat repeat repeat".toByteArray()
        val historical = BinaryData.Uncompressed(true, historyBytes).toCompressed()
        val iconId = UUID.randomUUID()
        val iconBytes = byteArrayOf(2, 3, 5, 7)
        val iconModified = Instant.parse("2026-11-10T00:00:00Z")
        val entry = Entry(uuid = UUID.randomUUID(), fields = EntryFields.of("Title" to EntryValue.Plain("Current")),
            binaries = listOf(BinaryReference(current.hash, "current.bin")),
            history = listOf(Entry(uuid = UUID.randomUUID(), customIconUuid = iconId,
                fields = EntryFields.of("Title" to EntryValue.Plain("History"),
                    "Foreign protected reference" to EntryValue.Encrypted(EncryptedValue.fromString("{REF:P@I:00000000000000000000000000000001}"))),
                binaries = listOf(BinaryReference(historical.hash, "history-only.gz")))))
        val group = Group(uuid = UUID.randomUUID(), name = "Captured tree", entries = listOf(entry))
        val destination = database()
        val source = database().modifyParentGroup { copy(groups = listOf(group)) }
            .modifyBinaries { mapOf(current.hash to current, historical.hash to historical) }
            .modifyCustomIcons { mapOf(iconId to CustomIcon(iconBytes, "History-only icon", iconModified)) }

        fun entryChange(database: KeePassDatabase): KeePassChangeSet = requireNotNull(
            KeePassNativeSnapshotBuilder.buildCreateEntryChangeSet(database, 42L, KeePassChangeTarget.UNKNOWN_ENTRY,
                entry, targetGroupPath = null, targetGroupUuidOverride = destination.content.group.uuid))

        fun groupChange(database: KeePassDatabase): KeePassChangeSet = KeePassChangeSet(
            databaseId = 42L,
            target = KeePassChangeTarget.GROUP,
            operation = KeePassChangeOperation.CREATE_GROUP_TREE,
            entryUuid = null,
            baseFingerprint = null,
            structurePatch = KeePassStructureChangePatch(targetGroupUuid = destination.content.group.uuid.toString(), groupName = group.name),
            groupTreePatch = KeePassNativeSnapshotBuilder.buildGroupTreeChangePatch(database, group, group.uuid, destination.content.group.uuid),
        )

        private fun database(): KeePassDatabase = KeePassDatabase.Ver4x.create("Root", Meta(name = "Memory-only capture fixture"),
            Credentials.from(EncryptedValue.fromString("synthetic capture password")))
    }
}
