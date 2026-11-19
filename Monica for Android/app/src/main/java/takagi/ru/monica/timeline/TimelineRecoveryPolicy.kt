package takagi.ru.monica.timeline

import kotlinx.serialization.json.*

/** Only independent field edits are restored. Identity, bindings, attachments and unknown
 * columns are guards, never reconstructed from a partial historical row. */
object TimelineRecoveryPolicy {
    val ignoredColumns = setOf("updatedAt", "customIconUpdatedAt")
    private val passwordFields = setOf("title", "website", "username", "password", "notes", "email", "phone",
        "addressLine", "city", "state", "zipCode", "country", "creditCardNumber", "creditCardHolder",
        "creditCardExpiry", "creditCardCVV", "appPackageName", "appName", "isFavorite", "sortOrder")
    private val secureFields = setOf("title", "notes", "isFavorite", "sortOrder")

    internal val comparableColumns = passwordFields + secureFields + "itemData"

    fun changedFieldIds(change: TimelineChange): Set<String> {
        val old = change.before?.fields.orEmpty().associateBy { it.text("id") }
        val new = change.after?.fields.orEmpty().associateBy { it.text("id") }
        return (old.keys + new.keys).filterTo(linkedSetOf()) { id ->
            old[id] != new[id] && !(id in change.equivalentFieldIds && old[id] != null && new[id] != null &&
                old.getValue(id) - "value" == new.getValue(id) - "value")
        }
    }

    fun columns(change: TimelineChange): Set<String> {
        if (change.before == null || change.after == null) return emptySet()
        val before = change.before.row
        val after = change.after.row
        return (before.keys + after.keys).filterTo(linkedSetOf()) { before[it] != after[it] && it !in ignoredColumns && !(it in comparableColumns && it in change.equivalentColumns) }
    }

    fun canRestore(change: TimelineChange): Boolean {
        val before = change.before ?: return false
        val after = change.after ?: return false
        if (!before.row.isLocal() || !after.row.isLocal()) return false
        if (before.row.text("isDeleted") != "0" || after.row.text("isDeleted") != "0") return false
        if (before.row.text("passkey_bindings").isNotBlank() || after.row.text("passkey_bindings").isNotBlank()) return false
        if (before.row.text("loginType") in setOf("GPG_KEY", "API_KEY") || before.row.text("ssh_key_data").isNotBlank()) return false
        if (before.attachments != after.attachments) return false
        val allowed = if (change.kind == "PASSWORD") passwordFields else secureFields +
            if (before.row.text("itemType") == "NOTE" && after.row.text("itemType") == "NOTE") setOf("itemData") else emptySet()
        if (columns(change).any { it !in allowed }) return false
        if ("itemData" in columns(change)) {
            val safeKeys = setOf("content", "tags", "isMarkdown", "customFields")
            val safe = listOf(before, after).all { snapshot ->
                runCatching {
                    val data = Json.parseToJsonElement(snapshot.row.text("itemData")).jsonObject
                    data.keys.all { it in safeKeys } && data["content"] is JsonPrimitive &&
                        !data.toString().contains("monica-image://") && snapshot.row.text("imagePaths").let { it.isBlank() || it == "[]" }
                }.getOrDefault(false)
            }
            if (!safe) return false
        }
        // Metadata can encode references, credentials and content blocks. Preserve it verbatim.
        fun metadata(snapshot: LocalTimelineSnapshot) = snapshot.fields.filter { it.text("title").startsWith("monica.") }
            .map { it - "id" }.sortedBy { it.text("title") + it.text("value") }
        if (metadata(before) != metadata(after)) return false
        return columns(change).isNotEmpty() || changedFieldIds(change).isNotEmpty()
    }

    fun canRestore(details: TimelineDetails): Boolean = details.version == 1 &&
        details.changes.isNotEmpty() && details.changes.all(::canRestore)

    fun action(change: TimelineChange): String = when {
        change.after == null -> "PERMANENT_DELETE"
        change.before == null -> "CREATE"
        change.before.row.text("isDeleted") != "1" && change.after.row.text("isDeleted") == "1" -> "TRASH"
        change.before.row.text("isDeleted") == "1" && change.after.row.text("isDeleted") == "0" -> "RESTORE_TRASH"
        change.before.row.text("isArchived") != change.after.row.text("isArchived") -> if (change.after.row.text("isArchived") == "1") "ARCHIVE" else "UNARCHIVE"
        change.before.row.scope() != change.after.row.scope() -> "MOVE"
        else -> "UPDATE"
    }
}
