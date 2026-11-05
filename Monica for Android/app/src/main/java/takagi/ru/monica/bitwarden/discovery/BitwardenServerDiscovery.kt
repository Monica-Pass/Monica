package takagi.ru.monica.bitwarden.discovery

import java.net.IDN
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentField
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver

internal enum class BitwardenDiscoveryStatus {
    FOUND, NO_RECORDS, NO_VALID_CANDIDATES, LOOKUP_FAILED, UNSUPPORTED, TIMED_OUT, INVALID_DOMAIN,
}
internal enum class BitwardenDiscoveryLookupStatus { RECORDS, NO_RECORDS, ERROR, UNSUPPORTED, TIMED_OUT }
internal enum class BitwardenDiscoveryDomainRelation { SAME_REGISTRABLE_DOMAIN, DIFFERENT_REGISTRABLE_DOMAIN, UNDETERMINED }

internal class BitwardenDiscoveryCandidate internal constructor(
    val url: HttpUrl,
    sourceNames: List<String>,
    val domainRelation: BitwardenDiscoveryDomainRelation,
) {
    val sourceNames: List<String> = Collections.unmodifiableList(sourceNames.toList())
    val requiresDestinationReview: Boolean get() = domainRelation != BitwardenDiscoveryDomainRelation.SAME_REGISTRABLE_DOMAIN
    val requiresConfirmation: Boolean get() = true
    override fun toString() = "BitwardenDiscoveryCandidate(relation=$domainRelation)"
}

internal class BitwardenDiscoveryLookup internal constructor(
    val name: String,
    val status: BitwardenDiscoveryLookupStatus,
    val failure: BitwardenTxtFailure? = null,
) {
    override fun toString() = "BitwardenDiscoveryLookup(status=$status, failure=$failure)"
}

internal class BitwardenDiscoveryResult internal constructor(
    val domain: String?,
    val status: BitwardenDiscoveryStatus,
    candidates: List<BitwardenDiscoveryCandidate> = emptyList(),
    lookups: List<BitwardenDiscoveryLookup> = emptyList(),
    val rejectedRecords: Int = 0,
    val candidateLimitReached: Boolean = false,
) {
    val candidates: List<BitwardenDiscoveryCandidate> = Collections.unmodifiableList(candidates.toList())
    val lookups: List<BitwardenDiscoveryLookup> = Collections.unmodifiableList(lookups.toList())
    override fun toString() = "BitwardenDiscoveryResult(status=$status, candidates=${candidates.size})"
}

