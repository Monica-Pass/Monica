package takagi.ru.monica.data.bitwarden

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Bitwarden Vault DAO
 * 
 * 安全规则:
 * - 所有删除操作都是软删除，除非明确需要硬删除
 * - 敏感数据（令牌、密钥）的更新必须使用事务
 */
@Dao
interface BitwardenVaultDao {
    
    // === 查询操作 ===
    
    @Query("SELECT * FROM bitwarden_vaults ORDER BY is_default DESC, created_at DESC")
    fun getAllVaultsFlow(): Flow<List<BitwardenVault>>
    
    @Query("SELECT * FROM bitwarden_vaults ORDER BY is_default DESC, created_at DESC")
    suspend fun getAllVaults(): List<BitwardenVault>
    
    @Query("SELECT * FROM bitwarden_vaults WHERE id = :id")
    suspend fun getVaultById(id: Long): BitwardenVault?

    /** Small transport stamp for a pre-connection IO-thread check; contains no tokens or TLS material. */
    @Query("SELECT id, server_url, api_url, identity_url, environment_preset, web_vault_url, notifications_url, transport_revision, transport_secret_ref, is_locked, is_connected FROM bitwarden_vaults WHERE id = :id")
    fun getTransportState(id: Long): BitwardenTransportState?

    @Query("SELECT * FROM bitwarden_vaults WHERE id = :id")
    fun getVaultForTransportPublication(id: Long): BitwardenVault?

    @Query("SELECT id FROM bitwarden_vaults WHERE is_default = 1 LIMIT 1")
    fun getDefaultVaultIdForPublication(): Long?

    @Update
    fun publishTransport(vault: BitwardenVault)
    
    @Query("SELECT * FROM bitwarden_vaults WHERE id = :id")
    fun getVaultByIdFlow(id: Long): Flow<BitwardenVault?>
    
    @Query("SELECT * FROM bitwarden_vaults WHERE LOWER(email) = LOWER(:email) ORDER BY updated_at DESC, created_at DESC LIMIT 1")
    suspend fun getVaultByEmail(email: String): BitwardenVault?

    @Query("SELECT * FROM bitwarden_vaults WHERE account_key = :accountKey LIMIT 1")
    suspend fun getVaultByAccountKey(accountKey: String): BitwardenVault?

    @Query(
        """
        SELECT * FROM bitwarden_vaults
        WHERE canonical_email = :canonicalEmail
          AND LOWER(RTRIM(server_url, '/')) = LOWER(RTRIM(:serverUrl, '/'))
        ORDER BY updated_at DESC, created_at DESC
        LIMIT 1
        """
    )
    suspend fun getVaultByServerAndCanonicalEmail(
        serverUrl: String,
        canonicalEmail: String
    ): BitwardenVault?
    
    @Query("SELECT * FROM bitwarden_vaults WHERE is_default = 1 LIMIT 1")
    suspend fun getDefaultVault(): BitwardenVault?
    
    @Query("SELECT * FROM bitwarden_vaults WHERE is_default = 1 LIMIT 1")
    fun getDefaultVaultFlow(): Flow<BitwardenVault?>
    
    @Query("SELECT COUNT(*) FROM bitwarden_vaults")
    suspend fun getVaultCount(): Int
    
