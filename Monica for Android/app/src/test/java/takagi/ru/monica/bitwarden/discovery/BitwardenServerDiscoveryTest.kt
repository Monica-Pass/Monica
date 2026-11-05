package takagi.ru.monica.bitwarden.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BitwardenServerDiscoveryTest {
    @Test fun onlyExplicitInvocationQueriesTwoNamesWithoutTheEmailLocalPart() = runTest {
        val queried = mutableListOf<String>()
        val discovery = BitwardenServerDiscovery { name -> queried += name; BitwardenTxtLookup.NoRecords }
        assertTrue(queried.isEmpty())
        val domain = requireNotNull(BitwardenServerDiscovery.canonicalEmailDomain("private-local-part@BÜCHER.Example"))
        assertEquals("xn--bcher-kva.example", domain)
        discovery.discover(domain)
        assertEquals(setOf("_bitwarden.$domain", "_vaultwarden.$domain"), queried.toSet())
        assertTrue(queried.none { it.contains("private") || it.contains('@') })
    }

    @Test fun invalidEmailOrDomainNeverTriggersResolver() = runTest {
        val discovery = BitwardenServerDiscovery { error("Must not query an invalid domain") }
        for (raw in listOf("", "person@example.com", "https://example.com", "example.com/path", " example.com", "a..com", "a".repeat(64) + ".com")) {
            assertEquals(raw, BitwardenDiscoveryStatus.INVALID_DOMAIN, discovery.discover(raw).status)
        }
        for (email in listOf("@example.com", "a@b@example.com", "a b@example.com", "a@example.com/path", "a".repeat(65) + "@example.com")) {
            assertNull(email, BitwardenServerDiscovery.canonicalEmailDomain(email))
        }
    }

    @Test fun usesSharedHttpsEndpointPolicyAndPreservesPortsPathsAndIpCandidates() = runTest {
        val values = listOf(" https://Vault.Example.com:8443/Team ", "https://192.168.1.8:444/Vault/", "https://[2001:db8::1]:8443/Path/",
            "http://vault.example.com/", "https://user:secret@example.com/", "https://example.com/?token=secret",
            "https://example.com/#fragment", "https://example.com/a/../b", "https://example.com/a%2fb")
        val result = discover(values)
        assertEquals(BitwardenDiscoveryStatus.FOUND, result.status)
        assertEquals(listOf("https://vault.example.com:8443/Team/", "https://192.168.1.8:444/Vault/", "https://[2001:db8::1]:8443/Path/"), result.candidates.map { it.url.toString() })
        assertEquals(6, result.rejectedRecords)
        assertTrue(result.candidates.all { it.requiresConfirmation })
    }

    @Test fun deduplicatesCanonicalUrlsWhileKeepingBothSourcesAndEightCandidateLimit() = runTest {
        val first = listOf("https://VAULT.example.com", "https://vault.example.com:443/") + (1..8).map { "https://v$it.example.com/" }
        val result = BitwardenServerDiscovery { name -> BitwardenTxtLookup.Records(if (name.startsWith("_bitwarden")) first else listOf("https://vault.example.com/")) }.discover("example.com")
        assertEquals(8, result.candidates.size)
        assertTrue(result.candidateLimitReached)
        assertEquals(listOf("_bitwarden.example.com", "_vaultwarden.example.com"), result.candidates.first().sourceNames)
        assertThrows(UnsupportedOperationException::class.java) { (result.candidates as MutableList<*>).clear() }
    }

    @Test fun usesPslAndNeverTreatsNullDomainAsSameOwnership() = runTest {
        val result = discover(listOf("https://vault.example.co.uk/", "https://vault.other.co.uk/", "https://co.uk/", "https://localhost/", "https://192.168.1.8/"), domain = "login.example.co.uk")
        assertEquals(listOf(BitwardenDiscoveryDomainRelation.SAME_REGISTRABLE_DOMAIN,
            BitwardenDiscoveryDomainRelation.DIFFERENT_REGISTRABLE_DOMAIN,
            BitwardenDiscoveryDomainRelation.UNDETERMINED, BitwardenDiscoveryDomainRelation.UNDETERMINED,
            BitwardenDiscoveryDomainRelation.UNDETERMINED), result.candidates.map { it.domainRelation })
        assertFalse(result.candidates.first().requiresDestinationReview)
        assertTrue(result.candidates.drop(1).all { it.requiresDestinationReview })
        val unknown = discover(listOf("https://localhost/"), domain = "localhost")
        assertTrue(unknown.candidates.single().requiresDestinationReview)
    }

    @Test fun keepsOneSuccessfulLabelAndReportsTheOtherFailureHonestly() = runTest {
        val result = BitwardenServerDiscovery { name ->
            if (name.startsWith("_bitwarden")) BitwardenTxtLookup.Failure(BitwardenTxtFailure.TRANSPORT)
            else BitwardenTxtLookup.Records(listOf("https://vault.example.com/"))
        }.discover("example.com")
        assertEquals(BitwardenDiscoveryStatus.FOUND, result.status)
        assertEquals(listOf(BitwardenDiscoveryLookupStatus.ERROR, BitwardenDiscoveryLookupStatus.RECORDS), result.lookups.map { it.status })
        assertEquals(BitwardenTxtFailure.TRANSPORT, result.lookups.first().failure)
    }

    @Test fun distinguishesUnsupportedNoRecordsAndInvalidCandidates() = runTest {
        assertEquals(BitwardenDiscoveryStatus.UNSUPPORTED, BitwardenServerDiscovery { BitwardenTxtLookup.Unsupported }.discover("example.com").status)
        assertEquals(BitwardenDiscoveryStatus.NO_RECORDS, BitwardenServerDiscovery { BitwardenTxtLookup.NoRecords }.discover("example.com").status)
        assertEquals(BitwardenDiscoveryStatus.NO_VALID_CANDIDATES, discover(listOf("not a URL", "http://example.com/")).status)
        val failed = BitwardenServerDiscovery { throw IllegalStateException("private-sentinel") }.discover("example.com")
        assertEquals(BitwardenDiscoveryStatus.LOOKUP_FAILED, failed.status)
        assertFalse(failed.toString().contains("private-sentinel"))
        assertTrue(failed.lookups.all { it.failure == BitwardenTxtFailure.TRANSPORT })
    }

    @Test fun totalDeadlineCancelsBothParallelQueriesAfterFourSeconds() = runTest {
        val started = mutableSetOf<String>()
        val cancelled = mutableSetOf<String>()
        val start = currentTime
        val result = BitwardenServerDiscovery { name ->
            started += name
            try { awaitCancellation() } finally { cancelled += name }
        }.discover("example.com")
        assertEquals(4_000L, currentTime - start)
        assertEquals(2, started.size)
        assertEquals(started, cancelled)
        assertEquals(BitwardenDiscoveryStatus.TIMED_OUT, result.status)
        assertTrue(result.lookups.all { it.status == BitwardenDiscoveryLookupStatus.TIMED_OUT })
    }

    @Test fun deadlineRetainsCompletedLabelAndMarksOnlyPendingLabelTimedOut() = runTest {
        var cancelled = false
        val result = BitwardenServerDiscovery { name ->
            if (name.startsWith("_bitwarden")) BitwardenTxtLookup.Records(listOf("https://vault.example.com/"))
            else try { awaitCancellation() } finally { cancelled = true }
        }.discover("example.com")
        assertTrue(cancelled)
        assertEquals(BitwardenDiscoveryStatus.FOUND, result.status)
        assertEquals(listOf(BitwardenDiscoveryLookupStatus.RECORDS, BitwardenDiscoveryLookupStatus.TIMED_OUT), result.lookups.map { it.status })
    }

    @Test fun callerCancellationPropagatesAndDoesNotPublishAnEmptySuccess() = runTest {
        var cancelled = 0
        var published = false
        val discovery = BitwardenServerDiscovery { try { awaitCancellation() } finally { cancelled++ } }
        val pending = async { discovery.discover("example.com").also { published = true } }
        runCurrent()
        pending.cancelAndJoin()
        assertEquals(2, cancelled)
        assertFalse(published)
        assertTrue(pending.isCancelled)
        try { pending.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }

    @Test fun boundsInjectedResolverResponsesAndRedactsResultStringification() = runTest {
        val excessive = BitwardenServerDiscovery { BitwardenTxtLookup.Records(List(17) { "https://vault.example/" }) }.discover("example.com")
        assertEquals(BitwardenDiscoveryStatus.LOOKUP_FAILED, excessive.status)
        assertTrue(excessive.lookups.all { it.failure == BitwardenTxtFailure.RESPONSE_LIMIT })
        val bytes = BitwardenServerDiscovery { BitwardenTxtLookup.Records(listOf("a".repeat(8193))) }.discover("example.com")
        assertEquals(BitwardenDiscoveryStatus.LOOKUP_FAILED, bytes.status)
        val result = discover(listOf("https://sentinel.example/private-path/"))
        assertFalse(result.toString().contains("sentinel"))
        assertFalse(result.candidates.single().toString().contains("private-path"))
    }

    private suspend fun discover(values: List<String>, domain: String = "example.com") =
        BitwardenServerDiscovery { name -> if (name.startsWith("_bitwarden")) BitwardenTxtLookup.Records(values) else BitwardenTxtLookup.NoRecords }.discover(domain)
}
