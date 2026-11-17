package takagi.ru.monica.autofill_ng

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.autofill_ng.core.AutofillLogger

/** Shared settings policy for framework and manually opened credential suggestions. */
internal object AutofillSuggestionPolicy {
    data class UriStrategyConfig(
        val allowSubdomainMatch: Boolean,
        val allowBaseDomainMatch: Boolean,
        val exactDomainOnly: Boolean,
        val disableMatch: Boolean = false,
    )

    fun resolveUriStrategyConfig(
        strategy: DomainMatchStrategy,
        allowSubdomainToggle: Boolean,
    ): UriStrategyConfig {
        return when (strategy) {
            DomainMatchStrategy.BASE_DOMAIN -> UriStrategyConfig(
                allowSubdomainMatch = allowSubdomainToggle,
                allowBaseDomainMatch = true,
                exactDomainOnly = false,
            )

            DomainMatchStrategy.DOMAIN -> UriStrategyConfig(
                allowSubdomainMatch = allowSubdomainToggle,
                allowBaseDomainMatch = false,
                exactDomainOnly = false,
            )

            DomainMatchStrategy.EXACT_MATCH -> UriStrategyConfig(
                allowSubdomainMatch = false,
                allowBaseDomainMatch = false,
                exactDomainOnly = true,
            )

            DomainMatchStrategy.NEVER -> UriStrategyConfig(
                allowSubdomainMatch = false,
                allowBaseDomainMatch = false,
                exactDomainOnly = false,
                disableMatch = true,
            )

            DomainMatchStrategy.STARTS_WITH,
            DomainMatchStrategy.REGEX -> {
                AutofillLogger.w(
                    "AF",
                    "URI strategy $strategy is not natively supported by NG matcher; fallback to BASE_DOMAIN",
                )
                UriStrategyConfig(
                    allowSubdomainMatch = allowSubdomainToggle,
                    allowBaseDomainMatch = true,
                    exactDomainOnly = false,
                )
            }
        }
    }

    fun applyDefaultSourceFilter(
        entries: List<PasswordEntry>,
        sourceFilter: AutofillPreferences.AutofillDefaultSourceFilter,
        keepassDatabaseId: Long?,
        bitwardenVaultId: Long?,
        mdbxDatabaseId: Long? = null,
    ): List<PasswordEntry> {
        val scope = sourceFilter.toAutofillScope(keepassDatabaseId, bitwardenVaultId, mdbxDatabaseId)
        return entries.filter { it.matchesAutofillScope(scope) }
    }

}
