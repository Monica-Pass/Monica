package takagi.ru.monica.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable coordinator state for an MDBX2 remote vault.
 *
 * Engine state remains in Rust. This table only stores transport cursors and
 * pending file identities, so a process restart can retry the exact same
 * immutable segment instead of exporting a different one.
 */
@Entity(tableName = "mdbx_sync_states")
data class MdbxSyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "database_id")
    val databaseId: Long,
    @ColumnInfo(name = "state_json")
    val stateJson: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface MdbxSyncStateDao {
    @Query("SELECT * FROM mdbx_sync_states WHERE database_id = :databaseId")
    suspend fun get(databaseId: Long): MdbxSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: MdbxSyncStateEntity)

    @Query("DELETE FROM mdbx_sync_states WHERE database_id = :databaseId")
    suspend fun delete(databaseId: Long)
}

@Serializable
data class MdbxSyncCheckpointState(
    val commitInventory: String,
    val deltaInventory: String
)

@Serializable
data class MdbxSyncResumeState(
    val transferId: String,
    val nextSegmentIndex: UInt,
    val previousSegmentSha256Hex: String
)

@Serializable
data class MdbxPendingSegmentState(
    val path: String,
    val streamId: String,
    val streamSequence: Long,
    val vaultId: String,
    val sourceDeviceId: String,
    val transferId: String,
    val segmentIndex: UInt,
    val isLast: Boolean,
    val base: MdbxSyncCheckpointState,
    val result: MdbxSyncCheckpointState,
    val nextResume: MdbxSyncResumeState? = null,
    val payloadSha256Hex: String,
    val fileSizeBytes: Long
)

@Serializable
data class MdbxRemoteStreamState(
    val streamId: String,
    val generationId: String,
    val nextSequence: Long,
    val checkpoint: MdbxSyncCheckpointState,
    val resume: MdbxSyncResumeState? = null,
    val lastAppliedDigestHex: String? = null,
    val blockedReason: String? = null
)

@Serializable
data class MdbxBlobTransferState(
    val blobId: String,
    val totalSize: Long,
    val ownerId: String,
    val nextOffset: Long,
    val direction: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class MdbxSyncStateSnapshot(
    val vaultId: String? = null,
    val generationId: String? = null,
    val exportCheckpoint: MdbxSyncCheckpointState? = null,
    val exportResume: MdbxSyncResumeState? = null,
    val bootstrapCheckpoint: MdbxSyncCheckpointState? = null,
    val pendingSegment: MdbxPendingSegmentState? = null,
    val remoteStreams: List<MdbxRemoteStreamState> = emptyList(),
    val blobTransfers: List<MdbxBlobTransferState> = emptyList()
)

/** Room-backed, serialized coordinator state with per-vault update locking. */
class MdbxSyncStateStore(
    private val dao: MdbxSyncStateDao,
    private val json: Json = DEFAULT_JSON
) {
    private val locks = mutableMapOf<Long, Mutex>()
    private val locksGuard = Any()

    suspend fun read(databaseId: Long): MdbxSyncStateSnapshot = withLock(databaseId) {
        dao.get(databaseId)?.let { entity ->
            runCatching {
                json.decodeFromString<MdbxSyncStateSnapshot>(entity.stateJson)
            }.getOrElse {
                throw IllegalStateException("MDBX2 sync state is corrupted for $databaseId", it)
            }
        } ?: MdbxSyncStateSnapshot()
    }

    suspend fun write(databaseId: Long, state: MdbxSyncStateSnapshot) = withLock(databaseId) {
        val encoded = json.encodeToString(state)
        dao.upsert(
            MdbxSyncStateEntity(
                databaseId = databaseId,
                stateJson = encoded,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun update(
        databaseId: Long,
        transform: (MdbxSyncStateSnapshot) -> MdbxSyncStateSnapshot
    ): MdbxSyncStateSnapshot = withLock(databaseId) {
        val current = dao.get(databaseId)?.let { entity ->
            runCatching {
                json.decodeFromString<MdbxSyncStateSnapshot>(entity.stateJson)
            }.getOrElse {
                throw IllegalStateException("MDBX2 sync state is corrupted for $databaseId", it)
            }
        } ?: MdbxSyncStateSnapshot()
        val next = transform(current)
        dao.upsert(
            MdbxSyncStateEntity(
                databaseId = databaseId,
                stateJson = json.encodeToString(next),
                updatedAt = System.currentTimeMillis()
            )
        )
        next
    }

    suspend fun delete(databaseId: Long) = withLock(databaseId) {
        dao.delete(databaseId)
    }

    private suspend fun <T> withLock(databaseId: Long, block: suspend () -> T): T {
        val mutex = synchronized(locksGuard) {
            locks.getOrPut(databaseId) { Mutex() }
        }
        return mutex.withLock { block() }
    }

    companion object {
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
