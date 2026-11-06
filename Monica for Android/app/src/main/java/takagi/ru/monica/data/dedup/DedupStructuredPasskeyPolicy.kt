package takagi.ru.monica.data.dedup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Detect explicit structured credential markers, never free-form notes or encoded string bodies. */
internal object DedupStructuredPasskeyPolicy {
    fun containsPasskeyContent(value: JsonElement): Boolean = contains(value, 0, false)

    private fun contains(value: JsonElement, depth: Int, fieldRecord: Boolean): Boolean {
        require(depth < 64) { "Structured content exceeds the supported depth" }
        return when (value) {
            is JsonObject -> value.keys.any(::isMarker) ||
                ((fieldRecord || "value" in value) && labels.any { label ->
                    (value[label] as? JsonPrimitive)?.let { it.isString && isMarker(it.content) } == true
                }) || value.any { (name, child) ->
                    contains(child, depth + 1, fieldCollections.any { name.equals(it, ignoreCase = true) })
                }
            is JsonArray -> value.any { contains(it, depth + 1, fieldRecord) }
            is JsonPrimitive -> false
        }
    }

    private fun isMarker(name: String): Boolean =
        name.contains("passkey", ignoreCase = true) || name.contains("fido", ignoreCase = true)

    private val labels = setOf("label", "name", "title")
    private val fieldCollections = setOf("customFields", "custom_fields", "fields")
}
