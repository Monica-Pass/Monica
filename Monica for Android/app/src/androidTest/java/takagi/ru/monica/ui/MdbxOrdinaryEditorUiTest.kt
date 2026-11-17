package takagi.ru.monica.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel
import uniffi.mdbx_ffi.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class MdbxOrdinaryEditorUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun ordinaryFormReadsNativeAndSavesWithoutChangingObjectId() = exercise(create = false)
    @Test fun ordinaryNewFormCreatesNativeObjectBeforeProjectingRoom() = exercise(create = true)

    private fun exercise(create: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val user = android.os.Process.myUid() / 100000
        check(user > 0 && InstrumentationRegistry.getArguments().getString("autofillBatchUser") == user.toString())
        check(takagi.ru.monica.security.SecureStorageStartup.prepareWithRetry(context) is takagi.ru.monica.security.SecureStartupResult.Ready)
        val security = SecurityManager(context)
        val master = "Batch328-synthetic-master!"
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(master))
        check(security.unlockVaultWithPassword(master)); SessionManager.markUnlocked()
        Mdbx2NativeReadSessions.updateForeground(true); Mdbx2NativeRuntime.ensureLoaded()
        val db = PasswordDatabase.getDatabase(context)
        val file = File(context.cacheDir, "ordinary-editor-${UUID.randomUUID()}.mdbx")
        val folder = UUID.randomUUID().toString(); val objectId = UUID.randomUUID().toString()
        val vaultPassword = "Ordinary-MDBX-Synthetic!"
        val vault = createVaultWithTigaMode(file.path, vaultPassword, "ordinary-editor-ui", uniffi.mdbx_ffi.MdbxTigaMode.SKY)
        vault.executeWriteOperation(UUID.randomUUID().toString(), "fixture", listOf(
            MdbxWriteCommand.CreateProject(folder, "Synthetic folder"),
            MdbxWriteCommand.CreateEntry(objectId, folder, "login", "Native form title",
                """{"username":"native-user","password_plain":"native-before","notes":"keep","future":{"value":900719925474099312345}}""")))
        vault.close()
        val databaseId = db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = "Ordinary editor synthetic",
            filePath = file.path, storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            engineType = MdbxEngineType.RUST_MDBX2.name, tigaMode = takagi.ru.monica.data.MdbxTigaMode.SKY.name,
            encryptedPassword = security.encryptData(vaultPassword)))
        val originalId = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Stale title", website = "",
            username = "stale", password = security.encryptData("stale"), mdbxDatabaseId = databaseId,
            mdbxFolderId = folder, replicaGroupId = objectId))
        val returned = AtomicInteger(); val visible = mutableStateOf(true)
        val model = withContext(Dispatchers.Main) { PasswordViewModel(
            PasswordRepository(db.passwordEntryDao(), db.categoryDao()), security,
            secureItemRepository = SecureItemRepository(db.secureItemDao()), customFieldRepository = CustomFieldRepository(db.customFieldDao()),
            context = context, localKeePassDatabaseDao = db.localKeePassDatabaseDao(), strings = AppLocaleStringResolver(context)) }
        try {
            compose.setContent { MonicaTheme {
                if (visible.value) AddEditPasswordScreen(model, passwordId = originalId.takeUnless { create },
                    initialStorageExplicit = true, initialMdbxDatabaseId = databaseId, initialMdbxFolderId = folder,
                    onNavigateBack = { returned.incrementAndGet(); visible.value = false })
            } }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("password_editor_title").fetchSemanticsNodes().isNotEmpty() }
            if (create) compose.onNodeWithTag("password_editor_title").performTextReplacement("Created through ordinary form")
            else compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("password_editor_title") and hasText("Native form title")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("password_editor_save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("password_editor_value_0").performTextReplacement("native-after")
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(20000) { returned.get() == 1 }
            val rows = db.passwordEntryDao().getByMdbxDatabaseIdSync(databaseId)
            assertEquals(if (create) 2 else 1, rows.size)
            val saved = if (create) rows.single { it.id != originalId } else rows.single()
            assertEquals("native-after", security.decryptData(saved.password))
            val reopened = openVault(file.path, vaultPassword, "ordinary-editor-independent")
            try {
                val nativeId = saved.replicaGroupId!!
                if (!create) assertEquals(objectId, nativeId)
                val original = readMdbxNativeObject(reopened, objectId)
                assertTrue(original.payload.contains("900719925474099312345"))
                assertTrue(readMdbxNativeObject(reopened, nativeId).payload.contains("native-after"))
                assertEquals(folder, reopened.getObjectSummary(nativeId)!!.collectionId)
                if (create) assertTrue(original.payload.contains("native-before"))
            } finally { reopened.close() }
            suspend fun reimport() {
                val scopedDao = object : LocalMdbxDatabaseDao by db.localMdbxDatabaseDao() {
                    override fun getAllDatabases() = kotlinx.coroutines.flow.flowOf(emptyList<LocalMdbxDatabase>())
                    override suspend fun getAllDatabasesSnapshot() = listOf(requireNotNull(db.localMdbxDatabaseDao().getDatabaseById(databaseId)))
                }
                val application = object : android.app.Application() {
                    init { attachBaseContext(context) }
                    override fun getApplicationContext(): android.content.Context = this
                }
                val store = androidx.lifecycle.ViewModelStore()
                val vm = withContext(Dispatchers.Main) {
                    takagi.ru.monica.viewmodel.MdbxViewModel(application, scopedDao, db.mdbxRemoteSourceDao(),
                        db.passwordEntryDao(), db.secureItemDao(), db.passkeyDao(), db.attachmentDao(), db.customFieldDao(), security)
                        .also { store.put("native-reimport", it); it.activateMdbxDatabase(databaseId) }
                }
                try { withTimeout(30000) { while (vm.vaultDiagnostics.value[databaseId] == null) delay(50) } }
                finally { withContext(Dispatchers.Main) { vm.forgetActiveMdbxDatabaseIf(databaseId); store.clear() } }
            }
            reimport()
            val imported = db.passwordEntryDao().getByMdbxDatabaseIdSync(databaseId)
            assertEquals(rows.map { it.id }.toSet(), imported.map { it.id }.toSet())
            assertEquals(saved.replicaGroupId, imported.single { it.id == saved.id }.replicaGroupId)
            assertEquals(folder, imported.single { it.id == saved.id }.mdbxFolderId)
            assertEquals("native-after", security.decryptData(imported.single { it.id == saved.id }.password))
            val deleter = openVault(file.path, vaultPassword, "external-delete")
            try { deleter.executeWriteOperation(UUID.randomUUID().toString(), "fixture-delete", listOf(
                MdbxWriteCommand.DeleteEntry(saved.replicaGroupId!!, folder))) }
            finally { deleter.close() }
            reimport()
            assertTrue(db.passwordEntryDao().getPasswordEntryById(saved.id)!!.isDeleted)
            val verify = openVault(file.path, vaultPassword, "no-resurrection-check")
            try { assertTrue(verify.getObjectSummary(saved.replicaGroupId!!)!!.deleted) }
            finally { verify.close() }
        } finally {
            compose.runOnIdle { visible.value = false }
            model.viewModelScope.cancel(); Mdbx2NativeReadSessions.updateForeground(false)
            db.passwordEntryDao().getByMdbxDatabaseIdSync(databaseId).forEach { db.passwordEntryDao().deletePasswordEntryById(it.id) }
            db.localMdbxDatabaseDao().deleteDatabaseById(databaseId)
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm")).forEach { it.delete() }
        }
    }
}
