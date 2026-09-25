package takagi.ru.monica.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Passkey 数据访问对象
 */
@Dao
interface PasskeyDao {
    
    // ==================== 查询操作 ====================
    
    /**
     * 获取所有 Passkey（按最后使用时间降序）
     */
    @Query("SELECT * FROM passkeys WHERE $MDBX_AVAILABLE_ENTRY_FILTER ORDER BY last_used_at DESC")
    fun getAllPasskeys(): Flow<List<PasskeyEntry>>
    
    /**
     * 获取所有 Passkey（同步版本）
     */
    @Query("SELECT * FROM passkeys WHERE $MDBX_AVAILABLE_ENTRY_FILTER ORDER BY last_used_at DESC")
    suspend fun getAllPasskeysSync(): List<PasskeyEntry>

    /**
     * 根据协议 credentialId 获取一个最合适的 Passkey
     */
    @Query(
        """
        SELECT * FROM passkeys
        WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (credential_id = :credentialId) ORDER BY
            CASE WHEN sync_status = 'REFERENCE' THEN 1 ELSE 0 END,
            last_used_at DESC,
            id DESC
        LIMIT 1
        """
    )
    suspend fun getPasskeyById(credentialId: String): PasskeyEntry?

    @Query("SELECT * FROM passkeys WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (id = :recordId) LIMIT 1")
    suspend fun getPasskeyByRecordId(recordId: Long): PasskeyEntry?

    @Query(
        """
        SELECT * FROM passkeys
        WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (credential_id = :credentialId) ORDER BY last_used_at DESC, id DESC
        """
    )
    suspend fun getPasskeysByCredentialId(credentialId: String): List<PasskeyEntry>

    @Query("""
        SELECT * FROM passkeys
        WHERE credential_id = :credentialId
          AND bitwarden_vault_id IS NULL
          AND keepass_database_id IS NULL
          AND mdbx_database_id IS NULL
        LIMIT 1
    """)
    suspend fun getLocalPasskeyById(credentialId: String): PasskeyEntry?
    
    /**
     * 根据依赖方 ID (域名) 获取 Passkeys
     */
    @Query("SELECT * FROM passkeys WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (rp_id = :rpId) ORDER BY last_used_at DESC")
    fun getPasskeysByRpId(rpId: String): Flow<List<PasskeyEntry>>
    
    /**
     * 根据依赖方 ID (域名) 获取 Passkeys（同步版本）
     */
    @Query("SELECT * FROM passkeys WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (rp_id = :rpId) ORDER BY last_used_at DESC")
    suspend fun getPasskeysByRpIdSync(rpId: String): List<PasskeyEntry>
    
    /**
     * 搜索 Passkey（按域名、用户名、显示名搜索）
     */
    @Query("""
        SELECT * FROM passkeys 
        WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (rp_id LIKE '%' || :query || '%'
           OR rp_name LIKE '%' || :query || '%'
           OR user_name LIKE '%' || :query || '%'
           OR user_display_name LIKE '%' || :query || '%') ORDER BY last_used_at DESC
    """)
    fun searchPasskeys(query: String): Flow<List<PasskeyEntry>>
    
    /**
     * 获取可发现的 Passkeys（用于 Credential Provider 展示）
     */
    @Query("SELECT * FROM passkeys WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (is_discoverable = 1) ORDER BY last_used_at DESC")
    suspend fun getDiscoverablePasskeys(): List<PasskeyEntry>
    
    /**
     * 获取可发现的 Passkeys（同步版本，用于 Service）
     */
    @Query("SELECT * FROM passkeys WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (is_discoverable = 1) ORDER BY last_used_at DESC")
    suspend fun getDiscoverablePasskeysSync(): List<PasskeyEntry>
    
    /**
     * 获取可发现的 Passkeys 按域名过滤
     */
    @Query("SELECT * FROM passkeys WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (is_discoverable = 1 AND rp_id = :rpId) ORDER BY last_used_at DESC")
    suspend fun getDiscoverablePasskeysByRpId(rpId: String): List<PasskeyEntry>
    
