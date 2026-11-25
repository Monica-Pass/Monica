package takagi.ru.monica.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 本地 KeePass 数据库存储位置
 */
enum class KeePassStorageLocation {
    INTERNAL,  // 内部存储（Monica 应用目录）
    EXTERNAL   // 外部存储（用户选择的位置）
}

enum class KeePassDatabaseSourceType {
    LOCAL_INTERNAL,
    LOCAL_DOCUMENT_URI,
    REMOTE_WEBDAV,
    REMOTE_ONEDRIVE,
    REMOTE_GOOGLE_DRIVE
}

enum class KeePassOpenMode {
    DIRECT,
    CACHED_MIRROR,
    WORKING_COPY
}

enum class KeePassSyncStatus {
    LOCAL_ONLY,
    IN_SYNC,
    SYNCING,
    PENDING_UPLOAD,
    REMOTE_CHANGED,
    CONFLICT,
    FAILED
}

fun KeePassStorageLocation.toSourceType(): KeePassDatabaseSourceType = when (this) {
    KeePassStorageLocation.INTERNAL -> KeePassDatabaseSourceType.LOCAL_INTERNAL
    KeePassStorageLocation.EXTERNAL -> KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI
}

enum class KeePassFormatVersion(val majorVersion: Int) {
    KDBX3(3),
    KDBX4(4)
}

enum class KeePassCipherAlgorithm {
    AES,
    CHACHA20,
    TWOFISH
}

enum class KeePassKdfAlgorithm {
    AES_KDF,
    ARGON2D,
    ARGON2ID
}

data class KeePassDatabaseCreationOptions(
    val formatVersion: KeePassFormatVersion = KeePassFormatVersion.KDBX4,
    val cipherAlgorithm: KeePassCipherAlgorithm = KeePassCipherAlgorithm.AES,
    val kdfAlgorithm: KeePassKdfAlgorithm = KeePassKdfAlgorithm.ARGON2D,
    val transformRounds: Long = DEFAULT_ARGON_ITERATIONS,
    val memoryBytes: Long = DEFAULT_ARGON_MEMORY_BYTES,
    val parallelism: Int = 2
) {
    fun normalized(): KeePassDatabaseCreationOptions {
        val normalizedVersion = formatVersion
        val normalizedCipher = when (normalizedVersion) {
            KeePassFormatVersion.KDBX3 -> when (cipherAlgorithm) {
                KeePassCipherAlgorithm.CHACHA20 -> KeePassCipherAlgorithm.AES
                else -> cipherAlgorithm
            }
            KeePassFormatVersion.KDBX4 -> cipherAlgorithm
        }
        val normalizedKdf = when (normalizedVersion) {
            KeePassFormatVersion.KDBX3 -> KeePassKdfAlgorithm.AES_KDF
            KeePassFormatVersion.KDBX4 -> kdfAlgorithm
        }
        return copy(
            formatVersion = normalizedVersion,
            cipherAlgorithm = normalizedCipher,
            kdfAlgorithm = normalizedKdf,
            transformRounds = transformRounds.coerceAtLeast(1L),
            memoryBytes = memoryBytes.coerceIn(MIN_MEMORY_BYTES, MAX_MEMORY_BYTES),
            parallelism = parallelism.coerceIn(1, 32)
        )
    }

    companion object {
        const val DEFAULT_ARGON_ITERATIONS = 8L
        const val DEFAULT_AES_KDF_ROUNDS = 600_000L
        const val DEFAULT_ARGON_MEMORY_BYTES = 32L * 1024L * 1024L
        const val MIN_MEMORY_BYTES = 1L * 1024L * 1024L
        const val MAX_MEMORY_BYTES = 1024L * 1024L * 1024L

        fun defaultTransformRoundsFor(kdfAlgorithm: KeePassKdfAlgorithm): Long {
            return if (kdfAlgorithm == KeePassKdfAlgorithm.AES_KDF) {
                DEFAULT_AES_KDF_ROUNDS
            } else {
                DEFAULT_ARGON_ITERATIONS
            }
        }

        fun remoteCompatibilityDefaults(): KeePassDatabaseCreationOptions {
            return KeePassDatabaseCreationOptions(
                formatVersion = KeePassFormatVersion.KDBX4,
                cipherAlgorithm = KeePassCipherAlgorithm.AES,
                kdfAlgorithm = KeePassKdfAlgorithm.AES_KDF,
                transformRounds = DEFAULT_AES_KDF_ROUNDS,
                memoryBytes = DEFAULT_ARGON_MEMORY_BYTES,
                parallelism = 2
            )
        }
    }
}

