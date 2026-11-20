package takagi.ru.monica.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.webdav.*

/** Real helper / HTTP sequence, using isolated settings and synthetic archives only. */
class WebDavBackupSequenceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private class IsolatedContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val prefix = "backup-sequence-${UUID.randomUUID()}-"
        private val names = mutableSetOf<String>()
        override fun getApplicationInfo() = android.content.pm.ApplicationInfo(baseContext.applicationInfo).apply {
            dataDir = File(baseContext.cacheDir, prefix).absolutePath
        }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names += prefix + name
            return baseContext.getSharedPreferences(prefix + name, mode)
        }
        override fun close() { names.forEach { baseContext.deleteSharedPreferences(it) } }
    }

    @After fun restoreGateway() {
        WebDavBackoffState.resetForTest()
    }

    private fun helper(context: Context, server: MockWebServer): WebDavHelper {
        WebDavBackoffState.resetForTest()
        return WebDavHelper(context).also { helper ->
            fun set(name: String, value: Any) {
                WebDavHelper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(helper, value)
            }
            val url = server.url("/dav").toString().trimEnd('/')
            set("serverUrl", url)
            set("sardine", OkHttpSardine(WebDavGateway.buildHttpClient(WebDavCredentials("", ""), url)))
        }
    }

    private fun directory(path: String) = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody("""<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>${path.trimEnd('/')}/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
        """.trimIndent())

    @Test fun listingNeedsOnlyOneDirectoryRequest() = runBlocking {
        IsolatedContext(base).use { context -> MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = directory(request.path!!)
            }
            server.start()
            assertTrue(helper(context, server).listBackups().getOrThrow().isEmpty())
            assertEquals("Listing must not first query whether the same directory exists", 1, server.requestCount)
            assertEquals("PROPFIND", server.takeRequest().method)
        } }
    }

    @Test fun onlyMissingDirectoriesBecomeEmptyLists() = runBlocking {
        for (status in listOf(404, 401, 503)) {
            IsolatedContext(base).use { context -> MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(status)
                }
                server.start()
                val result = helper(context, server).listBackups()
                if (status == 404) assertTrue(result.getOrThrow().isEmpty())
                else assertEquals(if (status == 401) WebDavErrorKind.AuthFailed else WebDavErrorKind.RateLimited,
                    WebDavErrorClassifier.classify(result.exceptionOrNull()).kind)
                assertEquals(1, server.requestCount)
            } }
        }
    }

    @Test fun successfulArchiveWithThrottledCleanupBlocksSubsequentMdbxRequest() = runBlocking {
        IsolatedContext(base).use { context -> MockWebServer().use { server ->
            var uploadedBytes = 0L
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "PUT" -> {
                        uploadedBytes = request.bodySize
                        MockResponse().setResponseCode(201)
                    }
                    uploadedBytes > 0 -> MockResponse().setResponseCode(503).setHeader("Retry-After", "300")
                    else -> directory(request.path!!)
                }
            }
            server.start()
            val helper = helper(context, server)
            val prefs = BackupPreferences(includePasswords = true, includeNotes = false,
                includeAuthenticators = false, includeDocuments = false, includeBankCards = false,
                includePasskeys = false, includeGeneratorHistory = false, includeImages = false,
                includeTrash = false, includeTrashAndHistory = false,
                includeWebDavConfig = false, includeLocalKeePass = false)
            val report = helper.createAndUploadBackup(listOf(PasswordEntry(title = "Synthetic sequence",
                username = "fixture", password = "synthetic-secret", website = "https://example.invalid")), emptyList(), prefs,
                isPermanent = true).getOrThrow()
            assertTrue("The completed archive remains a success", report.success)
            assertTrue("Cleanup failure must not be hidden", report.warnings.isNotEmpty())
            assertTrue(uploadedBytes > 0)
            assertTrue(helper.getLastBackupTime() > 0)
            val requestsAfterBackup = server.requestCount
            val source = WebDavMdbxFileSource(server.url("/dav").toString(), "", "",
                StringResolver { id, _ -> "resource:$id" })
            val failure = runCatching { source.statPath("synthetic.mdbx.sync/head") }.exceptionOrNull()
            assertEquals(WebDavErrorKind.RateLimited, WebDavErrorClassifier.classify(failure).kind)
            assertTrue(WebDavBackoffState.suggestedWaitMillis(server.hostName) > 250_000L)
            assertEquals("Cooldown stops MDBX before another network request", requestsAfterBackup, server.requestCount)
            assertEquals(listOf("PROPFIND", "PUT", "PROPFIND"),
                List(requestsAfterBackup) { server.takeRequest().method })
        } }
    }

    @Test fun successfulUploadDoesNotItselfStartCooldown() = runBlocking {
        IsolatedContext(base).use { context -> MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    if (request.method == "PUT") MockResponse().setResponseCode(201) else directory(request.path!!)
            }
            server.start()
            val archive = File.createTempFile("sequence-", ".zip", base.cacheDir).apply { writeText("synthetic") }
            try {
                helper(context, server).uploadBackup(archive, true).getOrThrow()
                assertFalse(WebDavBackoffState.shouldBlock(server.hostName))
                val source = WebDavMdbxFileSource(server.url("/dav").toString(), "", "",
                    StringResolver { id, _ -> "resource:$id" })
                assertNotNull(source.statPath("synthetic.mdbx.sync/head"))
                assertEquals(3, server.requestCount)
            } finally { archive.delete() }
        } }
    }
}
