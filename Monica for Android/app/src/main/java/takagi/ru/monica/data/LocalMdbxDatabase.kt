package takagi.ru.monica.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * MDBX vault storage location.
 */
enum class MdbxStorageLocation {
    INTERNAL,
    EXTERNAL,
    REMOTE_WEBDAV
}

enum class MdbxSourceType {
    LOCAL_INTERNAL,
    LOCAL_EXTERNAL,
    REMOTE_WEBDAV,
    REMOTE_ONEDRIVE
}

enum class MdbxEngineType {
    KOTLIN_MDBX1,
    RUST_MDBX2;

    companion object {
        fun fromName(name: String?): MdbxEngineType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: KOTLIN_MDBX1
    }
}

enum class MdbxCapability {
    LOCAL_CRUD,
    EMBEDDED_ATTACHMENTS,
    EXTERNAL_STORAGE,
    REMOTE_SYNC,
    NESTED_FOLDERS,
    PROJECT_TAGS,
    DELTA_HISTORY,
    SNAPSHOTS,
    CONFLICTS,
    SYNC_BUNDLES,
    BENCHMARK
}

val MdbxEngineType.capabilities: Set<MdbxCapability>
    get() = when (this) {
        MdbxEngineType.KOTLIN_MDBX1 -> emptySet()
        MdbxEngineType.RUST_MDBX2 -> setOf(
            MdbxCapability.LOCAL_CRUD,
            MdbxCapability.EMBEDDED_ATTACHMENTS,
            MdbxCapability.EXTERNAL_STORAGE,
            MdbxCapability.REMOTE_SYNC,
            MdbxCapability.NESTED_FOLDERS,
            MdbxCapability.PROJECT_TAGS,
            MdbxCapability.DELTA_HISTORY,
            MdbxCapability.SNAPSHOTS,
            MdbxCapability.CONFLICTS,
            MdbxCapability.SYNC_BUNDLES,
            MdbxCapability.BENCHMARK
        )
    }

fun LocalMdbxDatabase.supports(capability: MdbxCapability): Boolean =
    capability in engineTypeEnum.capabilities

/** Legacy records remain visible to the manager solely for upgrading or removal. */
val LocalMdbxDatabase.isUsable: Boolean
    get() = engineTypeEnum == MdbxEngineType.RUST_MDBX2

// Hide retired projections from normal lists and credential suggestions without
// deleting their cached contents. Migration reads the original vault separately.
internal const val MDBX_AVAILABLE_ENTRY_FILTER =
    "(mdbx_database_id IS NULL OR mdbx_database_id IN " +
        "(SELECT id FROM local_mdbx_databases WHERE engine_type = 'RUST_MDBX2' COLLATE NOCASE))"

/**
 * Tiga three-mode security model for MDBX vaults.
 *
 * Controls Argon2id KDF parameters (ops_limit / mem_limit / parallelism):
 *   POWER: 10 / 262144 KiB (256 MiB) / 4 — maximum brute-force resistance
 *   MULTI:  3 /  65536 KiB ( 64 MiB) / 2 — balanced default
 *   SKY:    1 /   8192 KiB (  8 MiB) / 1 — lightweight, fast
 */
enum class MdbxTigaMode(val label: String, val memoryMb: Int, val description: String) {
    POWER("Power", 256, "Maximum security, 256MB Argon2id"),
    MULTI("Multi", 64, "Balanced security, 64MB Argon2id (Recommended)"),
    SKY("Sky", 8, "Fast mode, 8MB Argon2id");

    companion object {
        fun fromName(name: String): MdbxTigaMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MULTI
    }
}

enum class MdbxSyncStatus {
    LOCAL_ONLY,
    IN_SYNC,
    SYNCING,
    PENDING_UPLOAD,
    REMOTE_CHANGED,
    CONFLICT,
    FAILED
}

enum class MdbxUnlockMethod(val storedValue: String) {
    MASTER_PASSWORD("password"),
    KEY_FILE("key_file"),
    MASTER_PASSWORD_AND_KEY_FILE("password+key_file"),
    DEVICE_KEY("device_key");

