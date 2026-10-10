package takagi.ru.monica.steam.data

import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.facade.AttachmentFacade.BitwardenContext
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.syncForUserVisibleRequest
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.LOGIN_TYPE_STEAM_MAFILE
import takagi.ru.monica.data.model.isExternalSteamMaFileEntry
import takagi.ru.monica.steam.importer.SteamMaFileBackupCodec
import takagi.ru.monica.steam.importer.SteamMaFileParser
import takagi.ru.monica.steam.importer.SteamMaFilePayload
import takagi.ru.monica.steam.diagnostics.SteamDiagLogger

data class SteamBitwardenAccountRecord(
    val account: SteamAccount,
    val passwordEntryId: Long,
    val cipherId: String
)

internal fun requireSteamBitwardenSyncSuccess(
    result: BitwardenRepository.SyncResult,
    operation: String
) {
    when (result) {
        is BitwardenRepository.SyncResult.Success -> {
            if (result.uploadFailedCount > 0) {
                throw IllegalStateException(
                    "$operation: Bitwarden upload failed (${result.uploadFailedCount})"
                )
            }
        }
        is BitwardenRepository.SyncResult.Error -> throw IllegalStateException(result.message)
        is BitwardenRepository.SyncResult.EmptyVaultBlocked -> {
            throw IllegalStateException(result.reason)
        }
    }
}

