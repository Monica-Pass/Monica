package takagi.ru.monica.bitwarden.service

import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

enum class SendPasswordAction { KEEP, REPLACE, REMOVE }

data class BitwardenSendDraft(
    val title: String,
    val text: String,
    val notes: String?,
    val passwordAction: SendPasswordAction = SendPasswordAction.KEEP,
    val password: String? = null,
    val maxAccessCount: Int? = null,
    val hideEmail: Boolean = false,
    val hiddenText: Boolean = false,
    val disabled: Boolean = false,
    val deletionMillis: Long,
    val expirationMillis: Long? = null
) {
    fun validate(now: Long, isText: Boolean) {
        require(title.isNotBlank()) { "Send title is required" }
        require(!isText || text.isNotEmpty()) { "Send text is required" }
        require(maxAccessCount == null || maxAccessCount > 0) { "Invalid Send access limit" }
        require(deletionMillis > now && deletionMillis <= now + 31L * 86_400_000L) { "Invalid Send deletion date" }
        require(expirationMillis == null || expirationMillis <= deletionMillis) { "Send expiration must not follow deletion" }
        require(passwordAction != SendPasswordAction.REPLACE || !password.isNullOrEmpty()) { "Send password is required" }
    }
}

/** Preserve opaque fields and file metadata. Password null means unchanged on the update API. */
internal object BitwardenSendEdit {
    fun field(source: JsonObject, name: String): JsonElement? =
        source.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun string(source: JsonObject, name: String): String? =
        field(source, name)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content

    fun patch(source: JsonObject, draft: BitwardenSendDraft, name: String,
        notes: String?, text: String?, passwordHash: String?): JsonObject {
        val values = source.toMutableMap()
        fun set(field: String, value: JsonElement) {
            values.keys.filter { it.equals(field, ignoreCase = true) }.forEach(values::remove)
            values[field] = value
        }
        set("name", JsonPrimitive(name))
        set("notes", notes?.let(::JsonPrimitive) ?: JsonNull)
        set("password", passwordHash?.let(::JsonPrimitive) ?: JsonNull)
        if (draft.passwordAction == SendPasswordAction.REPLACE) {
            set("authType", JsonPrimitive(1))
            set("emails", JsonNull)
        }
        set("maxAccessCount", draft.maxAccessCount?.let(::JsonPrimitive) ?: JsonNull)
        set("hideEmail", JsonPrimitive(draft.hideEmail))
        set("disabled", JsonPrimitive(draft.disabled))
        set("deletionDate", JsonPrimitive(Instant.ofEpochMilli(draft.deletionMillis).toString()))
        set("expirationDate", draft.expirationMillis?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
        if (text != null) {
            val textValues = (field(source, "text") as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            textValues.keys.filter { it.equals("text", true) || it.equals("hidden", true) }.forEach(textValues::remove)
            textValues["text"] = JsonPrimitive(text)
            textValues["hidden"] = JsonPrimitive(draft.hiddenText)
            set("text", JsonObject(textValues))
        }
        return JsonObject(values)
    }

    fun sameBaseline(expected: JsonObject, actual: JsonObject): Boolean =
        (expected.keys + actual.keys).map { it.lowercase() }.toSet()
            .filterNot { it == "accesscount" }
            .all { field(expected, it) == field(actual, it) }
}
