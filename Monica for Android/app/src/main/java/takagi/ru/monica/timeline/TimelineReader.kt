package takagi.ru.monica.timeline

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.FieldChange
import java.time.Instant
import java.time.ZoneId

/** All filters precede paging. Text search scans bounded encrypted pages, never secret values. */
class TimelineReader(private val db: PasswordDatabase, private val label: (String) -> String = { it }) {
    data class Cursor(val time: Long = Long.MAX_VALUE, val id: String = "~")
    data class Filter(val tab: String = "CHANGES", val scope: String = "", val since: Long = 0,
        val until: Long = Long.MAX_VALUE, val query: String = "")
    data class Entry(val key: String, val record: TimelineRecord, val details: TimelineDetails?,
        val status: String, val targets: List<TimelineTarget>, val checkCount: Int = 1)
    data class Page(val entries: List<Entry>, val cursor: Cursor, val more: Boolean)

    suspend fun page(filter: Filter, cursor: Cursor): Page = withContext(Dispatchers.IO) {
        val generation = SessionManager.lockGeneration.value
        check(SessionManager.isUnlocked.value) { "TIMELINE_LOCKED" }
        val now = System.currentTimeMillis()
        val localScope = "CASE WHEN p.id IS NOT NULL THEN CASE WHEN p.keepassDatabaseId IS NOT NULL THEN 'keepass:' || p.keepassDatabaseId WHEN p.mdbx_database_id IS NOT NULL THEN 'mdbx:' || p.mdbx_database_id WHEN p.bitwarden_vault_id IS NOT NULL THEN 'bitwarden:' || p.bitwarden_vault_id ELSE 'local' END WHEN s.id IS NOT NULL THEN CASE WHEN s.keepass_database_id IS NOT NULL THEN 'keepass:' || s.keepass_database_id WHEN s.mdbx_database_id IS NOT NULL THEN 'mdbx:' || s.mdbx_database_id WHEN s.bitwarden_vault_id IS NOT NULL THEN 'bitwarden:' || s.bitwarden_vault_id ELSE 'local' END ELSE 'unknown' END"
        val sql = """SELECT * FROM (
            SELECT 'v:' || id AS eventKey, occurredAt AS time, id AS sourceId, action,
                scopeKey AS scope, recoveryCandidate AS recoverable, expiresAt, 'v' AS version
            FROM timeline_events
            UNION ALL
            SELECT 'l:' || printf('%020d',l.id), l.timestamp, CAST(l.id AS TEXT), l.operationType,
                $localScope, 0, l.timestamp + ${TimelineJournal.RETENTION_MILLIS}, 'l'
            FROM operation_logs l
            LEFT JOIN password_entries p ON l.itemType = 'PASSWORD' AND p.id = l.itemId
            LEFT JOIN secure_items s ON l.itemType IN ('TOTP','NOTE','BANK_CARD','DOCUMENT','BILLING_ADDRESS','PAYMENT_ACCOUNT') AND s.id = l.itemId
        ) WHERE time >= ? AND time <= ? AND (time < ? OR (time = ? AND eventKey < ?))
        AND (? != 'CHANGES' OR action != 'SYNC') AND (? != 'SYNC' OR action = 'SYNC')
        AND (? != 'RECOVERABLE' OR (recoverable = 1 AND expiresAt > ?) OR (action = 'TRASH' AND version = 'v' AND EXISTS(
            SELECT 1 FROM timeline_targets deletedTarget
            LEFT JOIN password_entries deletedPassword ON deletedTarget.kind = 'PASSWORD' AND deletedPassword.id = deletedTarget.itemId
            LEFT JOIN secure_items deletedSecure ON deletedTarget.kind = 'SECURE' AND deletedSecure.id = deletedTarget.itemId
            WHERE deletedTarget.eventId = sourceId AND (deletedPassword.isDeleted = 1 OR deletedSecure.isDeleted = 1))))
        AND (? = '' OR scope = ? OR (version = 'v' AND EXISTS(SELECT 1 FROM timeline_targets t WHERE t.eventId = sourceId AND t.scopeKey = ?)))
        ORDER BY time DESC, eventKey DESC LIMIT 64"""
        val candidates = db.openHelper.writableDatabase.query(SimpleSQLiteQuery(sql,
            arrayOf<Any?>(filter.since, filter.until, cursor.time, cursor.time, cursor.id, filter.tab, filter.tab,
                filter.tab, now, filter.scope, filter.scope, filter.scope))).use { rows ->
            buildList { while (rows.moveToNext()) add(Candidate(rows.getString(0), rows.getLong(1), rows.getString(2), rows.getString(4))) }
        }
        val result = mutableListOf<Entry>()
        for ((key, time, sourceId, sourceScope) in candidates) {
            currentCoroutineContext().ensureActive()
            val entry = if (key.startsWith("v:")) {
                val record = db.timelineDao().get(sourceId) ?: continue
                var status = when {
                    record.expiresAt <= now -> "EXPIRED"
                    record.sealedDetails == null -> "MISSING"
                    record.format != 1 -> "UNSUPPORTED"
                    else -> "READY"
                }
                val details = if (status == "READY") try { db.timelineJournal.read(record) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { status = "UNREADABLE"; null } else null
                Entry(key, record, details, status, db.timelineDao().targets(record.id))
            } else {
                val log = db.operationLogDao().getLogById(sourceId.toLong()) ?: continue
                var status = "LEGACY"
                val facts = try {
                    if (log.changesJson.isBlank()) emptyList() else
                        Json { ignoreUnknownKeys = true }.decodeFromString<List<FieldChange>>(log.changesJson)
                            .filter { !it.fieldName.startsWith("__") }
                            .map { TimelineFact(it.fieldName, it.oldValue, it.newValue) }
                } catch (e: Exception) { status = "UNREADABLE"; emptyList() }
                val snapshot = db.timelineVersionSnapshotDao().getByOperationLogId(log.id)
                if (snapshot != null && snapshot.createdAt + TimelineJournal.RETENTION_MILLIS <= now) status = "EXPIRED"
                if (facts.any { it.before == "<redacted>" || it.after == "<redacted>" }) status = "REDACTED"
                // The scrubbed legacy title is an identity label, never a fabricated historical name.
                val details = TimelineDetails(title = log.itemTitle, facts = facts)
                Entry(key, TimelineRecord(key, time, log.operationType, "LEGACY", sourceScope, null,
                    time + TimelineJournal.RETENTION_MILLIS, false), details, status,
                    listOf(TimelineTarget(key, log.itemType, log.itemId, sourceScope)))
            }
            if (matches(entry, filter.query, label)) result.add(entry.copy(details = entry.details?.let { data ->
                data.copy(changes = emptyList(), facts = data.facts.map { it.copy(before = "", after = it.after.takeIf { value -> it.field in syncCountFields && value.toLongOrNull()?.let { count -> count >= 0 } == true }.orEmpty()) } +
                    data.changes.flatMap { change ->
                        TimelineRecoveryPolicy.columns(change).map { TimelineFact(it, "", "") } +
                            if (TimelineRecoveryPolicy.changedFieldIds(change).isNotEmpty()) listOf(TimelineFact("custom_fields", "", "")) else emptyList()
                    })
            }))
        }
        check(SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation) { "TIMELINE_LOCKED" }
        val next = candidates.lastOrNull()?.let { Cursor(it.time, it.key) } ?: cursor
        Page(result, next, candidates.size == 64)
    }

    private data class Candidate(val key: String, val time: Long, val sourceId: String, val scope: String)

    companion object {
        val syncCountFields = setOf("sync.changed","sync.offline","sync.added","sync.updated","sync.uploaded","sync.deleted","sync.conflicts","sync.failed","sync.pending", "本次变更", "离线可查看总数", "远端新增", "远端更新", "本地上传", "删除处理", "冲突数", "上传失败", "本地待上传阻塞")

        fun matches(entry: Entry, query: String, label: (String) -> String = { it }): Boolean {
            if (query.isBlank()) return true
            val details = entry.details
            val labels = buildList {
                add(entry.record.action); add(entry.record.scopeKey)
                details?.let { data ->
                    add(data.title)
                    addAll(data.facts.map { it.field })
                    data.changes.forEach { change ->
                        add((change.after ?: change.before)?.row?.text("title").orEmpty())
                        add((change.after ?: change.before)?.accountLabel().orEmpty())
                        addAll(TimelineRecoveryPolicy.columns(change))
                        addAll((change.after ?: change.before)?.fields.orEmpty().map { it.text("title") })
                    }
                }
            }
            return labels.any { it.contains(query.trim(), ignoreCase = true) || label(it).contains(query.trim(), ignoreCase = true) }
        }

        fun day(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()) = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

        /** Only explicit automatic, successful, empty checks can collapse, across page boundaries. */
        fun collapse(entries: List<Entry>): List<Entry> {
            val output = mutableListOf<Entry>()
            val positions = mutableMapOf<Pair<String, java.time.LocalDate>, Int>()
            entries.forEach { entry ->
                if (entry.record.action == "SYNC" && entry.details?.automaticEmptySync == true && entry.record.scopeKey != "unknown") {
                    val key = entry.record.scopeKey to day(entry.record.occurredAt)
                    val index = positions[key]
                    if (index != null) output[index] = output[index].copy(checkCount = output[index].checkCount + entry.checkCount)
                    else { positions[key] = output.size; output.add(entry) }
                } else output.add(entry)
            }
            return output
        }
    }
}
