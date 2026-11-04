package takagi.ru.monica.bitwarden

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.*
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.credentialexchange.TransferFixture
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.screens.AddEditSendScreen
import takagi.ru.monica.ui.screens.SendLocalDraftList

/** Synthetic guest only. Root runs Gradle/AVD; source presence does not establish a passing result. */
class BitwardenSendDraftsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val content = "Synthetic private Send text\nwith spacing  "

    private fun ensureMdk(requireKnownPassword: Boolean = false) {
        val security = SecurityManager(context)
        val password = InstrumentationRegistry.getArguments().getString("testMasterPassword") ?: MASTER_PASSWORD
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(password))
        if (requireKnownPassword || !security.isVaultRuntimeUnlocked()) {
            check(security.unlockVaultWithPassword(password)) { "This test requires its own synthetic guest master password" }
        }
        SessionManager.markUnlocked()
        check(security.encryptData("synthetic").startsWith("MDK|"))
    }

    private fun draft(auth: SendAuthAction = SendAuthAction.EMAIL) = BitwardenSendDraft(
        title = "Synthetic private Send", text = content, notes = "Synthetic private notes",
        deletionMillis = System.currentTimeMillis() + 5 * 86_400_000L,
        authAction = auth, recipientEmails = if (auth == SendAuthAction.EMAIL) listOf("one@example.test", "two@example.test") else emptyList())

    private inner class Fixture {
        val transfer = TransferFixture()
        val store = BitwardenSendDraftStore(context)
        val cache = BitwardenSendCache(context)
        val repository = BitwardenRepository.getInstance(context)
        val rows = linkedMapOf<String, JsonObject>()
        val posts = AtomicInteger()
        val fileUploads = linkedMapOf<String, ByteArray>()
        val loseResponse = AtomicBoolean()
        var ignoreEmail = false
        var duplicateKeys = false
        var vaultId = 0L
        val savedDrafts = mutableListOf<BitwardenSendDraftStore.Draft>()
        suspend fun initialize() {
            vaultId = transfer.bitwarden().databaseId
            BitwardenVaultPremiumStore.setPremium(context, vaultId, true)
            val vault = requireNotNull(transfer.db.bitwardenVaultDao().getVaultById(vaultId))
            transfer.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(rows) {
                    val path = request.requestUrl!!.encodedPath
                    if (!path.startsWith("/send-blobs/")) check(request.getHeader("Authorization") == "Bearer ${transfer.accessToken}")
                    when {
                        request.method == "POST" && path in setOf("/sends", "/sends/file/v2") -> {
                            posts.incrementAndGet()
                            val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            val id = UUID.randomUUID().toString()
                            var stored = JsonObject(body + mapOf("id" to JsonPrimitive(id), "accessId" to JsonPrimitive("access-$id"),
                                "revisionDate" to JsonPrimitive(Instant.now().toString())))
                            if (path == "/sends/file/v2") stored = JsonObject(stored + ("file" to JsonObject(
                                body["file"]!!.jsonObject + mapOf("id" to JsonPrimitive("file-$id"),
                                    "size" to JsonPrimitive(body["fileLength"]!!.jsonPrimitive.content)))))
                            if (ignoreEmail) stored = JsonObject(stored - "authType" - "emails")
                            rows[id] = stored
                            if (loseResponse.compareAndSet(true, false)) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            else if (path == "/sends/file/v2") ok(buildJsonObject {
                                put("sendResponse", stored); put("fileUploadType", 1)
                                put("url", transfer.server.url("/send-blobs/$id").toString())
                            }) else ok(stored)
                        }
                        request.method == "PUT" && path.startsWith("/send-blobs/") -> {
                            fileUploads[path.substringAfterLast('/')] = request.body.readByteArray()
                            MockResponse().setResponseCode(201)
                        }
                        request.method == "GET" && path == "/sync" -> {
                            val sends = rows.values.toMutableList()
                            if (duplicateKeys && sends.isNotEmpty()) sends += JsonObject(sends.first() + ("id" to JsonPrimitive("duplicate-id")))
                            ok(buildJsonObject {
                                put("profile", buildJsonObject { put("id", vault.userId); put("email", vault.email) })
                                put("sends", JsonArray(sends))
                            })
                        }
                        request.method == "GET" && path.startsWith("/sends/") -> rows[path.substringAfterLast('/')]
                            ?.let(::ok) ?: MockResponse().setResponseCode(404)
                        request.method == "DELETE" && path.startsWith("/sends/") -> {
                            rows.remove(path.substringAfterLast('/')); MockResponse().setResponseCode(200)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        }
        fun ok(body: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
        suspend fun save(value: BitwardenSendDraft = draft(), uri: Uri? = null): BitwardenSendDraftStore.Draft =
            store.save(vaultId, value, selectedUri = uri, selectedName = uri?.lastPathSegment,
                type = if (uri == null) BitwardenSend.TYPE_TEXT else BitwardenSend.TYPE_FILE).also(savedDrafts::add)
        suspend fun submit(saved: BitwardenSendDraftStore.Draft): BitwardenRepository.SendMutationResult {
            val value = saved.draft
            return repository.createTextSend(vaultId, value.title, value.text, value.notes, value.password,
                value.maxAccessCount, value.hideEmail, value.hiddenText, value.deletionMillis, value.expirationMillis,
                authAction = value.authAction, recipientEmails = value.recipientEmails,
                beforeRequest = { store.markSubmitting(vaultId, saved.id, it) })
        }
        suspend fun close() {
            transfer.db.bitwardenSendDao().deleteByVault(vaultId)
            savedDrafts.forEach { saved ->
                runCatching { store.discard(vaultId, saved.id) }
                val root = File(context.filesDir, "bitwarden_send_drafts_v1")
                listOfNotNull("$vaultId-${saved.id}.draft", "$vaultId-${saved.id}.draft.bak", "$vaultId-${saved.id}.draft.new",
                    saved.file?.let { "$vaultId-${it.id}.payload" }).forEach { name ->
                    val file = File(root, name); check(file.canonicalFile.parentFile == root.canonicalFile); file.delete()
                }
            }
            transfer.close()
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        ensureMdk()
        val fixture = Fixture()
        try { fixture.initialize(); fixture.block() } finally { fixture.close() }
    }
    private fun success(result: BitwardenRepository.SendMutationResult): BitwardenSend {
        assertTrue("Expected confirmed synthetic Send, got $result", result is BitwardenRepository.SendMutationResult.Success)
        return (result as BitwardenRepository.SendMutationResult.Success).send
    }

    @Test fun emailCreateIsReadBackAndCurrentRoomRowContainsOnlyProtectedContent() = runBlocking { fixture {
        val saved = save()
        val send = success(submit(saved))
        val raw = requireNotNull(transfer.db.bitwardenSendDao().getBySendId(vaultId, send.bitwardenSendId))
        assertEquals(1, raw.cacheVersion); assertTrue(raw.encryptedCache!!.startsWith("MDK|"))
        assertEquals("", raw.name); assertEquals("", raw.notes); assertNull(raw.textContent)
        assertNull(raw.keyBase64); assertNull(raw.recipientEmails); assertEquals("", raw.shareUrl); assertEquals("", raw.accessId)
        assertFalse(raw.encryptedCache.contains(content)); assertFalse(raw.encryptedCache.contains("one@example.test"))
        val opened = cache.getSendsByVault(vaultId).single()
        assertEquals(content, opened.textContent); assertEquals(0, opened.authType)
        assertEquals("one@example.test,two@example.test", opened.recipientEmails)
    } }

    @Test fun fileEmailAuthIsVerifiedBeforeUploadAndRecoveredBytesReachServer() = runBlocking { fixture {
        val bytes = ByteArray(65_561) { (it * 7).toByte() }
        val source = File(transfer.root, "email-file.bin").also { it.writeBytes(bytes) }
        val saved = save(draft(), Uri.fromFile(source))
        source.delete()
        val value = saved.draft
        val send = success(repository.createFileSend(vaultId, value.title, null, saved.file!!.name, value.notes,
            null, null, false, value.deletionMillis, null, authAction = SendAuthAction.EMAIL,
            recipientEmails = value.recipientEmails, sourceSize = saved.file.size,
            source = { store.openFile(vaultId, saved.id) },
            beforeRequest = { store.markSubmitting(vaultId, saved.id, it) },
            onUploaded = { store.markFileUploadConfirmed(vaultId, saved.id) }))
        assertTrue(store.load(vaultId, saved.id).fileUploadConfirmed)
        val encrypted = File(transfer.root, "received-send.enc").also { it.writeBytes(fileUploads.getValue(send.bitwardenSendId)) }
        val material = android.util.Base64.decode(send.keyBase64, android.util.Base64.NO_WRAP)
        val key = takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.deriveSendKey(material)
        try { takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto.openDecrypted(encrypted, key).use {
            assertArrayEquals(bytes, it.readBytes())
        } } finally { key.clear(); material.fill(0) }
        assertEquals(0, send.authType); assertEquals(1, posts.get())
    } }

    @Test fun fileServerIgnoringEmailNeverReceivesPayload() = runBlocking { fixture {
        ignoreEmail = true
        val source = File(transfer.root, "not-uploaded.bin").also { it.writeText("Synthetic private file") }
        val saved = save(draft(), Uri.fromFile(source))
        val value = saved.draft
        val result = repository.createFileSend(vaultId, value.title, null, saved.file!!.name, value.notes,
            null, null, false, value.deletionMillis, null, authAction = SendAuthAction.EMAIL,
            recipientEmails = value.recipientEmails, sourceSize = saved.file.size,
            source = { store.openFile(vaultId, saved.id) }, beforeRequest = { store.markSubmitting(vaultId, saved.id, it) })
        assertTrue(result is BitwardenRepository.SendMutationResult.Error)
        assertTrue(fileUploads.isEmpty()); assertTrue(rows.isEmpty()); assertEquals(1, posts.get())
        assertNotNull(store.load(vaultId, saved.id).file)
    } }

    @Test fun nonPremiumEmailKeepsDraftWithoutMakingRequest() = runBlocking { fixture {
        BitwardenVaultPremiumStore.setPremium(context, vaultId, false)
        val saved = save()
        assertTrue(submit(saved) is BitwardenRepository.SendMutationResult.Error)
        assertEquals(0, posts.get()); assertFalse(store.load(vaultId, saved.id).submissionUncertain)
    } }

    @Test fun ignoredEmailModeNeverFallsBackAndLocalDraftSurvives() = runBlocking { fixture {
        ignoreEmail = true
        val saved = save()
        val result = submit(saved)
        assertTrue(result is BitwardenRepository.SendMutationResult.Error)
        assertEquals(1, posts.get()); assertTrue(rows.isEmpty())
        val retained = store.load(vaultId, saved.id)
        assertEquals(saved.draft, retained.draft)
        assertEquals("0", BitwardenSendEdit.string(retained.preparedRequest!!, "authType"))
        assertTrue(cache.getSendsByVault(vaultId).isEmpty())
    } }

    @Test fun lostCreateResponseReconcilesByOneExactKeyWithoutAnotherPost() = runBlocking { fixture {
        loseResponse.set(true)
        val saved = save()
        val failed = submit(saved) as BitwardenRepository.SendMutationResult.Error
        assertTrue(failed.uncertain)
        val retained = store.load(vaultId, saved.id)
        assertTrue(retained.submissionUncertain)
        val confirmed = success(repository.reconcileSendDraft(retained))
        assertEquals(content, confirmed.textContent); assertEquals(1, posts.get())
        assertEquals(rows.keys.single(), confirmed.bitwardenSendId)
    } }

    @Test fun ambiguousExactKeysKeepUncertainDraftAndBlockEditing() = runBlocking { fixture {
        loseResponse.set(true)
        val saved = save()
        assertTrue(submit(saved) is BitwardenRepository.SendMutationResult.Error)
        duplicateKeys = true
        assertTrue(repository.reconcileSendDraft(store.load(vaultId, saved.id)) is BitwardenRepository.SendMutationResult.Error)
        assertTrue(runCatching { store.save(vaultId, saved.draft.copy(title = "Changed"), existingId = saved.id) }.isFailure)
        assertTrue(store.load(vaultId, saved.id).submissionUncertain)
        assertEquals(1, posts.get())
    } }

    @Test fun legacyMigrationValidatesShareIdentityAndPreservesCorruptRows() = runBlocking { fixture {
        val send = success(submit(save()))
        val rawDao = transfer.db.bitwardenSendDao()
        rawDao.update(send)
        assertEquals(send, cache.getSendsByVault(vaultId).single())
        assertEquals(1, rawDao.getBySendId(vaultId, send.bitwardenSendId)!!.cacheVersion)
        val wrongShare = send.copy(shareUrl = send.shareUrl + "tampered")
        rawDao.update(wrongShare)
        assertTrue(cache.getSendsByVault(vaultId).isEmpty())
        assertEquals(wrongShare, rawDao.getBySendId(vaultId, send.bitwardenSendId))
        assertTrue(runCatching { cache.upsert(send) }.isFailure)
        cache.deleteByVaultProtectingDirty(vaultId, Long.MAX_VALUE)
        assertEquals(wrongShare, rawDao.getBySendId(vaultId, send.bitwardenSendId))
        rawDao.update(send); cache.getSendsByVault(vaultId)
        val sealed = rawDao.getBySendId(vaultId, send.bitwardenSendId)!!
        val corrupt = sealed.copy(encryptedCache = "MDK|invalid", isDirty = false)
        rawDao.update(corrupt)
        cache.deleteNotInProtectingDirty(vaultId, emptyList(), Long.MAX_VALUE)
        assertTrue(runCatching { cache.upsert(send) }.isFailure)
        assertEquals(corrupt, rawDao.getBySendId(vaultId, send.bitwardenSendId))
    } }

    @Test fun activeCacheFlowClearsOnSameIdEndpointChangeAndCanResume() = runBlocking { fixture {
        success(submit(save()))
        val channel = Channel<List<BitwardenSend>>(Channel.UNLIMITED)
        val job = launch { cache.getSendsByVaultFlow(vaultId).collect { channel.send(it) } }
        val original = transfer.db.bitwardenVaultDao().getVaultById(vaultId)!!
        try {
            assertEquals(1, withTimeout(10_000) { channel.receive() }.size)
            transfer.db.bitwardenVaultDao().update(original.copy(apiUrl = original.apiUrl + "other/"))
            withTimeout(10_000) { while (channel.receive().isNotEmpty()) Unit }
            transfer.db.bitwardenVaultDao().update(original)
            withTimeout(10_000) { while (channel.receive().isEmpty()) Unit }
        } finally { job.cancel(); channel.close(); transfer.db.bitwardenVaultDao().update(original) }
    } }

    @Test fun encryptedFileDraftSurvivesSourceRemovalAndRejectsChangedAccountOrLock() = runBlocking { fixture {
        val bytes = ByteArray(70_003) { (it * 31).toByte() }
        val source = File(transfer.root, "synthetic.bin").also { it.writeBytes(bytes) }
        val saved = save(draft(SendAuthAction.NONE), Uri.fromFile(source))
        assertTrue(source.delete())
        BitwardenSendDraftStore(context).openFile(vaultId, saved.id).use { assertArrayEquals(bytes, it.readBytes()) }
        val original = transfer.db.bitwardenVaultDao().getVaultById(vaultId)!!
        transfer.db.bitwardenVaultDao().update(original.copy(userId = UUID.randomUUID().toString()))
        try { assertTrue(runCatching { store.load(vaultId, saved.id) }.isFailure) }
        finally { transfer.db.bitwardenVaultDao().update(original) }
        val input = store.openFile(vaultId, saved.id)
        try { SessionManager.markLocked(); assertTrue(runCatching { input.read() }.isFailure) }
        finally { input.close(); ensureMdk() }
        assertArrayEquals(bytes, store.openFile(vaultId, saved.id).use { it.readBytes() })
    } }

    @Test fun nativeEditorSavesIncompleteDraftAndExplicitDiscardKeepsRemoteSend() = runBlocking { fixture {
        val vault = transfer.db.bitwardenVaultDao().getVaultById(vaultId)!!
        var showList by mutableStateOf(false)
        var shown by mutableStateOf<List<BitwardenSendDraftStore.Draft>>(emptyList())
        var discarded by mutableStateOf(false)
        compose.setContent { SendTestTheme {
            if (!showList) AddEditSendScreen(sendState = BitwardenViewModel.SendState.Idle,
                vaults = listOf(vault), activeVault = vault,
                unlockStateByVault = mapOf(vaultId to BitwardenViewModel.UnlockState.Unlocked),
                initialTitle = "Incomplete native draft", initialText = "", onNavigateBack = {},
                onSubmitStoredDraft = { error("Saving a draft must not submit it") },
                onCreate = { _, _, _, _, _, _, _, _, _ -> }, onCreateFile = { _, _, _, _, _, _, _, _, _ -> })
            else SendLocalDraftList(shown, false, false, onResume = {}, onDiscard = { selected ->
                runBlocking { store.discard(selected.vaultId, selected.id) }; discarded = true
            })
        } }
        compose.onNodeWithTag("send_draft_save").performScrollTo().performClick()
        compose.waitUntil(15_000) { runBlocking { store.list(vaultId).isNotEmpty() } }
        val saved = store.list(vaultId).single().also(savedDrafts::add)
        assertEquals("", saved.draft.text); assertEquals(0, posts.get())
        val remote = success(submit(save()))
        compose.runOnIdle { shown = listOf(saved); showList = true }
        compose.onNodeWithTag("send_draft_discard_${saved.id}").performScrollTo().performClick()
        compose.onAllNodesWithText(context.getString(R.string.bw_send_draft_discard)).filter(hasClickAction()).onLast().performClick()
        compose.waitUntil(5_000) { discarded }
        assertTrue(runCatching { store.load(vaultId, saved.id) }.isFailure)
        assertTrue(rows.containsKey(remote.bitwardenSendId))
        compose.runOnIdle { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
    } }

    @Test fun safeCacheClearPreservesDirtyInvalidCorruptLockedRowsAndLocalDrafts() = runBlocking { fixture {
        val clean = success(submit(save()))
        val dirty = success(submit(save()))
        val invalid = success(submit(save()))
        val corrupt = success(submit(save()))
        val raw = transfer.db.bitwardenSendDao()
        // Create deliberately stays dirty until a later server reconciliation. Establish the
        // acknowledged-cache fixture explicitly; then ensure corruption alone still protects rows.
        assertTrue(raw.getBySendId(vaultId, clean.bitwardenSendId)!!.isDirty)
        raw.clearDirty(vaultId, clean.bitwardenSendId)
        raw.update(raw.getBySendId(vaultId, clean.bitwardenSendId)!!.copy(createdAt = 1L, updatedAt = 1L))
        raw.update(raw.getBySendId(vaultId, dirty.bitwardenSendId)!!.copy(isDirty = true, createdAt = 1L, updatedAt = 1L))
        raw.update(invalid.copy(shareUrl = invalid.shareUrl + "wrong", isDirty = false, createdAt = 1L, updatedAt = 1L))
        raw.update(raw.getBySendId(vaultId, corrupt.bitwardenSendId)!!.copy(encryptedCache = "MDK|invalid", isDirty = false, createdAt = 1L, updatedAt = 1L))
        assertFalse(raw.getBySendId(vaultId, clean.bitwardenSendId)!!.isDirty)
        assertFalse(raw.getBySendId(vaultId, invalid.bitwardenSendId)!!.isDirty)
        assertFalse(raw.getBySendId(vaultId, corrupt.bitwardenSendId)!!.isDirty)
        val snapshots = listOf(dirty, invalid, corrupt).associate { it.bitwardenSendId to raw.getBySendId(vaultId, it.bitwardenSendId) }
        val saved = save(draft(SendAuthAction.NONE))
        val sealedDraft = File(context.filesDir, "bitwarden_send_drafts_v1/$vaultId-${saved.id}.draft").readText()
        val persistedDraft = json.parseToJsonElement(SecurityManager(context).decryptData(sealedDraft)).jsonObject
        assertEquals(saved.updatedAt, persistedDraft.getValue("updatedAt").jsonPrimitive.long)
        val result = repository.clearVaultLocalCache(vaultId, BitwardenRepository.CacheClearMode.SAFE_ONLY_SYNCED)
        assertEquals(1, result.sendClearedCount)
        assertNull(raw.getBySendId(vaultId, clean.bitwardenSendId))
        snapshots.forEach { (id, row) -> assertEquals(row, raw.getBySendId(vaultId, id)) }
        assertEquals(saved, store.load(vaultId, saved.id))
        SessionManager.markLocked()
        try {
            assertEquals(0, repository.clearVaultLocalCache(vaultId, BitwardenRepository.CacheClearMode.SAFE_ONLY_SYNCED).sendClearedCount)
            snapshots.forEach { (id, row) -> assertEquals(row, raw.getBySendId(vaultId, id)) }
        } finally { ensureMdk() }
        assertEquals(saved, store.load(vaultId, saved.id))
    } }

    @Test fun legacyDraftWithoutTimestampUsesStableOwnedMetadataTimeWithoutRewriting() = runBlocking { fixture {
        val saved = save(draft(SendAuthAction.NONE))
        val metadata = File(context.filesDir, "bitwarden_send_drafts_v1/$vaultId-${saved.id}.draft")
        val security = SecurityManager(context)
        val persisted = json.parseToJsonElement(security.decryptData(metadata.readText())).jsonObject
        assertEquals(saved.updatedAt, persisted.getValue("updatedAt").jsonPrimitive.long)
        val legacy = JsonObject(persisted - "updatedAt")
        metadata.writeText(security.encryptData(legacy.toString()))
        assertTrue(metadata.setLastModified(1_700_000_000_000L))
        val legacyTime = metadata.lastModified()
        assertTrue(legacyTime > 0L && legacyTime < saved.updatedAt)
        val originalBytes = metadata.readBytes()
        val expected = saved.copy(updatedAt = legacyTime)
        assertEquals(expected, store.load(vaultId, saved.id))
        assertEquals(expected, BitwardenSendDraftStore(context).load(vaultId, saved.id))
        assertEquals(expected, store.list(vaultId).single())
        assertArrayEquals(originalBytes, metadata.readBytes())
        assertEquals(legacyTime, metadata.lastModified())
        metadata.writeText("MDK|corrupt synthetic legacy draft")
        val corruptBytes = metadata.readBytes()
        assertTrue(runCatching { store.load(vaultId, saved.id) }.isFailure)
        assertArrayEquals(corruptBytes, metadata.readBytes())
    } }

    @Test fun explicitLogoutDeletesOnlyOwnedDraftFilesIncludingCorruptMetadata() = runBlocking { fixture {
        val source = File(transfer.root, "logout.bin").also { it.writeBytes(byteArrayOf(1, 4, 9, 16)) }
        val saved = save(draft(SendAuthAction.NONE), Uri.fromFile(source))
        val directory = File(context.filesDir, "bitwarden_send_drafts_v1")
        val metadata = File(directory, "$vaultId-${saved.id}.draft")
        val payload = File(directory, "$vaultId-${saved.file!!.id}.payload")
        val unknown = File(directory, "$vaultId-preserve-unknown.txt").also { it.writeText("Synthetic unknown file") }
        val other = File(directory, "${Long.MAX_VALUE}-${UUID.randomUUID()}.draft").also { it.writeText("Synthetic other account marker") }
        try {
            metadata.writeText("MDK|corrupt synthetic metadata")
            assertTrue(repository.logout(vaultId))
            assertFalse(metadata.exists()); assertFalse(payload.exists())
            assertEquals("Synthetic unknown file", unknown.readText())
            assertEquals("Synthetic other account marker", other.readText())
        } finally { unknown.delete(); other.delete() }
    } }

    @Test fun nativeEmailDraftResumesEditsAndSubmitsThroughViewModel() = runBlocking { fixture {
        val vault = transfer.db.bitwardenVaultDao().getVaultById(vaultId)!!
        val owner = androidx.lifecycle.ViewModelStore()
        lateinit var model: BitwardenViewModel
        compose.runOnIdle {
            model = androidx.lifecycle.ViewModelProvider(owner,
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(compose.activity.application))[BitwardenViewModel::class.java]
        }
        var showList by mutableStateOf(false)
        var resume by mutableStateOf<BitwardenSendDraftStore.Draft?>(null)
        try {
            compose.setContent { SendTestTheme {
                val shown by model.localSendDrafts.collectAsState()
                val state by model.sendState.collectAsState()
                val version by model.sendCreateSuccessVersion.collectAsState()
                if (showList) SendLocalDraftList(shown, false, false,
                    onResume = { resume = it; showList = false }, onDiscard = model::discardLocalSendDraft)
                else key(resume?.id) {
                    AddEditSendScreen(sendState = state, sendCreateSuccessVersion = version,
                        vaults = listOf(vault), activeVault = vault,
                        unlockStateByVault = mapOf(vaultId to BitwardenViewModel.UnlockState.Unlocked),
                        initialTitle = "Synthetic email draft", initialText = content,
                        initialStoredDraft = resume, onNavigateBack = { showList = true; model.refreshLocalSendDrafts() },
                        onSubmitStoredDraft = model::submitStoredSendDraft, onLeaveForeground = model::leaveSendEditorForeground,
                        onCreate = { _, _, _, _, _, _, _, _, _ -> error("Unexpected legacy create") },
                        onCreateFile = { _, _, _, _, _, _, _, _, _ -> error("Unexpected legacy file") })
                }
            } }
            compose.onNodeWithTag("send_auth_email").performScrollTo().performClick()
            compose.onNodeWithTag("send_auth_recipient_0").performScrollTo().performTextReplacement("recipient@example.test")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            captureIfRequested("send-auth", "send_auth_recipient_0")
            compose.onNodeWithTag("send_draft_save").performScrollTo().performClick()
            compose.waitUntil(15_000) { runBlocking { store.list(vaultId).isNotEmpty() } }
            val saved = store.list(vaultId).single().also(savedDrafts::add)
            assertEquals(0, posts.get())
            val back = hasContentDescription(context.getString(R.string.back))
            compose.waitUntil(5_000) { compose.onAllNodes(back and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(back).assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("send_draft_resume_${saved.id}").fetchSemanticsNodes().isNotEmpty() }
            captureIfRequested("send-drafts", "send_draft_resume_${saved.id}")
            compose.onNodeWithTag("send_draft_resume_${saved.id}").performScrollTo().performClick()
            compose.onNodeWithTag("item_editor_title").performScrollTo().performTextReplacement("Resumed native email Send")
            captureIfRequested("send-editor", "item_editor_title")
            compose.onNodeWithTag("send_auth_recipient_0").performScrollTo().assertTextContains("recipient@example.test")
            compose.onNodeWithTag("send_editor_save").performClick()
            compose.waitUntil(30_000) { model.sendCreateSuccessVersion.value > 0 }
            assertTrue(store.list(vaultId).isEmpty())
            val remote = cache.getSendsByVault(vaultId).single()
            assertEquals("Resumed native email Send", remote.name)
            assertEquals("recipient@example.test", remote.recipientEmails)
            assertEquals(content, remote.textContent); assertEquals(1, posts.get())
        } finally {
            compose.runOnIdle { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)); owner.clear() }
        }
    } }

    @Composable private fun SendTestTheme(content: @Composable () -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val scale = if (args.getString("sendLargeFont") == "true") 1.5f else 1f
        val configuration = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply { fontScale = scale }
        CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides configuration,
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                androidx.compose.ui.platform.LocalDensity.current.density, scale)) {
            takagi.ru.monica.ui.theme.MonicaTheme(darkTheme = args.getString("sendDark") == "true", content = content)
        }
    }

    private fun captureIfRequested(name: String, tag: String) {
        if (InstrumentationRegistry.getArguments().getString("sendDraftScreenshots") != "true") return
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(context.filesDir, "editor-shapes-325").apply { check(isDirectory || mkdirs()) }
        val args = InstrumentationRegistry.getArguments()
        val suffix = "${if (args.getString("sendDark") == "true") "dark" else "light"}-${if (args.getString("sendLargeFont") == "true") "large" else "normal"}"
        try {
            File(directory, "$name-$suffix.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @Test fun actualSendPageOpensThemedDraftDialogAndResumesStoredContent() = runBlocking { fixture {
        val saved = save(draft(SendAuthAction.NONE))
        val owner = androidx.lifecycle.ViewModelStore()
        lateinit var model: BitwardenViewModel
        compose.runOnIdle {
            model = androidx.lifecycle.ViewModelProvider(owner,
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(compose.activity.application))[BitwardenViewModel::class.java]
        }
        try {
            compose.setContent { SendTestTheme {
                takagi.ru.monica.ui.screens.SendScreen(bitwardenViewModel = model)
            } }
            compose.waitUntil(15_000) { model.unlockStateByVault.value[vaultId] == BitwardenViewModel.UnlockState.Unlocked }
            compose.onNodeWithTag("send_open_drafts").assertIsEnabled().performClick()
            val resumeTag = "send_draft_resume_${saved.id}"
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(resumeTag).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(resumeTag).assertIsDisplayed()
            captureIfRequested("send-production-drafts", resumeTag)
            compose.onNodeWithTag(resumeTag).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("item_editor_title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("item_editor_title").performScrollTo().assertTextContains(saved.draft.title)
            assertEquals(content, store.load(vaultId, saved.id).draft.text)
            assertEquals(0, posts.get())
        } finally {
            compose.runOnIdle { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)); owner.clear() }
        }
    } }


    @Test fun processDeathPrepareEncryptedDraft() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sendDraftProcessPhase") == "prepare")
        ensureMdk(requireKnownPassword = true)
        val prefs = context.getSharedPreferences(PROCESS_PREFS, 0)
        check(!prefs.contains("draftId"))
        val fixture = Fixture(); fixture.initialize()
        val source = File(fixture.transfer.root, "process.bin").also { it.writeBytes(PROCESS_BYTES) }
        val saved = fixture.save(draft(SendAuthAction.NONE), Uri.fromFile(source))
        check(source.delete())
        check(prefs.edit().putLong("vaultId", saved.vaultId).putString("draftId", saved.id)
            .putString("fixtureRoot", fixture.transfer.root.name).commit())
        fixture.transfer.server.shutdown()
        // Deliberately retained for the separate post-force-stop method; root removes the synthetic guest.
    }

    @Test fun processDeathRecoverEncryptedDraft() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sendDraftProcessPhase") == "recover")
        ensureMdk(requireKnownPassword = true)
        val prefs = context.getSharedPreferences(PROCESS_PREFS, 0)
        val vaultId = prefs.getLong("vaultId", -1)
        val id = requireNotNull(prefs.getString("draftId", null))
        val database = PasswordDatabase.getDatabase(context)
        val vault = requireNotNull(database.bitwardenVaultDao().getVaultById(vaultId))
        check(vault.accountKey.startsWith("credential-transfer-") && vault.email.endsWith("@example.invalid"))
        val repository = BitwardenRepository.getInstance(context)
        repository.unlock(vaultId, "Synthetic transfer fixture password")
        check(repository.isVaultUnlocked(vaultId))
        val store = BitwardenSendDraftStore(context)
        assertEquals(content, store.load(vaultId, id).draft.text)
        store.openFile(vaultId, id).use { assertArrayEquals(PROCESS_BYTES, it.readBytes()) }
        store.discard(vaultId, id)
        repository.lock(vaultId)
        BitwardenVaultPremiumStore.clear(context, vaultId)
        database.bitwardenVaultDao().deleteById(vaultId)
        val folder = File(context.filesDir, requireNotNull(prefs.getString("fixtureRoot", null)))
        check(folder.name.startsWith("credential-transfer-") && folder.canonicalFile.parentFile == context.filesDir.canonicalFile)
        check(folder.listFiles().orEmpty().isEmpty()); folder.delete()
        check(prefs.edit().clear().commit())
    }

    companion object {
        private const val MASTER_PASSWORD = "Synthetic Bitwarden settings fixture 325!"
        private const val PROCESS_PREFS = "synthetic-send-process-recovery-325"
        private val PROCESS_BYTES = ByteArray(131_109) { (it * 13).toByte() }
    }
}