/**
 * 本地 KeePass 数据库信息
 */
@Entity(
    tableName = "local_keepass_databases",
    indices = [
        Index(value = ["storage_location"]),
        Index(value = ["source_type"]),
        Index(value = ["source_id"]),
        Index(value = ["key_file_fingerprint"])
    ]
)
data class LocalKeePassDatabase(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** 数据库显示名称 */
    val name: String,
    
    /** 文件路径（内部存储时为相对路径，外部存储时为 URI） */
    val filePath: String,
    
    /** 密钥文件 URI（可选，使用 SAF 选择或生成的密钥文件） */
    val keyFileUri: String? = null,

    /** Monica 私有目录中的加密密钥文件副本（可选；原始 URI 始终保留） */
    @ColumnInfo(name = "key_file_internal_path")
    val keyFileInternalPath: String? = null,

    /** 密钥文件显示名称 */
    @ColumnInfo(name = "key_file_name")
    val keyFileName: String? = null,

    /** 密钥文件 SHA-256 指纹，用于恢复校验与副本去重 */
    @ColumnInfo(name = "key_file_fingerprint")
    val keyFileFingerprint: String? = null,
    
    /** 存储位置 */
    @ColumnInfo(name = "storage_location")
    val storageLocation: KeePassStorageLocation = KeePassStorageLocation.INTERNAL,

    /** 数据源类型 */
    @ColumnInfo(name = "source_type")
    val sourceType: KeePassDatabaseSourceType = storageLocation.toSourceType(),

    /** 远端/附加数据源引用 */
    @ColumnInfo(name = "source_id")
    val sourceId: Long? = null,

    /** 打开方式 */
    @ColumnInfo(name = "open_mode")
    val openMode: KeePassOpenMode = KeePassOpenMode.DIRECT,

    /** 本地工作副本路径 */
    @ColumnInfo(name = "working_copy_path")
    val workingCopyPath: String? = null,

    /** 本地缓存副本路径 */
    @ColumnInfo(name = "cache_copy_path")
    val cacheCopyPath: String? = null,

    /** 是否可离线使用 */
    @ColumnInfo(name = "is_offline_available")
    val isOfflineAvailable: Boolean = storageLocation == KeePassStorageLocation.INTERNAL,
    
    /** 加密后的主密码（用于自动解锁） */
    @ColumnInfo(name = "encrypted_password")
    val encryptedPassword: String? = null,
    
    /** 创建时间 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    /** 最后访问时间 */
    @ColumnInfo(name = "last_accessed_at")
    val lastAccessedAt: Long = System.currentTimeMillis(),
    
    /** 最后同步时间 */
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long? = null,

    /** 最近同步状态 */
    @ColumnInfo(name = "last_sync_status")
    val lastSyncStatus: KeePassSyncStatus = KeePassSyncStatus.LOCAL_ONLY,

    /** 最近同步错误 */
    @ColumnInfo(name = "last_sync_error")
    val lastSyncError: String? = null,

    /** 最近同步状态更新时间 */
    @ColumnInfo(name = "last_sync_state_updated_at")
    val lastSyncStateUpdatedAt: Long = System.currentTimeMillis(),
    
    /** 是否为默认数据库 */
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,
    
    /** 数据库描述 */
    val description: String? = null,
    
    /** 条目数量（缓存） */
    @ColumnInfo(name = "entry_count")
    val entryCount: Int = 0,
    
    /** 排序顺序 */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    /** KDBX 主版本（3 或 4） */
    @ColumnInfo(name = "kdbx_major_version")
    val kdbxMajorVersion: Int = KeePassFormatVersion.KDBX4.majorVersion,

    /** 外层加密算法 */
    @ColumnInfo(name = "cipher_algorithm")
    val cipherAlgorithm: String = KeePassCipherAlgorithm.AES.name,

    /** 密钥派生函数 */
    @ColumnInfo(name = "kdf_algorithm")
    val kdfAlgorithm: String = KeePassKdfAlgorithm.ARGON2D.name,

    /** 转换次数（Argon2 Iterations 或 AES-KDF Rounds） */
    @ColumnInfo(name = "kdf_transform_rounds")
    val kdfTransformRounds: Long = 8L,

    /** KDF 内存占用（字节） */
    @ColumnInfo(name = "kdf_memory_bytes")
    val kdfMemoryBytes: Long = KeePassDatabaseCreationOptions.DEFAULT_ARGON_MEMORY_BYTES,

    /** KDF 并行度 */
    @ColumnInfo(name = "kdf_parallelism")
    val kdfParallelism: Int = 2
)

