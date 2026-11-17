package takagi.ru.monica.autofill_ng

import kotlinx.coroutines.flow.first
import takagi.ru.monica.data.PasswordEntry

/** A framework-supplied list (including an empty one) is authoritative for its field scope. */
internal object AutofillPickerSuggestions {
    suspend fun load(
        entries: List<PasswordEntry>,
        args: AutofillPickerActivityV2.Args,
        preferences: AutofillPreferences,
        appName: String?,
        urlRules: Map<Long, List<takagi.ru.monica.data.model.PasswordUrlRules.Rule>> = emptyMap(),
        activeScope: AutofillScope? = null,
    ): List<PasswordEntry> {
        val scope = activeScope ?: if (args.suggestedPasswordIds == null) preferences.v2DefaultScope.first() else null
        val scoped = if (scope == null) entries else entries.filter { it.matchesAutofillScope(scope) }
        args.suggestedPasswordIds?.let { ids ->
            val byId = scoped.associateBy { it.id }
            return ids.distinct().mapNotNull(byId::get)
        }
        val packageName = args.applicationId.orEmpty()
        if (packageName.isBlank() && args.webDomain.isNullOrBlank()) return emptyList()
        val uri = AutofillSuggestionPolicy.resolveUriStrategyConfig(
            preferences.domainMatchStrategy.first(), preferences.isBitwardenSubdomainMatchEnabled.first())
        val matcher = BitwardenLikeAutofillMatcherNg()
        return try {
            matcher.match(scoped, packageName, args.webDomain, appName,
                BitwardenLikeAutofillMatcherNg.Config(
                    strictOnly = preferences.isBitwardenStrictModeEnabled.first(),
                    allowSubdomainMatch = uri.allowSubdomainMatch,
                    allowBaseDomainMatch = uri.allowBaseDomainMatch,
                    exactDomainOnly = uri.exactDomainOnly,
                    allowPackageMatch = AutofillRequestContextPolicy.allowPackageMatching(
                        packageName, args.webDomain, isWebView = false),
                    maxSuggestions = Int.MAX_VALUE,
                    defaultUriMatchDisabled = uri.disableMatch,
                ), urlRules = urlRules)
        } finally { matcher.clear() }
    }
}