    /**
     * 获取 Passkey 总数
     */
    @Query("SELECT COUNT(*) FROM passkeys WHERE $MDBX_AVAILABLE_ENTRY_FILTER ")
    fun getPasskeyCount(): Flow<Int>

    /**
     * 获取本地 Passkey 数量（排除 Bitwarden 和 KeePass 的数据）
     */
    @Query("""
        SELECT COUNT(*) FROM passkeys
        WHERE bitwarden_vault_id IS NULL
          AND keepass_database_id IS NULL
          AND mdbx_database_id IS NULL
    """)
    suspend fun getLocalPasskeyCount(): Int
    
    /**
     * 获取未备份的 Passkeys（用于 WebDAV 同步）
     */
    @Query("SELECT * FROM passkeys WHERE is_backed_up = 0")
    suspend fun getUnbackedPasskeys(): List<PasskeyEntry>
    
    // ==================== 插入操作 ====================
    
    /**
     * 插入 Passkey
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(passkey: PasskeyEntry)
    
    /**
     * 批量插入 Passkeys
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(passkeys: List<PasskeyEntry>)
    
    // ==================== 更新操作 ====================
    
    /**
     * 更新 Passkey
     */
    @Update
    suspend fun update(passkey: PasskeyEntry)
    
    /**
     * 更新最后使用时间和使用次数
     */
    @Query("""
        UPDATE passkeys 
        SET last_used_at = :timestamp, 
            use_count = use_count + 1,
            sign_count = :signCount
        WHERE credential_id = :credentialId
    """)
    suspend fun updateUsage(credentialId: String, timestamp: Long = System.currentTimeMillis(), signCount: Long)

    @Query(
        """
        UPDATE passkeys
        SET last_used_at = :timestamp,
            use_count = use_count + 1,
            sign_count = :signCount
        WHERE id = :recordId
        """
    )
    suspend fun updateUsageByRecordId(
        recordId: Long,
        timestamp: Long = System.currentTimeMillis(),
        signCount: Long
    )
    
    /**
     * 标记为已备份
     */
    @Query("UPDATE passkeys SET is_backed_up = 1 WHERE credential_id = :credentialId")
    suspend fun markAsBackedUp(credentialId: String)
    
    /**
     * 批量标记为已备份
     */
    @Query("UPDATE passkeys SET is_backed_up = 1 WHERE credential_id IN (:credentialIds)")
    suspend fun markAllAsBackedUp(credentialIds: List<String>)
    
    // ==================== 删除操作 ====================
    
    /**
     * 删除 Passkey
     */
    @Delete
    suspend fun delete(passkey: PasskeyEntry)

    /**
     * 删除所有 Passkey（用于覆盖恢复）
     */
    @Query("DELETE FROM passkeys")
    suspend fun deleteAllPasskeys()

    @Query(
        """
        DELETE FROM passkeys
        WHERE bitwarden_vault_id IS NULL
          AND keepass_database_id IS NULL
          AND mdbx_database_id IS NULL
        """
    )
    suspend fun deleteAllLocalPasskeys()
    
    /**
     * 根据凭据 ID 删除 Passkey
     */
    @Query("DELETE FROM passkeys WHERE credential_id = :credentialId")
    suspend fun deleteById(credentialId: String)

    @Query("DELETE FROM passkeys WHERE id = :recordId")
    suspend fun deleteByRecordId(recordId: Long)

    @Query("DELETE FROM passkeys WHERE id IN (:recordIds)")
    suspend fun deleteByRecordIds(recordIds: List<Long>)
    
    /**
     * 删除指定域名的所有 Passkeys
     */
    @Query("DELETE FROM passkeys WHERE rp_id = :rpId")
    suspend fun deleteByRpId(rpId: String)
    
    /**
     * 清空所有 Passkeys
     */
    @Query("DELETE FROM passkeys")
    suspend fun deleteAll()
    
    // =============== Bitwarden 同步相关方法 ===============
    
    /**
     * 根据 Bitwarden Cipher ID 获取 Passkey
     */
    @Query("SELECT * FROM passkeys WHERE bitwarden_cipher_id = :cipherId LIMIT 1")
    suspend fun getByBitwardenCipherId(cipherId: String): PasskeyEntry?

