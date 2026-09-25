package takagi.ru.monica.autofill_ng

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.rustcore.RustAutofillCore
import java.net.URL
import java.util.Locale

/**
 * Bitwarden-style matcher:
 * - Prefer exact package/domain matches.
 * - Keep matching deterministic and conservative to reduce false positives.
 */
class BitwardenLikeAutofillMatcherNg internal constructor(
    private val useNativeIndex: Boolean = true,
    private val cacheMetadata: Boolean = true,
) {
    private class Metadata(entry: PasswordEntry, val row: RustAutofillCore.Row) {
        val id = entry.id
        val title = entry.title
        val appName = entry.appName
        val website = entry.website
        val packageName = entry.appPackageName
        fun matches(entry: PasswordEntry) = id == entry.id && title == entry.title && appName == entry.appName &&
            website == entry.website && packageName == entry.appPackageName
    }

    private data class IndexSnapshot(val metadata: List<Metadata>, val nativeHandle: Long = 0L)

    private val cacheLock = Any()
    private var snapshot = IndexSnapshot(emptyList())
    private var generation = 0L
    internal var metadataBuildCount = 0
        private set
    internal var nativeQueryCount = 0
        private set

    fun clear() {
        // Clearing must not wait for a large, in-flight match on the UI thread.
        val previous = synchronized(cacheLock) {
            generation++
            snapshot.also { snapshot = IndexSnapshot(emptyList()) }
        }
        RustAutofillCore.close(previous.nativeHandle)
    }

    private fun prepare(entry: PasswordEntry): Metadata {
        metadataBuildCount++
        val packages = linkedSetOf<String>().apply {
            addAll(extractNormalizedPackages(entry.appPackageName))
            extractWebsiteTokens(entry.website).mapNotNull(::extractAndroidAppPackage).forEach(::add)
        }
        val hosts = extractNormalizedHosts(entry.website)
        return Metadata(
            entry,
            RustAutofillCore.Row(
                packages, hosts, hosts.map(::extractBaseDomain).toSet(),
                setOf(normalizeLabel(entry.title), normalizeLabel(entry.appName)),
            ),
        )
    }

    private fun updateMetadata(entries: List<PasswordEntry>): IndexSnapshot {
        val (previous, revision) = synchronized(cacheLock) { snapshot to generation }
        if (entries.size == previous.metadata.size &&
            entries.indices.all { previous.metadata[it].matches(entries[it]) }
        ) return previous

        // Room can reorder all rows after an edit. Reuse parsed values by identity,
        // but rebuild native positions so results always address the current list.
        val previousById = previous.metadata.associateBy { it.id }
        val next = ArrayList<Metadata>(entries.size)
        entries.forEachIndexed { index, entry ->
            val cached = previous.metadata.getOrNull(index)?.takeIf { it.matches(entry) }
                ?: previousById[entry.id]?.takeIf { it.matches(entry) }
            next.add(cached ?: prepare(entry))
        }
        val handle = if (useNativeIndex && entries.size >= 256) {
            RustAutofillCore.open(next.map { it.row }) ?: 0L
        } else 0L
        val updated = IndexSnapshot(next, handle)
        val retained = synchronized(cacheLock) {
            if (generation == revision) {
                snapshot = updated
                true
            } else false
        }
        RustAutofillCore.close(previous.nativeHandle)
        if (!retained) RustAutofillCore.close(handle)
        // A clear during preparation must not repopulate the service cache.
        return if (retained) updated else updated.copy(nativeHandle = 0L)
    }
    data class Config(
        val strictOnly: Boolean = true,
        val allowSubdomainMatch: Boolean = true,
        val allowBaseDomainMatch: Boolean = true,
        val exactDomainOnly: Boolean = false,
        val allowPackageMatch: Boolean = true,
        val maxSuggestions: Int = 20,
    )

    private enum class Reason {
        EXACT_PACKAGE,
        EXACT_DOMAIN,
        SUBDOMAIN,
        BASE_DOMAIN,
        PACKAGE_DOMAIN_COMBO,
        EXACT_APP_TITLE,
        PACKAGE_TOKEN_TITLE,
        HEURISTIC_FALLBACK,
    }

    private data class ScoredMatch(
        val entry: PasswordEntry,
        val score: Int,
        val reasons: Set<Reason>,
    )

    companion object {
        private val PACKAGE_TOKEN_STOPWORDS = setOf(
            "com", "android", "app", "net", "org", "io",
            "mobile", "client", "common", "ui", "view", "service",
            "lib", "utils", "core", "main", "v2", "prod", "dev",
            "test", "debug",
        )
    }

    @Synchronized
    fun match(
        entries: List<PasswordEntry>,
        packageName: String,
        webDomain: String?,
        appDisplayName: String? = null,
        config: Config = Config(),
    ): List<PasswordEntry> {
        if (entries.isEmpty()) {
            clear()
            return emptyList()
        }
        val prepared = if (cacheMetadata) updateMetadata(entries) else null

        val targetPackage = normalizePackageName(packageName)
        val targetPackageTokens = targetPackage
            ?.split('.')
            ?.filter { it.length >= 3 }
            ?.filter { it !in PACKAGE_TOKEN_STOPWORDS }
            ?.distinct()
            ?: emptyList()
        val targetHost = normalizeHost(webDomain)
        val preferDomainSignals = !targetHost.isNullOrBlank()
        val targetRoot = targetHost?.let(::extractBaseDomain)
        val targetAppDisplayName = normalizeLabel(appDisplayName)

        // Strict matching always requires a domain, package or exact label signal.
        // Non-strict fallback deliberately scans all rows to preserve substring heuristics.
        val nativeIndices = if (config.strictOnly && prepared != null && prepared.nativeHandle > 0) {
            RustAutofillCore.query(
                prepared.nativeHandle, targetPackage.orEmpty(), targetHost.orEmpty(),
                targetRoot.orEmpty(), targetAppDisplayName,
            ).also { if (it != null) nativeQueryCount++ }
        } else null
        val validIndices = nativeIndices?.takeIf { indices ->
            var last = -1
            indices.all { index -> (index in entries.indices && index > last).also { last = index } }
        }
        val candidateIndices: Iterable<Int> = validIndices?.asIterable() ?: entries.indices
        val candidates = candidateIndices.mapNotNull { index ->
            val entry = entries[index]
            if (entry.isKeyCredential()) return@mapNotNull null
            scoreEntry(
                entry = entry,
                prepared = prepared?.metadata?.get(index)?.row,
                targetPackage = targetPackage,
                targetPackageTokens = targetPackageTokens,
                targetHost = targetHost,
                preferDomainSignals = preferDomainSignals,
                targetRoot = targetRoot,
                targetAppDisplayName = targetAppDisplayName,
                config = config,
            )
        }

        if (candidates.isEmpty()) return emptyList()

        // Deduplicate by entry id and keep higher score.
        val bestByEntry = linkedMapOf<Long, ScoredMatch>()
        candidates.forEach { candidate ->
            val existing = bestByEntry[candidate.entry.id]
            if (existing == null || candidate.score > existing.score) {
                bestByEntry[candidate.entry.id] = candidate
            }
        }

        return bestByEntry.values
            .sortedWith(
                compareByDescending<ScoredMatch> { it.score }
                    .thenByDescending { it.entry.isFavorite }
                    .thenByDescending { it.entry.updatedAt.time },
            )
            .take(config.maxSuggestions.coerceAtLeast(1))
            .map { it.entry }
    }

    private fun scoreEntry(
        entry: PasswordEntry,
        prepared: RustAutofillCore.Row?,
        targetPackage: String?,
        targetPackageTokens: List<String>,
        targetHost: String?,
        preferDomainSignals: Boolean,
        targetRoot: String?,
        targetAppDisplayName: String?,
        config: Config,
    ): ScoredMatch? {
        val reasons = linkedSetOf<Reason>()
        var score = 0

        val entryPackages = prepared?.packages ?: linkedSetOf<String>().apply {
            extractNormalizedPackages(entry.appPackageName).forEach(::add)
            extractWebsiteTokens(entry.website)
                .mapNotNull(::extractAndroidAppPackage)
                .forEach(::add)
        }
        val entryHosts = prepared?.hosts ?: extractNormalizedHosts(entry.website)
        val entryRoots = prepared?.roots ?: entryHosts.map(::extractBaseDomain).toSet()

        if (!preferDomainSignals &&
            config.allowPackageMatch &&
            !targetPackage.isNullOrBlank() &&
            entryPackages.contains(targetPackage)
        ) {
            score += 120
            reasons += Reason.EXACT_PACKAGE
        }

        val entryLabels = prepared?.labels
            ?: setOf(normalizeLabel(entry.title), normalizeLabel(entry.appName))
        if (!preferDomainSignals &&
            config.allowPackageMatch &&
            !targetAppDisplayName.isNullOrBlank() &&
            targetAppDisplayName in entryLabels
        ) {
            score += 95
            reasons += Reason.EXACT_APP_TITLE
        }

        if (!preferDomainSignals && config.allowPackageMatch && targetPackageTokens.isNotEmpty()) {
            val tokenMatched = targetPackageTokens.any { token ->
                entryLabels.any { it.contains(token) }
            }
            if (tokenMatched) {
                score += 70
                reasons += Reason.PACKAGE_TOKEN_TITLE
            }
        }

        if (!targetHost.isNullOrBlank() && entryHosts.isNotEmpty()) {
            val hasExactDomain = entryHosts.any { it == targetHost }
            val hasSubdomainRelation = entryHosts.any { isSubdomainRelation(it, targetHost) }
            when {
                hasExactDomain -> {
                    score += 140
                    reasons += Reason.EXACT_DOMAIN
                }

                hasSubdomainRelation && !config.exactDomainOnly && config.allowSubdomainMatch -> {
                    score += 115
                    reasons += Reason.SUBDOMAIN
                }

                hasSubdomainRelation && !config.allowSubdomainMatch -> {
                    // Respect explicit subdomain toggle: do not fall through
                    // to base-domain scoring for strict parent/child host pairs.
                }

                entryRoots.isNotEmpty() &&
                    !targetRoot.isNullOrBlank() &&
                    !config.exactDomainOnly &&
                    config.allowBaseDomainMatch &&
                    targetRoot in entryRoots -> {
                    score += 100
                    reasons += Reason.BASE_DOMAIN
                }
            }
        }

        if (Reason.EXACT_PACKAGE in reasons &&
            (
                Reason.EXACT_DOMAIN in reasons ||
                    Reason.SUBDOMAIN in reasons ||
                    Reason.BASE_DOMAIN in reasons
                )
        ) {
            score += 30
            reasons += Reason.PACKAGE_DOMAIN_COMBO
        }

        if (!config.strictOnly && score == 0) {
            score = heuristicFallbackScore(
                entry = entry,
                targetPackage = if (preferDomainSignals || !config.allowPackageMatch) null else targetPackage,
                targetHost = targetHost,
            )
            if (score > 0) {
                reasons += Reason.HEURISTIC_FALLBACK
            }
        }

        if (score <= 0) return null

        if (config.strictOnly) {
            val hasStrongReason = reasons.any {
                it == Reason.EXACT_PACKAGE ||
                    it == Reason.EXACT_DOMAIN ||
                    it == Reason.SUBDOMAIN ||
                    it == Reason.BASE_DOMAIN ||
                    it == Reason.EXACT_APP_TITLE
            }
            if (!hasStrongReason) return null
        }

        return ScoredMatch(entry = entry, score = score, reasons = reasons)
    }

    private fun normalizeLabel(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.trim().lowercase(Locale.ROOT)
    }

    private fun heuristicFallbackScore(
        entry: PasswordEntry,
        targetPackage: String?,
        targetHost: String?,
    ): Int {
        val title = entry.title.lowercase(Locale.ROOT)
        val username = entry.username.lowercase(Locale.ROOT)
        val website = entry.website.lowercase(Locale.ROOT)
        val packageName = entry.appPackageName.lowercase(Locale.ROOT)

        val hostToken = targetHost
            ?.substringBefore('.')
            ?.takeIf { it.length >= 3 }
        val packageToken = targetPackage
            ?.substringAfterLast('.')
            ?.takeIf { it.length >= 3 }

        val token = hostToken ?: packageToken ?: return 0

        val haystack = "$title $username $website $packageName"
        return if (haystack.contains(token)) 55 else 0
    }

    private fun normalizePackageName(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.removePrefix("androidapp://")
            ?.removePrefix("android-app://")
            ?.substringBefore(':')
            ?.substringBefore('/')
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.takeIf { it.isNotBlank() }
        return normalized
    }

    private fun extractNormalizedPackages(value: String?): Set<String> {
        if (value.isNullOrBlank()) return emptySet()
        return value
            .split(',', ';', '|', ' ')
            .asSequence()
            .mapNotNull { normalizePackageName(it) }
            .filter { it.isNotBlank() }
            .toCollection(linkedSetOf())
    }

    private fun extractWebsiteTokens(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value
            .split(',', ';', '|', '\n', '\r', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun extractAndroidAppPackage(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim().lowercase(Locale.ROOT)
        if (!raw.startsWith("androidapp://") && !raw.startsWith("android-app://")) {
            return null
        }
        return normalizePackageName(raw)
    }

    private fun extractNormalizedHosts(value: String?): Set<String> =
        extractWebsiteTokens(value)
            .mapNotNull(::normalizeHost)
            .toCollection(linkedSetOf())

    private fun normalizeHost(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim().lowercase(Locale.ROOT)
        if (raw.startsWith("androidapp://")) return null

        val fullValue = if (raw.contains("://")) raw else "https://$raw"
        val parsedHost = runCatching { URL(fullValue).host }
            .getOrNull()
            ?.trim()
            ?.lowercase(Locale.ROOT)
        val fallbackHost = raw
            .substringBefore('/')
            .substringBefore(':')
            .trim()
            .lowercase(Locale.ROOT)
        val host = (parsedHost ?: fallbackHost)
            .removePrefix("www.")
            .trim('.')
            .takeIf { it.isNotBlank() }
        return host
    }

    private fun extractBaseDomain(host: String): String {
        val parts = host.split(".").filter { it.isNotBlank() }
        if (parts.size < 2) return host

        val twoPartTlds = setOf(
            "co.uk", "com.cn", "net.cn", "org.cn", "gov.cn", "ac.uk",
            "co.jp", "ne.jp", "or.jp", "com.au", "net.au", "org.au",
        )
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (parts.size >= 3 && lastTwo in twoPartTlds) {
            parts.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }

    private fun isSubdomainRelation(left: String, right: String): Boolean {
        if (left == right) return false
        return left.endsWith(".$right") || right.endsWith(".$left")
    }
}
