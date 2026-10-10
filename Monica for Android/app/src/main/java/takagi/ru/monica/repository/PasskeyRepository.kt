package takagi.ru.monica.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import takagi.ru.monica.data.PasskeyDao
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.passkey.PasskeyCredentialIdCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.security.SecurityManager
import java.security.KeyStore

/**
 * Passkey 数据仓库
 * 
 * 提供 Passkey 数据的访问接口，封装 DAO 操作
 * 负责在删除 Passkey 时同步清理 Android Keystore 中的私钥
 */
class PasskeyRepository(
    private val passkeyDao: PasskeyDao,
    private val mdbxRepository: MdbxRepository? = null,
    private val context: Context? = null
) {
    
    companion object {
        private const val TAG = "PasskeyRepository"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
    
    // ==================== 查询操作 ====================
    
    /**
     * 获取所有 Passkey（响应式）
     */
    fun getAllPasskeys(): Flow<List<PasskeyEntry>> = passkeyDao.getAllPasskeys()
    
    /**
     * 获取所有 Passkey（同步）
     */
    suspend fun getAllPasskeysSync(): List<PasskeyEntry> = passkeyDao.getAllPasskeysSync()
    
    /**
     * 根据凭据 ID 获取 Passkey
     */
    suspend fun getPasskeyById(credentialId: String): PasskeyEntry? = 
        passkeyDao.getPasskeyById(credentialId)

    suspend fun getPasskeyByRecordId(recordId: Long): PasskeyEntry? =
        passkeyDao.getPasskeyByRecordId(recordId)

    suspend fun normalizeLegacyDetachedKeePassPasskey(
        passkey: PasskeyEntry,
        databaseExists: suspend (Long) -> Boolean = { false }
    ): PasskeyEntry {
        if (!isLegacyDetachedKeePassPasskey(passkey, databaseExists)) return passkey
        if (passkey.hasPersistentId()) {
            passkeyDao.clearKeePassBindingForRecordIds(listOf(passkey.id))
        } else {
            passkeyDao.clearKeePassBindingForCredentialIds(listOf(passkey.credentialId))
        }
        return resolveExistingPasskey(passkey) ?: passkey.copy(
            keepassDatabaseId = null,
            keepassGroupPath = null
        )
    }
    
    /**
     * 根据域名获取 Passkeys
     */
    fun getPasskeysByRpId(rpId: String): Flow<List<PasskeyEntry>> = 
        passkeyDao.getPasskeysByRpId(rpId)
    
    /**
     * 根据域名获取 Passkeys（同步）
     */
    suspend fun getPasskeysByRpIdSync(rpId: String): List<PasskeyEntry> = 
        passkeyDao.getPasskeysByRpIdSync(rpId)
    
    /**
     * 搜索 Passkey
     */
    fun searchPasskeys(query: String): Flow<List<PasskeyEntry>> = 
        passkeyDao.searchPasskeys(query)
    
    /**
     * 获取可发现的 Passkeys（用于 Credential Provider）
     */
    suspend fun getDiscoverablePasskeys(): List<PasskeyEntry> = 
        passkeyDao.getDiscoverablePasskeys()
    
    /**
     * 获取指定域名的可发现 Passkeys
     */
    suspend fun getDiscoverablePasskeysByRpId(rpId: String): List<PasskeyEntry> = 
        passkeyDao.getDiscoverablePasskeysByRpId(rpId)
    
    /**
     * 获取 Passkey 总数
     */
    fun getPasskeyCount(): Flow<Int> = passkeyDao.getPasskeyCount()
    
    /**
     * 获取未备份的 Passkeys
     */
    suspend fun getUnbackedPasskeys(): List<PasskeyEntry> = 
        passkeyDao.getUnbackedPasskeys()

    suspend fun repairLegacyDetachedKeePassPasskeys(
        databaseExists: suspend (Long) -> Boolean = { false }
    ): Int {
        val staleRecordIds = passkeyDao.getAllPasskeysSync()
            .filter { isLegacyDetachedKeePassPasskey(it, databaseExists) }
            .mapNotNull { it.id.takeIf { recordId -> recordId > 0L } }
        if (staleRecordIds.isEmpty()) return 0

        passkeyDao.clearKeePassBindingForRecordIds(staleRecordIds)
        Log.i(TAG, "Detached legacy KeePass-local passkey bindings: count=${staleRecordIds.size}")
        return staleRecordIds.size
    }

    /**
     * 获取绑定到指定密码的 Passkeys
     */
    fun getPasskeysByBoundPasswordId(passwordId: Long): Flow<List<PasskeyEntry>> =
        passkeyDao.getByBoundPasswordId(passwordId)
    
    // ==================== 写入操作 ====================
    
    /**
     * 保存 Passkey（插入或更新）
     */
    /** Insert a separate record; copies must never replace an existing credential or delete the source. */
    suspend fun copyPasskey(passkey: PasskeyEntry): Long {
        require(passkey.id == 0L)
        passkey.mdbxDatabaseId?.let { databaseId ->
            val store = requireNotNull(mdbxRepository) { "MDBX repository is unavailable" }
            store.requireRoomMirrorAllowed(databaseId)
            val normalized = PasskeyCredentialIdCodec.normalize(passkey.credentialId) ?: passkey.credentialId
            check(store.readStoredEntries(databaseId).none { entry ->
                !entry.deleted && entry.entryType.equals("passkey", ignoreCase = true) &&
                    (entry.entryId == "passkey:${passkey.credentialId}" || runCatching {
                        val id = org.json.JSONObject(entry.payloadJson).optString("credential_id")
                        (PasskeyCredentialIdCodec.normalize(id) ?: id) == normalized
                    }.getOrDefault(false))
            }) { "Target already contains this Passkey" }
        }
        val protected = protectPrivateKeyForRoom(passkey)
        return try {
            commitMirrorThenRoom(
                mirrorCommit = { mdbxRepository?.upsertPasskey(passkey) },
                roomCommit = { passkeyDao.insertCopy(protected) },
                rollbackMirror = { mdbxRepository?.deletePasskey(passkey) }
            )
        } catch (error: Throwable) {
            cleanupPrivateKeyIfUnreferenced(protected.privateKeyAlias)
            throw error
        }
    }

    suspend fun savePasskey(passkey: PasskeyEntry) {
        passkey.mdbxDatabaseId?.let { mdbxRepository?.requireRoomMirrorAllowed(it) }
        val protected = protectPrivateKeyForRoom(passkey)
        try {
            commitMirrorThenRoom(
                mirrorCommit = { mdbxRepository?.upsertPasskey(passkey) },
                roomCommit = { passkeyDao.insert(protected) },
                rollbackMirror = { mdbxRepository?.deletePasskey(passkey) }
            )
        } catch (error: Throwable) {
            cleanupPrivateKeyIfUnreferenced(protected.privateKeyAlias)
            throw error
        }
    }
    
    /**
     * 批量保存 Passkeys
     */
    suspend fun saveAllPasskeys(passkeys: List<PasskeyEntry>) {
        passkeys.mapNotNull { it.mdbxDatabaseId }.distinct().forEach { mdbxRepository?.requireRoomMirrorAllowed(it) }
        val protected = passkeys.map(::protectPrivateKeyForRoom)
        try {
            commitMirrorThenRoom(
                mirrorCommit = { mdbxRepository?.upsertPasskeys(passkeys) },
                roomCommit = { passkeyDao.insertAll(protected) },
                rollbackMirror = { mdbxRepository?.deletePasskeys(passkeys) }
            )
        } catch (error: Throwable) {
            protected.forEach { cleanupPrivateKeyIfUnreferenced(it.privateKeyAlias) }
            throw error
        }
    }
    
    /**
     * 更新 Passkey
     */
    suspend fun updatePasskey(passkey: PasskeyEntry) {
        passkey.mdbxDatabaseId?.let { mdbxRepository?.requireRoomMirrorAllowed(it) }
        val existing = resolveExistingPasskey(passkey)
        val normalized = if (existing == null) {
            passkey
        } else {
            normalizeBitwardenSyncState(existing, passkey)
        }
        val protected = protectPrivateKeyForRoom(normalized)
        try {
            commitMirrorThenRoom(
                mirrorCommit = {
                    if (normalized.mdbxDatabaseId != null) {
                        mdbxRepository?.upsertPasskey(normalized)
                    }
                    if (
                        existing?.mdbxDatabaseId != null &&
                        existing.mdbxDatabaseId != normalized.mdbxDatabaseId
                    ) {
                        mdbxRepository?.deletePasskey(existing)
                    }
                },
                roomCommit = {
                    takagi.ru.monica.passkey.PasskeyCipherFolderIntent.recordMove(context, existing, protected) {
                        passkeyDao.update(protected)
                    }
                },
                rollbackMirror = {
                    if (normalized.mdbxDatabaseId != null) {
                        mdbxRepository?.deletePasskey(normalized)
                    }
                    if (existing?.mdbxDatabaseId != null) {
                        mdbxRepository?.upsertPasskey(existing)
                    }
                }
            )
        } catch (error: Throwable) {
            cleanupPrivateKeyIfUnreferenced(protected.privateKeyAlias)
            throw error
        }
    }

    suspend fun protectPlaintextPrivateKeys(): Int {
        val appContext = context?.applicationContext ?: return 0
        val securityManager = SecurityManager(appContext)
        var migrated = 0
        passkeyDao.getAllPasskeysSync().forEach { passkey ->
            val protected = PasskeyPrivateKeyStore.protectPasskey(appContext, passkey)
            val normalized = if (
                protected.syncStatus != "REFERENCE" &&
                !PasskeyPrivateKeyStore.hasUsablePrivateKey(securityManager, protected.privateKeyAlias)
            ) {
                protected.copy(syncStatus = "REFERENCE")
            } else {
                protected
            }
            if (normalized != passkey) {
                passkeyDao.update(normalized)
                migrated++
            }
        }
        if (migrated > 0) {
            Log.i(TAG, "Protected or quarantined passkey private keys: count=$migrated")
        }
        return migrated
    }

    suspend fun updateMdbxDatabaseForPasskeys(
        recordIds: List<Long>,
        databaseId: Long?,
        folderId: String? = null
    ) {
        if (recordIds.isEmpty()) return
        val existingPasskeys = recordIds.mapNotNull { passkeyDao.getPasskeyByRecordId(it) }
        val passkeysForMdbx = if (databaseId != null) {
            existingPasskeys.map { passkey ->
                passkey.copy(
                    categoryId = null,
                    mdbxDatabaseId = databaseId,
                    mdbxFolderId = folderId,
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    bitwardenVaultId = null,
                    bitwardenFolderId = null,
                    bitwardenCipherId = null,
                    syncStatus = "NONE"
                )
            }
        } else {
            emptyList()
        }
        val previousMdbxPasskeys = existingPasskeys.filter { it.mdbxDatabaseId != null }
        commitMirrorThenRoom(
            mirrorCommit = {
                mdbxRepository?.upsertPasskeys(passkeysForMdbx)
                mdbxRepository?.deletePasskeys(
                    previousMdbxPasskeys.filter { it.mdbxDatabaseId != databaseId }
                )
            },
            roomCommit = {
                passkeyDao.updateMdbxDatabaseForPasskeys(
                    recordIds,
                    databaseId,
                    folderId.takeIf { databaseId != null }
                )
            },
            rollbackMirror = {
                mdbxRepository?.deletePasskeys(passkeysForMdbx)
                mdbxRepository?.upsertPasskeys(previousMdbxPasskeys)
            }
        )
    }

    suspend fun syncKeePassPasskeys(
        databaseId: Long,
        importedPasskeys: List<PasskeyEntry>
    ) {
        val existingPasskeys = passkeyDao.getKeePassCompatPasskeysByDatabaseId(databaseId)
        val mergeResult = mergeKeePassImportedPasskeys(
            databaseId = databaseId,
            importedPasskeys = importedPasskeys,
            existingPasskeys = existingPasskeys
        )
        if (
            mergeResult.staleRecordIds.isEmpty() &&
            existingPasskeys.matchesKeePassImportedPasskeyMirror(databaseId, mergeResult.mergedPasskeys)
        ) {
            return
        }
        if (mergeResult.staleRecordIds.isNotEmpty()) {
            passkeyDao.deleteByRecordIds(mergeResult.staleRecordIds)
        }

        if (mergeResult.mergedPasskeys.isNotEmpty()) {
            passkeyDao.insertAll(mergeResult.mergedPasskeys)
            passkeyDao.deleteKeePassCompatPasskeysNotIn(
                databaseId = databaseId,
                credentialIds = mergeResult.mergedPasskeys.map { it.credentialId }
            )
        } else {
            passkeyDao.deleteAllKeePassCompatPasskeysByDatabaseId(databaseId)
        }
    }

    /**
     * 更新绑定的密码 ID
     */
    suspend fun updateBoundPasswordId(recordId: Long, passwordId: Long?) =
        passkeyDao.updateBoundPasswordIdByRecordId(recordId, passwordId)
    
    /**
     * 更新使用记录
     */
    suspend fun updateUsage(recordId: Long, signCount: Long) =
        passkeyDao.updateUsageByRecordId(recordId, System.currentTimeMillis(), signCount)
    
    /**
     * 标记为已备份
     */
    suspend fun markAsBackedUp(credentialId: String) = passkeyDao.markAsBackedUp(credentialId)
    
    /**
     * 批量标记为已备份
     */
    suspend fun markAllAsBackedUp(credentialIds: List<String>) = 
        passkeyDao.markAllAsBackedUp(credentialIds)
    
    // ==================== 删除操作 ====================
    
    /**
     * 删除 Passkey 并清理 Keystore 私钥
     */
    suspend fun deletePasskey(passkey: PasskeyEntry) {
        deletePasskeyLocalOnly(passkey)
    }
    
    suspend fun deletePasskeyByRecordId(recordId: Long) {
        val passkey = passkeyDao.getPasskeyByRecordId(recordId)
        if (passkey != null) {
            deletePasskeyLocalOnly(passkey)
            return
        }
        passkeyDao.deleteByRecordId(recordId)
    }
    
    /**
     * 删除指定域名的所有 Passkeys 并清理 Keystore
     */
    suspend fun deletePasskeysByRpId(rpId: String) {
        // 获取所有匹配的 Passkey
        val passkeys = passkeyDao.getPasskeysByRpIdSync(rpId)
        commitMirrorThenRoom(
            mirrorCommit = { mdbxRepository?.deletePasskeys(passkeys) },
            roomCommit = { passkeyDao.deleteByRpId(rpId) },
            rollbackMirror = { mdbxRepository?.upsertPasskeys(passkeys) }
        )
        for (passkey in passkeys) {
            cleanupPrivateKeyIfUnreferenced(passkey.privateKeyAlias)
            logAudit("PASSKEY_DELETED", "${passkey.credentialId}|rpId=$rpId")
        }
    }
    
    /**
     * 清空所有 Passkeys 并清理 Keystore
     */
    suspend fun deleteAllPasskeys() {
        // 获取所有 Passkey
        val passkeys = passkeyDao.getAllPasskeysSync()
        commitMirrorThenRoom(
            mirrorCommit = { mdbxRepository?.deletePasskeys(passkeys) },
            roomCommit = { passkeyDao.deleteAll() },
            rollbackMirror = { mdbxRepository?.upsertPasskeys(passkeys) }
        )
        for (passkey in passkeys) {
            cleanupPrivateKey(passkey.privateKeyAlias)
        }
        logAudit("PASSKEY_CLEAR_ALL", "count=${passkeys.size}")
    }

    suspend fun deletePasskeyLocalOnly(passkey: PasskeyEntry) {
        commitMirrorThenRoom(
            mirrorCommit = { mdbxRepository?.deletePasskey(passkey) },
            roomCommit = { passkeyDao.delete(passkey) },
            rollbackMirror = { mdbxRepository?.upsertPasskey(passkey) }
        )
        context?.let { takagi.ru.monica.passkey.PasskeyCipherFolderIntent.discard(it, passkey) }
        cleanupPrivateKeyIfUnreferenced(passkey.privateKeyAlias)
        logAudit("PASSKEY_DELETED", "${passkey.credentialId}|rpId=${passkey.rpId}")
    }
    
    // ==================== Keystore 管理 ====================
    
    /**
     * 从 Android Keystore 删除私钥
     */
    private fun deletePrivateKey(keyAlias: String) {
        if (keyAlias.isBlank()) {
            Log.w(TAG, "Empty key alias, skipping Keystore cleanup")
            return
        }
        
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
                takagi.ru.monica.passkey.PasskeyPortabilityCache.shared.invalidate(keyAlias)
                Log.d(TAG, "Deleted private key from Keystore")
            } else {
                Log.w(TAG, "Key alias not found in Keystore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete private key", e)
        }
    }
    
    // ==================== 审计日志 ====================
    
    /**
     * 记录审计日志
     * TODO: 可扩展为写入文件或远程日志服务
     */
    fun logAudit(action: String, details: String) {
        Log.i("PasskeyAudit", "[$action] detailsPresent=${details.isNotBlank()}")
    }

    private fun cleanupPrivateKey(keyReferenceOrAlias: String) {
        context?.applicationContext?.let { appContext ->
            PasskeyPrivateKeyStore.removeIfProtectedReference(appContext, keyReferenceOrAlias)
        }
        if (!PasskeyPrivateKeyStore.isProtectedReference(keyReferenceOrAlias)) {
            deletePrivateKey(keyReferenceOrAlias)
        }
    }

    private suspend fun cleanupPrivateKeyIfUnreferenced(keyReferenceOrAlias: String) {
        if (keyReferenceOrAlias.isBlank()) return
        if (passkeyDao.countByPrivateKeyAlias(keyReferenceOrAlias) == 0) {
            cleanupPrivateKey(keyReferenceOrAlias)
        }
    }

    private fun protectPrivateKeyForRoom(passkey: PasskeyEntry): PasskeyEntry {
        val appContext = context?.applicationContext ?: return passkey
        return PasskeyPrivateKeyStore.protectPasskey(appContext, passkey)
    }

    private suspend fun resolveExistingPasskey(passkey: PasskeyEntry): PasskeyEntry? {
        return if (passkey.hasPersistentId()) {
            passkeyDao.getPasskeyByRecordId(passkey.id)
        } else {
            passkeyDao.getPasskeyById(passkey.credentialId)
        }
    }

    private suspend fun isLegacyDetachedKeePassPasskey(
        passkey: PasskeyEntry,
        databaseExists: suspend (Long) -> Boolean
    ): Boolean {
        val keepassDatabaseId = passkey.keepassDatabaseId ?: return false
        if (passkey.bitwardenVaultId != null || !passkey.bitwardenCipherId.isNullOrBlank()) return false
        if (passkey.categoryId != null) return true
        return !databaseExists(keepassDatabaseId) && passkey.keepassGroupPath.isNullOrBlank()
    }

    private fun normalizeBitwardenSyncState(
        existing: PasskeyEntry,
        updated: PasskeyEntry
    ): PasskeyEntry {
        if (updated.syncStatus == "REFERENCE") return updated
        if (updated.bitwardenVaultId == null) return updated
        if (updated.bitwardenCipherId.isNullOrBlank()) return updated

        val keepsSameRemoteCipher =
            existing.bitwardenVaultId == updated.bitwardenVaultId &&
                existing.bitwardenCipherId == updated.bitwardenCipherId
        if (!keepsSameRemoteCipher) return updated

        val remoteRelevantChanged = PasskeyBitwardenSyncSnapshot.from(existing) !=
            PasskeyBitwardenSyncSnapshot.from(updated)
        if (!remoteRelevantChanged) return updated

        return updated.copy(syncStatus = "PENDING")
    }

    private data class PasskeyBitwardenSyncSnapshot(
        val bitwardenFolderId: String?,
        val rpId: String,
        val rpName: String,
        val userId: String,
        val userName: String,
        val userDisplayName: String,
        val publicKeyAlgorithm: Int,
        val privateKeyAlias: String,
        val isDiscoverable: Boolean,
        val notes: String,
        val signCount: Long,
        val createdAt: Long,
        val passkeyMode: String
    ) {
        companion object {
            fun from(entry: PasskeyEntry): PasskeyBitwardenSyncSnapshot {
                return PasskeyBitwardenSyncSnapshot(
                    bitwardenFolderId = entry.bitwardenFolderId,
                    rpId = entry.rpId,
                    rpName = entry.rpName,
                    userId = entry.userId,
                    userName = entry.userName,
                    userDisplayName = entry.userDisplayName,
                    publicKeyAlgorithm = entry.publicKeyAlgorithm,
                    privateKeyAlias = entry.privateKeyAlias,
                    isDiscoverable = entry.isDiscoverable,
                    notes = entry.notes,
                    signCount = entry.signCount,
                    createdAt = entry.createdAt,
                    passkeyMode = entry.passkeyMode
                )
            }
        }
    }
}