fun LocalKeePassDatabase.toCreationOptions(): KeePassDatabaseCreationOptions {
    val parsedVersion = KeePassFormatVersion
        .entries
        .firstOrNull { it.majorVersion == kdbxMajorVersion }
        ?: KeePassFormatVersion.KDBX4
    val parsedCipher = runCatching { KeePassCipherAlgorithm.valueOf(cipherAlgorithm) }
        .getOrDefault(KeePassCipherAlgorithm.AES)
    val parsedKdf = runCatching { KeePassKdfAlgorithm.valueOf(kdfAlgorithm) }
        .getOrDefault(KeePassKdfAlgorithm.ARGON2D)

    return KeePassDatabaseCreationOptions(
        formatVersion = parsedVersion,
        cipherAlgorithm = parsedCipher,
        kdfAlgorithm = parsedKdf,
        transformRounds = kdfTransformRounds,
        memoryBytes = kdfMemoryBytes,
        parallelism = kdfParallelism
    ).normalized()
}

fun LocalKeePassDatabase.isRemoteSource(): Boolean = when (sourceType) {
    KeePassDatabaseSourceType.REMOTE_WEBDAV,
    KeePassDatabaseSourceType.REMOTE_ONEDRIVE,
    KeePassDatabaseSourceType.REMOTE_GOOGLE_DRIVE -> true
    else -> false
}

fun LocalKeePassDatabase.resolvedActiveFilePath(): String {
    return workingCopyPath?.takeIf { it.isNotBlank() } ?: filePath
}

fun LocalKeePassDatabase.resolvedActiveStorageLocation(): KeePassStorageLocation {
    return if (workingCopyPath.isNullOrBlank()) storageLocation else KeePassStorageLocation.INTERNAL
}

/**
 * 本地 KeePass 数据库 DAO
 */
@Dao
interface LocalKeePassDatabaseDao {
    
    @Query("SELECT * FROM local_keepass_databases ORDER BY sort_order ASC, created_at DESC")
    fun getAllDatabases(): Flow<List<LocalKeePassDatabase>>
    
    @Query("SELECT * FROM local_keepass_databases ORDER BY sort_order ASC, created_at DESC")
    fun getAllDatabasesSync(): List<LocalKeePassDatabase>
    
    @Query("SELECT * FROM local_keepass_databases WHERE id = :id")
    suspend fun getDatabaseById(id: Long): LocalKeePassDatabase?
    
    @Query("SELECT * FROM local_keepass_databases WHERE is_default = 1 LIMIT 1")
    suspend fun getDefaultDatabase(): LocalKeePassDatabase?
    
