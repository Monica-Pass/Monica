package takagi.ru.monica.utils

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordHistoryEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.steam.data.SteamAccountRepository
import takagi.ru.monica.steam.data.SteamDatabase

data class RestoreApplyStats(
    val passwordImported: Int,
    val passwordSkipped: Int,
    val passwordFailed: Int,
    val secureItemImported: Int,
    val secureItemSkipped: Int,
    val secureItemFailed: Int,
    val passkeyImported: Int,
    val passkeySkipped: Int,
    val passkeyFailed: Int,
    val failedPasswordDetails: List<String>,
    val failedSecureItemDetails: List<String>,
    val steamAccountImported: Int = 0,
    val steamAccountFailed: Int = 0
) {
    fun totalImported(): Int = passwordImported + secureItemImported + passkeyImported + steamAccountImported
}

object BackupRestoreApplier {
    /**
     * 恢复期间抑制「改动后自动同步」：恢复会写入大量行，若因此触发上传，
     * 刚恢复的数据会被整包传回远端，覆盖掉那个可能更完整的备份。
     */
    suspend fun applyRestoreResult(
        context: Context,
        restoreResult: RestoreResult,
        passwordRepository: PasswordRepository,
        secureItemRepository: SecureItemRepository,
        localOnlyDedup: Boolean,
        logTag: String
    ): RestoreApplyStats = ChangeTriggeredBackupScheduler.withoutTriggering {
        applyRestoreResultInternal(
            context = context,
            restoreResult = restoreResult,
            passwordRepository = passwordRepository,
            secureItemRepository = secureItemRepository,
            localOnlyDedup = localOnlyDedup,
            logTag = logTag
        )
    }

