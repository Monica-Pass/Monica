package takagi.ru.monica.service

import takagi.ru.monica.autofill_ng.AutofillHostPolicy

object BrowserAutofillContextStore {
    private const val MAX_DOMAIN_AGE_MS = 60_000L

    data class Snapshot(
        val packageName: String,
        val domain: String,
        val updatedAt: Long,
    )

    @Volatile
    private var latestSnapshot: Snapshot? = null

    @Synchronized
    fun update(packageName: String, rawUrlOrDomain: String) {
        val normalizedPackage = packageName.trim()
        val normalizedDomain = normalizeDomain(rawUrlOrDomain)
        if (normalizedDomain == null) {
            // A newly rejected address must not keep the same browser's older
            // origin eligible for autofill during the cache's freshness window.
            if (latestSnapshot?.packageName?.equals(normalizedPackage, ignoreCase = true) == true) {
                latestSnapshot = null
            }
            return
        }
        latestSnapshot = Snapshot(
            packageName = normalizedPackage,
            domain = normalizedDomain,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun getRecentDomain(packageName: String, maxAgeMs: Long = MAX_DOMAIN_AGE_MS): String? {
        val snapshot = latestSnapshot ?: return null
        if (!snapshot.packageName.equals(packageName.trim(), ignoreCase = true)) return null
        if (System.currentTimeMillis() - snapshot.updatedAt > maxAgeMs) return null
        return snapshot.domain
    }

    private fun normalizeDomain(rawValue: String): String? = AutofillHostPolicy.host(rawValue)
}