    @Query("SELECT * FROM local_keepass_databases WHERE storage_location = :location ORDER BY sort_order ASC")
    fun getDatabasesByLocation(location: KeePassStorageLocation): Flow<List<LocalKeePassDatabase>>

    @Query("SELECT * FROM local_keepass_databases WHERE source_type = :sourceType ORDER BY sort_order ASC, created_at DESC")
    fun getDatabasesBySourceType(sourceType: KeePassDatabaseSourceType): Flow<List<LocalKeePassDatabase>>

    @Query("SELECT * FROM local_keepass_databases WHERE source_type IN ('REMOTE_WEBDAV', 'REMOTE_ONEDRIVE', 'REMOTE_GOOGLE_DRIVE') ORDER BY sort_order ASC, created_at DESC")
    fun getRemoteDatabases(): Flow<List<LocalKeePassDatabase>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDatabase(database: LocalKeePassDatabase): Long
    
    @Update
    suspend fun updateDatabase(database: LocalKeePassDatabase)

    @Query("UPDATE local_keepass_databases SET sort_order = :order WHERE id = :id")
    suspend fun updateDisplayOrder(id: Long, order: Int)

    @Transaction
    suspend fun reorderDisplay(ids: List<Long>) {
        val current = getAllDatabasesSync().map { it.id }
        mergeDatabaseDisplayOrder(current, ids).forEachIndexed { index, id -> updateDisplayOrder(id, index) }
    }

    
    @Delete
    suspend fun deleteDatabase(database: LocalKeePassDatabase)
    
    @Query("DELETE FROM local_keepass_databases WHERE id = :id")
    suspend fun deleteDatabaseById(id: Long)
    
    @Query("UPDATE local_keepass_databases SET is_default = 0")
    suspend fun clearDefaultDatabase()
    
    @Query("UPDATE local_keepass_databases SET is_default = 1 WHERE id = :id")
    suspend fun setDefaultDatabase(id: Long)
    
    @Query("UPDATE local_keepass_databases SET last_accessed_at = :time WHERE id = :id")
    suspend fun updateLastAccessedTime(id: Long, time: Long = System.currentTimeMillis())
    
    @Query("UPDATE local_keepass_databases SET entry_count = :count WHERE id = :id AND entry_count != :count")
    suspend fun updateEntryCount(id: Long, count: Int)
    
    @Query("UPDATE local_keepass_databases SET storage_location = :location, source_type = :sourceType, filePath = :newPath WHERE id = :id")
    suspend fun updateStorageLocation(
        id: Long,
        location: KeePassStorageLocation,
        sourceType: KeePassDatabaseSourceType,
        newPath: String
    )

    @Query(
        """
        UPDATE local_keepass_databases
        SET source_type = :sourceType,
            source_id = :sourceId,
            open_mode = :openMode
        WHERE id = :id
        """
    )
    suspend fun updateSourceBinding(
        id: Long,
        sourceType: KeePassDatabaseSourceType,
        sourceId: Long?,
        openMode: KeePassOpenMode
    )

    @Query(
        """
        UPDATE local_keepass_databases
        SET working_copy_path = :workingCopyPath,
            cache_copy_path = :cacheCopyPath,
            is_offline_available = :isOfflineAvailable
        WHERE id = :id
        """
    )
    suspend fun updateLocalCopies(
        id: Long,
        workingCopyPath: String?,
        cacheCopyPath: String?,
        isOfflineAvailable: Boolean
    )

    @Query(
        """
        UPDATE local_keepass_databases
        SET last_sync_status = :status,
            last_sync_error = :error,
            last_synced_at = :syncedAt,
            last_sync_state_updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000
        WHERE id = :id
        """
    )
    suspend fun updateSyncStatus(
        id: Long,
        status: KeePassSyncStatus,
        error: String?,
        syncedAt: Long?
    )
}
