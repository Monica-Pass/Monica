package takagi.ru.monica.attachments

import android.content.Context
import android.content.ContextWrapper
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto
import takagi.ru.monica.attachments.executor.BitwardenAttachmentExecutor
import takagi.ru.monica.attachments.executor.BitwardenAttachmentReconciler
import takagi.ru.monica.attachments.executor.KeePassAttachmentExecutor
import takagi.ru.monica.attachments.executor.LocalAttachmentExecutor
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.repository.AttachmentRepository
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentPreviewCache
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.bitwarden.api.AttachmentDownloadInfo
import takagi.ru.monica.bitwarden.api.AttachmentUploadResponse
import takagi.ru.monica.bitwarden.api.BitwardenVaultApi
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager

/** Real HTTP/encryption/Room at the transfer boundary; TLS is covered by the handshake suite. */
@RunWith(AndroidJUnit4::class)
class BitwardenAttachmentEnvironmentInstrumentedTest {
    @Test fun freshSignedDownloadUsesSelectedClientAndPreservesBytes() = scenario {
        enqueueDownload()
        val downloaded = download()
        assertEquals(listOf(freshUrl), selected)
        assertArrayEquals(plain, read(downloaded))
        assertEquals("Bearer synthetic-token", account.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Authorization"))
        val signed = blob.takeRequest(2, TimeUnit.SECONDS)!!
        assertNull(signed.getHeader("Authorization"))
        assertEquals("/fresh?sig=synthetic%2Btoken", signed.path)
    }

    @Test fun cachedSignedFallbackUsesSameSelectorWithoutAccountBearer() = scenario {
        account.enqueue(MockResponse().setResponseCode(404))
        blob.enqueue(MockResponse().setBody(Buffer().write(ciphertext)))
        val downloaded = download()
        assertEquals(listOf(cachedUrl), selected)
        assertArrayEquals(plain, read(downloaded))
        assertNull(blob.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Authorization"))
    }

    @Test fun selectorSeesUnsafeOriginalPathBeforeAnySignedConnection() = scenario {
        val raw = blob.url("/").toString() + "scope/%2e%2e/payload?sig=synthetic"
        enqueueDownload(raw)
        val error = runCatching { download(selector = {
            selected += it
            throw AttachmentError.InvalidRemoteData
        }) }.exceptionOrNull()
        assertSame(AttachmentError.InvalidRemoteData, error)
        assertEquals(listOf(raw), selected)
        assertEquals(0, blob.requestCount)
        assertNoStagedFiles()
    }

    @Test fun retirementAfterMetadataStopsBeforeKeyOrSignedTransfer() = scenario {
        retireAfterMetadata = true
        enqueueDownload()
        assertSame(AttachmentError.BitwardenLocked, runCatching { download() }.exceptionOrNull())
        assertTrue(selected.isEmpty())
        assertEquals(0, blob.requestCount)
        assertNoStagedFiles()
    }

    @Test fun retirementInKeyProviderClearsOwnedKeyBeforeTransfer() = scenario {
        enqueueDownload()
        val supplied = SymmetricCryptoKey(key.encKey.copyOf(), key.macKey.copyOf())
        assertSame(AttachmentError.BitwardenLocked, runCatching {
            download(provider = { current = false; supplied })
        }.exceptionOrNull())
        assertTrue(supplied.encKey.all { it == 0.toByte() })
        assertTrue(supplied.macKey.all { it == 0.toByte() })
        assertEquals(0, blob.requestCount)
        assertNoStagedFiles()
    }

    @Test fun retirementAtBodyEndPreventsLocalCacheInstallation() = scenario {
        retireAtBodyEnd = true
        enqueueDownload()
        assertSame(AttachmentError.BitwardenLocked, runCatching { download() }.exceptionOrNull())
        assertEquals(1, blob.requestCount)
        assertNoStagedFiles()
    }

    @Test fun retirementAfterCacheWriteDeletesOnlyNewBlob() = scenario {
        val old = storage.writeEncrypted("existing encrypted cache".byteInputStream())
        old.cek.fill(0)
        val before = File(storageDirectory, old.relativePath).readBytes()
        retireWhenNewBlobExists = setOf(old.relativePath)
        enqueueDownload()
        assertSame(AttachmentError.BitwardenLocked, runCatching { download() }.exceptionOrNull())
        assertEquals(listOf(old.relativePath), storage.listAllBlobs())
        assertArrayEquals(before, File(storageDirectory, old.relativePath).readBytes())
        assertTrue(testContext.cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun azureUploadSelectsOriginalUrlAndSendsEncryptedBytesWithoutBearer() = scenario {
        enqueueUpload()
        val uploaded = executor.upload(1, "synthetic.txt", "text/plain", plain.inputStream(), plain.size.toLong(), uploadContext())
        assertEquals(listOf(freshUrl), selected)
        val put = blob.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PUT", put.method)
        assertEquals("BlockBlob", put.getHeader("x-ms-blob-type"))
        assertNull(put.getHeader("Authorization"))
        val transfer = File(root, "uploaded.bin").apply { writeBytes(put.body.readByteArray()) }
        val fileKey = BitwardenAttachmentCrypto.unwrapAttachmentKey(uploaded.bitwardenFileKeyEnc, key)
        try {
            assertArrayEquals(plain, BitwardenAttachmentCrypto.openDecrypted(transfer, fileKey).use { it.readBytes() })
        } finally { fileKey.clear(); transfer.delete() }
        assertArrayEquals(plain, read(uploaded))
    }

    @Test fun retirementAfterUploadMetadataPreventsPutAndDirectMultipart() = scenario {
        for (type in listOf(1, 0)) {
            current = true
            retireAfterMetadata = true
            enqueueUpload(type)
            assertSame(AttachmentError.BitwardenLocked, runCatching {
                executor.upload(1, "synthetic.txt", "text/plain", plain.inputStream(), plain.size.toLong(), uploadContext())
            }.exceptionOrNull())
            assertNoStagedFiles()
        }
        assertEquals(2, account.requestCount)
        assertEquals(0, blob.requestCount)
        assertTrue(selected.isEmpty())
    }

    @Test fun retiredRemoveCannotIssueRemoteDelete() = scenario {
        current = false
        assertSame(AttachmentError.BitwardenLocked, runCatching {
            executor.remove(api, "synthetic-token", cipherId, attachmentId, ::requireCurrent)
        }.exceptionOrNull())
        assertEquals(0, account.requestCount)
    }

    @Test fun downloadPublicationRetirementRollsBackRoomAndKeepsOldCache() = scenario {
        val owner = insertOwner()
        val old = storage.writeEncrypted("retained previous cache".byteInputStream())
        old.cek.fill(0)
        val original = existing.copy(parentPasswordId = owner, localPath = old.relativePath)
        val id = repository.insert(original)
        val before = File(storageDirectory, old.relativePath).readBytes()
        enqueueDownload()
        var publications = 0
        val bw = facadeContext { action ->
            database.withTransaction {
                requireCurrent()
                action()
                if (++publications == 2) current = false
                requireCurrent()
            }
        }
        assertSame(AttachmentError.BitwardenLocked, runCatching { facade.ensureDownloaded(id, bw) }.exceptionOrNull())
        val retained = repository.getById(id)!!
        assertEquals(old.relativePath, retained.localPath)
        assertNull(retained.wrappedCek)
        assertEquals(AttachmentDownloadState.DOWNLOADING, retained.downloadStateEnum)
        assertEquals(listOf(old.relativePath), storage.listAllBlobs())
        assertArrayEquals(before, File(storageDirectory, old.relativePath).readBytes())
    }

    @Test fun migrationPublicationRetirementPreservesLocalSourceAndSkipsOldDelete() = scenario {
        val owner = insertOwner()
        val local = facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
            AttachmentOwner.password(owner), AttachmentSource.LOCAL,
            "synthetic.txt", "text/plain", plain, isPlusActivated = true
        ))
        enqueueUpload()
        val bw = facadeContext { action ->
            database.withTransaction { requireCurrent(); action(); current = false; requireCurrent() }
        }
        assertSame(AttachmentError.BitwardenLocked, runCatching {
            facade.promoteLocalAttachmentsToBitwarden(AttachmentOwner.password(owner), bw, setOf(local.fileName))
        }.exceptionOrNull())
        assertEquals(local, repository.getById(local.id))
        assertArrayEquals(plain, read(local))
        assertEquals(listOf(local.localPath), storage.listAllBlobs())
        assertEquals(1, account.requestCount)
        assertEquals("POST", account.takeRequest(2, TimeUnit.SECONDS)?.method)
        assertEquals(1, blob.requestCount)
    }

    @Test fun retiredDownloadResetTouchesOnlyActiveTransfersInTheSelectedVault() = scenario {
        val vaultIds = mutableListOf<Long>()
        val ownerIds = mutableListOf<Long>()
        var secureOwner = 0L
        try {
            repeat(2) { index ->
                vaultIds += database.bitwardenVaultDao().insert(takagi.ru.monica.data.bitwarden.BitwardenVault(
                    email = "$cipherId-$index@example.invalid", accountKey = "$cipherId-$index"
                ))
                val owner = insertOwner()
                ownerIds += owner
                val row = database.passwordEntryDao().getPasswordEntryById(owner)!!
                database.passwordEntryDao().updatePasswordEntry(row.copy(bitwardenVaultId = vaultIds.last()))
            }
            secureOwner = database.secureItemDao().insertItem(takagi.ru.monica.data.SecureItem(
                itemType = takagi.ru.monica.data.ItemType.NOTE, title = cipherId, itemData = "{}",
                bitwardenVaultId = vaultIds.first()
            ))
            val retained = storage.writeEncrypted("reset must retain encrypted bytes".byteInputStream())
            retained.cek.fill(0)
            val originalBytes = File(storageDirectory, retained.relativePath).readBytes()
            suspend fun add(owner: Long?, secure: Long? = null, source: AttachmentSource = AttachmentSource.BITWARDEN,
                state: AttachmentDownloadState = AttachmentDownloadState.DOWNLOADING): Long = repository.insert(existing.copy(
                parentPasswordId = owner, parentSecureItemId = secure, source = source.name,
                downloadState = state.name, localPath = retained.relativePath,
                bitwardenAttachmentId = UUID.randomUUID().toString().takeIf { source == AttachmentSource.BITWARDEN }
            ))
            val target = add(ownerIds.first())
            val targetSecure = add(null, secureOwner)
            val other = add(ownerIds.last())
            val local = add(ownerIds.first(), source = AttachmentSource.LOCAL)
            val complete = add(ownerIds.first(), state = AttachmentDownloadState.DOWNLOADED)
            val ids = listOf(target, targetSecure, other, local, complete)
            val before = ids.associateWith { repository.getById(it)!! }
            suspend fun reset(): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                database.attachmentDao().resetRetiredBitwardenDownloads(vaultIds.first())
            }
            assertEquals(2, reset())
            ids.forEach { id ->
                val expected = before.getValue(id).let {
                    if (id == target || id == targetSecure) it.copy(downloadState = AttachmentDownloadState.PENDING.name) else it
                }
                assertEquals(expected, repository.getById(id))
            }
            assertEquals(0, reset())
            repository.markDownloadState(target, AttachmentDownloadState.DOWNLOADING)
            assertEquals(1, reset())
            assertEquals(AttachmentDownloadState.PENDING, repository.getById(target)?.downloadStateEnum)
            assertEquals(listOf(retained.relativePath), storage.listAllBlobs())
            assertArrayEquals(originalBytes, File(storageDirectory, retained.relativePath).readBytes())
        } finally {
            if (secureOwner != 0L) {
                repository.purge(AttachmentOwner.secureItem(secureOwner))
                database.secureItemDao().deleteItemById(secureOwner)
            }
            ownerIds.forEach { repository.purgeByPassword(it); database.passwordEntryDao().deletePasswordEntryById(it) }
            vaultIds.forEach { database.openHelper.writableDatabase.execSQL("DELETE FROM bitwarden_vaults WHERE id = ?", arrayOf(it)) }
        }
    }

    @Test fun rejectedReconciliationKeepsOriginalMetadataAndEncryptedBytes() = scenario {
        val row = cachedRow()
        val before = File(storageDirectory, row.localPath!!).readBytes()
        var publicationReached = false
        val failure = runCatching {
            reconciler.reconcile(row.parentPasswordId!!, emptyList(), ::requireCurrent) { action ->
                publicationReached = true
                current = false
                database.withTransaction { requireCurrent(); action() }
            }
        }.exceptionOrNull()
        assertTrue(publicationReached)
        assertSame(AttachmentError.BitwardenLocked, failure)
        assertEquals(row, repository.getById(row.id))
        assertArrayEquals(before, File(storageDirectory, row.localPath).readBytes())
    }

    @Test fun reconciliationRollbackRestoresMetadataAndNeverDeletesReferencedBlob() = scenario {
        val row = cachedRow()
        val before = File(storageDirectory, row.localPath!!).readBytes()
        var mutationObserved = false
        val failure = runCatching {
            reconciler.reconcile(row.parentPasswordId!!, emptyList(), ::requireCurrent) { action ->
                database.withTransaction {
                    requireCurrent()
                    action()
                    assertNull(repository.getById(row.id))
                    assertArrayEquals(before, File(storageDirectory, row.localPath).readBytes())
                    mutationObserved = true
                    current = false
                    requireCurrent()
                }
            }
        }.exceptionOrNull()
        assertTrue(mutationObserved)
        assertSame(AttachmentError.BitwardenLocked, failure)
        assertEquals(row, repository.getById(row.id))
        assertArrayEquals(before, File(storageDirectory, row.localPath).readBytes())
    }

    @Test fun reconciliationRejectsChangedSnapshotWithoutOverwritingNewDownload() = scenario {
        val row = cachedRow()
        val downloaded = storage.writeEncrypted("new concurrent download".byteInputStream())
        downloaded.cek.fill(0)
        val bytes = File(storageDirectory, downloaded.relativePath).readBytes()
        var replacement: Attachment? = null
        val failure = runCatching {
            reconciler.reconcile(row.parentPasswordId!!, emptyList(), ::requireCurrent) { action ->
                repository.update(row.copy(localPath = downloaded.relativePath, wrappedCek = "new-wrapper"))
                replacement = repository.getById(row.id)
                database.withTransaction { requireCurrent(); action(); requireCurrent() }
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertNotNull(replacement)
        assertEquals(replacement, repository.getById(row.id))
        assertArrayEquals(bytes, File(storageDirectory, downloaded.relativePath).readBytes())
        assertEquals(setOf(row.localPath, downloaded.relativePath), storage.listAllBlobs().toSet())
    }

    @Test fun absentRemoteListSkipsButExplicitEmptyListDeletesAfterCommit() = scenario {
        val row = cachedRow()
        val before = File(storageDirectory, row.localPath!!).readBytes()
        val skipped = reconciler.reconcile(row.parentPasswordId!!, null, ::requireCurrent) {
            error("A missing remote attachment list must not publish")
        }
        assertEquals(1, skipped.skipped)
        assertEquals(row, repository.getById(row.id))
        val removed = reconciler.reconcile(row.parentPasswordId!!, emptyList(), ::requireCurrent) { action ->
            database.withTransaction { requireCurrent(); action(); requireCurrent() }
            assertNull(repository.getById(row.id))
            assertArrayEquals(before, File(storageDirectory, row.localPath).readBytes())
        }
        assertEquals(1, removed.removed)
        assertNull(repository.getById(row.id))
        assertFalse(File(storageDirectory, row.localPath).exists())
    }

    @Test fun metadataInvalidationCommitsBeforeRemovingUnreferencedEncryptedCache() = scenario {
        val row = cachedRow()
        val before = File(storageDirectory, row.localPath!!).readBytes()
        var committed = false
        val report = reconciler.reconcile(row.parentPasswordId!!, listOf(remote.copy(fileName = "new-name.txt")), ::requireCurrent) { action ->
            database.withTransaction { requireCurrent(); action(); requireCurrent() }
            val pending = repository.getById(row.id)!!
            assertNull(pending.localPath)
            assertNull(pending.wrappedCek)
            assertEquals(AttachmentDownloadState.PENDING, pending.downloadStateEnum)
            assertArrayEquals(before, File(storageDirectory, row.localPath).readBytes())
            committed = true
        }
        assertTrue(committed)
        assertEquals(1, report.updated)
        assertEquals("new-name.txt", repository.getById(row.id)?.fileName)
        assertFalse(File(storageDirectory, row.localPath).exists())
    }

    @Test fun metadataInvalidationPreservesCacheReferencedByAnotherOwner() = scenario {
        val row = cachedRow()
        val before = File(storageDirectory, row.localPath!!).readBytes()
        val siblingId = repository.insert(row.copy(id = 0, parentPasswordId = insertOwner(),
            source = AttachmentSource.LOCAL.name, bitwardenAttachmentId = null))
        val sibling = repository.getById(siblingId)!!
        val report = reconciler.reconcile(row.parentPasswordId!!, listOf(remote.copy(fileName = "changed.txt")), ::requireCurrent) { action ->
            database.withTransaction { requireCurrent(); action(); requireCurrent() }
        }
        assertEquals(1, report.updated)
        assertNull(repository.getById(row.id)?.localPath)
        assertEquals(sibling, repository.getById(siblingId))
        assertArrayEquals(before, File(storageDirectory, row.localPath).readBytes())
    }

    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.close() }
    }

    private class Fixture {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "attachment-environment-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val testContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        val storageDirectory get() = File(testContext.filesDir, "secure_attachments")
        val storage = AttachmentStorage(testContext)
        private val keyVault = AttachmentKeyVault(SecurityManager(app))
        val executor = BitwardenAttachmentExecutor(testContext, storage, keyVault)
        val account = MockWebServer()
        val blob = MockWebServer()
        val database = PasswordDatabase.getDatabase(app)
        val repository = AttachmentRepository(database.attachmentDao())
        val reconciler = BitwardenAttachmentReconciler(repository, storage)
        val facade = AttachmentFacade(
            testContext, repository, LocalAttachmentExecutor(testContext, storage, keyVault), executor,
            KeePassAttachmentExecutor(testContext, { error("KeePass must not be used") }, storage, keyVault),
            storage, keyVault, AttachmentPreviewCache(testContext), fileProviderAuthority = "${app.packageName}.fileprovider"
        )
        private val owners = mutableListOf<Long>()
        val cipherId = "synthetic-cipher-${UUID.randomUUID()}"
        val attachmentId = "synthetic-file-${UUID.randomUUID()}"
        val key = SymmetricCryptoKey(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { (it + 33).toByte() })
        val plain = ByteArray(65_713) { (it * 19).toByte() }
        private val fileKey = SymmetricCryptoKey(ByteArray(32) { (it + 65).toByte() }, ByteArray(32) { (it + 97).toByte() })
        private val encryptedKey = BitwardenCrypto.encrypt(fileKey.encKey + fileKey.macKey, key)
        val ciphertext: ByteArray = File(root, "fixture.bin").let {
            try { BitwardenAttachmentCrypto.encryptFile(plain.inputStream(), it, fileKey); it.readBytes() }
            finally { it.delete() }
        }
        @Volatile var current = true
        @Volatile var retireAfterMetadata = false
        @Volatile var retireAtBodyEnd = false
        var retireWhenNewBlobExists: Set<String>? = null
        val selected = mutableListOf<String>()
        private val json = Json { ignoreUnknownKeys = true }
        private val accountClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (retireAfterMetadata) current = false
            response
        }.build()
        private val transferClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = response.body!!
            if (!retireAtBodyEnd) response else response.newBuilder().body(object : ResponseBody() {
                private val wrapped = object : ForwardingSource(original.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val count = super.read(sink, byteCount)
                        if (count == -1L) current = false
                        return count
                    }
                }.buffer()
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source(): BufferedSource = wrapped
            }).build()
        }.build()
        // Any accidental old fixed-client transfer fails, even though the selected client works.
        private val forbiddenClient = OkHttpClient.Builder().addInterceptor { error("Fixed attachment client was used") }.build()
        val api: BitwardenVaultApi = Retrofit.Builder().baseUrl(account.url("/"))
            .client(accountClient).addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(BitwardenVaultApi::class.java)
        val freshUrl = blob.url("/fresh?sig=synthetic%2Btoken").toString()
        val cachedUrl = blob.url("/cached?sig=synthetic").toString()
        val existing = Attachment(
            parentPasswordId = 1, source = AttachmentSource.BITWARDEN.name,
            fileName = "synthetic.txt", mimeType = "text/plain", sizeBytes = plain.size.toLong(),
            bitwardenAttachmentId = attachmentId, bitwardenFileKeyEnc = encryptedKey,
            bitwardenUrl = cachedUrl, downloadState = AttachmentDownloadState.PENDING.name,
            createdAt = 1, updatedAt = 1
        )
        val remote get() = CipherAttachmentApiData(
            id = attachmentId, fileName = "synthetic.txt", size = plain.size.toString(),
            sizeName = null, key = encryptedKey, url = cachedUrl
        )

        fun requireCurrent() {
            retireWhenNewBlobExists?.let { old -> if (storage.listAllBlobs().any { it !in old }) current = false }
            if (!current) throw AttachmentError.BitwardenLocked
        }

        fun select(url: String): OkHttpClient { selected += url; return transferClient }
        fun enqueueDownload(url: String = freshUrl) {
            account.enqueue(MockResponse().setBody(json.encodeToString(AttachmentDownloadInfo(attachmentId, url, key = encryptedKey))))
            blob.enqueue(MockResponse().setBody(Buffer().write(ciphertext)))
        }
        fun enqueueUpload(type: Int = 1) {
            account.enqueue(MockResponse().setBody(json.encodeToString(AttachmentUploadResponse(type, freshUrl, attachmentId = attachmentId))))
            blob.enqueue(MockResponse().setResponseCode(201))
        }
        suspend fun download(
            selector: (String) -> OkHttpClient = ::select,
            provider: (suspend () -> SymmetricCryptoKey)? = null
        ) = executor.download(existing, remote, api, forbiddenClient, "synthetic-token", cipherId, key,
            provider, selector, ::requireCurrent)

        fun uploadContext() = BitwardenAttachmentExecutor.UploadContext(
            api, forbiddenClient, "synthetic-token", cipherId, key,
            clientForUrl = ::select, requireCurrent = ::requireCurrent
        )

        fun facadeContext(publication: suspend (suspend () -> Unit) -> Unit) = AttachmentFacade.BitwardenContext(
            api, forbiddenClient, "synthetic-token", cipherId, key, true,
            clientForUrl = ::select, requireCurrent = ::requireCurrent, withPublication = publication
        )

        suspend fun read(attachment: Attachment): ByteArray {
            val cek = keyVault.unwrap(requireNotNull(attachment.wrappedCek))
            try { return storage.openDecryptedStream(requireNotNull(attachment.localPath), cek).use { it.readBytes() } }
            finally { cek.fill(0) }
        }

        suspend fun insertOwner(): Long = database.passwordEntryDao().insertPasswordEntry(
            PasswordEntry(title = cipherId, website = "https://example.invalid", username = "synthetic", password = "synthetic")
        ).also { owners += it }

        suspend fun cachedRow(): Attachment {
            val cached = storage.writeEncrypted("retained reconciliation cache".byteInputStream())
            cached.cek.fill(0)
            val id = repository.insert(existing.copy(parentPasswordId = insertOwner(), localPath = cached.relativePath,
                wrappedCek = "synthetic-retained-wrapper", downloadState = AttachmentDownloadState.DOWNLOADED.name))
            return repository.getById(id)!!
        }

        fun assertNoStagedFiles() {
            assertTrue(storage.listAllBlobs().isEmpty())
            assertTrue(testContext.cacheDir.listFiles().orEmpty().isEmpty())
        }

        suspend fun close() {
            owners.forEach { repository.purgeByPassword(it); database.passwordEntryDao().deletePasswordEntryById(it) }
            key.clear(); fileKey.clear()
            listOf(accountClient, transferClient, forbiddenClient).forEach {
                it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown()
            }
            account.shutdown(); blob.shutdown()
            check(root.canonicalFile.parentFile == app.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
}
