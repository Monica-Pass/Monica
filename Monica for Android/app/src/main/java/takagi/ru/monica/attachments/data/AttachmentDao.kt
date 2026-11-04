package takagi.ru.monica.attachments.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import takagi.ru.monica.attachments.model.Attachment

/**
 * 附件元数据表 DAO。
 *
 * 设计原则：
 * - 所有查询默认按 `parent_password_id` 的索引过滤，避免全表扫描。
 * - 软删除不用 `DELETE`，统一用 `is_deleted = 1` 标记；真删除走 [deleteById]/[purgeByParent]。
 * - 按 `created_at` 升序返回，保证附件在 UI 上的展示顺序与添加顺序一致。
 */
@Dao
interface AttachmentDao {
    /** Queue metadata only: no path, CEK, payload, URL, or remote attachment key is selected. */
    @Query("""
        SELECT 'attachment:' || a.id AS queueKey,
            COALESCE(NULLIF(p.bitwarden_cipher_id, ''), NULLIF(s.bitwarden_cipher_id, ''),
                'local:' || COALESCE(p.id, s.id)) || '/attachment:' || a.id AS itemId,
            a.file_name AS title, 'ATTACHMENT' AS kind, 'CREATE' AS operation, 'PENDING' AS status
        FROM attachments a
        LEFT JOIN password_entries p ON a.parent_password_id = p.id AND a.parent_secure_item_id IS NULL
        LEFT JOIN secure_items s ON a.parent_secure_item_id = s.id AND a.parent_password_id IS NULL
        WHERE a.id IN (:ids) AND a.source = 'LOCAL' AND a.is_deleted = 0 AND (
            (p.bitwarden_vault_id = :vaultId AND p.isDeleted = 0 AND p.keepassDatabaseId IS NULL AND p.mdbx_database_id IS NULL)
            OR (s.bitwarden_vault_id = :vaultId AND s.isDeleted = 0 AND s.keepass_database_id IS NULL AND s.mdbx_database_id IS NULL)
        )
        ORDER BY a.id
    """)
    fun observeBitwardenPendingMetadata(vaultId: Long, ids: List<Long>): Flow<List<takagi.ru.monica.data.bitwarden.BitwardenSyncQueueRecord>>

    @Query("SELECT COUNT(*) FROM attachments WHERE parent_password_id IN (:ids) AND is_deleted = 0")
    suspend fun countExportMetadataByPasswords(ids: List<Long>): Int

    @Query("SELECT COUNT(*) FROM attachments WHERE parent_secure_item_id IN (:ids) AND is_deleted = 0")
    suspend fun countExportMetadataBySecureItems(ids: List<Long>): Int



    @Query("SELECT * FROM attachments WHERE parent_password_id IN (:ids) AND is_deleted = 0 ORDER BY created_at, id")
    suspend fun getActiveByParents(ids: List<Long>): List<Attachment>

    @Query("SELECT * FROM attachments WHERE parent_secure_item_id IN (:ids) AND is_deleted = 0 ORDER BY created_at, id")
    suspend fun getActiveBySecureItems(ids: List<Long>): List<Attachment>

    // ---------------------------------------------------------------- 查询

    @Query(
        """
        SELECT * FROM attachments
        WHERE parent_password_id = :passwordId AND is_deleted = 0
        ORDER BY created_at ASC
        """
    )
    fun observeActiveByParent(passwordId: Long): Flow<List<Attachment>>

    @Query(
        """
        SELECT * FROM attachments
        WHERE parent_secure_item_id = :secureItemId AND is_deleted = 0
        ORDER BY created_at ASC
        """
    )
    fun observeActiveBySecureItem(secureItemId: Long): Flow<List<Attachment>>

    @Query(
        """
        SELECT * FROM attachments
        WHERE parent_password_id = :passwordId
        ORDER BY created_at ASC
        """
    )
    suspend fun getAllByParent(passwordId: Long): List<Attachment>

    @Query(
        """
        SELECT * FROM attachments
        WHERE parent_secure_item_id = :secureItemId
        ORDER BY created_at ASC
        """
    )
    suspend fun getAllBySecureItem(secureItemId: Long): List<Attachment>

