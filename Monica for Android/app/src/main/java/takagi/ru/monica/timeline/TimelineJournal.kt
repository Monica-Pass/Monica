package takagi.ru.monica.timeline

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One journal belongs to one Room database. No global DAO registry or background log writer. */
class TimelineJournal(private val db: PasswordDatabase) {
    @Volatile private var appContext: Context? = null
    private val json = Json { ignoreUnknownKeys = false }
    private val security by lazy { SecurityManager(requireNotNull(appContext)) }
    val enabled: Boolean get() = appContext != null
    fun configure(context: Context) { appContext = context.applicationContext }

    private data class Identity(val kind: String, val id: Long)
    private class Capture(val owner: TimelineJournal, val generation: Long) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Capture>
        val before = linkedMapOf<Identity, LocalTimelineSnapshot?>()
    }

    private fun verify(generation: Long) {
        check(SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation) { "TIMELINE_LOCKED" }
    }

    suspend fun <T> operation(block: suspend () -> T): T {
        val existing = currentCoroutineContext()[Capture]
        if (existing?.owner === this) return block()
        if (!enabled) return db.withTransaction { block() }
        if (!SessionManager.isUnlocked.value) return db.withTransaction { block() }
        return withContext(Dispatchers.IO) {
            val capture = Capture(this@TimelineJournal, SessionManager.lockGeneration.value)
            db.withTransaction {
                withContext(capture) {
                    verify(capture.generation)
                    val result = block()
                    currentCoroutineContext().ensureActive()
                    verify(capture.generation)
                    flush(capture)
                    currentCoroutineContext().ensureActive()
                    verify(capture.generation)
                    result
                }
            }
        }
    }

    suspend fun <T> mutate(kind: String, ids: List<Long>, inserted: (T) -> List<Long> = { emptyList() }, block: suspend () -> T): T = operation {
        if (!enabled) return@operation block()
        val capture = currentCoroutineContext()[Capture]?.takeIf { it.owner === this }
        val before = ids.filter { it > 0 }.distinct().associateWith { snapshot(kind, it) }
        before.forEach { (id, value) -> if (capture != null && !capture.before.containsKey(Identity(kind, id))) capture.before[Identity(kind, id)] = value }
        val result = block()
        inserted(result).filter { it > 0 && it !in before }.forEach { id ->
            val key = Identity(kind, id)
            if (capture != null && !capture.before.containsKey(key)) capture.before[key] = null
        }
        // Purging also runs while locked (expiry/maintenance). Never leave recoverable deleted data.
        before.forEach { (id, value) -> if (value != null && snapshot(kind, id) == null) purge(kind, id) }
        result
    }

    suspend fun <T> mutateWhere(kind: String, where: String, args: Array<out Any?> = emptyArray(), block: suspend () -> T): T = operation {
        mutate(kind, ids(kind, where, args), block = block)
    }

    suspend fun <T> mutateQuery(kind: String, where: String, parameters: Map<String, Any?>, block: suspend () -> T): T {
        val args = mutableListOf<Any?>()
        fun bind(value: Any?): Any? = when (value) {
            is java.util.Date -> value.time
            is Enum<*> -> value.name
            is Boolean -> if (value) 1 else 0
            else -> value
        }
        val expanded = Regex(":([A-Za-z][A-Za-z0-9_]*)").replace(where) { match ->
            val value = parameters.getValue(match.groupValues[1])
            if (value is Collection<*>) {
                args.addAll(value.map(::bind))
                if (value.isEmpty()) "NULL" else value.joinToString { "?" }
            } else { args.add(bind(value)); "?" }
        }
        return mutateWhere(kind, expanded, args.toTypedArray(), block)
    }

    internal fun ids(kind: String, where: String, args: Array<out Any?> = emptyArray()): List<Long> =
        rows("SELECT id FROM ${table(kind)} WHERE $where ORDER BY id", args).map { it.text("id").toLong() }

    internal fun snapshot(kind: String, id: Long): LocalTimelineSnapshot? {
        val row = rows("SELECT * FROM ${table(kind)} WHERE id = ?", arrayOf<Any?>(id)).singleOrNull() ?: return null
        // Provider projections are not recovery material. Keep their native executor in charge.
        if (!row.isLocal()) return LocalTimelineSnapshot(row.filterKeys { it in identityColumns })
        val fields = if (kind == "PASSWORD") rows("SELECT * FROM custom_fields WHERE entry_id = ? ORDER BY id", arrayOf<Any?>(id)) else emptyList()
        val attachmentOwner = if (kind == "PASSWORD") "parent_password_id" else "parent_secure_item_id"
        return LocalTimelineSnapshot(row, fields, rows("SELECT * FROM attachments WHERE $attachmentOwner = ? ORDER BY id", arrayOf<Any?>(id)))
    }

    internal fun rows(sql: String, args: Array<out Any?> = emptyArray()): List<TimelineRow> =
        db.openHelper.writableDatabase.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.indices.associate { index ->
                    val type = cursor.getType(index)
                    check(type != Cursor.FIELD_TYPE_BLOB) { "Unsupported timeline column" }
                    cursor.getColumnName(index) to TimelineCell(type, if (type == Cursor.FIELD_TYPE_NULL) null else cursor.getString(index))
                })
            }
        }

    private suspend fun purge(kind: String, id: Long) {
        db.timelineDao().purgeTarget(kind, id)
        db.openHelper.writableDatabase.execSQL("DELETE FROM timeline_version_snapshots WHERE (item_type = ? OR (? = 'SECURE' AND item_type != 'PASSWORD')) AND item_id = ?", arrayOf<Any?>(kind, kind, id))
    }

    private fun equivalentValues(change: TimelineChange): TimelineChange {
        val before = change.before ?: return change
        val after = change.after ?: return change
        if (!before.row.isLocal() || !after.row.isLocal()) return change
        fun equalValue(old: TimelineCell?, new: TimelineCell?): Boolean {
            if (old == null || new == null || old.type != Cursor.FIELD_TYPE_STRING || new.type != Cursor.FIELD_TYPE_STRING) return false
            val a = old.value ?: return false
            val b = new.value ?: return false
            if (!security.looksLikeMonicaCiphertext(a) && !security.looksLikeMonicaCiphertext(b)) return false
            // Never classify an unreadable value as unchanged. Plaintext is transient, not serialized.
            return runCatching { security.decryptDataIfMonicaCiphertext(a) == security.decryptDataIfMonicaCiphertext(b) }.getOrDefault(false)
        }
        val columns = TimelineRecoveryPolicy.columns(change).filterTo(linkedSetOf()) {
            it in TimelineRecoveryPolicy.comparableColumns && equalValue(before.row[it], after.row[it])
        }
        val oldFields = before.fields.associateBy { it.text("id") }
        val fields = after.fields.filter { field ->
            val old = oldFields[field.text("id")]
            !field.text("title").startsWith("monica.") && old != null && old - "value" == field - "value" &&
                equalValue(old["value"], field["value"])
        }.mapTo(linkedSetOf()) { it.text("id") }
        return change.copy(equivalentColumns = columns, equivalentFieldIds = fields)
    }

    private suspend fun flush(capture: Capture) {
        val changes = capture.before.mapNotNull { (key, before) ->
            val after = snapshot(key.kind, key.id)
            if (before == after || (before?.row?.isLocal() != true && after?.row?.isLocal() != true)) null
            else {
                val change = equivalentValues(TimelineChange(key.kind, key.id, before, after))
                if (before != null && after != null && TimelineRecoveryPolicy.columns(change).isEmpty() &&
                    TimelineRecoveryPolicy.changedFieldIds(change).isEmpty() && before.attachments == after.attachments) null
                else change
            }
        }
        if (changes.isEmpty()) return
        val actions = changes.map(TimelineRecoveryPolicy::action).distinct()
        val action = actions.singleOrNull() ?: "BATCH"
        val now = System.currentTimeMillis()
        // Permanent deletion retains only a content-free fact, including in a mixed batch.
        val hasDeletion = changes.any { it.after == null }
        val details = TimelineDetails(
            title = changes.first().let { (it.after ?: it.before)?.row?.text("title").orEmpty() },
            changes = changes
        )
        val encoded = if (hasDeletion) null else json.encodeToString(details)
        val sealed = encoded?.takeIf { it.length <= MAX_MATERIAL_CHARS }?.let(security::encryptTimelineSnapshot)
        val event = TimelineRecord(UUID.randomUUID().toString(), now, action, "LOCAL", "local", sealed,
            now + RETENTION_MILLIS, sealed != null && TimelineRecoveryPolicy.canRestore(details))
        db.timelineDao().insert(event)
        db.timelineDao().insertTargets(changes.map { TimelineTarget(event.id, it.kind, it.itemId,
            (it.after ?: it.before)?.row?.scope() ?: "local") })
        db.timelineDao().purgeExpired(now)
    }

    suspend fun observe(kind: String, itemId: Long, title: String, action: String,
        facts: List<TimelineFact>, occurredAt: Long, explicitScope: String? = null,
        automaticEmptySync: Boolean = false,
        observedGeneration: Long? = SessionManager.lockGeneration.value.takeIf { SessionManager.isUnlocked.value }) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val normalizedKind = if (kind in setOf("TOTP", "NOTE", "BANK_CARD", "DOCUMENT", "BILLING_ADDRESS", "PAYMENT_ACCOUNT")) "SECURE" else kind
            val knownRow = if (normalizedKind in setOf("PASSWORD", "SECURE"))
                rows("SELECT * FROM ${table(normalizedKind)} WHERE id = ?", arrayOf<Any?>(itemId)).singleOrNull() else null
            // The transaction journal owns local rows. An asynchronous UI notice is not a second edit.
            if (normalizedKind in setOf("PASSWORD", "SECURE") &&
                db.timelineDao().hasLocalTarget(normalizedKind, itemId) &&
                (knownRow == null || knownRow.isLocal())) return@withTransaction
            val scope = explicitScope ?: knownRow?.scope() ?: "unknown"
            val generation = observedGeneration
            // A deleted provider projection may already be absent. Unknown provenance must
            // not turn its delayed notice into a local copy of provider-owned secrets.
            val redactContent = scope != "local"
            val safeFacts = if (!redactContent) facts else facts.map { fact ->
                val counter = action == "SYNC" && fact.field in TimelineReader.syncCountFields &&
                    fact.after.toLongOrNull()?.let { it >= 0 } == true
                fact.copy(before = "", after = if (counter) fact.after else "<redacted>")
            }
            val details = TimelineDetails(title = if (redactContent) "" else title,
                facts = safeFacts, automaticEmptySync = automaticEmptySync)
            val sealed = if (enabled && generation != null && SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation)
                runCatching { security.encryptTimelineSnapshot(json.encodeToString(details)) }.getOrNull() else null
            // A late notice may retain its non-sensitive fact, never late plaintext/material.
            val authorizedSealed = sealed.takeIf { SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation }
            val event = TimelineRecord(UUID.randomUUID().toString(), occurredAt, action, "OBSERVED", scope,
                authorizedSealed, occurredAt + RETENTION_MILLIS, false)
            db.timelineDao().insert(event)
            db.timelineDao().insertTargets(listOf(TimelineTarget(event.id, normalizedKind, itemId, scope)))
        }
    }

    suspend fun read(event: TimelineRecord): TimelineDetails = withContext(Dispatchers.IO) {
        val generation = SessionManager.lockGeneration.value
        verify(generation)
        check(event.format == 1) { "TIMELINE_UNSUPPORTED" }
        check(event.expiresAt > System.currentTimeMillis()) { "TIMELINE_EXPIRED" }
        val sealed = event.sealedDetails ?: error("TIMELINE_MISSING")
        val details = json.decodeFromString<TimelineDetails>(security.decryptTimelineSnapshot(sealed))
        check(details.version == 1) { "TIMELINE_UNSUPPORTED" }
        currentCoroutineContext().ensureActive()
        verify(generation)
        details
    }

    data class Plan internal constructor(val eventId: String, val executionId: String, val generation: Long,
        internal val sealed: String, val details: TimelineDetails)

    suspend fun preview(id: String): Plan = withContext(Dispatchers.IO) {
        db.withTransaction {
            val generation = SessionManager.lockGeneration.value
            verify(generation)
            val event = db.timelineDao().get(id) ?: error("TIMELINE_MISSING")
            val details = read(event)
            check(event.recoveryCandidate && TimelineRecoveryPolicy.canRestore(details)) { "TIMELINE_UNSUPPORTED" }
            checkCurrent(details)
            verify(generation)
            Plan(id, UUID.randomUUID().toString(), generation, requireNotNull(event.sealedDetails), details)
        }
    }

    private fun checkCurrent(details: TimelineDetails) {
        check(details.changes.all { snapshot(it.kind, it.itemId) == it.after }) { "TIMELINE_CONFLICT" }
        details.changes.filter { it.kind == "PASSWORD" }.forEach { change ->
            change.before!!.fields.forEach { field ->
                val current = rows("SELECT entry_id FROM custom_fields WHERE id = ?", arrayOf<Any?>(field.text("id").toLong())).singleOrNull()
                check(current == null || current.text("entry_id") == change.itemId.toString()) { "TIMELINE_CONFLICT" }
            }
        }
    }

    /** executionId is stable for retries; no toggle on the original event and no duplicate writes. */
    suspend fun confirm(plan: Plan): String = withContext(Dispatchers.IO) {
        db.withTransaction {
            currentCoroutineContext().ensureActive()
            verify(plan.generation)
            db.timelineDao().get(plan.executionId)?.let {
                check(it.parentId == plan.eventId) { "TIMELINE_CONFLICT" }
                return@withTransaction it.id
            }
            val source = db.timelineDao().get(plan.eventId) ?: error("TIMELINE_MISSING")
            check(source.sealedDetails == plan.sealed) { "TIMELINE_CONFLICT" }
            val details = read(source)
            check(source.recoveryCandidate && TimelineRecoveryPolicy.canRestore(details)) { "TIMELINE_UNSUPPORTED" }
            checkCurrent(details)
            val now = System.currentTimeMillis()
            val restored = details.changes.map { change ->
                val old = requireNotNull(change.before)
                val current = requireNotNull(change.after)
                val row = current.row.toMutableMap()
                TimelineRecoveryPolicy.columns(change).forEach { row[it] = old.row.getValue(it) }
                row["updatedAt"] = TimelineCell(Cursor.FIELD_TYPE_INTEGER, now.toString())
                TimelineChange(change.kind, change.itemId, current, current.copy(row = row, fields = (old.fields.filterNot { it.text("title").startsWith("monica.") }.map { field ->
                    if (field.text("id") in change.equivalentFieldIds) current.fields.single { it.text("id") == field.text("id") } else field
                } +
                    current.fields.filter { it.text("title").startsWith("monica.") }).sortedBy { it.text("id").toLong() }))
            }
            val recovery = details.copy(changes = restored)
            // Encrypt and persist the current values before changing any business row.
            val sealed = security.encryptTimelineSnapshot(json.encodeToString(recovery))
            db.timelineDao().insert(TimelineRecord(plan.executionId, now, "RESTORE_FIELDS", "LOCAL", "local",
                sealed, now + RETENTION_MILLIS, true, source.id))
            db.timelineDao().insertTargets(restored.map { TimelineTarget(plan.executionId, it.kind, it.itemId, "local") })
            restored.forEach { change ->
                currentCoroutineContext().ensureActive()
                verify(plan.generation)
                val columns = TimelineRecoveryPolicy.columns(change) + "updatedAt"
                val row = requireNotNull(change.after).row
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE ${table(change.kind)} SET ${columns.joinToString { "`$it` = ?" }} WHERE id = ?",
                    (columns.map { row.getValue(it).boundValue() } + change.itemId).toTypedArray())
                if (change.kind == "PASSWORD" && change.before!!.fields != change.after.fields) {
                    db.openHelper.writableDatabase.execSQL("DELETE FROM custom_fields WHERE entry_id = ?", arrayOf<Any?>(change.itemId))
                    change.after.fields.forEach { field ->
                        val keys = field.keys.toList()
                        require(keys.all { it in fieldColumns })
                        db.openHelper.writableDatabase.execSQL("INSERT INTO custom_fields (${keys.joinToString { "`$it`" }}) VALUES (${keys.joinToString { "?" }})",
                            keys.map { field.getValue(it).boundValue() }.toTypedArray())
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            verify(plan.generation)
            plan.executionId
        }
    }

    companion object {
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_MATERIAL_CHARS = 8 * 1024 * 1024
        private val identityColumns = setOf("id", "keepassDatabaseId", "keepass_database_id", "mdbx_database_id", "bitwarden_vault_id")
        private val fieldColumns = setOf("id", "entry_id", "title", "value", "is_protected", "sort_order")
        internal fun table(kind: String): String = if (kind == "PASSWORD") "password_entries" else {
            require(kind in setOf("SECURE", "TOTP", "NOTE", "BANK_CARD", "DOCUMENT", "BILLING_ADDRESS", "PAYMENT_ACCOUNT"))
            "secure_items"
        }
        private fun TimelineCell.boundValue(): Any? = when (type) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> requireNotNull(value).toLong()
            Cursor.FIELD_TYPE_FLOAT -> requireNotNull(value).toDouble()
            Cursor.FIELD_TYPE_STRING -> requireNotNull(value)
            else -> error("Unsupported timeline column")
        }
    }
}
