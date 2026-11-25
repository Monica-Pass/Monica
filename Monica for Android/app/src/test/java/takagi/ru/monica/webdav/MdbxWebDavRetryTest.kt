package takagi.ru.monica.webdav

import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MdbxWebDavRetryTest {
    @Test fun oneSyncSharesItsRetryBudgetAcrossFiles() = runBlocking<Unit> {
        val waits = mutableListOf<Long>()
        val retry = MdbxWebDavRetry("fixture", { 0L }, { waits += it })
        repeat(2) {
            var attempts = 0
            retry.run { if (attempts++ == 0) throw RateLimitedIOException("limited", 1000) }
        }
        var attempts = 0
        val error = runCatching { retry.run { attempts++; throw RateLimitedIOException("limited", 1000) } }.exceptionOrNull()
        assertTrue(error is RateLimitedIOException)
        assertEquals(1, attempts)
        assertEquals(listOf(1000L, 1000L), waits)
    }

    @Test fun cancellationBeforeTheNextOperationDoesNotSendARequest() = runBlocking<Unit> {
        val job = kotlinx.coroutines.Job()
        job.cancel()
        var requests = 0
        val error = runCatching { kotlinx.coroutines.withContext(job) {
            MdbxWebDavRetry("fixture").run { requests++ }
        } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(0, requests)
    }

    @Test fun temporary503ResumesOnlyTheFailedOperation() = runBlocking<Unit> {
        var requests = 0
        val waits = mutableListOf<Long>()
        val retry = MdbxWebDavRetry("fixture", { 1500L }, { waits += it })
        val result = retry.run {
            requests++
            if (requests == 1) throw SardineException("Service Unavailable", 503, "fixture")
            "complete"
        }
        assertEquals("complete", result)
        assertEquals(2, requests)
        assertEquals(listOf(1500L), waits)
    }
    @Test fun permanentRateLimitHasBoundedAttempts() = runBlocking<Unit> {
        var requests = 0
        val waits = mutableListOf<Long>()
        val failure = runCatching { MdbxWebDavRetry("fixture", { 0 }, { waits += it }).run {
            requests++
            throw RateLimitedIOException("limited", 2000)
        } }.exceptionOrNull()
        assertTrue(failure is RateLimitedIOException)
        assertEquals(3, requests)
        assertEquals(listOf(2000L, 2000L), waits)
    }
    @Test fun longServerDelayIsNeverShortened() = runBlocking<Unit> {
        var requests = 0
        val failure = runCatching { MdbxWebDavRetry("fixture", { 60_000 }, { fail("must not wait") }).run {
            requests++
            throw RateLimitedIOException("limited", 60_000)
        } }.exceptionOrNull()
        assertTrue(failure is RateLimitedIOException)
        assertEquals(1, requests)
    }
    @Test fun authConflictsAndCancellationAreNotRetried() = runBlocking<Unit> {
        for (error in listOf(SardineException("Auth", 401, "fixture"),
            SardineException("Conflict", 412, "fixture"), CancellationException("cancelled"))) {
            var requests = 0
            assertSame(error, runCatching { MdbxWebDavRetry("fixture", { 0 }, { fail("must not wait") }).run {
                requests++
                throw error
            } }.exceptionOrNull())
            assertEquals(1, requests)
        }
    }
}
