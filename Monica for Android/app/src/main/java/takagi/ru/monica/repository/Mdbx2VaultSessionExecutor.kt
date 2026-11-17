package takagi.ru.monica.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.LocalMdbxDatabaseDao
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxSyncCheckpointState
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.resolvedActiveFilePath
import takagi.ru.monica.mdbx.MdbxDiagLogger
import takagi.ru.monica.security.SecurityManager
import uniffi.mdbx_ffi.MdbxVault
import uniffi.mdbx_ffi.MdbxTigaScope
import uniffi.mdbx_ffi.MdbxTigaScopeType
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.MdbxDeviceAssurance
import uniffi.mdbx_ffi.MdbxDeviceContext
import uniffi.mdbx_ffi.createVaultWithTigaMode
import uniffi.mdbx_ffi.openVault
import uniffi.mdbx_ffi.openVaultWithPasswordSecurityKey
import uniffi.mdbx_ffi.openVaultWithSecurityKey
import uniffi.mdbx_ffi.MdbxTigaMode as RustTigaMode

internal class Mdbx2VaultSessionExecutor(
    context: Context,
    private val databaseDao: LocalMdbxDatabaseDao,
    private val securityManager: SecurityManager,
    private val externalStorage: Mdbx2ExternalStorage = Mdbx2ExternalStorage(context)
) {
    private val appContext = context.applicationContext

    private val deviceId: String by lazy {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.getString(DEVICE_ID_KEY, null)?.takeIf { it.isNotBlank() }
            ?: "monica-android-${UUID.randomUUID()}".also { generated ->
                preferences.edit().putString(DEVICE_ID_KEY, generated).apply()
            }
    }

    suspend fun createInitializedVaultFile(
        tigaMode: MdbxTigaMode,
        password: String
    ): File = createInitializedVaultFile(
        tigaMode = tigaMode,
        credential = MdbxVaultCredential(
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD,
            password = password
        )
    )

    suspend fun createInitializedVaultFile(
        tigaMode: MdbxTigaMode,
        credential: MdbxVaultCredential
    ): File = withContext(Dispatchers.IO) {
        MdbxClientModePolicy.requireSupported(appContext, tigaMode)
        Mdbx2NativeRuntime.ensureLoaded()
        // Native setup consumes the byte arrays synchronously, but callers may
        // still need their key-file selection after creation (for example to
        // persist its fingerprint or retry an external publication).  Work on
        // private copies and wipe only those copies on every exit path.
        val workingCredential = credential.copy(
            keyFileBytes = credential.keyFileBytes?.clone(),
            deviceKeyBytes = credential.deviceKeyBytes?.clone()
        )
        try {
            validateCredential(workingCredential)
            val directory = File(appContext.filesDir, MDBX2_DIRECTORY).also { target ->
                check(target.exists() || target.mkdirs()) { "Cannot create MDBX2 directory" }
            }
            val file = File(directory, "${UUID.randomUUID()}.mdbx")
            val bootstrapPassword = normalizePassword(
                workingCredential.password?.takeIf { it.isNotEmpty() }
                    ?: "mdbx2-bootstrap-${UUID.randomUUID()}"
            )
            val vault = try {
                createVaultWithTigaMode(
                    path = file.absolutePath,
                    password = bootstrapPassword,
                    deviceId = deviceId,
                    mode = tigaMode.toRustMode()
                )
            } catch (error: Throwable) {
                deleteVaultArtifacts(file)
                val mapped = Mdbx2ErrorMapper.createFailure(error)
                MdbxDiagLogger.append(
                    "[MDBX2][create] failed kind=${mapped.kind.name} chain=${error.toDiagnosticChain()}"
                )
                throw mapped
            }
            var vaultClosed = false
            fun closeVault() {
                if (!vaultClosed) {
                    runCatching { vault.close() }
                    vaultClosed = true
                }
            }
            try {
                val rootProjectId = rootProjectId(vault.info().vaultId)
                vault.executeWriteOperation(
                    operationId = UUID.randomUUID().toString(),
                    operationKind = "monica-initialize",
                    commands = listOf(
                        MdbxWriteCommand.CreateProject(
                            projectId = rootProjectId,
                            title = ROOT_PROJECT_TITLE
                        )
                    )
                )
                configureRequestedUnlockMethod(vault, workingCredential)
            } catch (error: Throwable) {
                closeVault()
                deleteVaultArtifacts(file)
                val mapped = Mdbx2ErrorMapper.createFailure(error)
                MdbxDiagLogger.append(
                    "[MDBX2][create] failed kind=${mapped.kind.name} chain=${error.toDiagnosticChain()}"
                )
                throw mapped
            } finally {
                closeVault()
            }
            file
        } finally {
            workingCredential.keyFileBytes?.fill(0)
            workingCredential.deviceKeyBytes?.fill(0)
        }
    }

    suspend fun deleteOwnedVaultFile(file: File): Boolean = withContext(Dispatchers.IO) {
        val directory = ownedVaultDirectory()
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return@withContext false
        if (candidate.parentFile != directory || candidate.extension.lowercase() != "mdbx") {
            return@withContext false
        }
        Mdbx2NativeReadSessions.clear()
        val blobStoreSidecars = listOf("blobs", "leases", "transfers").map { suffix ->
            File(directory, "${candidate.name}.$suffix").canonicalFile
        }
        if (blobStoreSidecars.any { sidecar -> sidecar.parentFile != directory }) {
            return@withContext false
        }
        val sidecarsDeleted = blobStoreSidecars
            .map { sidecar -> !sidecar.exists() || sidecar.deleteRecursively() }
            .all { deleted -> deleted }
        sidecarsDeleted && deleteVaultArtifacts(candidate)
    }

    internal fun isOwnedVaultFile(file: File): Boolean {
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return candidate.parentFile == ownedVaultDirectory() &&
            candidate.extension.equals("mdbx", ignoreCase = true)
    }

    suspend fun <T> withVault(
        databaseId: Long,
        block: suspend (LocalMdbxDatabase, MdbxVault) -> T
    ): T = withVaultInternal(databaseId, mutating = false, block)

    /** Pure reads share a Rust unlock while its disclosure policy and app lifecycle permit. */
    suspend fun <T> withNativeReadVault(
        databaseId: Long,
        scope: MdbxTigaScope = MdbxTigaScope(MdbxTigaScopeType.VAULT, null),
        block: (LocalMdbxDatabase, MdbxVault) -> T
    ): T = withContext(Dispatchers.IO) {
        Mdbx2NativeRuntime.ensureLoaded()
        vaultLocks.getOrPut(databaseId) { Mutex() }.withLock {
            val database = requireDatabase(databaseId)
            val file = resolveLocalFile(database)
            if (!file.isFile) throw Mdbx2ErrorMapper.fileMissing()
            Mdbx2NativeReadSessions.read(database, file,
                open = { openVaultForRead(database, file) },
                scope = scope,
                block = { vault -> block(database, vault) })
        }
    }

    suspend fun <T> withMutatingVault(
        databaseId: Long,
        block: suspend (LocalMdbxDatabase, MdbxVault) -> T
    ): T = withVaultInternal(databaseId, mutating = true, block)

    /** A fresh request-owned native handle; never opens the main UI or IME authorization session. */
    suspend fun <T> withAutofillReadVault(
        databaseId: Long,
        checkActive: () -> Unit,
        block: (LocalMdbxDatabase, MdbxVault, String, () -> Unit) -> T,
    ): T = withContext(Dispatchers.IO) {
        val generation = SecurityManager.dataReadGeneration.value
        val requestContext = kotlinx.coroutines.currentCoroutineContext()
        fun checkRequest() {
            requestContext.ensureActive()
            checkActive()
            check(SecurityManager.dataReadGeneration.value == generation) { "MDBX autofill request expired" }
        }
        checkRequest()
        Mdbx2NativeRuntime.ensureLoaded()
        vaultLocks.getOrPut(databaseId) { Mutex() }.withLock {
            checkRequest()
            val database = requireDatabase(databaseId)
            val file = resolveLocalFile(database)
            fun identity(current: LocalMdbxDatabase): String {
                val target = resolveLocalFile(current).canonicalFile
                val attrs = java.nio.file.Files.readAttributes(target.toPath(),
                    java.nio.file.attribute.BasicFileAttributes::class.java)
                val inode = checkNotNull(attrs.fileKey()) { "MDBX file identity unavailable" }.toString()
                return mdbxAutofillDigest(listOf(current.id.toString(), target.path, inode,
                    current.engineType, current.sourceType, current.sourceId?.toString(), current.filePath,
                    current.workingCopyPath, current.externalTreeUri, current.encryptedPassword,
                    current.unlockMethod, current.keyFileUri, current.keyFileFingerprint, current.tigaMode,
                    current.lastSyncedAt?.toString()))
            }
            val namespace = identity(database)
            checkRequest()
            val vault = openVaultForRead(database, file)
            try {
                checkRequest()
                val result = block(database, vault, namespace, ::checkRequest)
                check(namespace == identity(requireDatabase(databaseId))) { "MDBX source changed" }
                checkRequest()
                result
            } finally { vault.close() }
        }
    }

    /** Do not clear edits made during network I/O or while the UI imports the result. */
    suspend fun completeRemoteSync(databaseId: Long, report: Mdbx2RemoteSyncReport): MdbxSyncStatus =
        withContext(Dispatchers.IO) {
            Mdbx2NativeRuntime.ensureLoaded()
            val published = requireNotNull(report.publishedCheckpoint)
            val syncedCommitInventory = report.syncedCommitInventory ?: published.commitInventory
            vaultLocks.getOrPut(databaseId) { Mutex() }.withLock {
                val database = requireDatabase(databaseId)
                val file = resolveLocalFile(database)
                if (!file.isFile) throw Mdbx2ErrorMapper.fileMissing()
                val readCheckpoint: (MdbxVault) -> MdbxSyncCheckpointState = { vault ->
                    require(vault.info().vaultId == report.vaultId) {
                        "MDBX2 vault identity changed before synchronization completed"
                    }
                    val checkpoint = vault.incrementalSyncCheckpoint()
                    MdbxSyncCheckpointState(checkpoint.commitInventory, checkpoint.deltaInventory)
                }
                val current = Mdbx2NativeReadSessions.read(database, file,
                    open = { openVaultForRead(database, file) }, block = readCheckpoint)
                val status = when {
                    report.conflicts > 0 -> MdbxSyncStatus.CONFLICT
                    report.blockedStreams > 0 -> MdbxSyncStatus.REMOTE_CHANGED
                    // Ordinary reads can append security-audit deltas without editing data.
                    // Keep their transport cursor pending, but base the visible dirty state
                    // on committed changes, including edits made during remote apply/import.
                    current.commitInventory != syncedCommitInventory -> MdbxSyncStatus.PENDING_UPLOAD
                    else -> MdbxSyncStatus.IN_SYNC
                }
                // The checkpoint check and status update share the same lock as native edits.
                databaseDao.updateRemoteSyncSuccess(databaseId, status.name, System.currentTimeMillis())
                status
            }
        }

    suspend fun createExternalDocument(
        treeUri: Uri,
        displayName: String,
        workingCopy: File
    ): Mdbx2ExternalDocument = externalStorage.createDocument(treeUri, displayName, workingCopy)

    suspend fun deleteCreatedExternalDocument(document: Mdbx2ExternalDocument) {
        externalStorage.deleteCreatedDocument(document)
    }

    suspend fun copyExternalDocumentToOwnedFile(
        sourceUri: Uri,
        sourceTreeUri: Uri? = null
    ): File = withContext(Dispatchers.IO) {
        val directory = ownedVaultDirectory().also { target ->
            check(target.exists() || target.mkdirs()) { "Cannot create MDBX2 directory" }
        }
        val target = File(directory, "${UUID.randomUUID()}.mdbx")
        externalStorage.copyDocumentToOwnedFile(sourceUri, target, sourceTreeUri)
        target
    }

    suspend fun inspectVaultFormat(file: File): String? = withContext(Dispatchers.IO) {
        Mdbx2NativeRuntime.ensureLoaded()
        uniffi.mdbx_ffi.inspectVaultMigration(file.absolutePath).formatVersion
    }

    suspend fun validatePasswordVaultFile(file: File, password: String) {
        validateVaultFile(file, MdbxVaultCredential(MdbxUnlockMethod.MASTER_PASSWORD, password))
    }

    suspend fun validateVaultFile(file: File, credential: MdbxVaultCredential): MdbxTigaMode =
        withContext(Dispatchers.IO) {
            Mdbx2NativeRuntime.ensureLoaded()
            val workingCredential = credential.copy(
                keyFileBytes = credential.keyFileBytes?.clone(),
                deviceKeyBytes = credential.deviceKeyBytes?.clone()
            )
            try {
                val vault = openVaultWithCredential(file, workingCredential)
                try {
                    MdbxTigaMode.valueOf(vault.resolveTigaPolicy(MdbxTigaScope(MdbxTigaScopeType.VAULT, null)).profile.name)
                } finally { vault.close() }
            } finally {
                workingCredential.keyFileBytes?.fill(0)
                workingCredential.deviceKeyBytes?.fill(0)
            }
        }

    suspend fun flushExternalWorkingCopy(databaseId: Long, onlyIfPending: Boolean) =
        withContext(Dispatchers.IO) {
            vaultLocks.getOrPut(databaseId) { Mutex() }.withLock {
                Mdbx2NativeReadSessions.invalidate(databaseId)
                val database = requireDatabase(databaseId)
                if (database.sourceTypeEnum != MdbxSourceType.LOCAL_EXTERNAL) return@withLock
                if (onlyIfPending && database.lastSyncStatus != takagi.ru.monica.data.MdbxSyncStatus.PENDING_UPLOAD.name) {
                    return@withLock
                }
                val file = resolveLocalFile(database)
                if (!file.isFile) throw Mdbx2ErrorMapper.fileMissing()
                publishExternal(database, file)
            }
        }

    suspend fun refreshExternalWorkingCopy(databaseId: Long) = withContext(Dispatchers.IO) {
        vaultLocks.getOrPut(databaseId) { Mutex() }.withLock {
            Mdbx2NativeReadSessions.invalidate(databaseId)
            var database = requireDatabase(databaseId)
            require(database.sourceTypeEnum == MdbxSourceType.LOCAL_EXTERNAL) {
                "MDBX2 refresh requires an external local vault"
            }
            val workingCopy = resolveLocalFile(database)
            // A failed publication still owns committed local edits. Never refresh over them.
            if (database.lastSyncStatus in setOf(MdbxSyncStatus.PENDING_UPLOAD.name, MdbxSyncStatus.FAILED.name)) {
                publishExternal(database, workingCopy)
                database = requireDatabase(databaseId)
            }
            externalStorage.replaceWorkingCopyFromDocument(
                sourceUri = Uri.parse(database.filePath),
                sourceTreeUri = database.externalTreeUri?.takeIf(String::isNotBlank)?.let(Uri::parse),
                workingCopy = workingCopy
            ) { staged ->
                val vault = openVaultForDatabase(database, staged)
                vault.close()
            }
            databaseDao.updateSyncSuccess(
                databaseId = databaseId,
                status = takagi.ru.monica.data.MdbxSyncStatus.IN_SYNC.name,
                time = System.currentTimeMillis()
            )
        }
    }

    private suspend fun <T> withVaultInternal(
        databaseId: Long,
        mutating: Boolean,
        block: suspend (LocalMdbxDatabase, MdbxVault) -> T
    ): T = withContext(Dispatchers.IO) {
        Mdbx2NativeRuntime.ensureLoaded()
        vaultLocks.getOrPut(databaseId) { Mutex() }.withLock {
            val database = requireDatabase(databaseId)
            val file = resolveLocalFile(database)
            if (!file.isFile) throw Mdbx2ErrorMapper.fileMissing()
            Mdbx2NativeReadSessions.invalidate(databaseId)
            val vault = openVaultForRead(database, file)
            var failure: Throwable? = null
            val before = try { if (mutating) vault.incrementalSyncCheckpoint().commitInventory else null }
                catch (error: Throwable) { vault.close(); throw error }
            try { block(database, vault) }
            catch (error: Throwable) { failure = error; throw error }
            finally {
                // A successful native commit must remain pending for publication even if
                // subsequent readback or coroutine cancellation fails the caller.
                try {
                    val changed = try { mutating && before != vault.incrementalSyncCheckpoint().commitInventory }
                        finally { vault.close() }
                    if (changed) withContext(kotlinx.coroutines.NonCancellable) { finalizeMutation(database, file) }
                } catch (finalizeError: Throwable) {
                    if (failure != null) failure.addSuppressed(finalizeError) else throw finalizeError
                }
            }
        }
    }

    private fun openVaultForRead(database: LocalMdbxDatabase, file: File): MdbxVault = try {
        openVaultForDatabase(database, file)
    } catch (error: Throwable) {
        val mapped = Mdbx2ErrorMapper.openFailure(error)
        MdbxDiagLogger.append(
            "[MDBX2][open] failed databaseId=${database.id} kind=${mapped.kind.name} cause=${error::class.java.simpleName}"
        )
        throw mapped
    }

    private fun decryptPassword(database: LocalMdbxDatabase): String {
        if (!database.unlockMethodEnum.requiresPassword()) return ""
        return try {
            database.encryptedPassword
                ?.takeIf { it.isNotBlank() }
                ?.let(securityManager::decryptData)
                .orEmpty()
        } catch (error: Throwable) {
            throw Mdbx2ErrorMapper.credentialUnavailable(error)
        }
    }

    private fun openVaultForDatabase(
        database: LocalMdbxDatabase,
        file: File
    ): MdbxVault {
        MdbxClientModePolicy.requireSupported(appContext, database)
        val credential = credentialForDatabase(database)
        return try {
            openVaultWithCredential(file, credential)
        } finally {
            credential.keyFileBytes?.fill(0)
            credential.deviceKeyBytes?.fill(0)
        }
    }

    private fun openVaultWithCredential(
        file: File,
        credential: MdbxVaultCredential
    ): MdbxVault {
        MdbxClientModePolicy.requireSupportedFile(appContext, file)
        validateCredential(credential)
        val password = credential.password?.let(::normalizePassword).orEmpty()
        val keyMaterial = credential.keyFileBytes ?: credential.deviceKeyBytes
        return when (credential.unlockMethod) {
            MdbxUnlockMethod.MASTER_PASSWORD -> openVault(
                path = file.absolutePath,
                password = password,
                deviceId = deviceId
            )
            MdbxUnlockMethod.KEY_FILE,
            MdbxUnlockMethod.DEVICE_KEY -> openVaultWithSecurityKey(
                path = file.absolutePath,
                keyMaterial = keyMaterial ?: error("MDBX security key is missing"),
                deviceId = deviceId
            )
            MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE -> openVaultWithPasswordSecurityKey(
                path = file.absolutePath,
                password = password,
                keyMaterial = keyMaterial ?: error("MDBX security key is missing"),
                deviceId = deviceId
            )
        }
    }

    private fun credentialForDatabase(database: LocalMdbxDatabase): MdbxVaultCredential {
        val method = database.unlockMethodEnum
        val password = if (method.requiresPassword()) decryptPassword(database) else null
        val keyMaterial = when (method) {
            MdbxUnlockMethod.KEY_FILE,
            MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE -> readKeyFile(database)
            MdbxUnlockMethod.DEVICE_KEY -> MdbxVaultCrypto.decodeDeviceKey(
                value = database.encryptedPassword,
                decrypt = securityManager::decryptData
            )
            MdbxUnlockMethod.MASTER_PASSWORD -> null
        }
        return MdbxVaultCredential(
            unlockMethod = method,
            password = password,
            keyFileBytes = keyMaterial.takeIf {
                method == MdbxUnlockMethod.KEY_FILE ||
                    method == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE
            },
            deviceKeyBytes = keyMaterial.takeIf { method == MdbxUnlockMethod.DEVICE_KEY }
        )
    }

    private fun readKeyFile(database: LocalMdbxDatabase): ByteArray {
        val uriString = database.keyFileUri?.takeIf { it.isNotBlank() }
            ?: throw Mdbx2ErrorMapper.credentialUnavailable(
                IllegalStateException("MDBX key file URI is missing")
            )
        val bytes = appContext.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
            input.readMdbxKeyFileBytes(database.keyFileFingerprint)
        } ?: throw Mdbx2ErrorMapper.credentialUnavailable(
            IllegalStateException("MDBX key file cannot be read")
        )
        return bytes
    }

    private fun validateCredential(credential: MdbxVaultCredential) {
        when (credential.unlockMethod) {
            MdbxUnlockMethod.MASTER_PASSWORD -> require(!credential.password.isNullOrEmpty()) {
                "MDBX master password is required"
            }
            MdbxUnlockMethod.KEY_FILE,
            MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE -> {
                if (credential.unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE) {
                    require(!credential.password.isNullOrEmpty()) {
                        "MDBX master password is required"
                    }
                }
                require(credential.keyFileBytes?.isEmpty() == false) {
                    "MDBX key file is required"
                }
            }
            MdbxUnlockMethod.DEVICE_KEY -> require(credential.deviceKeyBytes?.isEmpty() == false) {
                "MDBX device key is required"
            }
        }
    }

    private fun configureRequestedUnlockMethod(
        vault: MdbxVault,
        credential: MdbxVaultCredential
    ) {
        if (credential.unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD) return
        val device = snapshotDeviceContext()
        val passwordMethod = vault.listUnlockMethods()
            .firstOrNull { it.methodType == uniffi.mdbx_ffi.MdbxUnlockMethodType.PASSWORD }
            ?: error("MDBX bootstrap password method is missing")
        when (credential.unlockMethod) {
            MdbxUnlockMethod.KEY_FILE,
            MdbxUnlockMethod.DEVICE_KEY -> vault.setupLocalSecurityKeyUnlockWithDeviceContext(
                keyMaterial = credential.keyFileBytes ?: credential.deviceKeyBytes
                    ?: error("MDBX security key is missing"),
                device = device
            )
            MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE -> vault.setupPasswordSecurityKeyUnlock(
                password = normalizePassword(credential.password ?: error("MDBX password is missing")),
                keyMaterial = credential.keyFileBytes ?: error("MDBX key file is missing"),
                device = device
            )
            MdbxUnlockMethod.MASTER_PASSWORD -> Unit
        }
        vault.removeUnlockMethod(passwordMethod.methodId, device)
    }

    private fun snapshotDeviceContext(): MdbxDeviceContext = MdbxDeviceContext(
        assurance = MdbxDeviceAssurance.STANDARD,
        secureClipboardAvailable = true,
        screenCaptureProtectionAvailable = true,
        secureTempFilesAvailable = true
    )

    private suspend fun finalizeMutation(database: LocalMdbxDatabase, file: File, localVault: MdbxVault? = null) {
        when (database.sourceTypeEnum) {
            MdbxSourceType.LOCAL_INTERNAL -> databaseDao.updateSyncStatus(
                database.id,
                takagi.ru.monica.data.MdbxSyncStatus.LOCAL_ONLY.name,
                null
            )
            MdbxSourceType.LOCAL_EXTERNAL -> {
                databaseDao.updateSyncStatus(
                    database.id,
                    takagi.ru.monica.data.MdbxSyncStatus.PENDING_UPLOAD.name,
                    null
                )
                publishExternal(database, file, localVault)
            }
            MdbxSourceType.REMOTE_WEBDAV,
            MdbxSourceType.REMOTE_ONEDRIVE -> {
                databaseDao.updateSyncStatus(database.id, MdbxSyncStatus.PENDING_UPLOAD.name, null)
                // Scheduling failure must not turn a committed local edit into a failed save.
                // Startup/unlock recovery scans the durable pending status again.
                runCatching { takagi.ru.monica.workers.MdbxAutoSyncWorker.enqueue(appContext, database.id) }
            }
        }
    }

    private suspend fun publishExternal(database: LocalMdbxDatabase, file: File, localVault: MdbxVault? = null) {
        try {
            val publication = externalStorage.publishWithMerge(
                database = database,
                workingCopy = file
            ) { stagedRemote ->
                mergeExternalRevision(
                    database = database,
                    workingCopy = file,
                    stagedRemote = stagedRemote,
                    borrowedLocalVault = localVault
                )
            }
            if (publication.conflictCount > 0) {
                MdbxDiagLogger.append(
                    "[MDBX2][external-merge] publication completed " +
                        "databaseId=${database.id} conflicts=${publication.conflictCount}"
                )
            }
            databaseDao.updateSyncSuccess(
                databaseId = database.id,
                status = takagi.ru.monica.data.MdbxSyncStatus.IN_SYNC.name,
                time = System.currentTimeMillis()
            )
        } catch (error: Throwable) {
            databaseDao.updateSyncStatus(
                database.id,
                takagi.ru.monica.data.MdbxSyncStatus.FAILED.name,
                error.message
            )
            throw IllegalStateException(
                "Failed to publish MDBX2 vault to the selected local document",
                error
            )
        }
    }

    private fun mergeExternalRevision(
        database: LocalMdbxDatabase,
        workingCopy: File,
        stagedRemote: File,
        borrowedLocalVault: MdbxVault? = null
    ): Int {
        val localVault = borrowedLocalVault ?: openVaultForDatabase(database, workingCopy)
        var conflictCount = 0
        try {
            val remoteVault = openVaultForDatabase(database, stagedRemote)
            try {
                require(localVault.info().vaultId == remoteVault.info().vaultId) {
                    "External MDBX2 vault identity does not match the local working copy"
                }
                externalStorage.mergeSidecarIntoWorkingCopy(stagedRemote, workingCopy)
                val bundleDirectory = File(appContext.cacheDir, "mdbx2-external-merge")
                    .also { directory ->
                        check(directory.exists() || directory.mkdirs()) {
                            "Cannot create MDBX2 external merge directory"
                        }
                    }
                val bundle = File(bundleDirectory, "${UUID.randomUUID()}.mdbxsync")
                try {
                    remoteVault.exportManualSyncBundle(bundle.absolutePath)
                    val result = localVault.applyManualSyncBundle(bundle.absolutePath)
                    check(result.missingParentCount == 0u) {
                        "External MDBX2 merge contains commits with missing parents"
                    }
                    MdbxDiagLogger.append(
                        "[MDBX2][external-merge] imported " +
                            "databaseId=${database.id} applied=${result.appliedCommits} " +
                            "skipped=${result.skippedCommits} conflicts=${result.conflictCount}"
                    )
                    conflictCount = result.conflictCount.toInt()
                } finally {
                    bundle.delete()
                }
            } finally {
                remoteVault.close()
            }
        } finally {
            if (borrowedLocalVault == null) runCatching { localVault.close() }
        }
        return conflictCount
    }

    private suspend fun requireDatabase(databaseId: Long): LocalMdbxDatabase {
        val database = databaseDao.getDatabaseById(databaseId)
            ?: throw Mdbx2ErrorMapper.databaseNotFound()
        if (database.engineTypeEnum != MdbxEngineType.RUST_MDBX2) {
            throw Mdbx2ErrorMapper.unsupportedSource()
        }
        if (database.sourceTypeEnum !in SUPPORTED_SOURCE_TYPES) {
            throw Mdbx2ErrorMapper.unsupportedSource()
        }
        MdbxClientModePolicy.requireSupported(appContext, database)
        return database
    }

    private fun resolveLocalFile(database: LocalMdbxDatabase): File {
        val rawPath = database.resolvedActiveFilePath().takeIf { it.isNotBlank() }
            ?: throw Mdbx2ErrorMapper.fileMissing()
        return File(rawPath).let { file ->
            if (file.isAbsolute) file else File(appContext.filesDir, rawPath)
        }
    }

    private fun ownedVaultDirectory(): File =
        File(appContext.filesDir, MDBX2_DIRECTORY).canonicalFile

    private fun deleteVaultArtifacts(file: File): Boolean {
        val artifacts = listOf(
            file,
            File("${file.absolutePath}-wal"),
            File("${file.absolutePath}-shm"),
            File("${file.absolutePath}-journal")
        )
        val deletionResults = artifacts.map { artifact ->
            !artifact.exists() || artifact.delete()
        }
        return deletionResults.all { it }
    }

    companion object {
        // All repository instances coordinate with the same retained native readers.
        private val vaultLocks = ConcurrentHashMap<Long, Mutex>()
        private const val PREFERENCES_NAME = "mdbx2_vault_sessions"
        private const val DEVICE_ID_KEY = "device_id"
        private const val MDBX2_DIRECTORY = "mdbx2"
        internal const val ROOT_PROJECT_TITLE = ".monica-root"
        private val SUPPORTED_SOURCE_TYPES = setOf(
            MdbxSourceType.LOCAL_INTERNAL,
            MdbxSourceType.LOCAL_EXTERNAL,
            MdbxSourceType.REMOTE_WEBDAV,
            MdbxSourceType.REMOTE_ONEDRIVE
        )

        internal fun rootProjectId(vaultId: String): String =
            UUID.nameUUIDFromBytes("monica-root:$vaultId".toByteArray(Charsets.UTF_8)).toString()

        internal fun normalizePassword(password: String): String =
            Normalizer.normalize(password, Normalizer.Form.NFC)
    }
}

private fun MdbxUnlockMethod.requiresPassword(): Boolean =
    this == MdbxUnlockMethod.MASTER_PASSWORD ||
        this == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE

private fun MdbxTigaMode.toRustMode(): RustTigaMode = when (this) {
    MdbxTigaMode.SKY -> RustTigaMode.SKY
    MdbxTigaMode.MULTI -> RustTigaMode.MULTI
    MdbxTigaMode.POWER -> RustTigaMode.POWER
    MdbxTigaMode.GLITTER -> RustTigaMode.GLITTER
}
