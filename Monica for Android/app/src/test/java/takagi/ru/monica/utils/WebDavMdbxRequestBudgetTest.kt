package takagi.ru.monica.utils

import android.app.Application
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.webdav.WebDavBackoffState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebDavMdbxRequestBudgetTest {
    @After fun resetBackoff() = WebDavBackoffState.resetForTest()

    @Test fun successfulUploadKeepsPreflightAndUsesPutEtagWithoutPostflight() = runBlocking {
        withServer { server, file, requests ->
            val result = source(server).writeFileFrom("segment", file, MdbxRemoteWriteMode.CREATE_ONLY)
            assertEquals(listOf("PROPFIND", "PUT"), requests.map { it.method })
            assertEquals("*", requests.last().getHeader("If-None-Match"))
            assertEquals("\"v1\"", result.versionToken)
            assertEquals(file.length(), result.sizeBytes)
        }
    }

    @Test fun confirmedUploadDoesNotFailBecauseAnUnnecessaryFollowupIsThrottled() = runBlocking {
        withServer(probeStatus = 429) { server, file, requests ->
            val saved = source(server).writeFileFrom("segment", file, MdbxRemoteWriteMode.CREATE_ONLY)
            assertEquals(file.length(), saved.sizeBytes)
            assertEquals(listOf("PROPFIND", "PUT"), requests.map { it.method })
        }
    }

    @Test fun retryAfterLostAcknowledgementVerifiesIdenticalContent() = runBlocking {
        withServer(putStatus = 412) { server, file, requests ->
            source(server).writeFileFrom("segment", file, MdbxRemoteWriteMode.CREATE_ONLY)
            assertEquals(listOf("PROPFIND", "GET"), requests.map { it.method })
        }
    }

    @Test fun sameNameDifferentContentIsNeverOverwritten() = runBlocking {
        withServer(existsInitially = true, content = "different") { server, file, requests ->
            assertNotNull(runCatching { source(server).writeFileFrom("segment", file) }.exceptionOrNull())
            assertEquals(0, requests.count { it.method == "PUT" })
        }
    }

    @Test fun staleEtagIsRejectedBeforePutEvenIfTheProviderIgnoresConditions() = runBlocking {
        withServer(existsInitially = true) { server, file, requests ->
            assertNotNull(runCatching {
                source(server).writeFileFrom("segment", file, MdbxRemoteWriteMode.IF_MATCH, "\"old\"")
            }.exceptionOrNull())
            assertEquals(listOf("PROPFIND"), requests.map { it.method })
        }
    }

    @Test fun creationRaceStillUsesConditionsAndVerifiesIdenticalContent() = runBlocking {
        withServer(putStatus = 412, existsInitially = false) { server, file, requests ->
            source(server).writeFileFrom("segment", file, MdbxRemoteWriteMode.CREATE_ONLY)
            assertEquals(listOf("PROPFIND", "PUT", "PROPFIND", "GET"), requests.map { it.method })
            assertEquals("*", requests.single { it.method == "PUT" }.getHeader("If-None-Match"))
        }
    }

    private fun source(server: MockWebServer) = WebDavMdbxFileSource(
        server.url("/dav").toString(), "fixture-user", "fixture-password", StringResolver { id, _ -> "resource:$id" })

    private suspend fun withServer(putStatus: Int = 201, probeStatus: Int = 207, content: String = "immutable",
        existsInitially: Boolean = putStatus == 412,
        block: suspend (MockWebServer, File, MutableList<RecordedRequest>) -> Unit) {
        val server = MockWebServer()
        val requests = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
        var attemptedPut = false
        val file = File.createTempFile("mdbx-request-budget", ".bin").apply { writeText("immutable") }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.method) {
                    "PUT" -> { attemptedPut = true; MockResponse().setResponseCode(putStatus).setHeader("ETag", "\"v1\"") }
                    "GET" -> MockResponse().setBody(content)
                    "PROPFIND" -> MockResponse().setResponseCode(if (!attemptedPut && !existsInitially) 404 else probeStatus)
                        .setHeader("Content-Type", "application/xml").setBody("""
                        <d:multistatus xmlns:d="DAV:"><d:response><d:href>${request.path}</d:href>
                        <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>${content.length}</d:getcontentlength>
                        <d:getetag>"v1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat></d:response></d:multistatus>""".trimIndent())
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        server.start()
        try { block(server, file, requests) } finally { file.delete(); server.shutdown() }
    }
}
