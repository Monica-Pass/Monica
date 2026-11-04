package takagi.ru.monica.bitwarden.service

import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A title is never a lookup key. Account/session checks happen before and after network reads. */
internal object BitwardenSendSubmission {
    fun uniqueCandidate(request: JsonObject, serverRows: List<JsonObject>): JsonObject? {
        val key = BitwardenSendEdit.string(request, "key")?.takeIf(String::isNotBlank) ?: return null
        return serverRows.filter { BitwardenSendEdit.string(it, "key") == key }
            .singleOrNull()?.takeIf { matches(request, it) }
    }

    fun matches(request: JsonObject, actual: JsonObject, previousHasPassword: Boolean? = null,
        passwordRemoved: Boolean = false): Boolean {
        return runCatching {
        fun field(source: JsonObject, name: String): JsonElement? =
            BitwardenSendEdit.field(source, name)?.takeUnless { it == JsonNull }
        fun string(source: JsonObject, name: String): String? = BitwardenSendEdit.string(source, name)
        fun same(name: String) = field(request, name) == field(actual, name)
        fun date(source: JsonObject, name: String): Instant? = string(source, name)?.let(Instant::parse)
        val key = string(request, "key")?.takeIf(String::isNotBlank) ?: return false
        val kind = string(request, "type")
        if (string(actual, "id").isNullOrBlank() || string(actual, "accessId").isNullOrBlank() ||
            string(actual, "key") != key || kind !in setOf("0", "1") || !same("type") ||
            !same("name") || !same("notes") ||
            (string(request, "disabled") ?: "false") != (string(actual, "disabled") ?: "false") ||
            (string(request, "hideEmail") ?: "false") != (string(actual, "hideEmail") ?: "false") ||
            !same("maxAccessCount") || date(request, "deletionDate") != date(actual, "deletionDate") ||
            date(request, "expirationDate") != date(actual, "expirationDate")) return false

        // The saved document is the exact PUT. A separately requested password removal changes
        // only the expected final auth state; all original content/key/options checks still apply.
        if (passwordRemoved && (previousHasPassword != true || field(request, "password") != null)) return false
        val auth = if (passwordRemoved) "2" else string(request, "authType")
            ?: if (field(request, "password") == null && previousHasPassword != true) "2" else "1"
        val actualAuth = string(actual, "authType")
        when (auth) {
            "0" -> if (actualAuth != "0" || field(actual, "password") != null ||
                !same("emails") || string(request, "emails").isNullOrBlank()) return false
            "1" -> if (actualAuth !in setOf(null, "1") || field(actual, "password") == null ||
                !string(actual, "emails").isNullOrBlank()) return false
            "2" -> if (actualAuth !in setOf(null, "2") || field(actual, "password") != null ||
                !string(actual, "emails").isNullOrBlank()) return false
            else -> if (!same("authType") || !same("emails")) return false
        }
        if (kind == "0") {
            val expectedText = field(request, "text") as? JsonObject ?: return false
            val actualText = field(actual, "text") as? JsonObject ?: return false
            field(expectedText, "text") == field(actualText, "text") &&
                (string(expectedText, "hidden") ?: "false") == (string(actualText, "hidden") ?: "false")
        } else {
            val expectedFile = field(request, "file") as? JsonObject ?: return false
            val actualFile = field(actual, "file") as? JsonObject ?: return false
            // Existing file metadata is kept intact; a new file also binds the encrypted byte count.
            if (string(expectedFile, "id") != null) expectedFile == actualFile
            else field(expectedFile, "fileName") == field(actualFile, "fileName") &&
                !string(actualFile, "id").isNullOrBlank() &&
                string(request, "fileLength")?.toLongOrNull()?.let { it == string(actualFile, "size")?.toLongOrNull() } == true
        }
        }.getOrDefault(false)
    }
}