/** Call only for an explicit discovery action. The caller owns draft/email revision and late-result checks. */
internal class BitwardenServerDiscovery(private val resolver: BitwardenDnsTxtResolver) {
    suspend fun discover(domain: String): BitwardenDiscoveryResult {
        currentCoroutineContext().ensureActive()
        val canonical = canonicalDomain(domain)
            ?: return BitwardenDiscoveryResult(null, BitwardenDiscoveryStatus.INVALID_DOMAIN)
        val names = listOf("_bitwarden.$canonical", "_vaultwarden.$canonical")
        val completed = ConcurrentHashMap<String, BitwardenTxtLookup>()
        withTimeoutOrNull(TOTAL_TIMEOUT_MILLIS) {
            supervisorScope {
                names.map { name -> async {
                    val lookup = try {
                        bounded(resolver.queryTxt(name))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        BitwardenTxtLookup.Failure(BitwardenTxtFailure.TRANSPORT)
                    }
                    currentCoroutineContext().ensureActive()
                    completed[name] = lookup
                } }.awaitAll()
            }
        }
        currentCoroutineContext().ensureActive()
        val lookups = names.map { name ->
            when (val answer = completed[name]) {
                is BitwardenTxtLookup.Records -> BitwardenDiscoveryLookup(name, BitwardenDiscoveryLookupStatus.RECORDS)
                BitwardenTxtLookup.NoRecords -> BitwardenDiscoveryLookup(name, BitwardenDiscoveryLookupStatus.NO_RECORDS)
                BitwardenTxtLookup.Unsupported -> BitwardenDiscoveryLookup(name, BitwardenDiscoveryLookupStatus.UNSUPPORTED)
                is BitwardenTxtLookup.Failure -> BitwardenDiscoveryLookup(name, BitwardenDiscoveryLookupStatus.ERROR, answer.reason)
                null -> BitwardenDiscoveryLookup(name, BitwardenDiscoveryLookupStatus.TIMED_OUT)
            }
        }
        val unique = linkedMapOf<HttpUrl, MutableList<String>>()
        var rejected = 0
        names.forEach { name ->
            (completed[name] as? BitwardenTxtLookup.Records)?.records?.forEach { record ->
                val url = BitwardenEnvironmentResolver.validateEndpoint(record.trim(), BitwardenEnvironmentField.BASE, requireHttps = true).value
                if (url == null) rejected++ else unique.getOrPut(url) { mutableListOf() }.let { if (name !in it) it.add(name) }
            }
        }
        val sourceDomain = "https://$canonical/".toHttpUrlOrNull()?.topPrivateDomain()
        val candidates = unique.entries.take(MAX_CANDIDATES).map { (url, sources) ->
            val destinationDomain = url.topPrivateDomain()
            val relation = when {
                sourceDomain == null || destinationDomain == null -> BitwardenDiscoveryDomainRelation.UNDETERMINED
                sourceDomain == destinationDomain -> BitwardenDiscoveryDomainRelation.SAME_REGISTRABLE_DOMAIN
                else -> BitwardenDiscoveryDomainRelation.DIFFERENT_REGISTRABLE_DOMAIN
            }
            BitwardenDiscoveryCandidate(url, sources, relation)
        }
        val status = when {
            candidates.isNotEmpty() -> BitwardenDiscoveryStatus.FOUND
            lookups.any { it.status == BitwardenDiscoveryLookupStatus.TIMED_OUT } -> BitwardenDiscoveryStatus.TIMED_OUT
            lookups.any { it.status == BitwardenDiscoveryLookupStatus.ERROR } -> BitwardenDiscoveryStatus.LOOKUP_FAILED
            lookups.any { it.status == BitwardenDiscoveryLookupStatus.UNSUPPORTED } -> BitwardenDiscoveryStatus.UNSUPPORTED
            lookups.any { it.status == BitwardenDiscoveryLookupStatus.RECORDS } -> BitwardenDiscoveryStatus.NO_VALID_CANDIDATES
            else -> BitwardenDiscoveryStatus.NO_RECORDS
        }
        currentCoroutineContext().ensureActive()
        return BitwardenDiscoveryResult(canonical, status, candidates, lookups, rejected, unique.size > MAX_CANDIDATES)
    }

    private fun bounded(lookup: BitwardenTxtLookup): BitwardenTxtLookup {
        if (lookup !is BitwardenTxtLookup.Records) return lookup
        if (lookup.records.size > BitwardenDnsTxtParser.MAX_TXT_RECORDS || lookup.records.any { it.length > BitwardenDnsTxtParser.MAX_TEXT_BYTES }) {
            return BitwardenTxtLookup.Failure(BitwardenTxtFailure.RESPONSE_LIMIT)
        }
        if (lookup.records.sumOf { it.toByteArray(Charsets.UTF_8).size } > BitwardenDnsTxtParser.MAX_TEXT_BYTES) {
            return BitwardenTxtLookup.Failure(BitwardenTxtFailure.RESPONSE_LIMIT)
        }
        return lookup
    }

    companion object {
        const val TOTAL_TIMEOUT_MILLIS = 4_000L
        const val MAX_CANDIDATES = 8

        /** Local-only extraction; the local part is never passed to a resolver. */
        fun canonicalEmailDomain(email: String): String? {
            if (email.length > 320 || email.any { it.isWhitespace() || it.isISOControl() }) return null
            val separator = email.indexOf('@')
            if (separator !in 1..64 || separator != email.lastIndexOf('@')) return null
            return canonicalDomain(email.substring(separator + 1))
        }

        fun canonicalDomain(raw: String): String? {
            if (raw.isEmpty() || raw.length > 253 || raw.any { it.isWhitespace() || it.isISOControl() || it in "@:/\\[]?#" }) return null
            val ascii = try { IDN.toASCII(raw.removeSuffix("."), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }
                catch (_: IllegalArgumentException) { return null }
            return ascii.takeIf { BitwardenDnsTxtParser.canonicalQueryName("_vaultwarden.$it") != null }
        }
    }
}
