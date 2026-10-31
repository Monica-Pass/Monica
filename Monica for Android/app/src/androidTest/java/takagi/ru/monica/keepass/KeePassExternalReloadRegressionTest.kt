package takagi.ru.monica.keepass

import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.security.SessionManager
import java.time.Instant
import java.util.UUID

class KeePassExternalReloadRegressionTest {
    @Test fun subscriptionStartedWhileLockedSurvivesUnlockAndHidesOnDatabaseLock() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, _ -> coroutineScope {
            val store = KeePassMonicaPasswordStore(service)
            val seen = MutableStateFlow<Int?>(null)
            val errors = mutableListOf<Throwable>()
            SessionManager.markLocked()
            val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                store.rows(id).catch { errors += it }.collect { seen.value = it.size }
            }
            try {
                withTimeout(5000) { seen.filterNotNull().first { it == 0 } }
                assertTrue(subscription.isActive)
                SessionManager.markUnlocked()
                store.refresh(id)
                withTimeout(5000) { seen.filterNotNull().first { it == 300 } }
                service.lockDatabase(id)
                withTimeout(5000) { seen.filterNotNull().first { it == 0 } }
                assertFalse("Observing a lock must not reopen the database", service.isDatabaseUnlocked(id))
                assertTrue(errors.isEmpty())
            } finally { subscription.cancelAndJoin(); SessionManager.markUnlocked() }
        } }
    }

    @Test fun failedInitialReadKeepsSubscriptionForExplicitRecovery() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri -> coroutineScope {
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val original = resolver.openInputStream(uri)!!.use { it.readBytes() }
            resolver.openOutputStream(uri, "wt")!!.use { it.write(byteArrayOf(1, 2, 3)) }
            val store = KeePassMonicaPasswordStore(service)
            val seen = MutableStateFlow<Int?>(null)
            val errors = mutableListOf<Throwable>()
            val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                store.rows(id).catch { errors += it }.collect { seen.value = it.size }
            }
            try {
                withTimeout(5000) { seen.filterNotNull().first { it == 0 } }
                assertTrue(subscription.isActive)
                resolver.openOutputStream(uri, "wt")!!.use { it.write(original) }
                store.refresh(id)
                withTimeout(5000) { seen.filterNotNull().first { it == 300 } }
                assertTrue(errors.isEmpty())
                assertArrayEquals(original, resolver.openInputStream(uri)!!.use { it.readBytes() })
            } finally { subscription.cancelAndJoin() }
        } }
    }

    @Test fun emptyDatabasePublishesReadinessWithoutPasswordRowChanges() = runBlocking {
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { db ->
            db.modifyParentGroup { copy(entries = emptyList()) }
        }) { _, service, id, _, _ ->
            val store = KeePassMonicaPasswordStore(service)
            assertNull(store.fileStates.value[id]?.state)
            assertTrue(store.refresh(id).rows.isEmpty())
            assertEquals(KeePassFileState.READY, store.fileStates.value[id]?.state)
            assertTrue(store.isDatabaseAvailable(id))
        }
    }
    @Test fun externalDeletionMustBeVisibleWhenReenteringTheDatabase() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, uri ->
            val store = requireNotNull(model.monicaKeePassStore)
            assertEquals(300, store.refresh(id).rows.size)
            val reader = model.holdNativeKeePassRead(id)
            delay(100)
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val credentials = Credentials.from(EncryptedValue.fromString("Synthetic KDBX deletion test"))
            val before = resolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
            val deleted = before.modifyContent {
                copy(group = group.copy(entries = emptyList()), deletedObjects = group.entries.map {
                    DeletedObject(it.uuid, Instant.now())
                })
            }
            resolver.openOutputStream(uri, "wt")!!.use { deleted.encode(it) }
            try {
                model.syncKeePassDatabaseForVisibleVault(id, forceRefresh = true)
                repeat(100) { if (store.rows(id).first().isEmpty()) return@repeat else delay(50) }
                assertEquals("The actual reload menu command must refresh external deletions without restarting", 0, store.rows(id).first().size)
            } finally { reader.close() }
        }
    }

    @Test fun disablingFutureRecyclingMustNotExposeExistingRecycledDescendants() = runBlocking {
        val recycleId = UUID.randomUUID()
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { db ->
            db.modifyContent {
                copy(meta = meta.copy(recycleBinEnabled = false, recycleBinUuid = recycleId),
                    group = group.copy(entries = group.entries.take(1), groups = listOf(
                        Group(uuid = recycleId, name = "Renamed bin", groups = listOf(
                            Group(uuid = UUID.randomUUID(), name = "Nested", entries = group.entries.drop(1))
                        ))
                    )))
            }
        }) { _, service, id, _, _ ->
            assertEquals("Recycle membership comes from the UUID, independent of the future-deletion setting",
                1, KeePassMonicaPasswordStore(service).refresh(id).rows.size)
            assertEquals(299, service.readPasswordEntries(id).getOrThrow().count { it.isInRecycleBin })
        }
    }

    @Test fun ordinaryGroupNamedTrashMustStayVisible() = runBlocking {
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { db ->
            db.modifyParentGroup { copy(name = "Trash") }
        }) { _, service, id, _, _ ->
            assertEquals(300, KeePassMonicaPasswordStore(service).refresh(id).rows.size)
        }
    }

    @Test fun unchangedSourceCheckKeepsTheSameProjectionAndDoesNotWrite() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            val before = store.refresh(id)
            assertFalse(store.checkForExternalChanges(id))
            assertSame(before, store.refresh(id))
            assertEquals(1, counter(uri, "commits"))
        }
    }

    @Test fun foregroundCheckLoadsExternalDeletionAndMarksTheStatus() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            store.refresh(id)
            externalDelete(uri)
            assertTrue(store.checkForExternalChanges(id))
            assertTrue(store.rows(id).first().isEmpty())
            assertEquals(KeePassFileState.EXTERNAL_RELOADED, store.fileStates.value[id]?.state)
            assertEquals(2, counter(uri, "commits"))
        }
    }

    @Test fun failedExternalReloadKeepsTheFileAndShowsFailure() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            store.refresh(id)
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val broken = byteArrayOf(1,2,3)
            resolver.openOutputStream(uri, "wt")!!.use { it.write(broken) }
            assertFalse(store.checkForExternalChanges(id))
            assertEquals(KeePassFileState.RELOAD_FAILED, store.fileStates.value[id]?.state)
            assertTrue("Failed reload must keep the last valid session available for a retry", store.isDatabaseAvailable(id))
            assertArrayEquals(broken, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(300, store.rows(id).first().size)
        }
    }

    @Test fun reloadedDeletionCannotBeOverwrittenByAnOldEditorDraft() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            val row = store.refresh(id).rows.first()
            store.get(row.id, forEdit = true)
            externalDelete(uri)
            assertTrue(store.checkForExternalChanges(id))
            val writes = counter(uri, "commits")
            assertTrue(runCatching { store.save(id, listOf(row.copy(title="stale draft")), emptyMap()) }.isFailure)
            assertEquals(writes, counter(uri, "commits"))
            assertTrue(store.rows(id).first().isEmpty())
        }
    }

    @Test fun explicitSaveWritesOnceAndPreservesDatabaseParameters() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            val before = store.refresh(id)
            val header = service.openNativeSession(id).getOrThrow().database.header
            store.saveDatabase(id)
            assertEquals(2, counter(uri, "commits"))
            val after = store.refresh(id)
            assertEquals(before.rows, after.rows)
            val updated = service.openNativeSession(id).getOrThrow().database.header
            assertEquals(header.version, updated.version)
            assertEquals(header.cipherId, updated.cipherId)
            val oldKdf = (header as app.keemobile.kotpass.database.header.DatabaseHeader.Ver4x).kdfParameters as app.keemobile.kotpass.database.header.KdfParameters.Argon2
            val newKdf = (updated as app.keemobile.kotpass.database.header.DatabaseHeader.Ver4x).kdfParameters as app.keemobile.kotpass.database.header.KdfParameters.Argon2
            assertEquals(oldKdf.iterations, newKdf.iterations)
            assertEquals(oldKdf.memory, newKdf.memory)
            assertEquals(oldKdf.parallelism, newKdf.parallelism)
        }
    }

    @Test fun mergingSameDatabaseNeverDuplicatesItsEntries() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            val store = KeePassMonicaPasswordStore(service)
            val before = store.refresh(id)
            store.mergeDatabase(id, uri, "Synthetic KDBX deletion test", null)
            assertEquals(300, store.refresh(id).rows.size)
            assertEquals(before.rows.map { it.keepassEntryUuid }.toSet(), store.rows(id).first().map { it.keepassEntryUuid }.toSet())
            assertEquals(KeePassFileState.MERGED, store.fileStates.value[id]?.state)
        }
    }

    private fun counter(uri: android.net.Uri, column: String): Int = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        .query(uri, arrayOf(column), null, null, null)!!.use { it.moveToFirst(); it.getInt(0) }

    private fun externalDelete(uri: android.net.Uri) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val credentials = Credentials.from(EncryptedValue.fromString("Synthetic KDBX deletion test"))
        val before = resolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
        val deleted = before.modifyContent { copy(group=group.copy(entries=emptyList()),
            deletedObjects=group.entries.map { DeletedObject(it.uuid, Instant.now()) }) }
        resolver.openOutputStream(uri, "wt")!!.use { deleted.encode(it) }
    }
}
