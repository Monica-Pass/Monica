package takagi.ru.monica.autofill_ng

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.Mdbx2NativeRuntime
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import uniffi.mdbx_ffi.MdbxTigaMode as NativeMode
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.createVaultWithTigaMode
import uniffi.mdbx_ffi.openVault

/** All files/registrations are synthetic and private to an explicitly owned Android user. */
abstract class MdbxAutofillFixture {
    protected val context = InstrumentationRegistry.getInstrumentation().targetContext
    protected lateinit var database: PasswordDatabase
    protected lateinit var security: SecurityManager
    protected val active = AtomicBoolean(true)
    private val files = mutableListOf<File>()
    private val sources = mutableListOf<AutofillPasswordSource>()
    protected val vaultPassword = "Mdbx-source328-synthetic!"
    protected data class Fixture(val databaseId: Long, val file: File, val objectId: String, val folderId: String)

    @Before fun prepare() = runBlocking {
        val user = android.os.Process.myUid() / 100000
        check(user > 0 && InstrumentationRegistry.getArguments().getString("autofillBatchUser") == user.toString())
        security = SecurityManager(context)
        val password = "Batch328-synthetic-master!"
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(password))
        SessionManager.attachAppContext(context)
        SessionManager.markLocked()
        check(security.unlockVaultWithPassword(password))
        assertFalse(SessionManager.isUnlocked.value)
        Mdbx2NativeRuntime.ensureLoaded()
        database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
    }

    @After fun cleanup() {
        active.set(false)
        sources.forEach { it.clear() }
        if (::database.isInitialized) database.close()
        files.forEach { file ->
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
        }
        SecurityManager.clearRuntimeUnlockCache()
    }

    protected fun source(): AutofillPasswordSource = AutofillPasswordSource(context, security,
        SecurityManager.dataReadGeneration.value, active::get, database).also(sources::add)

    protected suspend fun fixture(type: String = "login", payload: String = LOGIN): Fixture {
        val file = File(context.cacheDir, "autofill-source-${UUID.randomUUID()}.mdbx").also(files::add)
        val objectId = UUID.randomUUID().toString()
        val folderId = UUID.randomUUID().toString()
        val vault = createVaultWithTigaMode(file.path, vaultPassword, "autofill-source-test", NativeMode.SKY)
        try {
            vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-fixture", listOf(
                MdbxWriteCommand.CreateProject(folderId, "Synthetic folder"),
                MdbxWriteCommand.CreateEntry(objectId, folderId, type, "Native source record", payload)))
        } finally { vault.close() }
        val id = database.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
            name = "Synthetic native vault", filePath = file.path,
            storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            engineType = MdbxEngineType.RUST_MDBX2.name, tigaMode = MdbxTigaMode.SKY.name,
            encryptedPassword = security.encryptData(vaultPassword)))
        return Fixture(id, file, objectId, folderId)
    }

    protected fun replace(fixture: Fixture, type: String = "login", payload: String) {
        val vault = openVault(fixture.file.path, vaultPassword, "autofill-external-writer")
        try { vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-external-edit", listOf(
            MdbxWriteCommand.UpdateEntry(fixture.objectId, fixture.folderId, type, "Native source record", payload)))
        } finally { vault.close() }
    }

    companion object {
        const val LOGIN = """{"username":"native-user","password_plain":"native-secret","website":"https://native.example"}"""
        const val CARD = """{"kind":"bank_card","item_data":"{\"cardNumber\":\"4111111111111111\",\"cardholderName\":\"Synthetic owner\",\"expiryMonth\":\"12\",\"expiryYear\":\"2030\"}"}"""
    }
}

/** Uses only pre-change APIs so the retained old app can demonstrate both integration gaps. */
class MdbxAutofillLegacyGapInstrumentedTest : MdbxAutofillFixture() {
    @Test fun originalNativeRecordWithoutRoomMirrorIsAvailable() = runBlocking {
        val native = fixture()
        val loaded = source().load()
        assertEquals("Native records must not require a Room copy", 1, loaded.size)
        val row = loaded.single()
        assertEquals(native.databaseId, row.mdbxDatabaseId)
        assertEquals("native-user", row.username)
        assertEquals("native-secret", row.password)
        assertTrue(database.passwordEntryDao().getAllPasswordEntriesSync().isEmpty())
        assertFalse(SessionManager.isUnlocked.value)
    }

    @Test fun unavailableNativeVaultNeverFallsBackToRoomMirror() = runBlocking {
        val native = fixture()
        database.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Stale mirror", website = "https://native.example",
            username = "old", password = "old", mdbxDatabaseId = native.databaseId, replicaGroupId = native.objectId))
        assertTrue(native.file.delete())
        assertTrue("An unreadable original must not reveal its stale Room mirror", source().load().isEmpty())
    }
}

