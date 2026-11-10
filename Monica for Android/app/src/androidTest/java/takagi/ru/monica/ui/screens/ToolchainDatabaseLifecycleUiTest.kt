package takagi.ru.monica.ui.screens

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.repository.Mdbx2VaultSessionExecutor
import takagi.ru.monica.repository.readMdbxNativeObject
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.KeePassUriPermissionState
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.MdbxViewModel
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Opt-in UI acceptance on the newly owned toolchain user 13, never retained user 12.
 * Real production forms and VMs; no password reset, cache-key injection or production seam.
 * First-time fixture setup uses SecurityManager only after the exact owner marker and empty
 * protected tables are proven. This is API fixture setup, not a first-run registration UI test.
 * KDBX removal intentionally keeps the file, matching LocalKeePassScreen's callback.
 * MDBX password rejection below uses the native reader; it is not a password-dialog test.
 */
class ToolchainDatabaseLifecycleUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val fixtureFontScale = when (args.getString("toolchainDatabaseUiFontScale") ?: "1.0") {
        "1.0" -> 1.0f
        "1.5" -> 1.5f
        else -> error("toolchainDatabaseUiFontScale must be 1.0 or 1.5")
    }
    private lateinit var room: PasswordDatabase
    private lateinit var security: SecurityManager
    private val prefix = "toolchain_ui_${UUID.randomUUID().toString().replace('-', '_')}"
    private val password = "Synthetic toolchain lifecycle 326!"
    private val captureDirectory by lazy { File(context.filesDir, "toolchain-db-ui/$prefix").apply { check(mkdirs()) } }
    private var fixtureHost: ComposeView? = null

    @Before fun requireOwnedDisposableUser() = runBlocking {
        check(Process.myUid() / 100_000 == 13) { "Restricted to newly owned toolchain user 13" }
        check(args.getString("toolchainDatabaseUiUser") == "13")
        check(context.packageName in setOf("takagi.ru.monica.bwsync326", "takagi.ru.monica.bwsync326fd"))
        check(args.getString("toolchainDatabaseUi") == "yes")
        val marker = File(context.filesDir, "toolchain-db-ui-owner.json")
        check(marker.isFile) { "Host must establish the new fixture ownership before running" }
        val owner = JSONObject(marker.readText())
        check(owner.getString("name") == "Monica_Toolchain326_Ui_20261110")
        check(owner.getInt("uid") == Process.myUid())
        check(owner.getString("package") == context.packageName)
        check(owner.getString("task") == "20261109-android-toolchain")
        val expected = checkNotNull(args.getString("toolchainTargetApkSha256"))
        check(expected.matches(Regex("[0-9a-f]{64}")) && expected == sha(File(context.applicationInfo.sourceDir)))
        check(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
        security = SecurityManager(context)
        room = PasswordDatabase.getDatabase(context)
        if (!security.isMasterPasswordSet()) {
            check(owner.optBoolean("initAllowed", false) && !owner.optBoolean("initialized", false)) {
                "Existing ownership does not authorize first-time initialization"
            }
            protectedTables.forEach { table ->
                room.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "Refuse initialization with non-empty $table" }
                }
            }
            check(security.setMasterPassword(password)) { "First-time synthetic fixture initialization failed" }
            check(security.isMasterPasswordSet())
            owner.put("initialized", true).put("initAllowed", false)
            val staged = File(context.filesDir, "toolchain-db-ui-owner-${UUID.randomUUID()}.tmp")
            staged.writeText(owner.toString(2))
            check(staged.renameTo(marker)) { "Could not record initialized ownership; do not retry initialization" }
        } else {
            check(owner.optBoolean("initialized", false)) { "Refuse an existing master password without initialized ownership" }
        }
        check(security.verifyMasterPassword(password)) { "Existing synthetic password must authenticate; never reset it" }
        assertEquals("synthetic readiness", security.decryptData(security.encryptData("synthetic readiness")))
        // These screens have automatic name repair/pruning. Do not expose unrelated registrations to it.
        check(room.localKeePassDatabaseDao().getAllDatabasesSync().isEmpty()) { "Existing KDBX registrations: refuse to reset or expose them to this fixture" }
        check(room.localMdbxDatabaseDao().getAllDatabasesSnapshot().isEmpty()) { "Existing MDBX registrations: refuse to reset or expose them to this fixture" }
        compose.runOnUiThread {
            compose.activity.setTurnScreenOn(true)
            compose.activity.setShowWhenLocked(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }

    @Test fun keepassRealCreateVerifyCancelAndRemovePreserveSiblingAndOriginalFile() = runBlocking<Unit> {
        val originalRows = protectedRows()
        val originalUnlocked = SessionManager.isUnlocked.value
        val dao = room.localKeePassDatabaseDao()
        val model = LocalKeePassViewModel(context.applicationContext as Application, dao, security)
        val ownedFiles = linkedSetOf<File>()
        var show by mutableStateOf(true)
        var form by mutableStateOf(false)
        var detail by mutableStateOf<LocalKeePassDatabase?>(null)
        var creates = 0
        var verifies = 0
        var removes = 0
        SessionManager.markUnlocked()
        var primaryFailure: Throwable? = null
        try {
            val siblingName = "${prefix}_sibling"
            model.createDatabase(siblingName, password, KeePassStorageLocation.INTERNAL,
                creationOptions = KeePassDatabaseCreationOptions(kdfAlgorithm = KeePassKdfAlgorithm.AES_KDF, transformRounds = 100))
            awaitKeepass(model)
            val sibling = dao.getAllDatabasesSync().single { it.name == siblingName }
            val siblingFile = keepassFile(sibling).also(ownedFiles::add)
            val siblingBytes = siblingFile.readBytes()
            assertEquals(siblingName, readKeepass(siblingFile).content.meta.name)
            val targetName = "${prefix}_target"
            val targetFile = File(context.filesDir, "keepass/$targetName.kdbx").also(ownedFiles::add)
            check(!targetFile.exists())
            model.clearOperationState()
            compose.setContent {
                if (show) FixtureTheme {
                    Text("Synthetic lifecycle fixture")
                    if (form) CreateKeePassDatabaseBottomSheet(
                        onDismiss = { form = false }, onGenerateKeyFile = { error("No key file in this fixture") },
                        onCreate = { name, secret, location, uri, keyUri, options, copyKey ->
                            check(name == targetName && location == KeePassStorageLocation.INTERNAL && uri == null && keyUri == null && !copyKey)
                            creates++
                            model.createDatabase(name, secret, location, uri, keyUri, options, keepKeyFileCopy = copyKey)
                            form = false
                        })
                    detail?.let { selected ->
                        val verification by model.verificationStates.collectAsState()
                        KeePassDatabaseDetailBottomSheet(selected,
                            verification[selected.id] ?: LocalKeePassViewModel.VerificationState.Unknown,
                            KeePassUriPermissionState.READ_WRITE, LocalKeePassViewModel.KeyFileAccessState.AVAILABLE,
                            onDismiss = { detail = null }, onSetDefault = { error("No default change") },
                            onDelete = { check(it.id != sibling.id); removes++; model.deleteDatabase(it.id, deleteFile = false) },
                            onTransferToInternal = { error("No transfer") }, onTransferToExternal = { error("No transfer") },
                            onVerifyPassword = { row, secret, key -> verifies++; model.reverifyDatabasePassword(row.id, secret, key) },
                            onSyncRemote = { error("No cloud") }, onResolveConflict = { error("No conflict") },
                            onExport = { error("No export") }, onRepairPermission = { error("No permission repair") },
                            onKeepKeyFileCopy = { error("No key file") }, onExportKeyFileCopy = { error("No key file") },
                            onDeleteKeyFileCopy = { error("No key file") }, onOpenNativeManager = { error("No navigation") })
                    }
                }
            }
            compose.runOnIdle { form = true }
            keepassSubmit().assertIsNotEnabled()
            fill(R.string.database_name, targetName)
            fill(R.string.database_password, password)
            fill(R.string.confirm_password, "different synthetic value")
            keepassSubmit().assertIsNotEnabled()
            capture("kdbx-create-mismatch")
            fill(R.string.confirm_password, password)
            keepassSubmit().assertIsEnabled()
            capture("kdbx-create-ready")
            Espresso.closeSoftKeyboard()
            Espresso.pressBack()
            compose.waitUntil(10_000) { !form }
            assertEquals(0, creates)
            assertFalse(targetFile.exists())
            assertEquals(listOf(sibling), dao.getAllDatabasesSync())
            assertArrayEquals(siblingBytes, siblingFile.readBytes())

            compose.runOnIdle { form = true }
            fill(R.string.database_name, targetName)
            fill(R.string.database_password, password)
            fill(R.string.confirm_password, password)
            keepassSubmit().assertIsEnabled().performClick()
            awaitKeepass(model)
            assertEquals(1, creates)
            val target = dao.getAllDatabasesSync().single { it.name == targetName }
            assertEquals(targetFile.canonicalFile, keepassFile(target).canonicalFile)
            assertEquals(targetName, readKeepass(targetFile).content.meta.name)
            val targetBytes = targetFile.readBytes()
            compose.runOnIdle { detail = target }
            waitTag("keepass_database_detail")
            capture("kdbx-created-detail")

            // Cancel verification first: it must not call the real VM or rewrite the saved credential.
            openKeepassVerify()
            fill(R.string.database_password, "wrong synthetic password")
            capture("kdbx-password-cancel")
            compose.onNodeWithText(label(R.string.cancel)).performClick()
            assertEquals(0, verifies)
            assertEquals(target, dao.getDatabaseById(target.id))
            assertArrayEquals(targetBytes, targetFile.readBytes())

            openKeepassVerify()
            fill(R.string.database_password, "wrong synthetic password")
            compose.onNodeWithText(label(R.string.confirm)).performClick()
            compose.waitUntil(30_000) { model.verificationStates.value[target.id] is LocalKeePassViewModel.VerificationState.Failed }
            assertEquals(1, verifies)
            assertEquals(target, dao.getDatabaseById(target.id))
            assertArrayEquals(targetBytes, targetFile.readBytes())
            capture("kdbx-password-rejected")

            openKeepassVerify()
            fill(R.string.database_password, password)
            compose.onNodeWithText(label(R.string.confirm)).performClick()
            compose.waitUntil(30_000) { model.verificationStates.value[target.id] is LocalKeePassViewModel.VerificationState.Verified }
            val verified = checkNotNull(dao.getDatabaseById(target.id))
            assertEquals(password, security.decryptData(checkNotNull(verified.encryptedPassword)))
            assertArrayEquals(targetBytes, targetFile.readBytes())
            capture("kdbx-password-accepted")

            compose.onNodeWithText(label(R.string.remove_database)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.confirm_remove_internal_description)).assertIsDisplayed()
            capture("kdbx-remove-confirmation")
            compose.onNodeWithText(label(R.string.cancel)).performClick()
            assertEquals(0, removes)
            assertEquals(verified, dao.getDatabaseById(target.id))
            assertArrayEquals(targetBytes, targetFile.readBytes())
            assertEquals(sibling, dao.getDatabaseById(sibling.id))
            assertArrayEquals(siblingBytes, siblingFile.readBytes())
            capture("kdbx-remove-cancelled")
            model.clearOperationState()
            compose.onNodeWithText(label(R.string.remove_database)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.remove)).performClick()
            compose.waitUntil(10_000) { removes == 1 }
            awaitKeepass(model)
            assertNull(dao.getDatabaseById(target.id))
            assertArrayEquals("Production removal retains the original KDBX file", targetBytes, targetFile.readBytes())
            assertEquals(targetName, readKeepass(targetFile).content.meta.name)
            assertEquals(sibling, dao.getDatabaseById(sibling.id))
            assertArrayEquals(siblingBytes, siblingFile.readBytes())
            compose.runOnIdle { detail = sibling }
            waitTag("keepass_database_detail")
            compose.onNodeWithText(siblingName).assertIsDisplayed()
            capture("kdbx-sibling-after-removal")
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(block: suspend () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanup { compose.runOnIdle { show = false } }
            cleanup { stop(model) }
            cleanup {
                dao.getAllDatabasesSync().filter { it.name.startsWith(prefix) }.forEach { row ->
                    ownedFiles += keepassFile(row)
                    KeePassKdbxService.invalidateProcessCache(row.id)
                    room.keepassPendingChangeDao().deleteByDatabase(row.id)
                    dao.deleteDatabaseById(row.id)
            }
            ownedFiles.forEach { file ->
                check(file.canonicalFile.parentFile == File(context.filesDir, "keepass").canonicalFile && file.name.startsWith(prefix))
                check(!file.exists() || file.delete())
            }
            }
            cleanup { if (!originalUnlocked) SessionManager.markLocked() }
            cleanup { assertEquals("All pre-existing protected rows must survive", originalRows, protectedRows()) }
            cleanupFailure?.let { failure -> if (primaryFailure != null) primaryFailure!!.addSuppressed(failure) else throw failure }
        }
    }

    @Test fun mdbxRealCreateCancelAndDeletePreserveIndependentSibling() = runBlocking<Unit> {
        val originalRows = protectedRows()
        val originalUnlocked = SessionManager.isUnlocked.value
        val prefs = context.getSharedPreferences("mdbx_active_vault", 0)
        val hadActive = prefs.contains("last_active_mdbx_database_id")
        val activeBefore = prefs.getLong("last_active_mdbx_database_id", -1)
        val nativePrefs = context.getSharedPreferences("mdbx2_vault_sessions", 0)
        val hadDeviceId = nativePrefs.contains("device_id")
        val deviceIdBefore = nativePrefs.getString("device_id", null)
        val dao = room.localMdbxDatabaseDao()
        val model = MdbxViewModel(context.applicationContext as Application, dao, room.mdbxRemoteSourceDao(),
            room.passwordEntryDao(), room.secureItemDao(), room.passkeyDao(), room.attachmentDao(), room.customFieldDao(), security)
        val repository = Mdbx2Repository(context, dao, security)
        val owned = linkedMapOf<Long, File>()
        var show by mutableStateOf(true)
        var form by mutableStateOf(false)
        var managerId by mutableStateOf<Long?>(null)
        SessionManager.markUnlocked()
        var primaryFailure: Throwable? = null
        try {
            val siblingName = "${prefix}_sibling"
            model.createLocalVault(siblingName, password, MdbxUnlockMethod.MASTER_PASSWORD, null, MdbxTigaMode.MULTI, null)
            awaitMdbx(model)
            val sibling = dao.getAllDatabasesSnapshot().single { it.name == siblingName }
            val siblingFile = mdbxFile(sibling).also { owned[sibling.id] = it }
            val siblingMarker = "Independent synthetic sibling"
            val siblingObject = uniffi.mdbx_ffi.openVault(siblingFile.absolutePath, password, "toolchain-ui-sibling").use { vault ->
                vault.createObject(Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId),
                    "com.example.toolchain", siblingMarker, "{\"unchanged\":true}", 1u).objectId
            }
            val siblingBefore = repository.nativeObject(sibling.id, siblingObject)
            Mdbx2NativeReadSessions.invalidate(sibling.id)
            val siblingSha = mdbxDiskState(siblingFile)
            model.clearOperationState()
            compose.setContent {
                if (show) FixtureTheme {
                    when {
                        form -> MdbxLocalCreateScreen(model) { form = false }
                        managerId != null -> MdbxManagerScreen(model, initialDatabaseId = managerId,
                            initialPage = MdbxManagerInitialPage.DETAIL, onNavigateBack = { managerId = null },
                            onNavigateToLocalCreate = { error("Unexpected navigation") }, onNavigateToLocalOpen = { error("Unexpected navigation") },
                            onNavigateToWebDavCreate = { error("No cloud") }, onNavigateToWebDavOpen = { error("No cloud") },
                            onNavigateToOneDriveCreate = { error("No cloud") }, onNavigateToOneDriveOpen = { error("No cloud") })
                        else -> Text("Synthetic lifecycle fixture")
                    }
                }
            }
            val targetName = "${prefix}_target"
            val fileRoster = File(context.filesDir, "mdbx2").listFiles()?.map { it.name }?.toSet().orEmpty()
            compose.runOnIdle { form = true }
            mdbxSubmit().assertIsNotEnabled()
            fill(R.string.mdbx_vault_name, targetName)
            fill(R.string.mdbx_master_password, password)
            fill(R.string.mdbx_confirm_password, "wrong confirmation")
            mdbxSubmit().assertIsNotEnabled()
            capture("mdbx-create-mismatch")
            fill(R.string.mdbx_confirm_password, password)
            mdbxSubmit().assertIsEnabled()
            capture("mdbx-create-ready")
            hideIme()
            compose.onNodeWithContentDescription(label(R.string.back)).performClick()
            compose.waitUntil(10_000) { !form }
            assertEquals(listOf(sibling), dao.getAllDatabasesSnapshot())
            assertEquals(fileRoster, File(context.filesDir, "mdbx2").listFiles()?.map { it.name }?.toSet().orEmpty())
            assertEquals(siblingSha, mdbxDiskState(siblingFile))

            compose.runOnIdle { form = true }
            fill(R.string.mdbx_vault_name, targetName)
            fill(R.string.mdbx_master_password, password)
            fill(R.string.mdbx_confirm_password, password)
            mdbxSubmit().assertIsEnabled().performClick()
            // MdbxLocalCreateScreen consumes Success and returns; verify its real completion and stored row.
            compose.waitUntil(60_000) { !form }
            val target = dao.getAllDatabasesSnapshot().single { it.name == targetName }
            val targetFile = mdbxFile(target).also { owned[target.id] = it }
            assertEquals(MdbxEngineType.RUST_MDBX2, target.engineTypeEnum)
            assertEquals(MdbxSourceType.LOCAL_INTERNAL, target.sourceTypeEnum)
            assertEquals("argon2id-mdbx2", target.kdfProfile)
            val nativeIdentity = uniffi.mdbx_ffi.openVault(targetFile.absolutePath, password, "toolchain-ui-correct").use { it.info().vaultId }
            assertTrue(nativeIdentity.isNotBlank())
            val targetBefore = sha(targetFile)
            assertTrue("Wrong password must fail in the actual native reader", runCatching {
                uniffi.mdbx_ffi.openVault(targetFile.absolutePath, "wrong synthetic password", "toolchain-ui-wrong").use { it.info() }
            }.isFailure)
            assertEquals(targetBefore, sha(targetFile))
            assertEquals(nativeIdentity, uniffi.mdbx_ffi.openVault(targetFile.absolutePath, password, "toolchain-ui-reopen").use { it.info().vaultId })

            compose.runOnIdle { managerId = target.id }
            compose.waitUntil(30_000) { compose.onAllNodesWithText(targetName).fetchSemanticsNodes().isNotEmpty() }
            capture("mdbx-created-detail")
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label(R.string.advanced_options)))
            compose.onNodeWithText(label(R.string.advanced_options)).performClick()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label(R.string.mdbx_delete)))
            compose.onNodeWithText(label(R.string.mdbx_delete)).performClick()
            compose.onNodeWithText(label(R.string.mdbx_delete_vault_title)).assertIsDisplayed()
            capture("mdbx-delete-confirmation")
            compose.onNodeWithText(label(R.string.mdbx_cancel)).performClick()
            assertNotNull(dao.getDatabaseById(target.id))
            assertTrue(targetFile.isFile)
            assertEquals(sibling, dao.getDatabaseById(sibling.id))
            assertEquals("Cancelling target deletion must not alter the sibling file", siblingSha, mdbxDiskState(siblingFile))
            assertEquals(siblingBefore, repository.nativeObject(sibling.id, siblingObject))
            Mdbx2NativeReadSessions.invalidate(sibling.id)
            // Reading a native object legitimately records disclosure in the audit WAL.
            val siblingAfterVerificationSha = mdbxDiskState(siblingFile)
            capture("mdbx-delete-cancelled")
            model.clearOperationState()
            compose.onNodeWithText(label(R.string.mdbx_delete)).performScrollTo().performClick()
            compose.onNode(hasText(label(R.string.mdbx_delete)) and hasAnyAncestor(isDialog())).performClick()
            awaitMdbx(model)
            assertNull(dao.getDatabaseById(target.id))
            assertFalse(targetFile.exists())
            assertEquals(listOf(sibling), dao.getAllDatabasesSnapshot())
            assertEquals("Deleting the target must not alter the sibling file", siblingAfterVerificationSha, mdbxDiskState(siblingFile))
            assertEquals(siblingBefore, repository.nativeObject(sibling.id, siblingObject))
            Mdbx2NativeReadSessions.invalidate(sibling.id)
            capture("mdbx-after-target-deletion")
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(block: suspend () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanup { compose.runOnIdle { show = false } }
            cleanup { stop(model) }
            cleanup {
                dao.getAllDatabasesSnapshot().filter { it.name.startsWith(prefix) }.forEach { row ->
                    owned[row.id] = mdbxFile(row)
                    room.passwordEntryDao().deleteAllByMdbxDatabaseId(row.id)
                    dao.deleteDatabaseById(row.id)
            }
            owned.forEach { (id, file) ->
                Mdbx2NativeReadSessions.invalidate(id)
                check(repository.deleteOwnedVaultFile(file))
            }
            }
            cleanup {
                val editor = prefs.edit()
                if (hadActive) editor.putLong("last_active_mdbx_database_id", activeBefore)
                else editor.remove("last_active_mdbx_database_id")
                check(editor.commit())
                val nativeEditor = nativePrefs.edit()
                if (hadDeviceId) nativeEditor.putString("device_id", deviceIdBefore)
                else nativeEditor.remove("device_id")
                check(nativeEditor.commit())
                assertEquals(hadActive, prefs.contains("last_active_mdbx_database_id"))
                assertEquals(activeBefore, prefs.getLong("last_active_mdbx_database_id", -1))
                assertEquals(hadDeviceId, nativePrefs.contains("device_id"))
                assertEquals(deviceIdBefore, nativePrefs.getString("device_id", null))
            }
            cleanup { if (!originalUnlocked) SessionManager.markLocked() }
            cleanup { assertEquals("All pre-existing protected rows must survive", originalRows, protectedRows()) }
            cleanupFailure?.let { failure -> if (primaryFailure != null) primaryFailure!!.addSuppressed(failure) else throw failure }
        }
    }

    /** The registry supplies only an owned URI; this does not exercise the system document picker.
     * Password input, validation, import, registration and native reads use production code.
     * Cancellation below is before submission, not cancellation of an already running import.
     */
    @Test fun mdbxLocalOpenWrongPasswordCancelAndCorrectPreserveSourceAndSibling() = runBlocking<Unit> {
        val originalRows = protectedRows()
        val originalUnlocked = SessionManager.isUnlocked.value
        val prefs = context.getSharedPreferences("mdbx_active_vault", 0)
        val hadActive = prefs.contains("last_active_mdbx_database_id")
        val activeBefore = prefs.getLong("last_active_mdbx_database_id", -1)
        val nativePrefs = context.getSharedPreferences("mdbx2_vault_sessions", 0)
        val hadDevice = nativePrefs.contains("device_id")
        val deviceBefore = nativePrefs.getString("device_id", null)
        val dao = room.localMdbxDatabaseDao()
        val model = MdbxViewModel(context.applicationContext as Application, dao, room.mdbxRemoteSourceDao(),
            room.passwordEntryDao(), room.secureItemDao(), room.passkeyDao(), room.attachmentDao(), room.customFieldDao(), security)
        val repository = Mdbx2Repository(context, dao, security)
        val ownedFiles = linkedSetOf<File>()
        val ownedIds = linkedSetOf<Long>()
        var sourceUri: Uri? = null
        var show by mutableStateOf(true)
        var form by mutableStateOf(true)
        var exits = 0
        var selections = 0
        var primaryFailure: Throwable? = null
        SessionManager.markUnlocked()
        try {
            model.createLocalVault("${prefix}_sibling", password, MdbxUnlockMethod.MASTER_PASSWORD, null, MdbxTigaMode.MULTI, null)
            awaitMdbx(model)
            val sibling = dao.getAllDatabasesSnapshot().single { it.name == "${prefix}_sibling" }
            ownedIds += sibling.id
            val siblingFile = mdbxFile(sibling).also { ownedFiles += it }
            val siblingObject = uniffi.mdbx_ffi.openVault(siblingFile.absolutePath, password, "toolchain-open-sibling").use { vault ->
                vault.createObject(Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId),
                    "com.example.toolchain", "Independent open sibling", "{\"unchanged\":true}", 1u).objectId
            }
            val siblingDetail = repository.nativeObject(sibling.id, siblingObject)
            Mdbx2NativeReadSessions.invalidate(sibling.id)
            val siblingDisk = mdbxDiskState(siblingFile)
            val source = repository.createInitializedVaultFile(MdbxTigaMode.MULTI, password).also { ownedFiles += it }
            val sourceDetail = uniffi.mdbx_ffi.openVault(source.absolutePath, password, "toolchain-open-source").use { vault ->
                val objectId = vault.createObject(Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId),
                    "com.example.toolchain", "Original import marker", "{\"synthetic\":true,\"unknown\":[1,\"保留\",null]}", 1u).objectId
                readMdbxNativeObject(vault, objectId)
            }
            // The production external-document import copies a complete single file.
            check(!File(source.absolutePath + "-wal").exists() || File(source.absolutePath + "-wal").length() == 0L)
            val selectedUri = Uri.fromFile(source).also { sourceUri = it }
            val sourceDisk = mdbxDiskState(source)
            val directory = File(context.filesDir, "mdbx2")
            val roster = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
            val registryOwner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                        options: ActivityOptionsCompat?) {
                        check(contract is ActivityResultContracts.OpenDocument)
                        selections++
                        dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(selectedUri))
                    }
                }
            }
            compose.setContent {
                if (show) FixtureTheme {
                    if (form) CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                        MdbxLocalOpenScreen(model) { exits++; form = false }
                    } else Text("Synthetic MDBX open returned")
                }
            }
            fun chooseAndFill(value: String) {
                compose.onNodeWithText(label(R.string.mdbx_open_local_vault_button)).performScrollTo().performClick()
                fill(R.string.mdbx_master_password, value)
                fill(R.string.mdbx_confirm_password, value)
                hideIme()
                mdbxSubmit().assertIsEnabled()
            }
            suspend fun assertUnimported() {
                assertEquals(listOf(sibling), dao.getAllDatabasesSnapshot())
                assertEquals(roster, directory.listFiles()?.map { it.name }?.toSet().orEmpty())
                assertEquals(sourceDisk, mdbxDiskState(source))
                assertEquals(siblingDisk, mdbxDiskState(siblingFile))
            }
            chooseAndFill("Incorrect synthetic import password")
            mdbxSubmit().performClick()
            val rejected = withTimeout(60_000) { model.operationState.first { it is MdbxViewModel.OperationState.Error } }
            assertTrue(rejected is MdbxViewModel.OperationState.Error)
            assertTrue(form)
            assertEquals(0, exits)
            assertUnimported()
            capture("mdbx-open-rejected")
            compose.onNodeWithContentDescription(label(R.string.back)).performClick()
            compose.waitUntil(10_000) { !form }
            compose.runOnIdle { form = true }
            chooseAndFill(password)
            capture("mdbx-open-cancel-ready")
            compose.onNodeWithContentDescription(label(R.string.back)).performClick()
            compose.waitUntil(10_000) { !form }
            assertEquals(2, exits)
            assertUnimported()
            compose.runOnIdle { form = true }
            chooseAndFill(password)
            mdbxSubmit().performClick()
            compose.waitUntil(60_000) { !form }
            assertEquals(3, exits)
            assertEquals(3, selections)
            val imported = dao.getAllDatabasesSnapshot().single { it.filePath == selectedUri.toString() }
            ownedIds += imported.id
            val importedFile = File(checkNotNull(imported.workingCopyPath)).also {
                check(it.canonicalFile.parentFile == directory.canonicalFile && it != source && it != siblingFile)
                ownedFiles += it
            }
            assertEquals(MdbxSourceType.LOCAL_EXTERNAL, imported.sourceTypeEnum)
            assertEquals(MdbxEngineType.RUST_MDBX2, imported.engineTypeEnum)
            assertEquals("argon2id-mdbx2", imported.kdfProfile)
            assertEquals(2, dao.getAllDatabasesSnapshot().size)
            assertTrue(importedFile.isFile)
            assertEquals(sourceDetail, repository.nativeObject(imported.id, sourceDetail.summary.id))
            Mdbx2NativeReadSessions.invalidate(imported.id)
            assertEquals(sourceDetail, repository.nativeObject(imported.id, sourceDetail.summary.id))
            Mdbx2NativeReadSessions.invalidate(imported.id)
            assertEquals(sibling, dao.getDatabaseById(sibling.id))
            assertEquals(siblingDisk, mdbxDiskState(siblingFile))
            assertEquals(sourceDisk, mdbxDiskState(source))
            assertEquals(siblingDetail, repository.nativeObject(sibling.id, siblingObject))
            Mdbx2NativeReadSessions.invalidate(sibling.id)
            capture("mdbx-open-accepted")
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(block: suspend () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanup { compose.runOnIdle { show = false } }
            cleanup { stop(model) }
            cleanup {
                dao.getAllDatabasesSnapshot().filter {
                    it.name == "${prefix}_sibling" || (sourceUri != null && it.filePath == sourceUri.toString())
                }.forEach { row ->
                    ownedIds += row.id
                    val file = File(checkNotNull(row.workingCopyPath))
                    check(file.canonicalFile.parentFile == File(context.filesDir, "mdbx2").canonicalFile)
                    ownedFiles += file
                }
                ownedIds.forEach { id ->
                    Mdbx2NativeReadSessions.invalidate(id)
                    room.passwordEntryDao().deleteAllByMdbxDatabaseId(id)
                    room.secureItemDao().deleteAllByMdbxDatabaseId(id)
                    room.passkeyDao().deleteAllByMdbxDatabaseId(id)
                    dao.deleteDatabaseById(id)
                }
                ownedFiles.forEach { check(repository.deleteOwnedVaultFile(it)) }
            }
            cleanup {
                val editor = prefs.edit()
                if (hadActive) editor.putLong("last_active_mdbx_database_id", activeBefore) else editor.remove("last_active_mdbx_database_id")
                check(editor.commit())
                val nativeEditor = nativePrefs.edit()
                if (hadDevice) nativeEditor.putString("device_id", deviceBefore) else nativeEditor.remove("device_id")
                check(nativeEditor.commit())
            }
            cleanup { if (!originalUnlocked) SessionManager.markLocked() }
            cleanup { assertEquals("All original protected rows survive import and cleanup", originalRows, protectedRows()) }
            cleanupFailure?.let { if (primaryFailure != null) primaryFailure!!.addSuppressed(it) else throw it }
        }
    }

    private fun label(id: Int) = compose.activity.getString(id)
    private fun fill(id: Int, value: String) {
        val field = compose.onNode(hasSetTextAction() and hasText(label(id)))
        if (!field.isDisplayed()) field.performScrollTo()
        field.assertIsDisplayed().performClick().performTextReplacement(value)
    }
    private fun keepassSubmit() = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("keepass_create_form_actions")))
    private fun mdbxSubmit() = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("mdbx_form_actions")))
    private fun waitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun openKeepassVerify() {
        compose.onNodeWithText(label(R.string.local_keepass_repair_credentials)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.local_keepass_reverify_dialog_title)).assertIsDisplayed()
    }
    private fun hideIme() {
        compose.runOnUiThread { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }
    private suspend fun stop(model: ViewModel) { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
    private suspend fun awaitKeepass(model: LocalKeePassViewModel) {
        val state = withTimeout(60_000) { model.operationState.first { it is LocalKeePassViewModel.OperationState.Success || it is LocalKeePassViewModel.OperationState.Error } }
        assertTrue(state.toString(), state is LocalKeePassViewModel.OperationState.Success)
    }
    private suspend fun awaitMdbx(model: MdbxViewModel) {
        val state = withTimeout(60_000) { model.operationState.first { it is MdbxViewModel.OperationState.Success || it is MdbxViewModel.OperationState.Error } }
        assertTrue(state.toString(), state is MdbxViewModel.OperationState.Success)
    }
    private fun keepassFile(row: LocalKeePassDatabase): File = File(context.filesDir, row.filePath).also {
        check(row.name.startsWith(prefix) && it.canonicalFile.parentFile == File(context.filesDir, "keepass").canonicalFile && it.name.startsWith(prefix))
    }
    private fun mdbxFile(row: LocalMdbxDatabase): File = File(checkNotNull(row.workingCopyPath)).also {
        check(row.name.startsWith(prefix) && it.canonicalFile.parentFile == File(context.filesDir, "mdbx2").canonicalFile)
    }
    private fun readKeepass(file: File) = file.inputStream().use { KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString(password))) }

    @Composable
    private fun FixtureTheme(content: @Composable () -> Unit) {
        val currentContent by rememberUpdatedState(content)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                val configuration = Configuration(compose.activity.resources.configuration).apply {
                    fontScale = fixtureFontScale
                }
                val wrapped = ContextThemeWrapper(compose.activity, 0).apply {
                    applyOverrideConfiguration(configuration)
                }
                ComposeView(wrapped).apply {
                    check(fixtureHost == null)
                    fixtureHost = this
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
                    setContent { MaterialTheme { currentContent() } }
                }
            },
            onRelease = { host ->
                host.disposeComposition()
                if (fixtureHost === host) fixtureHost = null
            }
        )
    }

    /** Default: capture immediately. Optional host ACK waits only on this method's unique nonce. */
    private fun capture(step: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val fontEvidence = compose.runOnIdle {
            val host = checkNotNull(fixtureHost) { "No fixture ComposeView at $step" }
            check(host.isAttachedToWindow) { "Detached fixture ComposeView at $step" }
            JSONObject().put("requestedFontScale", fixtureFontScale.toDouble())
                .put("hostContextFontScale", host.context.resources.configuration.fontScale.toDouble())
                .put("hostContextDensity", host.context.resources.displayMetrics.density.toDouble())
                .put("activityContextFontScale", compose.activity.resources.configuration.fontScale.toDouble())
        }
        val sampleMatchers = when (step) {
            "kdbx-create-mismatch", "kdbx-create-ready" -> listOf(
                "kdbx_create_action" to (hasText(label(R.string.create)) and hasAnyAncestor(isDialog())))
            "kdbx-password-cancel" -> listOf(
                "kdbx_verify_title" to (hasText(label(R.string.local_keepass_reverify_dialog_title)) and hasAnyAncestor(isDialog())))
            "kdbx-remove-confirmation" -> listOf(
                "kdbx_remove_title" to (hasText(label(R.string.confirm_remove)) and hasAnyAncestor(isDialog())),
                "kdbx_remove_body" to (hasText(label(R.string.confirm_remove_internal_description)) and hasAnyAncestor(isDialog())))
            "kdbx-created-detail", "kdbx-password-rejected", "kdbx-password-accepted", "kdbx-remove-cancelled" -> listOf(
                "kdbx_detail_title" to (hasText("${prefix}_target") and hasAnyAncestor(isDialog())))
            "kdbx-sibling-after-removal" -> listOf(
                "kdbx_sibling_title" to (hasText("${prefix}_sibling") and hasAnyAncestor(isDialog())))
            "mdbx-create-mismatch", "mdbx-create-ready" -> listOf(
                "mdbx_create_title" to hasText(label(R.string.mdbx_create_vault_title)))
            "mdbx-created-detail", "mdbx-delete-cancelled" -> listOf(
                "mdbx_detail_title" to hasText(label(R.string.mdbx_ui_database_information)))
            "mdbx-delete-confirmation" -> listOf(
                "mdbx_delete_title" to (hasText(label(R.string.mdbx_delete_vault_title)) and hasAnyAncestor(isDialog())),
                "mdbx_delete_body" to (hasText(context.getString(R.string.mdbx_delete_vault_message, "${prefix}_target")) and hasAnyAncestor(isDialog())))
            "mdbx-after-target-deletion" -> listOf("mdbx_hub_title" to hasText("MDBX"))
            "mdbx-open-rejected", "mdbx-open-cancel-ready" -> listOf(
                "mdbx_open_title" to hasText(label(R.string.mdbx_open_vault_button)))
            "mdbx-open-accepted" -> listOf("mdbx_open_returned" to hasText("Synthetic MDBX open returned"))
            else -> error("No font-scale capture contract for $step")
        }
        val samples = JSONArray()
        val missing = mutableListOf<String>()
        val actualScales = mutableListOf<Float>()
        sampleMatchers.forEach { (sample, matcher) ->
            val nodes = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.isEmpty()) missing += sample
            nodes.forEach { node ->
                val density = node.layoutInfo.density
                actualScales += density.fontScale
                samples.put(JSONObject().put("sample", sample).put("nodeId", node.id)
                    .put("fontScale", density.fontScale.toDouble()).put("density", density.density.toDouble()))
            }
        }
        fontEvidence.put("textNodeSamples", samples).put("missingSamples", JSONArray(missing))
        File(captureDirectory, "$step-fontscale.json").writeText(fontEvidence.toString(2))
        assertEquals("Host context font scale at $step", fixtureFontScale.toDouble(), fontEvidence.getDouble("hostContextFontScale"), 0.001)
        assertTrue("Missing font-scale text samples at $step: $missing", missing.isEmpty())
        assertTrue("No measured text font scale at $step", actualScales.isNotEmpty())
        actualScales.forEach { actual -> assertEquals("Text font scale at $step", fixtureFontScale, actual, 0.001f) }
        val screenshot = File(captureDirectory, "$step.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { screenshot.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val nonce = UUID.randomUUID().toString()
        val marker = File(captureDirectory, "$step.json")
        val ack = File(captureDirectory, "$step.ack")
        val pause = args.getString("toolchainDatabaseUiPause") == "true"
        val data = JSONObject().put("step", step).put("nonce", nonce).put("uid", Process.myUid())
            .put("package", context.packageName).put("image", screenshot.name).put("pause", pause).put("state", "ready")
            .put("requestedFontScale", fixtureFontScale.toDouble()).put("fontScale", actualScales.first().toDouble())
            .put("fontScaleEvidence", fontEvidence)
        marker.writeText(data.toString(2))
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "TOOLCHAIN_UI_CAPTURE ${marker.absolutePath}\n") })
        if (pause) {
            val seconds = (args.getString("toolchainDatabaseUiPauseSeconds")?.toLongOrNull() ?: 90).coerceIn(5, 120)
            val deadline = SystemClock.elapsedRealtime() + seconds * 1000
            while (SystemClock.elapsedRealtime() < deadline && (!ack.isFile || ack.readText().trim() != nonce)) SystemClock.sleep(100)
            val accepted = ack.isFile && ack.readText().trim() == nonce
            marker.writeText(data.put("state", if (accepted) "acknowledged" else "timed-out").toString(2))
            check(accepted) { "Host capture ACK timed out at $step; no assertion was skipped" }
        }
    }

    private val protectedTables = listOf("password_entries", "secure_items", "custom_fields", "password_history_entries", "passkeys", "attachments",
        "local_keepass_databases", "local_mdbx_databases", "bitwarden_vaults")

    private fun protectedRows(): String {
        val output = JSONObject()
        for (table in protectedTables) {
            val rows = JSONArray()
            room.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    cursor.columnNames.sorted().forEach { name ->
                        val index = cursor.getColumnIndexOrThrow(name)
                        val value: Any = when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(cursor.getBlob(index))
                            else -> cursor.getString(index)
                        }
                        row.put(name, value)
                    }
                    rows.put(row)
                }
            }
            output.put(table, rows)
        }
        return output.toString()
    }
    /** Include WAL contents, since an uncheckpointed write may leave the main file unchanged. */
    private fun mdbxDiskState(file: File): Map<String, String> = listOf(file, File(file.absolutePath + "-wal"))
        .filter { it.isFile }.associate { it.name to sha(it) }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
