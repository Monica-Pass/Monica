package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.service.BitwardenWireJson
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import uniffi.mdbx_ffi.MdbxWriteCommand
import java.io.File
import java.util.UUID

/** Synthetic native vaults only; no Room credential import and no main-session unlock. */
@RunWith(AndroidJUnit4::class)
class MdbxAutofillInstrumentedTest {
    private val loginPayload = """{"username":"native-user","password_plain":"native-secret","website":"https://native.test","future":{"big":9007199254740993,"nil":null}}"""
    private val cardPayload = """{"kind":"bank_card","item_data":"{\"cardNumber\":\"4111111111111111\",\"cardholderName\":\"Synthetic Card\",\"expiryMonth\":\"12\",\"expiryYear\":\"2030\",\"cvv\":\"123\"}"}"""

    private class Fixture(val room: PasswordDatabase, val repository: Mdbx2Repository, val security: SecurityManager,
        val file: File, val databaseId: Long, val password: String) {
        suspend fun login(payload: String, title: String = "Native login") =
            repository.saveNativeObject(databaseId, null, title, "login", payload)
        suspend fun snapshot(check: () -> Unit = {}) = repository.readAutofillSnapshot(databaseId, check)
        suspend fun current(id: String) = repository.nativeObject(databaseId, id)
    }

