package takagi.ru.monica.steam.data

import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import takagi.ru.monica.attachments.executor.KeePassAttachmentRef
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.LOGIN_TYPE_STEAM_MAFILE
import takagi.ru.monica.steam.importer.SteamMaFileBackupCodec
import takagi.ru.monica.steam.importer.SteamMaFileParser
import takagi.ru.monica.steam.importer.SteamMaFilePayload
import takagi.ru.monica.utils.KeePassCustomFieldData
import takagi.ru.monica.utils.KeePassEntryData
import takagi.ru.monica.utils.KeePassKdbxService

data class SteamKeePassAccountRecord(
    val account: SteamAccount,
    val entryUuid: String,
    val groupPath: String?
)

class SteamKeePassAccountStore(
    private val service: KeePassKdbxService,
    private val parser: SteamMaFileParser = SteamMaFileParser()
) {
    suspend fun moveToGroup(databaseId: Long, entryUuid: String, groupPath: String?) {
        service.moveNativeEntry(databaseId, entryUuid, databaseId, groupPath).getOrThrow()
    }

    suspend fun listGroups(databaseId: Long) = service.listGroups(databaseId).getOrThrow()

    suspend fun loadAccounts(databaseId: Long): List<SteamKeePassAccountRecord> {
        val entries = service.readPasswordEntries(databaseId).getOrThrow()
        return entries
            .filterNot(KeePassEntryData::isInRecycleBin)
            .filter { entry ->
                SteamExternalMaFileContract.isMarked(
                    entry.customFields.map { it.title to it.value }
                )
            }
            .mapNotNull { entry -> loadEntry(databaseId, entry) }
            .mapIndexed { index, record ->
                record.copy(
                    account = record.account.copy(
                        selected = index == 0,
                        sortOrder = index
                    )
                )
            }
    }

    suspend fun upsertPayload(
        databaseId: Long,
        payload: SteamMaFilePayload,
        existingEntryUuid: String? = null,
        groupPath: String? = null
    ): SteamKeePassAccountRecord {
        val existing = existingEntryUuid
            ?.let { uuid -> loadAccounts(databaseId).firstOrNull { it.entryUuid == uuid } }
            ?: payload.steamId.takeIf(String::isNotBlank)?.let { steamId ->
                loadAccounts(databaseId).firstOrNull { it.account.steamId == steamId }
            }
        return upsertAccount(
            databaseId = databaseId,
            entryUuid = existing?.entryUuid,
            groupPath = existing?.groupPath ?: groupPath,
            account = payload.toSteamAccount(
                id = existing?.account?.id ?: 0L
            )
        )
    }

    suspend fun upsertAccount(
        databaseId: Long,
        entryUuid: String?,
        groupPath: String?,
        account: SteamAccount
    ): SteamKeePassAccountRecord {
        val resolvedUuid = entryUuid?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        val isNew = entryUuid.isNullOrBlank()
        val title = account.displayName
            .ifBlank { account.accountName }
            .ifBlank { account.visibleSteamId }
            .ifBlank { "Steam" }
        val entry = PasswordEntry(
            title = title,
            website = "https://steamcommunity.com",
            username = account.accountName,
            password = "",
            loginType = LOGIN_TYPE_STEAM_MAFILE,
            notes = "",
            createdAt = Date(account.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()),
            updatedAt = Date(),
            keepassDatabaseId = databaseId,
            keepassGroupPath = groupPath,
            keepassEntryUuid = resolvedUuid
        )
        val preservedFields = if (isNew) {
            emptyList()
        } else {
            service.readPasswordEntries(databaseId).getOrThrow()
                .firstOrNull { it.entryUuid == resolvedUuid }
                ?.customFields
                .orEmpty()
                .filterNot {
                    it.title.equals(
                        SteamExternalMaFileContract.MARKER_FIELD,
                        ignoreCase = true
                    )
                }
        }
        val marker = preservedFields + KeePassCustomFieldData(
            title = SteamExternalMaFileContract.MARKER_FIELD,
            value = SteamExternalMaFileContract.MARKER_VALUE,
            isProtected = false,
            sortOrder = preservedFields.size
        )
        val previousAttachments = if (isNew) {
            emptyList()
        } else {
            service.readEntryAttachments(databaseId, resolvedUuid)
                .getOrThrow()
                .filter { SteamExternalMaFileContract.isMaFile(it.fileName) }
        }

        val entryWrite = if (isNew) {
            service.addPasswordEntry(
                databaseId = databaseId,
                entry = entry,
                resolvePassword = { "" },
                customFields = marker
            )
        } else {
            service.updatePasswordEntry(
                databaseId = databaseId,
                entry = entry,
                resolvePassword = { "" },
                customFields = marker
            )
        }
        entryWrite.getOrThrow()

        val maFileBytes = SteamMaFileBackupCodec.encode(account).toByteArray(Charsets.UTF_8)
        check(maFileBytes.size in 1..SteamExternalMaFileContract.MAX_MAFILE_BYTES) {
            "Steam maFile exceeds the 1 MiB attachment limit"
        }
        val newAttachment = try {
            service.addAttachmentToEntry(
                databaseId = databaseId,
                entryUuid = resolvedUuid,
                fileName = SteamExternalMaFileContract.attachmentFileName(account),
                bytes = maFileBytes,
                memoryProtection = true,
                compressed = true
            ).getOrThrow()
        } catch (error: Throwable) {
            if (isNew) {
                runCatching {
                    service.deletePasswordEntries(databaseId, listOf(entry)).getOrThrow()
                }
            }
            throw error
        } finally {
            maFileBytes.fill(0)
        }

        try {
            previousAttachments.forEach { attachment ->
                service.deleteAttachmentFromEntry(
                    databaseId = databaseId,
                    entryUuid = resolvedUuid,
                    hashHex = KeePassAttachmentRef.from(
                        hashHex = attachment.hashHex,
                        fileName = attachment.fileName
                    ).encode()
                ).getOrThrow()
            }
        } catch (error: Throwable) {
            runCatching {
                service.deleteAttachmentFromEntry(
                    databaseId = databaseId,
                    entryUuid = resolvedUuid,
                    hashHex = KeePassAttachmentRef.from(
                        newAttachment.hashHex,
                        newAttachment.fileName
                    ).encode()
                ).getOrThrow()
            }
            throw error
        }

        return SteamKeePassAccountRecord(
            account = account.copy(
                id = runtimeAccountId(databaseId, resolvedUuid),
                storageFolderId = groupPath,
                updatedAt = System.currentTimeMillis()
            ),
            entryUuid = resolvedUuid,
            groupPath = groupPath
        )
    }

    suspend fun deleteAccount(
        databaseId: Long,
        entryUuid: String,
        groupPath: String?
    ) {
        service.deletePasswordEntries(
            databaseId = databaseId,
            entries = listOf(
                PasswordEntry(
                    title = "Steam",
                    website = "",
                    username = "",
                    password = "",
                    keepassDatabaseId = databaseId,
                    keepassGroupPath = groupPath,
                    keepassEntryUuid = entryUuid
                )
            )
        ).getOrThrow()
    }

    private suspend fun loadEntry(
        databaseId: Long,
        entry: KeePassEntryData
    ): SteamKeePassAccountRecord? {
        val entryUuid = entry.entryUuid?.takeIf(String::isNotBlank) ?: return null
        val attachments = service.readEntryAttachments(databaseId, entryUuid).getOrNull() ?: return null
        val candidateNames = SteamExternalMaFileContract.candidateFileNames(
            attachments.map { it.fileName }
        )
        val candidates = candidateNames
            .flatMap { name -> attachments.filter { it.fileName == name } }
            .distinctBy { it.hashHex to it.fileName }
        for (candidate in candidates) {
            if (candidate.sizeBytes > SteamExternalMaFileContract.MAX_MAFILE_BYTES) continue
            val bytes = service.readAttachmentBytes(
                databaseId = databaseId,
                entryUuid = entryUuid,
                hashHex = KeePassAttachmentRef.from(candidate.hashHex, candidate.fileName).encode()
            ).getOrNull() ?: continue
            if (bytes.isEmpty() || bytes.size > SteamExternalMaFileContract.MAX_MAFILE_BYTES) {
                bytes.fill(0)
                continue
            }
            val parsed = try {
                parser.parse(
                    maFileContent = bytes.toString(Charsets.UTF_8),
                    fileName = candidate.fileName
                )
            } catch (_: Throwable) {
                null
            } finally {
                bytes.fill(0)
            }
            if (parsed != null) {
                val displayName = entry.title.trim().takeIf { title ->
                    title.isNotBlank() && title != parsed.accountName && title != parsed.steamId
                } ?: parsed.displayName
                return SteamKeePassAccountRecord(
                    account = parsed.copy(displayName = displayName).toSteamAccount(
                        id = runtimeAccountId(databaseId, entryUuid)
                    ).copy(storageFolderId = entry.groupPath),
                    entryUuid = entryUuid,
                    groupPath = entry.groupPath
                )
            }
        }
        return null
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
        fun runtimeAccountId(databaseId: Long, entryUuid: String): Long {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("keepass:$databaseId:$entryUuid".toByteArray(Charsets.UTF_8))
            var value = 0L
            repeat(7) { index ->
                value = (value shl 8) or (digest[index].toLong() and 0xff)
            }
            return -value.coerceAtLeast(1L)
        }
    }
}
