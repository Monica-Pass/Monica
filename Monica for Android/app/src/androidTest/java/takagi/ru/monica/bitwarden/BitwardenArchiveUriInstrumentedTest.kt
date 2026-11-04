package takagi.ru.monica.bitwarden

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.sync.BitwardenMutationSyncBridge
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.ui.LocalUiSecurityManager
import takagi.ru.monica.ui.components.ActionStrip
import takagi.ru.monica.ui.components.rememberBitwardenArchiveAction
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PasswordViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import takagi.ru.monica.data.model.PasswordUrlRules
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.service.*
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager


/** Real Room, encrypted source and mock HTTP round trips, without external accounts. */
@RunWith(AndroidJUnit4::class)
class BitwardenArchiveUriInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val localized get() = android.view.ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
        applyOverrideConfiguration(Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) })
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = PasswordDatabase.getDatabase(context)

    @Test fun nativeUriRulesReachTheEditorAndEditedDefaultRetainsDuplicateRowsAndMetadata(): Unit = runBlocking {
        scenario {
            seed("uri", 1)
            val raw = "http://192.0.2.1:30080/Path?q=A%20B#fragment"
            val source = remote.getValue("uri")
            val uris = JsonArray(listOf(1, 3).map { mode -> buildJsonObject {
                put("uri", BitwardenCrypto.encryptString(raw, key)); put("match", mode); put("FutureUri", "row-$mode")
            } })
            val userFields = listOf(Triple("Plain field", "synthetic visible value", 0), Triple("Hidden field", "synthetic hidden value", 1))
            remote["uri"] = JsonObject(source + ("login" to JsonObject(source["login"]!!.jsonObject + ("uris" to uris))) +
                ("fields" to JsonArray(userFields.map { (name, value, type) -> buildJsonObject {
                    put("name", BitwardenCrypto.encryptString(name, key)); put("value", BitwardenCrypto.encryptString(value, key)); put("type", type)
                } })))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "uri"))
            val fields = db.customFieldDao().getFieldsByEntryIdSync(entry.id)
            val field = fields.single { it.title == PasswordUrlRules.FIELD }
            val rules = requireNotNull(PasswordUrlRules.decode(field.value))
            assertEquals(listOf(PasswordUrlRules.Mode.HOST, PasswordUrlRules.Mode.EXACT), rules.map { it.mode })
            assertEquals(listOf(raw, raw), rules.map { it.url })
            val edited = rules.mapIndexed { index, rule -> if (index == 0) rule.copy(mode = PasswordUrlRules.Mode.DEFAULT) else rule }
            val settings = SettingsManager(context)
            val previousContent = settings.settingsFlow.first().passwordContentEditorEnabled
            settings.updatePasswordContentEditorEnabled(true)
            val model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao(),
                bitwardenFolderDao = db.bitwardenFolderDao(), passwordHistoryDao = db.passwordHistoryDao()), security,
                customFieldRepository = CustomFieldRepository(db.customFieldDao()), context = context,
                strings = AppLocaleStringResolver(context))
            val saveCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
            val editorEpoch = mutableStateOf(0)
            val english = localized
            try {
                compose.setContent { CompositionLocalProvider(LocalUiSecurityManager provides security,
                    LocalContext provides english, LocalConfiguration provides english.resources.configuration) {
                    MaterialTheme { androidx.compose.runtime.key(editorEpoch.value) {
                        AddEditPasswordScreen(model, passwordId = entry.id, initialStorageExplicit = true,
                            onSaveCompleted = { saveCompleted.set(true) }, onNavigateBack = {})
                    } }
                } }
                compose.waitUntil(15000) { compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("url_actions_0"))
                compose.onNodeWithTag("url_actions_0").performScrollTo().performClick()
                compose.onNodeWithTag("url_match_action_0").performClick()
                compose.onNodeWithTag("url_match_DEFAULT").performScrollTo().performClick()
                compose.onNodeWithTag("url_match_confirm").performClick()
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.onNodeWithTag("password_editor_save").performClick()
                compose.waitUntil(30000) { saveCompleted.get() }
                val stored = db.customFieldDao().getFieldsByEntryIdSync(entry.id).single { it.title == PasswordUrlRules.FIELD }
                assertEquals(PasswordUrlRules.encode(edited, native = true), stored.value)
                val savedCurrentFields = db.customFieldDao().getFieldsByEntryIdSync(entry.id).map {
                    Triple(it.title, it.value, if (it.isProtected) 1 else 0)
                }
                assertTrue(savedCurrentFields.containsAll(userFields))
                val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
                assertEquals(1, result.uploaded); assertEquals(0, result.failed)
                val saved = remote.getValue("uri")["login"]!!.jsonObject["uris"]!!.jsonArray
                assertEquals(2, saved.size)
                assertEquals(JsonNull, saved[0].jsonObject["match"])
                assertEquals(JsonPrimitive(3), saved[1].jsonObject["match"])
                assertEquals(listOf("row-1", "row-3"), saved.map { it.jsonObject["FutureUri"]!!.jsonPrimitive.content })
                assertEquals(listOf(raw, raw), saved.map { BitwardenCrypto.decryptToString(it.jsonObject["uri"]!!.jsonPrimitive.content, key) })
                val uploadedFields = remote.getValue("uri")["fields"]!!.jsonArray.map { row ->
                    val field = row.jsonObject
                    Triple(BitwardenCrypto.decryptToString(field["name"]!!.jsonPrimitive.content, key),
                        BitwardenCrypto.decryptToString(field["value"]!!.jsonPrimitive.content, key), field["type"]!!.jsonPrimitive.int)
                }
                assertTrue(uploadedFields.containsAll(userFields))
                assertTrue("Remote custom fields must never contain the local MDK envelope", uploadedFields.none { it.second.startsWith("MDK|") })
                assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
                val reloaded = db.customFieldDao().getFieldsByEntryIdSync(entry.id).single { it.title == PasswordUrlRules.FIELD }
                assertEquals(edited, PasswordUrlRules.decode(reloaded.value))
                val coldFields = db.customFieldDao().getFieldsByEntryIdSync(entry.id).map {
                    Triple(it.title, it.value, if (it.isProtected) 1 else 0)
                }
                assertTrue(coldFields.containsAll(userFields))
                assertEquals(1, coldFields.count { it.first == "Plain field" })
                assertEquals(1, coldFields.count { it.first == "Hidden field" })
                compose.runOnIdle { editorEpoch.value += 1 }
                compose.waitForIdle()
                compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("url_actions_0"))
                compose.onNodeWithTag("url_actions_0").performScrollTo().performClick()
                compose.onNodeWithTag("url_match_action_0").performClick()
                compose.onNodeWithTag("url_match_DEFAULT").assertIsSelected()
                compose.onNodeWithTag("url_match_confirm").performClick()
                compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("url_actions_1"))
                compose.onNodeWithTag("url_actions_1").performScrollTo().performClick()
                compose.onNodeWithTag("url_match_action_1").performClick()
                compose.onNodeWithTag("url_match_EXACT").assertIsSelected()
                compose.onNodeWithTag("url_match_confirm").performClick()
                compose.onNodeWithTag("password_editor_save").assertIsEnabled()
                compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("content_panel_CUSTOM_FIELDS"))
                compose.onNodeWithTag("content_panel_CUSTOM_FIELDS").performScrollTo().performClick()
                compose.onNodeWithText("synthetic visible value").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("synthetic hidden value").assertDoesNotExist()
                compose.onNodeWithText("Hidden field").performScrollTo().performClick()
                compose.onNodeWithContentDescription(english.getString(R.string.custom_field_show_content)).performScrollTo().performClick()
                compose.onNodeWithText("synthetic hidden value").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("content_detail_back").performClick()
            } finally { model.viewModelScope.cancel(); settings.updatePasswordContentEditorEnabled(previousContent) }
        }
    }

    @Test fun secureArchiveAndUnarchiveUseExplicitIntentPreserveAllFieldsAndMoveBetweenLists(): Unit = runBlocking {
        scenario {
            for (type in 2..4) seed("archive$type", type)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val originals = remote.toMap()
            val store = BitwardenSecureItemArchiveStore(context)
            assertEquals(3, store.observe().first().count { it.bitwardenVaultId == vault.id })
            assertEquals(0, db.secureItemDao().getAllItems().first().count { it.bitwardenVaultId == vault.id })
            for (type in 2..4) {
                val item = requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "archive$type"))
                assertTrue(item.isArchived)
                val pending = store.setArchived(item, false)
                assertEquals(false, pending.bitwardenArchiveIntent)
                assertTrue(pending.bitwardenLocalModified)
            }
            val first = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(3, first.uploaded); assertEquals(0, first.failed)
            for (type in 2..4) {
                val actual = remote.getValue("archive$type")
                assertEquals(JsonNull, actual["archivedDate"])
                for (field in listOf("FutureEnvelope", "passwordHistory", "attachments")) assertEquals(originals.getValue("archive$type")[field], actual[field])
                val item = requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "archive$type"))
                assertNull(item.bitwardenArchiveIntent)
                store.setArchived(item, true)
            }
            val second = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals("Every native type must upload its second archive mutation: $second", 3, second.uploaded)
            assertEquals(0, second.failed)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            assertEquals(3, store.observe().first().count { it.bitwardenVaultId == vault.id })
            assertEquals(0, db.secureItemDao().getAllItems().first().count { it.bitwardenVaultId == vault.id })
        }
    }

    @Test fun archiveRejectsStaleItemLockedSessionAndReadOnlySourceWithoutChangingLocalState(): Unit = runBlocking {
        scenario {
            seed("guards", 2)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val item = requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "guards"))
            val store = BitwardenSecureItemArchiveStore(context)
            suspend fun rejected(block: suspend () -> Unit) {
                var failed = false
                try { block() } catch (_: IllegalStateException) { failed = true } catch (_: IllegalArgumentException) { failed = true }
                assertTrue("Unsafe archive accepted", failed)
            }
            db.secureItemDao().update(item.copy(title = "newer local title"))
            rejected { store.setArchived(item, false) }
            assertEquals("newer local title", db.secureItemDao().getItemById(item.id)?.title)
            db.secureItemDao().update(item)
            SessionManager.markLocked()
            rejected { store.setArchived(item, false) }
            SessionManager.markUnlocked()
            val source = remote.getValue("guards")
            remote["guards"] = JsonObject(source + ("edit" to JsonPrimitive(false)) + ("revisionDate" to JsonPrimitive(revision2)))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val locked = requireNotNull(db.secureItemDao().getItemById(item.id))
            rejected { store.setArchived(locked, false) }
            assertEquals(locked, db.secureItemDao().getItemById(item.id))
            assertTrue(bodies.isEmpty())
        }
    }

    @Test fun sameRevisionUpgradeReplacesOnlyTheOldLocalUriMetadata(): Unit = runBlocking {
        scenario {
            seed("upgrade-uri", 1)
            val source = remote.getValue("upgrade-uri")
            remote["upgrade-uri"] = JsonObject(source + ("login" to JsonObject(source["login"]!!.jsonObject + ("uris" to buildJsonArray {
                add(buildJsonObject { put("uri", BitwardenCrypto.encryptString("http://192.0.2.1", key)); put("match", 1) })
            }))))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "upgrade-uri"))
            val field = db.customFieldDao().getFieldsByEntryIdSync(entry.id).single { it.title == PasswordUrlRules.FIELD }
            db.customFieldDao().insert(field.copy(value = PasswordUrlRules.encode(listOf(PasswordUrlRules.Rule("old", PasswordUrlRules.Mode.NEVER)))))
            db.customFieldDao().insert(CustomField(entryId = entry.id, title = "Local user field", value = "keep", isProtected = false, sortOrder = 4))
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val fields = db.customFieldDao().getFieldsByEntryIdSync(entry.id)
            assertEquals(listOf(PasswordUrlRules.Rule("http://192.0.2.1", PasswordUrlRules.Mode.HOST)),
                PasswordUrlRules.decode(fields.single { it.title == PasswordUrlRules.FIELD }.value))
            assertEquals("keep", fields.single { it.title == "Local user field" }.value)
        }
    }

    @Test fun archiveConfirmationCancelAndUnarchiveUseTheProductionActionAndLiveShelf(): Unit = runBlocking {
        scenario {
            (2..4).forEach { seed("ui$it", it) }
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val store = BitwardenSecureItemArchiveStore(context)
            for (type in 2..4) store.setArchived(requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "ui$type")), false)
            syncRequests.clear()
            val current = mutableStateOf<SecureItem?>(null)
            val english = localized
            compose.setContent { CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides english.resources.configuration) {
                MaterialTheme { Column {
                    val archived by remember { store.observe() }.collectAsState(initial = emptyList())
                    Text("shelf:${archived.count { it.bitwardenVaultId == vault.id }}")
                    val action = rememberBitwardenArchiveAction(current.value) { current.value = it }
                    if (action != null) ActionStrip(listOf(action))
                } }
            } }
            for (type in 2..4) {
                val item = requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "ui$type"))
                compose.runOnIdle { current.value = item }
                compose.onNodeWithContentDescription(english.getString(R.string.archive_action)).performClick()
                compose.onNodeWithText(english.getString(R.string.archive_password_confirmation_title)).assertIsDisplayed()
                compose.onNodeWithText(english.getString(R.string.cancel)).performClick()
                assertEquals(item, db.secureItemDao().getItemById(item.id))
                assertEquals((type - 2) * 2, syncRequests.size)
                compose.onNodeWithContentDescription(english.getString(R.string.archive_action)).performClick()
                compose.onNodeWithText(english.getString(R.string.archive_action)).performClick()
                compose.waitUntil(10000) { runBlocking { db.secureItemDao().getItemById(item.id)?.isArchived == true } }
                compose.waitUntil(10000) { compose.onAllNodesWithText("shelf:1").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription(english.getString(R.string.unarchive_action)).performClick()
                compose.waitUntil(10000) { runBlocking { db.secureItemDao().getItemById(item.id)?.isArchived == false } }
                compose.waitUntil(10000) { compose.onAllNodesWithText("shelf:0").fetchSemanticsNodes().isNotEmpty() }
                assertEquals((type - 1) * 2, syncRequests.size)
            }
        }
    }

    @Test fun perVaultRelockRejectsAnArchiveWaitingForItsTransaction(): Unit = runBlocking {
        scenario {
            seed("vault-lock", 2)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val item = requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "vault-lock"))
            val store = BitwardenSecureItemArchiveStore(context)
            val global = SessionManager.lockGeneration.value
            coroutineScope {
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val holder = launch(Dispatchers.IO) { db.withTransaction { entered.complete(Unit); release.await() } }
                entered.await()
                val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { store.setArchived(item, false) } }
                rotateVaultSession()
                release.complete(Unit); holder.join()
                assertTrue(pending.await().isFailure)
            }
            assertEquals(global, SessionManager.lockGeneration.value)
            assertEquals(item, db.secureItemDao().getItemById(item.id))
            assertTrue(syncRequests.isEmpty())
        }
    }

    @Test fun archivedZipRestoresAsAccessibleLocalDataWithoutSyncIntent(): Unit = runBlocking {
        scenario {
            (2..4).forEach { seed("zip$it", it) }
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val preferences = BackupPreferences(includePasswords = false, includeAuthenticators = false,
                includeNotes = true, includeBankCards = true, includeDocuments = true, includePasskeys = false,
                includeWebDavConfig = false, includeTrash = false)
            val destination = takagi.ru.monica.credentialexchange.ImportDestination(
                takagi.ru.monica.credentialexchange.ImportDestinationKind.BITWARDEN, vault.id)
            val snapshot = takagi.ru.monica.transfer.DatabaseExportSnapshotLoader(context).load(destination, preferences)
            assertEquals(3, snapshot.secureItems.size)
            val file = takagi.ru.monica.transfer.DatabaseArchiveExporter(context).prepare(destination, preferences, null).first
            val localIds = mutableListOf<Long>()
            try {
                java.util.zip.ZipFile(file).use { zip ->
                    val jsonRows = zip.entries().asSequence().filter { it.name.startsWith("notes/") || it.name.startsWith("bank_cards/") || it.name.startsWith("documents/") }.toList()
                    assertEquals(3, jsonRows.size)
                    jsonRows.forEach { row ->
                        val body = zip.getInputStream(row).bufferedReader().use { it.readText() }
                        assertTrue(org.json.JSONObject(body).getBoolean("isArchived"))
                        assertFalse(body.contains("bitwardenArchiveIntent"))
                    }
                }
                val restore = takagi.ru.monica.utils.WebDavHelper(context).restoreFromBackupFile(file,
                    overwrite = false, restoreMonicaConfig = false, importDataOnly = true).getOrThrow()
                assertEquals(3, restore.content.secureItems.size)
                assertTrue(restore.content.secureItems.all { it.isArchived && it.archivedAt != null && it.bitwardenVaultId == null })
                val stats = takagi.ru.monica.utils.BackupRestoreApplier.applyRestoreResult(context, restore,
                    PasswordRepository(db.passwordEntryDao()), SecureItemRepository(db.secureItemDao()),
                    localOnlyDedup = true, logTag = "ArchiveUriSynthetic")
                assertEquals(3, stats.secureItemImported); assertEquals(0, stats.secureItemFailed)
                val originals = snapshot.secureItems.map { it.title }.toSet()
                val locals = db.secureItemDao().getLocalItemsIncludingArchived().filter { it.title in originals }
                localIds += locals.map { it.id }
                assertEquals(3, locals.size)
                val store = BitwardenSecureItemArchiveStore(context)
                assertEquals(3, store.observe().first().count { it.id in localIds })
                locals.forEach {
                    assertTrue(it.isArchived); assertNull(it.bitwardenVaultId); assertNull(it.bitwardenCipherId); assertNull(it.bitwardenArchiveIntent)
                    val unarchived = store.setArchived(it, false)
                    assertFalse(unarchived.isArchived)
                    assertTrue(runCatching { store.setArchived(unarchived, true) }.isFailure)
                }
                assertTrue(syncRequests.isEmpty())
            } finally { file.delete(); localIds.forEach { db.secureItemDao().deleteItemById(it) } }
        }
    }

    private suspend fun scenario(block: suspend Fixture.() -> Unit) {
        val unlocked = SessionManager.isUnlocked.value
        val mdkField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val previousMdk = mdkField.get(null)
        val fixtureMdk = ByteArray(32) { (it + 27).toByte() }
        mdkField.set(null, fixtureMdk)
        SessionManager.markUnlocked()
        try { MockWebServer().use { server ->
            val fixture = Fixture(server)
            try { fixture.initialize(); fixture.block() }
            finally { fixture.close() }
        } } finally { mdkField.set(null, previousMdk); fixtureMdk.fill(0); if (!unlocked) SessionManager.markLocked() }
    }

    private inner class Fixture(private val server: MockWebServer) {
        val account = UUID.randomUUID().toString()
        val token = "synthetic-document-token"
        val key = SymmetricCryptoKey(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { (it + 33).toByte() })
        val security = SecurityManager(context)
        var active = true
        var revokeOnCipherGet = false
        val service = BitwardenSyncService(context, sessionIsActive = { _, candidate -> active && candidate === key })
        val remote = ConcurrentHashMap<String, JsonObject>()
        val bodies = CopyOnWriteArrayList<JsonObject>()
        val syncRequests = CopyOnWriteArrayList<Long>()
        private val bridgeField = BitwardenMutationSyncBridge.javaClass.getDeclaredField("registration").apply { isAccessible = true }
        private var previousBridge: Any? = null
        val revision1 = "2026-11-03T00:00:00Z"
        val revision2 = "2026-11-04T01:00:00Z"
        var syncOverride: String? = null
        var extraFolders = JsonArray(emptyList())
        var extraSends = JsonArray(emptyList())
        var updateStatus = 200
        var acknowledgmentTransform: (JsonObject) -> JsonObject = { it }
        lateinit var vault: BitwardenVault
        private val repository by lazy { takagi.ru.monica.bitwarden.repository.BitwardenRepository.getInstance(context) }
        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String): MutableMap<Long, T> =
            repository.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(repository) as MutableMap<Long, T>

        suspend fun initialize() {
            val endpoint = server.url("/").toString()
            val id = db.bitwardenVaultDao().insert(BitwardenVault(email = "$account@example.invalid", userId = account,
                accountKey = account, serverUrl = endpoint, identityUrl = endpoint, apiUrl = endpoint, isLocked = false, isConnected = true))
            vault = requireNotNull(db.bitwardenVaultDao().getVaultById(id))
            cache<SymmetricCryptoKey>("symmetricKeyCache")[id] = key
            cache<String>("accessTokenCache")[id] = token
            previousBridge = bridgeField.get(BitwardenMutationSyncBridge)
            BitwardenMutationSyncBridge.register(this) { requested ->
                check(requested == vault.id); syncRequests += requested; true
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401)
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    val profile = """{"id":"$account","email":"${vault.email}","securityStamp":"synthetic-stamp"}"""
                    if (path == "/accounts/profile") return ok(profile)
                    if (path == "/sync") return ok(syncOverride ?: """{"profile":$profile,"ciphers":${JsonArray(remote.values.toList())},"folders":$extraFolders,"sends":$extraSends}""")
                    val original = remote[path.removePrefix("/ciphers/")] ?: return MockResponse().setResponseCode(404)
                    if (request.method == "GET") { if (revokeOnCipherGet) active = false; return ok(original.toString()) }
                    if (request.method == "PUT") {
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        bodies += body
                        if (updateStatus != 200) return MockResponse().setResponseCode(updateStatus)
                        if (body["lastKnownRevisionDate"] != original["revisionDate"] || body["encryptedFor"]?.jsonPrimitive?.content != account)
                            return MockResponse().setResponseCode(409)
                        val actual = acknowledgmentTransform(JsonObject(body + ("id" to original.getValue("id")) + ("revisionDate" to JsonPrimitive(revision2))))
                        remote[original["id"]!!.jsonPrimitive.content] = actual
                        return ok(actual.toString())
                    }
                    return MockResponse().setResponseCode(405)
                }
            }
        }

        fun seed(id: String, type: Int) {
            fun enc(value: String) = JsonPrimitive(BitwardenCrypto.encryptString(value, key))
            val section = when (type) {
                1 -> "login" to JsonObject(mapOf("username" to enc("synthetic-user"), "password" to enc("synthetic-secret"), "FutureLogin" to JsonPrimitive("keep")))
                2 -> "secureNote" to JsonObject(mapOf("type" to JsonPrimitive(0), "FutureSection" to JsonPrimitive("keep")))
                3 -> "card" to JsonObject(mapOf("number" to enc("4242424242424242"), "FutureSection" to JsonPrimitive("keep")))
                else -> "identity" to JsonObject(mapOf("firstName" to enc("Synthetic"), "FutureSection" to JsonPrimitive("keep")))
            }
            remote[id] = JsonObject(mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type), "name" to enc("Synthetic $id"),
                "notes" to enc("Synthetic notes"), "revisionDate" to JsonPrimitive(revision1), "archivedDate" to JsonPrimitive("2026-11-02T00:00:00Z"),
                "FutureEnvelope" to JsonObject(mapOf("keep" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))),
                "passwordHistory" to JsonArray(listOf(JsonObject(mapOf("password" to enc("old-synthetic-secret"), "lastUsedDate" to JsonPrimitive(revision1), "FutureHistory" to JsonPrimitive(99))))),
                "attachments" to JsonArray(emptyList()), section))
        }

        fun rotateVaultSession() {
            cache<SymmetricCryptoKey>("symmetricKeyCache").remove(vault.id)
            cache<SymmetricCryptoKey>("symmetricKeyCache")[vault.id] = key
        }

        private fun ok(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
        suspend fun close() {
            BitwardenMutationSyncBridge.unregister(this)
            bridgeField.set(BitwardenMutationSyncBridge, previousBridge)
            if (::vault.isInitialized) {
                cache<SymmetricCryptoKey>("symmetricKeyCache").remove(vault.id)
                cache<String>("accessTokenCache").remove(vault.id)
                db.passwordEntryDao().deleteAllByBitwardenVaultId(vault.id)
                db.secureItemDao().deleteAllByBitwardenVaultId(vault.id)
                db.passkeyDao().deleteAllByBitwardenVaultId(vault.id)
                db.bitwardenConflictBackupDao().deleteByVault(vault.id)
                db.bitwardenSyncRawEntryRecordDao().deleteByVault(vault.id)
                db.bitwardenSendDao().deleteByVault(vault.id)
                db.bitwardenFolderDao().deleteByVault(vault.id)
                db.bitwardenVaultDao().deleteById(vault.id)
            }
            key.clear()
        }
    }
}
