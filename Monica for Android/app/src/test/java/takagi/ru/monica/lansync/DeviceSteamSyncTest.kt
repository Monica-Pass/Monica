package takagi.ru.monica.lansync

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.steam.data.SteamAccountRepository
import takagi.ru.monica.steam.data.SteamDatabase
import takagi.ru.monica.steam.importer.SteamMaFileParser
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [
    DeviceVaultProviderTest.SecureStoreShadow::class, DeviceVaultProviderTest.PreparedStoreShadow::class, DeviceVaultProviderTest.SecurityShadow::class
])
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSteamSyncTest {
    companion object {
        @JvmStatic @BeforeClass fun nativeLibrary() = DeviceVaultProviderTest.loadNative()
        const val SECRET = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA="
        val RAW = """{"steamid":"76561198000000000","account_name":"fixture","shared_secret":"$SECRET","identity_secret":"$SECRET","device_id":"android:fixture","revocation_code":"R00000","token_gid":"123","access_token":"synthetic-access","refresh_token":"synthetic-refresh","steamLoginSecure":"76561198000000000||synthetic-access","future_field":{"keep":true}}"""
    }
    private val fixtures = mutableListOf<Fixture>()
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory get() = File(context.cacheDir, "steam-sync-${UUID.randomUUID()}")

    private inner class Fixture {
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.build()
        val steam = Room.inMemoryDatabaseBuilder(context, SteamDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.build()
        val security = SecurityManager(context)
        val preferences = DeviceSyncPreferenceStore(context, security, "steam-fixture-${UUID.randomUUID()}")
        val provider = DevicePasswordSyncProvider(context, security, preferences, database,
            File(directory, "replica"), steam)
        val repository = SteamAccountRepository(steam.steamAccountDao(), security)
        suspend fun seed() = repository.upsertFromMaFile(SteamMaFileParser().parse(RAW))
    }
    private fun fixture() = Fixture().also(fixtures::add)
    private suspend fun send(from: Fixture, to: Fixture): DevicePasswordExport {
        val export = from.provider.prepare(directory)
        to.provider.apply(export.directory, JSONObject().put("pairing", JSONObject().put("content_key", export.contentKey)))
        return export
    }

    @Before fun setup() { Dispatchers.setMain(Dispatchers.Unconfined); SessionManager.markUnlocked() }
    @After fun cleanup() = runBlocking {
        fixtures.forEach { it.provider.pause(); it.steam.close(); it.database.close() }
        SessionManager.markLocked(); Dispatchers.resetMain()
    }

    @Test fun roundTripPreservesSteamFieldsFoldersAndUnknownDataWithoutDuplicating() = runBlocking { withTimeout(60000) {
        val a = fixture(); val b = fixture()
        val id = a.seed()
        val folderId = a.database.categoryDao().insert(Category(name = "Steam accounts"))
        a.repository.moveToCategory(id, folderId)
        val export = send(a, b)
        assertFalse(File(export.directory, "changes.mdbxsync").readBytes().toString(Charsets.ISO_8859_1).contains(SECRET))
        val received = b.repository.getAccounts().single()
        assertEquals(SECRET, received.sharedSecret)
        assertEquals(SECRET, received.identitySecret)
        assertEquals("synthetic-refresh", received.refreshToken)
        assertEquals("synthetic-access", received.accessToken)
        assertEquals("R00000", received.revocationCode)
        assertTrue(JSONObject(received.rawSteamGuardJson).getJSONObject("future_field").getBoolean("keep"))
        assertEquals("Steam accounts", b.database.categoryDao().getCategoryById(requireNotNull(received.categoryId))?.name)
        assertTrue(b.database.secureItemDao().getDeviceSyncLocalItems().isEmpty())
        send(a, b); send(b, a)
        assertEquals(id, a.repository.getAccounts().single().id)
        assertEquals(received.id, b.repository.getAccounts().single().id)
        b.repository.updateDisplayName(received.id, "Renamed remotely")
        b.repository.moveToCategory(received.id, null)
        b.repository.updateSessionTokens(received.id, "refreshed-access", "refreshed-refresh", null)
        send(b, a)
        val updated = a.repository.getAccounts().single()
        assertEquals("Renamed remotely", updated.displayName)
        assertEquals("refreshed-refresh", updated.refreshToken)
        assertNull(updated.categoryId)
    } }

    @Test fun sameSteamIdCopiesRemainIndependentAndRemoteDeletionDoesNotResurrect() = runBlocking { withTimeout(60000) {
        val a = fixture(); val b = fixture()
        val original = a.seed()
        a.repository.insertCopy(requireNotNull(a.repository.getAccount(original)), null)
        val stale = send(a, b)
        assertEquals(2, b.repository.getAccounts().size)
        a.repository.delete(original)
        send(a, b)
        assertEquals(1, b.repository.getAccounts().size)
        b.provider.apply(stale.directory, JSONObject().put("pairing", JSONObject().put("content_key", stale.contentKey)))
        assertEquals(1, b.repository.getAccounts().size)
    } }

    @Test fun concurrentEditsKeepBothValuesAndConverge() = runBlocking { withTimeout(60000) {
        val a = fixture(); val b = fixture()
        a.seed(); send(a, b); send(b, a)
        a.repository.updateDisplayName(a.repository.getAccounts().single().id, "Edited on A")
        b.repository.updateDisplayName(b.repository.getAccounts().single().id, "Edited on B")
        send(a, b); send(b, a); send(a, b)
        val namesA = a.repository.getAccounts().map { it.displayName }.toSet()
        val namesB = b.repository.getAccounts().map { it.displayName }.toSet()
        assertEquals(setOf("Edited on A", "Edited on B"), namesA.map { it.removeSuffix(" [Sync conflict]") }.toSet())
        assertTrue(namesA.any { it.endsWith(" [Sync conflict]") })
        assertEquals(namesA, namesB)
    } }

    @Test fun sharedLocalFolderWithBitwardenLinkCanPrepareAndTransfer() = runBlocking { withTimeout(60000) {
        val a = fixture(); val b = fixture()
        val categoryId = a.database.categoryDao().insert(Category(name = "Shared local folder",
            bitwardenVaultId = 42L, bitwardenFolderId = "external-folder"))
        val id = a.seed()
        a.repository.moveToCategory(id, categoryId)
        send(a, b)
        val folder = b.database.categoryDao().getCategoryById(requireNotNull(b.repository.getAccounts().single().categoryId))!!
        assertEquals("Shared local folder", folder.name)
        assertNull(folder.bitwardenVaultId)
        assertNull(folder.bitwardenFolderId)
        assertEquals(42L, a.database.categoryDao().getCategoryById(categoryId)?.bitwardenVaultId)
    } }

    @Test fun activeAccountSelectionDoesNotCreateNewSyncRevision() = runBlocking { withTimeout(60000) {
        val a = fixture()
        val id = a.seed()
        val other = a.repository.insertCopy(requireNotNull(a.repository.getAccount(id)), null)
        val before = a.provider.revision()
        a.repository.select(other)
        assertEquals(before, a.provider.revision())
    } }

    @Test fun invalidSteamPayloadIsRejectedWithoutLeakingInput() {
        val data = JSONObject().put("title", "Fixture").put("folder", "").put("entry_type", "steam-mafile")
            .put("payload", JSONObject().put("mafile_json", "{synthetic-private-token-invalid-json}"))
        try { DeviceVaultShape.validate(data); fail("Malformed Steam data must fail before applying") }
        catch (error: LanSyncException) {
            assertEquals("invalid_vault_payload", error.code)
            assertFalse(error.toString().contains("private-token"))
        }
    }

    @Test fun lockedVaultDoesNotExportSteamSecrets() = runBlocking { withTimeout(60000) {
        val a = fixture(); a.seed(); SessionManager.markLocked()
        try { a.provider.prepare(directory); fail("A locked vault must not export Steam") }
        catch (error: IllegalStateException) { assertEquals("device_sync_locked", error.message) }
        assertEquals(1, a.repository.getAccounts().size)
    } }
}
