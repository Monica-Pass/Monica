package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.json.*

private fun JsonObject.field(name: String) = entries.singleOrNull { it.key.equals(name, true) }?.value

internal fun folderCopyDocumentIsSafe(document: JsonObject, sourceId: String? = null, sourceFolderId: String? = null): Boolean = runCatching {
    fun absent(value: JsonElement?) = value == null || value == JsonNull || value is JsonArray && value.isEmpty()
    (sourceId == null || (document.field("id") as? JsonPrimitive)?.contentOrNull == sourceId) &&
        (sourceFolderId == null || (document.field("folderId") as? JsonPrimitive)?.contentOrNull == sourceFolderId) &&
        absent(document.field("attachments")) &&
        absent((document.field("login") as? JsonObject)?.field("fido2Credentials")) &&
        (document.field("organizationId") == null || document.field("organizationId") == JsonNull) &&
        (document.field("deletedDate") == null || document.field("deletedDate") == JsonNull)
}.getOrDefault(false)

/** Same vault and item key: keep every encrypted field, excluding response identity metadata. */
internal fun folderCopyDocument(document: JsonObject, folderId: String): JsonObject {
    require(folderCopyDocumentIsSafe(document))
    val responseFields = setOf("id", "object", "revisiondate", "creationdate", "deleteddate", "folderid",
        "attachments", "edit", "viewpassword", "organizationusetotp")
    return JsonObject(document.filterKeys { it.lowercase() !in responseFields }
        .mapKeys { it.key.replaceFirstChar(Char::lowercaseChar) } + ("folderId" to JsonPrimitive(folderId)))
}

internal fun folderCopyPreserved(request: JsonObject, response: JsonObject): Boolean {
    fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapKeys { it.key.lowercase() }.mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    val actual = canonical(response) as JsonObject
    return (canonical(request) as JsonObject).filterKeys { it != "encryptedfor" }.all { (key, value) -> actual[key] == value }
}
