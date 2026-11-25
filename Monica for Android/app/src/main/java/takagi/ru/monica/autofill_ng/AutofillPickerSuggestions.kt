package takagi.ru.monica.autofill_ng

import kotlinx.coroutines.flow.first
import takagi.ru.monica.data.PasswordEntry

/** Explicit callback/structured results stay authoritative. Login pickers may refresh after auth. */
internal object AutofillPickerSuggestions {
    suspend fun load(
        entries: List<PasswordEntry>,
        args: AutofillPickerActivityV2.Args,
        preferences: AutofillPreferences,
        appName: String?,
        urlRules: Map<Long, List<takagi.ru.monica.data.model.PasswordUrlRules.Rule>> = emptyMap(),
        activeScope: AutofillScope? = null,
    ): List<PasswordEntry> {
        val scope = activeScope ?: if (args.suggestedPasswordIds == null || args.refreshLoginSuggestions)
            preferences.v2DefaultScope.first() else null
        val scoped = if (scope == null) entries else entries.filter { it.matchesAutofillScope(scope) }
        val supplied = args.suggestedPasswordIds?.let { ids ->
            val byId = scoped.associateBy { it.id }
            ids.distinct().mapNotNull(byId::get)
        }
        if (!args.refreshLoginSuggestions && supplied != null) return supplied
        val packageName = args.applicationId.orEmpty()
        if (packageName.isBlank() && args.webDomain.isNullOrBlank()) return emptyList()
        val uri = AutofillSuggestionPolicy.resolveUriStrategyConfig(
            preferences.domainMatchStrategy.first(), preferences.isBitwardenSubdomainMatchEnabled.first())
        val matcher = BitwardenLikeAutofillMatcherNg()
        val matched = try {
            matcher.match(scoped, packageName, args.webDomain, appName,
                BitwardenLikeAutofillMatcherNg.Config(
                    strictOnly = preferences.isBitwardenStrictModeEnabled.first(),
                    allowSubdomainMatch = uri.allowSubdomainMatch,
                    allowBaseDomainMatch = uri.allowBaseDomainMatch,
                    exactDomainOnly = uri.exactDomainOnly,
                    allowPackageMatch = AutofillRequestContextPolicy.allowPackageMatching(
                        packageName, args.webDomain, isWebView = args.isWebView),
                    allowExactPackageMatch = AutofillRequestContextPolicy.allowExactPackageMatching(
                        packageName, args.webDomain, args.isWebView, args.webScheme),
                    maxSuggestions = Int.MAX_VALUE,
                    defaultUriMatchDisabled = uri.disableMatch,
                ), urlRules = urlRules)
        } finally { matcher.clear() }
        return (supplied.orEmpty() + matched).distinctBy { it.id }
    }
}
