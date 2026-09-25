package takagi.ru.monica.utils

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.webdav.WebDavBackoffState
import takagi.ru.monica.webdav.WebDavErrorClassifier
import takagi.ru.monica.webdav.WebDavErrorKind

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebDavMdbxDirectoryTest {
    @After fun resetBackoff() { WebDavBackoffState.resetForTest() }

    @Test
    fun existingParentsAreNotRecheckedForEverySegment() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(405)
                "PROPFIND" -> directory(request.path!!)
                else -> MockResponse().setResponseCode(400)
            }
        }
        server.start()
        try {
            val source = source(server)
            val path = "vault.mdbx.sync/streams/device/generation/segments"
            source.ensureDirectoryPath(path)
            val firstCount = server.requestCount
            source.ensureDirectoryPath(path)
            assertEquals("Repeated segment uploads must reuse verified parents", firstCount, server.requestCount)
            assertTrue("Existing directories should need at most one request per level", firstCount <= 5)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unavailableDirectoryRetainsServiceFailureInsteadOfBecomingGenericCreateError() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        server.start()
        try {
            val failure = runCatching { source(server).ensureDirectoryPath("vault.sync/streams") }.exceptionOrNull()
            assertEquals(WebDavErrorKind.RateLimited, WebDavErrorClassifier.classify(failure).kind)
            assertEquals("A service failure must not trigger speculative directory writes", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun source(server: MockWebServer) = WebDavMdbxFileSource(
        server.url("/dav").toString(), "fixture-user", "fixture-password",
        StringResolver { id, _ -> "resource:$id" }
    )

    private fun directory(path: String) = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody("""<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>${path.trimEnd('/')}/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
        """.trimIndent())
}
