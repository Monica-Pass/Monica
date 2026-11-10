package takagi.ru.monica.utils

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import takagi.ru.monica.update.UpdateChannel

class UpdateReleaseClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var updates: UpdateReleaseClient

    @Before fun prepare() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        updates = releaseClient(client)
    }

    @After fun close() {
        client.dispatcher.cancelAll()
        server.shutdown()
        client.connectionPool.evictAll()
    }

    private fun releaseClient(http: OkHttpClient) = UpdateReleaseClient(http, UpdateEndpoints(
        server.url("/standard/latest").toString(), server.url("/fdroid/package").toString(), server.url("/fdroid/tags/").toString()))

    private fun release(tag: String = "v1.0.328", assets: String = "", extra: String = ""): String = """
        {"tag_name":"$tag","html_url":"https://github.com/Monica-Pass/Monica/releases/tag/$tag",
        "name":"Synthetic release","body":"## Changes\n\n**Synthetic** note","assets":[$assets]$extra}
    """.trimIndent()

    private fun asset(name: String, url: String = "https://github.com/Monica-Pass/Monica/releases/download/v1.0.328/$name") =
        """{"name":"$name","browser_download_url":"$url","content_type":"application/vnd.android.package-archive"}"""

    @Test fun ordinaryCheckUsesNumericVersionAndPreferredSupportedAbiInsteadOfFirstAsset() = runBlocking {
        server.enqueue(MockResponse().setBody(release(assets = listOf(asset("Monica-armeabi-v7a.apk"), asset("Monica-arm64-v8a.apk")).joinToString())))
        val result = updates.check("1.0.327-26111012-01", UpdateChannel.STANDARD, 12L, listOf("arm64-v8a", "armeabi-v7a")).getOrThrow()
        assertTrue(result.isUpdateAvailable)
        assertFalse(result.isFdroid)
        assertEquals("standard:v1.0.328", result.releaseIdentity)
        assertEquals("Monica-arm64-v8a.apk", result.apkAssetName)
        assertEquals("## Changes\n\n**Synthetic** note", result.releaseNotes)
        val request = server.takeRequest()
        assertEquals("/standard/latest", request.path)
        assertEquals("Monica-Android", request.getHeader("User-Agent"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test fun unsupportedAbiAndUnmarkedApkNeverSelectAnArbitraryPackage() = runBlocking {
        server.enqueue(MockResponse().setBody(release(assets = listOf(asset("Monica-arm64-v8a.apk"), asset("Monica.apk")).joinToString())))
        val result = updates.check("1.0.327", UpdateChannel.STANDARD, 12L, listOf("x86_64")).getOrThrow()
        assertTrue(result.isUpdateAvailable)
        assertNull(result.apkDownloadUrl)
        assertNull(result.apkAssetName)
    }

    @Test fun x86DoesNotMatchX86_64AndExplicitUniversalIsAFallback() = runBlocking {
        server.enqueue(MockResponse().setBody(release(assets = listOf(asset("Monica-x86_64.apk"), asset("Monica-universal.apk")).joinToString())))
        assertEquals("Monica-universal.apk", updates.check("1.0.327", UpdateChannel.STANDARD, 12L, listOf("x86")).getOrThrow().apkAssetName)
    }

    @Test fun insecureAssetsAreIgnoredAndInsecureReleaseLinksAreRejected(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(release(assets = asset("Monica-arm64-v8a.apk", "http://example.invalid/a.apk"))))
        assertNull(updates.check("1.0.327", UpdateChannel.STANDARD, 12L, listOf("arm64-v8a")).getOrThrow().apkDownloadUrl)
        server.enqueue(MockResponse().setBody(release().replace("https://github.com/", "http://github.com/")))
        assertTrue(updates.check("1.0.327", UpdateChannel.STANDARD, 12L, emptyList()).isFailure)
        assertThrows(IllegalArgumentException::class.java) { requireHttpsUpdateUrl("https://user:secret@example.invalid/") }
        assertThrows(IllegalArgumentException::class.java) { requireHttpsUpdateUrl("javascript:alert(1)") }
    }

    @Test fun unknownVersionsAndPrereleasesFailRatherThanPromptByLexicalOrder() = runBlocking {
        for (body in listOf(release("nightly"), release("v1.0.328", extra = ",\"prerelease\":true"), "not json")) {
            server.enqueue(MockResponse().setBody(body))
            assertTrue(updates.check("1.0.327", UpdateChannel.STANDARD, 12L, emptyList()).isFailure)
        }
    }

    @Test fun httpFailureAndEmptyResponseRemainFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(updates.check("1.0.327", UpdateChannel.STANDARD, 12L, emptyList()).isFailure)
        server.enqueue(MockResponse().setBody(""))
        assertTrue(updates.check("1.0.327", UpdateChannel.STANDARD, 12L, emptyList()).isFailure)
    }

    @Test fun fdroidUsesSuggestedVersionCodeAndMatchingNotesWithoutOfferingOrdinaryApks() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"packageName":"takagi.ru.monica.fdroid","suggestedVersionCode":35,"packages":[{"versionName":"1.0.328","versionCode":35},{"versionName":"1.0.329","versionCode":36}]}"""))
        server.enqueue(MockResponse().setBody(release(assets = asset("Monica-arm64-v8a.apk"))))
        val result = updates.check("1.0.327", UpdateChannel.FDROID, 34L, listOf("arm64-v8a")).getOrThrow()
        assertTrue(result.isFdroid)
        assertTrue(result.isUpdateAvailable)
        assertEquals("1.0.328", result.latestVersion)
        assertEquals("fdroid:35", result.releaseIdentity)
        assertEquals("https://f-droid.org/packages/takagi.ru.monica.fdroid/", result.releaseUrl)
        assertEquals("https://github.com/Monica-Pass/Monica-Fdroid/releases/tag/v1.0.328", result.detailsUrl)
        assertNull(result.apkDownloadUrl)
        assertNull(result.apkAssetName)
        assertNotNull(result.releaseNotes)
        assertEquals("/fdroid/package", server.takeRequest().path)
        assertEquals("/fdroid/tags/v1.0.328", server.takeRequest().path)
    }

    @Test fun fdroidSourceReleaseNotesFailureDoesNotEraseOfficialAvailability() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"packageName":"takagi.ru.monica.fdroid","suggestedVersionCode":35,"packages":[{"versionName":"1.0.328","versionCode":35}]}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        val result = updates.check("1.0.327", UpdateChannel.FDROID, 34L, emptyList()).getOrThrow()
        assertTrue(result.isUpdateAvailable)
        assertNull(result.releaseNotes)
        assertNull(result.apkDownloadUrl)
    }

    @Test fun fdroidRejectsWrongPackageOrMissingSuggestedVersionAndDoesNotCheckNewerSourceTags() = runBlocking {
        for (body in listOf(
            """{"packageName":"takagi.ru.monica","suggestedVersionCode":35,"packages":[]}""",
            """{"packageName":"takagi.ru.monica.fdroid","suggestedVersionCode":35,"packages":[{"versionName":"1.0.329","versionCode":36}]}""",
        )) {
            server.enqueue(MockResponse().setBody(body))
            assertTrue(updates.check("1.0.327", UpdateChannel.FDROID, 34L, emptyList()).isFailure)
        }
        server.enqueue(MockResponse().setBody("""{"packageName":"takagi.ru.monica.fdroid","suggestedVersionCode":33,"packages":[{"versionName":"1.0.326","versionCode":33}]}"""))
        assertFalse(updates.check("1.0.327", UpdateChannel.FDROID, 34L, emptyList()).getOrThrow().isUpdateAvailable)
        assertEquals(3, server.requestCount)
    }

    @Test fun metadataLimitRejectsKnownLengthAndChunkedOverflow() = runBlocking {
        val oversized = " ".repeat(MAX_UPDATE_METADATA_BYTES + 1)
        server.enqueue(MockResponse().setBody(oversized))
        assertTrue(updates.check("1.0.327", UpdateChannel.STANDARD, 12L, emptyList()).isFailure)
        server.enqueue(MockResponse().setChunkedBody(oversized, 8192))
        assertTrue(updates.check("1.0.327", UpdateChannel.STANDARD, 12L, emptyList()).isFailure)
    }

    @Test fun timeoutIsReportedWithoutHanging() = runBlocking {
        val short = client.newBuilder().callTimeout(150, TimeUnit.MILLISECONDS).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val result = withTimeout(3000) { releaseClient(short).check("1.0.327", UpdateChannel.STANDARD, 12L, emptyList()) }
        assertTrue(result.isFailure)
    }

    @Test fun cancellationClosesTheCallWhileWaitingForHeaders() = runBlocking {
        assertCancelled(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    }

    @Test fun cancellationClosesTheCallDuringSlowResponseBody() = runBlocking {
        assertCancelled(MockResponse().setBody(release()).throttleBody(1, 1, TimeUnit.SECONDS), waitForBody = true)
    }

    private suspend fun assertCancelled(response: MockResponse, waitForBody: Boolean = false) = kotlinx.coroutines.coroutineScope {
        val failed = CompletableDeferred<Unit>()
        val bodyStarted = CompletableDeferred<Unit>()
        val observed = client.newBuilder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) { failed.complete(Unit) }
            override fun responseBodyStart(call: Call) { bodyStarted.complete(Unit) }
        }).build()
        server.enqueue(response)
        val request = async { releaseClient(observed).check("1.0.327", UpdateChannel.STANDARD, 12L, emptyList()) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
        if (waitForBody) withTimeout(3000) { bodyStarted.await() }
        withTimeout(3000) { request.cancelAndJoin(); failed.await() }
        assertTrue(request.isCancelled)
    }

    @Test fun downloadReportsProgressPreservesOtherFilesAndUsesAnApkSuffix() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("update-fixture-").toFile()
        try {
            val other = File(directory, "previous.apk").apply { writeText("retained") }
            server.enqueue(MockResponse().setBody("synthetic-apk-bytes"))
            val progress = mutableListOf<UpdateDownloadProgress>()
            val downloadClient = client.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/apk")).build())
            }.build()
            val result = downloadUpdateApk(downloadClient, "https://example.invalid/file.apk", directory, "../synthetic.apk") { progress += it }.getOrThrow()
            assertEquals(directory.canonicalFile, result.parentFile.canonicalFile)
            assertTrue(result.name.endsWith(".apk"))
            assertEquals("synthetic-apk-bytes", result.readText())
            assertEquals("retained", other.readText())
            assertEquals(0L, progress.first().bytesRead)
            assertEquals(result.length(), progress.last().bytesRead)
            assertEquals(1f, progress.last().fraction, 0f)
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationDuringDownloadDeletesOnlyItsOwnPartialFile() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("update-cancel-fixture-").toFile()
        try {
            val other = File(directory, "previous.apk").apply { writeText("retained") }
            val started = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().setBody("synthetic-apk-bytes").throttleBody(1, 1, TimeUnit.SECONDS))
            val downloadClient = client.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/apk")).build())
            }.build()
            val request = async {
                downloadUpdateApk(downloadClient, "https://example.invalid/file.apk", directory, "synthetic.apk") {
                    if (it.bytesRead > 0L) started.complete(Unit)
                }
            }
            withTimeout(3000) { started.await(); request.cancelAndJoin() }
            assertTrue(request.isCancelled)
            assertEquals(listOf(other.name), directory.listFiles()!!.map { it.name })
            assertEquals("retained", other.readText())
        } finally { directory.deleteRecursively() }
    }
}
