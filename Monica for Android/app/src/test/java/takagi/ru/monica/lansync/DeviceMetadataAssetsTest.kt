package takagi.ru.monica.lansync

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [DeviceVaultProviderTest.SecureStoreShadow::class, DeviceVaultProviderTest.PreparedStoreShadow::class, DeviceVaultProviderTest.SecurityShadow::class])
class DeviceMetadataAssetsTest {
    private fun descriptor(bytes: ByteArray, identity: String) = JSONObject()
        .put("id", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        .put("attachment_id", identity).put("file_name", "同名文件.bin").put("media_type", "application/octet-stream")
        .put("size", bytes.size).put("future_asset_field", JSONObject().put("version", 9))
    private fun snapshot(owner: String) = DevicePasswordSnapshot(owner, owner, "metadata", "", false, JSONObject())

    @Test fun replacement_omission_empty_and_purge_are_owner_scoped_and_survive_database_reopen() = runBlocking {
        // Keep the native Windows SQLite path below MAX_PATH; Robolectric embeds
        // the long test name in its ordinary application data directory.
        val directory = java.nio.file.Files.createTempDirectory("mda-").toFile()
        val context = object : android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getDatabasePath(name: String): File = File(directory, name)
        }
        val security = SecurityManager(context)
        val name = "metadata-assets-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, PasswordDatabase::class.java, name).allowMainThreadQueries().build()
        val storage = AttachmentStorage(context, AttachmentStorage.Scope.DEVICE_METADATA)
        val first = ByteArray(280_017) { (it % 251).toByte() }; val second = ByteArray(180_003) { (it % 241).toByte() }
        try {
            var store = DeviceMetadataAssets(context, database, security)
            store.transaction { adopt("owner-a", JSONObject().put("assets", JSONArray().put(descriptor(first, "stable-a"))), false) { first.copyOf() } }
            store.transaction { adopt("owner-b", JSONObject().put("assets", JSONArray().put(descriptor(first, "stable-b"))), false) { first.copyOf() } }
            val original = store.snapshot(snapshot("owner-a")).assets.single().metadata!!
            assertFalse(File(context.filesDir, "device_sync_metadata_attachments/${original.path}").readBytes().contentEquals(first))
            store.transaction { adopt("owner-a", JSONObject().put("assets", JSONArray().put(descriptor(second, "stable-a"))), false) { second.copyOf() } }
            assertFalse(storage.exists(original.path))
            val omitted = store.transaction { adopt("owner-a", JSONObject().put("opaque", "preserved"), false) { error("Must not fetch omitted assets") } }
            assertEquals("stable-a", omitted.getJSONArray("assets").getJSONObject(0).getString("attachment_id"))
            database.close()
            database = Room.databaseBuilder(context, PasswordDatabase::class.java, name).allowMainThreadQueries().build()
            store = DeviceMetadataAssets(context, database, security)
            val restored = store.snapshot(snapshot("owner-a")).assets.single().metadata!!
            assertEquals(9, restored.descriptor.getJSONObject("future_asset_field").getInt("version"))
            assertArrayEquals(second, store.read(restored))
            assertArrayEquals(first, store.read(store.snapshot(snapshot("owner-b")).assets.single().metadata!!))
            store.transaction { adopt("owner-a", JSONObject().put("assets", JSONArray()), false) { error("No bytes expected") } }
            assertTrue(store.snapshot(snapshot("owner-a")).assets.isEmpty())
            assertFalse(storage.exists(restored.path))
            assertEquals(1, store.snapshot(snapshot("owner-b")).assets.size)
            store.transaction { adopt("owner-b", JSONObject(), true) { error("No bytes expected") } }
            assertTrue(store.snapshot(snapshot("owner-b")).assets.isEmpty())
        } finally { database.close(); directory.deleteRecursively() }
    }

    @Test fun corrupt_second_file_and_late_database_failure_roll_back_new_files_and_preserve_original() = runBlocking {
        val context = RuntimeEnvironment.getApplication(); val security = SecurityManager(context)
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).allowMainThreadQueries().build()
        val store = DeviceMetadataAssets(context, database, security)
        val storage = AttachmentStorage(context, AttachmentStorage.Scope.DEVICE_METADATA)
        val old = ByteArray(513) { 3 }; val replacement = ByteArray(70_019) { 7 }
        try {
            store.transaction { adopt("owner", JSONObject().put("assets", JSONArray().put(descriptor(old, "stable"))), false) { old.copyOf() } }
            val files = storage.listAllBlobs().toSet()
            val before = store.snapshot(snapshot("owner")).assets.single().metadata!!
            val bad = runCatching { store.transaction {
                adopt("owner", JSONObject().put("assets", JSONArray().put(descriptor(replacement, "stable")).put(descriptor(old, "second"))), false) {
                    if(it.getString("attachment_id") == "stable") replacement.copyOf() else byteArrayOf(0)
                }
            } }.exceptionOrNull()
            assertTrue(bad is LanSyncException)
            assertEquals(files, storage.listAllBlobs().toSet())
            assertArrayEquals(old, store.read(store.snapshot(snapshot("owner")).assets.single().metadata!!))
            val late = runCatching { store.transaction {
                adopt("owner", JSONObject().put("assets", JSONArray().put(descriptor(replacement, "stable"))), false) { replacement.copyOf() }
                database.openHelper.writableDatabase.execSQL("INSERT INTO deliberately_missing_table VALUES(1)")
            } }.exceptionOrNull()
            assertNotNull(late)
            assertEquals(files, storage.listAllBlobs().toSet())
            assertEquals(before.path, store.snapshot(snapshot("owner")).assets.single().metadata!!.path)
            assertArrayEquals(old, store.read(before))
        } finally {
            store.transaction { adopt("owner", JSONObject(), true) { error("No bytes expected") } }
            database.close()
        }
    }
}
