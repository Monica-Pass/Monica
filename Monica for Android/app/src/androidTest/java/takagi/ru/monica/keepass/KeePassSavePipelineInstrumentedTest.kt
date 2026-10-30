package takagi.ru.monica.keepass

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.ui.model.plainValueOrEmpty
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.utils.KeePassKdbxService
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the editor's real multi-target command, including its replica discovery. */
class KeePassSavePipelineInstrumentedTest {
    // Compare fixture values and protection, not the randomized in-memory XOR pads.
    private fun semanticEntry(entry: Entry): Entry = entry.copy(
        fields = entry.fields.mapValues { EntryValue.Plain("${it.value is EntryValue.Encrypted}:${it.value.content}") },
        history = entry.history.map(::semanticEntry),
    )

    private fun semanticFields(fields: List<KeePassNativeFieldRecord>) = fields.map {
        it.copy(nativeValue = EntryValue.Plain("${it.nativeValue is EntryValue.Encrypted}:${it.nativeValue.content}"))
    }

    private fun semanticHistory(history: List<KeePassNativeHistoryVersion>) = history.map {
        it.copy(fields = semanticFields(it.fields), nativeEntry = semanticEntry(it.nativeEntry))
    }

    private suspend fun select(model: PasswordViewModel, id: Long) {
        withContext(Dispatchers.Main) {
            val field = PasswordViewModel::class.java.getDeclaredField("_categoryFilter").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(model) as MutableStateFlow<CategoryFilter>).value = CategoryFilter.KeePassDatabase(id)
        }
        withTimeout(30000) { while (model.passwordEntries.value.size != 300) delay(25) }
    }

    private suspend fun save(model: PasswordViewModel, row: PasswordEntry, targets: List<StorageTarget>): List<Long> {
        model.getRawPasswordEntryById(row.id, true)
        val result = CompletableDeferred<List<Long>>()
        withContext(Dispatchers.Main) {
            model.savePasswordsAcrossTargets(listOf(row.id), row, listOf(row.password), targets,
                onCompleteWithIds = { _, ids -> result.complete(ids) })
        }
        return withTimeout(30000) { result.await() }
    }

    @Test fun sameVaultSaveDoesNotSubscribeToGlobalPasswordRows() = runBlocking {
        val measuring = AtomicBoolean(false)
        val fullReads = AtomicInteger()
        val saveFullReads = AtomicInteger()
        KeePassDeleteInvestigationTest().scenario(passwordDaoFactory = { dao ->
            Proxy.newProxyInstance(PasswordEntryDao::class.java.classLoader, arrayOf(PasswordEntryDao::class.java)) { _, method, args ->
                if (measuring.get() && method.name == "getActiveEntries") {
                    fullReads.incrementAndGet()
                    // Startup maintenance can overlap the save. Count the actual save
                    // coroutine separately so that unrelated readers cannot fail this guard.
                    if (Thread.currentThread().stackTrace.any { it.className.contains("PasswordViewModel\$savePasswordsAcrossTargets") }) {
                        saveFullReads.incrementAndGet()
                    }
                }
                try { method.invoke(dao, *(args ?: emptyArray())) }
                catch (error: InvocationTargetException) { throw error.targetException }
            } as PasswordEntryDao
        }) { model, service, id, room, uri ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val row = model.passwordEntries.value.first()
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                fun commits() = resolver.query(uri, arrayOf("commits"), null, null, null)!!.use { it.moveToFirst(); it.getInt(0) }
                val before = commits()
                measuring.set(true)
                val started = SystemClock.elapsedRealtime()
                val saved = save(model, row.copy(title = "Scoped save"), listOf(row.toStorageTarget()))
                val elapsed = SystemClock.elapsedRealtime() - started
                measuring.set(false)
                assertEquals(listOf(row.id), saved)
                assertEquals(1, commits() - before)
                assertEquals("Scoped save", service.openNativeBrowser(id).getOrThrow().entries.single { it.identity.entryUuid.toString() == row.keepassEntryUuid }.title)
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
                InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                    putString("stream", "KDBX_SAVE_PIPELINE ms=$elapsed global_reads=${fullReads.get()} save_global_reads=${saveFullReads.get()}\n")
                })
                assertEquals("Saving one native entry must not subscribe to all storage contents", 0, saveFullReads.get())
            }
        }
    }

    @Test fun multiTargetEditUpdatesExistingLocalReplicaAndPreservesUnrelatedRows() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, _ ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val store = requireNotNull(model.monicaKeePassStore)
                val original = model.passwordEntries.value.first()
                val group = UUID.randomUUID().toString()
                val source = store.save(id, listOf(original.copy(replicaGroupId = group)), emptyMap()).single()
                val local = PasswordEntry(title = "Existing replica", website = "", username = "", password = "fixture", replicaGroupId = group)
                val localId = room.passwordEntryDao().insertPasswordEntry(local)
                val sentinel = local.copy(title = "Unrelated", replicaGroupId = UUID.randomUUID().toString())
                val sentinelId = room.passwordEntryDao().insertPasswordEntry(sentinel)
                val saved = save(model, source.copy(title = "Updated both", password = "replica secret"),
                    listOf(source.toStorageTarget(), StorageTarget.MonicaLocal(null)))
                assertEquals(setOf(source.id, localId), saved.toSet())
                val updated = requireNotNull(room.passwordEntryDao().getPasswordEntryById(localId))
                assertEquals("Updated both", updated.title)
                assertEquals("replica secret", model.inspectSecretState(updated).plainValueOrEmpty())
                assertEquals(group, updated.replicaGroupId)
                assertEquals(sentinel.copy(id = sentinelId), room.passwordEntryDao().getPasswordEntryById(sentinelId))
                assertEquals("Updated both", service.openNativeBrowser(id).getOrThrow().entries.single { it.identity.entryUuid.toString() == source.keepassEntryUuid }.title)
                assertEquals(2, room.passwordEntryDao().getLocalBackupEntries().size)
            }
        }
    }

    @Test fun singleTargetEditStillRemovesUnselectedReplicaAfterSuccessfulSave() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, _, id, room, _ ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val store = requireNotNull(model.monicaKeePassStore)
                val group = UUID.randomUUID().toString()
                val source = store.save(id, listOf(model.passwordEntries.value.first().copy(replicaGroupId = group)), emptyMap()).single()
                val localId = room.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Old replica", website = "", username = "", password = "fixture", replicaGroupId = group))
                assertEquals(listOf(source.id), save(model, source.copy(title = "Selected target"), listOf(source.toStorageTarget())))
                assertNull(room.passwordEntryDao().getPasswordEntryById(localId))
                assertNotNull(model.getRawPasswordEntryById(source.id))
            }
        }
    }

    @Test fun failedNativeSaveKeepsSourceAndUnselectedReplica() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, _, id, room, uri ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val store = requireNotNull(model.monicaKeePassStore)
                val group = UUID.randomUUID().toString()
                val source = store.save(id, listOf(model.passwordEntries.value.first().copy(replicaGroupId = group)), emptyMap()).single()
                val local = PasswordEntry(title = "Preserve replica", website = "", username = "", password = "fixture", replicaGroupId = group)
                val localId = room.passwordEntryDao().insertPasswordEntry(local)
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                resolver.call(uri, "deny-write", uri.toString(), null)
                assertTrue(save(model, source.copy(title = "Rejected"), listOf(source.toStorageTarget())).isEmpty())
                assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
                assertEquals(local.copy(id = localId), room.passwordEntryDao().getPasswordEntryById(localId))
                assertEquals(source, model.getRawPasswordEntryById(source.id))
            }
        }
    }

    @Test fun scopedCandidatesPreserveOverviewVisibilityAcrossStores() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, _, id, room, _ ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val group = UUID.randomUUID().toString()
                val base = PasswordEntry(title = "Scoped fixture", website = "", username = "", password = "fixture", replicaGroupId = group)
                val localId = room.passwordEntryDao().insertPasswordEntry(base)
                val archivedId = room.passwordEntryDao().insertPasswordEntry(base.copy(isArchived = true))
                room.passwordEntryDao().insertPasswordEntry(base.copy(isDeleted = true))
                room.passwordEntryDao().insertPasswordEntry(base.copy(bitwardenVaultId = 991L, bitwardenCipherId = "fixture"))
                room.passwordEntryDao().insertPasswordEntry(base.copy(mdbxDatabaseId = 992L))
                room.passwordEntryDao().insertPasswordEntry(base.copy(keepassDatabaseId = id, keepassEntryUuid = UUID.randomUUID().toString()))
                room.passwordEntryDao().insertPasswordEntry(base.copy(keepassDatabaseId = 993L))
                room.passwordEntryDao().insertPasswordEntry(base.copy(replicaGroupId = "unrelated"))
                val repository = PasswordRepository(room.passwordEntryDao()).apply { monicaKeePassStore = model.monicaKeePassStore }
                val ids = listOf(model.passwordEntries.value.first().id, localId, archivedId)
                suspend fun checkCandidates(selected: List<Long>) {
                    val expected = repository.getAllPasswordEntries().first().filter { it.id in selected || it.replicaGroupId == group }
                    assertEquals(expected, repository.getActiveSaveCandidates(selected, group))
                }
                checkCandidates(ids)
                checkCandidates(emptyList())
                checkCandidates(ids + (1L..950L))
                assertTrue(repository.getActiveSaveCandidates(ids, group).none { it.id == archivedId })
            }
        }
    }

    @Test fun unchangedRefreshReusesProjectionButNewRevisionRefreshesReferenceDependents() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, uri ->
            model.holdNativeKeePassRead(id).use {
                select(model, id)
                val store = requireNotNull(model.monicaKeePassStore)
                val first = store.refresh(id)
                assertSame(first, store.refresh(id))
                val target = first.browser.entries[0]
                val dependent = first.browser.entries[1]
                val reference = "{REF:P@I:${target.identity.entryUuid.toString().replace("-", "")}}"
                service.replaceNativeEntryFields(id, dependent.identity.entryUuid,
                    dependent.fields.map { KeePassFieldChange(it.name, if (it.name == "Password") reference else it.rawValue, it.isProtected) },
                    first.browser.sourceRevision.sha256).getOrThrow()
                val linked = store.refresh(id)
                assertNotSame(first, linked)
                val targetRow = linked.rows.single { it.keepassEntryUuid == target.identity.entryUuid.toString() }
                val after = store.save(id, listOf(targetRow.copy(password = "changed reference target")), emptyMap())
                assertEquals(targetRow.id, after.single().id)
                val updated = store.refresh(id)
                assertEquals("changed reference target", updated.rows.single { it.keepassEntryUuid == dependent.identity.entryUuid.toString() }.password)
                assertEquals(reference, updated.browser.entriesByIdentity[dependent.identity]!!.single().field("Password")!!.rawValue)
                assertSame(updated, store.refresh(id))
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                service.lockDatabase(id)
                // The UI consumes the lock notification asynchronously before another read.
                withTimeout(10000) { while (model.passwordEntries.value.isNotEmpty()) delay(25) }
                assertNull(model.getRawPasswordEntryById(targetRow.id))
                assertTrue(runCatching { store.refresh(id) }.isFailure)
                assertFalse(service.isDatabaseUnlocked(id))
                assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            }
        }
    }

    @Test fun nativeCrossVaultMovePreservesUuidHistoryAndAttachment() = runBlocking {
        crossVaultMove(denyTarget = false)
    }

    @Test fun nativeCrossVaultMoveFailureLeavesBothFilesIntact() = runBlocking {
        crossVaultMove(denyTarget = true)
    }

    private suspend fun crossVaultMove(denyTarget: Boolean) {
        KeePassDeleteInvestigationTest().scenario { model, service, sourceId, room, sourceUri ->
            KeePassDeleteInvestigationTest().scenario { _, _, targetId, _, targetUri ->
                model.holdNativeKeePassRead(sourceId).use {
                    model.holdNativeKeePassRead(targetId).use {
                        select(model, sourceId)
                        val store = requireNotNull(model.monicaKeePassStore)
                        val original = model.passwordEntries.value.first()
                        store.save(sourceId, listOf(original.copy(title = "Move with history")), emptyMap())
                        val owner = takagi.ru.monica.attachments.model.AttachmentOwner.password(original.id)
                        store.addAttachment(owner, "move.txt", "move attachment".toByteArray())
                        val row = requireNotNull(model.getRawPasswordEntryById(original.id))
                        // Compare the persisted source: KDBX4 stores timestamps to seconds,
                        // while a just-saved in-memory entry can still contain milliseconds.
                        KeePassKdbxService.invalidateProcessCache(sourceId)
                        val record = service.openNativeBrowser(sourceId).getOrThrow().entries.single { it.identity.entryUuid.toString() == row.keepassEntryUuid }
                        assertTrue(record.history.isNotEmpty())
                        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                        val sourceBytes = resolver.openInputStream(sourceUri)!!.use { it.readBytes() }
                        val targetBytes = resolver.openInputStream(targetUri)!!.use { it.readBytes() }
                        if (denyTarget) resolver.call(targetUri, "deny-write", targetUri.toString(), null)
                        val result = runCatching { store.moveToDatabase(row, targetId, null) }
                        if (denyTarget) {
                            assertTrue(result.isFailure)
                            assertArrayEquals(sourceBytes, resolver.openInputStream(sourceUri)!!.use { it.readBytes() })
                            assertArrayEquals(targetBytes, resolver.openInputStream(targetUri)!!.use { it.readBytes() })
                        } else {
                            result.getOrThrow()
                            assertFalse(service.openNativeBrowser(sourceId).getOrThrow().entries.any { it.identity.entryUuid == record.identity.entryUuid })
                            val moved = service.openNativeBrowser(targetId).getOrThrow().entries.single { it.identity.entryUuid == record.identity.entryUuid }
                            assertEquals(semanticHistory(record.history), semanticHistory(moved.history))
                            assertEquals(semanticFields(record.fields), semanticFields(moved.fields))
                            assertEquals(record.attachments.map { it.name to it.hash }, moved.attachments.map { it.name to it.hash })
                            assertArrayEquals("move attachment".toByteArray(), moved.attachments.single().binary!!.rawContent)
                            assertEquals(299, store.refresh(sourceId).rows.size)
                            assertEquals(301, store.refresh(targetId).rows.size)
                        }
                        assertTrue(room.passwordEntryDao().getAllPasswordEntriesSync().isEmpty())
                    }
                }
            }
        }
    }
}
