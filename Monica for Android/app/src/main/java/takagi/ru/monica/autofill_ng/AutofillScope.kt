package takagi.ru.monica.autofill_ng

/** A selected source. A null ID means all databases of that source, never all sources. */
sealed interface AutofillScope {
    data object All : AutofillScope
    data object Local : AutofillScope
    data class KeePass(val databaseId: Long? = null) : AutofillScope
    data class Bitwarden(val vaultId: Long? = null) : AutofillScope
    data class Mdbx(val databaseId: Long? = null) : AutofillScope
}

internal fun AutofillScope.toDefaultSourceFilter(): AutofillPreferences.AutofillDefaultSourceFilter = when (this) {
    AutofillScope.All -> AutofillPreferences.AutofillDefaultSourceFilter.ALL
    AutofillScope.Local -> AutofillPreferences.AutofillDefaultSourceFilter.LOCAL
    is AutofillScope.KeePass -> AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS
    is AutofillScope.Bitwarden -> AutofillPreferences.AutofillDefaultSourceFilter.BITWARDEN
    is AutofillScope.Mdbx -> AutofillPreferences.AutofillDefaultSourceFilter.MDBX
}

internal fun AutofillPreferences.AutofillDefaultSourceFilter.toAutofillScope(
    keepassDatabaseId: Long? = null,
    bitwardenVaultId: Long? = null,
    mdbxDatabaseId: Long? = null,
): AutofillScope = when (this) {
    AutofillPreferences.AutofillDefaultSourceFilter.ALL -> AutofillScope.All
    AutofillPreferences.AutofillDefaultSourceFilter.LOCAL -> AutofillScope.Local
    AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS -> AutofillScope.KeePass(keepassDatabaseId)
    AutofillPreferences.AutofillDefaultSourceFilter.BITWARDEN -> AutofillScope.Bitwarden(bitwardenVaultId)
    AutofillPreferences.AutofillDefaultSourceFilter.MDBX -> AutofillScope.Mdbx(mdbxDatabaseId)
}
