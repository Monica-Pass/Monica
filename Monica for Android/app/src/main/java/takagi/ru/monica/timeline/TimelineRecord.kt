package takagi.ru.monica.timeline

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Facts contain identities only. Names, revisions and values live inside sealedDetails. */
@Entity(tableName = "timeline_events", indices = [Index(value = ["occurredAt", "id"]), Index(value = ["parentId"])])
data class TimelineRecord(
    @PrimaryKey val id: String,
    val occurredAt: Long,
    val action: String,
    val origin: String,
    val scopeKey: String,
    val sealedDetails: String?,
    val expiresAt: Long,
    val recoveryCandidate: Boolean,
    val parentId: String? = null,
    val format: Int = 1
)

@Entity(tableName = "timeline_targets", primaryKeys = ["eventId", "kind", "itemId"],
    indices = [Index(value = ["kind", "itemId"]), Index(value = ["scopeKey"])],
    foreignKeys = [ForeignKey(entity = TimelineRecord::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)])
data class TimelineTarget(val eventId: String, val kind: String, val itemId: Long, val scopeKey: String)

@Dao
interface TimelineDao {
    @Insert suspend fun insert(record: TimelineRecord)
    @Insert suspend fun insertTargets(targets: List<TimelineTarget>)
    @Query("SELECT * FROM timeline_events WHERE id = :id") suspend fun get(id: String): TimelineRecord?
    @Query("SELECT * FROM timeline_targets WHERE eventId = :id ORDER BY kind, itemId") suspend fun targets(id: String): List<TimelineTarget>
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(occurredAt),0) FROM timeline_events") fun changes(): Flow<String>
    @Query("SELECT DISTINCT scopeKey FROM timeline_targets ORDER BY scopeKey") suspend fun scopes(): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM timeline_targets WHERE kind = :kind AND itemId = :id AND scopeKey = 'local')")
    suspend fun hasLocalTarget(kind: String, id: Long): Boolean
    @Query("UPDATE timeline_events SET sealedDetails = NULL, recoveryCandidate = 0 WHERE id IN (SELECT eventId FROM timeline_targets WHERE kind = :kind AND itemId = :id)")
    suspend fun purgeTarget(kind: String, id: Long)
    @Query("UPDATE timeline_events SET sealedDetails = NULL, recoveryCandidate = 0 WHERE expiresAt <= :now AND sealedDetails IS NOT NULL")
    suspend fun purgeExpired(now: Long)
    @Query("""SELECT * FROM timeline_events e WHERE occurredAt >= :since AND occurredAt <= :until
        AND (occurredAt < :cursorTime OR (occurredAt = :cursorTime AND id < :cursorId))
        AND (:tab != 'CHANGES' OR action != 'SYNC') AND (:tab != 'SYNC' OR action = 'SYNC')
        AND (:tab != 'RECOVERABLE' OR (recoveryCandidate = 1 AND expiresAt > :now) OR action = 'TRASH')
        AND (:scope = '' OR scopeKey = :scope OR EXISTS(SELECT 1 FROM timeline_targets t WHERE t.eventId = e.id AND t.scopeKey = :scope))
        ORDER BY occurredAt DESC, id DESC LIMIT :limit""")
    suspend fun page(tab: String, scope: String, since: Long, until: Long, cursorTime: Long, cursorId: String, now: Long, limit: Int): List<TimelineRecord>
}

@Serializable
data class TimelineCell(val type: Int, val value: String? = null)
typealias TimelineRow = Map<String, TimelineCell>

/** Raw row guards are encrypted too; hashes of low entropy values are never indexed. */
@Serializable
data class LocalTimelineSnapshot(
    val row: TimelineRow,
    val fields: List<TimelineRow> = emptyList(),
    val attachments: List<TimelineRow> = emptyList()
)

@Serializable
data class TimelineChange(
    val kind: String,
    val itemId: Long,
    val before: LocalTimelineSnapshot?,
    val after: LocalTimelineSnapshot?,
    // Proven plaintext equivalence only; raw encrypted rows remain exact conflict guards.
    val equivalentColumns: Set<String> = emptySet(),
    val equivalentFieldIds: Set<String> = emptySet()
)

@Serializable
data class TimelineDetails(
    val version: Int = 1,
    val title: String = "",
    val changes: List<TimelineChange> = emptyList(),
    val facts: List<TimelineFact> = emptyList(),
    val automaticEmptySync: Boolean = false
)

@Serializable
data class TimelineFact(val field: String, val before: String, val after: String)

internal fun TimelineRow.text(key: String): String = get(key)?.value.orEmpty()
internal fun TimelineRow.isLocal(): Boolean = listOf("keepassDatabaseId", "keepass_database_id", "mdbx_database_id", "bitwarden_vault_id").all { get(it)?.value == null }
internal fun TimelineRow.scope(): String = when {
    get("keepassDatabaseId")?.value != null -> "keepass:${text("keepassDatabaseId") }"
    get("keepass_database_id")?.value != null -> "keepass:${text("keepass_database_id") }"
    get("mdbx_database_id")?.value != null -> "mdbx:${text("mdbx_database_id") }"
    get("bitwarden_vault_id")?.value != null -> "bitwarden:${text("bitwarden_vault_id") }"
    else -> "local"
}

internal fun LocalTimelineSnapshot.accountLabel(): String {
    val copies = fields.filter { it.text("title") == takagi.ru.monica.data.model.ProjectCredentialGroup.FIELD }
    val first = copies.firstOrNull()?.text("value")?.let(takagi.ru.monica.data.model.ProjectCredentialGroup::parse) ?: return ""
    return first.label.takeIf { copies.all { takagi.ru.monica.data.model.ProjectCredentialGroup.parse(it.text("value"))?.raw == first.raw } }.orEmpty()
}
