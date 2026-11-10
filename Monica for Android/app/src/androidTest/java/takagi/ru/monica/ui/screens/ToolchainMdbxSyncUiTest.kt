package takagi.ru.monica.ui.screens

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.*
import takagi.ru.monica.viewmodel.MdbxViewModel
import takagi.ru.monica.webdav.WebDavGateway
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Actual manager detail Sync button -> real ViewModel/coordinator/native engine -> loopback WebDAV.
 * Only initialized owned user13 and the host-owned loopback service on port18869 are accepted.
 * API setup publishes a baseline; the tested folder commit is published solely by the UI button.
 * Retains this run's remote UUID subtree for host evidence; never deletes remote/shared roots.
 */
class ToolchainMdbxSyncUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val runId = UUID.randomUUID().toString()
    private val prefix = "toolchain_mdbx_sync_${runId.replace('-', '_')}"
    private val captures by lazy { File(context.filesDir, "toolchain-db-ui/$prefix").apply { check(mkdirs()) } }

    @Test fun actualSyncButtonPublishesChangedFolderAndRemoteReplicaReadsIt() = runBlocking<Unit> {
        check(Process.myUid() / 100_000 == 13 && args.getString("toolchainDatabaseUiUser") == "13")
        check(args.getString("toolchainDatabaseUi") == "yes")
        check(context.packageName in setOf("takagi.ru.monica.bwsync326", "takagi.ru.monica.bwsync326fd"))
        check(args.getString("toolchainTargetApkSha256") == sha(File(context.applicationInfo.sourceDir)))
        val ownerFile = File(context.filesDir, "toolchain-db-ui-owner.json")
        val ownerBytes = ownerFile.readBytes()
        val owner = JSONObject(ownerBytes.decodeToString())
        check(owner.getString("task") == "20261109-android-toolchain" && owner.getInt("uid") == Process.myUid())
        check(owner.getString("name") == "Monica_Toolchain326_Ui_20261110" && owner.getString("package") == context.packageName)
        check(owner.getBoolean("initialized") && !owner.getBoolean("initAllowed"))
        val url = checkNotNull(args.getString("mdbxWebDavUrl")).trim()
        val uri = URI(url)
        check(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 18869 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/"))
        check(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
        val security = SecurityManager(context)
        check(security.isMasterPasswordSet() && security.verifyMasterPassword(APP_PASSWORD))
        val keyState = protectedKeyDigests(security)
        val sentinel = security.encryptData("Synthetic MDBX sync MDK continuity")
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        check(dao.getAllDatabasesSnapshot().isEmpty()) { "Refuse unrelated MDBX registrations before manager normalization/pruning" }
        val originalRows = protectedRows(room)
        val originalSettings = SettingsManager(context).settingsFlow.first()
        val wasUnlocked = SessionManager.isUnlocked.value
        val activePrefs = context.getSharedPreferences("mdbx_active_vault", 0)
        val nativePrefs = context.getSharedPreferences("mdbx2_vault_sessions", 0)
        val autoPrefs = context.getSharedPreferences("mdbx_auto_sync", 0)
        val originalActive = activePrefs.all.toMap()
        val originalNative = nativePrefs.all.toMap()
        val originalAuto = autoPrefs.all.toMap()
        val ownedFiles = linkedSetOf<File>()
        val ownedIds = linkedSetOf<Long>()
        val sourceIds = linkedSetOf<Long>()
        val root = File(context.cacheDir, prefix).apply { check(mkdirs()) }
        val repository = Mdbx2Repository(context, dao, security)
        val state = MdbxSyncStateStore(room.mdbxSyncStateDao())
        val syncRoot = File(context.filesDir, "mdbx2-sync")
        val coordinator = Mdbx2RemoteSyncCoordinator(syncRoot, Mdbx2RepositorySyncSessionProvider(repository), state)
        var model: MdbxViewModel? = null
        var pendingSyncResult: Deferred<MdbxViewModel.OperationState>? = null
        var visible by mutableStateOf(true)
        var restoreHttp: (() -> Unit)? = null
        var primary: Throwable? = null
        val remotePrefix = "monica-toolchain-ui-sync/$runId"
        val remotePath = "$remotePrefix/${prefix}_target.mdbx"
        val vaultPassword = "Synthetic MDBX UI remote $runId"
        try {
            compose.runOnUiThread {
                SessionManager.markUnlocked()
                compose.activity.setTurnScreenOn(true)
                compose.activity.setShowWhenLocked(true)
                compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
            restoreHttp = configureLoopbackHttpFixture()
            val transport = WebDavMdbxRemoteTransport(url, args.getString("mdbxWebDavUsername").orEmpty(),
                args.getString("mdbxWebDavPassword").orEmpty(), AppLocaleStringResolver(context))
            check(transport.stat(remotePrefix) == null) { "Remote fixture UUID must be new" }
            check(nativePrefs.edit().putString("device_id", "ui-a-$runId").commit())
            val targetFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, vaultPassword).also(ownedFiles::add)
            val sourceId = room.mdbxRemoteSourceDao().insertSource(MdbxRemoteSource(
                displayName = "${prefix}_target", remotePath = remotePath, remoteParentPath = remotePrefix, baseUrl = url,
                usernameEncrypted = security.encryptData(args.getString("mdbxWebDavUsername").orEmpty()),
                passwordEncrypted = security.encryptData(args.getString("mdbxWebDavPassword").orEmpty()))).also(sourceIds::add)
            suspend fun register(file: File, name: String, remote: Boolean): Long = dao.insertDatabase(LocalMdbxDatabase(
                name = name, filePath = if (remote) remotePath else file.absolutePath,
                sourceId = if (remote) sourceId else null,
                storageLocation = if (remote) MdbxStorageLocation.REMOTE_WEBDAV.name else MdbxStorageLocation.INTERNAL.name,
                sourceType = if (remote) MdbxSourceType.REMOTE_WEBDAV.name else MdbxSourceType.LOCAL_INTERNAL.name,
                engineType = MdbxEngineType.RUST_MDBX2.name, tigaMode = MdbxTigaMode.SKY.name,
                encryptedPassword = security.encryptData(vaultPassword), unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                kdfProfile = "argon2id-mdbx2", workingCopyPath = file.absolutePath, cacheCopyPath = file.absolutePath,
                isOfflineAvailable = true, lastSyncStatus = MdbxSyncStatus.IN_SYNC.name)).also(ownedIds::add)
            val targetId = register(targetFile, "${prefix}_target", true)
            val bootstrap = coordinator.publishBootstrap(targetId, remotePath, transport)
            assertTrue(bootstrap.fileSizeBytes > 0uL)
            val baselineInventory = checkNotNull(state.read(targetId).syncedCommitInventory)
            val targetCredentials = checkNotNull(dao.getDatabaseById(targetId)).let {
                listOf(it.encryptedPassword, it.unlockMethod, it.kdfProfile, it.keyFileUri, it.keyFileFingerprint)
            }

            // The receiving replica is downloaded before the new local edit exists.
            val replicaFile = File(context.filesDir, "mdbx2/$prefix-replica.mdbx").also {
                check(!it.exists()); ownedFiles += it
            }
            coordinator.downloadBootstrapTo(remotePath, transport, replicaFile)
            check(nativePrefs.edit().putString("device_id", "ui-b-$runId").commit())
            val replica = Mdbx2Repository(context, dao, security)
            replica.validatePasswordVaultFile(replicaFile, vaultPassword)
            val replicaId = register(replicaFile, "${prefix}_replica", true)
            val receiver = Mdbx2RemoteSyncCoordinator(File(root, "receiver"), Mdbx2RepositorySyncSessionProvider(replica), state)
            receiver.registerDownloadedBootstrap(replicaId, remotePath)
            check(nativePrefs.edit().putString("device_id", "ui-a-$runId").commit())

            val siblingFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, vaultPassword).also(ownedFiles::add)
            val siblingId = register(siblingFile, "${prefix}_sibling", false)
            val siblingFolder = repository.createFolder(siblingId, "Unchanged independent sibling", null)
            assertEquals(siblingFolder.folderId, repository.listFolders(siblingId).single().folderId)
            Mdbx2NativeReadSessions.invalidate(siblingId)
            val siblingBytes = diskState(siblingFile)
            val siblingRow = checkNotNull(dao.getDatabaseById(siblingId))
            val folderName = "Published by real Sync button $runId"
            val changedFolder = repository.createFolder(targetId, folderName, null)
            val changedInventory = repository.withReadVaultForSync(targetId) { _, vault -> vault.incrementalSyncCheckpoint().commitInventory }
            assertNotEquals(baselineInventory, changedInventory)
            assertTrue(repository.getPendingSyncCount(targetId) > 0)
            assertFalse(replica.listFolders(replicaId).any { it.folderId == changedFolder.folderId })
            val segmentsBefore = remoteSegments(transport, remotePath)
            val vm = MdbxViewModel(context.applicationContext as Application, dao, room.mdbxRemoteSourceDao(),
                room.passwordEntryDao(), room.secureItemDao(), room.passkeyDao(), room.attachmentDao(), room.customFieldDao(), security)
            model = vm
            compose.setContent {
                MaterialTheme {
                    if (visible) MdbxManagerScreen(vm, initialDatabaseId = targetId, initialPage = MdbxManagerInitialPage.DETAIL,
                        onNavigateBack = { error("Unexpected navigation") }, onNavigateToLocalCreate = { error("Unexpected create") },
                        onNavigateToLocalOpen = { error("Unexpected open") }, onNavigateToWebDavCreate = { error("Unexpected create") },
                        onNavigateToWebDavOpen = { error("Unexpected open") }, onNavigateToOneDriveCreate = { error("Unexpected cloud") },
                        onNavigateToOneDriveOpen = { error("Unexpected cloud") })
                }
            }
            val sync = hasText(compose.activity.getString(R.string.mdbx_sync_status_label)) and hasClickAction()
            compose.waitUntil(30_000) { compose.onAllNodes(sync).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(sync).performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertEquals(baselineInventory, state.read(targetId).syncedCommitInventory)
            assertEquals(segmentsBefore, remoteSegments(transport, remotePath))
            assertEquals(siblingBytes, diskState(siblingFile))
            capture("mdbx-sync-before-click", remotePrefix)
            vm.clearOperationState()
            val syncResult = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                withTimeout(120_000) { vm.operationState.first {
                    it is MdbxViewModel.OperationState.Success || it is MdbxViewModel.OperationState.Error
                } }
            }.also { pendingSyncResult = it }
            compose.onNode(sync).performClick()
            val completion = syncResult.await()
            assertTrue("Actual UI sync must complete successfully: $completion", completion is MdbxViewModel.OperationState.Success)
            val published = state.read(targetId)
            assertEquals(changedInventory, published.syncedCommitInventory)
            assertNotEquals(baselineInventory, published.syncedCommitInventory)
            assertNull(published.pendingSegment)
            assertEquals(MdbxSyncStatus.IN_SYNC.name, dao.getDatabaseById(targetId)?.lastSyncStatus)
            assertEquals(0, repository.getPendingSyncCount(targetId))
            val segmentsAfter = remoteSegments(transport, remotePath)
            assertTrue(segmentsAfter.keys.containsAll(segmentsBefore.keys))
            val added = segmentsAfter.keys - segmentsBefore.keys
            assertTrue("UI sync must publish a real new immutable segment", added.isNotEmpty())
            added.forEachIndexed { index, path ->
                val downloaded = File(root, "segment-$index.mdbxsync")
                transport.readTo(path, downloaded)
                assertTrue(downloaded.length() > 0)
                val inspected = repository.withReadVaultForSync(targetId) { _, vault ->
                    vault.inspectIncrementalSyncSegment(downloaded.absolutePath)
                }
                val payloadHash = inspected.payloadSha256.joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff) }
                assertEquals(path.substringAfterLast('-').removeSuffix(".mdbxsync"), payloadHash)
                assertEquals(downloaded.length().toULong(), inspected.fileSizeBytes)
                assertEquals(32, inspected.payloadSha256.size)
                assertEquals(path, MdbxRemoteSyncPaths.segmentPath(remotePath, inspected.sourceDeviceId,
                    inspected.transferId, inspected.segmentIndex, payloadHash))
                File(captures, "segment-$index-proof.json").writeText(JSONObject().put("remotePath", path)
                    .put("payloadSha256", payloadHash).put("completeFileSha256", sha(downloaded))
                    .put("bytes", downloaded.length()).put("nativeAuthenticationPassed", true).toString(2))
            }
            assertEquals(siblingBytes, diskState(siblingFile))
            assertEquals(siblingRow, dao.getDatabaseById(siblingId))
            assertTrue("Original MDK wrappers and password material must remain unchanged", keyState == protectedKeyDigests(security))
            assertEquals("Synthetic MDBX sync MDK continuity", security.decryptData(sentinel))
            assertEquals(targetCredentials, checkNotNull(dao.getDatabaseById(targetId)).let {
                listOf(it.encryptedPassword, it.unlockMethod, it.kdfProfile, it.keyFileUri, it.keyFileFingerprint)
            })
            capture("mdbx-sync-after-publication", remotePrefix)
            // Separate native reader receives the newly uploaded encrypted segment over HTTP.
            val received = receiver.synchronize(replicaId, remotePath, transport)
            assertTrue(received.downloadedSegments > 0)
            assertEquals(folderName, replica.listFolders(replicaId).single { it.folderId == changedFolder.folderId }.name)
            assertEquals(folderName, repository.listFolders(targetId).single { it.folderId == changedFolder.folderId }.name)
            assertEquals(siblingBytes, diskState(siblingFile))
            assertEquals(siblingFolder.folderId, repository.listFolders(siblingId).single().folderId)
            val proof = JSONObject().put("targetId", targetId).put("replicaId", replicaId).put("siblingId", siblingId)
                .put("remotePrefix", remotePrefix).put("addedSegments", JSONArray(added.toList()))
                .put("baselineCommitInventorySha256", digest(baselineInventory.toByteArray()))
                .put("publishedCommitInventorySha256", digest(checkNotNull(published.syncedCommitInventory).toByteArray()))
                .put("folderId", changedFolder.folderId).put("receivedSegments", received.downloadedSegments)
                .put("mdkPreserved", true).put("siblingDiskPreservedBeforeRead", true)
            File(captures, "mdbx-sync-proof.json").writeText(proof.toString(2))
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("toolchainMdbxSync", "PASS real button, native commit, new HTTP segment and independent replica read") })
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                }
            }
            cleanup { compose.runOnIdle { visible = false }; compose.waitForIdle() }
            cleanup { pendingSyncResult?.cancelAndJoin() }
            cleanup { model?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
            ownedIds.forEach { id -> cleanup {
                val row = dao.getDatabaseById(id)
                check(row == null || row.name.startsWith(prefix))
                Mdbx2NativeReadSessions.invalidate(id)
                androidx.work.WorkManager.getInstance(context).cancelUniqueWork("mdbx-auto-$id").result.get()
                coordinator.clearLocalState(id)
                room.passwordEntryDao().deleteAllByMdbxDatabaseId(id)
                room.secureItemDao().deleteAllByMdbxDatabaseId(id)
                room.passkeyDao().deleteAllByMdbxDatabaseId(id)
                room.categoryDao().getCategoriesByMdbxDatabase(id).first().forEach { room.categoryDao().delete(it) }
                dao.deleteDatabaseById(id)
            } }
            sourceIds.forEach { id -> cleanup { room.mdbxRemoteSourceDao().deleteSourceById(id) } }
            ownedFiles.forEach { file -> cleanup {
                check(file.canonicalFile.parentFile == File(context.filesDir, "mdbx2").canonicalFile)
                check(repository.deleteOwnedVaultFile(file))
            } }
            cleanup {
                restorePreference(activePrefs, originalActive, "last_active_mdbx_database_id")
                restorePreference(nativePrefs, originalNative, "device_id")
                val editor = autoPrefs.edit()
                ownedIds.forEach { id -> listOf("enabled_$id", "next_$id", "idle_$id").forEach(editor::remove) }
                check(editor.commit())
                assertTrue(originalActive == activePrefs.all && originalNative == nativePrefs.all && originalAuto == autoPrefs.all)
            }
            cleanup { restoreHttp?.invoke() }
            cleanup {
                assertEquals(originalRows, protectedRows(room))
                assertEquals(originalSettings, SettingsManager(context).settingsFlow.first())
                assertTrue(keyState == protectedKeyDigests(security))
                assertEquals("Synthetic MDBX sync MDK continuity", security.decryptData(sentinel))
                assertArrayEquals(ownerBytes, ownerFile.readBytes())
            }
            cleanup {
                check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile && root.name == prefix)
                check(!root.exists() || root.deleteRecursively())
            }
            cleanup { if (!wasUnlocked) compose.runOnUiThread { SessionManager.markLocked() } }
            cleanupFailure?.let { if (primary != null) primary!!.addSuppressed(it) else throw it }
        }
    }

    private suspend fun remoteSegments(transport: MdbxRemoteTransport, path: String): Map<String, Long?> {
        val base = MdbxRemoteSyncPaths.streamsRoot(path)
        val seen = linkedSetOf<String>()
        val found = linkedMapOf<String, Long?>()
        suspend fun visit(directory: String, depth: Int) {
            check(depth <= 5 && seen.size < 128)
            if (!seen.add(directory)) return
            transport.list(directory).forEach { item ->
                val normalized = MdbxRemoteSyncPaths.normalizePath(item.path)
                if (normalized != directory) {
                    check(normalized.startsWith("$base/"))
                    if (item.isDirectory) visit(normalized, depth + 1)
                    else if (normalized.endsWith(".mdbxsync")) found[normalized] = item.sizeBytes
                }
            }
        }
        visit(base, 0)
        return found
    }

    /** Existing F-Droid HTTP test opt-in, attached only to this UUID's isolated preferences. */
    private fun configureLoopbackHttpFixture(): () -> Unit {
        val attach = WebDavGateway::class.java.methods.firstOrNull {
            it.name == "attach" && it.parameterTypes.contentEquals(arrayOf(Context::class.java))
        } ?: return {}
        val names = linkedSetOf<String>()
        val scoped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val fixtureName = "$name-mdbx-ui-$runId".also(names::add)
                return super.getSharedPreferences(fixtureName, mode).also { check(it.edit().putBoolean("allow_insecure_http", true).commit()) }
            }
        }
        attach.invoke(WebDavGateway, scoped)
        return { attach.invoke(WebDavGateway, context); names.forEach(context::deleteSharedPreferences) }
    }

    private fun restorePreference(prefs: SharedPreferences, original: Map<String, *>, key: String) {
        val editor = prefs.edit()
        when (val value = original[key]) {
            null -> editor.remove(key)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
            else -> error("Unexpected original fixture preference type")
        }
        check(editor.commit())
    }
    private fun protectedKeyDigests(security: SecurityManager) = listOf("master_password_hash", "master_password_salt",
        "mdk_password_blob", "mdk_password_salt", "mdk_keystore_blob").associateWith { key ->
        security.getProtectedString(key)?.let { digest(it.toByteArray()) }
    }
    private fun diskState(file: File) = listOf(file, File("${file.absolutePath}-wal")).filter { it.isFile }.associate { it.name to sha(it) }
    private fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun protectedRows(room: PasswordDatabase): String {
        val output = JSONObject()
        listOf("password_entries", "secure_items", "custom_fields", "password_history_entries", "passkeys", "attachments", "categories",
            "local_keepass_databases", "local_mdbx_databases", "bitwarden_vaults", "mdbx_remote_sources", "mdbx_sync_states").forEach { table ->
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
    private fun capture(step: String, remotePrefix: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(captures, "$step.png")
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val nonce = UUID.randomUUID().toString()
        val marker = File(captures, "$step.json")
        val ack = File(captures, "$step.ack")
        val pause = args.getString("toolchainDatabaseUiPause") == "true"
        val data = JSONObject().put("step", step).put("nonce", nonce).put("uid", Process.myUid())
            .put("package", context.packageName).put("image", file.name).put("pause", pause).put("state", "ready")
            .put("remotePrefix", remotePrefix).put("coverage", "actual MDBX manager manual Sync and loopback HTTP; synthetic only")
        marker.writeText(data.toString(2))
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "TOOLCHAIN_UI_CAPTURE ${marker.absolutePath}\n") })
        if (pause) {
            val deadline = SystemClock.elapsedRealtime() + 90_000
            while (SystemClock.elapsedRealtime() < deadline && (!ack.isFile || ack.readText().trim() != nonce)) SystemClock.sleep(100)
            val accepted = ack.isFile && ack.readText().trim() == nonce
            marker.writeText(data.put("state", if (accepted) "acknowledged" else "timed-out").toString(2))
            check(accepted) { "MDBX screenshot acknowledgement timed out" }
        }
    }
    private companion object { const val APP_PASSWORD = "Synthetic toolchain lifecycle 326!" }
}