    @Query(
        """
        SELECT * FROM attachments
        WHERE parent_password_id = :passwordId AND is_deleted = 0
        ORDER BY created_at ASC
        """
    )
    suspend fun getActiveByParent(passwordId: Long): List<Attachment>

    @Query(
        """
        SELECT * FROM attachments
        WHERE parent_secure_item_id = :secureItemId AND is_deleted = 0
        ORDER BY created_at ASC
        """
    )
    suspend fun getActiveBySecureItem(secureItemId: Long): List<Attachment>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getById(id: Long): Attachment?

    @Query(
        """
        SELECT * FROM attachments
        WHERE parent_password_id = :passwordId AND source = :source
        """
    )
    suspend fun getByParentAndSource(passwordId: Long, source: String): List<Attachment>

    @Query(
        """
        SELECT * FROM attachments
        WHERE parent_secure_item_id = :secureItemId AND source = :source
        """
    )
    suspend fun getBySecureItemAndSource(secureItemId: Long, source: String): List<Attachment>

    @Query("SELECT * FROM attachments WHERE bitwarden_attachment_id = :attachmentId LIMIT 1")
    suspend fun findByBitwardenAttachmentId(attachmentId: String): Attachment?

    @Query(
        """
        SELECT COUNT(*) FROM attachments
        WHERE parent_password_id = :passwordId AND is_deleted = 0
        """
    )
    suspend fun countActiveByParent(passwordId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM attachments
        WHERE parent_secure_item_id = :secureItemId AND is_deleted = 0
        """
    )
    suspend fun countActiveBySecureItem(secureItemId: Long): Int

    @Query("SELECT COUNT(*) FROM attachments WHERE parent_password_id IN (:passwordIds) AND is_deleted = 0")
    suspend fun countActiveByParents(passwordIds: List<Long>): Int

    @Query(
        """
        SELECT parent_password_id FROM attachments
        WHERE parent_password_id IN (:passwordIds) AND is_deleted = 0
        GROUP BY parent_password_id
        """
    )
    suspend fun parentsWithActiveAttachments(passwordIds: List<Long>): List<Long>

    @Query(
        """
        SELECT parent_password_id FROM attachments
        WHERE is_deleted = 0
        GROUP BY parent_password_id
        """
    )
    fun observeParentsWithActiveAttachments(): Flow<List<Long>>

    @Query(
        """
        SELECT parent_secure_item_id FROM attachments
        WHERE parent_secure_item_id IN (:secureItemIds) AND is_deleted = 0
        GROUP BY parent_secure_item_id
        """
    )
    suspend fun secureItemsWithActiveAttachments(secureItemIds: List<Long>): List<Long>

    @Query(
        """
        SELECT parent_secure_item_id FROM attachments
        WHERE parent_secure_item_id IS NOT NULL AND is_deleted = 0
        GROUP BY parent_secure_item_id
        """
    )
    fun observeSecureItemsWithActiveAttachments(): Flow<List<Long>>

    @Query("SELECT local_path FROM attachments WHERE local_path IS NOT NULL")
    suspend fun selectAllLocalPaths(): List<String?>

    @Query(
        """
        SELECT DISTINCT attachments.local_path FROM attachments
        INNER JOIN password_entries
            ON password_entries.id = attachments.parent_password_id
        WHERE password_entries.mdbx_database_id = :databaseId
          AND attachments.local_path IS NOT NULL
        """
    )
    suspend fun selectLocalPathsByMdbxDatabaseId(databaseId: Long): List<String>

    @Query(
        """
        SELECT DISTINCT attachments.local_path FROM attachments
        INNER JOIN secure_items
            ON secure_items.id = attachments.parent_secure_item_id
        WHERE secure_items.mdbx_database_id = :databaseId
          AND attachments.local_path IS NOT NULL
        """
    )
    suspend fun selectSecureItemLocalPathsByMdbxDatabaseId(databaseId: Long): List<String>

    @Query("SELECT COUNT(*) FROM attachments WHERE local_path = :localPath")
    suspend fun countByLocalPath(localPath: String): Int

    /** 列出所有未软删除且本地已下载（有 local_path 与 wrappedCek）的附件，供备份 / 迁移使用。 */
    @Query(
        """
        SELECT * FROM attachments
        WHERE is_deleted = 0
          AND local_path IS NOT NULL
          AND wrapped_cek IS NOT NULL
        """
    )
    suspend fun selectAllActiveLocalAttachments(): List<Attachment>

    /**
     * 将某密码下指定 `source` 的附件改写为 LOCAL（清掉远端专属字段）。
     *
     * 典型用法：KeePass 密码被移动到 Monica 本地时，把 `source=KEEPASS` 的附件转为 `source=LOCAL`，
     * 本地缓存密文继续可用；Bitwarden 条目转 Monica 本地同理。
     */
    @Query(
        """
        UPDATE attachments
        SET
            source = 'LOCAL',
            bitwarden_attachment_id = NULL,
            bitwarden_url = NULL,
            bitwarden_file_key_enc = NULL,
            keepass_binary_ref = NULL,
            updated_at = :now
        WHERE parent_password_id = :passwordId
          AND source = :fromSource
          AND local_path IS NOT NULL
          AND wrapped_cek IS NOT NULL
          AND is_deleted = 0
        """
    )
    suspend fun rewriteSourceToLocal(passwordId: Long, fromSource: String, now: Long): Int

    @Query(
        """
        UPDATE attachments
        SET
            source = 'LOCAL',
            bitwarden_attachment_id = NULL,
            bitwarden_url = NULL,
            bitwarden_file_key_enc = NULL,
            keepass_binary_ref = NULL,
            updated_at = :now
        WHERE parent_secure_item_id = :secureItemId
          AND source = :fromSource
          AND local_path IS NOT NULL
          AND wrapped_cek IS NOT NULL
          AND is_deleted = 0
        """
    )
    suspend fun rewriteSecureItemSourceToLocal(
        secureItemId: Long,
        fromSource: String,
        now: Long
    ): Int

    // ---------------------------------------------------------------- 写入

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attachment: Attachment): Long

    @Update
    suspend fun update(attachment: Attachment): Int

    @Delete
    suspend fun delete(attachment: Attachment): Int

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM attachments WHERE parent_password_id = :passwordId")
    suspend fun purgeByParent(passwordId: Long): Int

    @Query("DELETE FROM attachments WHERE parent_secure_item_id = :secureItemId")
    suspend fun purgeBySecureItem(secureItemId: Long): Int

    // ---------------------------------------------------------------- 软删除联动

    @Query(
        """
        UPDATE attachments
        SET is_deleted = 1, deleted_at = :deletedAt, updated_at = :updatedAt
        WHERE parent_password_id = :passwordId AND is_deleted = 0
        """
    )
    suspend fun softDeleteByParent(passwordId: Long, deletedAt: Long, updatedAt: Long): Int

    @Query(
        """
        UPDATE attachments
        SET is_deleted = 1, deleted_at = :deletedAt, updated_at = :updatedAt
        WHERE parent_secure_item_id = :secureItemId AND is_deleted = 0
        """
    )
    suspend fun softDeleteBySecureItem(
        secureItemId: Long,
        deletedAt: Long,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE attachments
        SET is_deleted = 0, deleted_at = NULL, updated_at = :updatedAt
        WHERE parent_password_id = :passwordId AND is_deleted = 1
        """
    )
    suspend fun restoreByParent(passwordId: Long, updatedAt: Long): Int

    @Query(
        """
        UPDATE attachments
        SET is_deleted = 0, deleted_at = NULL, updated_at = :updatedAt
        WHERE parent_secure_item_id = :secureItemId AND is_deleted = 1
        """
    )
    suspend fun restoreBySecureItem(secureItemId: Long, updatedAt: Long): Int

    @Query(
        """
        UPDATE attachments
        SET download_state = :state, updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateDownloadState(id: Long, state: String, updatedAt: Long): Int
}