    companion object {
        fun fromStoredValue(value: String?): MdbxUnlockMethod =
            entries.firstOrNull { method ->
                method.storedValue.equals(value, ignoreCase = true) ||
                    method.name.equals(value, ignoreCase = true)
            } ?: MASTER_PASSWORD
    }
}

fun MdbxStorageLocation.toSourceType(): MdbxSourceType = when (this) {
    MdbxStorageLocation.INTERNAL -> MdbxSourceType.LOCAL_INTERNAL
    MdbxStorageLocation.EXTERNAL -> MdbxSourceType.LOCAL_EXTERNAL
    MdbxStorageLocation.REMOTE_WEBDAV -> MdbxSourceType.REMOTE_WEBDAV
}

@Entity(
    tableName = "local_mdbx_databases",
    indices = [
        Index(value = ["storage_location"]),
        Index(value = ["source_type"]),
        Index(value = ["source_id"])
    ]
)
data class LocalMdbxDatabase(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "storage_location")
    val storageLocation: String = MdbxStorageLocation.REMOTE_WEBDAV.name,

    @ColumnInfo(name = "source_type")
    val sourceType: String = MdbxSourceType.REMOTE_WEBDAV.name,

    @ColumnInfo(name = "source_id")
    val sourceId: Long? = null,

    @ColumnInfo(name = "engine_type")
    val engineType: String = MdbxEngineType.KOTLIN_MDBX1.name,

    @ColumnInfo(name = "tiga_mode")
    val tigaMode: String = MdbxTigaMode.MULTI.name,

    @ColumnInfo(name = "encrypted_password")
    val encryptedPassword: String? = null,

    @ColumnInfo(name = "unlock_method")
    val unlockMethod: String = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,

    @ColumnInfo(name = "kdf_profile")
    val kdfProfile: String = "argon2id",

    @ColumnInfo(name = "key_file_name")
    val keyFileName: String? = null,

    @ColumnInfo(name = "key_file_uri")
    val keyFileUri: String? = null,

    @ColumnInfo(name = "key_file_fingerprint")
    val keyFileFingerprint: String? = null,

    val description: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_accessed_at")
    val lastAccessedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long? = null,

    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "project_count")
    val projectCount: Int = 0,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "working_copy_path")
    val workingCopyPath: String? = null,

    @ColumnInfo(name = "cache_copy_path")
    val cacheCopyPath: String? = null,

    @ColumnInfo(name = "external_tree_uri")
    val externalTreeUri: String? = null,

    @ColumnInfo(name = "is_offline_available")
    val isOfflineAvailable: Boolean = false,

    @ColumnInfo(name = "last_sync_status")
    val lastSyncStatus: String = MdbxSyncStatus.LOCAL_ONLY.name,

    @ColumnInfo(name = "last_sync_error")
    val lastSyncError: String? = null
) {
    val tigaModeEnum: MdbxTigaMode get() = MdbxTigaMode.fromName(tigaMode)
    val storageLocationEnum: MdbxStorageLocation get() =
        runCatching { MdbxStorageLocation.valueOf(storageLocation) }.getOrDefault(MdbxStorageLocation.REMOTE_WEBDAV)
    val sourceTypeEnum: MdbxSourceType get() =
        runCatching { MdbxSourceType.valueOf(sourceType) }.getOrDefault(MdbxSourceType.REMOTE_WEBDAV)
    val engineTypeEnum: MdbxEngineType get() = MdbxEngineType.fromName(engineType)
    val unlockMethodEnum: MdbxUnlockMethod get() = MdbxUnlockMethod.fromStoredValue(unlockMethod)
}

fun LocalMdbxDatabase.isRemoteSource(): Boolean = when (sourceTypeEnum) {
    MdbxSourceType.REMOTE_WEBDAV,
    MdbxSourceType.REMOTE_ONEDRIVE -> true
    MdbxSourceType.LOCAL_INTERNAL,
    MdbxSourceType.LOCAL_EXTERNAL -> false
}

fun LocalMdbxDatabase.resolvedActiveFilePath(): String =
    workingCopyPath?.takeIf { it.isNotBlank() } ?: filePath

@Dao
interface LocalMdbxDatabaseDao {