class SteamBitwardenAccountStore(
    private val database: PasswordDatabase,
    private val bitwardenRepository: BitwardenRepository,
    private val attachmentFacade: AttachmentFacade,
    private val parser: SteamMaFileParser = SteamMaFileParser()
) {
    private val passwordDao = database.passwordEntryDao()
    private val customFieldDao = database.customFieldDao()
    private val vaultDao = database.bitwardenVaultDao()
    private val lastKnownGoodRecords = ConcurrentHashMap<RecordCacheKey, CachedRecord>()

    private data class RecordCacheKey(
        val vaultId: Long,
        val passwordEntryId: Long
    )

    private data class CachedRecord(
        val record: SteamBitwardenAccountRecord,
        val attachmentId: String?,
        val attachmentKey: String?
    )

    private sealed class EntryLoadResult {
        data object NotSteamEntry : EntryLoadResult()
        data class Loaded(val record: SteamBitwardenAccountRecord) : EntryLoadResult()
        data class Failed(val stage: String) : EntryLoadResult()
    }

    suspend fun loadAccounts(
        vaultId: Long,
        refreshRemote: Boolean = false
    ): List<SteamBitwardenAccountRecord> {
        val vault = vaultDao.getVaultById(vaultId) ?: return emptyList()
        check(bitwardenRepository.isVaultUnlocked(vaultId)) { "Bitwarden vault is locked" }
        val syncError = if (refreshRemote) {
            when (
                val result = bitwardenRepository.syncForUserVisibleRequest(
                    vaultId = vaultId,
                    requestIdPrefix = "steam-mafile-load"
                )
            ) {
                is BitwardenRepository.SyncResult.Error -> result.message
                is BitwardenRepository.SyncResult.EmptyVaultBlocked -> result.reason
                is BitwardenRepository.SyncResult.Success -> null
            }
        } else {
            null
        }
        var failedSteamEntries = 0
        val records = passwordDao.getByBitwardenVaultId(vaultId)
            .filterNot { it.isDeleted || it.isArchived }
            .mapNotNull { entry ->
                when (val result = loadEntry(vault, entry)) {
                    EntryLoadResult.NotSteamEntry -> null
                    is EntryLoadResult.Loaded -> result.record.copy(
                        account = result.record.account.copy(storageFolderId = entry.bitwardenFolderId)
                    )
                    is EntryLoadResult.Failed -> {
                        failedSteamEntries++
                        SteamDiagLogger.append(
                            "bitwarden_mafile load_failed stage=${result.stage} " +
                                "vault_id=$vaultId entry_id=${entry.id}"
                        )
                        null
                    }
                }
            }
            .mapIndexed { index, record ->
                record.copy(
                    account = record.account.copy(selected = index == 0, sortOrder = index)
                )
            }
        if (records.isEmpty() && (syncError != null || failedSteamEntries > 0)) {
            throw if (syncError != null) IllegalStateException(syncError) else IllegalStateException()
        }
        return records
    }

    private suspend fun loadEntry(
        vault: BitwardenVault,
        entry: PasswordEntry
    ): EntryLoadResult {
        val vaultId = vault.id
        val fields = customFieldDao.getFieldsByEntryIdSync(entry.id)
        val hasMarker = SteamExternalMaFileContract.isMarked(fields.map { it.title to it.value })
        if (!hasMarker && !entry.isExternalSteamMaFileEntry()) {
            return EntryLoadResult.NotSteamEntry
        }
        val cipherId = entry.bitwardenCipherId?.takeIf(String::isNotBlank)
            ?: return cachedRecordOrFailure(vaultId, entry.id, "cipher_missing")

        // Migrate entries created before the internal type was persisted. This update is local
        // metadata only; the marker remains the source of truth for the external Steam store.
        if (hasMarker && !entry.isExternalSteamMaFileEntry()) {
            passwordDao.updatePasswordEntry(entry.copy(loginType = LOGIN_TYPE_STEAM_MAFILE))
        }

        val cachedContext = bitwardenRepository.getAttachmentBitwardenContext(vault, cipherId)
        if (cachedContext != null) {
            val cachedResult = loadEntryFromAttachments(vaultId, entry, cipherId, cachedContext)
            if (cachedResult is EntryLoadResult.Loaded) return cachedResult
        }

        // A new device can receive the cipher before its attachment metadata is present in
        // Monica's local attachment table. Fetch the cipher directly as a recovery step and
        // resolve its per-item key before downloading the maFile.
        val snapshot = bitwardenRepository.fetchAttachmentCipherSnapshot(vault, cipherId)
            ?: return cachedRecordOrFailure(vaultId, entry.id, "cipher_snapshot")
        val reconcileResult = runCatching {
            attachmentFacade.reconcileBitwardenAttachments(
                owner = AttachmentOwner.password(entry.id),
                remoteAttachments = snapshot.attachments
            )
        }
        if (reconcileResult.isFailure) {
            return cachedRecordOrFailure(
                vaultId = vaultId,
                passwordEntryId = entry.id,
                stage = "attachment_reconcile",
                remoteAttachments = snapshot.attachments
            )
        }
        val recoveredResult = loadEntryFromAttachments(vaultId, entry, cipherId, snapshot.context)
        return if (recoveredResult is EntryLoadResult.Loaded) {
            recoveredResult
        } else {
            cachedRecordOrFailure(
                vaultId = vaultId,
                passwordEntryId = entry.id,
                stage = (recoveredResult as? EntryLoadResult.Failed)?.stage ?: "attachment_load",
                remoteAttachments = snapshot.attachments
            )
        }
    }

    private suspend fun loadEntryFromAttachments(
        vaultId: Long,
        entry: PasswordEntry,
        cipherId: String,
        context: BitwardenContext
    ): EntryLoadResult {
        val attachments = attachmentFacade.listByPassword(entry.id)
            .filter { it.sourceEnum == AttachmentSource.BITWARDEN }
        if (attachments.isEmpty()) return EntryLoadResult.Failed("attachment_missing")
        val candidateNames = SteamExternalMaFileContract.candidateFileNames(
            attachments.map { it.fileName }
        )
        val candidates = candidateNames
            .flatMap { name -> attachments.filter { it.fileName == name } }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<takagi.ru.monica.attachments.model.Attachment> {
                    SteamExternalMaFileContract.isMaFile(it.fileName)
                }.thenByDescending { it.updatedAt }
            )
        for (attachment in candidates) {
            if (attachment.sizeBytes > SteamExternalMaFileContract.MAX_MAFILE_BYTES) continue
            val bytesResult = runCatching {
                attachmentFacade.readAttachmentBytes(
                    attachmentId = attachment.id,
                    maxBytes = SteamExternalMaFileContract.MAX_MAFILE_BYTES,
                    bitwardenContext = context
                )
            }
            val bytes = bytesResult.getOrNull()
            if (bytes == null) {
                SteamDiagLogger.append(
                    "bitwarden_mafile attachment_read_failed " +
                        "entry_id=${entry.id} error=${bytesResult.exceptionOrNull()?.javaClass?.simpleName.orEmpty()}"
                )
                continue
            }
            val payload = try {
                parser.parse(
                    maFileContent = bytes.toString(Charsets.UTF_8),
                    fileName = attachment.fileName
                )
            } catch (error: Throwable) {
                SteamDiagLogger.append(
                    "bitwarden_mafile attachment_parse_failed " +
                        "entry_id=${entry.id} error=${error.javaClass.simpleName}"
                )
                null
            } finally {
                bytes.fill(0)
            }
            if (payload != null) {
                val displayName = entry.title.trim().takeIf { title ->
                    title.isNotBlank() && title != payload.accountName && title != payload.steamId
                } ?: payload.displayName
                val record = SteamBitwardenAccountRecord(
                    account = payload.copy(displayName = displayName).toSteamAccount(
                        id = runtimeAccountId(vaultId, entry.id)
                    ),
                    passwordEntryId = entry.id,
                    cipherId = cipherId
                )
                lastKnownGoodRecords[RecordCacheKey(vaultId, entry.id)] = CachedRecord(
                    record = record,
                    attachmentId = attachment.bitwardenAttachmentId,
                    attachmentKey = attachment.bitwardenFileKeyEnc
                )
                return EntryLoadResult.Loaded(record)
            }
        }
        return EntryLoadResult.Failed("attachment_unreadable")
    }

    private fun cachedRecordOrFailure(
        vaultId: Long,
        passwordEntryId: Long,
        stage: String,
        remoteAttachments: List<CipherAttachmentApiData>? = null
    ): EntryLoadResult {
        val key = RecordCacheKey(vaultId, passwordEntryId)
        val cached = lastKnownGoodRecords[key] ?: return EntryLoadResult.Failed(stage)
        if (remoteAttachments != null) {
            val compatible = remoteAttachments.any { remote ->
                remote.id == cached.attachmentId &&
                    (
                        remote.key.isNullOrBlank() ||
                            cached.attachmentKey.isNullOrBlank() ||
                            remote.key == cached.attachmentKey
                    )
            }
            if (!compatible) {
                lastKnownGoodRecords.remove(key)
                return EntryLoadResult.Failed(stage)
            }
        }
        return EntryLoadResult.Loaded(cached.record)
    }

    suspend fun upsertPayload(
        vaultId: Long,
        payload: SteamMaFilePayload,
        existingPasswordEntryId: Long? = null
    ): SteamBitwardenAccountRecord {
        val provisional = payload.toSteamAccount(
            id = existingPasswordEntryId?.let { runtimeAccountId(vaultId, it) } ?: 0L
        )
        return upsertAccount(vaultId, existingPasswordEntryId, provisional)
    }

    suspend fun upsertAccount(
        vaultId: Long,
        existingPasswordEntryId: Long?,
        account: SteamAccount,
        folderId: String? = null,
        relocate: Boolean = false,
        forceNew: Boolean = false
    ): SteamBitwardenAccountRecord {
        val vault = vaultDao.getVaultById(vaultId)
            ?: throw IllegalStateException("Bitwarden vault not found")
        check(bitwardenRepository.isVaultUnlocked(vaultId)) { "Bitwarden vault is locked" }
        val bitwardenPremium = bitwardenRepository.isVaultPremium(vaultId)
        check(bitwardenPremium) { "Bitwarden Premium is required for Steam maFile attachments" }
        val resolvedExisting = existingPasswordEntryId
            ?.let { entryId ->
                passwordDao.getPasswordEntryById(entryId)
                    ?.takeIf { it.bitwardenVaultId == vaultId }
            }
            ?: account.steamId.takeIf { it.isNotBlank() && !forceNew }?.let { steamId ->
                findSteamEntryBySteamId(vaultId, steamId)
            }
        val existingFields = resolvedExisting?.let { entry ->
            customFieldDao.getFieldsByEntryIdSync(entry.id).map { it.title to it.value }
        }.orEmpty()
        val entryWasReady = SteamExternalMaFileContract.isMarked(existingFields)
        val initialMarkerValue = if (entryWasReady) {
            SteamExternalMaFileContract.MARKER_VALUE
        } else {
            SteamExternalMaFileContract.PENDING_MARKER_VALUE
        }
        val title = account.displayName
            .ifBlank { account.accountName }
            .ifBlank { account.visibleSteamId }
            .ifBlank { "Steam" }
        val now = Date()
        val candidate = (resolvedExisting ?: PasswordEntry(
            title = title,
            website = "https://steamcommunity.com",
            username = account.steamId.ifBlank { account.accountName },
            password = "",
            bitwardenVaultId = vaultId,
            bitwardenLocalModified = true
        )).copy(
            title = title,
            website = "https://steamcommunity.com",
            username = account.steamId.ifBlank { account.accountName },
            password = "",
            updatedAt = now,
            bitwardenFolderId = if (relocate) folderId else resolvedExisting?.bitwardenFolderId,
            bitwardenVaultId = vaultId,
            isDeleted = false,
            deletedAt = null,
            isArchived = false,
            archivedAt = null,
            loginType = if (entryWasReady) LOGIN_TYPE_STEAM_MAFILE else "PASSWORD",
            bitwardenLocalModified = true
        )
        val entryId = database.withTransaction {
            val id = if (candidate.id == 0L) {
                passwordDao.insertPasswordEntry(candidate)
            } else {
                passwordDao.updatePasswordEntry(candidate)
                candidate.id
            }
            val preserved = customFieldDao.getFieldsByEntryIdSync(id)
                .filterNot {
                    it.title.equals(
                        SteamExternalMaFileContract.MARKER_FIELD,
                        ignoreCase = true
                    )
                }
            customFieldDao.replaceFieldsForEntry(
                entryId = id,
                newFields = preserved + CustomField(
                    entryId = id,
                    title = SteamExternalMaFileContract.MARKER_FIELD,
                    value = initialMarkerValue,
                    isProtected = false,
                    sortOrder = preserved.size
                )
            )
            id
        }

        val syncResult = bitwardenRepository.syncForUserVisibleRequest(
            vaultId = vaultId,
            requestIdPrefix = "steam-mafile-upsert"
        )
        requireSteamBitwardenSyncSuccess(syncResult, operation = "steam-mafile-upsert")
        val syncedEntry = passwordDao.getPasswordEntryById(entryId)
            ?.takeIf { !it.bitwardenCipherId.isNullOrBlank() }
            ?: account.steamId.takeIf { it.isNotBlank() && !forceNew }?.let { steamId ->
                findSteamEntryBySteamId(vaultId, steamId)
            }
                ?.takeIf { !it.bitwardenCipherId.isNullOrBlank() }
            ?: throw IllegalStateException("Bitwarden cipher was not created")
        val cipherId = syncedEntry.bitwardenCipherId.orEmpty()
        val snapshot = bitwardenRepository.fetchAttachmentCipherSnapshot(vault, cipherId)
            ?: throw IllegalStateException("Bitwarden attachment cipher could not be verified")
        attachmentFacade.reconcileBitwardenAttachments(
            owner = AttachmentOwner.password(syncedEntry.id),
            remoteAttachments = snapshot.attachments
        )
        val context = snapshot.context
        val oldAttachments = attachmentFacade.listByPassword(syncedEntry.id)
            .filter { it.sourceEnum == AttachmentSource.BITWARDEN }
            .let { attachments ->
                val namedMaFiles = attachments.filter {
                    SteamExternalMaFileContract.isMaFile(it.fileName)
                }
                if (namedMaFiles.isNotEmpty()) namedMaFiles
                else attachments.singleOrNull()?.let(::listOf).orEmpty()
            }
        val bytes = SteamMaFileBackupCodec.encode(account).toByteArray(Charsets.UTF_8)
        val newAttachment = try {
            attachmentFacade.addInlineAttachment(
                AttachmentFacade.InlineUploadRequest(
                    owner = AttachmentOwner.password(syncedEntry.id),
                    source = AttachmentSource.BITWARDEN,
                    fileName = SteamExternalMaFileContract.attachmentFileName(account),
                    mimeType = SteamExternalMaFileContract.MIME_TYPE,
                    bytes = bytes,
                    isPlusActivated = true,
                    bitwardenPremium = bitwardenPremium,
                    bitwardenContext = context
                )
            )
        } finally {
            bytes.fill(0)
        }
        try {
            oldAttachments.forEach { old ->
                attachmentFacade.deleteAttachment(
                    attachmentId = old.id,
                    bitwardenContext = context
                )
            }
        } catch (error: Throwable) {
            runCatching {
                attachmentFacade.deleteAttachment(
                    attachmentId = newAttachment.id,
                    bitwardenContext = context
                )
            }
            throw error
        }

        if (!entryWasReady) {
            database.withTransaction {
                val latest = passwordDao.getPasswordEntryById(syncedEntry.id)
                    ?: throw IllegalStateException("Bitwarden Steam entry is missing")
                passwordDao.updatePasswordEntry(
                    latest.copy(
                        loginType = LOGIN_TYPE_STEAM_MAFILE,
                        bitwardenLocalModified = true,
                        updatedAt = Date()
                    )
                )
                val preserved = customFieldDao.getFieldsByEntryIdSync(syncedEntry.id)
                    .filterNot {
                        it.title.equals(
                            SteamExternalMaFileContract.MARKER_FIELD,
                            ignoreCase = true
                        )
                    }
                customFieldDao.replaceFieldsForEntry(
                    entryId = syncedEntry.id,
                    newFields = preserved + CustomField(
                        entryId = syncedEntry.id,
                        title = SteamExternalMaFileContract.MARKER_FIELD,
                        value = SteamExternalMaFileContract.MARKER_VALUE,
                        isProtected = false,
                        sortOrder = preserved.size
                    )
                )
            }
            val readySyncResult = bitwardenRepository.syncForUserVisibleRequest(
                vaultId = vaultId,
                requestIdPrefix = "steam-mafile-ready"
            )
            requireSteamBitwardenSyncSuccess(readySyncResult, operation = "steam-mafile-ready")
        }

        val record = SteamBitwardenAccountRecord(
            account = account.copy(
                id = runtimeAccountId(vaultId, syncedEntry.id),
                storageFolderId = syncedEntry.bitwardenFolderId,
                updatedAt = System.currentTimeMillis()
            ),
            passwordEntryId = syncedEntry.id,
            cipherId = cipherId
        )
        lastKnownGoodRecords[RecordCacheKey(vaultId, syncedEntry.id)] = CachedRecord(
            record = record,
            attachmentId = newAttachment.bitwardenAttachmentId,
            attachmentKey = newAttachment.bitwardenFileKeyEnc
        )
        return record
    }

    suspend fun moveToFolder(vaultId: Long, passwordEntryId: Long, folderId: String?) {
        check(bitwardenRepository.isVaultUnlocked(vaultId)) { "Bitwarden vault is locked" }
        val entry = requireNotNull(passwordDao.getPasswordEntryById(passwordEntryId))
        require(entry.bitwardenVaultId == vaultId)
        passwordDao.updatePasswordEntry(entry.copy(bitwardenFolderId = folderId,
            bitwardenLocalModified = true, updatedAt = Date()))
        requireSteamBitwardenSyncSuccess(bitwardenRepository.syncForUserVisibleRequest(
            vaultId = vaultId, requestIdPrefix = "steam-mafile-move"), operation = "steam-mafile-move")
    }

    suspend fun deleteAccount(vaultId: Long, passwordEntryId: Long) {
        val entry = passwordDao.getPasswordEntryById(passwordEntryId) ?: return
        val cipherId = entry.bitwardenCipherId
            ?: throw IllegalStateException("Bitwarden cipher is missing")
        bitwardenRepository.queueCipherDelete(
            vaultId = vaultId,
            cipherId = cipherId,
            entryId = passwordEntryId
        ).getOrThrow()
        val syncResult = bitwardenRepository.syncForUserVisibleRequest(
            vaultId = vaultId,
            requestIdPrefix = "steam-mafile-delete"
        )
        requireSteamBitwardenSyncSuccess(syncResult, operation = "steam-mafile-delete")
        attachmentFacade.purgeByPassword(passwordEntryId)
        passwordDao.getPasswordEntryById(passwordEntryId)?.let { entry ->
            passwordDao.deletePasswordEntry(entry)
        }
        lastKnownGoodRecords.remove(RecordCacheKey(vaultId, passwordEntryId))
    }

    private suspend fun findSteamEntryBySteamId(vaultId: Long, steamId: String): PasswordEntry? {
        val steamEntries = passwordDao.getByBitwardenVaultId(vaultId).filter { entry ->
            val fields = customFieldDao.getFieldsByEntryIdSync(entry.id).map { it.title to it.value }
            SteamExternalMaFileContract.isMarked(fields) ||
                SteamExternalMaFileContract.isPending(fields)
        }
        steamEntries.firstOrNull { it.username == steamId }?.let { return it }
        val readyEntries = steamEntries.filter { entry ->
            SteamExternalMaFileContract.isMarked(
                customFieldDao.getFieldsByEntryIdSync(entry.id).map { it.title to it.value }
            )
        }
        // 新建 Cipher 在首次同步后可能尚未完成附件元数据对齐，先按 Monica 写入的
        // Steam ID 查找；已有外部条目则再以 maFile 中解析出的 Steam ID为准。
        if (readyEntries.isEmpty()) return null
        return loadAccounts(vaultId)
            .firstOrNull { it.account.steamId == steamId }
            ?.let { record -> passwordDao.getPasswordEntryById(record.passwordEntryId) }
    }

    private fun SteamMaFilePayload.toSteamAccount(id: Long): SteamAccount {
        val now = System.currentTimeMillis()
        return SteamAccount(
            id = id,
            steamId = steamId,
            accountName = accountName,
            displayName = displayName,
            deviceId = deviceId,
            sharedSecret = sharedSecret,
            identitySecret = identitySecret,
            revocationCode = revocationCode,
            tokenGid = tokenGid,
            accessToken = accessToken,
            refreshToken = refreshToken,
            steamLoginSecure = steamLoginSecure,
            rawSteamGuardJson = rawJson,
            selected = true,
            sortOrder = 0,
            createdAt = now,
            updatedAt = now
        )
    }

    companion object {
        fun runtimeAccountId(vaultId: Long, passwordEntryId: Long): Long {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("bitwarden:$vaultId:$passwordEntryId".toByteArray(Charsets.UTF_8))
            var value = 0L
            repeat(7) { index ->
                value = (value shl 8) or (digest[index].toLong() and 0xff)
            }
            return -value.coerceAtLeast(1L)
        }
    }
}