    private suspend fun <T> fixture(block: suspend Fixture.() -> T): T = withContext(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, room.localMdbxDatabaseDao(), security)
        val password = "synthetic-autofill-${UUID.randomUUID()}"
        val file = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        var databaseId = 0L
        try {
            databaseId = room.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                name = "Synthetic native autofill ${UUID.randomUUID()}", filePath = file.absolutePath,
                storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                engineType = MdbxEngineType.RUST_MDBX2.name, tigaMode = MdbxTigaMode.SKY.name,
                encryptedPassword = security.encryptDataLegacyCompat(password),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                workingCopyPath = file.absolutePath, cacheCopyPath = file.absolutePath, isOfflineAvailable = true))
            Fixture(room, repository, security, file, databaseId, password).block()
        } finally {
            if (databaseId > 0) room.localMdbxDatabaseDao().deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(file)
        }
    }

    @Test fun realNativeLoginAndWalletReadWithoutRoomMirrorsOrMainUnlock() = runBlocking { fixture {
        SessionManager.markLocked()
        val id = login(loginPayload)
        val cardId = repository.saveNativeObject(databaseId, null, "Native card", "card", cardPayload)
        val documentId = repository.saveNativeObject(databaseId, null, "Native passport", "document-ref",
            """{"kind":"document","item_data":"{\"documentType\":\"PASSPORT\",\"documentNumber\":\"SYNTHETIC\",\"fullName\":\"Synthetic Person\"}"}""")
        val addressId = repository.saveNativeObject(databaseId, null, "Native address", "billing-address",
            """{"kind":"billing_address","item_data":"{\"streetAddress\":\"Synthetic Street\",\"country\":\"Test\"}"}""")
        val before = current(id)
        assertTrue(room.passwordEntryDao().getAllPasswordEntries().first().none { it.mdbxDatabaseId == databaseId })
        val snapshot = snapshot()
        val credential = snapshot.credentials.single()
        assertEquals(id, credential.objectId)
        assertEquals("native-user", credential.entry.username)
        assertEquals("native-secret", credential.entry.password)
        assertEquals(0L, credential.entry.id)
        assertEquals(setOf(cardId, documentId, addressId), snapshot.walletItems.map { it.objectId }.toSet())
        assertEquals(credential, repository.readAutofillCredential(databaseId, id, credential.revision) {})
        snapshot.walletItems.forEach { item -> assertEquals(item,
            repository.readAutofillWallet(databaseId, item.objectId, item.revision) {}) }
        assertEquals(before, current(id))
        assertTrue(room.passwordEntryDao().getAllPasswordEntries().first().none { it.mdbxDatabaseId == databaseId })
        assertFalse("Autofill must not unlock the main application", SessionManager.isUnlocked.value)
    } }

    @Test fun unknownFutureAndConflictingPayloadsAreNeverLoginSuggestions() = runBlocking { fixture {
        val validId = login(loginPayload)
        repository.saveNativeObject(databaseId, null, "Unknown", "identity", loginPayload)
        repository.saveNativeObject(databaseId, null, "API token", "api-token", loginPayload)
        // Distinct conflicting aliases survive native JSON canonicalization and must be rejected.
        login("""{"username":"u","password_plain":"one","passwordPlain":"two"}""", "Conflicting aliases")
        login("""{"username":"u","password_plain":"secret","login_type":"WIFI"}""", "Wi-Fi")
        login("""{"username":"u","password_plain":"secret","bitwarden_mode":true}""", "Connector mirror")
        repository.withVaultForSync(databaseId) { _, vault ->
            val root = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
            vault.createObject(root, "login", "Future version", loginPayload, 99u)
            vault.createObject(root, "card", "Future card", cardPayload, 99u)
        }
        assertEquals(listOf(validId), snapshot().credentials.map { it.objectId })
        assertTrue(snapshot().walletItems.isEmpty())
    } }

    @Test fun nativeDuplicateKeyCanonicalizationIsObservedBeforeAutofillParsing() = runBlocking { fixture {
        val original = """{"username":"synthetic-duplicate","password_plain":"one","password_plain":"two"}"""
        assertNull("The reader must still reject raw duplicate keys", MdbxAutofillReader.parse(
            original, databaseId, "synthetic-object", "synthetic-folder", "Duplicate keys", "revision", { it }))
        val id = login(original, "Synthetic duplicate normalization")
        val returned = current(id).payload
        // The engine stores a parsed JSON object; a duplicate key is no longer present on read.
        // Record the actual returned synthetic fixture rather than assuming it equals the input.
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putString("stream", "\nMDBX_DUPLICATE_CANONICALIZATION328 " + org.json.JSONObject()
                .put("input", original).put("returned", returned).toString() + "\n")
        })
        val decoded = BitwardenWireJson.parseObject(returned, MdbxAutofillReader.MAX_PAYLOAD_BYTES)
        assertEquals(BitwardenWireJson.parseObject(
            """{"username":"synthetic-duplicate","password_plain":"two"}""",
            MdbxAutofillReader.MAX_PAYLOAD_BYTES), decoded)
        val credential = snapshot().credentials.single()
        assertEquals(id, credential.objectId)
        assertEquals("synthetic-duplicate", credential.entry.username)
        assertEquals("two", credential.entry.password)
        assertEquals(credential, repository.readAutofillCredential(databaseId, id, credential.revision) {})
    } }

    @Test fun selectionRejectsPasswordTypeVersionMoveAndDeletionChanges() = runBlocking { fixture {
        val id = login(loginPayload)
        suspend fun assertRejectedAfter(change: suspend () -> Unit) {
            val prior = snapshot().credentials.single { it.objectId == id }
            change()
            assertNull(repository.readAutofillCredential(databaseId, id, prior.revision) {})
        }
        assertRejectedAfter {
            val before = current(id)
            repository.saveNativeObject(databaseId, before, before.summary.title, "login",
                loginPayload.replace("native-secret", "new-secret"))
        }
        assertRejectedAfter {
            val previousFolder = current(id).summary.collectionId
            repository.withVaultForSync(databaseId) { _, vault ->
                val folder = UUID.randomUUID().toString()
                vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-move", listOf(
                    MdbxWriteCommand.CreateProject(folder, "New folder"),
                    MdbxWriteCommand.MoveEntry(id, previousFolder, folder)))
            }
        }
        val beforeVersion = snapshot().credentials.single()
        repository.withVaultForSync(databaseId) { _, vault ->
            val original = vault.getObjectSummary(id)!!
            vault.updateObject(original.collectionId, id, "login", original.title, loginPayload, 2u)
        }
        assertNull(repository.readAutofillCredential(databaseId, id, beforeVersion.revision) {})
        val changedType = login(loginPayload, "Changed type")
        val beforeType = snapshot().credentials.single { it.objectId == changedType }
        val typeOriginal = current(changedType)
        // Native type IDs are immutable through UpdateEntry. Verify the engine's rejection
        // and preservation before testing a supported mutation of the login payload subtype.
        val typeFailure = runCatching {
            repository.saveNativeObject(databaseId, typeOriginal, typeOriginal.summary.title, "api-token", typeOriginal.payload)
        }.exceptionOrNull()
        assertTrue("The engine must reject native type mutation", typeFailure is uniffi.mdbx_ffi.MdbxFfiException.Storage)
        assertTrue(typeFailure?.message.orEmpty().contains("cannot be updated"))
        assertEquals(typeOriginal, current(changedType))
        assertEquals(beforeType, repository.readAutofillCredential(databaseId, changedType, beforeType.revision) {})
        repository.saveNativeObject(databaseId, typeOriginal, typeOriginal.summary.title, "login",
            org.json.JSONObject(typeOriginal.payload).put("login_type", "WIFI").toString())
        assertNull(repository.readAutofillCredential(databaseId, changedType, beforeType.revision) {})
        assertFalse(snapshot().credentials.any { it.objectId == changedType })
        val other = login(loginPayload, "Deleted")
        val beforeDelete = snapshot().credentials.single { it.objectId == other }
        repository.deleteNativeObject(databaseId, current(other))
        assertNull(repository.readAutofillCredential(databaseId, other, beforeDelete.revision) {})
    } }

    @Test fun requestCancellationAndGlobalLockRevokeInFlightResults() = runBlocking { fixture {
        repeat(3) { login(loginPayload, "Synthetic $it") }
        var checks = 0
        assertTrue(runCatching { snapshot {
            if (++checks == 9) throw CancellationException("Synthetic cancelled request")
        } }.exceptionOrNull() is CancellationException)
        checks = 0
        assertTrue(runCatching { snapshot {
            if (++checks == 9) SecurityManager.clearRuntimeUnlockCache()
        } }.isFailure)
        assertEquals(3, snapshot().credentials.size)
    } }

    @Test fun replacingVaultAtSameRegisteredPathRejectsOldSelection() = runBlocking { fixture {
        val id = login(loginPayload)
        val before = snapshot().credentials.single()
        val replacement = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        try {
            java.nio.file.Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertNull(repository.readAutofillCredential(databaseId, id, before.revision) {})
            assertTrue(snapshot().credentials.isEmpty())
        } finally { repository.deleteOwnedVaultFile(replacement) }
    } }

    @Test fun changingRegisteredCredentialsRevokesSelectionAndMidReadConfiguration() = runBlocking { fixture {
        val id = login(loginPayload)
        val before = snapshot().credentials.single()
        val dao = room.localMdbxDatabaseDao()
        val original = dao.getDatabaseById(databaseId)!!
        dao.updateDatabase(original.copy(encryptedPassword = security.encryptDataLegacyCompat(password)))
        assertNull(repository.readAutofillCredential(databaseId, id, before.revision) {})
        var changed = false
        var checks = 0
        assertTrue(runCatching { snapshot {
            if (++checks == 8 && !changed) {
                changed = true
                runBlocking { dao.updateDatabase(dao.getDatabaseById(databaseId)!!.copy(
                    encryptedPassword = security.encryptDataLegacyCompat(password))) }
            }
        } }.isFailure)
        assertTrue(changed)
    } }

    @Test fun bindingRefusesMutationAndPreservesUnknownPayloadAndAttachment() = runBlocking { fixture {
        val bytes = "synthetic native attachment".toByteArray()
        val id = repository.saveNativeObject(databaseId, null, "Native attachment", "login", loginPayload,
            listOf(NativeApiTokenUpload("synthetic.txt", "text/plain") { bytes.inputStream() }))
        val before = current(id)
        val credential = snapshot().credentials.single()
        assertFalse(repository.patchAutofillUriBinding(databaseId, id, credential.revision,
            "https://changed.test", "new.package", "New app") {})
        assertTrue(repository.patchAutofillUriBinding(databaseId, id, credential.revision,
            "https://native.test", null, null) {})
        val after = current(id)
        assertEquals(before, after)
        assertArrayEquals(bytes, repository.readNativeAttachment(databaseId, after, after.attachmentRecords.single().id))
    } }

    @Test fun pagedSnapshotReadsEveryNativeLoginWithoutMirrorWrites() = runBlocking { fixture {
        val expected = (0 until 425).associate { UUID.randomUUID().toString() to "native-$it" }
        repository.withVaultForSync(databaseId) { _, vault ->
            val folder = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
            expected.map { (id, username) ->
                MdbxWriteCommand.CreateEntry(id, folder, "login", username,
                    """{"username":"$username","password_plain":"synthetic-secret"}""")
            }.chunked(200).forEach { commands ->
                vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-autofill-batch", commands)
            }
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val snapshot = snapshot()
        android.util.Log.i("MdbxAutofillTest", "native_snapshot_count=${snapshot.credentials.size} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}")
        assertEquals(expected, snapshot.credentials.associate { it.objectId to it.entry.username })
        assertTrue(room.passwordEntryDao().getAllPasswordEntries().first().none { it.mdbxDatabaseId == databaseId })
        val last = snapshot.credentials.last()
        assertEquals(last, repository.readAutofillCredential(databaseId, last.objectId, last.revision) {})
    } }
}
