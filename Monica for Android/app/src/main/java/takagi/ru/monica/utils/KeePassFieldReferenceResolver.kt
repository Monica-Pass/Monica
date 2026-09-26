package takagi.ru.monica.utils

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import java.util.Locale

internal data class KeePassEntryResolutionContext(
    val entries: List<Entry>,
    val entriesByNormalizedUuid: Map<String, List<Entry>>
)

internal object KeePassFieldReferenceResolver {
    private const val MAX_DEPTH = 8
    private val refPattern = Regex("""\{REF:([A-Z])@([A-Z]):([^}]+)\}""", RegexOption.IGNORE_CASE)
    private val localPattern = Regex("""\{(?:S:([^}]+)|(TITLE|USERNAME|PASSWORD|URL|NOTES|UUID))\}""", RegexOption.IGNORE_CASE)
    private val standardFieldByCode = mapOf(
        'T' to "Title",
        'U' to "UserName",
        'P' to "Password",
        'A' to "URL",
        'N' to "Notes"
    )
    private val standardFieldNames = standardFieldByCode.values.toSet()

    fun buildContext(entries: Iterable<Entry>): KeePassEntryResolutionContext {
        val entryList = entries.toList()
        return KeePassEntryResolutionContext(
            entries = entryList,
            entriesByNormalizedUuid = entryList.groupBy { normalizeUuid(it.uuid.toString()) }
        )
    }

    fun getRawFieldValue(entry: Entry, key: String): String {
        return extractContent(entry.fields[key] ?: entry.fields.entries.firstOrNull {
            key in standardFieldNames && it.key.equals(key, ignoreCase = true)
        }?.value)
    }

    fun getFieldValue(
        entry: Entry,
        key: String,
        context: KeePassEntryResolutionContext? = null
    ): String {
        return resolveValue(getRawFieldValue(entry, key), entry, context)
    }

    fun getFieldValueIgnoreCase(
        entry: Entry,
        context: KeePassEntryResolutionContext? = null,
        vararg keys: String
    ): String {
        if (keys.isEmpty()) return ""
        val direct = keys.firstNotNullOfOrNull { key ->
            entry.fields[key]?.let { value ->
                resolveValue(extractContent(value), entry, context).takeIf { it.isNotBlank() }
            }
        }
        if (direct != null) return direct

        val matched = entry.fields.entries.firstOrNull { (fieldKey, _) ->
            keys.any { it.equals(fieldKey, ignoreCase = true) }
        } ?: return ""
        return resolveValue(extractContent(matched.value), entry, context)
    }

    fun resolveValue(
        rawValue: String,
        currentEntry: Entry,
        context: KeePassEntryResolutionContext? = null
    ): String {
        return resolveValueInternal(rawValue, currentEntry, context, emptySet(), 0)
    }

    /** A present Password is authoritative, including empty strings and literal labels. */
    fun getPasswordFieldValue(entry: Entry, context: KeePassEntryResolutionContext? = null): String {
        val standardKey = if (entry.fields.containsKey("Password")) "Password" else
            entry.fields.keys.firstOrNull { it.equals("Password", ignoreCase = true) }
        if (standardKey != null) return getFieldValue(entry, standardKey, context)
        // Older writers used aliases. Other protected strings may be card PINs or recovery
        // codes and must never silently become the login password.
        return getFieldValueIgnoreCase(entry, context, "Pass", "pwd", "密码", "口令")
    }

