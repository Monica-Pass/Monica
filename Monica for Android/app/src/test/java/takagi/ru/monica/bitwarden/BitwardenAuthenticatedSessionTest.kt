package takagi.ru.monica.bitwarden

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import takagi.ru.monica.bitwarden.api.BitwardenAuthenticatedSession

class BitwardenAuthenticatedSessionTest {
    private class Fixture {
        val token = AtomicReference("old-synthetic-token")
        val live = AtomicBoolean(true)
        val refreshes = AtomicInteger()
        var onRefresh: suspend () -> Unit = {}
        val session = BitwardenAuthenticatedSession(token.get(), token::get, { check(live.get()) }) {
            refreshes.incrementAndGet()
            onRefresh()
            check(live.get())
            token.set("new-synthetic-token")
            token.get()
        }
    }

    private fun <T> error(code: Int): Response<T> = Response.error(code, "synthetic rejection".toResponseBody())

    @Test fun concurrentRejectionsRotateRefreshTokenOnlyOnce(): Unit = runBlocking {
        val f = Fixture()
        val ready = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val results = (1..12).map {
            async {
                f.session.execute("Bearer old-synthetic-token") { auth ->
                    calls.incrementAndGet()
                    if (auth == "Bearer old-synthetic-token") {
                        if (ready.incrementAndGet() == 12) release.complete(Unit)
                        release.await()
                        error<String>(401)
                    } else Response.success("unchanged account data")
                }
            }
        }.awaitAll()
        assertEquals(1, f.refreshes.get())
        assertEquals(24, calls.get())
        assertTrue(results.all { it.body() == "unchanged account data" })
    }

    @Test fun secondUnauthorizedResponseIsReturnedWithoutThirdAttempt(): Unit = runBlocking {
        val f = Fixture(); var calls = 0
        val response = f.session.execute("Bearer old-synthetic-token") { calls++; error<String>(401) }
        assertEquals(401, response.code()); assertEquals(2, calls); assertEquals(1, f.refreshes.get())
    }

    @Test fun permissionsRateLimitsAndServerErrorsNeverRefresh(): Unit = runBlocking {
        for (code in listOf(400, 403, 404, 405, 429, 500, 503)) {
            val f = Fixture(); var calls = 0
            assertEquals(code, f.session.execute("Bearer old-synthetic-token") {
                calls++; error<String>(code)
            }.code())
            assertEquals(1, calls); assertEquals(0, f.refreshes.get())
        }
    }

    @Test fun lostWriteResponseIsNotReplayed(): Unit = runBlocking {
        val f = Fixture(); var writes = 0
        val error = runCatching {
            f.session.execute<String>("Bearer old-synthetic-token") {
                writes++; throw IOException("response lost after accepted write")
            }
        }.exceptionOrNull()
        assertTrue(error is IOException); assertEquals(1, writes); assertEquals(0, f.refreshes.get())
    }

    @Test fun nonReplayableBodyIsNotResubmittedEvenFor401(): Unit = runBlocking {
        val f = Fixture(); var calls = 0
        assertEquals(401, f.session.execute("Bearer old-synthetic-token", replayable = false) {
            calls++; error<String>(401)
        }.code())
        assertEquals(1, calls); assertEquals(0, f.refreshes.get())
    }

    @Test fun foreignBearerIsRejectedBeforeAnyNetworkCall(): Unit = runBlocking {
        val f = Fixture(); var calls = 0
        assertTrue(runCatching {
            f.session.execute("Bearer another-account-token") { calls++; Response.success("bad") }
        }.isFailure)
        assertEquals(0, calls); assertEquals(0, f.refreshes.get())
    }

    @Test fun earlierOperationUsesRotatedTokenWithoutExtraRefresh(): Unit = runBlocking {
        val f = Fixture()
        f.session.refreshRejected("old-synthetic-token")
        val seen = mutableListOf<String>()
        val response = f.session.execute("Bearer old-synthetic-token") {
            seen += it; Response.success("okay")
        }
        assertEquals("okay", response.body())
        assertEquals(listOf("Bearer new-synthetic-token"), seen)
        assertEquals(1, f.refreshes.get())
    }

    @Test fun lockDuringRefreshPreventsTheRetry(): Unit = runBlocking {
        val f = Fixture(); var calls = 0
        f.onRefresh = { f.live.set(false) }
        assertTrue(runCatching {
            f.session.execute("Bearer old-synthetic-token") { calls++; error<String>(401) }
        }.isFailure)
        assertEquals(1, calls); assertEquals("old-synthetic-token", f.token.get())
    }

    @Test fun lateSuccessAfterSessionRetirementCannotPublish(): Unit = runBlocking {
        val f = Fixture()
        assertTrue(runCatching {
            f.session.execute("Bearer old-synthetic-token") {
                f.session.retire(); Response.success("sensitive result")
            }
        }.isFailure)
    }

    @Test fun cancellationDuringRefreshDoesNotScheduleRetry(): Unit = runBlocking {
        val f = Fixture(); var calls = 0
        val started = CompletableDeferred<Unit>()
        f.onRefresh = { started.complete(Unit); CompletableDeferred<Unit>().await() }
        val task = launch {
            f.session.execute("Bearer old-synthetic-token") { calls++; error<String>(401) }
        }
        started.await(); task.cancelAndJoin()
        assertEquals(1, calls); assertEquals("old-synthetic-token", f.token.get())
    }

    @Test fun unavailableRefreshRetainsOriginalUnauthorizedResponse(): Unit = runBlocking {
        var calls = 0
        val session = BitwardenAuthenticatedSession("old", { "old" }, {}, { null })
        assertEquals(401, session.execute("Bearer old") { calls++; error<String>(401) }.code())
        assertEquals(1, calls)
    }

    @Test fun reusedTokenTextDoesNotReviveRetiredSession(): Unit = runBlocking {
        val f = Fixture(); f.session.retire(); var calls = 0
        assertTrue(runCatching {
            f.session.execute("Bearer old-synthetic-token") { calls++; Response.success("bad") }
        }.isFailure)
        assertEquals(0, calls)
        assertFalse(f.session.toString().contains("old-synthetic-token"))
    }
}
