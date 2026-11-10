package takagi.ru.monica.ui.screens

import android.app.Application
import android.content.Intent
import android.database.Cursor
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.keepass.*
import takagi.ru.monica.security.*
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Actual MainActivity/NavHost route, only the already initialized owned user13.
 * The old standalone native-manager test is retained separately. This exercises
 * today's unified manager: folder -> root -> database list -> source screen.
 * Does not alter FLAG_SECURE or claim screenshot acceptance of secure windows.
 */
class ToolchainKeePassNavigationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val password = "Synthetic toolchain lifecycle 326!"
    private var scenario: ActivityScenario<MainActivity>? = null

    @Test fun actualMainNavigationReturnsToScrolledKeePassSourceWithoutChangingFiles() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(Process.myUid() / 100_000 == 13 && args.getString("toolchainNavigationUser") == "13")
        check(context.packageName in setOf("takagi.ru.monica.bwsync326", "takagi.ru.monica.bwsync326fd"))
        check(args.getString("toolchainTargetApkSha256") == sha(File(context.applicationInfo.sourceDir)))
        val ownerFile = File(context.filesDir, "toolchain-db-ui-owner.json")
        val ownerBytes = ownerFile.readBytes()
        val owner = JSONObject(ownerBytes.decodeToString())
        check(owner.getString("task") == "20261109-android-toolchain" && owner.getInt("uid") == Process.myUid())
        check(owner.getString("name") == "Monica_Toolchain326_Ui_20261110" && owner.getString("package") == context.packageName)
        check(owner.getBoolean("initialized") && !owner.getBoolean("initAllowed"))
        check(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
        val security = SecurityManager(context)
        check(security.isMasterPasswordSet() && security.verifyMasterPassword(password))
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localKeePassDatabaseDao()
        check(dao.getAllDatabasesSync().isEmpty()) { "Refuse pre-existing KDBX registrations" }
        val originalRows = rows(room)
        val settings = SettingsManager(context)
        @Suppress("UNCHECKED_CAST")
        val settingsStore = SettingsManager::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
            .get(settings) as DataStore<Preferences>
        val originalSettings = settingsStore.data.first()
        val originalTimeout = settings.settingsFlow.first().autoLockMinutes
        val model = LocalKeePassViewModel(context.applicationContext as Application, dao, security)
        val prefix = "toolchain_nav_${UUID.randomUUID().toString().replace('-', '_')}_"
        val owned = mutableListOf<LocalKeePassDatabase>()
        val files = linkedMapOf<File, String>()
        var primary: Throwable? = null
        try {
            settings.updateDisablePasswordVerification(false)
            settings.updateBiometricEnabled(false)
            settings.updateAutoLockMinutes(5)
            settings.updateQuickSetupCompleted(true)
            SessionManager.updateAutoLockTimeout(5)
            SessionManager.markUnlocked()
            repeat(40) { index ->
                val name = prefix + index.toString().padStart(2, '0')
                model.clearOperationState()
                model.createDatabase(name, password, KeePassStorageLocation.INTERNAL,
                    creationOptions = KeePassDatabaseCreationOptions(kdfAlgorithm = KeePassKdfAlgorithm.AES_KDF, transformRounds = 100))
                val result = withTimeout(20_000) { model.operationState.first {
                    it is LocalKeePassViewModel.OperationState.Success || it is LocalKeePassViewModel.OperationState.Error
                } }
                check(result is LocalKeePassViewModel.OperationState.Success) { "Owned KDBX setup failed" }
                owned += dao.getAllDatabasesSync().single { it.name == name }
            }
            val target = owned[36]
            val snapshot = model.openNativeBrowser(target.id).getOrThrow()
            model.createNativeGroup(target.id, snapshot.rootGroup.identity.groupUuid,
                "Synthetic empty folder", snapshot.sourceRevision.sha256).getOrThrow()
            val updated = model.openNativeBrowser(target.id).getOrThrow()
            model.createNativeEntry(target.id, updated.rootGroup.identity.groupUuid,
                listOf(KeePassFieldChange("Title", "Synthetic navigation login"),
                    KeePassFieldChange("UserName", "synthetic@example.invalid"),
                    KeePassFieldChange("Password", "Synthetic navigation secret", protected = true)),
                updated.sourceRevision.sha256).getOrThrow()
            owned.forEach { row ->
                val file = File(context.filesDir, row.filePath).canonicalFile
                check(file.toPath().startsWith(File(context.filesDir, "keepass").canonicalFile.toPath()) && file.name.startsWith(prefix))
                files[file] = sha(file)
            }
            val credentialRows = dao.getAllDatabasesSync().associate { it.id to listOf(it.filePath, it.encryptedPassword, it.keyFileUri, it.keyFileInternalPath, it.keyFileFingerprint) }
            SessionManager.markLocked()
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitText(R.string.enter_master_password)
            compose.onNode(hasSetTextAction()).performTextInput(password)
            compose.onNodeWithText(label(R.string.unlock)).performClick()
            waitText(R.string.nav_settings_short)
            compose.onAllNodesWithText(label(R.string.nav_settings_short)).onFirst().performClick()
            compose.onNodeWithText(label(R.string.sync_backup_title)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.local_keepass_database)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.mdbx_ui_local_databases)).performClick()
            waitTag("keepass_database_grid")
            compose.onNodeWithTag("keepass_database_grid").performScrollToNode(hasTestTag("keepass_database_${target.id}"))
            compose.onNodeWithTag("keepass_database_${target.id}").assertIsDisplayed().performClick()
            compose.onNodeWithTag("keepass_browse_database").performClick()
            waitTag("manager-pane-0")
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Synthetic navigation login").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Synthetic navigation login").assertIsDisplayed()
            compose.onNodeWithText("Synthetic empty folder").performClick()
            compose.onNodeWithText("Synthetic navigation login").assertDoesNotExist()
            Espresso.pressBack()
            compose.onNodeWithText("Synthetic navigation login").assertIsDisplayed()
            Espresso.pressBack()
            waitTag("manager-store-0-KEEPASS:${target.id}", scroll = "manager-list-0")
            compose.onNodeWithTag("manager-store-0-KEEPASS:${target.id}").assertIsDisplayed()
            Espresso.pressBack()
            waitTag("keepass_database_grid")
            compose.onNodeWithTag("keepass_database_${target.id}").assertIsDisplayed()
            compose.onNodeWithTag("keepass_create_database").assertIsDisplayed()
            files.forEach { (file, before) -> assertEquals("Navigation must not rewrite ${file.name}", before, sha(file)) }
            assertEquals(credentialRows, dao.getAllDatabasesSync().associate { it.id to listOf(it.filePath, it.encryptedPassword, it.keyFileUri, it.keyFileInternalPath, it.keyFileFingerprint) })
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("toolchainNavigation", "PASS actual MainActivity login/settings/source/browse/folder/root/list/source; 40 distinct KDBX SHA and saved credentials unchanged")
            })
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            val failures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { runCatching(action).exceptionOrNull()?.let(failures::add) }
            cleanup { scenario?.close(); scenario = null }
            runCatching { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }.exceptionOrNull()?.let(failures::add)
            val registered = dao.getAllDatabasesSync().filter { it.name.startsWith(prefix) }
            registered.forEach { row ->
                cleanup {
                    val file = File(context.filesDir, row.filePath).canonicalFile
                    check(file.toPath().startsWith(File(context.filesDir, "keepass").canonicalFile.toPath()) && file.name.startsWith(prefix))
                    files[file]?.let { check(it == sha(file)) }
                    KeePassKdbxService.invalidateProcessCache(row.id)
                    runBlocking { room.keepassPendingChangeDao().deleteByDatabase(row.id); dao.deleteDatabaseById(row.id) }
                    check(!file.exists() || file.delete())
                }
            }
            runCatching { settingsStore.updateData { originalSettings }; check(settingsStore.data.first().asMap() == originalSettings.asMap()) }.exceptionOrNull()?.let(failures::add)
            cleanup { SessionManager.updateAutoLockTimeout(originalTimeout); SessionManager.markLocked() }
            cleanup { assertEquals(originalRows, rows(room)); assertArrayEquals(ownerBytes, ownerFile.readBytes()) }
            if (failures.isNotEmpty()) {
                val failure = failures.first(); failures.drop(1).forEach(failure::addSuppressed)
                if (primary == null) throw failure else primary!!.addSuppressed(failure)
            }
        }
    }

    private fun label(id: Int): String { var value = ""; checkNotNull(scenario).onActivity { value = it.getString(id) }; return value }
    private fun waitText(id: Int) { compose.waitUntil(20_000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitTag(tag: String, scroll: String? = null) {
        if (scroll != null) compose.onNodeWithTag(scroll).performScrollToNode(hasTestTag(tag))
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun rows(room: PasswordDatabase): String {
        val output = JSONObject()
        for (table in listOf("password_entries", "secure_items", "custom_fields", "password_history_entries", "passkeys", "attachments", "local_keepass_databases", "local_mdbx_databases", "bitwarden_vaults")) {
            val rows = JSONArray()
            room.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    cursor.columnNames.sorted().forEach { name ->
                        val i = cursor.getColumnIndexOrThrow(name)
                        row.put(name, when (cursor.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(cursor.getBlob(i))
                            else -> cursor.getString(i)
                        })
                    }; rows.put(row)
                }
            }; output.put(table, rows)
        }; return output.toString()
    }
}