    // === 插入操作 ===
    
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(vault: BitwardenVault): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vault: BitwardenVault): Long
    
    // === 更新操作 ===
    
    @Update
    suspend fun update(vault: BitwardenVault)
    
    /**
     * 更新访问令牌 - 原子操作
     */
    @Query("""
        UPDATE bitwarden_vaults 
        SET encrypted_access_token = :encryptedAccessToken,
            access_token_expires_at = :expiresAt,
            updated_at = :updatedAt
        WHERE id = :vaultId
    """)
    suspend fun updateAccessToken(
        vaultId: Long,
        encryptedAccessToken: String,
        expiresAt: Long,
        updatedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * 更新刷新令牌 - 原子操作
     */
    @Query("""
        UPDATE bitwarden_vaults 
        SET encrypted_refresh_token = :encryptedRefreshToken,
            updated_at = :updatedAt
        WHERE id = :vaultId
    """)
    suspend fun updateRefreshToken(
        vaultId: Long,
        encryptedRefreshToken: String,
        updatedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * 更新加密密钥 - 原子操作
     */
    @Query("""
        UPDATE bitwarden_vaults 
        SET encrypted_master_key = :encryptedMasterKey,
            encrypted_enc_key = :encryptedEncKey,
            encrypted_mac_key = :encryptedMacKey,
            updated_at = :updatedAt
        WHERE id = :vaultId
    """)
    suspend fun updateEncryptionKeys(
        vaultId: Long,
        encryptedMasterKey: String,
        encryptedEncKey: String,
        encryptedMacKey: String,
        updatedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * 更新同步状态
     */
    @Query("""
        UPDATE bitwarden_vaults 
        SET last_sync_at = :lastSyncAt,
            revision_date = :revisionDate,
            updated_at = :updatedAt
        WHERE id = :vaultId
    """)
    suspend fun updateSyncStatus(
        vaultId: Long,
        lastSyncAt: Long,
        revisionDate: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE bitwarden_vaults
        SET email = :email,
            canonical_email = :canonicalEmail,
            user_id = :userId,
            account_key = :accountKey,
            display_name = :displayName,
            last_sync_at = :lastSyncAt,
            revision_date = :revisionDate,
            updated_at = :updatedAt
        WHERE id = :vaultId
        """
    )
    suspend fun updateIdentityAndSyncStatus(
        vaultId: Long,
        email: String,
        canonicalEmail: String,
        userId: String?,
        accountKey: String,
        displayName: String?,
        lastSyncAt: Long,
        revisionDate: String?,
        updatedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * 设置锁定状态
     */
    @Query("UPDATE bitwarden_vaults SET is_locked = :isLocked, updated_at = :updatedAt WHERE id = :vaultId")
    suspend fun setLocked(vaultId: Long, isLocked: Boolean, updatedAt: Long = System.currentTimeMillis())
    
    /**
     * 设置连接状态
     */
    @Query("UPDATE bitwarden_vaults SET is_connected = :isConnected, updated_at = :updatedAt WHERE id = :vaultId")
    suspend fun setConnected(vaultId: Long, isConnected: Boolean, updatedAt: Long = System.currentTimeMillis())
    
    /**
     * 设置默认 Vault (清除其他默认)
     */
    @Transaction
    suspend fun setAsDefault(vaultId: Long) {
        clearDefaultFlag()
        setDefaultFlag(vaultId)
    }
    
    @Query("UPDATE bitwarden_vaults SET is_default = 0")
    suspend fun clearDefaultFlag()
    
    @Query("UPDATE bitwarden_vaults SET is_default = 1, updated_at = :updatedAt WHERE id = :vaultId")
    suspend fun setDefaultFlag(vaultId: Long, updatedAt: Long = System.currentTimeMillis())
    
    // === 删除操作 ===
    
    /**
     * 删除 Vault - 需谨慎！
     * 建议先备份或确认用户意图
     */
    @Delete
    suspend fun delete(vault: BitwardenVault)
    
    @Query("DELETE FROM bitwarden_vaults WHERE id = :vaultId")
    suspend fun deleteById(vaultId: Long)
    
    /**
     * 清除敏感数据（登出时使用）
     */
    @Query("""
        UPDATE bitwarden_vaults 
        SET encrypted_access_token = NULL,
            encrypted_refresh_token = NULL,
            access_token_expires_at = NULL,
            encrypted_master_key = NULL,
            encrypted_enc_key = NULL,
            encrypted_mac_key = NULL,
            is_locked = 1,
            is_connected = 0,
            updated_at = :updatedAt
        WHERE id = :vaultId
    """)
    suspend fun clearSensitiveData(vaultId: Long, updatedAt: Long = System.currentTimeMillis())
}

/**
 * Bitwarden Folder DAO
 */
@Dao
interface BitwardenFolderDao {
    
    @Query("SELECT * FROM bitwarden_folders WHERE vault_id = :vaultId ORDER BY name ASC")
    fun getFoldersByVaultFlow(vaultId: Long): Flow<List<BitwardenFolder>>
    
    @Query("SELECT * FROM bitwarden_folders WHERE vault_id = :vaultId ORDER BY name ASC")
    suspend fun getFoldersByVault(vaultId: Long): List<BitwardenFolder>
    
    @Query("SELECT * FROM bitwarden_folders WHERE id = :id")
    suspend fun getFolderById(id: Long): BitwardenFolder?
    
    @Query("SELECT * FROM bitwarden_folders WHERE bitwarden_folder_id = :bitwardenId")
    suspend fun getFolderByBitwardenId(bitwardenId: String): BitwardenFolder?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: BitwardenFolder): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: BitwardenFolder): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(folders: List<BitwardenFolder>)
    
    @Update
    suspend fun update(folder: BitwardenFolder)
    
    @Delete
    suspend fun delete(folder: BitwardenFolder)
    
    @Query("DELETE FROM bitwarden_folders WHERE vault_id = :vaultId")
    suspend fun deleteByVault(vaultId: Long)
    
    @Query("DELETE FROM bitwarden_folders WHERE bitwarden_folder_id = :bitwardenId")
    suspend fun deleteByBitwardenId(bitwardenId: String)
    
    @Query("UPDATE bitwarden_folders SET name = :newName, encrypted_name = :encryptedName WHERE bitwarden_folder_id = :folderId")
    suspend fun updateName(folderId: String, newName: String, encryptedName: String)
    
    @Query("DELETE FROM bitwarden_folders WHERE bitwarden_folder_id NOT IN (:keepIds) AND vault_id = :vaultId")
    suspend fun deleteNotIn(vaultId: Long, keepIds: List<String>)
}

/**
 * Bitwarden Send DAO
 */
@Dao
interface BitwardenSendDao {

    @Query("SELECT COUNT(*) FROM bitwarden_sends WHERE vault_id = :vaultId")
    suspend fun countByVault(vaultId: Long): Int

    @Query("""
        SELECT id, vault_id, bitwarden_send_id, created_at, updated_at, last_synced_at, is_dirty
        FROM bitwarden_sends WHERE vault_id = :vaultId AND bitwarden_send_id = :sendId LIMIT 1
    """)
    suspend fun getIdentity(vaultId: Long, sendId: String): BitwardenSendIdentity?


    @Query("SELECT * FROM bitwarden_sends WHERE vault_id = :vaultId ORDER BY updated_at DESC")
    fun getSendsByVaultFlow(vaultId: Long): Flow<List<BitwardenSend>>

    @Query("SELECT * FROM bitwarden_sends WHERE vault_id = :vaultId ORDER BY updated_at DESC")
    suspend fun getSendsByVault(vaultId: Long): List<BitwardenSend>

    /**
     * 跨多个已解锁 Vault 的合并查询。
     *
     * 用于 Send 标签页同时展示多个账号下的 Send。
     */
    @Query("SELECT * FROM bitwarden_sends WHERE vault_id IN (:vaultIds) ORDER BY updated_at DESC")
    fun getSendsByVaultsFlow(vaultIds: List<Long>): Flow<List<BitwardenSend>>

    @Query("SELECT * FROM bitwarden_sends WHERE vault_id IN (:vaultIds) ORDER BY updated_at DESC")
    suspend fun getSendsByVaults(vaultIds: List<Long>): List<BitwardenSend>

    @Query("SELECT * FROM bitwarden_sends WHERE vault_id = :vaultId AND bitwarden_send_id = :sendId LIMIT 1")
    suspend fun getBySendId(vaultId: Long, sendId: String): BitwardenSend?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(send: BitwardenSend): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sends: List<BitwardenSend>)

    @Update
    suspend fun update(send: BitwardenSend)

    @Delete
    suspend fun delete(send: BitwardenSend)

    @Query("DELETE FROM bitwarden_sends WHERE vault_id = :vaultId")
    suspend fun deleteByVault(vaultId: Long)

    @Query("DELETE FROM bitwarden_sends WHERE vault_id = :vaultId AND bitwarden_send_id = :sendId")
    suspend fun deleteBySendId(vaultId: Long, sendId: String)

    /**
     * 清理服务器上已不存在的 Send。
     *
     * 双重保护：
     * - is_dirty = 1：本地刚创建/修改但还没和服务器对账的行（防 Vaultwarden / Cloudflare 写后读延迟）
     * - created_at >= :syncStartedAtMs：本次 sync 开始之后才落地的行（兜底，防止 dirty 标记被遗漏）
     *
     * 满足任一条件即跳过删除。
     */
    @Query(
        """
        DELETE FROM bitwarden_sends
        WHERE vault_id = :vaultId
          AND bitwarden_send_id NOT IN (:keepIds)
          AND is_dirty = 0
          AND created_at < :syncStartedAtMs
        """
    )
    suspend fun deleteNotInProtectingDirty(
        vaultId: Long,
        keepIds: List<String>,
        syncStartedAtMs: Long
    )

    /**
     * 清空 vault 所有 Send 时仍保留尚未对账的本地新建条目。
     */
    @Query(
        """
        DELETE FROM bitwarden_sends
        WHERE vault_id = :vaultId
          AND is_dirty = 0
          AND created_at < :syncStartedAtMs
        """
    )
    suspend fun deleteByVaultProtectingDirty(vaultId: Long, syncStartedAtMs: Long)

    @Query(
        """
        UPDATE bitwarden_sends SET is_dirty = 0
        WHERE vault_id = :vaultId AND bitwarden_send_id = :sendId
        """
    )
    suspend fun clearDirty(vaultId: Long, sendId: String)

    @Query("SELECT bitwarden_send_id FROM bitwarden_sends WHERE vault_id = :vaultId AND is_dirty = 1")
    suspend fun getDirtySendIds(vaultId: Long): List<String>
}

/**
 * Bitwarden 冲突备份 DAO
 */
@Dao
interface BitwardenConflictBackupDao {
    
    @Query("SELECT * FROM bitwarden_conflict_backups WHERE is_resolved = 0 ORDER BY created_at DESC")
    fun getUnresolvedConflictsFlow(): Flow<List<BitwardenConflictBackup>>
    
    @Query("SELECT * FROM bitwarden_conflict_backups WHERE is_resolved = 0 ORDER BY created_at DESC")
    suspend fun getUnresolvedConflicts(): List<BitwardenConflictBackup>
    
    @Query("SELECT * FROM bitwarden_conflict_backups WHERE vault_id = :vaultId AND is_resolved = 0")
    suspend fun getUnresolvedConflictsByVault(vaultId: Long): List<BitwardenConflictBackup>
    
    @Query("SELECT COUNT(*) FROM bitwarden_conflict_backups WHERE is_resolved = 0")
    fun getUnresolvedCountFlow(): Flow<Int>
    
    @Query("SELECT * FROM bitwarden_conflict_backups WHERE id = :id")
    suspend fun getById(id: Long): BitwardenConflictBackup?
    
    @Insert
    suspend fun insert(conflict: BitwardenConflictBackup): Long
    
    @Update
    suspend fun update(conflict: BitwardenConflictBackup)
    
    /**
     * 标记冲突已解决
     */
    @Query("""
        UPDATE bitwarden_conflict_backups 
        SET is_resolved = 1, 
            resolution = :resolution,
            resolved_at = :resolvedAt
        WHERE id = :id
    """)
    suspend fun markResolved(id: Long, resolution: String, resolvedAt: Long = System.currentTimeMillis())
    
    /**
     * 删除已解决的冲突 (超过保留期限)
     */
    @Query("DELETE FROM bitwarden_conflict_backups WHERE is_resolved = 1 AND resolved_at < :beforeTimestamp")
    suspend fun deleteResolvedBefore(beforeTimestamp: Long)

    @Query("DELETE FROM bitwarden_conflict_backups WHERE vault_id = :vaultId")
    suspend fun deleteByVault(vaultId: Long)
}

/**
 * Bitwarden 待处理操作 DAO
 */
@Dao
interface BitwardenPendingOperationDao {
    /** Account-scoped metadata only; active operations take precedence over dirty item rows. */
    @Query("""
        SELECT 'password:' || p.id AS queueKey,
               COALESCE(p.bitwarden_cipher_id, 'local:password:' || p.id) AS itemId,
               p.title AS title, 'CIPHER' AS kind,
               CASE WHEN p.bitwarden_cipher_id IS NULL THEN 'CREATE' ELSE 'UPDATE' END AS operation,
               'PENDING' AS status
        FROM password_entries p
        WHERE p.bitwarden_vault_id = :vaultId AND p.isDeleted = 0
          AND (p.bitwarden_local_modified = 1 OR p.bitwarden_cipher_id IS NULL)
          AND NOT EXISTS (SELECT 1 FROM bitwarden_pending_operations op
              WHERE op.vault_id = :vaultId AND op.target_type = 'CIPHER' AND op.item_type = 'PASSWORD'
                AND op.status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
                AND (op.entry_id = p.id OR op.bitwarden_cipher_id = p.bitwarden_cipher_id))
        UNION ALL
        SELECT 'secure:' || s.id, COALESCE(s.bitwarden_cipher_id, 'local:secure:' || s.id),
               s.title, 'CIPHER',
               CASE WHEN s.bitwarden_cipher_id IS NULL THEN 'CREATE' ELSE 'UPDATE' END, 'PENDING'
        FROM secure_items s
        WHERE s.bitwarden_vault_id = :vaultId AND s.isDeleted = 0
          AND (s.bitwarden_local_modified = 1 OR s.bitwarden_cipher_id IS NULL)
          AND NOT EXISTS (SELECT 1 FROM bitwarden_pending_operations op
              WHERE op.vault_id = :vaultId AND op.target_type = 'CIPHER'
                AND op.item_type IN ('TOTP', 'CARD', 'NOTE', 'DOCUMENT', 'BILLING_ADDRESS', 'PAYMENT_ACCOUNT')
                AND op.status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
                AND (op.entry_id = s.id OR op.bitwarden_cipher_id = s.bitwarden_cipher_id))
        UNION ALL
        SELECT 'passkey:' || pk.id, COALESCE(pk.bitwarden_cipher_id, 'passkey:' || pk.id),
               pk.rp_name, 'PASSKEY',
               CASE WHEN pk.bitwarden_cipher_id IS NULL THEN 'CREATE' ELSE 'UPDATE' END,
               CASE WHEN pk.sync_status = 'FAILED' THEN 'FAILED' ELSE 'PENDING' END
        FROM passkeys pk
        WHERE pk.bitwarden_vault_id = :vaultId AND pk.passkey_mode = 'BW_COMPAT'
          AND pk.sync_status != 'REFERENCE'
          AND (pk.bitwarden_cipher_id IS NULL OR (pk.sync_status IN ('PENDING', 'FAILED') AND pk.private_key_alias != ''))
          AND NOT EXISTS (SELECT 1 FROM bitwarden_pending_operations op
              WHERE op.vault_id = :vaultId AND op.item_type = 'PASSKEY'
                AND op.status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
                AND (op.entry_id = pk.id OR op.bitwarden_cipher_id = pk.bitwarden_cipher_id))
        UNION ALL
        SELECT 'send:' || id, bitwarden_send_id, name, 'SEND', 'CONFIRM', 'PENDING'
        FROM bitwarden_sends WHERE vault_id = :vaultId AND is_dirty = 1
        UNION ALL
        SELECT 'operation:' || op.id,
               COALESCE(op.bitwarden_cipher_id, 'local:operation:' || op.id),
               CASE WHEN op.item_type = 'PASSWORD' THEN p.title ELSE s.title END,
               CASE WHEN op.item_type = 'PASSKEY' THEN 'PASSKEY' ELSE op.target_type END,
               op.operation_type, op.status
        FROM bitwarden_pending_operations op
        LEFT JOIN password_entries p ON op.target_type = 'CIPHER' AND op.item_type = 'PASSWORD'
             AND p.id = op.entry_id AND p.bitwarden_vault_id = op.vault_id
        LEFT JOIN secure_items s ON op.target_type = 'CIPHER'
             AND op.item_type IN ('TOTP', 'CARD', 'NOTE', 'DOCUMENT', 'BILLING_ADDRESS', 'PAYMENT_ACCOUNT')
             AND s.id = op.entry_id AND s.bitwarden_vault_id = op.vault_id
        WHERE op.vault_id = :vaultId AND op.status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
        ORDER BY queueKey
    """)
    fun observeQueueRecords(vaultId: Long): Flow<List<BitwardenSyncQueueRecord>>

    
    @Query("SELECT * FROM bitwarden_pending_operations WHERE status = 'PENDING' ORDER BY created_at ASC")
    fun getPendingOperationsFlow(): Flow<List<BitwardenPendingOperation>>
    
    @Query("SELECT * FROM bitwarden_pending_operations WHERE status = 'PENDING' ORDER BY created_at ASC")
    suspend fun getPendingOperations(): List<BitwardenPendingOperation>
    
    @Query("SELECT * FROM bitwarden_pending_operations WHERE vault_id = :vaultId AND status = 'PENDING' ORDER BY created_at ASC")
    suspend fun getPendingOperationsByVault(vaultId: Long): List<BitwardenPendingOperation>

    @Query("""
        SELECT * FROM bitwarden_pending_operations
        WHERE vault_id = :vaultId
          AND status IN ('PENDING', 'FAILED')
        ORDER BY created_at ASC
    """)
    suspend fun getRunnableOperationsByVault(vaultId: Long): List<BitwardenPendingOperation>
    
    @Query("SELECT COUNT(*) FROM bitwarden_pending_operations WHERE status = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM bitwarden_pending_operations WHERE status = 'FAILED'")
    fun getFailedCountFlow(): Flow<Int>
    
    @Query("SELECT * FROM bitwarden_pending_operations WHERE status = 'FAILED' ORDER BY created_at DESC")
    fun getFailedOperationsFlow(): Flow<List<BitwardenPendingOperation>>
    
    @Query("SELECT * FROM bitwarden_pending_operations WHERE status = 'FAILED' ORDER BY created_at DESC")
    suspend fun getFailedOperations(): List<BitwardenPendingOperation>
    
    @Query("SELECT * FROM bitwarden_pending_operations WHERE status IN ('PENDING', 'FAILED') ORDER BY created_at ASC")
    fun getAllPendingAndFailedFlow(): Flow<List<BitwardenPendingOperation>>
    
    @Query("SELECT * FROM bitwarden_pending_operations WHERE id = :id")
    suspend fun getById(id: Long): BitwardenPendingOperation?
    
    @Query("SELECT * FROM bitwarden_pending_operations WHERE entry_id = :entryId AND item_type = :itemType AND status IN ('PENDING', 'IN_PROGRESS')")
    suspend fun findPendingByEntryAndType(entryId: Long, itemType: String): BitwardenPendingOperation?

    @Query("""
        SELECT * FROM bitwarden_pending_operations
        WHERE vault_id = :vaultId
          AND bitwarden_cipher_id = :cipherId
          AND operation_type = 'DELETE'
          AND status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
        ORDER BY created_at DESC
        LIMIT 1
    """)
    suspend fun findActiveDeleteByCipher(vaultId: Long, cipherId: String): BitwardenPendingOperation?

    @Query("""
        SELECT * FROM bitwarden_pending_operations
        WHERE vault_id = :vaultId
          AND bitwarden_cipher_id = :cipherId
          AND operation_type = 'RESTORE'
          AND status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
        ORDER BY created_at DESC
        LIMIT 1
    """)
    suspend fun findActiveRestoreByCipher(vaultId: Long, cipherId: String): BitwardenPendingOperation?

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM bitwarden_pending_operations
            WHERE vault_id = :vaultId
              AND bitwarden_cipher_id = :cipherId
              AND operation_type = 'DELETE'
              AND status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
        )
    """)
    suspend fun hasActiveDeleteByCipher(vaultId: Long, cipherId: String): Boolean
    
    @Insert
    suspend fun insert(operation: BitwardenPendingOperation): Long
    
    @Update
    suspend fun update(operation: BitwardenPendingOperation)
    
    /**
     * 更新操作状态
     */
    @Query("""
        UPDATE bitwarden_pending_operations 
        SET status = :status,
            last_attempt_at = :lastAttemptAt,
            retry_count = retry_count + 1,
            last_error = :lastError
        WHERE id = :id
    """)
    suspend fun updateStatus(
        id: Long,
        status: String,
        lastAttemptAt: Long = System.currentTimeMillis(),
        lastError: String? = null
    )
    
    /**
     * 标记操作完成
     */
    @Query("""
        UPDATE bitwarden_pending_operations 
        SET status = 'COMPLETED',
            completed_at = :completedAt
        WHERE id = :id
    """)
    suspend fun markCompleted(id: Long, completedAt: Long = System.currentTimeMillis())
    
    /**
     * 更新 Bitwarden Cipher ID（创建成功后）
     */
    @Query("""
        UPDATE bitwarden_pending_operations 
        SET bitwarden_cipher_id = :cipherId
        WHERE id = :id
    """)
    suspend fun updateCipherId(id: Long, cipherId: String)
    
    /**
     * 取消操作
     */
    @Query("UPDATE bitwarden_pending_operations SET status = 'CANCELLED' WHERE id = :id")
    suspend fun cancel(id: Long)

    @Query("""
        UPDATE bitwarden_pending_operations
        SET status = 'CANCELLED'
        WHERE vault_id = :vaultId
          AND bitwarden_cipher_id = :cipherId
          AND operation_type = 'DELETE'
          AND status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
    """)
    suspend fun cancelActiveDeleteByCipher(vaultId: Long, cipherId: String)

    @Query("""
        UPDATE bitwarden_pending_operations
        SET status = 'CANCELLED'
        WHERE vault_id = :vaultId
          AND bitwarden_cipher_id = :cipherId
          AND operation_type = 'RESTORE'
          AND status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
    """)
    suspend fun cancelActiveRestoreByCipher(vaultId: Long, cipherId: String)
    
    /**
     * 重置失败操作为待处理状态（手动重试）
     */
    @Query("""
        UPDATE bitwarden_pending_operations 
        SET status = 'PENDING',
            retry_count = 0,
            last_error = NULL,
            last_attempt_at = NULL
        WHERE id = :id AND status = 'FAILED'
    """)
    suspend fun resetForRetry(id: Long)
    
    /**
     * 批量重置所有失败操作
     */
    @Query("""
        UPDATE bitwarden_pending_operations 
        SET status = 'PENDING',
            retry_count = 0,
            last_error = NULL,
            last_attempt_at = NULL
        WHERE status = 'FAILED'
    """)
    suspend fun resetAllFailedForRetry()
    
    /**
     * 删除已完成的操作 (超过保留期限)
     */
    @Query("DELETE FROM bitwarden_pending_operations WHERE status = 'COMPLETED' AND completed_at < :beforeTimestamp")
    suspend fun deleteCompletedBefore(beforeTimestamp: Long)
    
    /**
     * 删除条目相关的待处理操作
     */
    @Query("DELETE FROM bitwarden_pending_operations WHERE entry_id = :entryId AND status = 'PENDING'")
    suspend fun deletePendingForEntry(entryId: Long)
    
    /**
     * 删除条目相关的所有待处理操作（指定类型）
     */
    @Query("DELETE FROM bitwarden_pending_operations WHERE entry_id = :entryId AND item_type = :itemType AND status IN ('PENDING', 'FAILED')")
    suspend fun deletePendingForEntryAndType(entryId: Long, itemType: String)
    
    /**
     * 获取指定 Vault 的待同步数量统计
     */
    @Query("""
        SELECT item_type, COUNT(*) as count 
        FROM bitwarden_pending_operations 
        WHERE vault_id = :vaultId AND status = 'PENDING' 
        GROUP BY item_type
    """)
    suspend fun getPendingCountByType(vaultId: Long): List<ItemTypeCount>

    @Query("DELETE FROM bitwarden_pending_operations WHERE vault_id = :vaultId")
    suspend fun deleteByVault(vaultId: Long)
}

/**
 * 用于统计各类型待同步数量的数据类
 */
data class ItemTypeCount(
    @ColumnInfo(name = "item_type") val itemType: String,
    @ColumnInfo(name = "count") val count: Int
)

@Dao
interface BitwardenSyncRawEntryRecordDao {

    @Query(
        """
        SELECT * FROM bitwarden_sync_raw_entry_records
        WHERE vault_id = :vaultId AND bitwarden_cipher_id = :cipherId
        ORDER BY captured_at DESC, id DESC
        """
    )
    fun getByCipherFlow(vaultId: Long, cipherId: String): Flow<List<BitwardenSyncRawEntryRecord>>

    @Query(
        """
        SELECT * FROM bitwarden_sync_raw_entry_records
        WHERE vault_id = :vaultId AND bitwarden_cipher_id = :cipherId
        ORDER BY captured_at DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestByCipher(vaultId: Long, cipherId: String): BitwardenSyncRawEntryRecord?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: BitwardenSyncRawEntryRecord): Long

    @Query(
        """
        DELETE FROM bitwarden_sync_raw_entry_records
        WHERE vault_id = :vaultId
          AND bitwarden_cipher_id = :cipherId
          AND id NOT IN (
              SELECT id
              FROM bitwarden_sync_raw_entry_records
              WHERE vault_id = :vaultId AND bitwarden_cipher_id = :cipherId
              ORDER BY captured_at DESC, id DESC
              LIMIT :limit
          )
        """
    )
    suspend fun trimToLimit(vaultId: Long, cipherId: String, limit: Int)

    @Query("DELETE FROM bitwarden_sync_raw_entry_records WHERE vault_id = :vaultId")
    suspend fun deleteByVault(vaultId: Long)
}
