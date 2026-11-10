package takagi.ru.monica.bitwarden

import android.database.Cursor
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

/** Actual management Dialog -> shared Repository -> two loopback servers and durable local data.
 * Restricted to the already initialized disposable user13. No global MDK/singleton injection,
 * no production hook, real cloud account, password reset or encryption change.
 */
class ToolchainBitwardenDeleteUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefix = "toolchain_delete_${UUID.randomUUID().toString().replace('-', '_')}"
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
        compose.runOnUiThread {
            compose.activity.setTurnScreenOn(true)
            compose.activity.setShowWhenLocked(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }

    @Test fun actualDeleteReviewCancelAndConfirmPreserveLocalCopyAndOtherAccount() = runBlocking<Unit> {
        val originalRows = protectedRows()
        val originalUnlocked = SessionManager.isUnlocked.value
        val originalJournals = mutationPrefs.all.toMap()
        val settings = RecoverableBitwardenSettings.open(context)
        val originalSettings = settings.all.toMap()
        val target = Account("target", 37)
        val sibling = Account("sibling", 79)
        var visible by mutableStateOf(true)
        var dismissals = 0
        var reauthentications = 0
        var primary: Throwable? = null
        try {
            compose.runOnUiThread { SessionManager.markUnlocked() }
            target.initialize()
            sibling.initialize()
            val targetBefore = target.row()
            val siblingBefore = sibling.row()
            val siblingGeneration = repository.getVaultSessionGeneration(siblingBefore.id)
            val siblingTransport = siblingBefore.transportSecretRef?.let(security::getProtectedString)
            assertNotNull(siblingTransport)
            assertTrue(repository.isVaultUnlocked(targetBefore.id) && repository.isVaultUnlocked(siblingBefore.id))
            compose.setContent {
                MaterialTheme {
                    if (visible) BitwardenAccountManagementDialog(targetBefore.id,
                        onDismiss = { dismissals++; visible = false }, onReauthenticate = { reauthentications++ })
                }
            }
            waitTag("bitwarden_management_open_delete")
            choose("bitwarden_management_open_delete")
            compose.onNodeWithTag("bitwarden_management_identity_email").assertTextEquals(target.email)
            fill("bitwarden_management_current_password", ACCOUNT_PASSWORD)
            fill("bitwarden_management_confirm_email", " ${target.email} ")
            compose.onNodeWithTag("bitwarden_management_review").assertIsNotEnabled()
            fill("bitwarden_management_confirm_email", target.email)
            compose.onNodeWithTag("bitwarden_management_review").assertIsEnabled().performClick()
            waitTag("bitwarden_management_submit")
            compose.onNodeWithTag("bitwarden_management_review_action").performScrollTo().assertIsDisplayed()
            assertEquals(0, target.deletions().size)
            assertEquals(0, sibling.deletions().size)
            assertEquals(originalJournals, mutationPrefs.all)
            capture("bitwarden-delete-review-before-cancel")
            choose("bitwarden_management_back")
            waitTag("bitwarden_management_open_delete")
            assertEquals(targetBefore, target.row())
            assertEquals(siblingBefore, sibling.row())
            target.assertLocalUnchanged()
            sibling.assertLocalUnchanged()
            assertEquals(originalJournals, mutationPrefs.all)
            assertEquals(0, target.deletions().size)
            assertEquals(0, sibling.deletions().size)

            choose("bitwarden_management_open_delete")
            compose.onNodeWithTag("bitwarden_management_current_password")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("bitwarden_management_confirm_email")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            fill("bitwarden_management_current_password", ACCOUNT_PASSWORD)
            fill("bitwarden_management_confirm_email", target.email)
            compose.onNodeWithTag("bitwarden_management_review").assertIsEnabled().performClick()
            waitTag("bitwarden_management_submit")
            assertEquals(0, target.deletions().size)
            compose.onNodeWithTag("bitwarden_management_submit").assertIsEnabled().performClick()
            waitTag("bitwarden_management_local_password", 60_000)
            assertEquals(1, target.deletions().size)
            assertEquals(0, sibling.deletions().size)
            val request = target.deletions().single()
            val body = json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
            assertEquals(setOf("MasterPasswordHash", "otp"), body.keys)
            assertEquals(target.passwordHash, body["MasterPasswordHash"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, body["otp"])
            assertEquals(BitwardenAccountManagementState.DELETED_LOCAL_COPY,
                repository.loadAccountManagement(targetBefore.id).getOrThrow().state)
            val afterDelete = target.row()
            assertEquals(targetBefore.encryptedMasterKey, afterDelete.encryptedMasterKey)
            assertEquals(targetBefore.encryptedEncKey, afterDelete.encryptedEncKey)
            assertEquals(targetBefore.encryptedMacKey, afterDelete.encryptedMacKey)
            assertEquals(targetBefore.kdfType, afterDelete.kdfType)
            assertEquals(targetBefore.kdfIterations, afterDelete.kdfIterations)
            assertEquals(targetBefore.kdfMemory, afterDelete.kdfMemory)
            assertEquals(targetBefore.kdfParallelism, afterDelete.kdfParallelism)
            assertNull(afterDelete.encryptedAccessToken)
            assertNull(afterDelete.encryptedRefreshToken)
            assertTrue(repository.isAccountMutationBlocked(targetBefore.id))
            val journal = checkNotNull(mutationPrefs.getString(target.journalKey(), null))
            assertTrue(journal.startsWith("MDK|"))
            assertFalse(journal.contains(ACCOUNT_PASSWORD))
            assertEquals(originalJournals.keys + target.journalKey(), mutationPrefs.all.keys)
            assertEquals(siblingBefore, sibling.row())
            assertEquals(siblingGeneration, repository.getVaultSessionGeneration(siblingBefore.id))
            assertEquals(siblingTransport, siblingBefore.transportSecretRef?.let(security::getProtectedString))
            assertFalse(repository.isAccountMutationBlocked(siblingBefore.id))
            target.assertLocalUnchanged()
            sibling.assertLocalUnchanged()
            sibling.assertCachedUserKey()
            capture("bitwarden-delete-local-copy")

            val targetTrafficAfterDelete = target.requests.size
            // The real dialog dispatches the original password through Repository.unlock locally.
            fill("bitwarden_management_local_password", ACCOUNT_PASSWORD)
            choose("bitwarden_management_unlock_local")
            waitTag("bitwarden_management_local_unlocked", 60_000)
            assertTrue(repository.isVaultUnlocked(targetBefore.id))
            target.assertCachedUserKey()
            assertEquals(targetTrafficAfterDelete, target.requests.size)
            assertTrue(repository.getAccountDevices(targetBefore.id).isFailure)
            assertEquals(targetTrafficAfterDelete, target.requests.size)
            target.assertLocalUnchanged()
            sibling.assertLocalUnchanged()
            assertEquals(siblingBefore, sibling.row())
            assertEquals(0, sibling.deletions().size)
            capture("bitwarden-delete-original-password-unlocked")
            choose("bitwarden_management_close")
            compose.waitUntil(10_000) { !visible }
            assertEquals(1, dismissals)
            assertEquals(0, reauthentications)
            // Reauthenticate the unaffected sibling locally after checking its exact frozen row.
            repository.forceLock(siblingBefore.id)
            val siblingBeforeUnlock = sibling.requests.size
            assertEquals(BitwardenRepository.UnlockResult.Success, repository.unlock(siblingBefore.id, ACCOUNT_PASSWORD))
            assertEquals(siblingBeforeUnlock, sibling.requests.size)
            sibling.assertCachedUserKey()
            sibling.assertLocalUnchanged()
            assertEquals(BitwardenAccountManagementState.READY,
                repository.loadAccountManagement(siblingBefore.id).getOrThrow().state)
            assertEquals(0, sibling.deletions().size)
            assertEquals(1, target.deletions().size)
            assertTrue(target.serverErrors.isEmpty() && sibling.serverErrors.isEmpty())
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
            cleanup { compose.runOnIdle { visible = false }; compose.waitForIdle() }
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
        private val cipherId = UUID.randomUUID().toString()
        private val attachmentBytes = "Synthetic $kind attachment preserved after server deletion".toByteArray()
        private var started = false
        private var deleted = false
        private var endpoint: String? = null
        private var vaultId: Long? = null
        private var passwordId: Long? = null
        private var attachment: Attachment? = null
        private var operationId: Long? = null
        private var baselinePassword: PasswordEntry? = null
        private var baselineOperation: BitwardenPendingOperation? = null
        private var baselineCiphertext: ByteArray? = null
        private var pendingIds: Set<Long> = emptySet()
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
                                check(!deleted && form["grant_type"] == "password" && form["username"] == email && form["password"] == passwordHash)
                                ok(buildJsonObject { put("access_token", token); put("refresh_token", "synthetic-refresh-$userId")
                                    put("expires_in", 3600); put("Key", wrapped) })
                            }
                            "/api/accounts/profile" -> {
                                check(request.method == "GET" && !deleted && request.getHeader("Authorization") == "Bearer $token")
                                ok(buildJsonObject { put("id", userId); put("email", email); put("name", "Synthetic account")
                                    put("securityStamp", "synthetic-$userId"); put("key", wrapped); put("masterPasswordHint", "Synthetic hint")
                                    put("organizations", JsonArray(emptyList())); put("usesKeyConnector", false) })
                            }
                            "/api/accounts" -> {
                                check(request.method == "DELETE" && !deleted && request.getHeader("Authorization") == "Bearer $token")
                                val body = json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                                check(body["MasterPasswordHash"]!!.jsonPrimitive.content == passwordHash)
                                deleted = true
                                MockResponse().setResponseCode(200)
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
            passwordId = room.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "$prefix-$userId",
                website = "https://synthetic.example.invalid", username = "synthetic", password = security.encryptData("Synthetic preserved local password"),
                bitwardenVaultId = saved.id, bitwardenCipherId = cipherId, bitwardenLocalModified = true))
            operationId = room.bitwardenPendingOperationDao().insert(BitwardenPendingOperation(vaultId = saved.id,
                entryId = passwordId, bitwardenCipherId = cipherId, operationType = "UPDATE", targetType = "CIPHER", payloadJson = "{\"syntheticPending\":true}"))
            val facade = AttachmentContainer.facade(context)
            attachment = facade.addStreamAttachment(AttachmentFacade.StreamUploadRequest(AttachmentOwner.password(checkNotNull(passwordId)),
                AttachmentSource.LOCAL, "Synthetic $userId", "application/octet-stream", attachmentBytes.size.toLong(), { attachmentBytes.inputStream() }, true))
            BitwardenImportAttachmentQueue(context).enqueueAttachment(saved.id, checkNotNull(attachment).id)
            baselinePassword = room.passwordEntryDao().getPasswordEntryById(checkNotNull(passwordId))
            baselineOperation = room.bitwardenPendingOperationDao().getById(checkNotNull(operationId))
            attachment = facade.getById(checkNotNull(attachment).id)
            baselineCiphertext = attachmentFile(checkNotNull(attachment)).readBytes()
            pendingIds = BitwardenImportAttachmentQueue(context).observePendingIds(saved.id).first()
        }

        suspend fun row(): BitwardenVault = room.bitwardenVaultDao().getAllVaults().single { it.email == email && it.serverUrl.trimEnd('/') == endpoint }
        fun journalKey() = "bitwarden_account_mutation_v1_${checkNotNull(vaultId)}"
        fun deletions() = requests.filter { it.method == "DELETE" && it.requestUrl!!.encodedPath == "/api/accounts" }
        fun assertCachedUserKey() {
            val key = checkNotNull(repository.getCachedSymmetricKey(checkNotNull(vaultId)))
            try { assertArrayEquals(userKey.encKey, key.encKey); assertArrayEquals(userKey.macKey, key.macKey) }
            finally { key.clear() }
        }
        suspend fun assertLocalUnchanged() {
            assertEquals(baselinePassword, room.passwordEntryDao().getPasswordEntryById(checkNotNull(passwordId)))
            assertEquals(baselineOperation, room.bitwardenPendingOperationDao().getById(checkNotNull(operationId)))
            val original = checkNotNull(attachment)
            val facade = AttachmentContainer.facade(context)
            assertEquals(original, facade.getById(original.id))
            assertArrayEquals(baselineCiphertext, attachmentFile(original).readBytes())
            assertArrayEquals(attachmentBytes, facade.readAttachmentBytes(original.id, 4096))
            assertEquals(pendingIds, BitwardenImportAttachmentQueue(context).observePendingIds(checkNotNull(vaultId)).first())
        }
        suspend fun close() {
            try {
                attachment?.let { AttachmentContainer.facade(context).forgetLocalAttachment(it.id) }
                passwordId?.let { room.passwordEntryDao().deletePasswordEntryById(it) }
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
                finally { userKey.clear(); baselineCiphertext?.fill(0) }
            }
        }
    }

    private fun ok(body: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun attachmentFile(attachment: Attachment): File = File(context.filesDir, "secure_attachments/${attachment.localPath}").also {
        check(it.canonicalPath.startsWith(File(context.filesDir, "secure_attachments").canonicalPath + File.separator))
    }
    private fun choose(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }
    private fun fill(tag: String, value: String) {
        val node = compose.onNodeWithTag(tag)
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().performTextReplacement(value)
    }
    private fun waitTag(tag: String, timeout: Long = 20_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(step: String) {
        compose.waitForIdle()
        val file = File(captures, "$step.png")
        val bitmap = compose.onNodeWithTag("bitwarden_management_dialog").captureToImage().asAndroidBitmap()
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val marker = File(captures, "$step.json")
        marker.writeText(JSONObject().put("step", step).put("uid", Process.myUid()).put("package", context.packageName)
            .put("image", file.name).put("pause", false).put("state", "ready")
            .put("coverage", "real management Dialog, loopback servers; synthetic data only").toString(2))
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "TOOLCHAIN_UI_CAPTURE ${marker.absolutePath}\n") })
    }
    private fun protectedRows(): String {
        val result = JSONObject()
        listOf("password_entries", "secure_items", "custom_fields", "password_history_entries", "passkeys", "attachments",
            "local_keepass_databases", "local_mdbx_databases", "bitwarden_vaults", "bitwarden_pending_operations").forEach { table ->
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
