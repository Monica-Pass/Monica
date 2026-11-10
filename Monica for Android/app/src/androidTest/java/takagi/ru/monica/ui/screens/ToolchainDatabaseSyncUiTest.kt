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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.webdav.WebDavGateway

/** Actual LocalKeePassScreen action -> ViewModel -> sync coordinator -> loopback WebDAV.
 * A single clean-local/changed-remote pull, with normal-font before/after screenshots.
 * This is not MainActivity navigation, cloud-provider, upload, conflict or automatic-sync coverage.
 */
class ToolchainDatabaseSyncUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefix = "toolchain_sync_${UUID.randomUUID().toString().replace('-', '_')}"
    private lateinit var room: PasswordDatabase
    private lateinit var security: SecurityManager
    private lateinit var ownerBytes: ByteArray
    private val ownerFile get() = File(context.filesDir, "toolchain-db-ui-owner.json")
    private val captures by lazy { File(context.filesDir, "toolchain-db-ui/$prefix").apply { check(mkdirs()) } }
    private val credentials get() = Credentials.from(EncryptedValue.fromString(VAULT_PASSWORD))

    @Before fun requireOwnedInitializedUser() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(Process.myUid() / 100_000 == 13 && args.getString("toolchainDatabaseUiUser") == "13")
        check(args.getString("toolchainDatabaseUi") == "yes")
        check(context.packageName in setOf("takagi.ru.monica.bwsync326", "takagi.ru.monica.bwsync326fd"))
        check(args.getString("toolchainTargetApkSha256") == sha(File(context.applicationInfo.sourceDir)))
        ownerBytes = ownerFile.readBytes()
        val owner = JSONObject(ownerBytes.decodeToString())
        check(owner.getString("task") == "20261109-android-toolchain" && owner.getInt("uid") == Process.myUid())
        check(owner.getString("name") == "Monica_Toolchain326_Ui_20261110" && owner.getString("package") == context.packageName)
        check(owner.getBoolean("initialized") && !owner.getBoolean("initAllowed"))
        check(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
        security = SecurityManager(context)
        check(security.isMasterPasswordSet() && security.verifyMasterPassword(APP_PASSWORD))
        room = PasswordDatabase.getDatabase(context)
        check(room.localKeePassDatabaseDao().getAllDatabasesSync().isEmpty()) { "Refuse pre-existing KDBX registrations" }
        compose.runOnUiThread {
            compose.activity.setTurnScreenOn(true)
            compose.activity.setShowWhenLocked(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }

    @Test fun keepassActualSyncButtonPullsChangedLoopbackVaultAndPreservesSibling() = runBlocking<Unit> {
        val originalRows = protectedRows()
        val originalUnlocked = SessionManager.isUnlocked.value
        val originalHttpPrefs = context.getSharedPreferences("webdav_config", 0).all.toMap()
        val root = File(context.filesDir, prefix)
        val working = File(root, "working.kdbx")
        val baseFile = File(root, "base.kdbx")
        val siblingFile = File(root, "sibling.kdbx")
        val entryId = UUID.randomUUID()
        val baseline = fixtureDatabase(entryId)
        val baselineBytes = encode(baseline)
        val changed = baseline.modifyParentGroup {
            copy(entries = listOf(fixtureEntry(entryId, "Changed by synthetic remote")))
        }
        val changedBytes = encode(changed)
        val remote = Remote(baselineBytes)
        val server = MockWebServer()
        val dao = room.localKeePassDatabaseDao()
        var targetId = 0L
        var siblingId = 0L
        var sourceId = 0L
        var rootCreated = false
        var started = false
        var restoreHttp: (() -> Unit)? = null
        var model: LocalKeePassViewModel? = null
        var pendingSyncResult: Deferred<LocalKeePassViewModel.OperationState>? = null
        var show by mutableStateOf(true)
        var primary: Throwable? = null
        try {
            check(root.mkdirs())
            rootCreated = true
            compose.runOnUiThread { SessionManager.markUnlocked() }
            working.writeBytes(baselineBytes)
            baseFile.writeBytes(baselineBytes)
            siblingFile.writeBytes(encode(fixtureDatabase(UUID.randomUUID())))
            val siblingSha = sha(siblingFile)
            server.dispatcher = remote
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            started = true
            val endpoint = "http://127.0.0.1:${server.port}/"
            check(java.net.URI(endpoint).host == "127.0.0.1")
            restoreHttp = configureLoopbackHttpFixture()
            sourceId = room.keepassRemoteSourceDao().insertSource(KeepassRemoteSource(
                providerType = KeePassRemoteProviderType.WEBDAV, displayName = "$prefix WebDAV",
                remotePath = "fixture.kdbx", baseUrl = endpoint, autoSyncEnabled = false))
            val targetName = "${prefix}_target"
            targetId = dao.insertDatabase(LocalKeePassDatabase(name = targetName,
                filePath = working.relativeTo(context.filesDir).path, sourceType = KeePassDatabaseSourceType.REMOTE_WEBDAV,
                sourceId = sourceId, openMode = KeePassOpenMode.WORKING_COPY,
                workingCopyPath = working.relativeTo(context.filesDir).path, cacheCopyPath = baseFile.relativeTo(context.filesDir).path,
                encryptedPassword = security.encryptData(VAULT_PASSWORD), lastSyncStatus = KeePassSyncStatus.IN_SYNC,
                lastSyncedAt = 1L, kdfAlgorithm = KeePassKdfAlgorithm.AES_KDF.name, kdfTransformRounds = 100L))
            siblingId = dao.insertDatabase(LocalKeePassDatabase(name = "${prefix}_sibling",
                filePath = siblingFile.relativeTo(context.filesDir).path, encryptedPassword = security.encryptData(VAULT_PASSWORD),
                kdfAlgorithm = KeePassKdfAlgorithm.AES_KDF.name, kdfTransformRounds = 100L))
            room.keepassRemoteSyncStateDao().insertState(KeepassRemoteSyncState(databaseId = targetId,
                remoteEtag = remote.etag, baseHash = sha(baselineBytes), workingHash = sha(baselineBytes)))
            val originalTarget = checkNotNull(dao.getDatabaseById(targetId))
            val originalSibling = checkNotNull(dao.getDatabaseById(siblingId))
            val originalSource = checkNotNull(room.keepassRemoteSourceDao().getSourceById(sourceId))
            val vm = LocalKeePassViewModel(context.applicationContext as Application, dao, security).also { model = it }
            compose.setContent {
                if (show) MaterialTheme {
                    LocalKeePassScreen(vm, onNavigateBack = { error("Unexpected departure") },
                        onNavigateNativeEntry = { error("No native entry navigation in this sync fixture") })
                }
            }
            waitTag("keepass_hub")
            val webdav = compose.onNodeWithText("WebDAV")
            if (!webdav.isDisplayed()) webdav.performScrollTo()
            webdav.assertIsDisplayed().performClick()
            waitTag("keepass_database_$targetId")
            compose.onNodeWithTag("keepass_database_$targetId").assertIsDisplayed().performClick()
            waitTag("keepass_database_detail")
            withTimeout(30_000) { vm.verificationStates.first { it[targetId] is LocalKeePassViewModel.VerificationState.Verified } }
            assertTrue("Opening details must not sync", remote.requests.isEmpty())
            assertArrayEquals(baselineBytes, working.readBytes())
            remote.replace(changedBytes)
            val syncAction = compose.onNode(hasText(label(R.string.sync_now)) and hasAnyAncestor(hasTestTag("keepass_database_detail")))
            syncAction.performScrollTo().assertIsDisplayed()
            capture("kdbx-sync-before", targetId)
            val requestCountBeforeClick = remote.requests.size
            assertEquals(0, requestCountBeforeClick)
            // Subscribe before the click: the production screen clears its success state.
            val syncResult = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                withTimeout(60_000) { vm.operationState.first {
                    it is LocalKeePassViewModel.OperationState.Success || it is LocalKeePassViewModel.OperationState.Error
                } }
            }.also { pendingSyncResult = it }
            syncAction.performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("keepass_database_detail").fetchSemanticsNodes().isEmpty() }
            val result = syncResult.await()
            assertTrue("Manual synchronization must complete successfully", result is LocalKeePassViewModel.OperationState.Success)
            val after = checkNotNull(dao.getDatabaseById(targetId))
            val afterState = checkNotNull(room.keepassRemoteSyncStateDao().getState(targetId))
            assertEquals(KeePassSyncStatus.IN_SYNC, after.lastSyncStatus)
            assertNull(after.lastSyncError)
            assertTrue(checkNotNull(after.lastSyncedAt) > 1L)
            assertEquals(KeePassSyncPhase.IDLE, afterState.syncPhase)
            assertFalse(afterState.hasLocalChanges)
            assertFalse(afterState.hasRemoteChanges)
            assertEquals(remote.etag, afterState.remoteEtag)
            assertEquals(sha(changedBytes), afterState.baseHash)
            assertEquals(sha(changedBytes), afterState.workingHash)
            assertEquals(originalTarget.encryptedPassword, after.encryptedPassword)
            assertEquals(originalTarget.sourceId, after.sourceId)
            assertEquals(originalSource, room.keepassRemoteSourceDao().getSourceById(sourceId))
            assertArrayEquals(changedBytes, working.readBytes())
            assertArrayEquals(changedBytes, baseFile.readBytes())
            assertArrayEquals(changedBytes, remote.bytes)
            listOf(working, baseFile).forEach { file ->
                val entry = decode(file).content.group.entries.single()
                assertEquals(entryId, entry.uuid)
                assertEquals("Original synthetic account", entry.fields.getValue("Title").content)
                assertEquals("synthetic-user", entry.fields.getValue("UserName").content)
                assertEquals(ENTRY_PASSWORD, entry.fields.getValue("Password").content)
                assertTrue(entry.fields.getValue("Password") is EntryValue.Encrypted)
                assertEquals("Changed by synthetic remote", entry.fields.getValue("Notes").content)
                assertEquals("Preserved unknown property", entry.fields.getValue("ToolchainUnknown").content)
            }
            assertEquals(originalSibling, dao.getDatabaseById(siblingId))
            assertEquals(siblingSha, sha(siblingFile))
            val requests = remote.requests.toList()
            assertTrue(requests.any { it.method == "PROPFIND" && it.path == "/fixture.kdbx" })
            assertTrue(requests.any { it.method == "GET" && it.path == "/fixture.kdbx" })
            assertTrue(requests.all { it.method in setOf("GET", "HEAD", "PROPFIND") && it.path == "/fixture.kdbx" })
            assertTrue(remote.errors.isEmpty())
            assertTrue(room.keepassPendingChangeDao().getUnfinishedChangesByDatabase(targetId).isEmpty())
            compose.onNodeWithTag("keepass_database_$targetId").assertIsDisplayed().performClick()
            waitTag("keepass_database_detail")
            compose.onNode(hasText(label(R.string.sync_now)) and hasAnyAncestor(hasTestTag("keepass_database_detail")))
                .performScrollTo().assertIsDisplayed()
            compose.onNode(hasText(label(R.string.keepass_remote_sync_status_in_sync)) and hasAnyAncestor(hasTestTag("keepass_database_detail")))
                .assertIsDisplayed()
            capture("kdbx-sync-after", targetId)
            File(captures, "sync-result.json").writeText(JSONObject().put("targetId", targetId).put("siblingId", siblingId)
                .put("requestCountBeforeClick", requestCountBeforeClick).put("requests", JSONArray(requests.map { request ->
                    JSONObject().put("method", request.method).put("path", request.path)
                })).put("putCount", requests.count { it.method == "PUT" }).put("beforeSha256", sha(baselineBytes))
                .put("afterSha256", sha(changedBytes)).put("workingSha256", sha(working)).put("cacheSha256", sha(baseFile))
                .put("siblingSha256", siblingSha).put("status", after.lastSyncStatus.name).put("nativeEntryVerified", true).toString(2))
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(block: suspend () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanup { compose.runOnIdle { show = false }; compose.waitForIdle() }
            cleanup { pendingSyncResult?.cancelAndJoin() }
            cleanup { model?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
            cleanup {
                listOf(targetId, siblingId).filter { it > 0 }.forEach { id ->
                    val row = dao.getDatabaseById(id)
                    check(row == null || row.name.startsWith(prefix))
                    KeePassKdbxService.invalidateProcessCache(id)
                    room.keepassPendingChangeDao().deleteByDatabase(id)
                    room.passwordEntryDao().deleteByKeePassDatabaseId(id)
                    room.secureItemDao().deleteByKeePassDatabaseId(id)
                    room.passkeyDao().deleteByKeePassDatabaseId(id)
                    room.keepassRemoteSyncStateDao().deleteState(id)
                    dao.deleteDatabaseById(id)
                }
                if (sourceId > 0) {
                    check(room.keepassRemoteSourceDao().getSourceById(sourceId)?.displayName == "$prefix WebDAV")
                    room.keepassRemoteSourceDao().deleteSourceById(sourceId)
                }
            }
            cleanup {
                if (rootCreated) {
                    check(root.canonicalFile.parentFile == context.filesDir.canonicalFile && root.name == prefix)
                    check(root.deleteRecursively())
                }
            }
            cleanup { if (started) server.shutdown() }
            cleanup { restoreHttp?.invoke() }
            cleanup { assertEquals(originalHttpPrefs, context.getSharedPreferences("webdav_config", 0).all) }
            cleanup { assertEquals("Original data must survive sync and cleanup", originalRows, protectedRows()) }
            cleanup { assertArrayEquals(ownerBytes, ownerFile.readBytes()) }
            cleanup { if (!originalUnlocked) compose.runOnUiThread { SessionManager.markLocked() } }
            cleanupFailure?.let { if (primary != null) primary!!.addSuppressed(it) else throw it }
        }
    }

    private fun fixtureEntry(id: UUID, note: String) = Entry(uuid = id, fields = EntryFields.of(
        "Title" to EntryValue.Plain("Original synthetic account"), "UserName" to EntryValue.Plain("synthetic-user"),
        "Password" to EntryValue.Encrypted(EncryptedValue.fromString(ENTRY_PASSWORD)), "Notes" to EntryValue.Plain(note),
        "ToolchainUnknown" to EntryValue.Plain("Preserved unknown property")))
    private fun fixtureDatabase(id: UUID): KeePassDatabase.Ver4x = KeePassDatabase.Ver4x.create(
        rootName = "Synthetic root", meta = Meta(generator = "Toolchain sync fixture", name = "Synthetic sync vault"), credentials = credentials
    ).let { created ->
        val seed = when (val kdf = created.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        created.copy(header = created.header.copy(kdfParameters = KdfParameters.Aes(rounds = 100U, seed = seed)))
    }.modifyParentGroup { copy(entries = listOf(fixtureEntry(id, "Original local note"))) } as KeePassDatabase.Ver4x
    private fun encode(database: KeePassDatabase): ByteArray = ByteArrayOutputStream().use { database.encode(it); it.toByteArray() }
    private fun decode(file: File): KeePassDatabase = file.inputStream().use { KeePassDatabase.decode(it, credentials) }

    private class Remote(initial: ByteArray) : Dispatcher() {
        @Volatile var bytes = initial
            private set
        @Volatile var etag = "\"toolchain-revision-1\""
            private set
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val errors = CopyOnWriteArrayList<String>()
        @Synchronized fun replace(value: ByteArray) { bytes = value; etag = "\"toolchain-revision-2\"" }
        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            if (request.path != "/fixture.kdbx") {
                errors += "Unexpected path"
                return MockResponse().setResponseCode(404)
            }
            return when (request.method) {
                "HEAD" -> MockResponse().setResponseCode(200).setHeader("ETag", etag)
                "GET" -> MockResponse().setResponseCode(200).setHeader("ETag", etag).setBody(Buffer().write(bytes))
                "PROPFIND" -> MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(
                    """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:"><d:response>
                    <d:href>/fixture.kdbx</d:href><d:propstat><d:prop><d:displayname>fixture.kdbx</d:displayname>
                    <d:getetag>${etag.replace("\"", "&quot;")}</d:getetag><d:getcontentlength>${bytes.size}</d:getcontentlength>
                    <d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    </d:response></d:multistatus>""".trimIndent())
                else -> { errors += "Unexpected write or method"; MockResponse().setResponseCode(405) }
            }
        }
    }

    /** Existing device-test loopback technique; original preferences are never edited. */
    private fun configureLoopbackHttpFixture(): () -> Unit {
        val attach = WebDavGateway::class.java.methods.firstOrNull {
            it.name == "attach" && it.parameterTypes.contentEquals(arrayOf(Context::class.java))
        } ?: return {}
        val names = CopyOnWriteArraySet<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val ownName = "$name-$prefix"
                names += ownName
                return super.getSharedPreferences(ownName, mode).also { check(it.edit().putBoolean("allow_insecure_http", true).commit()) }
            }
        }
        attach.invoke(WebDavGateway, isolated)
        return {
            attach.invoke(WebDavGateway, context)
            names.forEach { check(it.endsWith(prefix)); check(context.deleteSharedPreferences(it)) }
        }
    }
    private fun label(id: Int) = compose.activity.getString(id)
    private fun waitTag(tag: String) = compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(step: String, id: Long) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val output = File(captures, "$step.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val marker = File(captures, "$step.json")
        marker.writeText(JSONObject().put("step", step).put("uid", Process.myUid()).put("package", context.packageName)
            .put("databaseId", id).put("image", output.name).put("pause", false).put("state", "ready")
            .put("coverage", "actual production LocalKeePassScreen; synthetic loopback pull; normal font").toString(2))
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "TOOLCHAIN_UI_CAPTURE ${marker.absolutePath}\n") })
    }
    private fun protectedRows(): String {
        val result = JSONObject()
        listOf("password_entries", "secure_items", "custom_fields", "password_history_entries", "passkeys", "attachments",
            "local_keepass_databases", "local_mdbx_databases", "bitwarden_vaults", "keepass_remote_sources",
            "keepass_remote_sync_states", "keepass_pending_changes").forEach { table ->
            val rows = JSONArray()
            room.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    cursor.columnNames.sorted().forEach { name ->
                        val i = cursor.getColumnIndexOrThrow(name)
                        val value: Any = when (cursor.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(cursor.getBlob(i))
                            else -> cursor.getString(i)
                        }
                        row.put(name, value)
                    }
                    rows.put(row)
                }
            }
            result.put(table, rows)
        }
        return result.toString()
    }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private companion object {
        const val APP_PASSWORD = "Synthetic toolchain lifecycle 326!"
        const val VAULT_PASSWORD = "Synthetic KDBX synchronization 326!"
        const val ENTRY_PASSWORD = "Synthetic unchanged entry password 326!"
    }
}