    @Query(
        """
        SELECT * FROM passkeys
        WHERE bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id = :cipherId
        LIMIT 1
        """
    )
    suspend fun getByBitwardenCipherIdInVault(vaultId: Long, cipherId: String): PasskeyEntry?

    @Query(
        """
        SELECT * FROM passkeys
        WHERE bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id = :cipherId
          AND credential_id = :credentialId
        ORDER BY id DESC
        LIMIT 1
        """
    )
    suspend fun getByBitwardenCipherCredentialIdInVault(
        vaultId: Long,
        cipherId: String,
        credentialId: String
    ): PasskeyEntry?

    /**
     * 根据 Bitwarden Cipher ID 获取所有 Passkey
     */
    @Query("SELECT * FROM passkeys WHERE bitwarden_cipher_id = :cipherId")
    suspend fun getAllByBitwardenCipherId(cipherId: String): List<PasskeyEntry>

    @Query(
        """
        SELECT * FROM passkeys
        WHERE bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id = :cipherId
        """
    )
    suspend fun getAllByBitwardenCipherIdInVault(vaultId: Long, cipherId: String): List<PasskeyEntry>
    
    /**
     * 获取指定 Vault 的所有 Passkeys
     */
    @Query("SELECT * FROM passkeys WHERE bitwarden_vault_id = :vaultId")
    suspend fun getByBitwardenVaultId(vaultId: Long): List<PasskeyEntry>

    @Query("SELECT COUNT(*) FROM passkeys WHERE bitwarden_vault_id = :vaultId")
    suspend fun getBitwardenEntriesCount(vaultId: Long): Int

    /**
     * 删除指定 Vault 的所有同步 Passkeys
     */
    @Query("DELETE FROM passkeys WHERE bitwarden_vault_id = :vaultId")
    suspend fun deleteAllByBitwardenVaultId(vaultId: Long)

    /**
     * 删除不在服务器返回集合中的 Bitwarden Passkeys
     */
    @Query("DELETE FROM passkeys WHERE bitwarden_vault_id = :vaultId AND bitwarden_cipher_id IS NOT NULL AND bitwarden_cipher_id NOT IN (:cipherIds)")
    suspend fun deleteBitwardenEntriesNotIn(vaultId: Long, cipherIds: List<String>)

    /**
     * 获取绑定到指定密码的 Passkeys
     */
    @Query("SELECT * FROM passkeys WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (bound_password_id = :passwordId) ORDER BY last_used_at DESC")
    fun getByBoundPasswordId(passwordId: Long): Flow<List<PasskeyEntry>>

    /**
     * 获取绑定到指定密码集合的 Passkeys（同步版本）
     */
    @Query("SELECT * FROM passkeys WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (bound_password_id IN (:passwordIds)) ")
    suspend fun getByBoundPasswordIds(passwordIds: List<Long>): List<PasskeyEntry>

    @Query(
        """
        SELECT * FROM passkeys
        WHERE keepass_database_id = :databaseId
          AND passkey_mode = :passkeyMode
        """
    )
    suspend fun getKeePassCompatPasskeysByDatabaseId(
        databaseId: Long,
        passkeyMode: String = PasskeyEntry.MODE_KEEPASS_COMPAT
    ): List<PasskeyEntry>

    /**
     * 更新绑定的密码 ID
     */
    @Query("UPDATE passkeys SET bound_password_id = :passwordId WHERE credential_id = :credentialId")
    suspend fun updateBoundPasswordId(credentialId: String, passwordId: Long?)

    @Query("UPDATE passkeys SET bound_password_id = :passwordId WHERE id = :recordId")
    suspend fun updateBoundPasswordIdByRecordId(recordId: Long, passwordId: Long?)

    @Query("UPDATE passkeys SET category_id = NULL WHERE category_id = :categoryId")
    suspend fun removeCategoryFromPasskeys(categoryId: Long)

    @Query(
        """
        UPDATE passkeys
        SET keepass_database_id = NULL,
            keepass_group_path = NULL
        WHERE keepass_database_id = :databaseId
        """
    )
    suspend fun clearKeePassBindingForDatabase(databaseId: Long)

