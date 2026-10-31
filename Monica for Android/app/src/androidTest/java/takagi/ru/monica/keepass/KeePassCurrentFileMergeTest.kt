package takagi.ru.monica.keepass

import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class KeePassCurrentFileMergeTest {
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val credentials get() = Credentials.from(EncryptedValue.fromString("Synthetic KDBX deletion test"))

    @Test fun currentFileMergeReadsExternalEditsAndDeletionWithoutChoosingASource() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            val before = store.refresh(id)
            val original = resolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
            val removed = original.content.group.entries.last().uuid
            val edited = original.content.group.entries.first().uuid
            val changed = original.modifyContent { copy(
                group = group.copy(entries = group.entries.dropLast(1).map { entry ->
                    if (entry.uuid != edited) entry else entry.copy(
                        fields = entry.fields + ("Title" to EntryValue.Plain("External client update")),
                        times = entry.times?.copy(lastModificationTime = Instant.now().plusSeconds(5)))
                }),
                deletedObjects = deletedObjects + DeletedObject(removed, Instant.now().plusSeconds(5))) }
            resolver.openOutputStream(uri, "wt")!!.use { changed.encode(it) }
            store.mergeCurrentDatabase(id)
            val saved = resolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
            assertEquals(299, saved.content.group.entries.size)
            assertFalse(saved.content.group.entries.any { it.uuid == removed })
            assertEquals("External client update", saved.content.group.entries.first { it.uuid == edited }.fields["Title"]?.content)
            assertEquals(299, store.rows(id).first().size)
            assertEquals(before.browser.databaseId, store.refresh(id).browser.databaseId)
            assertEquals(KeePassFileState.MERGED, store.fileStates.value[id]?.state)
        }
    }

    @Test fun unreadableCurrentFileIsNotOverwrittenAndLastSessionIsKept() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            store.refresh(id)
            val broken = byteArrayOf(1, 2, 3)
            resolver.openOutputStream(uri, "wt")!!.use { it.write(broken) }
            assertTrue(runCatching { store.mergeCurrentDatabase(id) }.isFailure)
            assertArrayEquals(broken, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(300, store.rows(id).first().size)
            assertTrue(store.isDatabaseAvailable(id))
        }
    }

    @Test fun lockedCurrentFileMergeDoesNotWrite() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            store.refresh(id)
            val original = resolver.openInputStream(uri)!!.use { it.readBytes() }
            service.lockDatabase(id)
            assertTrue(runCatching { store.mergeCurrentDatabase(id) }.isFailure)
            assertArrayEquals(original, resolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun readOnlyCurrentFileMergeDoesNotWrite() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            store.refresh(id)
            val original = resolver.openInputStream(uri)!!.use { it.readBytes() }
            service.setDatabaseReadOnly(id, true)
            try {
                assertTrue(runCatching { store.mergeCurrentDatabase(id) }.isFailure)
                assertArrayEquals(original, resolver.openInputStream(uri)!!.use { it.readBytes() })
            } finally { service.setDatabaseReadOnly(id, false) }
        }
    }

    @Test fun eachClientsAddedEntrySurvivesCurrentFileMerge() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            store.refresh(id)
            val original = resolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
            val localOnly = original.content.group.entries.last().uuid
            val remoteOnly = java.util.UUID.randomUUID()
            val changed = original.modifyContent { copy(group = group.copy(entries =
                group.entries.dropLast(1) + group.entries.first().copy(uuid = remoteOnly))) }
            resolver.openOutputStream(uri, "wt")!!.use { changed.encode(it) }
            store.mergeCurrentDatabase(id)
            val saved = resolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
            assertEquals(301, saved.content.group.entries.size)
            assertTrue(saved.content.group.entries.any { it.uuid == localOnly })
            assertTrue(saved.content.group.entries.any { it.uuid == remoteOnly })
            assertEquals(original.header.version, saved.header.version)
            assertEquals(original.header.cipherId, saved.header.cipherId)
        }
    }

    @Test fun sourceChangedDuringMergeIsNotOverwritten() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            store.refresh(id)
            val thirdVersion = byteArrayOf(7, 8, 9)
            resolver.call(uri, "replace-on-read", uri.toString(), android.os.Bundle().apply {
                putByteArray("bytes", thirdVersion); putInt("read", 2)
            })
            assertTrue(runCatching { store.mergeCurrentDatabase(id) }.isFailure)
            assertArrayEquals(thirdVersion, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(300, store.rows(id).first().size)
        }
    }
}
