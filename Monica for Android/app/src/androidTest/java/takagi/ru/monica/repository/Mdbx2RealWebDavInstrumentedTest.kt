package takagi.ru.monica.repository

import takagi.ru.monica.utils.AppLocaleStringResolver

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxRemoteSource
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStateStore
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.WebDavMdbxRemoteTransport
import takagi.ru.monica.webdav.WebDavGateway

@RunWith(AndroidJUnit4::class)
class Mdbx2RealWebDavInstrumentedTest {
    @Test
    fun realWebDavBootstrapSyncAttachmentConflictAndReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val serverUrl = arguments.getString(ARG_SERVER_URL)?.trim().orEmpty()
        assumeTrue("Real WebDAV URL was not supplied", serverUrl.isNotBlank())
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val password = arguments.getString(ARG_PASSWORD).orEmpty()
        val runId = UUID.randomUUID().toString()

        val restoreHttpFixture = configureLoopbackHttpFixture(instrumentation.targetContext, serverUrl, runId)
        try { withTimeout(REAL_PROVIDER_TIMEOUT_MS) {
            exerciseRealProvider(
                context = instrumentation.targetContext,
                providerName = "WebDAV",
                remoteRoot = "monica-mdbx2-real-webdav",
                runId = runId,
                transport = WebDavMdbxRemoteTransport(serverUrl, username, password, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)),
                sourceType = MdbxSourceType.REMOTE_WEBDAV,
                sourceFactory = { remoteSourceDao, securityManager, displayName, remotePath ->
                    insertWebDavRemoteSource(
                        remoteSourceDao = remoteSourceDao,
                        securityManager = securityManager,
                        displayName = displayName,
                        remotePath = remotePath,
                        serverUrl = serverUrl,
                        username = username,
                        password = password
                    )
                }
            )
        } } finally { restoreHttpFixture() }
    }

    /** F-Droid's HTTP opt-in uses isolated test preferences, never the user's setting. */
    private fun configureLoopbackHttpFixture(context: Context, url: String, runId: String): () -> Unit {
        val uri = java.net.URI(url)
        if (uri.scheme != "http" || uri.host != "127.0.0.1") return {}
        // The ordinary edition has no context-backed HTTP gate. Keep this test
        // source shared without adding a production bypass to either edition.
        val attach = WebDavGateway::class.java.methods.firstOrNull {
            it.name == "attach" && it.parameterTypes.contentEquals(arrayOf(Context::class.java))
        } ?: return {}
        val preferenceNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val testName = "$name-mdbx-loopback-$runId"
                preferenceNames += testName
                return super.getSharedPreferences(testName, mode).also {
                    it.edit().putBoolean("allow_insecure_http", true).commit()
                }
            }
        }
        attach.invoke(WebDavGateway, isolated)
        return {
            attach.invoke(WebDavGateway, context)
            preferenceNames.forEach(context::deleteSharedPreferences)
        }
    }

    internal suspend fun exerciseRealProvider(
        context: Context,
        providerName: String,
        remoteRoot: String,
        runId: String,
        transport: takagi.ru.monica.utils.MdbxRemoteTransport,
        sourceType: MdbxSourceType,
        sourceFactory: suspend (
            takagi.ru.monica.data.MdbxRemoteSourceDao,
            SecurityManager,
            String,
            String
        ) -> Long,
        onSyncTiming: (String, Long) -> Unit = { _, _ -> }
    ) {
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val remoteSourceDao = room.mdbxRemoteSourceDao()
        val securityManager = SecurityManager(context)
        val stateStore = MdbxSyncStateStore(room.mdbxSyncStateDao())
        val providerKey = providerName.lowercase()
        val remotePath = "$remoteRoot/$runId/vault.mdbx"
        suspend fun syncStep(
            name: String,
            coordinator: Mdbx2RemoteSyncCoordinator,
            databaseId: Long,
            stepTransport: takagi.ru.monica.utils.MdbxRemoteTransport = transport
        ): Mdbx2RemoteSyncReport {
            val started = android.os.SystemClock.elapsedRealtime()
            return try {
                coordinator.synchronize(databaseId, remotePath, stepTransport)
            } finally {
                onSyncTiming(name, android.os.SystemClock.elapsedRealtime() - started)
            }
        }
        val vaultPassword = "real-$providerKey-$runId"
        val sessionPreferences = context.getSharedPreferences(
            SESSION_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val originalDeviceId = sessionPreferences.getString(DEVICE_ID_KEY, null)
        val clientARoot = File(context.cacheDir, "mdbx2-real-$providerKey-a-$runId")
        val clientBRoot = File(context.cacheDir, "mdbx2-real-$providerKey-b-$runId")
        val databaseIds = mutableListOf<Long>()
        val sourceIds = mutableListOf<Long>()
        val vaultFiles = mutableListOf<File>()
        var encryptedAttachmentPath: String? = null

        try {
            setDeviceId(sessionPreferences, "real-$providerKey-a-$runId")
            val repositoryA = Mdbx2Repository(context, databaseDao, securityManager)
            val vaultA = repositoryA.createInitializedVaultFile(MdbxTigaMode.SKY, vaultPassword)
            vaultFiles += vaultA
            val sourceA = sourceFactory(remoteSourceDao, securityManager, "Real $providerName A", remotePath)
            sourceIds += sourceA
            val databaseA = insertRemoteDatabase(
                databaseDao = databaseDao,
                securityManager = securityManager,
                sourceId = sourceA,
                name = "Real $providerName A",
                remotePath = remotePath,
                vaultFile = vaultA,
                password = vaultPassword,
                sourceType = sourceType
            )
            databaseIds += databaseA
            val coordinatorA = Mdbx2RemoteSyncCoordinator(
                rootDirectory = clientARoot,
                sessions = Mdbx2RepositorySyncSessionProvider(repositoryA),
                stateStore = stateStore
            )

            transport.testConnection()
            val bootstrap = coordinatorA.publishBootstrap(databaseA, remotePath, transport)
            assertTrue(bootstrap.fileSizeBytes > 0uL)
            assertNotNull(transport.stat(remotePath))

            setDeviceId(sessionPreferences, "real-$providerKey-b-$runId")
            val repositoryB = Mdbx2Repository(context, databaseDao, securityManager)
            val vaultBDirectory = File(context.filesDir, "mdbx2").also { directory ->
                check(directory.exists() || directory.mkdirs())
            }
            val vaultB = File(vaultBDirectory, "real-$providerKey-b-$runId.mdbx")
            coordinatorA.downloadBootstrapTo(remotePath, transport, vaultB)
            repositoryB.validatePasswordVaultFile(vaultB, vaultPassword)
            vaultFiles += vaultB
            val sourceB = sourceFactory(remoteSourceDao, securityManager, "Real $providerName B", remotePath)
            sourceIds += sourceB
            val databaseB = insertRemoteDatabase(
                databaseDao = databaseDao,
                securityManager = securityManager,
                sourceId = sourceB,
                name = "Real $providerName B",
                remotePath = remotePath,
                vaultFile = vaultB,
                password = vaultPassword,
                sourceType = sourceType
            )
            databaseIds += databaseB
            val coordinatorB = Mdbx2RemoteSyncCoordinator(
                rootDirectory = clientBRoot,
                sessions = Mdbx2RepositorySyncSessionProvider(repositoryB),
                stateStore = stateStore
            )
            coordinatorB.registerDownloadedBootstrap(databaseB, remotePath)

            val sharedFolder = repositoryA.createFolder(databaseA, "Shared folder", null)
            repositoryA.setProjectTags(databaseA, sharedFolder.folderId, listOf("Remote", providerName))
            val passwordEntry = PasswordEntry(
                id = 42L,
                title = "Remote entry",
                website = "https://example.test",
                username = "alice",
                password = "remote-secret",
                mdbxDatabaseId = databaseA,
                mdbxFolderId = sharedFolder.folderId
            )
            repositoryA.upsertPassword(passwordEntry)

            val attachmentStorage = AttachmentStorage(context)
            val encrypted = attachmentStorage.writeEncrypted(
                "real $providerName attachment $runId".byteInputStream()
            )
            encryptedAttachmentPath = encrypted.relativePath
            val wrappedCek = try {
                AttachmentKeyVault(securityManager).wrap(encrypted.cek)
            } finally {
                encrypted.cek.fill(0)
            }
            val attachment = Attachment(
                parentPasswordId = passwordEntry.id,
                source = AttachmentSource.LOCAL.name,
                fileName = "real-$providerKey.txt",
                mimeType = "text/plain",
                sizeBytes = encrypted.sizeBytes,
                sha256Hex = encrypted.sha256Hex,
                wrappedCek = wrappedCek,
                localPath = encrypted.relativePath,
                downloadState = AttachmentDownloadState.DOWNLOADED.name,
                createdAt = Date().time,
                updatedAt = Date().time
            )
            repositoryA.upsertAttachment(databaseA, "password:42", attachment)
            val sourceAttachment = repositoryA.readStoredAttachments(databaseA).single()
            val sourceBlob = repositoryA.withVaultForSync(databaseA) { _, vault ->
                vault.listExternalBlobReferences(null, 16u).items.single()
            }
            val vaultABlobRoot = File("${vaultA.absolutePath}.blobs").canonicalFile
            val vaultBBlobRoot = File("${vaultB.absolutePath}.blobs").canonicalFile
            assertFalse(
                "MDBX2 replicas must use separate Blob directories",
                vaultABlobRoot == vaultBBlobRoot
            )
            assertTrue("Client A Blob directory is missing", vaultABlobRoot.isDirectory)
            assertFalse(
                "Client B received attachment metadata before synchronization",
                repositoryB.readStoredAttachments(databaseB).isNotEmpty()
            )
            assertFalse(
                "Client B Blob exists before remote publication",
                repositoryB.withVaultForSync(databaseB) { _, vault ->
                    vault.hasExternalBlob(sourceBlob.blobId, sourceBlob.totalSize ?: 0uL)
                }
            )

            val uploadReport = syncStep("attachment_upload", coordinatorA, databaseA)
            assertTrue(uploadReport.uploadedSegments > 0)
            assertTrue(uploadReport.uploadedBlobs > 0)
            assertFalse(
                "Client B Blob exists before receiving the remote segment",
                repositoryB.withVaultForSync(databaseB) { _, vault ->
                    vault.hasExternalBlob(sourceBlob.blobId, sourceBlob.totalSize ?: 0uL)
                }
            )

            // Apply real native metadata, then interrupt the attachment download.
            // Editing in this state used to strand every later upload at "has no size".
            val interruptedTransport = object : takagi.ru.monica.utils.MdbxRemoteTransport by transport {
                override suspend fun readTo(path: String, destination: File) {
                    if ("/blobs/" in path) throw java.net.SocketTimeoutException("synthetic attachment timeout")
                    transport.readTo(path, destination)
                }
            }
            val interrupted = runCatching {
                syncStep("interrupted_attachment_receive", coordinatorB, databaseB, interruptedTransport)
            }.exceptionOrNull()
            assertTrue("Must interrupt the real Blob download", interrupted is java.net.SocketTimeoutException)
            val missingBlob = repositoryB.withVaultForSync(databaseB) { _, vault ->
                vault.listExternalBlobReferences(null, 16u).items.single()
            }
            assertEquals(uniffi.mdbx_ffi.MdbxExternalBlobState.MISSING, missingBlob.state)
            assertEquals(null, missingBlob.totalSize)
            repositoryB.upsertPassword(passwordEntry.copy(id = 849997L, mdbxDatabaseId = databaseB,
                title = "Local edit after attachment timeout"))
            val recoveredCoordinator = Mdbx2RemoteSyncCoordinator(clientBRoot,
                Mdbx2RepositorySyncSessionProvider(Mdbx2Repository(context, databaseDao, securityManager)),
                MdbxSyncStateStore(room.mdbxSyncStateDao()))
            val downloadReport = syncStep("attachment_recovery_and_local_edit", recoveredCoordinator, databaseB)
            assertTrue("Local edit must publish after attachment recovery", downloadReport.uploadedSegments > 0)
            assertTrue(downloadReport.downloadedSegments > 0)
            val replicaBlobReferences = repositoryB.withVaultForSync(databaseB) { _, vault ->
                vault.listExternalBlobReferences(null, 16u).items
            }
            assertEquals(
                "Client B did not receive the external Blob reference",
                listOf(sourceBlob.blobId),
                replicaBlobReferences.map { reference -> reference.blobId }
            )
            assertTrue(
                "Client B did not receive the referenced Blob",
                repositoryB.withVaultForSync(databaseB) { _, vault ->
                    vault.hasExternalBlob(sourceBlob.blobId, sourceBlob.totalSize ?: 0uL)
                }
            )
            assertTrue(
                "MDBX2 downloaded the Blob without reporting it: $downloadReport",
                downloadReport.downloadedBlobs > 0
            )
            assertEquals(
                "Shared folder",
                repositoryB.listFolders(databaseB).single { it.folderId == sharedFolder.folderId }.name
            )
            assertEquals(
                listOf("Remote", providerName).sortedWith(String.CASE_INSENSITIVE_ORDER),
                repositoryB.listProjectTags(databaseB, sharedFolder.folderId)
            )
            assertEquals(
                "Remote entry",
                repositoryB.readStoredEntries(databaseB)
                    .single { it.entryId == "password:42" }
                    .title
            )
            val replicaAttachment = repositoryB.readStoredAttachments(databaseB).single()
            assertEquals(sourceAttachment.fileName, replicaAttachment.fileName)
            assertEquals(sourceAttachment.contentHash, replicaAttachment.contentHash)
            assertEquals(
                "real $providerName attachment $runId",
                repositoryB.withVaultForSync(databaseB) { _, vault ->
                    vault.readAttachmentContent(
                        attachmentId = replicaAttachment.attachmentId,
                        maxPlaintextBytes = 1024uL * 1024uL
                    ).decodeToString()
                }
            )

            // Native tokens use real entry-owned assets, independently of Room password attachments.
            val tokenBytes = "synthetic native $providerName file".toByteArray()
            val nativeToken = repositoryA.saveNativeApiToken(databaseA, null, "Native remote token",
                """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://example.test/api/v4/","token":"synthetic-remote-native-token"}""",
                uploads = listOf(takagi.ru.monica.data.NativeApiTokenUpload("native-file.txt", "text/plain",
                    tokenBytes.size.toLong()) { tokenBytes.inputStream() }))
            syncStep("native_token_upload", coordinatorA, databaseA)
            syncStep("native_token_receive", coordinatorB, databaseB)
            val receivedToken = repositoryB.readNativeApiToken(databaseB, nativeToken.entryId)
            org.junit.Assert.assertArrayEquals(tokenBytes, repositoryB.readNativeApiTokenAttachment(
                receivedToken, receivedToken.attachments.single().id))
            repositoryA.transferNativeApiToken(nativeToken, databaseA, sharedFolder.folderId, false)
            syncStep("native_token_move_upload", coordinatorA, databaseA)
            syncStep("native_token_move_receive", coordinatorB, databaseB)
            val movedToken = repositoryB.readNativeApiToken(databaseB, nativeToken.entryId)
            assertEquals(sharedFolder.folderId, movedToken.summary.collectionId)
            org.junit.Assert.assertArrayEquals(tokenBytes, repositoryB.readNativeApiTokenAttachment(
                movedToken, movedToken.attachments.single().id))

            repositoryA.renameFolder(databaseA, sharedFolder.folderId, "Client A name")
            repositoryB.renameFolder(databaseB, sharedFolder.folderId, "Client B name")
            syncStep("folder_conflict_upload", coordinatorA, databaseA)
            val divergentReport = syncStep("folder_conflict_receive", coordinatorB, databaseB)
            assertTrue(divergentReport.conflicts > 0)
            assertTrue(repositoryB.listConflicts(databaseB).isNotEmpty())

            val reopenedB = Mdbx2Repository(context, databaseDao, securityManager)
            val reopenedCoordinatorB = Mdbx2RemoteSyncCoordinator(
                rootDirectory = clientBRoot,
                sessions = Mdbx2RepositorySyncSessionProvider(reopenedB),
                stateStore = MdbxSyncStateStore(room.mdbxSyncStateDao())
            )
            val retryReport = syncStep("reopen_retry", reopenedCoordinatorB, databaseB)
            assertEquals(0, retryReport.blockedStreams)
            assertTrue(reopenedB.listConflicts(databaseB).isNotEmpty())
            assertEquals(
                MdbxSyncStatus.PENDING_UPLOAD.name,
                databaseDao.getDatabaseById(databaseB)?.lastSyncStatus
            )
            if (InstrumentationRegistry.getArguments().getString("mdbxExerciseAutoSync") == "true") {
                exerciseAutomaticSync(
                    context, databaseIds, sessionPreferences, providerKey, runId,
                    databaseA, vaultABlobRoot, sourceBlob, repositoryA, passwordEntry,
                    room, databaseDao, databaseB, reopenedB,
                    receiveAutomaticWorkerEdits = {
                        syncStep("receive_automatic_worker_edits", reopenedCoordinatorB, databaseB)
                    },
                )
            }
        } finally {
            cleanupRealProviderFixture(
                context, room, databaseDao, remoteSourceDao, securityManager, stateStore,
                databaseIds, sourceIds, vaultFiles, encryptedAttachmentPath,
                clientARoot, clientBRoot, sessionPreferences, originalDeviceId,
            )
        }
    }

    private suspend fun exerciseAutomaticSync(
        context: Context,
        databaseIds: List<Long>,
        sessionPreferences: SharedPreferences,
        providerKey: String,
        runId: String,
        databaseA: Long,
        vaultABlobRoot: File,
        sourceBlob: uniffi.mdbx_ffi.MdbxExternalBlobReference,
        repositoryA: Mdbx2Repository,
        passwordEntry: PasswordEntry,
        room: PasswordDatabase,
        databaseDao: takagi.ru.monica.data.LocalMdbxDatabaseDao,
        databaseB: Long,
        reopenedB: Mdbx2Repository,
        receiveAutomaticWorkerEdits: suspend () -> Unit,
    ) {
        val work = androidx.work.WorkManager.getInstance(context)
        databaseIds.forEach { work.cancelUniqueWork("mdbx-auto-$it").result.get() }
        val previouslyUnlocked = takagi.ru.monica.security.SessionManager.isUnlocked.value
        setDeviceId(sessionPreferences, "real-$providerKey-a-$runId")
        try {
            takagi.ru.monica.workers.MdbxAutoSyncPreferences(context).setEnabled(databaseA, true)
            takagi.ru.monica.security.SessionManager.markUnlocked()
            // Delete only this synthetic vault's attachment ciphertext.
            // WorkManager must repair it from the provider before publishing the edit.
            val localBlob = vaultABlobRoot.walkTopDown().single {
                it.isFile && it.name == sourceBlob.blobId
            }
            check(localBlob.canonicalPath.startsWith(vaultABlobRoot.canonicalPath + File.separator))
            check(localBlob.delete())
            assertEquals(null, repositoryA.withVaultForSync(databaseA) { _, vault ->
                vault.listExternalBlobReferences(null, 16u).items.single { it.blobId == sourceBlob.blobId }.totalSize
            })
            val previousJobs = work.getWorkInfosForUniqueWork("mdbx-auto-$databaseA").get().map { it.id }.toSet()
            repositoryA.upsertPassword(passwordEntry.copy(id = 849999L, title = "Automatic delivery"))
            kotlinx.coroutines.withTimeout(30_000L) {
                while (work.getWorkInfosForUniqueWork("mdbx-auto-$databaseA").get()
                    .none { it.id !in previousJobs && it.state == androidx.work.WorkInfo.State.RUNNING }) {
                    kotlinx.coroutines.delay(50)
                }
            }
            repositoryA.upsertPassword(passwordEntry.copy(id = 849998L, title = "Saved during upload"))
            repeat(20) { takagi.ru.monica.workers.MdbxAutoSyncWorker.enqueue(context, databaseA) }
            kotlinx.coroutines.withTimeout(120_000L) {
                while (true) {
                    val jobs = work.getWorkInfosForUniqueWork("mdbx-auto-$databaseA").get().filter { it.id !in previousJobs }
                    if (jobs.any { it.state == androidx.work.WorkInfo.State.SUCCEEDED } &&
                        jobs.none { !it.state.isFinished }) break
                    kotlinx.coroutines.delay(250)
                }
            }
            assertTrue("Automatic job must import its result into the UI projection",
                room.passwordEntryDao().getByMdbxDatabaseIdSync(databaseA).any { it.title == "Automatic delivery" })
            assertTrue("Automatic job must recover the missing attachment",
                repositoryA.withVaultForSync(databaseA) { _, vault ->
                    vault.hasExternalBlob(sourceBlob.blobId, requireNotNull(sourceBlob.totalSize))
                })
            assertEquals(MdbxSyncStatus.IN_SYNC.name, databaseDao.getDatabaseById(databaseA)?.lastSyncStatus)
            receiveAutomaticWorkerEdits()
            assertTrue("An edit during the running upload must also arrive",
                reopenedB.readStoredEntries(databaseB).any { it.title == "Saved during upload" && !it.deleted })
            assertTrue("Other replica must receive the automatically uploaded edit",
                reopenedB.readStoredEntries(databaseB).any { it.title == "Automatic delivery" && !it.deleted })
        } finally {
            if (!previouslyUnlocked) takagi.ru.monica.security.SessionManager.markLocked()
        }
    }

    private suspend fun cleanupRealProviderFixture(
        context: Context,
        room: PasswordDatabase,
        databaseDao: takagi.ru.monica.data.LocalMdbxDatabaseDao,
        remoteSourceDao: takagi.ru.monica.data.MdbxRemoteSourceDao,
        securityManager: SecurityManager,
        stateStore: MdbxSyncStateStore,
        databaseIds: List<Long>,
        sourceIds: List<Long>,
        vaultFiles: List<File>,
        encryptedAttachmentPath: String?,
        clientARoot: File,
        clientBRoot: File,
        sessionPreferences: SharedPreferences,
        originalDeviceId: String?,
    ) {
        databaseIds.forEach { databaseId ->
            takagi.ru.monica.workers.MdbxAutoSyncPreferences(context).setEnabled(databaseId, false)
            runCatching { androidx.work.WorkManager.getInstance(context).cancelUniqueWork("mdbx-auto-$databaseId").result.get() }
            runCatching { room.passwordEntryDao().deleteAllByMdbxDatabaseId(databaseId) }
            runCatching { room.secureItemDao().deleteAllByMdbxDatabaseId(databaseId) }
            runCatching { room.passkeyDao().deleteAllByMdbxDatabaseId(databaseId) }

            runCatching { stateStore.delete(databaseId) }
            runCatching { databaseDao.deleteDatabaseById(databaseId) }
        }
        sourceIds.forEach { sourceId ->
            runCatching { remoteSourceDao.deleteSourceById(sourceId) }
        }
        vaultFiles.forEach { file ->
            runCatching { Mdbx2Repository(context, databaseDao, securityManager).deleteOwnedVaultFile(file) }
        }
        encryptedAttachmentPath?.let { relativePath ->
            runCatching { AttachmentStorage(context).delete(relativePath) }
        }
        clientARoot.deleteRecursively()
        clientBRoot.deleteRecursively()
        if (originalDeviceId == null) {
            sessionPreferences.edit().remove(DEVICE_ID_KEY).commit()
        } else {
            sessionPreferences.edit().putString(DEVICE_ID_KEY, originalDeviceId).commit()
        }
    }

    private suspend fun insertWebDavRemoteSource(
        remoteSourceDao: takagi.ru.monica.data.MdbxRemoteSourceDao,
        securityManager: SecurityManager,
        displayName: String,
        remotePath: String,
        serverUrl: String,
        username: String,
        password: String
    ): Long = remoteSourceDao.insertSource(
        MdbxRemoteSource(
            displayName = "$displayName ${UUID.randomUUID()}",
            remotePath = remotePath,
            remoteParentPath = remotePath.substringBeforeLast('/', "").ifBlank { null },
            baseUrl = serverUrl,
            usernameEncrypted = username.takeIf(String::isNotEmpty)?.let(securityManager::encryptData),
            passwordEncrypted = password.takeIf(String::isNotEmpty)?.let(securityManager::encryptData)
        )
    )

    private suspend fun insertRemoteDatabase(
        databaseDao: takagi.ru.monica.data.LocalMdbxDatabaseDao,
        securityManager: SecurityManager,
        sourceId: Long,
        name: String,
        remotePath: String,
        vaultFile: File,
        password: String,
        sourceType: MdbxSourceType
    ): Long = databaseDao.insertDatabase(
        LocalMdbxDatabase(
            name = "$name ${UUID.randomUUID()}",
            filePath = remotePath,
            storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
            sourceType = sourceType.name,
            sourceId = sourceId,
            engineType = MdbxEngineType.RUST_MDBX2.name,
            tigaMode = MdbxTigaMode.SKY.name,
            encryptedPassword = securityManager.encryptData(password),
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
            kdfProfile = "argon2id-mdbx2",
            workingCopyPath = vaultFile.absolutePath,
            cacheCopyPath = vaultFile.absolutePath,
            isOfflineAvailable = true,
            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name
        )
    )

    private fun setDeviceId(
        preferences: android.content.SharedPreferences,
        value: String
    ) {
        check(preferences.edit().putString(DEVICE_ID_KEY, value).commit())
    }

    companion object {
        private const val ARG_SERVER_URL = "mdbxWebDavUrl"
        private const val ARG_USERNAME = "mdbxWebDavUsername"
        private const val ARG_PASSWORD = "mdbxWebDavPassword"
        private const val SESSION_PREFERENCES = "mdbx2_vault_sessions"
        private const val DEVICE_ID_KEY = "device_id"
        private const val REAL_PROVIDER_TIMEOUT_MS = 180_000L
    }
}
