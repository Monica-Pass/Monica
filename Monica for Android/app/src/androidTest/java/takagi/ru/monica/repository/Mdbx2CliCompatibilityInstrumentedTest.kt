package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import uniffi.mdbx_ffi.MdbxTigaMode
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.createVaultWithTigaMode
import uniffi.mdbx_ffi.createPortableBackup
import uniffi.mdbx_ffi.openVault
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class Mdbx2CliCompatibilityInstrumentedTest {
    @Test
    fun realCliVaultPreservesItsEntryThroughAndroidWritesAndPortableReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val room = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, room.localMdbxDatabaseDao(), security)
        val directory = File(context.filesDir, "mdbx2").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.mdbx")
        val password = "Synthetic Android CLI roundtrip 20260926!"
        var databaseId = 0L
        try {
            instrumentation.context.assets.open("mdbx/cli-origin-20260926.mdbx").use { input ->
                file.outputStream().use(input::copyTo)
            }
            databaseId = room.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                name = "Real CLI roundtrip fixture", filePath = file.absolutePath, workingCopyPath = file.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                storageLocation = MdbxStorageLocation.INTERNAL.name, encryptedPassword = security.encryptData(password),
            ))
            val foreign = repository.readStoredEntries(databaseId).single { !it.deleted }
            assertEquals("CLI 原生条目", foreign.title)
            assertTrue("CLI token payload must be readable", foreign.payloadJson.contains("synthetic-interop-token-no-service-access"))
            val original = PasswordEntry(id = 987654322, title = "Android native entry", username = "fixture-user",
                password = "synthetic-android-secret", website = "https://example.invalid/", mdbxDatabaseId = databaseId)
            repository.upsertPassword(original)
            repository.upsertPassword(original.copy(username = "updated-android-user", notes = "Line 1\r\n中文备注"))
            val reopened = Mdbx2Repository(context, room.localMdbxDatabaseDao(), security)
                .readStoredEntries(databaseId).filter { !it.deleted }
            assertEquals(2, reopened.size)
            assertEquals(foreign, reopened.single { it.entryId == foreign.entryId })
            val androidEntry = reopened.single { it.title == original.title }
            val payload = JSONObject(androidEntry.payloadJson)
            assertEquals("updated-android-user", payload.getString("username"))
            assertEquals("synthetic-android-secret", payload.getString("password_plain"))
            assertEquals("Line 1\r\n中文备注", payload.getString("notes"))
            // Keep native 0600 files in internal storage so adb run-as can retrieve the fixture.
            val outputDirectory = File(context.filesDir, "native-database-roundtrip").apply { mkdirs() }
            val output = File(outputDirectory, "cli-after-android.mdbx")
            if (output.exists()) check(output.delete())
            createPortableBackup(file.absolutePath, output.absolutePath)
            val native = openVault(output.absolutePath, password, "independent-android-reader")
            try { assertEquals(2, native.listCollectionSummaries(100u, null).items.sumOf {
                native.listEntries(it.collectionId, null).size
            }) } finally { native.close() }
        } finally {
            if (databaseId != 0L) room.localMdbxDatabaseDao().deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(file)
        }
    }

    @Test
    fun cliVaultWithoutAndroidRootCanCreateEntriesWithoutLosingExistingCollections() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, room.localMdbxDatabaseDao(), security)
        val directory = File(context.filesDir, "mdbx2").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.mdbx")
        val password = "cli-compatibility-fixture"
        val foreignProject = UUID.randomUUID().toString()
        Mdbx2NativeRuntime.ensureLoaded()
        val cliVault = createVaultWithTigaMode(file.absolutePath, password, "cli-fixture", MdbxTigaMode.SKY)
        val rootId = Mdbx2VaultSessionExecutor.rootProjectId(cliVault.info().vaultId)
        try {
            cliVault.executeWriteOperation(UUID.randomUUID().toString(), "cli-initialize", listOf(
                MdbxWriteCommand.CreateProject(foreignProject, "Existing CLI collection")
            ))
            assertFalse(cliVault.listCollectionSummaries(200u, null).items.any { it.collectionId == rootId })
        } finally {
            cliVault.close()
        }
        var databaseId = 0L
        try {
            databaseId = room.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                name = "CLI compatibility fixture", filePath = file.absolutePath,
                workingCopyPath = file.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                storageLocation = MdbxStorageLocation.INTERNAL.name,
                encryptedPassword = security.encryptData(password),
            ))
            repository.upsertPassword(PasswordEntry(id = 987654321, title = "New Android entry",
                website = "", username = "fixture", password = "fixture-secret", mdbxDatabaseId = databaseId))
            repository.upsertPassword(PasswordEntry(id = 987654321, title = "New Android entry",
                website = "", username = "updated", password = "updated-secret", mdbxDatabaseId = databaseId))
            assertEquals("New Android entry", repository.readStoredEntries(databaseId).single { !it.deleted }.title)
            val reopened = openVault(file.absolutePath, password, "cli-fixture")
            try {
                val projects = reopened.listCollectionSummaries(200u, null).items
                assertTrue(projects.any { it.collectionId == foreignProject })
                assertTrue(projects.any { it.collectionId == rootId })
                assertEquals(1, projects.count { it.collectionId == rootId })
            } finally {
                reopened.close()
            }
        } finally {
            if (databaseId != 0L) room.localMdbxDatabaseDao().deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(file)
        }
    }
}
