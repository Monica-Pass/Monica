package takagi.ru.monica.ime

import java.util.Locale
import takagi.ru.monica.data.isLocalPasswordOwnership
import takagi.ru.monica.rustcore.RustPasswordListCore

/** One index per unlocked vault snapshot. Queries never repeat ICU transliteration or Room reads. */
internal class ImePasswordIndex(
    entries: List<MonicaImePasswordEntry>,
    sortKey: ((String) -> String)? = null,
) {
    private val alphabetical = run {
        val labels = entries.map(::imePasswordAlphabeticalLabel)
        val keys = if (sortKey != null) labels.map(sortKey) else normalizedImeSortKeys(labels)
        entries.zip(keys.map { it.lowercase(Locale.ROOT) })
    }.sortedWith(compareBy<Pair<MonicaImePasswordEntry, String>> { it.second }.thenBy { it.first.id })
        .map { (entry, key) -> entry.copy(alphabeticalLetter = imeIndexLetter(key)) }

    private val searchFields = alphabetical.map { entry ->
        arrayOf(entry.title, entry.username, entry.website, entry.appName, entry.packageName)
            .map { it.lowercase(Locale.ROOT) }.toTypedArray()
    }

    private val metadata by lazy {
        RustPasswordListCore.prepareSearch(searchFields.map { fields ->
            RustPasswordListCore.SearchRow(fields[0], fields[1], fields[2], fields[3], fields[4])
        })
    }

    fun query(rawQuery: String, scope: MonicaImeDatabaseScope = MonicaImeDatabaseScope.All,
        sortMode: MonicaImePasswordSortMode = MonicaImePasswordSortMode.ALPHABETICAL,
        activePackageName: String = ""): List<MonicaImePasswordEntry> {
        val query = ImeSearchQuery(rawQuery)
        val nativeMatches = if (query.terms.isNotEmpty() && alphabetical.size >= 256) nativeMatches(query) else null
        val matches = alphabetical.filterIndexed { index, entry ->
            entry.matchesImeScope(scope) && (nativeMatches?.get(index) ?: query.matchesNormalized(searchFields[index]))
        }
        if (sortMode == MonicaImePasswordSortMode.ALPHABETICAL) return matches
        // Stable sorting preserves the precomputed alphabetical/id order within each priority.
        return matches.map { entry ->
            entry to imeEntryMatchesPackage(entry.packageName, entry.website, entry.title, activePackageName)
        }.sortedWith(compareByDescending<Pair<MonicaImePasswordEntry, Boolean>> { it.second }
            .thenByDescending { it.first.isFavorite }).map { it.first }
    }

    private fun nativeMatches(query: ImeSearchQuery): BooleanArray? {
        val matches = BooleanArray(alphabetical.size) { true }
        for (term in query.terms) {
            val indices = RustPasswordListCore.filterIndices(metadata, term) ?: return null
            val termMatches = BooleanArray(alphabetical.size)
            indices.forEach { termMatches[it] = true }
            matches.indices.forEach { matches[it] = matches[it] && termMatches[it] }
        }
        return matches
    }
}

internal class ImeSearchQuery(raw: String) {
    val terms = raw.trim().split(Whitespace).filter(String::isNotBlank).map { it.lowercase(Locale.ROOT) }

    fun matchesNormalized(fields: Array<String>): Boolean = terms.all { term -> fields.any { it.contains(term) } }

    fun matches(vararg fields: String): Boolean = terms.isEmpty() ||
        matchesNormalized(fields.map { it.lowercase(Locale.ROOT) }.toTypedArray())

    fun matchesPassword(entry: MonicaImePasswordEntry): Boolean =
        matches(entry.title, entry.username, entry.website, entry.appName, entry.packageName)

    private companion object { val Whitespace = Regex("\\s+") }
}

internal fun MonicaImePasswordEntry.matchesImeScope(scope: MonicaImeDatabaseScope): Boolean = when (scope) {
    MonicaImeDatabaseScope.All -> true
    MonicaImeDatabaseScope.Local -> isLocalPasswordOwnership(keepassDatabaseId, bitwardenVaultId, mdbxDatabaseId)
    is MonicaImeDatabaseScope.KeePass -> keepassDatabaseId == scope.databaseId
    is MonicaImeDatabaseScope.Mdbx -> mdbxDatabaseId == scope.databaseId
    is MonicaImeDatabaseScope.Bitwarden -> bitwardenVaultId == scope.vaultId
}