    private suspend fun applyRestoreResultInternal(
        context: Context,
        restoreResult: RestoreResult,
        passwordRepository: PasswordRepository,
        secureItemRepository: SecureItemRepository,
        localOnlyDedup: Boolean,
        logTag: String
    ): RestoreApplyStats {
        val content = restoreResult.content
        val passwords = content.passwords
        val passwordHistory = content.passwordHistory
        val secureItems = content.secureItems
        val passkeys = content.passkeys
        val steamMaFiles = content.steamMaFiles
        val securityManager = SecurityManager(context)

        android.util.Log.d(logTag, "===== 开始恢复 =====")
        android.util.Log.d(logTag, "备份中密码数量: ${passwords.size}")
        android.util.Log.d(logTag, "备份中安全项数量: ${secureItems.size}")
        android.util.Log.d(logTag, "备份中通行密钥数量: ${passkeys.size}")
        android.util.Log.d(logTag, "备份中Steam maFile数量: ${steamMaFiles.size}")
        android.util.Log.d(logTag, "报告: ${restoreResult.report.getSummary()}")

        val passwordIdMap = mutableMapOf<Long, Long>()
        var passwordCount = 0
        var passwordSkipped = 0
        var passwordFailed = 0
        val failedPasswordDetails = mutableListOf<String>()

        passwords.forEach { password ->
            try {
                val originalId = password.id
                val existingEntry = PasswordImportDuplicateResolver.findMatchingEntry(
                    passwordRepository = passwordRepository,
                    securityManager = securityManager,
                    snapshot = ImportedPasswordSnapshot(
                        title = password.title,
                        username = password.username,
                        website = password.website,
                        password = password.password,
                        notes = password.notes,
                        email = password.email,
                        phone = password.phone,
                        authenticatorKey = password.authenticatorKey
                    ),
                    localOnly = localOnlyDedup
                )
                val encryptedImportedPassword = encryptImportedPasswordForDisplay(password.password, securityManager, logTag)
                val encryptedImportedAuthenticatorKey = encryptImportedAuthenticatorKey(
                    value = password.authenticatorKey,
                    securityManager = securityManager
                )

                if (existingEntry == null) {
                    val newId = passwordRepository.insertPasswordEntry(
                        password.copy(
                            id = 0,
                            password = encryptedImportedPassword,
                            authenticatorKey = encryptedImportedAuthenticatorKey
                        )
                    )
                    if (newId > 0) {
                        passwordIdMap[originalId] = newId
                        passwordCount++
                    } else {
                        passwordFailed++
                        android.util.Log.e(logTag, "Failed to insert password, returned ID <= 0")
                    }
                } else {
                    passwordIdMap[originalId] = existingEntry.id
                    if (
                        !password.customIconType.equals("NONE", ignoreCase = true) &&
                        existingEntry.customIconType.equals("NONE", ignoreCase = true)
                    ) {
                        val patchedEntry = existingEntry.copy(
                            customIconType = password.customIconType,
                            customIconValue = password.customIconValue?.let { java.io.File(it).name },
                            customIconUpdatedAt = password.customIconUpdatedAt
                        )
                        passwordRepository.updatePasswordEntry(patchedEntry)
                    }
                    passwordSkipped++
                }
            } catch (e: Exception) {
                passwordFailed++
                val detail = "${password.title} (${password.username}): ${e.message}"
                failedPasswordDetails.add(detail)
                android.util.Log.e(logTag, "Failed to import password: ${e.message}")
            }
        }

        passwords.forEach { password ->
            if (password.ssoRefEntryId != null && password.ssoRefEntryId > 0) {
                try {
                    val originalRefId = password.ssoRefEntryId
                    val originalId = password.id
                    val currentId = passwordIdMap[originalId]

                    if (currentId != null) {
                        val newRefId = passwordIdMap[originalRefId]
                        val existingEntry = passwordRepository.getPasswordEntryById(currentId)

                        if (existingEntry != null) {
                            if (newRefId != null) {
                                if (newRefId != existingEntry.ssoRefEntryId) {
                                    val updatedEntry = existingEntry.copy(ssoRefEntryId = newRefId)
                                    passwordRepository.updatePasswordEntry(updatedEntry)
                                    android.util.Log.d(
                                        logTag,
                                        "Updated ssoRefEntryId from $originalRefId to $newRefId"
                                    )
                                }
                            } else if (existingEntry.ssoRefEntryId != null) {
                                val updatedEntry = existingEntry.copy(ssoRefEntryId = null)
                                passwordRepository.updatePasswordEntry(updatedEntry)
                                android.util.Log.w(
                                    logTag,
                                    "Cleared invalid ssoRefEntryId $originalRefId (referenced password not found)"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(logTag, "Failed to update ssoRefEntryId: ${e.message}")
                }
            }
        }

        if (content.customFieldsMap.isNotEmpty()) {
            val customFieldDao = PasswordDatabase.getDatabase(context).customFieldDao()
            var customFieldCount = 0

            content.customFieldsMap.forEach { (originalId, fields) ->
                val newId = passwordIdMap[originalId]
                if (newId != null && fields.isNotEmpty()) {
                    try {
                        fields.forEachIndexed { index, fieldBackup ->
                            val customField = CustomField(
                                id = 0,
                                entryId = newId,
                                title = fieldBackup.title,
                                value = fieldBackup.value,
                                isProtected = fieldBackup.isProtected,
                                sortOrder = index
                            )
                            customFieldDao.insert(customField)
                            customFieldCount++
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(logTag, "Failed to restore custom fields for password $originalId -> $newId: ${e.message}")
                    }
                }
            }

            if (customFieldCount > 0) {
                android.util.Log.d(logTag, "Restored $customFieldCount custom fields")
            }
        }

        // 附件恢复：优先使用 portable 格式，它会在当前设备重新生成 localPath/wrappedCek；
        // 旧 attachments 格式只作为同机/旧备份兼容 fallback。
        // 对应 spec Requirement 9.5 / 9.6。
        if (content.portableAttachments.isNotEmpty) {
            val attachmentDao = PasswordDatabase.getDatabase(context).attachmentDao()
            var attachmentRestored = 0
            var attachmentSkipped = 0
            var attachmentMissingPayload = 0
            var attachmentUnmappedParent = 0
            val now = System.currentTimeMillis()
            content.portableAttachments.entries.forEach { entry ->
                val originalPasswordId = entry.parentPasswordId ?: return@forEach
                val mappedParentId = passwordIdMap[originalPasswordId]
                if (mappedParentId == null) {
                    attachmentUnmappedParent++
                    return@forEach
                }
                val payload = content.portableAttachments.payloads[entry.payloadPath]
                if (payload == null || !payload.isFile) {
                    attachmentMissingPayload++
                    return@forEach
                }
                val existingForParent = attachmentDao.getAllByParent(mappedParentId)
                val duplicate = existingForParent.any { existing ->
                    existing.fileName == entry.fileName &&
                        existing.sizeBytes == entry.sizeBytes &&
                        existing.sha256Hex != null &&
                        existing.sha256Hex == entry.sha256Hex
                }
                if (duplicate) {
                    attachmentSkipped++
                    return@forEach
                }
                try {
                    val attachment = takagi.ru.monica.attachments.backup.PortableAttachmentBackup
                        .materialize(context, entry, payload, mappedParentId, now)
                    attachmentDao.insert(attachment)
                    attachmentRestored++
                } catch (e: Exception) {
                    android.util.Log.w(
                        logTag,
                        "Portable attachment restore failed for ${entry.payloadPath} -> parent $mappedParentId: ${e.message}"
                    )
                }
            }
            android.util.Log.d(
                logTag,
                "Restored password portable attachments: restored=$attachmentRestored skipped=$attachmentSkipped missingPayload=$attachmentMissingPayload unmappedParent=$attachmentUnmappedParent"
            )
        } else if (content.attachments.isNotEmpty()) {
            val attachmentDao = PasswordDatabase.getDatabase(context).attachmentDao()
            val storageDir = java.io.File(context.filesDir, "secure_attachments")
            var attachmentRestored = 0
            var attachmentSkipped = 0
            var attachmentMissingBlob = 0
            var attachmentUnmappedParent = 0
            val now = System.currentTimeMillis()
            content.attachments.forEach { entry ->
                val originalPasswordId = entry.parentPasswordId ?: return@forEach
                val mappedParentId = passwordIdMap[originalPasswordId]
                if (mappedParentId == null) {
                    attachmentUnmappedParent++
                    return@forEach
                }
                val blob = java.io.File(storageDir, entry.localPath)
                if (!blob.isFile) {
                    attachmentMissingBlob++
                    return@forEach
                }
                val existingForParent = attachmentDao.getAllByParent(mappedParentId)
                val duplicate = existingForParent.any { existing ->
                    existing.localPath == entry.localPath ||
                        (existing.fileName == entry.fileName &&
                            existing.sizeBytes == entry.sizeBytes &&
                            existing.sha256Hex != null &&
                            existing.sha256Hex == entry.sha256Hex)
                }
                if (duplicate) {
                    attachmentSkipped++
                    return@forEach
                }
                val attachment = with(
                    takagi.ru.monica.attachments.backup.AttachmentBackupCodec
                ) { entry.toAttachment(now) }.copy(parentPasswordId = mappedParentId)
                try {
                    attachmentDao.insert(attachment)
                    attachmentRestored++
                } catch (e: Exception) {
                    android.util.Log.w(
                        logTag,
                        "Legacy attachment upsert failed for ${entry.localPath} -> parent $mappedParentId: ${e.message}"
                    )
                }
            }
            android.util.Log.d(
                logTag,
                "Restored password legacy attachments: restored=$attachmentRestored skipped=$attachmentSkipped missingBlob=$attachmentMissingBlob unmappedParent=$attachmentUnmappedParent"
            )
        }

        if (passwordHistory.isNotEmpty()) {
            var historyCount = 0
            passwordHistory.forEach { historyEntry ->
                val mappedEntryId = passwordIdMap[historyEntry.entryId] ?: return@forEach
                try {
                    passwordRepository.insertPasswordHistory(
                        PasswordHistoryEntry(
                            entryId = mappedEntryId,
                            password = encryptImportedPasswordForDisplay(
                                historyEntry.password,
                                securityManager,
                                logTag
                            ),
                            lastUsedAt = java.util.Date(historyEntry.lastUsedAt)
                        )
                    )
                    historyCount++
                } catch (e: Exception) {
                    android.util.Log.w(
                        logTag,
                        "Failed to restore password history for password ${historyEntry.entryId} -> $mappedEntryId: ${e.message}"
                    )
                }
            }

            if (historyCount > 0) {
                android.util.Log.d(logTag, "Restored $historyCount password history entries")
            }
        }

        var secureItemCount = 0
        var secureItemSkipped = 0
        var secureItemFailed = 0
        val secureItemIdMap = mutableMapOf<Long, Long>()
        val failedSecureItemDetails = mutableListOf<String>()
        var passkeyCountImported = 0
        var passkeySkipped = 0
        var passkeyFailed = 0
        val json = Json { ignoreUnknownKeys = true }

        secureItems.forEach { exportItem ->
            try {
                val itemType = ItemType.valueOf(exportItem.itemType)
                val existingItem = secureItemRepository.findDuplicateSecureItem(
                    itemType,
                    exportItem.itemData,
                    exportItem.title,
                    localOnly = localOnlyDedup
                )

                if (existingItem == null) {
                    var finalItemData = if (itemType == ItemType.TOTP) {
                        PortableTotpBackupCodec.encode(
                            storedItemData = exportItem.itemData,
                            entryTitle = exportItem.title,
                            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
                        )
                    } else {
                        exportItem.itemData
                    }
                    if (itemType == ItemType.TOTP) {
                        try {
                            val totpData = TotpDataResolver.parseStoredItemData(
                                itemData = finalItemData,
                                fallbackIssuer = exportItem.title,
                                decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
                            ) ?: throw IllegalArgumentException("Unable to parse TOTP data")
                            if (totpData.boundPasswordId != null && totpData.boundPasswordId > 0) {
                                val newBoundId = passwordIdMap[totpData.boundPasswordId]
                                if (newBoundId != null) {
                                    val updatedTotpData = totpData.copy(boundPasswordId = newBoundId)
                                    val updatedJson = json.encodeToString(updatedTotpData)
                                    finalItemData = updatedJson
                                    android.util.Log.d(logTag, "Updated TOTP binding to Password ID $newBoundId")
                                } else {
                                    android.util.Log.w(logTag, "Could not find new password ID for TOTP binding: oldId=${totpData.boundPasswordId}")
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w(logTag, "Failed to parse/update TOTP data: ${e.message}")
                        }
                    }

                    val secureItem = takagi.ru.monica.data.SecureItem(
                        id = 0,
                        itemType = itemType,
                        title = exportItem.title,
                        itemData = encodeSecureItemDataForLocalStorage(
                            itemType = itemType,
                            itemData = finalItemData,
                            securityManager = securityManager
                        ),
                        notes = exportItem.notes,
                        isFavorite = exportItem.isFavorite,
                        sortOrder = exportItem.sortOrder,
                        imagePaths = exportItem.imagePaths,
                        createdAt = java.util.Date(exportItem.createdAt),
                        updatedAt = java.util.Date(exportItem.updatedAt),
                        categoryId = exportItem.categoryId
                    )
                    val newId = secureItemRepository.insertItem(secureItem)
                    if (newId > 0L) {
                        secureItemIdMap[exportItem.id] = newId
                    }
                    secureItemCount++
                } else {
                    secureItemIdMap[exportItem.id] = existingItem.id
                    secureItemSkipped++
                }
            } catch (e: Exception) {
                secureItemFailed++
                val detail = "${exportItem.title} (${exportItem.itemType}): ${e.message}"
                failedSecureItemDetails.add(detail)
                android.util.Log.e(logTag, "Failed to import secure item: ${e.message}")
            }
        }

        if (passkeys.isNotEmpty()) {
            val passkeyDao = PasswordDatabase.getDatabase(context).passkeyDao()
            passkeys.forEach { passkey ->
                try {
                    val existing = if (localOnlyDedup) {
                        passkeyDao.getLocalPasskeyById(passkey.credentialId)
                    } else {
                        passkeyDao.getPasskeyById(passkey.credentialId)
                    }
                    if (existing == null) {
                        val mappedBoundPasswordId = passkey.boundPasswordId?.let { oldId ->
                            passwordIdMap[oldId]
                        }
                        passkeyDao.insert(
                            PasskeyPrivateKeyStore.protectPasskey(
                                context,
                                passkey.copy(boundPasswordId = mappedBoundPasswordId)
                            )
                        )
                        passkeyCountImported++
                    } else {
                        passkeySkipped++
                    }
                } catch (e: Exception) {
                    passkeyFailed++
                    android.util.Log.e(
                        logTag,
                        "Failed to import passkey ${passkey.credentialId}: ${e.message}",
                        e
                    )
                }
            }
        }

        restoreSecureItemAttachments(
            context = context,
            content = content,
            secureItemIdMap = secureItemIdMap,
            logTag = logTag
        )

        var steamAccountImported = 0
        var steamAccountFailed = 0
        if (steamMaFiles.isNotEmpty()) {
            val steamRepository = SteamAccountRepository(
                SteamDatabase.getDatabase(context).steamAccountDao(),
                securityManager
            )
            steamMaFiles.forEach { payload ->
                try {
                    steamRepository.upsertFromMaFile(payload)
                    steamAccountImported++
                } catch (e: Exception) {
                    steamAccountFailed++
                    android.util.Log.e(
                        logTag,
                        "Failed to import Steam maFile for steamId=${payload.steamId}: ${e.message}"
                    )
                }
            }
        }

        android.util.Log.d(logTag, "===== 导入统计 =====")
        android.util.Log.d(logTag, "成功导入密码: $passwordCount")
        android.util.Log.d(logTag, "跳过重复密码: $passwordSkipped")
        android.util.Log.d(logTag, "导入失败密码: $passwordFailed")
        android.util.Log.d(logTag, "成功导入安全项: $secureItemCount")
        android.util.Log.d(logTag, "跳过重复安全项: $secureItemSkipped")
        android.util.Log.d(logTag, "导入失败安全项: $secureItemFailed")
        android.util.Log.d(logTag, "成功导入通行密钥: $passkeyCountImported")
        android.util.Log.d(logTag, "跳过重复通行密钥: $passkeySkipped")
        android.util.Log.d(logTag, "导入失败通行密钥: $passkeyFailed")
        android.util.Log.d(logTag, "成功导入Steam maFile: $steamAccountImported")
        android.util.Log.d(logTag, "导入失败Steam maFile: $steamAccountFailed")
        android.util.Log.d(logTag, "总计: ${passwordCount + passwordSkipped + passwordFailed} vs 备份中: ${passwords.size}")

        return RestoreApplyStats(
            passwordImported = passwordCount,
            passwordSkipped = passwordSkipped,
            passwordFailed = passwordFailed,
            secureItemImported = secureItemCount,
            secureItemSkipped = secureItemSkipped,
            secureItemFailed = secureItemFailed,
            passkeyImported = passkeyCountImported,
            passkeySkipped = passkeySkipped,
            passkeyFailed = passkeyFailed,
            steamAccountImported = steamAccountImported,
            steamAccountFailed = steamAccountFailed,
            failedPasswordDetails = failedPasswordDetails,
            failedSecureItemDetails = failedSecureItemDetails
        )
    }
}

private suspend fun restoreSecureItemAttachments(
    context: Context,
    content: BackupContent,
    secureItemIdMap: Map<Long, Long>,
    logTag: String
) {
    val attachmentDao = PasswordDatabase.getDatabase(context).attachmentDao()
    var restored = 0
    var skipped = 0
    var missingPayload = 0
    var unmappedParent = 0
    val now = System.currentTimeMillis()

    if (content.portableAttachments.isNotEmpty) {
        content.portableAttachments.entries.forEach { entry ->
            val originalSecureItemId = entry.parentSecureItemId ?: return@forEach
            val mappedSecureItemId = secureItemIdMap[originalSecureItemId]
            if (mappedSecureItemId == null) {
                unmappedParent++
                return@forEach
            }
            val payload = content.portableAttachments.payloads[entry.payloadPath]
            if (payload == null || !payload.isFile) {
                missingPayload++
                return@forEach
            }
            val existing = attachmentDao.getAllBySecureItem(mappedSecureItemId)
            val duplicate = existing.any { attachment ->
                attachment.fileName == entry.fileName &&
                    attachment.sizeBytes == entry.sizeBytes &&
                    attachment.sha256Hex != null &&
                    attachment.sha256Hex == entry.sha256Hex
            }
            if (duplicate) {
                skipped++
                return@forEach
            }
            runCatching {
                takagi.ru.monica.attachments.backup.PortableAttachmentBackup.materialize(
                    context = context,
                    entry = entry,
                    payloadFile = payload,
                    mappedOwner = takagi.ru.monica.attachments.model.AttachmentOwner.secureItem(
                        mappedSecureItemId
                    ),
                    now = now
                )
            }.onSuccess { attachment ->
                attachmentDao.insert(attachment)
                restored++
            }.onFailure { error ->
                android.util.Log.w(
                    logTag,
                    "Portable secure-item attachment restore failed for ${entry.payloadPath}: ${error.message}"
                )
            }
        }
        content.portableAttachments.payloads.values.distinct().forEach { payload ->
            runCatching { payload.delete() }
        }
    } else if (content.attachments.isNotEmpty()) {
        val storageDir = java.io.File(context.filesDir, "secure_attachments")
        content.attachments.forEach { entry ->
            val originalSecureItemId = entry.parentSecureItemId ?: return@forEach
            val mappedSecureItemId = secureItemIdMap[originalSecureItemId]
            if (mappedSecureItemId == null) {
                unmappedParent++
                return@forEach
            }
            val blob = java.io.File(storageDir, entry.localPath)
            if (!blob.isFile) {
                missingPayload++
                return@forEach
            }
            val existing = attachmentDao.getAllBySecureItem(mappedSecureItemId)
            val duplicate = existing.any { attachment ->
                attachment.localPath == entry.localPath ||
                    (attachment.fileName == entry.fileName &&
                        attachment.sizeBytes == entry.sizeBytes &&
                        attachment.sha256Hex != null &&
                        attachment.sha256Hex == entry.sha256Hex)
            }
            if (duplicate) {
                skipped++
                return@forEach
            }
            val attachment = with(takagi.ru.monica.attachments.backup.AttachmentBackupCodec) {
                entry.toAttachment(now)
            }.copy(
                parentPasswordId = null,
                parentSecureItemId = mappedSecureItemId
            )
            runCatching { attachmentDao.insert(attachment) }
                .onSuccess { restored++ }
                .onFailure { error ->
                    android.util.Log.w(
                        logTag,
                        "Legacy secure-item attachment restore failed for ${entry.localPath}: ${error.message}"
                    )
                }
        }
    }

    if (restored + skipped + missingPayload + unmappedParent > 0) {
        android.util.Log.d(
            logTag,
            "Restored secure-item attachments: restored=$restored skipped=$skipped missingPayload=$missingPayload unmappedParent=$unmappedParent"
        )
    }
}

private fun encodeSecureItemDataForLocalStorage(
    itemType: ItemType,
    itemData: String,
    securityManager: SecurityManager
): String {
    if (itemData.isBlank()) return itemData
    if (
        itemType != ItemType.TOTP &&
        itemType != ItemType.BANK_CARD &&
        itemType != ItemType.DOCUMENT
    ) {
        return itemData
    }
    if (securityManager.looksLikeMonicaCiphertext(itemData)) {
        return itemData
    }
    return securityManager.encryptDataLegacyCompat(itemData)
}

private fun encryptImportedPasswordForDisplay(
    plainPassword: String,
    securityManager: SecurityManager,
    logTag: String
): String {
    val portablePassword = securityManager.decryptDataIfMonicaCiphertext(plainPassword)
    val primaryEncrypted = securityManager.encryptData(portablePassword)
    val primaryReadable = runCatching { securityManager.decryptData(primaryEncrypted) }
        .getOrNull()
        ?.let { it == portablePassword }
        ?: false
    if (primaryReadable) {
        return primaryEncrypted
    }

    android.util.Log.w(
        logTag,
        "Imported password encrypted payload is not immediately readable; fallback to legacy V1"
    )
    val legacyEncrypted = securityManager.encryptDataLegacyCompat(portablePassword)
    val legacyReadable = runCatching { securityManager.decryptData(legacyEncrypted) }
        .getOrNull()
        ?.let { it == portablePassword }
        ?: false
    return if (legacyReadable) {
        legacyEncrypted
    } else {
        android.util.Log.w(
            logTag,
            "Legacy fallback is still unreadable; keep primary encrypted payload"
        )
        primaryEncrypted
    }
}

private fun encryptImportedAuthenticatorKey(
    value: String,
    securityManager: SecurityManager
): String {
    if (value.isBlank()) return ""
    val plainValue = securityManager.decryptDataIfMonicaCiphertext(value)
    return securityManager.encryptDataLegacyCompat(plainValue)
}
