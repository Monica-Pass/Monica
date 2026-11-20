package takagi.ru.monica.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import takagi.ru.monica.security.SessionManager

/** Keeps the existing transaction/lock boundary without recording activity or copying values. */
internal class DatabaseWriteGuard(private val database: PasswordDatabase) {
    suspend fun <T> write(block: suspend () -> T): T {
        val generation = SessionManager.lockGeneration.value.takeIf { SessionManager.isUnlocked.value }
        fun verify() {
            if (generation != null) check(SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation) { "Vault is locked" }
        }
        return withContext(Dispatchers.IO) {
            database.withTransaction {
                verify()
                val result = block()
                currentCoroutineContext().ensureActive()
                verify()
                result
            }
        }
    }

    suspend fun <T> delete(kind: String, ids: List<Long>, block: suspend () -> T): T = write {
        val existing = ids.distinct().filter { exists(kind, it) }
        val result = block()
        existing.filterNot { exists(kind, it) }.forEach { id ->
            if (hasLegacyJournal()) database.openHelper.writableDatabase.execSQL(
                "UPDATE timeline_events SET sealedDetails = NULL, recoveryCandidate = 0 WHERE id IN (SELECT eventId FROM timeline_targets WHERE kind = ? AND itemId = ?)",
                arrayOf<Any?>(kind, id))
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM timeline_version_snapshots WHERE (item_type = ? OR (? = 'SECURE' AND item_type IN ('SECURE','TOTP','NOTE','BANK_CARD','DOCUMENT','BILLING_ADDRESS','PAYMENT_ACCOUNT'))) AND item_id = ?",
                arrayOf<Any?>(kind, kind, id))
        }
        result
    }

    suspend fun <T> deleteQuery(kind: String, where: String, parameters: Map<String, Any?>, block: suspend () -> T): T = write {
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
        val ids = database.openHelper.writableDatabase.query(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT id FROM ${table(kind)} WHERE $expanded", args.toTypedArray())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        delete(kind, ids, block)
    }

    private fun hasLegacyJournal(): Boolean = database.openHelper.writableDatabase.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('timeline_events','timeline_targets')"
    ).use { it.moveToFirst() && it.getInt(0) == 2 }

    private fun exists(kind: String, id: Long): Boolean = database.openHelper.writableDatabase.query(
        androidx.sqlite.db.SimpleSQLiteQuery("SELECT 1 FROM ${table(kind)} WHERE id = ?", arrayOf(id))
    ).use { it.moveToFirst() }

    private fun table(kind: String): String = when (kind) {
        "PASSWORD" -> "password_entries"
        "SECURE" -> "secure_items"
        else -> error("Unsupported deletion kind")
    }
}
