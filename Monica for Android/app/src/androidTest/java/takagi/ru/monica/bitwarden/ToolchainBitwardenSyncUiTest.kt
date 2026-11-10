package takagi.ru.monica.bitwarden

import android.database.Cursor
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.ui.BitwardenSettingsScreen
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.bitwarden.sync.SyncTriggerReason
import android.graphics.Bitmap
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.BitwardenAccountManagementState
import takagi.ru.monica.bitwarden.ui.BitwardenAccountManagementDialog
import takagi.ru.monica.credentialexchange.BitwardenImportAttachmentQueue
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenPendingOperation
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.RecoverableBitwardenSettings
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Actual settings sync button -> shared ViewModel/Repository -> encrypted loopback response.
 * Restricted to the already initialized disposable user13. No global MDK/singleton injection,
 * no production hook, real cloud account, password reset or encryption change.
 */
class ToolchainBitwardenSyncUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefix = "toolchain_sync_${UUID.randomUUID().toString().replace('-', '_')}"
    private lateinit var room: PasswordDatabase
    private lateinit var security: SecurityManager
    private lateinit var repository: BitwardenRepository
    private lateinit var ownerBytes: ByteArray
    private val ownerFile get() = File(context.filesDir, "toolchain-db-ui-owner.json")
    private val mutationPrefs get() = context.getSharedPreferences("bitwarden_account_mutations", 0)
    private val captures by lazy { File(context.filesDir, "toolchain-db-ui/$prefix").apply { check(mkdirs()) } }

    @Before fun requireOwnedInitializedUser() = runBlocking {
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
        check(room.bitwardenVaultDao().getAllVaults().isEmpty()) { "Refuse pre-existing Bitwarden accounts" }
        repository = BitwardenRepository.getInstance(context)
        check(!repository.isNeverLockEnabled) { "This fixture requires normal vault locking" }
        compose.runOnUiThread {
            compose.activity.setTurnScreenOn(true)
            compose.activity.setShowWhenLocked(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }

    @Test fun actualAccountSyncButtonDecryptsRemoteFolderAndPreservesOtherAccount() = runBlocking<Unit> {
        val originalKeys = protectedKeyDigests()
        val sentinel = security.encryptData("Synthetic BW sync MDK continuity")
        val originalRows = protectedRows()
        val originalUnlocked = SessionManager.isUnlocked.value
        val originalJournals = mutationPrefs.all.toMap()
        val settings = RecoverableBitwardenSettings.open(context)
        val originalSettings = settings.all.toMap()
        val target = Account("target", 37)
        val sibling = Account("sibling", 79)
        val store = ViewModelStore()
        var visible by mutableStateOf(true)
        var primary: Throwable? = null
        try {
            compose.runOnUiThread { SessionManager.markUnlocked() }
            target.initialize()
            sibling.initialize()
            val targetBefore = target.row()
            val siblingBefore = sibling.row()
            val siblingGeneration = repository.getVaultSessionGeneration(siblingBefore.id)
            val siblingTraffic = sibling.requests.size
            val model = compose.runOnUiThread {
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))
                    .get(BitwardenViewModel::class.java)
            }
            compose.setContent { MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("toolchain_bitwarden_sync_screen")) {
                    if (visible) BitwardenSettingsScreen(model, {}, {}, {}, {})
                }
            } }
            compose.waitUntil(10_000) { model.vaults.value.size == 2 }
            val cardTag = "bitwarden_vault_${targetBefore.id}"
            val accountNode = compose.onNode(hasText(target.email) and hasAnyAncestor(hasTestTag(cardTag)))
            accountNode.performScrollTo().performClick()
            val syncNode = compose.onNode(hasText(context.getString(R.string.legacy_ui_sync)) and hasAnyAncestor(hasTestTag(cardTag)))
            syncNode.performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertEquals(0, target.syncs())
            assertEquals(0, sibling.syncs())
            assertNull(room.bitwardenFolderDao().getFolderByBitwardenId(target.folderId))
            capture("bitwarden-sync-before")
            syncNode.performClick()
            compose.waitUntil(30_000) { model.syncStatusByVault.value[targetBefore.id]?.let {
                !it.isRunning && it.lastSuccessAt != null && it.lastTriggerReason == SyncTriggerReason.MANUAL && it.lastError == null
            } == true }
            assertEquals(1, target.syncs())
            assertEquals(0, sibling.syncs())
            assertEquals(siblingTraffic, sibling.requests.size)
            val folder = checkNotNull(room.bitwardenFolderDao().getFolderByBitwardenId(target.folderId))
            assertEquals(targetBefore.id, folder.vaultId)
            assertEquals(target.folderName, folder.name)
            assertEquals(target.encryptedFolder, folder.encryptedName)
            assertNotEquals(target.folderName, folder.encryptedName)
            assertTrue(checkNotNull(target.row().lastSyncAt) > 0)
            assertEquals(targetBefore.encryptedMasterKey, target.row().encryptedMasterKey)
            assertEquals(targetBefore.encryptedEncKey, target.row().encryptedEncKey)
            assertEquals(targetBefore.encryptedMacKey, target.row().encryptedMacKey)
            assertEquals(siblingBefore, sibling.row())
            assertEquals(siblingGeneration, repository.getVaultSessionGeneration(siblingBefore.id))
            target.assertCachedUserKey()
            sibling.assertCachedUserKey()
            assertEquals(originalJournals, mutationPrefs.all)
            assertTrue(target.serverErrors.isEmpty() && sibling.serverErrors.isEmpty())
            compose.onNodeWithTag(cardTag).performScrollTo().assertIsDisplayed()
            capture("bitwarden-sync-completed")
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
            cleanup { compose.runOnIdle { visible = false; store.clear() }; compose.waitForIdle() }
            cleanup { target.close() }
            cleanup { sibling.close() }
            cleanup {
                val editor = settings.edit()
                listOf("active_vault_id", "last_sync_time").forEach { key ->
                    if (originalSettings.containsKey(key)) editor.putLong(key, originalSettings[key] as Long)
                    else editor.remove(key)
                }
                check(editor.commit())
                assertEquals(originalSettings, settings.all)
            }
            cleanup { assertEquals(originalJournals, mutationPrefs.all) }
            cleanup { assertEquals("Original protected data must survive", originalRows, protectedRows()) }
            cleanup { assertArrayEquals(ownerBytes, ownerFile.readBytes()) }
            cleanup { assertEquals(originalKeys, protectedKeyDigests()); assertEquals("Synthetic BW sync MDK continuity", security.decryptData(sentinel)) }
            cleanup { if (!originalUnlocked) compose.runOnUiThread { SessionManager.markLocked() } }
            cleanupFailure?.let { if (primary != null) primary!!.addSuppressed(it) else throw it }
        }
    }

    private inner class Account(kind: String, seed: Int) {
        val userId = UUID.randomUUID().toString()
        val email = "$prefix-$kind@example.invalid"
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val serverErrors = CopyOnWriteArrayList<String>()
        private val server = MockWebServer()
        private val userKey = SymmetricCryptoKey(ByteArray(32) { seed.toByte() }, ByteArray(32) { (seed + 11).toByte() })
        val folderId = UUID.randomUUID().toString()
        val folderName = "Synthetic synced folder $kind"
        val encryptedFolder = BitwardenCrypto.encryptString(folderName, userKey)
        private var started = false
        private var endpoint: String? = null
        private var vaultId: Long? = null
        private val ownedRefs = linkedSetOf<String>()
        lateinit var passwordHash: String
            private set

        suspend fun initialize() {
            val master = BitwardenCrypto.deriveMasterKeyPbkdf2(ACCOUNT_PASSWORD, email, 600_000)
            val stretched = BitwardenCrypto.stretchMasterKey(master)
            val wrapped: String
            try {
                passwordHash = BitwardenCrypto.deriveMasterPasswordHash(master, ACCOUNT_PASSWORD)
                val bytes = userKey.encKey + userKey.macKey
                try { wrapped = BitwardenCrypto.encrypt(bytes, stretched) } finally { bytes.fill(0) }
            } finally { master.fill(0); stretched.clear() }
            val token = "synthetic-delete-$userId"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@Account) {
                    requests += request
                    try {
                        when (request.requestUrl!!.encodedPath) {
                            "/identity/accounts/prelogin" -> ok(buildJsonObject { put("kdf", 0); put("kdfIterations", 600_000) })
                            "/identity/connect/token" -> {
                                val form = request.body.clone().readUtf8().split('&').associate {
                                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                                }
                                check(form["grant_type"] == "password" && form["username"] == email && form["password"] == passwordHash)
                                ok(buildJsonObject { put("access_token", token); put("refresh_token", "synthetic-refresh-$userId")
                                    put("expires_in", 3600); put("Key", wrapped) })
                            }
                            "/api/accounts/profile" -> {
                                check(request.method == "GET" && request.getHeader("Authorization") == "Bearer $token")
                                ok(buildJsonObject { put("id", userId); put("email", email); put("name", "Synthetic account")
                                    put("securityStamp", "synthetic-$userId"); put("key", wrapped); put("masterPasswordHint", "Synthetic hint")
                                    put("organizations", JsonArray(emptyList())); put("usesKeyConnector", false) })
                            }
                            "/api/sync" -> {
                                check(request.method == "GET" && request.getHeader("Authorization") == "Bearer $token")
                                ok(buildJsonObject {
                                    put("profile", buildJsonObject { put("id", userId); put("email", email); put("name", "Synthetic account")
                                        put("securityStamp", "synthetic-$userId"); put("key", wrapped)
                                        put("organizations", JsonArray(emptyList())); put("usesKeyConnector", false) })
                                    put("folders", buildJsonArray { add(buildJsonObject { put("id", folderId)
                                        put("name", encryptedFolder); put("revisionDate", "2026-11-09T00:00:00Z") }) })
                                    put("ciphers", JsonArray(emptyList())); put("collections", JsonArray(emptyList()))
                                    put("sends", JsonArray(emptyList())); put("policies", JsonArray(emptyList()))
                                })
                            }
                            else -> { serverErrors += "Unexpected route"; MockResponse().setResponseCode(404) }
                        }
                    } catch (error: Throwable) {
                        serverErrors += error.javaClass.simpleName
                        MockResponse().setResponseCode(500)
                    }
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            started = true
            val base = server.url("/").toString().trimEnd('/').also { endpoint = it }
            val environment = requireNotNull(BitwardenEnvironmentResolver.resolve(BitwardenEnvironmentDraft(
                BitwardenEnvironmentPreset.CUSTOM, base, apiUrl = "$base/api/", identityUrl = "$base/identity/",
                webUrl = "$base/web/", notificationsUrl = "$base/notifications/"), UUID.randomUUID().toString()).value)
            assertTrue(repository.login(base, email, ACCOUNT_PASSWORD, environment = environment) is BitwardenRepository.RepositoryLoginResult.Success)
            val saved = row()
            vaultId = saved.id
            saved.transportSecretRef?.let { ownedRefs += it }
            assertEquals(BitwardenAccountManagementState.READY, repository.loadAccountManagement(saved.id).getOrThrow().state)

        }

        suspend fun row(): BitwardenVault = room.bitwardenVaultDao().getAllVaults().single { it.email == email && it.serverUrl.trimEnd('/') == endpoint }
        fun syncs() = requests.count { it.method == "GET" && it.requestUrl!!.encodedPath == "/api/sync" }
        fun assertCachedUserKey() {
            val key = checkNotNull(repository.getCachedSymmetricKey(checkNotNull(vaultId)))
            try { assertArrayEquals(userKey.encKey, key.encKey); assertArrayEquals(userKey.macKey, key.macKey) }
            finally { key.clear() }
        }
        suspend fun close() {
            try {
                room.bitwardenVaultDao().getAllVaults().filter { it.email == email && it.serverUrl.trimEnd('/') == endpoint }.forEach { row ->
                    row.transportSecretRef?.let { ownedRefs += it }
                    context.getSharedPreferences("credential_import_attachments_v1", 0).edit().remove("vault:${row.id}").commit()
                    check(repository.logout(row.id))
                }
                ownedRefs.forEach { ref ->
                    check(room.bitwardenVaultDao().getAllVaults().none { it.transportSecretRef == ref })
                    security.removeProtectedString(ref)
                }
            } finally {
                try { if (started) server.shutdown() }
                finally { userKey.clear() }
            }
        }
    }

    private fun ok(body: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun capture(step: String) {
        compose.waitForIdle()
        val file = File(captures, "$step.png")
        val bitmap = compose.onNodeWithTag("toolchain_bitwarden_sync_screen").captureToImage().asAndroidBitmap()
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val marker = File(captures, "$step.json")
        marker.writeText(JSONObject().put("step", step).put("uid", Process.myUid()).put("package", context.packageName)
            .put("image", file.name).put("pause", false).put("state", "ready")
            .put("coverage", "real settings sync button, encrypted loopback folder; synthetic data only").toString(2))
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "TOOLCHAIN_UI_CAPTURE ${marker.absolutePath}\n") })
    }
    private fun protectedKeyDigests() = listOf("master_password_hash", "master_password_salt",
        "mdk_password_blob", "mdk_password_salt", "mdk_keystore_blob").associateWith { key ->
        security.getProtectedString(key)?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
    }
    private fun protectedRows(): String {
        val result = JSONObject()
        listOf("password_entries", "secure_items", "custom_fields", "password_history_entries", "passkeys", "attachments",
            "local_keepass_databases", "local_mdbx_databases", "bitwarden_vaults", "bitwarden_pending_operations", "bitwarden_folders", "categories").forEach { table ->
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
            result.put(table, rows)
        }
        return result.toString()
    }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private companion object {
        const val APP_PASSWORD = "Synthetic toolchain lifecycle 326!"
        const val ACCOUNT_PASSWORD = "Synthetic loopback deletion password 326!"
    }
}
