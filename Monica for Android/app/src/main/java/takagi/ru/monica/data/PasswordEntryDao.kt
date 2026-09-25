package takagi.ru.monica.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for password entries
 *
 * Full-row lists may span several CursorWindows. Keep each read in one
 * transaction so concurrent sync/import writes cannot change later windows.
 */
@Dao
interface PasswordEntryDao {
    @Transaction
    @Query("""
        SELECT id, title, username, website, password, appName, appPackageName,
               isFavorite, authenticatorKey, keepassDatabaseId, mdbx_database_id, bitwarden_vault_id
        FROM password_entries
        WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND isDeleted = 0 AND isArchived = 0
          AND LOWER(loginType) NOT IN ('gpg_key', 'api_key')
    """)
    suspend fun getImePasswordRows(): List<ImePasswordRow>

    @Transaction
    @Query("""
        SELECT id, title, username, website, password, appName, appPackageName,
               isFavorite, authenticatorKey, keepassDatabaseId, mdbx_database_id, bitwarden_vault_id
        FROM password_entries
        WHERE id = :id AND $MDBX_AVAILABLE_ENTRY_FILTER AND isDeleted = 0 AND isArchived = 0
          AND LOWER(loginType) NOT IN ('gpg_key', 'api_key')
    """)
    suspend fun getImePasswordRowById(id: Long): ImePasswordRow?

