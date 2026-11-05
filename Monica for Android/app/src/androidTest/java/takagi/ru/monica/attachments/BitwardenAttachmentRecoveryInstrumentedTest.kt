package takagi.ru.monica.attachments

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import takagi.ru.monica.attachments.executor.BitwardenAttachmentExecutor
import takagi.ru.monica.attachments.executor.BitwardenAttachmentRecoveryStore
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.bitwarden.api.BitwardenVaultApi
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.credentialexchange.BitwardenImportAttachmentQueue
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData

/** Synthetic guest only. Source presence is not a build, device, or process-death result. */
@RunWith(AndroidJUnit4::class)
class BitwardenAttachmentRecoveryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun ensureMdk() {
        val security = SecurityManager(context)
        val password = InstrumentationRegistry.getArguments().getString("testMasterPassword") ?: "Synthetic attachment recovery 325!"
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(password))
        if (!security.isVaultRuntimeUnlocked()) check(security.unlockVaultWithPassword(password))
        SessionManager.markUnlocked()
        check(security.encryptData("synthetic").startsWith("MDK|"))
    }

    private inner class Fixture(val type: Int? = 1) {
        val server = MockWebServer()
        val store = BitwardenAttachmentRecoveryStore(context)
        val storage = AttachmentStorage(context)
        val keyVault = AttachmentKeyVault(SecurityManager(context))
        val key = SymmetricCryptoKey(ByteArray(32) { (it + 3).toByte() }, ByteArray(32) { (it + 41).toByte() })
        val remoteId = UUID.randomUUID().toString()
        val cipherId = UUID.randomUUID().toString()
        var owner = AttachmentOwner.password((System.nanoTime() and Long.MAX_VALUE).coerceAtLeast(1))
        var roomVault: BitwardenVault? = null
        var failAfterRoomAdoption = false
        val plain = ByteArray(16_789) { (it * 31 + 17).toByte() }
        var allocated: JsonObject? = null
        var uploaded: ByteArray? = null
        var allocationCount = 0
        var uploadCount = 0
        var deleteCount = 0
        var blobGetCount = 0
        var loseAllocationResponse = false
        var loseUploadResponse = false
        var lockAfterUpload = false
        var active = true
        var duplicateMetadata = false
        val localFiles = mutableListOf<String>()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
        lateinit var binding: BitwardenAttachmentRecoveryStore.Binding
        lateinit var uploadContext: BitwardenAttachmentExecutor.UploadContext

        fun initialize() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@Fixture) {
                    val path = request.requestUrl!!.encodedPath
                    if (path != "/blob") check(request.getHeader("Authorization") == "Bearer synthetic-token")
                    else check(request.getHeader("Authorization") == null)
                    when {
                        request.method == "POST" && path.endsWith("/attachment/v2") -> {
                            allocationCount++
                            allocated = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            if (loseAllocationResponse) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            else ok(buildJsonObject {
                                put("attachmentId", remoteId)
                                if (type != null) put("fileUploadType", type)
                                put("url", server.url("/blob").toString())
                            })
                        }
                        request.method == "PUT" && path == "/blob" -> receiveUpload(request.body.readByteArray())
                        request.method == "POST" && path.endsWith("/attachment/$remoteId") -> {
                            val body = request.body.readByteArray()
                            val dataHeader = "name=\"data\"".toByteArray()
                            val dataStart = body.indexOfSequence(dataHeader)
                            check(dataStart >= 0)
                            val bytesStart = body.indexOfSequence("\r\n\r\n".toByteArray(), dataStart) + 4
                            val expected = allocated!!["fileSize"]!!.jsonPrimitive.content.toInt()
                            receiveUpload(body.copyOfRange(bytesStart, bytesStart + expected))
                        }
                        request.method == "GET" && path == "/ciphers/$cipherId" -> ok(buildJsonObject {
                            put("Id", cipherId); put("Edit", true)
                            put("Attachments", buildJsonArray {
                                if (allocated != null) {
                                    add(metadata(remoteId))
                                    if (duplicateMetadata) add(metadata("second-exact-match"))
                                }
                            })
                        })
                        request.method == "GET" && path.endsWith("/attachment/$remoteId") ->
                            if (allocated != null) ok(metadata(remoteId)) else MockResponse().setResponseCode(404)
                        request.method == "GET" && path == "/blob" -> {
                            blobGetCount++
                            uploaded?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                        }
                        request.method == "DELETE" && path.endsWith("/attachment/$remoteId") -> {
                            deleteCount++; allocated = null; uploaded = null; MockResponse().setResponseCode(204)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val api = Retrofit.Builder().baseUrl(server.url("/")).client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BitwardenVaultApi::class.java)
            binding = BitwardenAttachmentRecoveryStore.Binding(owner.id, "synthetic-${UUID.randomUUID()}", "synthetic-user",
                server.url("/").toString(), server.url("/").toString(), server.url("/identity").toString(),
                "synthetic-transport", null, cipherId, owner.kind.name, owner.id)
            uploadContext = BitwardenAttachmentExecutor.UploadContext(api, client, "synthetic-token", cipherId, key,
                requireCurrent = { check(active && SessionManager.isUnlocked.value) },
                recoveryBinding = binding, localAttachmentId = owner.id)
        }

        private fun receiveUpload(bytes: ByteArray): MockResponse {
            uploadCount++; uploaded = bytes
            if (lockAfterUpload) active = false
            return if (loseUploadResponse) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else MockResponse().setResponseCode(201)
        }

        private fun metadata(id: String) = buildJsonObject {
            val request = requireNotNull(allocated)
            put("id", id); put("key", request["key"]!!); put("fileName", request["fileName"]!!)
            put("size", request["fileSize"]!!); put("url", server.url("/blob").toString())
        }
        private fun ok(body: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
        fun executor() = BitwardenAttachmentExecutor(context, storage, keyVault)
        suspend fun upload() = executor().upload(owner, "synthetic private filename", "application/octet-stream",
            plain.inputStream(), plain.size.toLong(), uploadContext).also { localFiles += requireNotNull(it.localPath) }
        suspend fun assertPlain(attachment: Attachment) {
            val cek = keyVault.unwrap(requireNotNull(attachment.wrappedCek))
            try { storage.openDecryptedStream(requireNotNull(attachment.localPath), cek).use { assertArrayEquals(plain, it.readBytes()) } }
            finally { cek.fill(0) }
        }
        suspend fun roomContext(): AttachmentFacade.BitwardenContext {
            val database = PasswordDatabase.getDatabase(context)
            val vault = BitwardenVault(email = "${UUID.randomUUID()}@example.test", accountKey = binding.accountKey,
                userId = binding.userId, serverUrl = binding.serverUrl, apiUrl = binding.apiUrl,
                identityUrl = binding.identityUrl, transportRevision = binding.transportRevision,
                isLocked = false, isConnected = true)
            val vaultId = database.bitwardenVaultDao().insert(vault)
            roomVault = vault.copy(id = vaultId)
            owner = AttachmentOwner.password(database.passwordEntryDao().insertPasswordEntry(PasswordEntry(
                title = "Synthetic attachment recovery owner", website = "", username = "synthetic", password = "",
                bitwardenVaultId = vaultId, bitwardenCipherId = cipherId)))
            binding = BitwardenAttachmentRecoveryStore.binding(requireNotNull(roomVault), cipherId, owner)
            uploadContext = uploadContext.copy(recoveryBinding = binding, localAttachmentId = null)
            return AttachmentFacade.BitwardenContext(uploadContext.vaultApi, client, uploadContext.accessToken,
                cipherId, key, isOnline = true, requireCurrent = uploadContext.requireCurrent,
                withPublication = { action ->
                    database.withTransaction { action() }
                    localFiles += AttachmentContainer.facade(context).list(owner)
                        .filter { it.sourceEnum == AttachmentSource.LOCAL }.mapNotNull { it.localPath }
                    if (failAfterRoomAdoption && AttachmentContainer.facade(context).list(owner).any { it.sourceEnum == AttachmentSource.BITWARDEN }) {
                        active = false
                        throw kotlinx.coroutines.CancellationException("Synthetic lock after Room adoption")
                    }
                }, recoveryBinding = { target -> BitwardenAttachmentRecoveryStore.binding(requireNotNull(roomVault), cipherId, target) })
        }
        suspend fun close() {
            active = true
            SessionManager.markUnlocked()
            store.list(binding).forEach { record ->
                listOf("record", "record.bak", "record.new", "payload").forEach { suffix ->
                    val file = File(context.filesDir, "bitwarden_attachment_recovery_v1/${record.operationId}.$suffix")
                    check(file.canonicalFile.parentFile == File(context.filesDir, "bitwarden_attachment_recovery_v1").canonicalFile)
                    if (file.exists()) check(file.delete())
                }
            }
            roomVault?.let { vault ->
                val facade = AttachmentContainer.facade(context)
                facade.list(owner).forEach { facade.forgetLocalAttachment(it.id) }
                val database = PasswordDatabase.getDatabase(context)
                database.passwordEntryDao().deletePasswordEntryById(owner.id)
                database.openHelper.writableDatabase.execSQL("DELETE FROM bitwarden_vaults WHERE id = ?", arrayOf(vault.id))
                context.getSharedPreferences("credential_import_attachments_v1", 0).edit().remove("vault:${vault.id}").commit()
            }
            localFiles.forEach { storage.delete(it) }
            key.clear(); server.shutdown()
        }
    }

    private suspend fun fixture(type: Int? = 1, block: suspend Fixture.() -> Unit) {
        ensureMdk()
        val fixture = Fixture(type)
        fixture.initialize()
        try { fixture.block() } finally { fixture.close() }
    }

    @Test fun azureLostUploadResponseRecoversExactBytesWithoutASecondAllocation() = runBlocking { fixture {
        loseUploadResponse = true
        assertTrue(runCatching { upload() }.isFailure)
        val record = store.list(binding).single()
        assertEquals(BitwardenAttachmentRecoveryStore.Phase.UPLOADING, record.phase)
        val original = store.ciphertext(binding, record.operationId).readBytes()
        assertArrayEquals(uploaded, original)
        // New store/executor instances exercise durable data, not an in-memory upload receipt.
        assertEquals(record, BitwardenAttachmentRecoveryStore(context).load(binding, record.operationId))
        val result = executor().recoverUpload(record.operationId, uploadContext)
        localFiles += requireNotNull(result.localPath)
        assertPlain(result)
        assertEquals(1, allocationCount); assertEquals(1, uploadCount); assertEquals(1, blobGetCount); assertEquals(0, deleteCount)
        assertTrue(store.ciphertext(binding, record.operationId).exists())
        store.markPublished(binding, remoteId, 111, uploadContext.requireCurrent)
        assertTrue(store.isPublished(binding, remoteId))
        assertFalse(File(context.filesDir, "bitwarden_attachment_recovery_v1/${record.operationId}.payload").exists())
        assertFalse(executor().cleanupRecovery(record.operationId, uploadContext) { false })
        assertEquals(0, deleteCount)
    } }

    @Test fun directUploadUsesExactAllocationAndPreservesRecoveryUntilAdoption() = runBlocking { fixture(type = 0) {
        val result = upload()
        assertPlain(result)
        val record = store.list(binding).single()
        assertEquals(remoteId, result.bitwardenAttachmentId)
        assertEquals(BitwardenAttachmentRecoveryStore.Phase.UPLOADED, record.phase)
        assertArrayEquals(uploaded, store.ciphertext(binding, record.operationId).readBytes())
        assertEquals(1, allocationCount); assertEquals(1, uploadCount); assertEquals(0, deleteCount)
    } }

    @Test fun promotedAccountAliasRecoversExactCiphertextButDifferentUserCannotReadOrResumeIt() = runBlocking { fixture {
        loseUploadResponse = true
        assertTrue(runCatching { upload() }.isFailure)
        val record = store.list(binding).single()
        val originalBytes = store.ciphertext(binding, record.operationId).readBytes()
        val promoted = binding.copy(accountKey = "promoted-server-user-${UUID.randomUUID()}")
        val reopened = BitwardenAttachmentRecoveryStore(context)
        assertEquals(record, reopened.load(promoted, record.operationId))
        assertEquals(record, reopened.findPending(promoted, requireNotNull(record.localAttachmentId)))
        assertEquals(listOf(record), reopened.list(promoted))
        assertArrayEquals(originalBytes, reopened.ciphertext(promoted, record.operationId).readBytes())
        val vault = BitwardenVault(id = promoted.vaultId, email = "synthetic@example.invalid", accountKey = promoted.accountKey,
            userId = promoted.userId, serverUrl = promoted.serverUrl, apiUrl = promoted.apiUrl, identityUrl = promoted.identityUrl,
            transportRevision = promoted.transportRevision, transportSecretRef = promoted.transportSecretRef)
        assertEquals(listOf(record), reopened.listForVault(vault))
        val replaced = promoted.copy(userId = "different-server-user")
        assertTrue(reopened.list(replaced).isEmpty())
        assertTrue(reopened.listForVault(vault.copy(userId = replaced.userId)).isEmpty())
        assertTrue(runCatching { reopened.load(replaced, record.operationId) }.isFailure)
        assertTrue(runCatching { reopened.findPending(replaced, requireNotNull(record.localAttachmentId)) }.isFailure)
        assertTrue(runCatching { reopened.ciphertext(replaced, record.operationId) }.isFailure)
        val requestsBefore = server.requestCount
        assertTrue(runCatching { executor().recoverUpload(record.operationId, uploadContext.copy(recoveryBinding = replaced)) }.isFailure)
        assertEquals("Changed server identity must be rejected before HTTP", requestsBefore, server.requestCount)
        val promotedContext = uploadContext.copy(recoveryBinding = promoted)
        val result = executor().recoverUpload(record.operationId, promotedContext)
        localFiles += requireNotNull(result.localPath)
        assertPlain(result)
        assertEquals(binding, reopened.load(promoted, record.operationId).binding)
        reopened.markPublished(promoted, remoteId, 111, promotedContext.requireCurrent)
        assertTrue(reopened.isPublished(binding, remoteId))
        assertTrue(reopened.isPublished(promoted, remoteId))
        assertFalse(executor().cleanupRecovery(record.operationId, promotedContext) { false })
        assertEquals(1, allocationCount); assertEquals(1, uploadCount); assertEquals(1, blobGetCount); assertEquals(0, deleteCount)
    } }

    @Test fun missingAndUnknownTypesRetainTheReservedSlotAndRequireExactUnreferencedCleanup() = runBlocking {
        for (type in listOf(null, 7)) fixture(type) {
            assertTrue(runCatching { upload() }.isFailure)
            val record = store.list(binding).single()
            assertEquals(BitwardenAttachmentRecoveryStore.Phase.ALLOCATED, record.phase)
            assertEquals(0, uploadCount)
            assertFalse(executor().cleanupRecovery(record.operationId, uploadContext) { true })
            assertEquals(0, deleteCount)
            assertTrue(executor().cleanupRecovery(record.operationId, uploadContext) { false })
            assertEquals(1, deleteCount)
            assertEquals(BitwardenAttachmentRecoveryStore.Phase.REMOVED, store.load(binding, record.operationId).phase)
            assertTrue(store.ciphertext(binding, record.operationId).exists())
        }
    }

    @Test fun allocationResponseLossAndDuplicateMatchesNeverCauseAnotherAllocationOrDelete() = runBlocking { fixture {
        loseAllocationResponse = true
        assertTrue(runCatching { upload() }.isFailure)
        val record = store.list(binding).single()
        assertEquals(BitwardenAttachmentRecoveryStore.Phase.ALLOCATING, record.phase)
        assertTrue(runCatching { executor().recoverUpload(record.operationId, uploadContext) }.isFailure)
        assertEquals(1, blobGetCount)
        duplicateMetadata = true
        assertTrue(runCatching { executor().recoverUpload(record.operationId, uploadContext) }.isFailure)
        assertEquals(1, blobGetCount)
        assertFalse(executor().cleanupRecovery(record.operationId, uploadContext) { false })
        assertEquals(1, allocationCount); assertEquals(0, uploadCount); assertEquals(0, deleteCount)
        assertTrue(store.ciphertext(binding, record.operationId).exists())
    } }

    @Test fun lockAfterUploadPreservesCiphertextAndTamperedRemoteBytesCannotBeAdopted() = runBlocking { fixture {
        lockAfterUpload = true
        assertTrue(runCatching { upload() }.isFailure)
        active = true
        val record = store.list(binding).single()
        val actual = uploaded!!.copyOf()
        uploaded = actual.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { executor().recoverUpload(record.operationId, uploadContext) }.isFailure)
        assertEquals(BitwardenAttachmentRecoveryStore.Phase.UPLOADING, store.load(binding, record.operationId).phase)
        assertTrue(store.ciphertext(binding, record.operationId).exists())
        uploaded = actual
        val result = executor().recoverUpload(record.operationId, uploadContext)
        localFiles += requireNotNull(result.localPath); assertPlain(result)
        assertEquals(1, allocationCount); assertEquals(1, uploadCount); assertEquals(0, deleteCount)
    } }

    @Test fun facadeStagesExactPendingRowAndQuarantinesOnlyItsUnverifiedRemoteMetadata() = runBlocking { fixture {
        val bw = roomContext()
        val facade = AttachmentContainer.facade(context)
        loseUploadResponse = true
        assertTrue(runCatching { facade.addStreamAttachment(AttachmentFacade.StreamUploadRequest(
            owner, AttachmentSource.BITWARDEN, "synthetic private filename", "application/octet-stream",
            plain.size.toLong(), openStream = { plain.inputStream() }, isPlusActivated = true, bitwardenContext = bw)) }.isFailure)
        val staged = facade.list(owner).single()
        localFiles += requireNotNull(staged.localPath)
        assertEquals(AttachmentSource.LOCAL, staged.sourceEnum)
        assertTrue(staged.id in BitwardenImportAttachmentQueue(context).observePendingIds(binding.vaultId).first())
        val record = store.list(binding).single()
        assertEquals(staged.id, record.localAttachmentId)
        val unverified = CipherAttachmentApiData(remoteId, staged.fileName, record.encryptedSize.toString(),
            key = record.requestKey, url = server.url("/blob").toString())
        val unrelated = unverified.copy(id = "unrelated", fileName = "other file", key = "other-key")
        val report = facade.reconcileBitwardenAttachments(owner, listOf(unverified, unrelated), bw)
        assertEquals(1, report.inserted)
        assertEquals(2, facade.list(owner).size)
        assertEquals(AttachmentSource.LOCAL, facade.getById(staged.id)!!.sourceEnum)
        assertEquals(1, facade.promoteLocalAttachmentsToBitwarden(owner, bw, setOf(staged.fileName), setOf(staged.id)))
        val adopted = facade.getById(staged.id)!!
        assertEquals(AttachmentSource.BITWARDEN, adopted.sourceEnum)
        assertEquals(remoteId, adopted.bitwardenAttachmentId)
        assertPlain(adopted)
        assertTrue(store.isPublished(binding, remoteId))
        assertEquals(1, allocationCount); assertEquals(1, uploadCount); assertEquals(0, deleteCount)
    } }

    @Test fun lateRoomCommitCancellationNeverRollsBackTheAdoptedRemoteAttachment() = runBlocking { fixture {
        val bw = roomContext()
        val facade = AttachmentContainer.facade(context)
        failAfterRoomAdoption = true
        assertTrue(runCatching { facade.addStreamAttachment(AttachmentFacade.StreamUploadRequest(
            owner, AttachmentSource.BITWARDEN, "synthetic private filename", "application/octet-stream",
            plain.size.toLong(), openStream = { plain.inputStream() }, isPlusActivated = true, bitwardenContext = bw)) }.isFailure)
        active = true
        val row = facade.list(owner).single()
        assertEquals(AttachmentSource.BITWARDEN, row.sourceEnum)
        assertEquals(BitwardenAttachmentRecoveryStore.Phase.UPLOADED, store.list(binding).single().phase)
        assertTrue(row.id in BitwardenImportAttachmentQueue(context).observePendingIds(binding.vaultId).first())
        assertEquals(0, deleteCount)
        failAfterRoomAdoption = false
        facade.acknowledgePublishedAttachmentRecovery(row.id, bw)
        assertTrue(store.isPublished(binding, remoteId))
        assertPlain(row)
        assertFalse(facade.cleanupAttachmentRecovery(owner, store.list(binding).single().operationId, bw))
        assertEquals(1, allocationCount); assertEquals(1, uploadCount); assertEquals(0, deleteCount)
    } }

    /** Root runs stage, stops the app process, then runs resume with the same unique UUID argument.
     * This intentionally leaves ONLY this nonce's encrypted synthetic journal between invocations.
     */
    @Test fun processDeathStageUncertainCiphertext() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("attachmentRecoveryProcessStep") == "stage")
        val nonce = requireNotNull(args.getString("attachmentRecoveryProcessNonce"))
        require(UUID.fromString(nonce).toString() == nonce)
        ensureMdk()
        val preferences = context.getSharedPreferences("attachment_recovery_process_test", 0)
        check(preferences.getString(nonce, null) == null) { "Use a new synthetic process-test nonce" }
        val store = BitwardenAttachmentRecoveryStore(context)
        val binding = processBinding(nonce)
        val key = processWrappingKey()
        val (attachmentKey, wrapped) = takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto.generateAndWrapAttachmentKey(key)
        val fileName = "synthetic process attachment"
        val encryptedName = takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto.encryptStringForAttachment(fileName, key)
        val temporary = File.createTempFile("attachment_process_", ".ciphertext", context.cacheDir)
        try {
            val plain = processPlaintext()
            val result = takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto.encryptFile(plain.inputStream(), temporary, attachmentKey)
            val request = takagi.ru.monica.bitwarden.api.AttachmentUploadRequest(wrapped, encryptedName, temporary.length().toString())
            takagi.ru.monica.attachments.executor.BitwardenAttachmentMutationGate.withLock(context) {
                var record = store.prepare(binding, binding.ownerId, fileName, "application/octet-stream",
                    result.plainSizeBytes, result.plainSha256Hex, request, temporary) { check(SessionManager.isUnlocked.value) }
                record = store.advance(binding, record.operationId, record.phase, BitwardenAttachmentRecoveryStore.Phase.ALLOCATING) {}
                record = store.advance(binding, record.operationId, record.phase, BitwardenAttachmentRecoveryStore.Phase.ALLOCATED,
                    remoteId = "synthetic-exact-allocation-$nonce", uploadType = 1) {}
                record = store.advance(binding, record.operationId, record.phase, BitwardenAttachmentRecoveryStore.Phase.UPLOADING) {}
                assertTrue(store.ciphertext(binding, record.operationId).exists())
                check(preferences.edit().putString(nonce, record.operationId).commit())
            }
        } finally { attachmentKey.clear(); key.clear(); temporary.delete() }
    }

    @Test fun processDeathResumeUncertainCiphertext() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("attachmentRecoveryProcessStep") == "resume")
        val nonce = requireNotNull(args.getString("attachmentRecoveryProcessNonce"))
        require(UUID.fromString(nonce).toString() == nonce)
        ensureMdk()
        val preferences = context.getSharedPreferences("attachment_recovery_process_test", 0)
        val operationId = requireNotNull(preferences.getString(nonce, null)) { "Run stage in the preceding process" }
        val binding = processBinding(nonce)
        val store = BitwardenAttachmentRecoveryStore(context)
        val record = store.load(binding, operationId)
        val payload = store.ciphertext(binding, operationId)
        assertEquals(BitwardenAttachmentRecoveryStore.Phase.UPLOADING, record.phase)
        assertEquals(operationId, store.findPending(binding, binding.ownerId)?.operationId)
        SessionManager.markLocked()
        assertTrue(runCatching { store.load(binding, operationId) }.isFailure)
        assertTrue(payload.exists())
        ensureMdk()
        val wrapping = processWrappingKey()
        val key = try { takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto.unwrapAttachmentKey(record.requestKey, wrapping) }
            finally { wrapping.clear() }
        try {
            takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto.openDecrypted(payload, key).use {
                assertArrayEquals(processPlaintext(), it.readBytes())
            }
        } finally { key.clear() }
        assertTrue(runCatching { store.prepare(binding, binding.ownerId, record.fileName, record.mimeType,
            record.plainSize, record.plainSha256, record.request(), payload) {} }.isFailure)
        assertEquals(BitwardenAttachmentRecoveryStore.Phase.UPLOADING, store.load(binding, operationId).phase)
        assertTrue(payload.exists())
        // Only this explicit synthetic fixture is discarded. Production uncertain records have no timeout cleanup.
        listOf("record", "record.bak", "record.new", "payload").forEach { suffix ->
            val file = File(context.filesDir, "bitwarden_attachment_recovery_v1/$operationId.$suffix")
            check(file.canonicalFile.parentFile == File(context.filesDir, "bitwarden_attachment_recovery_v1").canonicalFile)
            if (file.exists()) check(file.delete())
        }
        check(preferences.edit().remove(nonce).commit())
    }

    private fun processBinding(nonce: String): BitwardenAttachmentRecoveryStore.Binding {
        val id = (nonce.hashCode().toLong() and 0x7fffffffL) + 1
        return BitwardenAttachmentRecoveryStore.Binding(id, "synthetic-process-$nonce", "synthetic-user",
            "https://process.example.invalid", "https://process.example.invalid/api", "https://process.example.invalid/identity",
            "synthetic-process-transport", null, "synthetic-cipher-$nonce", "PASSWORD", id)
    }
    private fun processWrappingKey() = SymmetricCryptoKey(ByteArray(32) { (it + 7).toByte() }, ByteArray(32) { (it + 81).toByte() })
    private fun processPlaintext() = ByteArray(91_001) { (it * 17 + 9).toByte() }

    private fun ByteArray.indexOfSequence(sequence: ByteArray, from: Int = 0): Int {
        for (i in from..size - sequence.size) if (sequence.indices.all { this[i + it] == sequence[it] }) return i
        return -1
    }
}
