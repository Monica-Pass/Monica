package takagi.ru.monica.keepass

import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.utils.KeePassKdbxService
import java.time.Instant

class KeePassHistoryStabilityTest {
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val credentials get() = Credentials.from(EncryptedValue.fromString("Synthetic KDBX deletion test"))
    private val epoch = Instant.parse("2026-10-31T14:00:00Z")
    internal fun fixture(db: KeePassDatabase): KeePassDatabase = db.modifyParentGroup {
        val current = entries.first().copy(times = TimeData.create().copy(lastModificationTime = epoch.plusSeconds(180),
            lastAccessTime = epoch, usageCount = 0))
        val older = current.copy(times = current.times!!.copy(lastModificationTime = epoch.plusSeconds(60)),
            fields = current.fields + ("Notes" to EntryValue.Plain("First historical note")))
        val newer = older.copy(times = older.times!!.copy(lastModificationTime = epoch.plusSeconds(120)),
            fields = older.fields + ("Notes" to EntryValue.Plain("Second historical note")))
        copy(entries = listOf(current.copy(history = listOf(older, newer))))
    }

    @Test fun historyDisplayIsNewestFirstAndDeleteKeepsOriginalNativeIndex() = runBlocking {
        KeePassDeleteInvestigationTest().scenario(databaseTransform = ::fixture) { _, service, id, _, _ ->
            val store = KeePassMonicaPasswordStore(service)
            val row = store.refresh(id).rows.single()
            val history = store.history(row.id).first()
            assertEquals(listOf(epoch.plusSeconds(120).toEpochMilli(), epoch.plusSeconds(60).toEpochMilli()),
                history.map { it.lastUsedAt.time })
            assertEquals(2, history.size)
            store.deleteHistory(history.first().id)
            KeePassKdbxService.invalidateProcessCache(id)
            val native = service.openNativeBrowser(id).getOrThrow().entries.single().nativeEntry
            assertEquals("First historical note", native.history.single().fields["Notes"]!!.content)
            assertTrue(native.history.single().fields["Password"] is EntryValue.Encrypted)
        }
    }

    @Test fun repeatedIdenticalNativeSavesPreserveHistoryAfterDiskReopen() = runBlocking {
        KeePassDeleteInvestigationTest().scenario(databaseTransform = ::fixture) { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            store.refresh(id)
            fun read() = resolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
            val before = read().content.group.entries.single()
            repeat(3) {
                val browser = service.openNativeBrowser(id).getOrThrow()
                val record = browser.entries.single()
                service.replaceNativeEntryFields(id, record.identity.entryUuid,
                    record.fields.reversed().map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) },
                    browser.sourceRevision.sha256).getOrThrow()
                KeePassKdbxService.invalidateProcessCache(id)
            }
            assertTrue(KeePassLosslessTransfer.entriesEquivalent(before, read().content.group.entries.single()))
            // The ordinary editor can add its compatibility fields once. Further
            // unchanged saves must keep the resulting native history and timestamps.
            store.refresh(id)
            var row = requireNotNull(store.get(store.rows(id).first().single().id, forEdit = true))
            store.save(id, listOf(row), mapOf(row.id to store.fields(row.id).map(CustomFieldDraft::fromCustomField)))
            val baseline = read().content.group.entries.single()
            repeat(3) {
                row = requireNotNull(store.get(row.id, forEdit = true))
                store.save(id, listOf(row), mapOf(row.id to store.fields(row.id).map(CustomFieldDraft::fromCustomField)))
            }
            assertTrue(KeePassLosslessTransfer.entriesEquivalent(baseline, read().content.group.entries.single()))
        }
    }

    @Test fun accessOnlyExternalMergesAndRepeatedMergesKeepRealHistory() = runBlocking {
        KeePassDeleteInvestigationTest().scenario(databaseTransform = ::fixture) { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            val row = store.refresh(id).rows.single()
            val original = resolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
            val accessed = original.modifyParentGroup { copy(entries = entries.map { entry ->
                entry.copy(times = entry.times!!.copy(lastAccessTime = epoch.plusSeconds(240), usageCount = 9))
            }) }
            resolver.openOutputStream(uri, "wt")!!.use { accessed.encode(it) }
            repeat(3) { store.mergeCurrentDatabase(id) }
            KeePassKdbxService.invalidateProcessCache(id)
            val saved = service.openNativeBrowser(id).getOrThrow().entries.single().nativeEntry
            assertEquals(2, saved.history.size)
            assertEquals(listOf("First historical note", "Second historical note"), saved.history.map { it.fields["Notes"]!!.content })
            assertEquals(epoch.plusSeconds(240), saved.times!!.lastAccessTime)
            assertEquals(9, saved.times!!.usageCount)
            assertTrue(saved.fields["Password"] is EntryValue.Encrypted)
            store.refresh(id)
            assertEquals(listOf(epoch.plusSeconds(120).toEpochMilli(), epoch.plusSeconds(60).toEpochMilli()),
                store.history(row.id).first().map { it.lastUsedAt.time })
        }
    }
}