    @Query("SELECT * FROM local_mdbx_databases ORDER BY sort_order ASC, created_at DESC")
    fun getAllDatabases(): Flow<List<LocalMdbxDatabase>>

    @Query("SELECT * FROM local_mdbx_databases WHERE engine_type = 'RUST_MDBX2' COLLATE NOCASE ORDER BY sort_order ASC, created_at DESC")
    fun getAvailableDatabases(): Flow<List<LocalMdbxDatabase>>

    @Query("SELECT * FROM local_mdbx_databases WHERE engine_type = 'RUST_MDBX2' COLLATE NOCASE ORDER BY sort_order ASC, created_at DESC")
    suspend fun getAvailableDatabasesSnapshot(): List<LocalMdbxDatabase>

    @Query("SELECT * FROM local_mdbx_databases ORDER BY sort_order ASC, created_at DESC")
    suspend fun getAllDatabasesSnapshot(): List<LocalMdbxDatabase>

    @Query("SELECT * FROM local_mdbx_databases WHERE id = :id")
    suspend fun getDatabaseById(id: Long): LocalMdbxDatabase?

    @Query("SELECT * FROM local_mdbx_databases WHERE is_default = 1 AND engine_type = 'RUST_MDBX2' COLLATE NOCASE LIMIT 1")
    suspend fun getDefaultDatabase(): LocalMdbxDatabase?

    @Query("SELECT * FROM local_mdbx_databases WHERE storage_location = :location ORDER BY sort_order ASC")
    fun getDatabasesByLocation(location: String): Flow<List<LocalMdbxDatabase>>

    @Query("SELECT * FROM local_mdbx_databases WHERE source_type = :sourceType ORDER BY sort_order ASC, created_at DESC")
    fun getDatabasesBySourceType(sourceType: String): Flow<List<LocalMdbxDatabase>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDatabase(database: LocalMdbxDatabase): Long

    @Update
    suspend fun updateDatabase(database: LocalMdbxDatabase)

    @Delete
    suspend fun deleteDatabase(database: LocalMdbxDatabase)

    @Query("DELETE FROM local_mdbx_databases WHERE id = :id")
    suspend fun deleteDatabaseById(id: Long)

    @Query("UPDATE local_mdbx_databases SET is_default = 0")
    suspend fun clearDefaultDatabase()

    @Query("UPDATE local_mdbx_databases SET is_default = 1 WHERE id = :id")
    suspend fun setDefaultDatabase(id: Long)

    @Query("UPDATE local_mdbx_databases SET last_accessed_at = :time WHERE id = :id")
    suspend fun updateLastAccessedTime(id: Long, time: Long = System.currentTimeMillis())

    @Query("UPDATE local_mdbx_databases SET project_count = :count WHERE id = :id")
    suspend fun updateProjectCount(id: Long, count: Int)

    @Query("UPDATE local_mdbx_databases SET source_id = :sourceId WHERE id = :databaseId")
    suspend fun updateSourceBinding(databaseId: Long, sourceId: Long?)

    @Query("UPDATE local_mdbx_databases SET working_copy_path = :workingPath, cache_copy_path = :cachePath WHERE id = :databaseId")
    suspend fun updateLocalCopies(databaseId: Long, workingPath: String?, cachePath: String?)

    @Query("UPDATE local_mdbx_databases SET last_sync_status = :status, last_sync_error = :error WHERE id = :databaseId")
    suspend fun updateSyncStatus(databaseId: Long, status: String, error: String?)

    @Query(
        """
        UPDATE local_mdbx_databases
        SET last_synced_at = :time,
            last_sync_status = :status,
            last_sync_error = NULL
        WHERE id = :databaseId
        """
    )
    suspend fun updateSyncSuccess(databaseId: Long, status: String, time: Long)

    @Query(
        """
        UPDATE local_mdbx_databases
        SET last_synced_at = :time,
            last_sync_status = :status,
            last_sync_error = NULL,
            is_offline_available = 1
        WHERE id = :databaseId
        """
    )
    suspend fun updateRemoteSyncSuccess(databaseId: Long, status: String, time: Long)
}