class MdbxAutofillSourceInstrumentedTest : MdbxAutofillFixture() {
    @Test fun selectionResolvesExactNativeObjectAndRejectsExternalRevision() = runBlocking {
        val native = fixture()
        val source = source()
        val row = source.load(AutofillScope.Mdbx(native.databaseId)).single()
        val selected = requireNotNull(source.selection(row))
        assertEquals(NativeAutofillStorage.MDBX, selected.storage)
        assertEquals(native.objectId, selected.entryUuid)
        assertTrue(row.id < 0)
        assertNotEquals(AutofillPasswordSource.nativeId(native.databaseId, native.objectId), row.id)
        assertEquals(row, source.resolve(row))
        assertEquals(row, source().getSelected(selected))
        replace(native, payload = LOGIN.replace("native-secret", "changed-secret"))
        assertNull(source.resolve(row))
        assertNull(source().getSelected(selected))
        assertEquals("changed-secret", source.load(AutofillScope.Mdbx(native.databaseId)).single().password)
    }

    @Test fun localAndBitwardenScopesDoNotOpenUnreadableMdbxVault() = runBlocking {
        val native = fixture()
        assertTrue(native.file.delete())
        database.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Local", website = "", username = "local", password = "local"))
        database.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "BW A", website = "", username = "bw", password = "bw",
            bitwardenVaultId = 71, bitwardenCipherId = "synthetic-item"))
        database.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "BW B", website = "", username = "bw2", password = "bw2",
            bitwardenVaultId = 72, bitwardenCipherId = "synthetic-item2"))
        val source = source()
        assertEquals(listOf("Local"), source.load(localOnly = true).map { it.title })
        assertTrue(source.unavailableMdbxDatabaseIds.isEmpty())
        assertEquals(listOf("BW A"), source.load(AutofillScope.Bitwarden(71)).map { it.title })
        assertTrue(source.unavailableMdbxDatabaseIds.isEmpty())
        assertTrue(source.load(AutofillScope.Mdbx(native.databaseId + 1)).isEmpty())
        assertTrue(source.unavailableMdbxDatabaseIds.isEmpty())
        assertTrue(source.load(AutofillScope.Mdbx(native.databaseId)).isEmpty())
        assertEquals(setOf(native.databaseId), source.unavailableMdbxDatabaseIds)
    }

    @Test fun selectedMdbxDoesNotReadAnotherBrokenMdbxOrReturnItsMirror() = runBlocking {
        val first = fixture()
        val second = fixture()
        assertTrue(second.file.delete())
        database.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Other mirror", website = "", username = "old", password = "old",
            mdbxDatabaseId = second.databaseId, replicaGroupId = second.objectId))
        val source = source()
        assertEquals(listOf(first.databaseId), source.load(AutofillScope.Mdbx(first.databaseId)).map { it.mdbxDatabaseId })
        assertTrue(source.unavailableMdbxDatabaseIds.isEmpty())
        assertTrue(source.load(AutofillScope.Mdbx(second.databaseId)).isEmpty())
        assertNull(source.get(AutofillPasswordSource.mdbxId(first.databaseId, first.objectId)))
    }

    @Test fun lockCancellationAndWrongStorageRejectCapabilities() = runBlocking {
        val native = fixture()
        val source = source()
        val row = source.load(AutofillScope.Mdbx(native.databaseId)).single()
        val selection = requireNotNull(source.selection(row))
        assertNull(source().getSelected(selection.copy(storage = NativeAutofillStorage.KEEPASS)))
        active.set(false)
        assertFalse(source.areCurrent(listOf(row)))
        assertNull(source.resolve(row))
        assertNull(source().getSelected(selection))
        active.set(true)
        SessionManager.markLocked()
        assertFalse(source.isCurrent(row))
        assertNull(source().getSelected(selection))
    }

    @Test fun nativeWalletResolvesCurrentObjectAndRejectsChanges() = runBlocking {
        val native = fixture("card", CARD)
        val source = source()
        assertTrue(source.load(AutofillScope.Mdbx(native.databaseId)).isEmpty())
        val item = source.nativeWalletItems.single()
        assertEquals(ItemType.BANK_CARD, item.itemType)
        assertEquals(native.databaseId, item.mdbxDatabaseId)
        assertTrue(item.id < 0)
        assertEquals(item, source.resolveCurrentWallet(item))
        replace(native, "card", CARD.replace("4111111111111111", "4000000000000002"))
        assertNull(source.resolveCurrentWallet(item))
        source.clear()
        assertFalse(source.isWalletCurrent(item))
        assertTrue(source.nativeWalletItems.isEmpty())
    }

    @Test fun unsupportedNativeTypesStayUnchangedAndDoNotFill() = runBlocking {
        val native = fixture("identity", LOGIN)
        val source = source()
        assertTrue(source.load(AutofillScope.Mdbx(native.databaseId)).isEmpty())
        assertTrue(source.nativeWalletItems.isEmpty())
        val vault = openVault(native.file.path, vaultPassword, "verify-unknown")
        try { assertEquals("identity", vault.getObjectSummary(native.objectId)?.objectTypeId) }
        finally { vault.close() }
    }

    @Test fun fillCanReadWhileUnsafeBindingPatchDoesNotWriteAnything() = runBlocking {
        val native = fixture()
        val source = source()
        val row = source.load(AutofillScope.Mdbx(native.databaseId)).single()
        assertFalse(source.saveUriBinding(row, "org.example.newapp", "other.example"))
        assertEquals(row, source.resolve(row))
        assertTrue(database.passwordEntryDao().getAllPasswordEntriesSync().isEmpty())
        assertFalse(SessionManager.isUnlocked.value)
    }
}