data class KeePassPasskeySyncMergeResult(
    val mergedPasskeys: List<PasskeyEntry>,
    val staleRecordIds: List<Long>
)

fun mergeKeePassImportedPasskeys(
    databaseId: Long,
    importedPasskeys: List<PasskeyEntry>,
    existingPasskeys: List<PasskeyEntry>
): KeePassPasskeySyncMergeResult {
    val existingGroups = existingPasskeys
        .filter { it.keepassDatabaseId == databaseId && it.passkeyMode == PasskeyEntry.MODE_KEEPASS_COMPAT }
        .groupBy { it.keepassCredentialKey() }
        .filterKeys { it != null }

    val canonicalExistingByKey = existingGroups.mapValues { (_, entries) ->
        entries.maxWith(compareBy<PasskeyEntry> { it.lastUsedAt }.thenBy { it.id })
    }
    val staleRecordIds = existingGroups.values.flatMap { entries ->
        val canonicalId = entries.maxWith(compareBy<PasskeyEntry> { it.lastUsedAt }.thenBy { it.id }).id
        entries.mapNotNull { entry ->
            entry.id.takeIf { it > 0L && it != canonicalId }
        }
    }

    val importedByKey = importedPasskeys
        .filter { it.passkeyMode == PasskeyEntry.MODE_KEEPASS_COMPAT }
        .groupBy { it.keepassCredentialKey() }
        .filterKeys { it != null }
        .mapValues { (_, entries) ->
            entries.maxWith(compareBy<PasskeyEntry> { it.lastUsedAt }.thenBy { it.id })
        }

    val merged = importedByKey.mapNotNull { (key, imported) ->
        key ?: return@mapNotNull null
        val existing = canonicalExistingByKey[key]
        if (existing == null) {
            imported.copy(
                id = 0L,
                keepassDatabaseId = databaseId,
                passkeyMode = PasskeyEntry.MODE_KEEPASS_COMPAT
            )
        } else {
            imported.copy(
                id = existing.id,
                boundPasswordId = existing.boundPasswordId,
                categoryId = existing.categoryId,
                isBackedUp = existing.isBackedUp || imported.isBackedUp,
                backupEligible = takagi.ru.monica.passkey.PasskeyBackupFlags.mergeEligibility(existing.backupEligible, imported.backupEligible),
                backupState = imported.backupState ?: existing.backupState,
                keepassDatabaseId = databaseId,
                passkeyMode = PasskeyEntry.MODE_KEEPASS_COMPAT
            )
        }
    }

    return KeePassPasskeySyncMergeResult(
        mergedPasskeys = merged,
        staleRecordIds = staleRecordIds
    )
}

private fun PasskeyEntry.keepassCredentialKey(): String? {
    return PasskeyCredentialIdCodec.normalize(credentialId)?.ifBlank { null }
}

private fun List<PasskeyEntry>.matchesKeePassImportedPasskeyMirror(
    databaseId: Long,
    mergedPasskeys: List<PasskeyEntry>
): Boolean {
    val existingByKey = mutableMapOf<String, PasskeyEntry>()
    filter {
        it.keepassDatabaseId == databaseId && it.passkeyMode == PasskeyEntry.MODE_KEEPASS_COMPAT
    }.forEach { entry ->
        val key = entry.keepassCredentialKey() ?: return false
        if (existingByKey.put(key, entry) != null) return false
    }

    if (existingByKey.size != mergedPasskeys.size) return false
    return mergedPasskeys.all { imported ->
        val key = imported.keepassCredentialKey() ?: return false
        existingByKey[key] == imported
    }
}
