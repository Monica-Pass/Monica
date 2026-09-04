package takagi.ru.monica.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.data.CustomFieldDao
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.LocalMdbxDatabaseDao
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.capabilities
import takagi.ru.monica.data.isRemoteSource
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordEntryDao
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.SecureItemDao
import takagi.ru.monica.data.resolvedActiveFilePath
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.util.TotpDataResolver
import uniffi.mdbx_ffi.MdbxAttachmentContentLimits
import uniffi.mdbx_ffi.MdbxAttachmentCreateRequest
import uniffi.mdbx_ffi.MdbxConflictChoice
import uniffi.mdbx_ffi.MdbxDeviceAssurance
import uniffi.mdbx_ffi.MdbxDeviceContext
import uniffi.mdbx_ffi.MdbxHealthIssue as NativeMdbxHealthIssue
import uniffi.mdbx_ffi.MdbxHealthIssueSeverity as NativeMdbxHealthIssueSeverity
import uniffi.mdbx_ffi.MdbxHealthRepairApplyResult as NativeMdbxHealthRepairApplyResult
import uniffi.mdbx_ffi.MdbxHealthRepairChoice as NativeMdbxHealthRepairChoice
import uniffi.mdbx_ffi.MdbxHealthRepairDecision as NativeMdbxHealthRepairDecision
import uniffi.mdbx_ffi.MdbxHealthRepairItem as NativeMdbxHealthRepairItem
import uniffi.mdbx_ffi.MdbxHealthRepairItemKind as NativeMdbxHealthRepairItemKind
import uniffi.mdbx_ffi.MdbxHealthRepairPlan as NativeMdbxHealthRepairPlan
import uniffi.mdbx_ffi.MdbxHealthRepairStatus as NativeMdbxHealthRepairStatus
import uniffi.mdbx_ffi.MdbxSnapshotKind
import uniffi.mdbx_ffi.MdbxSnapshotStructureNode
import uniffi.mdbx_ffi.MdbxVault
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.defaultWriteOperationLimits