    @Query("""
        SELECT bitwarden_vault_id AS bitwardenVaultId, keepass_database_id AS keepassDatabaseId,
               mdbx_database_id AS mdbxDatabaseId, COUNT(*) AS count
        FROM (
            SELECT bitwarden_vault_id, keepassDatabaseId AS keepass_database_id, mdbx_database_id FROM password_entries WHERE isDeleted = 1
            UNION ALL
            SELECT bitwarden_vault_id, keepass_database_id, mdbx_database_id FROM secure_items WHERE isDeleted = 1
        ) GROUP BY bitwarden_vault_id, keepass_database_id, mdbx_database_id
    """)
    fun observeVaultOverviewTrashCounts(): Flow<List<VaultOverviewTrashCount>>

    
    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 0) ORDER BY isFavorite DESC, sortOrder ASC, updatedAt DESC")
    fun getAllPasswordEntries(): Flow<List<PasswordEntry>>

    /** Lightweight invalidation key for consumers that cache the active entry projection. */
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(updatedAt), '') FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 0) ")
    fun observeActivePasswordRevision(): Flow<String>
    
    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 0 AND categoryId = :categoryId) ORDER BY isFavorite DESC, sortOrder ASC, updatedAt DESC")
    fun getPasswordEntriesByCategory(categoryId: Long): Flow<List<PasswordEntry>>

    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 0 AND categoryId IS NULL) ORDER BY isFavorite DESC, sortOrder ASC, updatedAt DESC")
    fun getUncategorizedPasswordEntries(): Flow<List<PasswordEntry>>

    @Transaction
    @Query("SELECT * FROM password_entries WHERE isDeleted = 0 AND isArchived = 0 AND keepassDatabaseId = :databaseId ORDER BY isFavorite DESC, sortOrder ASC, updatedAt DESC")
    fun getPasswordEntriesByKeePassDatabase(databaseId: Long): Flow<List<PasswordEntry>>

    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE isDeleted = 0
          AND isArchived = 0
          AND keepassDatabaseId = :databaseId
          AND (
            (:groupUuid IS NOT NULL AND keepass_group_uuid = :groupUuid)
            OR ((:groupUuid IS NULL OR keepass_group_uuid IS NULL) AND keepassGroupPath = :groupPath)
          )
        ORDER BY isFavorite DESC, sortOrder ASC, updatedAt DESC
        """
    )
    fun getPasswordEntriesByKeePassGroup(
        databaseId: Long,
        groupPath: String,
        groupUuid: String?
    ): Flow<List<PasswordEntry>>

    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 0 AND keepassDatabaseId IS NULL) ORDER BY isFavorite DESC, sortOrder ASC, updatedAt DESC")
    fun getPasswordEntriesWithoutKeePassDatabase(): Flow<List<PasswordEntry>>

    @Query("SELECT COUNT(*) FROM password_entries WHERE isDeleted = 0 AND isArchived = 0 AND keepassDatabaseId = :databaseId")
    fun getPasswordCountByKeePassDatabase(databaseId: Long): Flow<Int>

    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 0 AND isFavorite = 1) ORDER BY sortOrder ASC, updatedAt DESC")
    fun getFavoritePasswordEntries(): Flow<List<PasswordEntry>>
    
    @Query("UPDATE password_entries SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun removeCategoryFromPasswords(categoryId: Long)
    
    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 0 AND (title LIKE '%' || :query || '%' OR website LIKE '%' || :query || '%' OR username LIKE '%' || :query || '%' OR appName LIKE '%' || :query || '%' OR appPackageName LIKE '%' || :query || '%')) ORDER BY isFavorite DESC, sortOrder ASC, updatedAt DESC")
    fun searchPasswordEntries(query: String): Flow<List<PasswordEntry>>
    
    @Query("SELECT * FROM password_entries WHERE id = :id")
    suspend fun getPasswordEntryById(id: Long): PasswordEntry?

        @Query(
                """
                SELECT * FROM password_entries
                WHERE keepassDatabaseId = :databaseId
                    AND keepass_entry_uuid = :entryUuid
                LIMIT 1
                """
        )
        suspend fun findByKeePassEntryUuid(databaseId: Long, entryUuid: String): PasswordEntry?
    
    @Transaction
    @Query("SELECT * FROM password_entries WHERE id IN (:ids)")
    suspend fun getPasswordsByIds(ids: List<Long>): List<PasswordEntry>

    @Transaction
    @Query("SELECT * FROM password_entries WHERE id IN (:ids) AND isDeleted = 0 AND isArchived = 0")
    suspend fun getActivePasswordsByIds(ids: List<Long>): List<PasswordEntry>

    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE id > :afterId
          AND authenticatorKey != ''
        ORDER BY id ASC
        LIMIT :limit
        """
    )
    suspend fun getAuthenticatorKeyMigrationBatch(afterId: Long, limit: Int): List<PasswordEntry>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPasswordEntry(entry: PasswordEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPasswordEntries(entries: List<PasswordEntry>): List<Long>
    
    /**
     * 插入密码条目（别名方法，供 Bitwarden 同步服务使用）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PasswordEntry): Long
    
    @Update
    suspend fun updatePasswordEntry(entry: PasswordEntry)

    @Update
    suspend fun updatePasswordEntries(entries: List<PasswordEntry>)
    
    @Delete
    suspend fun deletePasswordEntry(entry: PasswordEntry)

    @Delete
    suspend fun deletePasswordEntries(entries: List<PasswordEntry>)
    
    @Query("DELETE FROM password_entries WHERE id = :id")
    suspend fun deletePasswordEntryById(id: Long)
    
    @Query("UPDATE password_entries SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean)

    @Query("UPDATE password_entries SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateUpdatedAt(id: Long, updatedAt: java.util.Date)
    
    @Query("UPDATE password_entries SET isGroupCover = :isGroupCover WHERE id = :id")
    suspend fun updateGroupCoverStatus(id: Long, isGroupCover: Boolean)
    
    @Query("UPDATE password_entries SET isGroupCover = 0 WHERE website = :website")
    suspend fun clearGroupCover(website: String)
    
    @Query("UPDATE password_entries SET categoryId = :categoryId WHERE id IN (:ids)")
    suspend fun updateCategoryForPasswords(ids: List<Long>, categoryId: Long?)

    @Query("UPDATE password_entries SET boundNoteId = :noteId WHERE id = :id")
    suspend fun updateBoundNoteId(id: Long, noteId: Long?)

    @Query("UPDATE password_entries SET boundNoteId = NULL WHERE boundNoteId = :noteId")
    suspend fun clearBoundNoteReferences(noteId: Long)

    /**
     * 将指定条目绑定到 Bitwarden 文件夹
     * 仅作用于非 KeePass 条目，避免跨数据源污染。
     */
    @Query("""
        UPDATE password_entries
        SET bitwarden_vault_id = :vaultId,
            bitwarden_folder_id = :folderId,
            bitwarden_local_modified = CASE
                WHEN bitwarden_cipher_id IS NOT NULL AND COALESCE(bitwarden_folder_id, '') != :folderId THEN 1
                ELSE bitwarden_local_modified
            END
        WHERE id IN (:ids)
          AND keepassDatabaseId IS NULL
          AND isDeleted = 0
          AND isArchived = 0
    """)
    suspend fun bindPasswordsToBitwardenFolder(ids: List<Long>, vaultId: Long, folderId: String)

    /**
     * 清理未上传条目的 Bitwarden 绑定
     * 仅清理还没有 cipherId 的本地待上传条目，避免破坏已同步映射。
     */
    @Query("""
        UPDATE password_entries
        SET bitwarden_vault_id = NULL,
            bitwarden_folder_id = NULL,
            bitwarden_local_modified = 0
        WHERE id IN (:ids)
          AND bitwarden_cipher_id IS NULL
          AND isDeleted = 0
          AND isArchived = 0
    """)
    suspend fun clearPendingBitwardenBinding(ids: List<Long>)

    /**
     * 按分类批量绑定到 Bitwarden（用于“立即应用”）
     */
    @Query("""
        UPDATE password_entries
        SET bitwarden_vault_id = :vaultId,
            bitwarden_folder_id = :folderId,
            bitwarden_local_modified = CASE
                WHEN bitwarden_cipher_id IS NOT NULL AND COALESCE(bitwarden_folder_id, '') != :folderId THEN 1
                ELSE bitwarden_local_modified
            END
        WHERE categoryId = :categoryId
          AND keepassDatabaseId IS NULL
          AND isDeleted = 0
          AND isArchived = 0
    """)
    suspend fun bindCategoryToBitwarden(categoryId: Long, vaultId: Long, folderId: String)
    
    @Query(
        """
        UPDATE password_entries
        SET keepassDatabaseId = :databaseId,
            keepassGroupPath = NULL,
            keepass_entry_uuid = NULL,
            keepass_group_uuid = NULL,
            bitwarden_vault_id = NULL,
            bitwarden_folder_id = NULL,
            bitwarden_cipher_id = NULL,
            bitwarden_revision_date = NULL,
            bitwarden_local_modified = 0
        WHERE id IN (:ids)
        """
    )
    suspend fun updateKeePassDatabaseForPasswords(ids: List<Long>, databaseId: Long?)


    @Query(
        """
        UPDATE password_entries
        SET mdbx_database_id = :databaseId,
            mdbx_folder_id = :folderId,
            keepassDatabaseId = NULL,
            keepassGroupPath = NULL,
            keepass_entry_uuid = NULL,
            keepass_group_uuid = NULL,
            bitwarden_vault_id = NULL,
            bitwarden_cipher_id = NULL,
            bitwarden_folder_id = NULL,
            bitwarden_revision_date = NULL,
            bitwarden_local_modified = 0,
            updatedAt = :now
        WHERE id IN (:ids)
        """
    )
    suspend fun updateMdbxDatabaseForPasswords(
        ids: List<Long>,
        databaseId: Long?,
        folderId: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE password_entries
        SET keepassDatabaseId = :databaseId,
            keepassGroupPath = :groupPath,
            keepass_entry_uuid = NULL,
            keepass_group_uuid = NULL,
            bitwarden_vault_id = NULL,
            bitwarden_folder_id = NULL,
            bitwarden_cipher_id = NULL,
            bitwarden_revision_date = NULL,
            bitwarden_local_modified = 0
        WHERE id IN (:ids)
        """
    )
    suspend fun updateKeePassGroupForPasswords(ids: List<Long>, databaseId: Long, groupPath: String)

    @Query(
        """
        UPDATE password_entries
        SET keepassDatabaseId = NULL,
            keepassGroupPath = NULL,
            keepass_entry_uuid = NULL,
            keepass_group_uuid = NULL
        WHERE keepassDatabaseId = :databaseId
        """
    )
    suspend fun clearKeePassBindingForDatabase(databaseId: Long)

    @Query("DELETE FROM password_entries WHERE keepassDatabaseId = :databaseId")
    suspend fun deleteByKeePassDatabaseId(databaseId: Long)

    @Query(
        """
        UPDATE password_entries
        SET bitwarden_vault_id = NULL,
            bitwarden_folder_id = NULL,
            bitwarden_local_modified = 0
        WHERE id IN (:ids)
          AND isDeleted = 0
          AND isArchived = 0
        """
    )
    suspend fun clearBitwardenBindingForPasswords(ids: List<Long>)

    @Transaction
    suspend fun setGroupCover(id: Long, website: String) {
        // 先清除该分组的所有封面标记
        clearGroupCover(website)
        // 再设置新的封面
        updateGroupCoverStatus(id, true)
    }

    @Query("UPDATE password_entries SET appPackageName = :packageName, appName = :appName, updatedAt = :now WHERE website = :website AND website != ''")
    suspend fun updateAppAssociationByWebsite(
        website: String,
        packageName: String,
        appName: String,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE password_entries SET appPackageName = :packageName, appName = :appName, updatedAt = :now WHERE title = :title AND title != ''")
    suspend fun updateAppAssociationByTitle(
        title: String,
        packageName: String,
        appName: String,
        now: Long = System.currentTimeMillis()
    )

    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE website = :website
          AND website != ''
          AND mdbx_database_id IS NOT NULL
          AND isDeleted = 0
          AND isArchived = 0
        """
    )
    suspend fun getActiveMdbxEntriesByWebsite(website: String): List<PasswordEntry>

    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE title = :title
          AND title != ''
          AND mdbx_database_id IS NOT NULL
          AND isDeleted = 0
          AND isArchived = 0
        """
    )
    suspend fun getActiveMdbxEntriesByTitle(title: String): List<PasswordEntry>

    /**
     * 更新绑定的验证器密钥
     */
    @Query("UPDATE password_entries SET authenticatorKey = :authenticatorKey WHERE id = :id")
    suspend fun updateAuthenticatorKey(id: Long, authenticatorKey: String)

    /**
     * 更新绑定的通行密钥元数据
     */
    @Query("UPDATE password_entries SET passkey_bindings = :passkeyBindings WHERE id = :id")
    suspend fun updatePasskeyBindings(id: Long, passkeyBindings: String)

    
    @Query("UPDATE password_entries SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)
    
    @Transaction
    suspend fun updateSortOrders(items: List<Pair<Long, Int>>) {
        items.forEach { (id, sortOrder) ->
            updateSortOrder(id, sortOrder)
        }
    }
    
    @Query("SELECT COUNT(*) FROM password_entries")
    suspend fun getPasswordEntriesCount(): Int
    
    @Query("DELETE FROM password_entries")
    suspend fun deleteAllPasswordEntries()

    @Query(
        """
        DELETE FROM password_entries
        WHERE bitwarden_vault_id IS NULL
          AND keepassDatabaseId IS NULL
          AND mdbx_database_id IS NULL
        """
    )
    suspend fun deleteAllLocalPasswordEntries()

    @Query("DELETE FROM password_entries WHERE mdbx_database_id = :databaseId")
    suspend fun deleteAllByMdbxDatabaseId(databaseId: Long)

    @Transaction
    @Query("SELECT * FROM password_entries WHERE mdbx_database_id = :databaseId AND isDeleted = 0 AND isArchived = 0")
    suspend fun getByMdbxDatabaseIdSync(databaseId: Long): List<PasswordEntry>
    
    /**
     * 检查是否存在相同的密码条目(根据title、username、website匹配)
     */
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND title = :title AND username = :username AND website = :website LIMIT 1")
    suspend fun findDuplicateEntry(title: String, username: String, website: String): PasswordEntry?

    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND isDeleted = 0
          AND isArchived = 0
          AND LOWER(title) = :title
          AND LOWER(username) = :username
          AND LOWER(website) = :website
        ORDER BY updatedAt DESC, id DESC
        """
    )
    suspend fun findActiveDuplicateCandidatesByKey(
        title: String,
        username: String,
        website: String
    ): List<PasswordEntry>

    @Query(
        """
        SELECT * FROM password_entries
        WHERE keepassDatabaseId = :databaseId
          AND title = :title
          AND username = :username
          AND website = :website
          AND (
            (keepassGroupPath IS NULL AND :groupPath IS NULL)
            OR keepassGroupPath = :groupPath
          )
        LIMIT 1
        """
    )
    suspend fun findDuplicateEntryInKeePass(
        databaseId: Long,
        title: String,
        username: String,
        website: String,
        groupPath: String?
    ): PasswordEntry?

    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE isDeleted = 0 AND keepassDatabaseId = :databaseId
        """
    )
    suspend fun getPasswordEntriesByKeePassDatabaseSync(databaseId: Long): List<PasswordEntry>
    
    /**
     * 按包名和用户名查询密码
     * 用于自动填充保存时检测重复
     */
    @Query(
        """
        SELECT * FROM password_entries
        WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND LOWER(username) = LOWER(:username)
          AND isDeleted = 0
          AND isArchived = 0
          AND instr(
            '|' || LOWER(REPLACE(REPLACE(appPackageName, ',', '|'), ';', '|')) || '|',
            '|' || LOWER(:packageName) || '|'
          ) > 0
        LIMIT 1
        """
    )
    suspend fun findByPackageAndUsername(packageName: String, username: String): PasswordEntry?
    
    /**
     * 按网站和用户名查询密码
     * 用于自动填充保存时检测重复
     */
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND LOWER(website) LIKE '%' || LOWER(:domain) || '%' AND LOWER(username) = LOWER(:username) AND isDeleted = 0 AND isArchived = 0 LIMIT 1")
    suspend fun findByDomainAndUsername(domain: String, username: String): PasswordEntry?
    
    /**
     * 按包名查询所有密码
     * 用于检测同一应用的多个账号
     */
    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0
          AND isArchived = 0
          AND instr(
            '|' || LOWER(REPLACE(REPLACE(appPackageName, ',', '|'), ';', '|')) || '|',
            '|' || LOWER(:packageName) || '|'
          ) > 0) ORDER BY updatedAt DESC
        """
    )
    suspend fun findByPackageName(packageName: String): List<PasswordEntry>
    
    /**
     * 按网站域名查询所有密码
     * 用于检测同一网站的多个账号
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (LOWER(website) LIKE '%' || LOWER(:domain) || '%' AND isDeleted = 0 AND isArchived = 0) ORDER BY updatedAt DESC")
    suspend fun findByDomain(domain: String): List<PasswordEntry>
    
    /**
     * 检查是否存在完全相同的密码(包名+用户名+密码)
     * 用于避免重复保存
     */
    @Query(
        """
        SELECT * FROM password_entries
        WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND LOWER(username) = LOWER(:username)
          AND password = :encryptedPassword
          AND isDeleted = 0
          AND isArchived = 0
          AND instr(
            '|' || LOWER(REPLACE(REPLACE(appPackageName, ',', '|'), ';', '|')) || '|',
            '|' || LOWER(:packageName) || '|'
          ) > 0
        LIMIT 1
        """
    )
    suspend fun findExactMatch(packageName: String, username: String, encryptedPassword: String): PasswordEntry?
    
    // =============== 回收站相关方法 ===============
    
    /**
     * 获取所有已删除的条目（回收站）
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 1) ORDER BY deletedAt DESC")
    fun getDeletedEntries(): Flow<List<PasswordEntry>>
    
    /**
     * 获取所有已删除的条目（同步版本，用于备份）
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 1) ORDER BY deletedAt DESC")
    suspend fun getDeletedEntriesSync(): List<PasswordEntry>
    
    /**
     * 获取所有未删除的密码条目（同步版本，用于KeePass导出）
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 0) ORDER BY isFavorite DESC, sortOrder ASC, updatedAt DESC")
    suspend fun getAllPasswordEntriesSync(): List<PasswordEntry>
    
    /**
     * 检查是否存在相同的条目（用于KeePass导入去重）
     */
    @Query("SELECT COUNT(*) FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND title = :title AND username = :username AND website = :website")
    suspend fun countByTitleUsernameWebsite(title: String, username: String, website: String): Int
    
    /**
     * 获取所有未删除的条目（正常条目）
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 0) ORDER BY isFavorite DESC, sortOrder ASC, updatedAt DESC")
    fun getActiveEntries(): Flow<List<PasswordEntry>>

    // Do not materialize every password row just to find password-bound OTPs.
    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 0 AND authenticatorKey != '') ORDER BY isFavorite DESC, sortOrder ASC, updatedAt DESC")
    fun getActiveAuthenticatorEntries(): Flow<List<PasswordEntry>>

    @Query("SELECT id, title FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 0) ")
    fun getActivePasswordTitles(): Flow<List<PasswordTitle>>

    /**
     * 获取归档中的密码条目
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE $MDBX_AVAILABLE_ENTRY_FILTER AND (isDeleted = 0 AND isArchived = 1) ORDER BY archivedAt DESC, updatedAt DESC")
    fun getArchivedEntries(): Flow<List<PasswordEntry>>

    /**
     * 归档单个密码条目
     */
    @Query("UPDATE password_entries SET isArchived = 1, archivedAt = :archivedAt, updatedAt = :updatedAt WHERE id = :id AND isDeleted = 0")
    suspend fun archiveById(id: Long, archivedAt: java.util.Date = java.util.Date(), updatedAt: java.util.Date = java.util.Date())

    /**
     * 批量归档密码条目
     */
    @Query("UPDATE password_entries SET isArchived = 1, archivedAt = :archivedAt, updatedAt = :updatedAt WHERE id IN (:ids) AND isDeleted = 0")
    suspend fun archiveByIds(ids: List<Long>, archivedAt: java.util.Date = java.util.Date(), updatedAt: java.util.Date = java.util.Date())

    /**
     * 取消归档单个密码条目
     */
    @Query("UPDATE password_entries SET isArchived = 0, archivedAt = NULL, updatedAt = :updatedAt WHERE id = :id AND isDeleted = 0")
    suspend fun unarchiveById(id: Long, updatedAt: java.util.Date = java.util.Date())

    /**
     * 批量取消归档密码条目
     */
    @Query("UPDATE password_entries SET isArchived = 0, archivedAt = NULL, updatedAt = :updatedAt WHERE id IN (:ids) AND isDeleted = 0")
    suspend fun unarchiveByIds(ids: List<Long>, updatedAt: java.util.Date = java.util.Date())
    
    /**
     * 软删除条目（移动到回收站）
     */
    @Query("UPDATE password_entries SET isDeleted = 1, deletedAt = :deletedAt, isArchived = 0, archivedAt = NULL WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long = System.currentTimeMillis())
    
    /**
     * 恢复已删除的条目
     */
    @Query("UPDATE password_entries SET isDeleted = 0, deletedAt = NULL, isArchived = 0, archivedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)
    
    /**
     * 永久删除所有回收站中的条目
     */
    @Query("DELETE FROM password_entries WHERE isDeleted = 1")
    suspend fun permanentlyDeleteAll()
    
    /**
     * 删除过期的回收站条目（超过指定天数）
     */
    @Query("DELETE FROM password_entries WHERE isDeleted = 1 AND deletedAt < :cutoffDate")
    suspend fun deleteExpiredItems(cutoffDate: java.util.Date)
    
    /**
     * 获取回收站条目数量
     */
    @Query("SELECT COUNT(*) FROM password_entries WHERE isDeleted = 1")
    suspend fun getDeletedCount(): Int
    
    /**
     * 更新条目（用于恢复等操作）
     */
    @Update
    suspend fun update(entry: PasswordEntry)

    /**
     * 批量更新条目（用于回收站批量恢复等场景）
     */
    @Update
    suspend fun updateAll(entries: List<PasswordEntry>)
    
    /**
     * 删除条目
     */
    @Delete
    suspend fun delete(entry: PasswordEntry)
    
    // ==================== Bitwarden 集成相关方法 ====================
    
    /**
     * 根据 Bitwarden Cipher ID 获取条目
     * 用于同步时查找本地是否已存在对应条目
     */
    @Query("SELECT * FROM password_entries WHERE bitwarden_cipher_id = :cipherId LIMIT 1")
    suspend fun getByBitwardenCipherId(cipherId: String): PasswordEntry?

    @Query(
        """
        SELECT * FROM password_entries
        WHERE bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id = :cipherId
        LIMIT 1
        """
    )
    suspend fun getByBitwardenCipherIdInVault(vaultId: Long, cipherId: String): PasswordEntry?

    /**
     * 根据 Bitwarden Cipher ID 获取所有条目（用于清理历史重复数据）
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE bitwarden_cipher_id = :cipherId")
    suspend fun getAllByBitwardenCipherId(cipherId: String): List<PasswordEntry>

    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id = :cipherId
        """
    )
    suspend fun getAllByBitwardenCipherIdInVault(vaultId: Long, cipherId: String): List<PasswordEntry>

    /**
     * 删除指定 Vault 下所有已同步的 Bitwarden 密码条目。
     * 仅删除未进入回收站的条目，避免覆盖本地删除墓碑。
     */
    @Query("""
        DELETE FROM password_entries
        WHERE bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id IS NOT NULL
          AND isDeleted = 0
    """)
    suspend fun deleteAllSyncedBitwardenEntries(vaultId: Long)

    /**
     * 清理服务器不存在的 Bitwarden 密码条目（delete-wins）。
     */
    @Query("""
        DELETE FROM password_entries
        WHERE bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id IS NOT NULL
          AND isDeleted = 0
          AND bitwarden_cipher_id NOT IN (:keepIds)
          AND NOT EXISTS (
              SELECT 1
              FROM bitwarden_pending_operations op
              WHERE op.vault_id = :vaultId
                AND op.bitwarden_cipher_id = password_entries.bitwarden_cipher_id
                AND op.operation_type = 'RESTORE'
                AND op.status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
          )
    """)
    suspend fun deleteBitwardenEntriesNotIn(vaultId: Long, keepIds: List<String>)

        /**
         * 查找本地重复条目（仅本地库）
         * 用于 Bitwarden 同步时合并本地条目，避免重复
         */
        @Query("""
                SELECT * FROM password_entries
                WHERE bitwarden_vault_id IS NULL
                    AND keepassDatabaseId IS NULL
                    AND mdbx_database_id IS NULL
                    AND isDeleted = 0
                    AND LOWER(title) = :title
                    AND LOWER(username) = :username
                    AND LOWER(website) = :website
                LIMIT 1
        """)
        suspend fun findLocalDuplicateByKey(title: String, username: String, website: String): PasswordEntry?

    @Transaction
        @Query(
            """
                SELECT * FROM password_entries
                WHERE bitwarden_vault_id IS NULL
                    AND keepassDatabaseId IS NULL
                    AND mdbx_database_id IS NULL
                    AND isDeleted = 0
                    AND isArchived = 0
                    AND LOWER(title) = :title
                    AND LOWER(username) = :username
                    AND LOWER(website) = :website
                ORDER BY updatedAt DESC, id DESC
        """
        )
        suspend fun findLocalDuplicateCandidatesByKey(
            title: String,
            username: String,
            website: String
        ): List<PasswordEntry>
    
    /**
     * 根据 Bitwarden Vault ID 获取所有条目
     * 用于获取某个 Vault 的所有密码
     */
    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE isDeleted = 0
          AND isArchived = 0
          AND bitwarden_vault_id = :vaultId
        ORDER BY title ASC
        """
    )
    suspend fun getByBitwardenVaultId(vaultId: Long): List<PasswordEntry>
    
    /**
     * 根据 Bitwarden Vault ID 获取所有条目 (Flow 版本)
     * 用于实时观察 Vault 的密码列表变化
     */
    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE isDeleted = 0
          AND isArchived = 0
          AND bitwarden_vault_id = :vaultId
        ORDER BY title ASC
        """
    )
    fun getByBitwardenVaultIdFlow(vaultId: Long): kotlinx.coroutines.flow.Flow<List<PasswordEntry>>
    
    /**
     * 获取所有 Bitwarden 条目
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE bitwarden_vault_id IS NOT NULL AND isDeleted = 0 AND isArchived = 0 ORDER BY title ASC")
    suspend fun getAllBitwardenEntries(): List<PasswordEntry>
    
    /**
     * 获取待同步到 Bitwarden 的条目（本地有修改）
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE bitwarden_vault_id = :vaultId AND bitwarden_local_modified = 1 AND isDeleted = 0")
    suspend fun getEntriesWithPendingBitwardenSync(vaultId: Long): List<PasswordEntry>
    
    /**
     * 获取所有待同步的 Bitwarden 条目（跨所有 Vault）
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE bitwarden_vault_id IS NOT NULL AND bitwarden_local_modified = 1 AND isDeleted = 0")
    suspend fun getAllEntriesWithPendingBitwardenSync(): List<PasswordEntry>
    
    /**
     * 清除指定 Vault 的所有条目的本地修改标记
     */
    @Query("UPDATE password_entries SET bitwarden_local_modified = 0 WHERE bitwarden_vault_id = :vaultId")
    suspend fun clearBitwardenLocalModifiedFlag(vaultId: Long)
    
    /**
     * 更新条目的 Bitwarden 同步信息
     */
    @Query("""
        UPDATE password_entries 
        SET bitwarden_revision_date = :revisionDate, 
            bitwarden_local_modified = 0 
        WHERE id = :entryId
    """)
    suspend fun updateBitwardenSyncInfo(entryId: Long, revisionDate: String?)
    
    @Transaction
    @Query("SELECT * FROM password_entries WHERE bitwarden_folder_id = :folderId AND isDeleted = 0 AND isArchived = 0 ORDER BY title ASC")
    fun getByBitwardenFolderIdFlow(folderId: String): kotlinx.coroutines.flow.Flow<List<PasswordEntry>>

    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE bitwarden_vault_id = :vaultId
          AND bitwarden_folder_id = :folderId
          AND isDeleted = 0
          AND isArchived = 0
        ORDER BY title ASC
        """
    )
    fun getByBitwardenFolderIdFlow(vaultId: Long, folderId: String): kotlinx.coroutines.flow.Flow<List<PasswordEntry>>

    /**
     * 根据 Bitwarden Folder ID 获取条目
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE bitwarden_folder_id = :folderId AND isDeleted = 0 AND isArchived = 0 ORDER BY title ASC")
    suspend fun getByBitwardenFolderId(folderId: String): List<PasswordEntry>

    @Transaction
    @Query(
        """
        SELECT * FROM password_entries
        WHERE bitwarden_vault_id = :vaultId
          AND bitwarden_folder_id = :folderId
          AND isDeleted = 0
          AND isArchived = 0
        ORDER BY title ASC
        """
    )
    suspend fun getByBitwardenFolderId(vaultId: Long, folderId: String): List<PasswordEntry>
    
    /**
     * 获取 Bitwarden 条目按类型分组
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE bitwarden_vault_id = :vaultId AND bitwarden_cipher_type = :cipherType AND isDeleted = 0 AND isArchived = 0 ORDER BY title ASC")
    suspend fun getBitwardenEntriesByCipherType(vaultId: Long, cipherType: Int): List<PasswordEntry>
    
    /**
     * 搜索 Bitwarden 条目
     */
    @Transaction
    @Query("""
        SELECT * FROM password_entries 
        WHERE bitwarden_vault_id = :vaultId 
        AND isDeleted = 0 
        AND isArchived = 0
        AND (title LIKE '%' || :query || '%' 
             OR username LIKE '%' || :query || '%' 
             OR notes LIKE '%' || :query || '%')
        ORDER BY title ASC
    """)
    suspend fun searchBitwardenEntries(vaultId: Long, query: String): List<PasswordEntry>
    
    /**
     * 删除指定 Vault 的所有条目（用于注销时清理）
     */
    @Query("DELETE FROM password_entries WHERE bitwarden_vault_id = :vaultId")
    suspend fun deleteAllByBitwardenVaultId(vaultId: Long)
    
    /**
     * 获取 Bitwarden 条目数量
     */
    @Query("SELECT COUNT(*) FROM password_entries WHERE bitwarden_vault_id = :vaultId AND isDeleted = 0 AND isArchived = 0")
    suspend fun getBitwardenEntryCount(vaultId: Long): Int
    
    /**
     * 获取指定 Vault 的最后修改时间
     */
    @Query("SELECT MAX(bitwarden_revision_date) FROM password_entries WHERE bitwarden_vault_id = :vaultId")
    suspend fun getLastBitwardenRevisionDate(vaultId: Long): String?
    
    // ==================== V2 多源密码库相关方法 ====================
    
    /**
     * 获取纯本地条目数量（非 Bitwarden、非 KeePass）
     * 用于 V2 多源密码库统计
     */
    @Query("SELECT COUNT(*) FROM password_entries WHERE bitwarden_vault_id IS NULL AND keepassDatabaseId IS NULL AND mdbx_database_id IS NULL AND isDeleted = 0 AND isArchived = 0")
    suspend fun getLocalEntriesCount(): Int

    /**
     * 获取本地已删除条目数量（非 Bitwarden、非 KeePass）
     * 用于回收站统计
     */
    @Query("SELECT COUNT(*) FROM password_entries WHERE bitwarden_vault_id IS NULL AND keepassDatabaseId IS NULL AND mdbx_database_id IS NULL AND isDeleted = 1")
    suspend fun getLocalDeletedEntriesCount(): Int

    /**
     * 获取指定 Bitwarden Vault 的条目数量
     * 用于 V2 多源密码库统计
     */
    @Query("SELECT COUNT(*) FROM password_entries WHERE bitwarden_vault_id = :vaultId AND isDeleted = 0 AND isArchived = 0")
    suspend fun getBitwardenEntriesCount(vaultId: Long): Int
    
    /**
     * 获取 KeePass 条目数量
     * 用于 V2 多源密码库统计
     */
    @Query("SELECT COUNT(*) FROM password_entries WHERE keepassDatabaseId IS NOT NULL AND isDeleted = 0 AND isArchived = 0")
    suspend fun getKeePassEntriesCount(): Int

    /**
     * 获取所有纯本地条目（非 Bitwarden、非 KeePass）
     * 用于 V2 多源密码库显示
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE bitwarden_vault_id IS NULL AND keepassDatabaseId IS NULL AND mdbx_database_id IS NULL AND isDeleted = 0 AND isArchived = 0 ORDER BY isFavorite DESC, updatedAt DESC")
    suspend fun getAllLocalEntries(): List<PasswordEntry>
    
    /**
     * 获取所有 KeePass 条目
     * 用于 V2 多源密码库显示
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE keepassDatabaseId IS NOT NULL AND isDeleted = 0 AND isArchived = 0 ORDER BY isFavorite DESC, updatedAt DESC")
    suspend fun getAllKeePassEntries(): List<PasswordEntry>
    /**
     * 获取指定 Bitwarden Vault 的所有条目
     * 用于 V2 多源密码库显示
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE bitwarden_vault_id = :vaultId AND isDeleted = 0 AND isArchived = 0 ORDER BY isFavorite DESC, updatedAt DESC")
    suspend fun getEntriesByVaultId(vaultId: Long): List<PasswordEntry>
    
    /**
     * 获取待上传到 Bitwarden 的本地条目
     * 这些条目有 bitwardenVaultId（表示属于某个 Bitwarden vault）
     * 但没有 bitwardenCipherId（表示尚未上传到服务器）
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE bitwarden_vault_id = :vaultId AND bitwarden_cipher_id IS NULL AND isDeleted = 0")
    suspend fun getLocalEntriesPendingUpload(vaultId: Long): List<PasswordEntry>

    /**
     * 修复历史上缺失待上传标记的 Bitwarden 本地新建条目。
     */
    @Query(
        """
        UPDATE password_entries
        SET bitwarden_local_modified = 1
        WHERE bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id IS NULL
          AND bitwarden_local_modified = 0
          AND isDeleted = 0
        """
    )
    suspend fun markHistoricalPendingBitwardenUploads(vaultId: Long): Int

    /**
     * 标记所有未关联 Bitwarden 的条目为指定 Vault
     */
    @Query("UPDATE password_entries SET bitwarden_vault_id = :vaultId WHERE bitwarden_vault_id IS NULL AND isDeleted = 0")
    suspend fun markAllForBitwarden(vaultId: Long)
    
    /**
     * 获取本地已修改但未同步的 Bitwarden 条目
     */
    @Transaction
    @Query("SELECT * FROM password_entries WHERE bitwarden_vault_id = :vaultId AND bitwarden_local_modified = 1 AND isDeleted = 0")
    suspend fun getLocalModifiedBitwardenEntries(vaultId: Long): List<PasswordEntry>
}
