package takagi.ru.monica.webdav

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitInterceptorTest {
    @Test
    fun serviceUnavailableWithoutRetryAfterBlocksImmediateRequestsAndCanRecover() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        var now = 10_000L
        val backoff = TestBackoff()
        val client = OkHttpClient.Builder()
            .addInterceptor(RateLimitInterceptor(backoff) { now })
            .build()
        val request = Request.Builder().url(server.url("/dav/vault.sync/streams")).build()
        try {
            client.newCall(request).execute().use { assertEquals(503, it.code) }
            val failure = runCatching { client.newCall(request).execute().close() }.exceptionOrNull()
            assertTrue("503 must trigger cooldown even without Retry-After", failure is RateLimitedIOException)
            assertEquals(1, server.requestCount)
            now += 1_001L
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun serviceRetryAfterIsRespected() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "12"))
        server.start()
        val backoff = TestBackoff()
        val client = OkHttpClient.Builder().addInterceptor(RateLimitInterceptor(backoff) { 20_000L }).build()
        try {
            client.newCall(Request.Builder().url(server.url("/dav")).build()).execute().close()
            assertEquals(12_000L, backoff.suggestedWaitMillis(server.hostName, 20_000L))
        } finally {
            server.shutdown()
        }
    }

    private class TestBackoff : WebDavBackoffStateApi {
        private var until = 0L
        override fun shouldBlock(host: String, now: Long) = now < until
        override fun suggestedWaitMillis(host: String, now: Long) = (until - now).coerceAtLeast(0L)
        override fun recordRateLimit(host: String, retryAfterMillis: Long?, now: Long) {
            until = now + (retryAfterMillis ?: 1_000L)
        }
        override fun recordSuccess(host: String) { until = 0L }
    }
}
