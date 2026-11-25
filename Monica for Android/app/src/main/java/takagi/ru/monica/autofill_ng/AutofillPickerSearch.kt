package takagi.ru.monica.autofill_ng

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import takagi.ru.monica.data.PasswordEntry

/** Search-only labels: request-local, resolved on IO, never written into credentials. */
internal object AutofillPickerSearch {
    private val separator = Regex("[,;|\\s]+")
    private val packageShape = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

    internal fun linkedPackages(entry: PasswordEntry): Set<String> {
        fun packageName(token: String, website: Boolean): String? {
            val raw = token.trim()
            val appUri = raw.startsWith("androidapp://", true) || raw.startsWith("android-app://", true)
            val value = if (appUri) raw.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#') else raw
            if (!packageShape.matches(value)) return null
            if (website && !appUri && value.count { it == '.' } < 2) return null
            return value
        }
        return (entry.appPackageName.split(separator).mapNotNull { packageName(it, false) } +
            entry.website.split(separator).mapNotNull { packageName(it, true) }).toSet()
    }

    suspend fun loadAppLabels(entries: List<PasswordEntry>, resolveLabel: (String) -> String?): Map<Long, List<String>> {
        val cache = mutableMapOf<String, String?>()
        return entries.associate { entry ->
            entry.id to linkedPackages(entry).mapNotNull { packageName ->
                currentCoroutineContext().ensureActive()
                if (!cache.containsKey(packageName)) cache[packageName] = resolveLabel(packageName)?.takeIf { it.isNotBlank() }
                cache[packageName]
            }.distinct()
        }
    }

    fun matches(entry: PasswordEntry, query: String, appLabels: List<String> = emptyList()): Boolean {
        val needle = query.trim()
        return needle.isEmpty() || sequenceOf(entry.title, entry.appName, entry.appPackageName, entry.username, entry.website)
            .plus(appLabels.asSequence()).any { it.contains(needle, ignoreCase = true) }
    }
}
