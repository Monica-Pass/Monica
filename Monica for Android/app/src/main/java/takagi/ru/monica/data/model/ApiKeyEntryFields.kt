package takagi.ru.monica.data.model

import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import java.net.URI

/** Only the type and endpoint use custom fields. The key uses the encrypted password column. */
object ApiKeyEntryFields {
    const val TYPE = "API_KEY"
    const val MARKER = "monica_api_key_type"
    const val API_URL = "monica_api_key_url"

    fun isApiKey(fields: Map<String, String>): Boolean = fields[MARKER] == TYPE
    fun owns(name: String): Boolean = name == MARKER || name == API_URL

    fun encode(apiUrl: String): List<CustomFieldDraft> = buildList {
        add(CustomFieldDraft(title = MARKER, value = TYPE))
        if (apiUrl.isNotBlank()) add(CustomFieldDraft(title = API_URL, value = apiUrl.trim()))
    }

    fun isValidOptionalUrl(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty()) return true
        if (text.length > 2048 || text.any { it.isISOControl() }) return false
        return runCatching {
            val uri = URI(text)
            uri.scheme.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port in 1..65535)
        }.getOrDefault(false)
    }
}

data class ApiKeyDraft(
    val provider: String = "",
    val website: String = "",
    val key: String = "",
    val apiUrl: String = "",
    val notes: String = "",
) {
    // Prevent accidental logging of a secret-bearing draft.
    override fun toString(): String = "ApiKeyDraft(redacted)"

    val isValid: Boolean get() = provider.isNotBlank() && key.isNotBlank() &&
        ApiKeyEntryFields.isValidOptionalUrl(website) && ApiKeyEntryFields.isValidOptionalUrl(apiUrl)

    fun toEntry(original: PasswordEntry? = null): PasswordEntry =
        (original ?: PasswordEntry(title = "", website = "", username = "", password = "")).copy(
            title = provider.trim(), website = website.trim(), password = key,
            notes = notes, loginType = ApiKeyEntryFields.TYPE,
        )

    fun customFields(existing: List<CustomFieldDraft>): List<CustomFieldDraft> =
        existing.filterNot { ApiKeyEntryFields.owns(it.title) } + ApiKeyEntryFields.encode(apiUrl)

    companion object {
        fun from(entry: PasswordEntry, fields: Map<String, String>) = ApiKeyDraft(
            provider = entry.title, website = entry.website, key = entry.password,
            apiUrl = fields[ApiKeyEntryFields.API_URL].orEmpty(), notes = entry.notes,
        )
    }
}
