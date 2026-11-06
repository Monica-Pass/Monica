package takagi.ru.monica.data.dedup

import android.database.Cursor
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import takagi.ru.monica.data.PasswordDatabase

/** Reads optional protocol tables without creating them or invoking the sync provider. */
internal object SingleVaultDedupDeviceSyncAudit {
    private data class Column(val name: String, val type: String)
    private val layouts = mapOf(
        "device_sync_password_identity" to listOf(Column("global_id", "TEXT"), Column("local_id", "INTEGER"), Column("entry_type", "TEXT")),
        "device_sync_vault_identity" to listOf(Column("global_id", "TEXT"), Column("local_id", "TEXT")),
        "device_sync_content_extensions" to listOf(Column("local_id", "TEXT"), Column("payload", "TEXT")),
        "device_sync_attachment_identity" to listOf(Column("local_id", "INTEGER"), Column("portable_id", "TEXT")),
        "device_sync_pending_bindings" to listOf(Column("local_id", "TEXT"), Column("guard", "TEXT")),
        "device_sync_vault_metadata" to listOf(Column("global_id", "TEXT"), Column("payload", "TEXT"), Column("deleted", "INTEGER")),
        "device_sync_metadata_assets" to listOf(Column("owner", "TEXT"), Column("portable_id", "TEXT"), Column("payload", "TEXT")),
    )

    data class Snapshot(
        val revision: String,
        val complete: Boolean,
        val passwordIds: Set<Long>,
        val itemIds: Set<Long>,
        val attachmentIds: Set<Long>,
        val passkeyIds: Set<Long>,
    ) { override fun toString() = "DeviceSyncAudit(<redacted>)" }

    /** Call inside the same Room transaction as the original rows and every final write check. */
    fun read(database: PasswordDatabase): Snapshot {
        val db = database.openHelper.readableDatabase
        val evidence = mutableListOf<List<String?>>()
        val passwords = mutableSetOf<Long>()
        val items = mutableSetOf<Long>()
        val attachments = mutableSetOf<Long>()
        val passkeys = mutableSetOf<Long>()
        var complete = true
        // Include unknown tables, views and triggers in evidence; unfamiliar protocol data blocks the source.
        val objects = db.query("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name GLOB 'device_sync_*' OR tbl_name GLOB 'device_sync_*' ORDER BY type,name").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0..3).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
        evidence += objects
        objects.forEach { row ->
            if (row[0] !in setOf("table", "index") || row[2] !in layouts) complete = false
        }
        fun address(value: String?) {
            val prefix = value?.substringBefore(':')
            val raw = value?.substringAfter(':', "")
            val id = raw?.toLongOrNull()?.takeIf { it > 0 && it.toString() == raw }
            if (id == null) { complete = false; return }
            when (prefix) {
                "local" -> passwords += id
                "secure" -> items += id
                // These are independently retained; identities are still included in revision evidence.
                "passkey" -> passkeys += id
                "folder" -> Unit
                else -> complete = false
            }
        }
        for (objectRow in objects.filter { it[0] == "table" }) {
            val name = requireNotNull(objectRow[1])
            val expected = layouts[name] ?: continue
            // Only names from the fixed map reach SQL identifiers.
            val actual = db.query("PRAGMA table_info(`$name`)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(Column(cursor.getString(1), cursor.getString(2).uppercase())) }
            }
            if (actual != expected) { complete = false; continue }
            val rows = db.query("SELECT ${expected.joinToString(",") { "`${it.name}`" }} FROM `$name`").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        check(size < 100_000) { "Sync audit exceeds the supported limit" }
                        add(expected.mapIndexed { index, column ->
                            val type = cursor.getType(index)
                            if (type != if (column.type == "INTEGER") Cursor.FIELD_TYPE_INTEGER else Cursor.FIELD_TYPE_STRING) complete = false
                            when (type) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(index)}"
                                Cursor.FIELD_TYPE_STRING -> "text:${cursor.getString(index)}"
                                else -> { complete = false; "unsupported-type:$type" }
                            }
                        })
                    }
                }
            }.sortedBy { JsonArray(it.map { value -> value?.let(::JsonPrimitive) ?: JsonNull }).toString() }
            evidence += rows.map { listOf(name) + it }
            fun List<String?>.text(column: String) = get(expected.indexOfFirst { it.name == column })?.takeIf { it.startsWith("text:") }?.removePrefix("text:")
            fun List<String?>.number(column: String) = get(expected.indexOfFirst { it.name == column })?.takeIf { it.startsWith("integer:") }?.removePrefix("integer:")?.toLongOrNull()?.takeIf { it > 0 }
            rows.forEach { row ->
                when (name) {
                    "device_sync_password_identity" -> row.number("local_id")?.let(passwords::add) ?: run { complete = false }
                    "device_sync_attachment_identity" -> row.number("local_id")?.let(attachments::add) ?: run { complete = false }
                    "device_sync_vault_identity", "device_sync_pending_bindings" -> address(row.text("local_id"))
                    "device_sync_content_extensions" -> {
                        address(row.text("local_id"))
                        // Extensions may carry future inbound relations that Room cannot express.
                        complete = false
                    }
                    "device_sync_vault_metadata", "device_sync_metadata_assets" -> complete = false
                }
            }
        }
        val encoded = JsonArray(evidence.map { row -> JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull }) }).toString()
        val revision = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
        return Snapshot(revision, complete, passwords, items, attachments, passkeys)
    }
}
