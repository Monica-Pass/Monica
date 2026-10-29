package takagi.ru.monica.keepass

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.utils.KeePassKdbxService
import java.util.UUID
import takagi.ru.monica.repository.PasskeyRepository

class KeePassMonicaSecureItemTest {
    @Test fun monicaPasskeyDetailsWriteUuidEntryWithoutRoomProjectionOrLegacySecondWrite() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, uri ->
            val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val encode: (ByteArray) -> String = { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            val browser = service.openNativeBrowser(id).getOrThrow()
            val entry = browser.entries.first()
            service.replaceNativeEntryFields(id, entry.identity.entryUuid,
                entry.fields.map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) } + listOf(
                    KeePassFieldChange(KeePassDxPasskeyCodec.FIELD_PASSKEY, ""),
                    KeePassFieldChange(KeePassDxPasskeyCodec.FIELD_USERNAME, "fixture-user"),
                    KeePassFieldChange(KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY, "-----BEGIN PRIVATE KEY-----\n${encode(pair.private.encoded)}\n-----END PRIVATE KEY-----", true),
                    KeePassFieldChange(KeePassDxPasskeyCodec.FIELD_CREDENTIAL_ID, encode(UUID.randomUUID().toString().toByteArray())),
                    KeePassFieldChange(KeePassDxPasskeyCodec.FIELD_USER_HANDLE, "c3ludGhldGljLXVzZXI"),
                    KeePassFieldChange(KeePassDxPasskeyCodec.FIELD_RELYING_PARTY, "example.test")),
                browser.sourceRevision.sha256).getOrThrow()
            val repository = PasskeyRepository(room.passkeyDao())
            repository.bindMonicaKeePassStore(requireNotNull(model.monicaKeePassStore))
            model.holdNativeKeePassRead(id).use {
                requireNotNull(model.monicaKeePassStore).refresh(id)
                val passkey = repository.getAllPasskeys().first().single()
                assertTrue(KeePassMonicaPasswordStore.isUiId(passkey.id))
                assertTrue(room.passkeyDao().getAllPasskeysSync().isEmpty())
                val draft = requireNotNull(repository.getPasskeyByRecordId(passkey.id))
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                fun commits() = resolver.query(uri, arrayOf("commits"), null, null, null)!!.use { it.moveToFirst(); it.getInt(0) }
                val before = commits()
                assertTrue(KeePassPasskeyUpdateExecutor(null).update(draft, draft.copy(notes = "Monica passkey note"), repository::updatePasskey).isSuccess)
                assertEquals(1, commits() - before)
                val after = service.openNativeBrowser(id).getOrThrow().entry(entry.identity)!!
                assertEquals("Monica passkey note", after.field("Notes")!!.rawValue)
                assertEquals(entry.field("Password")!!.rawValue, after.field("Password")!!.rawValue)
                assertTrue(room.passkeyDao().getAllPasskeysSync().isEmpty())
            }
        }
    }

    @Test fun lateBoundMonicaNoteFlowReadsAndWritesOriginalVaultWithoutDoubleCommit() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, uri ->
            val repository = SecureItemRepository(room.secureItemDao())
            val flowCreatedBeforeBinding = repository.getActiveItemsByType(ItemType.NOTE)
            val localId = repository.insertItem(SecureItem(itemType = ItemType.NOTE, title = "Local sentinel", itemData = "local content"))
            val localBefore = repository.getItemById(localId)
            repository.bindMonicaKeePassStore(requireNotNull(model.monicaKeePassStore))
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            fun commits() = resolver.query(uri, arrayOf("commits"), null, null, null)!!.use { it.moveToFirst(); it.getInt(0) }
            model.holdNativeKeePassRead(id).use {
                repository.refreshOriginalKeePass(id)
                val before = commits()
                val created = KeePassSecureItemCreateExecutor(null).create(
                    SecureItem(itemType = ItemType.NOTE, title = "Original note", itemData = "Original body", keepassDatabaseId = id),
                    repository::insertItem, repository::deleteItemById)
                assertNotNull(created)
                assertEquals(1, commits() - before)
                val notes = withTimeout(30000) { flowCreatedBeforeBinding.first { it.any { row -> row.id == created } } }
                val note = notes.single { it.id == created }
                assertEquals("Original note", note.title)
                assertTrue(KeePassMonicaPasswordStore.isUiId(note.id))
                assertTrue(room.secureItemDao().getAllItems().first().none { it.keepassDatabaseId == id })
                val origin = requireNotNull(repository.getItemById(note.id))
                val writes = commits()
                val result = KeePassSecureItemUpdateExecutor(null, repository).syncUpdatedItem(origin,
                    origin.copy(title = "Monica note updated"), repository::updateItem)
                assertTrue(result.isSuccess)
                assertEquals("A Monica note update must not perform a second legacy write", 1, commits() - writes)
                assertEquals("Monica note updated", service.openNativeBrowser(id).getOrThrow().entry(
                    KeePassNativeEntryIdentity(id, UUID.fromString(note.keepassEntryUuid)))!!.title)
                assertEquals(localBefore, repository.getItemById(localId))
                service.lockDatabase(id)
                withTimeout(10000) { while (repository.getItemById(note.id) != null) delay(25) }
                assertEquals(localBefore, repository.getItemById(localId))
                assertFalse(service.isDatabaseUnlocked(id))
            }
        }
    }

    @Test fun nativeNoteDraftDoesNotOverwriteExternalChangeAfterRefresh() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, uri ->
            val repository = SecureItemRepository(room.secureItemDao())
            repository.bindMonicaKeePassStore(requireNotNull(model.monicaKeePassStore))
            model.holdNativeKeePassRead(id).use {
                repository.refreshOriginalKeePass(id)
                val noteId = repository.insertItem(SecureItem(itemType = ItemType.NOTE, title = "Original note", itemData = "body", keepassDatabaseId = id))
                val draft = requireNotNull(repository.getItemById(noteId))
                val browser = service.openNativeBrowser(id).getOrThrow()
                val identity = KeePassNativeEntryIdentity(id, UUID.fromString(draft.keepassEntryUuid))
                val entry = browser.entry(identity)!!
                service.replaceNativeEntryFields(id, identity.entryUuid,
                    entry.fields.map { KeePassFieldChange(it.name, if (it.name == "Title") "Other writer" else it.rawValue, it.isProtected) },
                    browser.sourceRevision.sha256).getOrThrow()
                repository.refreshOriginalKeePass(id)
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                assertTrue(runCatching { repository.updateItem(draft.copy(title = "Stale overwrite")) }.isFailure)
                assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            }
        }
    }
}