    @Query(
        """
        DELETE FROM passkeys
        WHERE keepass_database_id = :databaseId
        """
    )
    suspend fun deleteByKeePassDatabaseId(databaseId: Long)

    @Query(
        """
        UPDATE passkeys
        SET keepass_database_id = NULL,
            keepass_group_path = NULL
        WHERE credential_id IN (:credentialIds)
        """
    )
    suspend fun clearKeePassBindingForCredentialIds(credentialIds: List<String>)

    @Query(
        """
        UPDATE passkeys
        SET keepass_database_id = NULL,
            keepass_group_path = NULL
        WHERE id IN (:recordIds)
        """
    )
    suspend fun clearKeePassBindingForRecordIds(recordIds: List<Long>)

    @Query(
        """
        DELETE FROM passkeys
        WHERE keepass_database_id = :databaseId
          AND passkey_mode = :passkeyMode
        """
    )
    suspend fun deleteAllKeePassCompatPasskeysByDatabaseId(
        databaseId: Long,
        passkeyMode: String = PasskeyEntry.MODE_KEEPASS_COMPAT
    )

    @Query(
        """
        DELETE FROM passkeys
        WHERE keepass_database_id = :databaseId
          AND passkey_mode = :passkeyMode
          AND credential_id NOT IN (:credentialIds)
        """
    )
    suspend fun deleteKeePassCompatPasskeysNotIn(
        databaseId: Long,
        credentialIds: List<String>,
        passkeyMode: String = PasskeyEntry.MODE_KEEPASS_COMPAT
    )

    @Query("SELECT * FROM passkeys WHERE mdbx_database_id = :databaseId ORDER BY last_used_at DESC")
    suspend fun getByMdbxDatabaseId(databaseId: Long): List<PasskeyEntry>

    @Query("SELECT COUNT(*) FROM passkeys WHERE private_key_alias = :keyReference")
    suspend fun countByPrivateKeyAlias(keyReference: String): Int

    @Query("DELETE FROM passkeys WHERE mdbx_database_id = :databaseId")
    suspend fun deleteAllByMdbxDatabaseId(databaseId: Long)

    @Query(
        """
        UPDATE passkeys
        SET mdbx_database_id = :databaseId,
            mdbx_folder_id = :folderId,
            category_id = NULL,
            keepass_database_id = NULL,
            keepass_group_path = NULL,
            bitwarden_vault_id = NULL,
            bitwarden_folder_id = NULL,
            bitwarden_cipher_id = NULL,
            sync_status = 'NONE'
        WHERE id IN (:recordIds)
        """
    )
    suspend fun updateMdbxDatabaseForPasskeys(recordIds: List<Long>, databaseId: Long?, folderId: String?)
    
    /**
     * 获取待上传到 Bitwarden 的 Passkeys
     */
    @Query("SELECT * FROM passkeys WHERE bitwarden_vault_id = :vaultId AND bitwarden_cipher_id IS NULL")
    suspend fun getLocalEntriesPendingUpload(vaultId: Long): List<PasskeyEntry>

    /**
     * 标记所有未关联 Bitwarden 的 Passkeys 为指定 Vault
     */
    @Query("UPDATE passkeys SET bitwarden_vault_id = :vaultId WHERE bitwarden_vault_id IS NULL")
    suspend fun markAllForBitwarden(vaultId: Long)
    
    /**
     * 标记 Passkey 为已同步
     */
    @Query("UPDATE passkeys SET sync_status = 'SYNCED', bitwarden_cipher_id = :cipherId WHERE credential_id = :credentialId")
    suspend fun markSynced(credentialId: String, cipherId: String)

    @Query("UPDATE passkeys SET sync_status = 'SYNCED', bitwarden_cipher_id = :cipherId WHERE id = :recordId")
    suspend fun markSyncedByRecordId(recordId: Long, cipherId: String)

    /**
     * 标记 Passkey 同步失败
     */
    @Query("UPDATE passkeys SET sync_status = 'FAILED' WHERE credential_id = :credentialId")
    suspend fun markFailed(credentialId: String)

    @Query("UPDATE passkeys SET sync_status = 'FAILED' WHERE id = :recordId")
    suspend fun markFailedByRecordId(recordId: Long)
}