    private fun resolveValueInternal(
        rawValue: String,
        currentEntry: Entry,
        context: KeePassEntryResolutionContext?,
        visited: Set<String>,
        depth: Int
    ): String {
        if (rawValue.isBlank() || depth >= MAX_DEPTH || !rawValue.contains('{')) {
            return rawValue
        }
        val withLocalFields = localPattern.replace(rawValue) { match ->
            val tokenKey = "${currentEntry.uuid}:${match.value}"
            if (tokenKey in visited) return@replace match.value
            val customName = match.groupValues[1]
            val name = customName.ifEmpty {
                when (match.groupValues[2].uppercase(Locale.ROOT)) {
                    "TITLE" -> "Title"
                    "USERNAME" -> "UserName"
                    "PASSWORD" -> "Password"
                    "URL" -> "URL"
                    "NOTES" -> "Notes"
                    else -> return@replace normalizeUuid(currentEntry.uuid.toString()).uppercase(Locale.ROOT)
                }
            }
            val value = currentEntry.fields[name] ?: return@replace match.value
            resolveValueInternal(extractContent(value), currentEntry, context, visited + tokenKey, depth + 1)
        }
        if (context == null) return withLocalFields
        return refPattern.replace(withLocalFields) { match ->
            val tokenKey = "${currentEntry.uuid}:${match.value}"
            if (tokenKey in visited) {
                return@replace match.value
            }
            resolveReferenceToken(
                currentEntry = currentEntry,
                matchValue = match,
                context = context,
                visited = visited + tokenKey,
                depth = depth + 1
            ) ?: match.value
        }
    }

    private fun resolveReferenceToken(
        currentEntry: Entry,
        matchValue: MatchResult,
        context: KeePassEntryResolutionContext,
        visited: Set<String>,
        depth: Int
    ): String? {
        val targetCode = matchValue.groupValues.getOrNull(1)?.uppercase(Locale.ROOT)?.firstOrNull() ?: return null
        val searchCode = matchValue.groupValues.getOrNull(2)?.uppercase(Locale.ROOT)?.firstOrNull() ?: return null
        val searchText = resolveValueInternal(
            rawValue = matchValue.groupValues.getOrNull(3).orEmpty(),
            currentEntry = currentEntry,
            context = context,
            visited = visited,
            depth = depth
        )
        val matchedEntry = findReferencedEntry(searchCode, searchText, context, visited, depth) ?: return null
        return resolveReferenceField(matchedEntry, targetCode, context, visited, depth)
    }

    private fun findReferencedEntry(
        searchCode: Char,
        searchText: String,
        context: KeePassEntryResolutionContext,
        visited: Set<String>,
        depth: Int
    ): Entry? {
        if (searchText.isBlank()) return null
        return when (searchCode) {
            'I' -> context.entriesByNormalizedUuid[normalizeUuid(searchText)]?.firstOrNull()
            'T', 'U', 'P', 'A', 'N', 'O' -> context.entries.firstOrNull { entry ->
                resolveSearchValues(entry, searchCode, context, visited, depth).any { candidateValue ->
                    candidateValue.contains(searchText, ignoreCase = true)
                }
            }
            else -> null
        }
    }

    private fun resolveReferenceField(
        entry: Entry,
        targetCode: Char,
        context: KeePassEntryResolutionContext,
        visited: Set<String>,
        depth: Int
    ): String? {
        return when (targetCode) {
            'I' -> normalizeUuid(entry.uuid.toString()).uppercase(Locale.ROOT)
            'O' -> null // O is a search scope; custom values use {S:Name} in a standard field.
            else -> {
                val fieldName = standardFieldByCode[targetCode] ?: return null
                resolveValueInternal(getRawFieldValue(entry, fieldName), entry, context, visited, depth)
            }
        }
    }

    private fun resolveSearchValues(
        entry: Entry,
        code: Char,
        context: KeePassEntryResolutionContext,
        visited: Set<String>,
        depth: Int
    ): List<String> {
        return when (code) {
            'I' -> listOf(entry.uuid.toString())
            'O' -> entry.fields.entries.mapNotNull { (key, value) ->
                if (key in standardFieldNames) {
                    null
                } else {
                    resolveValueInternal(extractContent(value), entry, context, visited, depth)
                        .takeIf { it.isNotBlank() }
                }
            }
            else -> {
                val fieldName = standardFieldByCode[code] ?: return emptyList()
                listOf(resolveValueInternal(getRawFieldValue(entry, fieldName), entry, context, visited, depth))
                    .filter { it.isNotBlank() }
            }
        }
    }

    private fun normalizeUuid(value: String): String {
        return value.trim().trim('{', '}').replace("-", "").lowercase(Locale.ROOT)
    }

    private fun extractContent(value: EntryValue?): String {
        return runCatching { value?.content.orEmpty() }.getOrDefault("")
    }
}
