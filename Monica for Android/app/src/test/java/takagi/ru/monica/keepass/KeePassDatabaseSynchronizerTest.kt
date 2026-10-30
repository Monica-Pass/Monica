package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.modifiers.*
import app.keemobile.kotpass.models.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class KeePassDatabaseSynchronizerTest {
    private val epoch = Instant.parse("2026-01-01T00:00:00Z")
    private fun times(second: Long) = TimeData.create().copy(lastModificationTime=epoch.plusSeconds(second), locationChanged=epoch.plusSeconds(second))
    private fun entry(second: Long, value: String, uuid: UUID = UUID.randomUUID()) = Entry(uuid=uuid,times=times(second),fields=EntryFields.of(
        "Title" to EntryValue.Plain(value), "Password" to EntryValue.Encrypted(EncryptedValue.fromString("  secret  "))))
    private fun database(entries: List<Entry> = emptyList()) = KeePassDatabase.Ver4x.create("Root",Meta(),Credentials.from(EncryptedValue.fromString("fixture")))
        .modifyParentGroup { copy(entries=entries, times=times(0)) }
    private fun merge(a: KeePassDatabase,b: KeePassDatabase) = KeePassDatabaseSynchronizer.merge(a,b).database
    private fun values(db: KeePassDatabase): List<Entry> = buildList { db.content.group.traverse { if (it is Entry) add(it) } }

    @Test fun sameUuidUsesNewerContentAndPreservesHistoryWithoutDuplicates() {
        val old=entry(1,"old"); val next=old.copy(fields=EntryFields.of("Title" to EntryValue.Plain("new"), "Password" to old.fields.getValue("Password")),times=times(2))
        val a=database(listOf(old));val b=a.modifyParentGroup {copy(entries=listOf(next))}
        val result=merge(a,b);val current=values(result).single()
        assertEquals(old.uuid,current.uuid);assertEquals("new",current.fields["Title"]!!.content)
        assertTrue(current.history.any { it.fields["Title"]!!.content=="old" })
        assertEquals(1,values(merge(result,b)).single().history.size)
        assertSame(a.credentials,result.credentials);assertEquals(a.header,result.header)
        assertEquals("old",values(a).single().fields["Title"]!!.content)
    }
    @Test fun sourceDeletionDoesNotResurrectAnOldEntry() {
        val old=entry(1,"old"); val a=database(listOf(old))
        val b=a.modifyContent {copy(group=group.copy(entries=emptyList()),deletedObjects=listOf(DeletedObject(old.uuid,epoch.plusSeconds(2))))}
        assertTrue(values(merge(a,b)).isEmpty());assertTrue(values(merge(b,a)).isEmpty())
    }
    @Test fun modificationAfterDeletionSurvives() {
        val current=entry(3,"restored"); val a=database(listOf(current))
        val b=a.modifyContent {copy(group=group.copy(entries=emptyList()),deletedObjects=listOf(DeletedObject(current.uuid,epoch.plusSeconds(2))))}
        val result=merge(a,b);assertEquals(current.uuid,values(result).single().uuid)
        assertFalse(result.content.deletedObjects.any {it.id==current.uuid})
    }
    @Test fun equalTimeDivergencePreservesTargetAndSourceInHistory() {
        val aEntry=entry(1,"left");val bEntry=aEntry.copy(fields=EntryFields.of("Title" to EntryValue.Plain("right")))
        val a=database(listOf(aEntry));val b=a.modifyParentGroup {copy(entries=listOf(bEntry))}
        val result=KeePassDatabaseSynchronizer.merge(a,b)
        assertEquals(1,result.equalTimeConflicts);assertEquals("left",values(result.database).single().fields["Title"]!!.content)
        assertEquals("right",values(result.database).single().history.single().fields["Title"]!!.content)
    }
    @Test fun groupMoveAndEntryEditAreMergedIndependently() {
        val e=entry(1,"old");val folder=Group(UUID.randomUUID(),"Destination",times=times(1))
        val a=database(listOf(e)).modifyParentGroup {copy(groups=listOf(folder))}
        val edited=e.copy(fields=EntryFields.of("Title" to EntryValue.Plain("edited")),times=e.times!!.copy(lastModificationTime=epoch.plusSeconds(5)))
        val moved=e.copy(times=e.times!!.copy(locationChanged=epoch.plusSeconds(8)))
        val left=a.modifyParentGroup {copy(entries=listOf(edited))}
        val right=a.modifyParentGroup {copy(entries=emptyList(),groups=listOf(folder.copy(entries=listOf(moved))))}
        val result=merge(left,right)
        assertTrue(result.content.group.entries.isEmpty());assertEquals("edited",result.content.group.groups.single().entries.single().fields["Title"]!!.content)
    }
    @Test fun deletedGroupRetainsANewerDescendant() {
        val newer=entry(4,"new");val older=entry(1,"old")
        val folder=Group(UUID.randomUUID(),"Folder",times=times(1),entries=listOf(newer,older))
        val a=database().modifyParentGroup {copy(groups=listOf(folder))}
        val b=a.modifyContent {copy(group=group.copy(groups=emptyList()),deletedObjects=listOf(DeletedObject(folder.uuid,epoch.plusSeconds(3))))}
        val result=merge(a,b);assertEquals(newer.uuid,values(result).single().uuid);assertEquals(folder.uuid,result.content.group.groups.single().uuid)
    }
    @Test fun winningGroupMovePersistsAcrossLaterMergeWithOlderLocation() {
        val folder=Group(UUID.randomUUID(),"Folder",times=times(1))
        val destination=Group(UUID.randomUUID(),"Destination",times=times(1))
        val a=database().modifyParentGroup {copy(groups=listOf(folder.copy(name="Edited",times=times(5)),destination))}
        val moved=folder.copy(times=times(1).copy(locationChanged=epoch.plusSeconds(8)),previousParentGroup=a.content.group.uuid)
        val b=a.modifyParentGroup {copy(groups=listOf(destination.copy(groups=listOf(moved))))}
        val first=merge(a,b)
        val merged=first.content.group.groups.single().groups.single()
        assertEquals("Edited",merged.name)
        assertEquals(epoch.plusSeconds(8),merged.times!!.locationChanged)
        assertEquals(a.content.group.uuid,merged.previousParentGroup)
        val stale=a.modifyParentGroup {copy(groups=listOf(folder.copy(times=times(6)),destination))}
        assertEquals(folder.uuid,merge(first,stale).content.group.groups.single().groups.single().uuid)
    }
    @Test fun moveAfterDeletionSurvivesEvenWhenContentComesFromOtherSide() {
        val e=entry(5,"edited");val a=database(listOf(e)).modifyContent {copy(deletedObjects=listOf(DeletedObject(e.uuid,epoch.plusSeconds(7))))}
        val moved=e.copy(times=times(1).copy(locationChanged=epoch.plusSeconds(8)))
        val b=a.modifyContent {copy(group=group.copy(entries=listOf(moved)),deletedObjects=emptyList())}
        val current=values(merge(a,b)).single()
        assertEquals("edited",current.fields["Title"]!!.content)
        assertEquals(epoch.plusSeconds(8),current.times!!.locationChanged)
    }
    @Test(expected=IllegalArgumentException::class) fun equalRawAttachmentHashWithDifferentRepresentationIsRejected() {
        val compressed=BinaryData.Uncompressed(false,byteArrayOf(1,2,3)).toCompressed()
        val raw=BinaryData.Uncompressed(false,compressed.rawContent)
        assertEquals(compressed.hash,raw.hash)
        val a=database().modifyBinaries {mapOf(compressed.hash to compressed)}
        val b=database().modifyBinaries {mapOf(raw.hash to raw)}
        merge(a,b)
    }
    @Test fun twoRecycleBinsRemainRecycledAfterMerge() {
        val ga=Group(UUID.randomUUID(),"Bin A",times=times(0),entries=listOf(entry(1,"deleted-a")))
        val gb=Group(UUID.randomUUID(),"Bin B",times=times(0),entries=listOf(entry(1,"deleted-b")))
        val a=database().modifyContent {copy(meta=meta.copy(recycleBinUuid=ga.uuid,recycleBinEnabled=true),group=group.copy(groups=listOf(ga)))}
        val b=database().modifyContent {copy(meta=meta.copy(recycleBinUuid=gb.uuid,recycleBinEnabled=true),group=group.copy(groups=listOf(gb)))}
        val result=merge(a,b)
        assertEquals(ga.uuid,result.content.meta.recycleBinUuid);assertEquals(gb.uuid,result.content.group.groups.single().groups.single().uuid)
    }
    @Test fun unrelatedDatabasesImportNewUuidsButRepeatedMergeIsIdempotent() {
        val a=database(listOf(entry(1,"a"))); val b=database(listOf(entry(1,"b")))
        assertEquals(2,values(merge(merge(a,b),b)).size)
    }
    @Test fun iconCollisionRetainsBothOriginalImagesAndReferences() {
        val uuid=UUID.randomUUID();val aIcon=CustomIcon(byteArrayOf(1),null,null);val bIcon=CustomIcon(byteArrayOf(2),null,null)
        val a=database(listOf(entry(1,"a").copy(customIconUuid=uuid))).modifyContent {copy(meta=meta.copy(customIcons=mapOf(uuid to aIcon)))}
        val b=database(listOf(entry(1,"b").copy(customIconUuid=uuid))).modifyContent {copy(meta=meta.copy(customIcons=mapOf(uuid to bIcon)))}
        val result=merge(a,b);assertEquals(2,result.content.meta.customIcons.size)
        assertArrayEquals(byteArrayOf(2),result.content.meta.customIcons[values(result).single {it.fields["Title"]!!.content=="b"}.customIconUuid]!!.data)
        assertEquals(2,merge(result,b).content.meta.customIcons.size)
    }
    @Test fun attachmentsRemainAvailableForHistoricalVersions() {
        val binary=BinaryData.Uncompressed(true,byteArrayOf(1,2,3))
        val old=entry(1,"old").copy(binaries=listOf(BinaryReference(binary.hash,"file.bin")))
        val a=database(listOf(old)).modifyBinaries {mapOf(binary.hash to binary)}
        val b=a.modifyParentGroup {copy(entries=listOf(old.copy(times=times(2),binaries=emptyList())))}
        val result=merge(a,b);assertEquals(binary.hash,values(result).single().history.single().binaries.single().hash)
        assertArrayEquals(binary.rawContent,result.binaries[binary.hash]!!.rawContent)
    }
    @Test(expected=IllegalArgumentException::class) fun ambiguousUuidFailsBeforeAnyWrite() {
        val e=entry(1,"duplicate");merge(database(),database(listOf(e,e)))
    }
    @Test(expected=IllegalStateException::class) fun untimedPluginConflictIsNotSilentlyLost() {
        val a=database().modifyContent {copy(meta=meta.copy(customData=mapOf("plugin" to CustomDataValue("one",null))))}
        val b=a.modifyContent {copy(meta=meta.copy(customData=mapOf("plugin" to CustomDataValue("two",null))))}
        merge(a,b)
    }
}