class Mdbx2Repository(
    context: Context,
    private val databaseDao: LocalMdbxDatabaseDao,
    private val securityManager: SecurityManager,
    private val passwordEntryDao: PasswordEntryDao? = null,
    private val secureItemDao: SecureItemDao? = null,
    private val customFieldDao: CustomFieldDao? = null
) : MdbxRepository {
    private val appContext = context.applicationContext
    private val externalStorage = Mdbx2ExternalStorage(appContext)
    private val sessions = Mdbx2VaultSessionExecutor(
        context = appContext,
        databaseDao = databaseDao,
        securityManager = securityManager,
        externalStorage = externalStorage
    )
    private val attachmentStorage = AttachmentStorage(appContext)
    private val attachmentKeyVault = AttachmentKeyVault(securityManager)

    override suspend fun requiresStrictMutationConsistency(databaseId: Long): Boolean = true

    suspend fun createInitializedVaultFile(
        tigaMode: MdbxTigaMode,
        password: String
    ): File = sessions.createInitializedVaultFile(tigaMode, password)

    internal suspend fun createInitializedVaultFile(
        tigaMode: MdbxTigaMode,
        credential: MdbxVaultCredential
    ): File = sessions.createInitializedVaultFile(tigaMode, credential)

    suspend fun deleteOwnedVaultFile(file: File): Boolean = sessions.deleteOwnedVaultFile(file)

    internal suspend fun createExternalDocument(
        treeUri: Uri,
        displayName: String,
        workingCopy: File
    ): Mdbx2ExternalDocument = sessions.createExternalDocument(treeUri, displayName, workingCopy)

    internal suspend fun deleteCreatedExternalDocument(document: Mdbx2ExternalDocument) {
        sessions.deleteCreatedExternalDocument(document)
    }

    internal suspend fun copyExternalDocumentToOwnedFile(
        sourceUri: Uri,
        sourceTreeUri: Uri? = null
    ): File = sessions.copyExternalDocumentToOwnedFile(sourceUri, sourceTreeUri)

    internal suspend fun inspectVaultFormat(file: File): String? = sessions.inspectVaultFormat(file)

    internal suspend fun validatePasswordVaultFile(file: File, password: String) {
        sessions.validatePasswordVaultFile(file, password)
    }

    internal suspend fun validateVaultFile(file: File, credential: MdbxVaultCredential) {
        sessions.validateVaultFile(file, credential)
    }

    internal suspend fun refreshExternalWorkingCopy(databaseId: Long) {
        sessions.refreshExternalWorkingCopy(databaseId)
    }

    internal suspend fun <T> withVaultForSync(
        databaseId: Long,
        block: suspend (LocalMdbxDatabase, MdbxVault) -> T
    ): T = sessions.withVault(databaseId, block)

    override suspend fun readStoredEntries(databaseId: Long): List<MdbxStoredVaultEntry> =
        sessions.withVault(databaseId) { _, vault ->
            buildList {
                vault.listAllProjects().forEach { project ->
                    vault.listEntries(project.collectionId, null).forEach { entry ->
                        add(entry.toStoredEntry())
                    }
                    vault.listDeletedEntries(project.collectionId, null).forEach { entry ->
                        add(entry.toStoredEntry())
                    }
                }
            }.distinctBy { it.entryId to it.deleted }
        }

    override suspend fun readStoredAttachments(databaseId: Long): List<MdbxStoredAttachment> =
        sessions.withVault(databaseId) { _, vault ->
            val logicalEntryIds = vault.listAllProjects()
                .flatMap { project ->
                    vault.listEntries(project.collectionId, null) +
                        vault.listDeletedEntries(project.collectionId, null)
                }
                .associate { entry -> entry.entryId to entry.logicalEntryId() }
            buildList {
                vault.listAllProjects().forEach { project ->
                    vault.listAttachments(project.collectionId, null)
                        .filterNot { it.deleted }
                        .forEach { attachment ->
                            val plaintext = vault.readAttachmentContent(
                                attachmentId = attachment.attachmentId,
                                maxPlaintextBytes = MAX_ATTACHMENT_BYTES.toULong()
                            )
                            try {
                                val encrypted = attachmentStorage.writeEncrypted(plaintext.inputStream())
                                val encryptedFile = attachmentStorage.absolutePathOf(encrypted.relativePath)
                                var conversionFailure: Throwable? = null
                                try {
                                    val localWrappedCek = attachmentKeyVault.wrap(encrypted.cek)
                                    val portableCek = MdbxAttachmentCekPayload.fromLocalWrappedCek(
                                        wrappedCek = localWrappedCek,
                                        unwrapToBase64 = securityManager::decryptData
                                    )
                                    val blob = encryptedFile.readBytes()
                                    add(
                                        MdbxStoredAttachment(
                                            attachmentId = attachment.attachmentId,
                                            projectId = attachment.projectId,
                                            entryId = attachment.entryId?.let { logicalEntryIds[it] ?: it },
                                            fileName = attachment.fileName,
                                            mimeType = attachment.mediaType ?: DEFAULT_MIME_TYPE,
                                            contentHash = attachment.contentHash,
                                            originalSize = attachment.originalSize.toLong(),
                                            storedSize = blob.size.toLong(),
                                            wrappedCek = portableCek,
                                            createdAtMillis = 0L,
                                            updatedAtMillis = 0L,
                                            deleted = false,
                                            blob = blob
                                        )
                                    )
                                } catch (error: Throwable) {
                                    conversionFailure = error
                                    throw error
                                } finally {
                                    encrypted.cek.fill(0)
                                    deleteTemporaryAttachmentFile(encryptedFile, conversionFailure)
                                }
                            } finally {
                                plaintext.fill(0)
                            }
                        }
                }
            }
        }

    override suspend fun createFolder(
        databaseId: Long,
        name: String,
        parentFolderId: String?
    ): MdbxStoredFolderEntry {
        val title = name.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Folder name cannot be empty")
        return sessions.withMutatingVault(databaseId) { _, vault ->
            val rootId = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
            val parentProjectId = normalizeFolderParentId(parentFolderId, rootId)
            val folderId = UUID.randomUUID().toString()
            vault.executeWriteOperation(
                operationId = UUID.randomUUID().toString(),
                operationKind = "monica-create-folder",
                commands = listOf(
                    MdbxWriteCommand.CreateProjectWithParent(
                        projectId = folderId,
                        title = title,
                        parentProjectId = parentProjectId
                    )
                )
            )
            markPendingUpload(databaseId)
            listFolderEntries(vault, rootId).first { it.folderId == folderId }
        }
    }

    override suspend fun listFolders(databaseId: Long): List<MdbxStoredFolderEntry> =
        sessions.withVault(databaseId) { _, vault ->
            val rootId = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
            listFolderEntries(vault, rootId)
        }

    override suspend fun renameFolder(
        databaseId: Long,
        folderId: String,
        name: String
    ): MdbxStoredFolderEntry {
        val title = name.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Folder name cannot be empty")
        return sessions.withMutatingVault(databaseId) { _, vault ->
            val rootId = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
            requireMutableFolderId(folderId, rootId)
            vault.executeWriteOperation(
                operationId = UUID.randomUUID().toString(),
                operationKind = "monica-rename-folder",
                commands = listOf(MdbxWriteCommand.RenameProject(folderId, title))
            )
            markPendingUpload(databaseId)
            listFolderEntries(vault, rootId).first { it.folderId == folderId }
        }
    }

    override suspend fun moveFolder(
        databaseId: Long,
        folderId: String,
        parentFolderId: String?
    ): MdbxStoredFolderEntry = sessions.withMutatingVault(databaseId) { _, vault ->
        val rootId = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
        requireMutableFolderId(folderId, rootId)
        val parentProjectId = normalizeFolderParentId(parentFolderId, rootId)
        vault.executeWriteOperation(
            operationId = UUID.randomUUID().toString(),
            operationKind = "monica-move-folder",
            commands = listOf(MdbxWriteCommand.MoveProject(folderId, parentProjectId))
        )
        markPendingUpload(databaseId)
        listFolderEntries(vault, rootId).first { it.folderId == folderId }
    }

    override suspend fun deleteFolder(databaseId: Long, folderId: String) {
        sessions.withMutatingVault(databaseId) { _, vault ->
            val rootId = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
            requireMutableFolderId(folderId, rootId)
            vault.executeWriteOperation(
                operationId = UUID.randomUUID().toString(),
                operationKind = "monica-delete-folder",
                commands = listOf(MdbxWriteCommand.DeleteProject(folderId))
            )
            markPendingUpload(databaseId)
        }
    }

    override suspend fun restoreFolder(
        databaseId: Long,
        folderId: String,
        parentFolderId: String?
    ): MdbxStoredFolderEntry = sessions.withMutatingVault(databaseId) { _, vault ->
        val rootId = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
        requireMutableFolderId(folderId, rootId)
        val parentProjectId = normalizeFolderParentId(parentFolderId, rootId)
        vault.executeWriteOperation(
            operationId = UUID.randomUUID().toString(),
            operationKind = "monica-restore-folder",
            commands = listOf(MdbxWriteCommand.RestoreProject(folderId, parentProjectId))
        )
        markPendingUpload(databaseId)
        listFolderEntries(vault, rootId).first { it.folderId == folderId }
    }

    internal suspend fun createMigrationFolders(
        databaseId: Long,
        folders: List<MdbxMigrationFolderPlan>
    ): Map<String, String> = sessions.withMutatingVault(databaseId) { _, vault ->
        val vaultId = vault.info().vaultId
        val mapping = folders.associate { folder ->
            folder.sourceFolderId to UUID.nameUUIDFromBytes(
                "monica-migration-folder:$vaultId:${folder.sourceFolderId}".toByteArray(Charsets.UTF_8)
            ).toString()
        }
        topologicallySortedFolders(folders).chunked(MIGRATION_BATCH_SIZE).forEach { batch ->
            val commands = batch.flatMap { folder ->
                val targetFolderId = mapping.getValue(folder.sourceFolderId)
                val targetParentId = folder.sourceParentFolderId
                    .normalizedMigrationParentId()
                    ?.let(mapping::getValue)
                val existing = vault.getCollectionSummary(targetFolderId)
                buildList {
                    if (existing == null) {
                        add(
                            MdbxWriteCommand.CreateProjectWithParent(
                                projectId = targetFolderId,
                                title = folder.targetDisplayName,
                                parentProjectId = targetParentId
                            )
                        )
                    } else {
                        if (existing.deleted) {
                            add(MdbxWriteCommand.RestoreProject(targetFolderId, targetParentId))
                        } else if (existing.groupId != targetParentId) {
                            add(MdbxWriteCommand.MoveProject(targetFolderId, targetParentId))
                        }
                        if (existing.title != folder.targetDisplayName) {
                            add(MdbxWriteCommand.RenameProject(targetFolderId, folder.targetDisplayName))
                        }
                    }
                }
            }
            if (commands.isNotEmpty()) {
                vault.executeWriteOperation(
                    operationId = UUID.randomUUID().toString(),
                    operationKind = "monica-migration-folders",
                    commands = commands
                )
                markPendingUpload(databaseId)
            }
        }
        mapping
    }

    internal suspend fun importMigrationEntries(
        databaseId: Long,
        entries: List<MdbxMigrationEntryPlan>,
        targetFolderIds: Map<String, String>
    ) {
        entries.chunked(MIGRATION_BATCH_SIZE).forEach { batch ->
            val mutations = batch.map { plan ->
                val targetFolderId = plan.sourceFolderId?.let(targetFolderIds::get)
                val rewritten = MdbxMigrationEntryMapper.rewrite(plan, targetFolderId)
                EntryMutation(
                    databaseId = databaseId,
                    folderId = targetFolderId,
                    entryId = rewritten.entryId,
                    entryType = rewritten.entryType,
                    title = rewritten.title,
                    payloadJson = rewritten.payloadJson,
                    deleted = rewritten.deleted
                )
            }
            upsertMutations(mutations)
        }
    }

    internal suspend fun importMigrationAttachments(
        databaseId: Long,
        attachments: List<MdbxMigrationAttachmentPlan>,
        onImported: (Int, Int) -> Unit = { _, _ -> }
    ) {
        attachments.forEachIndexed { index, plan ->
            val plaintext = readPortableAttachmentPlaintext(plan.attachment)
            try {
                val expectedHash = plan.attachment.contentHash.trim()
                if (expectedHash.isNotEmpty()) {
                    check(sha256Hex(plaintext).equals(expectedHash, ignoreCase = true)) {
                        "Source attachment content hash does not match its metadata"
                    }
                }
                sessions.withMutatingVault(databaseId) { _, vault ->
                    val vaultId = vault.info().vaultId
                    val physicalParentEntryId = mdbx2PhysicalEntryId(vaultId, plan.parentEntryId)
                    val parent = vault.getObjectSummary(physicalParentEntryId)
                        ?: error("MDBX2 migration attachment parent is missing")
                    val attachmentId = mdbx2PhysicalAttachmentId(vaultId, plan.attachment.attachmentId)
                    val existing = vault.getAttachment(attachmentId)
                    if (existing == null) {
                        vault.createAttachmentWithExternalContent(
                            operationId = UUID.randomUUID().toString(),
                            request = MdbxAttachmentCreateRequest(
                                attachmentId = attachmentId,
                                projectId = parent.collectionId,
                                entryId = physicalParentEntryId,
                                fileName = plan.attachment.fileName,
                                mediaType = plan.attachment.mimeType.ifBlank { DEFAULT_MIME_TYPE }
                            ),
                            content = plaintext,
                            limits = attachmentLimits()
                        )
                    } else {
                        if (
                            existing.fileName != plan.attachment.fileName ||
                            existing.mediaType != plan.attachment.mimeType
                        ) {
                            vault.renameAttachment(
                                attachmentId = attachmentId,
                                fileName = plan.attachment.fileName,
                                mediaType = plan.attachment.mimeType.ifBlank { DEFAULT_MIME_TYPE }
                            )
                        }
                        vault.replaceAttachmentExternalContent(
                            operationId = UUID.randomUUID().toString(),
                            attachmentId = attachmentId,
                            content = plaintext,
                            limits = attachmentLimits()
                        )
                    }
                }
            } finally {
                plaintext.fill(0)
            }
            onImported(index + 1, attachments.size)
        }
    }

    internal suspend fun verifyMigration(
        databaseId: Long,
        plan: MdbxMigrationPlan,
        targetFolderIds: Map<String, String>
    ): MdbxMigrationVerification {
        val folderErrors = MdbxMigrationVerifier.folderErrors(
            plan = plan,
            targetFolderIds = targetFolderIds,
            actualFolders = listFolders(databaseId)
        )
        check(folderErrors.isEmpty()) { folderErrors.joinToString() }

        val entryErrors = MdbxMigrationVerifier.entryErrors(
            plan = plan,
            targetFolderIds = targetFolderIds,
            actualEntries = readStoredEntries(databaseId)
        )
        check(entryErrors.isEmpty()) { entryErrors.joinToString() }

        val expectedAttachments = plan.attachments.map { attachmentPlan ->
            val plaintext = readPortableAttachmentPlaintext(attachmentPlan.attachment)
            try {
                AttachmentFingerprint(
                    parentEntryId = attachmentPlan.parentEntryId,
                    fileName = attachmentPlan.attachment.fileName,
                    mimeType = attachmentPlan.attachment.mimeType.ifBlank { DEFAULT_MIME_TYPE },
                    size = plaintext.size.toLong(),
                    sha256 = sha256Hex(plaintext)
                )
            } finally {
                plaintext.fill(0)
            }
        }.sortedWith(attachmentFingerprintComparator)
        val actualAttachments = readStoredAttachments(databaseId).map { attachment ->
            val plaintext = readPortableAttachmentPlaintext(attachment)
            try {
                AttachmentFingerprint(
                    parentEntryId = attachment.entryId ?: attachment.projectId,
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType.ifBlank { DEFAULT_MIME_TYPE },
                    size = plaintext.size.toLong(),
                    sha256 = sha256Hex(plaintext)
                )
            } finally {
                plaintext.fill(0)
                attachment.blob.fill(0)
            }
        }.sortedWith(attachmentFingerprintComparator)
        check(expectedAttachments == actualAttachments) { "Migrated attachment content does not match the source" }

        return MdbxMigrationVerification(
            folderCount = plan.folders.size,
            entryCount = plan.entries.size,
            attachmentCount = plan.attachments.size,
            attachmentBytes = plan.attachmentBytes
        )
    }

    override suspend fun upsertPassword(entry: PasswordEntry) {
        passwordMutation(entry)?.let { upsertMutations(listOf(it)) }
    }

    override suspend fun upsertPasswords(entries: List<PasswordEntry>) {
        upsertMutations(entries.mapNotNull { passwordMutation(it) })
    }

    override suspend fun deletePassword(entry: PasswordEntry) {
        entry.mdbxDatabaseId?.let { deleteEntries(it, listOf(passwordObjectId(entry))) }
    }

    override suspend fun deletePasswords(entries: List<PasswordEntry>) {
        entries.groupBy { it.mdbxDatabaseId }.forEach { (databaseId, values) ->
            if (databaseId != null) deleteEntries(databaseId, values.map(::passwordObjectId))
        }
    }

    override suspend fun upsertSecureItem(item: SecureItem) {
        secureItemMutation(item)?.let { upsertMutations(listOf(it)) }
    }

    override suspend fun upsertSecureItems(items: List<SecureItem>) {
        upsertMutations(items.mapNotNull { secureItemMutation(it) })
    }

    override suspend fun deleteSecureItem(item: SecureItem) {
        item.mdbxDatabaseId?.let { deleteEntries(it, listOf(secureItemObjectId(item))) }
    }

    override suspend fun deleteSecureItems(items: List<SecureItem>) {
        items.groupBy { it.mdbxDatabaseId }.forEach { (databaseId, values) ->
            if (databaseId != null) deleteEntries(databaseId, values.map(::secureItemObjectId))
        }
    }

    override suspend fun upsertPasskey(passkey: PasskeyEntry) {
        passkeyMutation(passkey)?.let { upsertMutations(listOf(it)) }
    }

    override suspend fun upsertPasskeys(passkeys: List<PasskeyEntry>) {
        upsertMutations(passkeys.mapNotNull { passkeyMutation(it) })
    }

    override suspend fun deletePasskey(passkey: PasskeyEntry) {
        passkey.mdbxDatabaseId?.let { deleteEntries(it, listOf(passkeyObjectId(passkey))) }
    }

    override suspend fun deletePasskeys(passkeys: List<PasskeyEntry>) {
        passkeys.groupBy { it.mdbxDatabaseId }.forEach { (databaseId, values) ->
            if (databaseId != null) deleteEntries(databaseId, values.map(::passkeyObjectId))
        }
    }

    override suspend fun listSteamMaFileEntries(databaseId: Long): List<MdbxStoredVaultEntry> =
        readStoredEntries(databaseId).filter { entry ->
            !entry.deleted && entry.entryType.equals(STEAM_MAFILE_ENTRY_TYPE, ignoreCase = true)
        }

    override suspend fun upsertSteamMaFileEntry(
        databaseId: Long,
        entryId: String?,
        title: String,
        maFileJson: String
    ): String {
        val resolvedEntryId = entryId?.takeIf { it.isNotBlank() }
            ?: steamMaFileObjectId(maFileJson)
        val payload = JSONObject()
            .put("kind", "steam_mafile")
            .put("monica_entry_id", resolvedEntryId)
            .put("steamid", steamField(maFileJson, "steamid", "SteamID").orEmpty())
            .put("account_name", steamField(maFileJson, "account_name", "accountName", "AccountName").orEmpty())
            .put("mafile_json", maFileJson)
        upsertMutations(
            listOf(
                EntryMutation(
                    databaseId = databaseId,
                    folderId = null,
                    entryId = resolvedEntryId,
                    entryType = STEAM_MAFILE_ENTRY_TYPE,
                    title = title,
                    payloadJson = payload.toString(),
                    deleted = false
                )
            )
        )
        return resolvedEntryId
    }

    override suspend fun deleteSteamMaFileEntry(databaseId: Long, entryId: String) {
        if (entryId.isNotBlank()) deleteEntries(databaseId, listOf(entryId))
    }

    override suspend fun getVaultDiagnostics(databaseId: Long): MdbxVaultDiagnostics =
        sessions.withVault(databaseId) { database, vault ->
            val file = File(database.resolvedActiveFilePath())
            val native = vault.diagnosticsSummary()
            val health = vault.healthCheck()
            val status = runCatching { MdbxSyncStatus.valueOf(database.lastSyncStatus) }.getOrNull()
            val rootProjectId = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
            val rootProjectCount = if (vault.getCollectionSummary(rootProjectId)?.deleted == false) 1 else 0
            val healthIssues = health.issues.map(::toRepositoryDiagnostic)
            val issueSummary = healthIssues
                .take(MAX_DIAGNOSTIC_ISSUE_PREVIEW)
                .joinToString(separator = "; ") { issue ->
                    "${issue.category}: ${issue.description}"
                }
                .ifBlank { "Rust health check passed" }
            MdbxVaultDiagnostics(
                databaseId = databaseId,
                filePath = file.absolutePath,
                fileExists = file.isFile,
                fileSizeBytes = file.takeIf(File::isFile)?.length() ?: 0L,
                isReadable = true,
                currentDeviceId = vault.info().deviceId,
                formatVersion = "MDBX2",
                releaseLabel = "Rust MDBX2",
                capabilityFlags = database.engineTypeEnum.capabilities
                    .joinToString(separator = ",") { capability ->
                        capability.name.lowercase().replace('_', '-')
                    },
                defaultTigaMode = database.tigaMode,
                integrityOk = health.healthy,
                integrityMessage = issueSummary,
                healthIssues = healthIssues,
                unresolvedConflictCount = native.unresolvedConflictCount.toDiagnosticInt(),
                pendingSyncCount = pendingSyncCount(database, status, vault),
                commitCount = native.commitCount.toDiagnosticInt(),
                tombstoneCount = native.tombstoneCount.toDiagnosticInt(),
                branchCount = native.branchCount.toDiagnosticInt(),
                deviceCount = native.deviceCount.toDiagnosticInt(),
                snapshotCount = native.snapshotCount.toDiagnosticInt(),
                folderCount = (native.projectCount.toDiagnosticInt() - rootProjectCount).coerceAtLeast(0),
                indexedObjectCount = (native.entryCount + native.deletedEntryCount).toDiagnosticInt(),
                entryCount = native.entryCount.toDiagnosticInt(),
                deletedEntryCount = native.deletedEntryCount.toDiagnosticInt(),
                attachmentCount = native.attachmentCount.toDiagnosticInt(),
                externalAttachmentCount = native.externalAttachmentCount.toDiagnosticInt(),
                originalAttachmentBytes = native.originalAttachmentBytes.toDiagnosticLong(),
                storedAttachmentBytes = native.storedAttachmentBytes.toDiagnosticLong(),
                danglingParentCount = healthIssues.count {
                    it.category == "commit-chain" && it.description.contains("parent", ignoreCase = true)
                },
                danglingBranchHeadCount = healthIssues.count {
                    it.category == "commit-chain" && it.description.contains("branch", ignoreCase = true)
                },
                danglingDeviceHeadCount = healthIssues.count {
                    it.category == "stale-heads" && it.severity.requiresAction
                },
                attachmentChunkMismatchCount = healthIssues.count {
                    it.category == "attachment-chunks"
                },
                lastSyncStatus = database.lastSyncStatus,
                lastSyncError = database.lastSyncError
            )
        }

    override suspend fun planHealthRepair(databaseId: Long): MdbxHealthRepairPlan =
        sessions.withVault(databaseId) { _, vault ->
            toRepositoryPlan(vault.planHealthRepair())
        }

    override suspend fun applyHealthRepair(
        databaseId: Long,
        planToken: String,
        operationId: String,
        decisions: List<MdbxHealthRepairDecision>
    ): MdbxHealthRepairApplyResult {
        val nativeDecisions = decisions.map { decision ->
            NativeMdbxHealthRepairDecision(
                repairId = decision.repairId,
                choice = toNativeChoice(decision.choice)
            )
        }
        val nativeResult = if (decisions.any { it.choice == MdbxHealthRepairChoice.CANCEL }) {
            sessions.withVault(databaseId) { _, vault ->
                vault.applyHealthRepair(planToken, operationId, nativeDecisions)
            }
        } else {
            sessions.withMutatingVault(databaseId) { _, vault ->
                vault.applyHealthRepair(planToken, operationId, nativeDecisions)
            }
        }
        return toRepositoryResult(nativeResult)
    }

    override suspend fun getPendingSyncCount(databaseId: Long): Int {
        val database = databaseDao.getDatabaseById(databaseId) ?: return 0
        val status = runCatching { MdbxSyncStatus.valueOf(database.lastSyncStatus) }.getOrNull()
        if (status == MdbxSyncStatus.LOCAL_ONLY || status == MdbxSyncStatus.IN_SYNC) {
            return 0
        }

        return sessions.withVault(databaseId) { _, vault -> pendingSyncCount(database, status, vault) }
    }

    override suspend fun setProjectTags(databaseId: Long, projectId: String, tags: List<String>) {
        val desired = normalizeProjectTags(tags)
        sessions.withMutatingVault(databaseId) { _, vault ->
            val existing = projectTagRecords(vault, projectId)
            val existingByKey = existing.associateBy { it.name.normalizedProjectTagKey() }
            val desiredKeys = desired.map { it.normalizedProjectTagKey() }.toSet()
            val commands = buildList {
                existing
                    .filter { it.name.normalizedProjectTagKey() !in desiredKeys }
                    .forEach { add(MdbxWriteCommand.DeleteObjectLabel(it.labelId)) }

                desired.forEach { tag ->
                    val existingLabel = existingByKey[tag.normalizedProjectTagKey()]
                    if (existingLabel == null) {
                        add(
                            MdbxWriteCommand.CreateObjectLabel(
                                labelId = UUID.randomUUID().toString(),
                                collectionId = projectId,
                                name = tag,
                                payloadJson = projectTagPayload(tag),
                                payloadSchemaVersion = PROJECT_TAG_PAYLOAD_SCHEMA_VERSION
                            )
                        )
                    } else if (existingLabel.name != tag ||
                        !isProjectTagPayload(existingLabel.payloadJson)
                    ) {
                        add(
                            MdbxWriteCommand.UpdateObjectLabel(
                                labelId = existingLabel.labelId,
                                name = tag,
                                payloadJson = projectTagPayload(tag),
                                payloadSchemaVersion = PROJECT_TAG_PAYLOAD_SCHEMA_VERSION
                            )
                        )
                    }
                }
            }
            if (commands.isNotEmpty()) {
                vault.executeWriteOperation(
                    operationId = UUID.randomUUID().toString(),
                    operationKind = "monica-project-tags",
                    commands = commands
                )
                markPendingUpload(databaseId)
            }
        }
    }

    override suspend fun listProjectTags(databaseId: Long, projectId: String): List<String> =
        sessions.withVault(databaseId) { _, vault ->
            projectTagRecords(vault, projectId)
                .map { it.name }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }

    override suspend fun listAllProjectTags(databaseId: Long): List<MdbxProjectTagSummary> =
        sessions.withVault(databaseId) { _, vault ->
            val counts = linkedMapOf<String, Pair<String, Int>>()
            vault.listAllProjects()
                .filterNot { it.title == Mdbx2VaultSessionExecutor.ROOT_PROJECT_TITLE }
                .forEach { project ->
                    projectTagRecords(vault, project.collectionId).forEach { label ->
                        val tag = label.name.trim()
                        if (tag.isBlank()) return@forEach
                        val key = tag.normalizedProjectTagKey()
                        val current = counts[key]
                        counts[key] = (current?.first ?: tag) to ((current?.second ?: 0) + 1)
                    }
                }
            counts.values
                .map { (tag, count) -> MdbxProjectTagSummary(tag, count) }
                .sortedWith(
                    compareByDescending<MdbxProjectTagSummary> { it.projectCount }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.tag }
                )
        }

    override suspend fun searchProjects(
        databaseId: Long,
        query: String,
        requiredTags: List<String>
    ): List<MdbxProjectSearchResult> = sessions.withVault(databaseId) { _, vault ->
        val normalized = query.trim()
        val normalizedRequiredTags = normalizeProjectTags(requiredTags)
            .map { it.normalizedProjectTagKey() }
        vault.listAllProjects()
            .filterNot { it.title == Mdbx2VaultSessionExecutor.ROOT_PROJECT_TITLE }
            .map { project ->
                val tags = projectTagRecords(vault, project.collectionId).map { it.name }
                project to tags
            }
            .filter { (project, tags) ->
                (normalized.isBlank() || project.title.contains(normalized, ignoreCase = true)) &&
                    tags.map { it.normalizedProjectTagKey() }.containsAll(normalizedRequiredTags)
            }
            .map { project ->
                val (summary, tags) = project
                MdbxProjectSearchResult(
                    projectId = summary.collectionId,
                    title = summary.title,
                    parentFolderId = summary.groupId,
                    entryTypes = vault.listEntries(summary.collectionId, null)
                        .map { it.entryType }
                        .distinct(),
                    tags = tags.sortedWith(String.CASE_INSENSITIVE_ORDER),
                    updatedAt = summary.updatedAt
                )
            }
    }

    override suspend fun getCurrentHeadCommitId(databaseId: Long): String? =
        sessions.withVault(databaseId) { _, vault ->
            val branches = vault.listBranches()
            branches.firstOrNull { branch ->
                branch.branchName.equals("main", ignoreCase = true)
            }?.headCommitId ?: branches.maxByOrNull { it.updatedAt }?.headCommitId
        }

    override suspend fun listDeltaHistory(databaseId: Long): List<MdbxDeltaSummary> =
        sessions.withVault(databaseId) { _, vault ->
            buildList {
                var cursor: String? = null
                while (size < MAX_HISTORY_ITEMS) {
                    val pageSize = minOf(HISTORY_PAGE_SIZE, MAX_HISTORY_ITEMS - size).toUInt()
                    val page = vault.listCommitHistory(pageSize, cursor)
                    page.items.forEach { item ->
                        val changes = item.changes.map { change ->
                            MdbxCommitChangeSummary(
                                objectType = change.objectType,
                                objectId = change.objectId,
                                action = change.action,
                                fields = change.fields
                            )
                        }
                        val objectIds = item.changes.map { it.objectId }.distinct()
                        val objectPreview = summarizeHistoryObjects(changes)
                        val fieldSummary = item.changes
                            .flatMap { change ->
                                change.fields.ifEmpty { listOf(change.action) }
                            }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString(separator = ", ")
                            .ifBlank { "metadata" }
                        add(
                            MdbxDeltaSummary(
                                commitId = item.commitId,
                                deviceId = item.deviceId,
                                localSeq = item.localSeq.toLong(),
                                commitKind = item.commitKind,
                                changeScope = item.changeScope,
                                changedObjectIds = JSONArray(objectIds).toString(),
                                changedObjectPreview = objectPreview,
                                changedFieldSummary = fieldSummary,
                                parentCount = item.parentIds.size,
                                createdAt = item.createdAt,
                                operationId = item.operationId,
                                operationKind = item.operationKind,
                                branchName = item.branchName,
                                message = item.message,
                                changes = changes,
                                legacy = item.legacy
                            )
                        )
                    }
                    cursor = page.nextCursor
                    if (cursor == null || page.items.isEmpty()) break
                }
            }
        }

    override suspend fun listCommitDiff(databaseId: Long, commitId: String): List<MdbxCommitDiff> =
        sessions.withVault(databaseId) { _, vault ->
            val rootProjectId = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
            val collectionPaths = collectionDisplayPaths(vault, rootProjectId)
            vault.listCommitDiff(commitId).map { diff ->
                val objectSummary = runCatching { vault.getObjectSummary(diff.objectId) }.getOrNull()
                MdbxCommitDiff(
                    commitId = diff.commitId,
                    objectType = diff.objectType,
                    objectId = diff.objectId,
                    displayTitle = diff.currentTitle ?: diff.previousTitle,
                    storagePath = diff.collectionId?.let(collectionPaths::get),
                    previousTitle = diff.previousTitle,
                    currentTitle = diff.currentTitle,
                    previousPayloadPreview = diff.previousPayloadPreview,
                    currentPayloadPreview = diff.currentPayloadPreview,
                    previousDeleted = diff.previousDeleted,
                    currentDeleted = diff.currentDeleted,
                    changedFields = diff.changedFields,
                    createdAt = diff.createdAt,
                    contentType = objectSummary?.objectTypeId
                )
            }
        }

    override suspend fun revertCommit(databaseId: Long, commitId: String): Int =
        sessions.withMutatingVault(databaseId) { _, vault ->
            vault.revertCommit(
                commitId = commitId,
                operationId = UUID.randomUUID().toString(),
                device = snapshotDeviceContext()
            ).revertedObjectCount.toInt()
        }.also {
            markPendingUpload(databaseId)
        }

    override suspend fun listSnapshots(databaseId: Long): List<MdbxSnapshotSummary> =
        sessions.withVault(databaseId) { _, vault ->
            buildList {
                var cursor: String? = null
                while (size < MAX_SNAPSHOT_ITEMS) {
                    val pageSize = minOf(SNAPSHOT_PAGE_SIZE, MAX_SNAPSHOT_ITEMS - size).toUInt()
                    val page = vault.listManagedSnapshots(pageSize, cursor)
                    page.items.forEach { snapshot -> add(snapshot.toRepositorySummary()) }
                    cursor = page.nextCursor
                    if (cursor == null || page.items.isEmpty()) break
                }
            }
        }

    override suspend fun createSnapshot(
        databaseId: Long,
        name: String,
        fullSnapshot: Boolean,
        autoPrune: Boolean
    ): MdbxSnapshotSummary {
        check(!autoPrune) { "Automatic MDBX2 snapshot creation is managed by retention policy" }
        return sessions.withMutatingVault(databaseId) { _, vault ->
            // MDBX2 snapshots always capture the complete authenticated vault state.
            vault.createManualSnapshot(name, snapshotDeviceContext()).toRepositorySummary()
        }.also {
            markPendingUpload(databaseId)
        }
    }

    override suspend fun deleteSnapshot(databaseId: Long, snapshotId: String) {
        sessions.withMutatingVault(databaseId) { _, vault ->
            vault.deleteSnapshot(snapshotId, snapshotDeviceContext())
        }
        markPendingUpload(databaseId)
    }

    override suspend fun revertToSnapshot(databaseId: Long, snapshotId: String): Int =
        sessions.withMutatingVault(databaseId) { _, vault ->
            vault.restoreSnapshot(snapshotId, snapshotDeviceContext()).affectedObjectCount.toInt()
        }.also {
            markPendingUpload(databaseId)
        }

    override suspend fun pruneAutomaticSnapshots(
        databaseId: Long,
        keepCount: Int?,
        maxBytes: Long?
    ): Int = sessions.withMutatingVault(databaseId) { _, vault ->
        val keepLatest = (keepCount ?: 0).coerceAtLeast(0).toUInt()
        val plan = vault.planAutomaticSnapshotPrune(keepLatest)
        if (plan.candidates.isEmpty()) {
            0
        } else {
            vault.pruneAutomaticSnapshots(
                planToken = plan.planToken,
                keepLatest = keepLatest,
                device = snapshotDeviceContext()
            ).deletedSnapshotIds.size
        }
    }.also { deletedCount ->
        if (deletedCount > 0) markPendingUpload(databaseId)
    }

    override suspend fun getSnapshotStructurePreview(
        databaseId: Long,
        snapshotId: String
    ): MdbxStructurePreview = sessions.withVault(databaseId) { _, vault ->
        val preview = vault.getSnapshotStructurePreview(snapshotId)
        val snapshots = vault.listManagedSnapshots(MAX_SNAPSHOT_ITEMS.toUInt(), null).items
        val snapshotName = snapshots.firstOrNull { it.snapshotId == snapshotId }?.name
            ?: snapshotId.take(SHORT_ID_LENGTH)
        val rootIds = (preview.currentNodes + preview.snapshotNodes)
            .filter { node ->
                node.nodeType.equals("folder", ignoreCase = true) &&
                    node.name == Mdbx2VaultSessionExecutor.ROOT_PROJECT_TITLE
            }
            .mapTo(mutableSetOf()) { it.id }
        MdbxStructurePreview(
            snapshotId = preview.snapshotId,
            snapshotName = snapshotName,
            currentNodes = preview.currentNodes.mapNotNull { it.toRepositoryNode(rootIds) },
            snapshotNodes = preview.snapshotNodes.mapNotNull { it.toRepositoryNode(rootIds) },
            currentItemCount = preview.currentItemCount.toInt(),
            snapshotItemCount = preview.snapshotItemCount.toInt()
        )
    }

    override suspend fun exportSyncBundle(databaseId: Long, baseCommitId: String?): MdbxSyncBundle {
        require(baseCommitId.isNullOrBlank()) {
            "MDBX2 manual sync exports a complete authenticated bundle; incremental base commits are not accepted"
        }
        return sessions.withVault(databaseId) { _, vault ->
            val temporary = newManualSyncBundleFile()
            try {
                val info = vault.exportManualSyncBundle(temporary.absolutePath)
                require(info.fileSizeBytes <= MAX_MANUAL_SYNC_BUNDLE_BYTES.toULong()) {
                    "MDBX2 manual sync bundle exceeds the Android text-transfer limit"
                }
                val payload = temporary.inputStream().buffered().use { input ->
                    input.readBytesBounded(MAX_MANUAL_SYNC_BUNDLE_BYTES)
                }
                try {
                    val payloadHash = sha256Hex(payload)
                    check(payloadHash.equals(info.payloadSha256.toHex(), ignoreCase = true)) {
                        "MDBX2 manual sync bundle digest changed after export"
                    }
                    MdbxSyncBundle(
                        bundleId = payloadHash,
                        baseCommitId = null,
                        headCommitId = info.headCommitId,
                        commitCount = info.commitCount.toInt(),
                        payloadJson = JSONObject()
                            .put("format", MDBX2_MANUAL_SYNC_PAYLOAD_FORMAT)
                            .put("vault_id", info.vaultId)
                            .put("source_device_id", info.sourceDeviceId)
                            .put("encoding", "base64")
                            .put("data", Base64.encodeToString(payload, Base64.NO_WRAP))
                            .toString(),
                        payloadHash = payloadHash,
                        createdAt = info.exportedAt
                    )
                } finally {
                    payload.fill(0)
                }
            } finally {
                temporary.delete()
            }
        }
    }

    override suspend fun importSyncBundle(
        databaseId: Long,
        bundle: MdbxSyncBundle
    ): MdbxApplyResult {
        val envelope = JSONObject(bundle.payloadJson)
        require(envelope.optString("format") == MDBX2_MANUAL_SYNC_PAYLOAD_FORMAT) {
            "Unsupported MDBX2 manual sync payload"
        }
        require(envelope.optString("encoding") == "base64") {
            "Unsupported MDBX2 manual sync encoding"
        }
        val encoded = envelope.getString("data")
        require(encoded.length <= MAX_MANUAL_SYNC_BASE64_CHARACTERS) {
            "MDBX2 manual sync payload exceeds the Android text-transfer limit"
        }
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        try {
            require(payload.size.toLong() <= MAX_MANUAL_SYNC_BUNDLE_BYTES) {
                "MDBX2 manual sync payload exceeds the Android text-transfer limit"
            }
            val payloadHash = sha256Hex(payload)
            require(payloadHash.equals(bundle.payloadHash, ignoreCase = true)) {
                "MDBX2 manual sync payload hash mismatch"
            }
            return sessions.withMutatingVault(databaseId) { _, vault ->
                val temporary = newManualSyncBundleFile()
                try {
                    temporary.outputStream().buffered().use { output -> output.write(payload) }
                    val result = vault.applyManualSyncBundle(temporary.absolutePath)
                    check(result.bundle.payloadSha256.toHex().equals(payloadHash, ignoreCase = true)) {
                        "MDBX2 authenticated bundle digest does not match its transfer envelope"
                    }
                    if (result.appliedCommits > 0u || result.conflictCount > 0u) {
                        markPendingUpload(databaseId)
                    }
                    MdbxApplyResult(
                        appliedObjectCount = result.appliedCommits.toInt(),
                        keptLocalObjectCount = result.skippedCommits.toInt(),
                        conflictCount = result.conflictCount.toInt(),
                        tombstoneCount = 0
                    )
                } finally {
                    temporary.delete()
                }
            }
        } finally {
            payload.fill(0)
        }
    }

    internal suspend fun runBenchmark(
        databaseId: Long,
        operationCount: Int
    ): MdbxBenchmarkResult = sessions.withMutatingVault(databaseId) { database, vault ->
        val count = operationCount.coerceIn(1, MAX_METADATA_BENCHMARK_OPERATIONS)
        val activeFile = File(database.resolvedActiveFilePath())
        val beforeBytes = vaultArtifactBytes(activeFile)
        val nativeResult = vault.runMetadataBenchmark(count.toUInt())
        markPendingUpload(databaseId)
        val afterBytes = vaultArtifactBytes(activeFile)
        MdbxBenchmarkResult(
            runId = UUID.randomUUID().toString(),
            scenario = "rust-metadata-commit",
            operationCount = nativeResult.operationCount.toInt(),
            elapsedMs = nativeResult.elapsedMs.toLong(),
            fileDeltaBytes = afterBytes - beforeBytes,
            createdAt = Instant.now().toString()
        )
    }

    override suspend fun flushPendingWorkingCopy(databaseId: Long) {
        sessions.flushExternalWorkingCopy(databaseId, onlyIfPending = true)
    }

    override suspend fun flushWorkingCopy(databaseId: Long) {
        sessions.flushExternalWorkingCopy(databaseId, onlyIfPending = false)
    }
    override suspend fun listConflicts(databaseId: Long): List<MdbxConflictSummary> =
        sessions.withVault(databaseId) { _, vault ->
            buildList {
                var cursor: String? = null
                while (size < MAX_CONFLICT_ITEMS) {
                    val pageSize = minOf(MAX_CONFLICT_PAGE_SIZE, MAX_CONFLICT_ITEMS - size).toUInt()
                    val page = vault.listUnresolvedConflictSummaries(
                        objectType = null,
                        pageSize = pageSize,
                        cursor = cursor
                    )
                    page.items.forEach { conflict ->
                        add(
                            MdbxConflictSummary(
                                conflictId = conflict.conflictId,
                                objectType = conflict.objectType,
                                objectId = conflict.objectId,
                                baseCommitId = conflict.baseCommitId,
                                localCommitId = conflict.localCommitId,
                                incomingCommitId = conflict.incomingCommitId,
                                conflictingFields = JSONArray(conflict.conflictingFields).toString(),
                                createdAt = conflict.createdAt
                            )
                        )
                    }
                    cursor = page.nextCursor
                    if (cursor == null || page.items.isEmpty()) break
                }
            }
        }

    override suspend fun resolveConflict(
        databaseId: Long,
        conflictId: String,
        resolution: MdbxConflictResolution
    ) {
        val choice = when (resolution) {
            MdbxConflictResolution.LOCAL_WINS,
            MdbxConflictResolution.MARK_RESOLVED -> MdbxConflictChoice.LOCAL_WINS
            MdbxConflictResolution.INCOMING_WINS -> MdbxConflictChoice.INCOMING_WINS
            MdbxConflictResolution.CUSTOM_MERGE -> {
                error("Custom MDBX2 conflict merge requires an explicit merged payload")
            }
        }
        sessions.withMutatingVault(databaseId) { _, vault ->
            vault.resolveConflict(conflictId, choice)
        }
        markPendingUpload(databaseId)
    }

    override suspend fun upsertAttachment(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment
    ): Unit = withContext(Dispatchers.IO) {
        require(attachment.sizeBytes in 0..MAX_ATTACHMENT_BYTES) {
            "MDBX2 attachment exceeds ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MiB"
        }
        val localPath = attachment.localPath?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Attachment has no local content")
        val wrappedCek = attachment.wrappedCek?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Attachment has no local content key")
        val cek = attachmentKeyVault.unwrap(wrappedCek)
        val plaintext = try {
            attachmentStorage.openDecryptedStream(localPath, cek).use { stream ->
                stream.readBytesBounded(MAX_ATTACHMENT_BYTES)
            }
        } finally {
            cek.fill(0)
        }
        sessions.withMutatingVault(databaseId) { _, vault ->
            val vaultId = vault.info().vaultId
            val physicalParentEntryId = mdbx2PhysicalEntryId(vaultId, parentEntryId)
            val parent = vault.getObjectSummary(physicalParentEntryId)
                ?: error("MDBX2 parent entry not found: $parentEntryId")
            val logicalAttachmentId = attachmentObjectId(parentEntryId, attachment)
            val attachmentId = mdbx2PhysicalAttachmentId(vaultId, logicalAttachmentId)
            val existing = vault.getAttachment(attachmentId)
            if (existing == null) {
                vault.createAttachmentWithExternalContent(
                    operationId = UUID.randomUUID().toString(),
                    request = MdbxAttachmentCreateRequest(
                        attachmentId = attachmentId,
                        projectId = parent.collectionId,
                        entryId = physicalParentEntryId,
                        fileName = attachment.fileName,
                        mediaType = attachment.mimeType.ifBlank { DEFAULT_MIME_TYPE }
                    ),
                    content = plaintext,
                    limits = attachmentLimits()
                )
            } else {
                if (existing.fileName != attachment.fileName || existing.mediaType != attachment.mimeType) {
                    vault.renameAttachment(
                        attachmentId = attachmentId,
                        fileName = attachment.fileName,
                        mediaType = attachment.mimeType.ifBlank { DEFAULT_MIME_TYPE }
                    )
                }
                vault.replaceAttachmentExternalContent(
                    operationId = UUID.randomUUID().toString(),
                    attachmentId = attachmentId,
                    content = plaintext,
                    limits = attachmentLimits()
                )
            }
        }
        markPendingUpload(databaseId)
        Unit
    }

    override suspend fun upsertExternalAttachmentRef(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment,
        externalUri: String
    ) {
        upsertAttachment(databaseId, parentEntryId, attachment)
    }

    override suspend fun deleteAttachment(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment
    ) {
        sessions.withMutatingVault(databaseId) { _, vault ->
            val logicalAttachmentId = attachmentObjectId(parentEntryId, attachment)
            val attachmentId = mdbx2PhysicalAttachmentId(vault.info().vaultId, logicalAttachmentId)
            if (vault.getAttachment(attachmentId) != null) {
                vault.deleteAttachment(attachmentId)
            }
        }
        markPendingUpload(databaseId)
    }

    private suspend fun upsertMutations(mutations: List<EntryMutation>) {
        mutations.groupBy { it.databaseId }.forEach { (databaseId, grouped) ->
            sessions.withMutatingVault(databaseId) { _, vault ->
                val vaultId = vault.info().vaultId
                val rootProjectId = Mdbx2VaultSessionExecutor.rootProjectId(vaultId)
                val mutationsWithPhysicalIds = grouped.map { mutation ->
                    mutation to mdbx2PhysicalEntryId(vaultId, mutation.entryId)
                }
                val snapshot = vault.loadMdbx2EntryMutationSnapshot(
                    requestedObjectIds = mutationsWithPhysicalIds.mapTo(linkedSetOf()) { it.second },
                    preferredCollectionIds = grouped.mapNotNullTo(linkedSetOf()) { mutation ->
                        mutation.folderId?.takeIf(String::isNotBlank)
                    },
                    rootCollectionId = rootProjectId
                )
                val commandGroups = mutationsWithPhysicalIds.map { (mutation, physicalEntryId) ->
                    val desiredProjectId = mutation.folderId
                        ?.takeIf { it.isNotBlank() && it in snapshot.activeCollectionIds }
                        ?: rootProjectId
                    val current = snapshot.objectsById[physicalEntryId]
                    buildList {
                        if (current == null) {
                            add(
                                MdbxWriteCommand.CreateEntry(
                                    entryId = physicalEntryId,
                                    projectId = desiredProjectId,
                                    entryType = mutation.entryType,
                                    title = mutation.title,
                                    payloadJson = mutation.payloadJson
                                )
                            )
                        } else {
                            if (current.deleted) {
                                add(MdbxWriteCommand.RestoreEntry(physicalEntryId, current.collectionId))
                            }
                            if (current.collectionId != desiredProjectId) {
                                add(
                                    MdbxWriteCommand.MoveEntry(
                                        entryId = physicalEntryId,
                                        projectId = current.collectionId,
                                        targetProjectId = desiredProjectId
                                    )
                                )
                            }
                            add(
                                MdbxWriteCommand.UpdateEntry(
                                    entryId = physicalEntryId,
                                    projectId = desiredProjectId,
                                    entryType = mutation.entryType,
                                    title = mutation.title,
                                    payloadJson = mutation.payloadJson
                                )
                            )
                        }
                        if (mutation.deleted) {
                            add(MdbxWriteCommand.DeleteEntry(physicalEntryId, desiredProjectId))
                        }
                    }
                }
                executeEntryCommandGroups(
                    databaseId = databaseId,
                    vault = vault,
                    operationKind = "monica-upsert-entries",
                    commandGroups = commandGroups
                )
            }
        }
    }

    private suspend fun deleteEntries(databaseId: Long, entryIds: List<String>) {
        sessions.withMutatingVault(databaseId) { _, vault ->
            val vaultId = vault.info().vaultId
            val physicalEntryIds = entryIds.distinct().associateWith { entryId ->
                mdbx2PhysicalEntryId(vaultId, entryId)
            }
            val snapshot = vault.loadMdbx2EntryMutationSnapshot(
                requestedObjectIds = physicalEntryIds.values.toSet(),
                preferredCollectionIds = emptySet(),
                rootCollectionId = Mdbx2VaultSessionExecutor.rootProjectId(vaultId)
            )
            val commandGroups = physicalEntryIds.values.mapNotNull { physicalEntryId ->
                snapshot.objectsById[physicalEntryId]
                    ?.takeUnless { it.deleted }
                    ?.let { summary ->
                        listOf(MdbxWriteCommand.DeleteEntry(physicalEntryId, summary.collectionId))
                    }
            }
            executeEntryCommandGroups(
                databaseId = databaseId,
                vault = vault,
                operationKind = "monica-delete-entries",
                commandGroups = commandGroups
            )
        }
    }

    private suspend fun executeEntryCommandGroups(
        databaseId: Long,
        vault: MdbxVault,
        operationKind: String,
        commandGroups: List<List<MdbxWriteCommand>>
    ) {
        val batches = planMdbx2WriteBatches(
            commandGroups = commandGroups,
            baseOperationId = UUID.randomUUID().toString(),
            defaultLimits = defaultWriteOperationLimits()
        )
        batches.forEach { batch ->
            vault.executeWriteOperationWithLimits(
                operationId = batch.operationId,
                operationKind = operationKind,
                commands = batch.commands,
                limits = batch.limits
            )
            markPendingUpload(databaseId)
        }
    }

    private suspend fun markPendingUpload(databaseId: Long) {
        val database = databaseDao.getDatabaseById(databaseId) ?: return
        if (database.isRemoteSource()) {
            databaseDao.updateSyncStatus(databaseId, MdbxSyncStatus.PENDING_UPLOAD.name, null)
        }
    }

    private fun pendingSyncCount(
        database: LocalMdbxDatabase,
        status: MdbxSyncStatus?,
        vault: MdbxVault
    ): Int {
        if (status == MdbxSyncStatus.LOCAL_ONLY || status == MdbxSyncStatus.IN_SYNC) return 0
        if (status == MdbxSyncStatus.CONFLICT) {
            return vault.diagnosticsSummary().unresolvedConflictCount
                .toDiagnosticInt()
                .coerceAtLeast(1)
        }

        val currentDeviceId = vault.info().deviceId
        val lastSyncedAt = database.lastSyncedAt
        var cursor: String? = null
        var count = 0
        var scanned = 0
        while (scanned < MAX_PENDING_SYNC_ITEMS) {
            val pageSize = minOf(
                PENDING_SYNC_PAGE_SIZE,
                MAX_PENDING_SYNC_ITEMS - scanned
            ).toUInt()
            val page = vault.listCommitHistory(pageSize, cursor)
            scanned += page.items.size
            page.items.forEach { item ->
                if (item.deviceId != currentDeviceId) return@forEach
                val createdAtMillis = runCatching {
                    Instant.parse(item.createdAt).toEpochMilli()
                }.getOrNull()
                if (lastSyncedAt == null || createdAtMillis == null || createdAtMillis > lastSyncedAt) {
                    count += 1
                }
            }
            cursor = page.nextCursor
            if (cursor == null || page.items.isEmpty()) break
        }
        return count.coerceIn(1, MAX_PENDING_SYNC_ITEMS)
    }

    private suspend fun passwordMutation(entry: PasswordEntry): EntryMutation? {
        val databaseId = entry.mdbxDatabaseId ?: return null
        val entryId = passwordObjectId(entry)
        val payload = JSONObject()
            .put("kind", "password")
            .put("monica_entry_id", entryId)
            .put("room_id", entry.id)
            .put("website", entry.website)
            .put("username", entry.username)
            .put("app_package_name", entry.appPackageName)
            .put("app_name", entry.appName)
            .put("password_plain", decryptSensitiveValue(entry.password, "password", entry.id))
            .put("notes", entry.notes)
            .put("sort_order", entry.sortOrder)
            .put("category_id", entry.categoryId)
            .put("mdbx_folder_id", entry.mdbxFolderId)
            .put("bound_note_room_id", entry.boundNoteId)
            .put("bound_note_entry_id", resolveBoundNoteEntryId(entry))
            .put("login_type", entry.loginType)
            .put("authenticator_key", decryptSensitiveValue(entry.authenticatorKey, "authenticator_key", entry.id))
            .put("passkey_bindings", entry.passkeyBindings)
            .put("custom_fields", passwordCustomFieldsPayload(entry.id))
            .put("bitwarden_mode", entry.bitwardenVaultId != null)
            .put("keepass_mode", entry.keepassDatabaseId != null)
        return EntryMutation(
            databaseId = databaseId,
            folderId = entry.mdbxFolderId,
            entryId = entryId,
            entryType = "login",
            title = entry.title,
            payloadJson = payload.toString(),
            deleted = entry.isDeleted
        )
    }

    private suspend fun secureItemMutation(item: SecureItem): EntryMutation? {
        val databaseId = item.mdbxDatabaseId ?: return null
        val prefix = secureItemPrefix(item)
        val entryId = secureItemObjectId(item)
        val payload = JSONObject()
            .put("kind", item.itemType.name.lowercase())
            .put("monica_entry_id", entryId)
            .put("room_id", item.id)
            .put("notes", item.notes)
            .put("sort_order", item.sortOrder)
            .put("item_data", decryptSensitiveValue(item.itemData, "item_data", item.id))
            .put("image_paths", item.imagePaths)
            .put("category_id", item.categoryId)
            .put("mdbx_folder_id", item.mdbxFolderId)
            .put("bound_password_entry_id", resolveBoundPasswordEntryId(item))
            .put("bitwarden_mode", item.bitwardenVaultId != null)
            .put("keepass_mode", item.keepassDatabaseId != null)
        return EntryMutation(
            databaseId = databaseId,
            folderId = item.mdbxFolderId,
            entryId = entryId,
            entryType = prefix,
            title = item.title,
            payloadJson = payload.toString(),
            deleted = item.isDeleted
        )
    }

    private fun passkeyMutation(passkey: PasskeyEntry): EntryMutation? {
        val databaseId = passkey.mdbxDatabaseId ?: return null
        val entryId = passkeyObjectId(passkey)
        val payload = JSONObject()
            .put("kind", "passkey")
            .put("monica_entry_id", entryId)
            .put("room_id", passkey.id)
            .put("credential_id", passkey.credentialId)
            .put("rp_id", passkey.rpId)
            .put("rp_name", passkey.rpName)
            .put("user_id", passkey.userId)
            .put("user_name", passkey.userName)
            .put("user_display_name", passkey.userDisplayName)
            .put("public_key_algorithm", passkey.publicKeyAlgorithm)
            .put("public_key", passkey.publicKey)
            .put("private_key_alias", PasskeyPrivateKeyStore.resolve(appContext, passkey.privateKeyAlias).orEmpty())
            .put("transports", passkey.transports)
            .put("aaguid", passkey.aaguid)
            .put("sign_count", passkey.signCount)
            .put("notes", passkey.notes)
            .put("passkey_mode", passkey.passkeyMode)
            .put("mdbx_folder_id", passkey.mdbxFolderId)
            .put("bitwarden_compatible", passkey.isBitwardenCompatible())
            .put("keepass_compatible", passkey.isKeePassCompatible())
        return EntryMutation(
            databaseId = databaseId,
            folderId = passkey.mdbxFolderId,
            entryId = entryId,
            entryType = "passkey",
            title = passkey.rpName.ifBlank { passkey.rpId },
            payloadJson = payload.toString(),
            deleted = false
        )
    }

    private suspend fun passwordCustomFieldsPayload(entryId: Long): JSONArray =
        JSONArray().also { array ->
            customFieldDao?.getFieldsByEntryIdSync(entryId).orEmpty()
                .filter { it.title.isNotBlank() }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .forEach { field ->
                    array.put(
                        JSONObject()
                            .put("title", field.title)
                            .put("value", field.value)
                            .put("is_protected", field.isProtected)
                            .put("sort_order", field.sortOrder)
                    )
                }
        }

    private fun decryptSensitiveValue(value: String, fieldName: String, roomId: Long): String {
        if (value.isBlank() || !securityManager.looksLikeMonicaCiphertext(value)) return value
        return runCatching { securityManager.decryptData(value) }.getOrElse { error ->
            throw IllegalStateException(
                "Cannot write encrypted $fieldName for Room item $roomId into MDBX2",
                error
            )
        }
    }

    private suspend fun resolveBoundNoteEntryId(entry: PasswordEntry): String? {
        val note = entry.boundNoteId?.let { secureItemDao?.getItemById(it) } ?: return null
        return note.takeIf { it.itemType == ItemType.NOTE }?.let(::secureItemObjectId)
    }

    private suspend fun resolveBoundPasswordEntryId(item: SecureItem): String? {
        if (item.itemType != ItemType.TOTP) return null
        val data = TotpDataResolver.parseStoredItemData(
            itemData = item.itemData,
            fallbackIssuer = item.title,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        ) ?: return null
        return data.boundPasswordId
            ?.let { passwordEntryDao?.getPasswordEntryById(it) }
            ?.let(::passwordObjectId)
    }

    private fun passwordObjectId(entry: PasswordEntry): String =
        mdbxPasswordObjectId(entry)

    private fun secureItemObjectId(item: SecureItem): String {
        val prefix = secureItemPrefix(item)
        return item.replicaGroupId?.takeIf { it.startsWith("$prefix:") } ?: "$prefix:${item.id}"
    }

    private fun secureItemPrefix(item: SecureItem): String = when (item.itemType) {
        ItemType.NOTE -> "note"
        ItemType.TOTP -> "totp"
        ItemType.BANK_CARD -> "card"
        ItemType.DOCUMENT -> "document-ref"
        ItemType.BILLING_ADDRESS -> "billing-address"
        ItemType.PAYMENT_ACCOUNT -> "payment-account"
        ItemType.PASSWORD -> "password"
    }

    private fun passkeyObjectId(passkey: PasskeyEntry): String =
        "passkey:${passkey.credentialId.ifBlank { passkey.id.toString() }}"

    private fun attachmentObjectId(parentEntryId: String, attachment: Attachment): String {
        val stableValue = listOf(
            parentEntryId,
            attachment.fileName,
            attachment.sha256Hex ?: attachment.localPath ?: attachment.id.toString(),
            attachment.createdAt.takeIf { it > 0L } ?: attachment.id
        ).joinToString("|")
        return "attachment:${sha256Hex(stableValue.toByteArray(Charsets.UTF_8)).take(32)}"
    }

    private fun steamMaFileObjectId(maFileJson: String): String {
        val steamId = steamField(maFileJson, "steamid", "SteamID")
        val account = steamField(maFileJson, "account_name", "accountName", "AccountName")
        val stable = steamId ?: account ?: sha256Hex(maFileJson.toByteArray(Charsets.UTF_8)).take(24)
        return "steam-mafile:$stable"
    }

    private fun steamField(json: String, vararg names: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return names.asSequence().map { root.optString(it).trim() }.firstOrNull { it.isNotBlank() }
    }

    private fun attachmentLimits(): MdbxAttachmentContentLimits = MdbxAttachmentContentLimits(
        chunkSize = ATTACHMENT_CHUNK_BYTES.toULong(),
        maxPlaintextBytes = MAX_ATTACHMENT_BYTES.toULong()
    )

    private fun java.io.InputStream.readBytesBounded(maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(ATTACHMENT_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count <= 0) break
            total += count
            require(total <= maxBytes) { "MDBX2 attachment exceeds the supported size" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun newManualSyncBundleFile(): File {
        val directory = File(appContext.cacheDir, "mdbx2-manual-sync").also { target ->
            check(target.exists() || target.mkdirs()) {
                "Cannot create the MDBX2 manual sync cache directory"
            }
        }
        return File(directory, "${UUID.randomUUID()}.mdbx-sync")
    }

    private fun vaultArtifactBytes(file: File): Long =
        listOf(file, File("${file.absolutePath}-wal"))
            .sumOf { artifact -> artifact.takeIf(File::isFile)?.length() ?: 0L }

    private suspend fun readPortableAttachmentPlaintext(attachment: MdbxStoredAttachment): ByteArray {
        val storedCek = attachment.wrappedCek?.takeIf(String::isNotBlank)
            ?: error("MDBX migration attachment key is missing")
        val relativePath = "mdbx-migration-${UUID.randomUUID()}.enc"
        val encryptedFile = attachmentStorage.absolutePathOf(relativePath)
        encryptedFile.parentFile?.mkdirs()
        encryptedFile.writeBytes(attachment.blob)
        var cek: ByteArray? = null
        var conversionFailure: Throwable? = null
        return try {
            val localWrappedCek = MdbxAttachmentCekPayload.toLocalWrappedCek(
                storedValue = storedCek,
                wrapBase64 = securityManager::encryptData
            )
            cek = attachmentKeyVault.unwrap(localWrappedCek)
            attachmentStorage.openDecryptedStream(relativePath, cek).use { stream ->
                stream.readBytesBounded(MAX_ATTACHMENT_BYTES)
            }
        } catch (error: Throwable) {
            conversionFailure = error
            throw error
        } finally {
            cek?.fill(0)
            deleteTemporaryAttachmentFile(encryptedFile, conversionFailure)
        }
    }

    private fun deleteTemporaryAttachmentFile(file: File, originalFailure: Throwable?) {
        if (!file.exists() || file.delete()) return
        val cleanupFailure = IllegalStateException("Unable to remove a temporary attachment file")
        if (originalFailure == null) throw cleanupFailure
        originalFailure.addSuppressed(cleanupFailure)
    }

    private fun uniffi.mdbx_ffi.EntryRecord.logicalEntryId(): String =
        runCatching { JSONObject(payloadJson).optString("monica_entry_id").trim() }
            .getOrDefault("")
            .ifBlank { entryId }

    private fun uniffi.mdbx_ffi.EntryRecord.toStoredEntry(): MdbxStoredVaultEntry =
        MdbxStoredVaultEntry(logicalEntryId(), entryType, title, payloadJson, deleted)

    private fun MdbxVault.listAllProjects(): List<uniffi.mdbx_ffi.MdbxCollectionSummary> {
        val projects = mutableListOf<uniffi.mdbx_ffi.MdbxCollectionSummary>()
        var cursor: String? = null
        do {
            val page = listCollectionSummaries(COLLECTION_PAGE_SIZE, cursor)
            projects += page.items.filterNot { it.deleted }
            cursor = page.nextCursor
        } while (cursor != null)
        return projects
    }

    private fun projectTagRecords(
        vault: MdbxVault,
        projectId: String
    ): List<uniffi.mdbx_ffi.MdbxObjectLabelRecord> =
        vault.listObjectLabels(projectId)
            .filter { !it.deleted && isProjectTagPayload(it.payloadJson) }

    private fun isProjectTagPayload(payloadJson: String): Boolean =
        runCatching {
            JSONObject(payloadJson).optString("kind") == PROJECT_TAG_PAYLOAD_KIND
        }.getOrDefault(false)

    private fun projectTagPayload(tag: String): String =
        JSONObject()
            .put("kind", PROJECT_TAG_PAYLOAD_KIND)
            .put("tag", tag)
            .toString()

    private fun normalizeProjectTags(tags: List<String>): List<String> =
        tags.asSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter(String::isNotBlank)
            .distinctBy { it.normalizedProjectTagKey() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .take(MAX_PROJECT_TAGS)
            .toList()

    private fun String.normalizedProjectTagKey(): String = lowercase().trim()

    private fun listFolderEntries(
        vault: MdbxVault,
        rootProjectId: String
    ): List<MdbxStoredFolderEntry> {
        val summaries = vault.listAllProjects()
            .filterNot { it.collectionId == rootProjectId }
        val byId = summaries.associateBy { it.collectionId }
        val parentById = summaries.associate { summary ->
            val parentId = summary.groupId
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != rootProjectId && it in byId }
            summary.collectionId to parentId
        }
        val pathById = mutableMapOf<String, String>()

        fun pathFor(folderId: String, visiting: MutableSet<String> = linkedSetOf()): String {
            pathById[folderId]?.let { return it }
            if (!visiting.add(folderId)) return "/$folderId"
            val parentPath = parentById[folderId]
                ?.let { parentId -> pathFor(parentId, visiting) }
                ?.takeUnless { it == "/" }
            visiting.remove(folderId)
            return (parentPath?.let { "$it/$folderId" } ?: "/$folderId")
                .also { pathById[folderId] = it }
        }

        return summaries.map { summary ->
            MdbxStoredFolderEntry(
                folderId = summary.collectionId,
                parentFolderId = parentById[summary.collectionId],
                name = summary.title,
                pathKey = pathFor(summary.collectionId),
                objectClock = summary.updatedAt.hashCode().toLong()
            )
        }.sortedWith(compareBy({ it.pathKey }, { it.name.lowercase() }, { it.folderId }))
    }

    private fun collectionDisplayPaths(
        vault: MdbxVault,
        rootProjectId: String
    ): Map<String, String> {
        val summaries = vault.listAllProjects()
        return buildMdbx2CollectionDisplayPaths(
            rootCollectionId = rootProjectId,
            rootDisplayName = ROOT_COLLECTION_DISPLAY_NAME,
            nodes = summaries.map { summary ->
                Mdbx2CollectionPathNode(
                    collectionId = summary.collectionId,
                    parentCollectionId = summary.groupId,
                    title = summary.title
                )
            }
        )
    }

    private fun summarizeHistoryObjects(changes: List<MdbxCommitChangeSummary>): String {
        if (changes.isEmpty()) return ""
        val distinctObjects = changes.distinctBy { it.objectType to it.objectId }
        val objectTypes = distinctObjects.map { it.objectType.lowercase() }.distinct()
        val label = when (objectTypes.singleOrNull()) {
            "entry" -> "条目"
            "project" -> "文件夹"
            "attachment" -> "附件"
            "object-relation" -> "关联"
            "object-label", "object-label-assignment" -> "标签"
            else -> "对象"
        }
        return "${distinctObjects.size} 个$label"
    }

    private fun normalizeFolderParentId(parentFolderId: String?, rootProjectId: String): String? {
        val value = parentFolderId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return value.takeUnless {
            it.equals("root", ignoreCase = true) || it == rootProjectId
        }
    }

    private fun requireMutableFolderId(folderId: String, rootProjectId: String) {
        require(folderId.isNotBlank()) { "Folder ID cannot be empty" }
        require(folderId != rootProjectId && !folderId.equals("root", ignoreCase = true)) {
            "The MDBX2 root collection cannot be modified as a folder"
        }
    }

    private fun topologicallySortedFolders(
        folders: List<MdbxMigrationFolderPlan>
    ): List<MdbxMigrationFolderPlan> {
        val byId = folders.associateBy { it.sourceFolderId }
        require(byId.size == folders.size) { "Migration folder IDs must be unique" }
        val state = mutableMapOf<String, Int>()
        val sorted = mutableListOf<MdbxMigrationFolderPlan>()

        fun visit(folder: MdbxMigrationFolderPlan) {
            when (state[folder.sourceFolderId]) {
                1 -> error("Migration folder hierarchy contains a cycle at ${folder.sourceFolderId}")
                2 -> return
            }
            state[folder.sourceFolderId] = 1
            folder.sourceParentFolderId.normalizedMigrationParentId()?.let { parentId ->
                val parent = byId[parentId]
                    ?: error("Migration parent folder is missing: $parentId")
                visit(parent)
            }
            state[folder.sourceFolderId] = 2
            sorted += folder
        }

        folders.forEach(::visit)
        return sorted
    }

    private fun String?.normalizedMigrationParentId(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return value.takeUnless { it.equals("root", ignoreCase = true) }
    }

    private fun snapshotDeviceContext(): MdbxDeviceContext = MdbxDeviceContext(
        assurance = MdbxDeviceAssurance.STANDARD,
        secureClipboardAvailable = true,
        screenCaptureProtectionAvailable = true,
        secureTempFilesAvailable = true
    )

    private fun uniffi.mdbx_ffi.MdbxManagedSnapshotSummary.toRepositorySummary(): MdbxSnapshotSummary =
        MdbxSnapshotSummary(
            snapshotId = snapshotId,
            baseCommitId = baseCommitId,
            name = name,
            snapshotType = if (kind == MdbxSnapshotKind.AUTOMATIC) "auto" else "manual",
            isFull = isFull,
            payloadBytes = payloadBytes.toLong(),
            createdAt = createdAt,
            createdByDeviceId = createdByDeviceId,
            autoPrune = autoPrune,
            integrityOk = integrityOk
        )

    private fun MdbxSnapshotStructureNode.toRepositoryNode(
        rootIds: Set<String>
    ): MdbxStructureNode? {
        if (nodeType.equals("folder", ignoreCase = true) && id in rootIds) return null
        val parentIsRoot = parentId != null && parentId in rootIds
        return MdbxStructureNode(
            id = id,
            parentId = parentId?.takeUnless { it in rootIds },
            name = name,
            type = if (nodeType.equals("folder", ignoreCase = true)) {
                MdbxStructureNodeType.FOLDER
            } else {
                MdbxStructureNodeType.ENTRY
            },
            path = if (parentIsRoot) path.substringAfter('/', name) else path,
            status = when (status.lowercase()) {
                "added" -> MdbxStructureNodeStatus.ADDED
                "removed" -> MdbxStructureNodeStatus.REMOVED
                "modified" -> MdbxStructureNodeStatus.MODIFIED
                else -> MdbxStructureNodeStatus.UNCHANGED
            },
            childCount = childCount.toInt(),
            metadata = metadata
        )
    }

    private fun toRepositoryDiagnostic(issue: NativeMdbxHealthIssue): MdbxHealthIssueDiagnostic =
        MdbxHealthIssueDiagnostic(
            severity = when (issue.severity) {
                NativeMdbxHealthIssueSeverity.INFO -> MdbxHealthSeverity.INFO
                NativeMdbxHealthIssueSeverity.WARNING -> MdbxHealthSeverity.WARNING
                NativeMdbxHealthIssueSeverity.ERROR -> MdbxHealthSeverity.ERROR
                NativeMdbxHealthIssueSeverity.CRITICAL -> MdbxHealthSeverity.CRITICAL
            },
            category = issue.category,
            description = issue.description
        )

    private fun toRepositoryItem(item: NativeMdbxHealthRepairItem): MdbxHealthRepairItem =
        MdbxHealthRepairItem(
            repairId = item.repairId,
            kind = when (item.kind) {
                NativeMdbxHealthRepairItemKind.MISSING_TOMBSTONE ->
                    MdbxHealthRepairItemKind.MISSING_TOMBSTONE
                NativeMdbxHealthRepairItemKind.DUPLICATE_TOMBSTONES ->
                    MdbxHealthRepairItemKind.DUPLICATE_TOMBSTONES
                NativeMdbxHealthRepairItemKind.ACTIVE_OBJECT_TOMBSTONE_CONFLICT ->
                    MdbxHealthRepairItemKind.ACTIVE_OBJECT_TOMBSTONE_CONFLICT
            },
            objectType = item.objectType,
            objectId = item.objectId,
            tombstoneCount = item.tombstoneCount.toDiagnosticInt()
        )

    private fun toRepositoryPlan(plan: NativeMdbxHealthRepairPlan): MdbxHealthRepairPlan =
        MdbxHealthRepairPlan(
            token = plan.token,
            automaticItems = plan.automaticItems.map(::toRepositoryItem),
            conflictItems = plan.conflictItems.map(::toRepositoryItem),
            blockers = plan.blockers.map { blocker ->
                MdbxHealthRepairBlocker(
                    category = blocker.category,
                    description = blocker.description
                )
            },
            canApply = plan.canApply
        )

    private fun toNativeChoice(choice: MdbxHealthRepairChoice): NativeMdbxHealthRepairChoice =
        when (choice) {
            MdbxHealthRepairChoice.KEEP_CONTENT -> NativeMdbxHealthRepairChoice.KEEP_CONTENT
            MdbxHealthRepairChoice.DELETE_OBJECT -> NativeMdbxHealthRepairChoice.DELETE_OBJECT
            MdbxHealthRepairChoice.CANCEL -> NativeMdbxHealthRepairChoice.CANCEL
        }

    private fun toRepositoryResult(
        result: NativeMdbxHealthRepairApplyResult
    ): MdbxHealthRepairApplyResult =
        MdbxHealthRepairApplyResult(
            status = when (result.status) {
                NativeMdbxHealthRepairStatus.APPLIED -> MdbxHealthRepairStatus.APPLIED
                NativeMdbxHealthRepairStatus.CANCELLED -> MdbxHealthRepairStatus.CANCELLED
                NativeMdbxHealthRepairStatus.NO_CHANGES -> MdbxHealthRepairStatus.NO_CHANGES
            },
            snapshotId = result.snapshotId,
            commitId = result.commitId,
            repairedCount = result.repairedCount.toDiagnosticInt(),
            alreadyCommitted = result.alreadyCommitted,
            healthy = result.health.healthy,
            remainingIssues = result.health.issues.map(::toRepositoryDiagnostic)
        )

    private fun ULong.toDiagnosticInt(): Int = coerceAtMost(Int.MAX_VALUE.toULong()).toInt()

    private fun ULong.toDiagnosticLong(): Long = coerceAtMost(Long.MAX_VALUE.toULong()).toLong()

    private data class EntryMutation(
        val databaseId: Long,
        val folderId: String?,
        val entryId: String,
        val entryType: String,
        val title: String,
        val payloadJson: String,
        val deleted: Boolean
    )

    private data class AttachmentFingerprint(
        val parentEntryId: String,
        val fileName: String,
        val mimeType: String,
        val size: Long,
        val sha256: String
    )

    companion object {
        private const val MIGRATION_BATCH_SIZE = 100
        private const val STEAM_MAFILE_ENTRY_TYPE = "steam-mafile"
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
        private val attachmentFingerprintComparator = compareBy<AttachmentFingerprint>(
            AttachmentFingerprint::parentEntryId,
            AttachmentFingerprint::fileName,
            AttachmentFingerprint::mimeType,
            AttachmentFingerprint::size,
            AttachmentFingerprint::sha256
        )
        private const val COLLECTION_PAGE_SIZE = 200u
        private const val HISTORY_PAGE_SIZE = 100
        private const val MAX_HISTORY_ITEMS = 120
        private const val PENDING_SYNC_PAGE_SIZE = 100
        private const val MAX_PENDING_SYNC_ITEMS = 1_000
        private const val MAX_DIAGNOSTIC_ISSUE_PREVIEW = 3
        private const val SNAPSHOT_PAGE_SIZE = 100
        private const val MAX_SNAPSHOT_ITEMS = 200
        private const val MAX_CONFLICT_PAGE_SIZE = 50
        private const val MAX_CONFLICT_ITEMS = 100
        private const val MAX_PROJECT_TAGS = 64
        private const val PROJECT_TAG_PAYLOAD_KIND = "monica-project-tag"
        private const val PROJECT_TAG_PAYLOAD_SCHEMA_VERSION = 1u
        private const val MDBX2_MANUAL_SYNC_PAYLOAD_FORMAT = "mdbx2-authenticated-complete-v1"
        private const val MAX_MANUAL_SYNC_BUNDLE_BYTES = 32L * 1024L * 1024L
        private const val MAX_MANUAL_SYNC_BASE64_CHARACTERS = 44_739_248
        private const val MAX_METADATA_BENCHMARK_OPERATIONS = 500
        private const val SHORT_ID_LENGTH = 8
        private const val ROOT_COLLECTION_DISPLAY_NAME = "根目录"
        private const val ATTACHMENT_CHUNK_BYTES = 256L * 1024L
        private const val ATTACHMENT_BUFFER_BYTES = 8 * 1024
        private const val MAX_ATTACHMENT_BYTES = 64L * 1024L * 1024L
    }
}
