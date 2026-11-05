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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.steam.data.SteamDatabase
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [DeviceVaultProviderTest.SecureStoreShadow::class, DeviceVaultProviderTest.PreparedStoreShadow::class, DeviceVaultProviderTest.SecurityShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSelectedDatabaseTest {
    @Test fun local_default_ignores_other_vaults_and_selected_target_is_isolated() = runBlocking { withTimeout(90_000) {
        DeviceNativePasskeyPreparationTest.hostEngine(); DeviceVaultProviderTest.loadNative()
        Dispatchers.setMain(Dispatchers.Unconfined); SessionManager.markUnlocked()
        val context = RuntimeEnvironment.getApplication()
        val security = SecurityManager(context)
        val source = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).allowMainThreadQueries().build()
        val destination = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).allowMainThreadQueries().build()
        val steam = Room.inMemoryDatabaseBuilder(context, SteamDatabase::class.java).build()
        val sourceStore = DeviceSyncPreferenceStore(context, security, "source-${UUID.randomUUID()}").also { it.save(DeviceSyncPreferences()) }
        val targetStore = DeviceSyncPreferenceStore(context, security, "target-${UUID.randomUUID()}").also { it.save(DeviceSyncPreferences()) }
        val sender = DevicePasswordSyncProvider(context, security, sourceStore, source, File(context.cacheDir, "scope-source-${UUID.randomUUID()}"), steam)
        val receiver = DevicePasswordSyncProvider(context, security, targetStore, destination, File(context.cacheDir, "scope-target-${UUID.randomUUID()}"), steam)
        try {
            source.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = "Unreadable unrelated vault", filePath = "missing-file",
                engineType = MdbxEngineType.RUST_MDBX2.name, tigaMode = MdbxTigaMode.SKY.name, isDefault = true))
            source.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Selected source", website = "example.test", username = "fixture", password = "scope-secret"))
            destination.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Keep local", website = "", username = "", password = "untouched"))
            val repository = Mdbx2Repository(context, destination.localMdbxDatabaseDao(), security)
            suspend fun native(name: String): Long {
                val file = repository.createInitializedVaultFile(MdbxTigaMode.SKY, "fixture-password")
                return destination.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = name, filePath = file.absolutePath,
                    engineType = MdbxEngineType.RUST_MDBX2.name, tigaMode = MdbxTigaMode.SKY.name,
                    storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                    encryptedPassword = "fixture-password", isOfflineAvailable = true))
            }
            val first = native("First"); val second = native("Second")
            targetStore.save(targetStore.load().apply { databaseId = second })
            val outgoing = sender.prepare(File(context.cacheDir, "scope-out-${UUID.randomUUID()}"))
            receiver.apply(outgoing.directory, JSONObject().put("pairing", JSONObject().put("content_key", outgoing.contentKey)))
            assertEquals(listOf("Keep local"), destination.passwordEntryDao().getDeviceSyncLocalPasswords().map { it.title })
            assertFalse(repository.deviceSyncPasswords(first).any { it.data.optString("entry_type") == "login" })
            assertEquals("Selected source", repository.deviceSyncPasswords(second).single { it.data.optString("entry_type") == "login" }.data.getString("title"))
            val selectedRevision = receiver.revision()
            targetStore.save(targetStore.load().apply { databaseId = first })
            receiver.prepare(File(context.cacheDir, "other-scope-${UUID.randomUUID()}"))
            targetStore.save(targetStore.load().apply { databaseId = second })
            assertEquals(selectedRevision, receiver.revision())
            assertEquals(1, repository.deviceSyncPasswords(second).count { it.data.optString("entry_type") == "login" })
            targetStore.save(targetStore.load().apply { databaseId = Long.MAX_VALUE })
            val failure = try { receiver.revision(); error("Unavailable selection must fail without falling back") } catch (e: LanSyncException) { e }
            assertEquals("sync_database_unavailable", failure.code)
        } finally {
            sender.pause(); receiver.pause(); source.close(); destination.close(); steam.close()
            takagi.ru.monica.repository.Mdbx2NativeReadSessions.clear()
            SessionManager.markLocked(); Dispatchers.resetMain()
        }
    } }
}
