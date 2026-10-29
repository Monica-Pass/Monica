package takagi.ru.monica.utils

import android.app.Application
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.lansync.DeviceVaultProviderTest
import takagi.ru.monica.webdav.WebDavBackoffState
import takagi.ru.monica.webdav.WebDavCredentials
import takagi.ru.monica.webdav.WebDavErrorClassifier
import takagi.ru.monica.webdav.WebDavErrorKind
import takagi.ru.monica.webdav.WebDavGateway

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [
    DeviceVaultProviderTest.SecureStoreShadow::class,
    DeviceVaultProviderTest.SecurityShadow::class
])
class WebDavBackupDirectoryFailureTest {
    private val server = MockWebServer()
    private val file = File.createTempFile("backup-directory-", ".enc.zip")
    private val bytes = byteArrayOf(1, 5, 9, 13)

    @Before fun prepare() {
        WebDavBackoffState.resetForTest()
        file.writeBytes(bytes)
        server.start()
    }

    @After fun cleanup() {
        server.shutdown()
        file.delete()
        WebDavBackoffState.resetForTest()
    }

    @Test fun serverErrorDoesNotCreateDirectoryOrUploadBackup() = runBlocking {
        directoryResponse(MockResponse().setResponseCode(500))
        val result = helper().uploadBackup(file)
        assertTrue("HTTP 500 must stop the backup", result.isFailure)
        assertEquals(WebDavErrorKind.Unknown, WebDavErrorClassifier.classify(result.exceptionOrNull()).kind)
        assertOnlyDirectoryCheck()
    }

    @Test fun metadataTimeoutDoesNotCreateDirectoryOrUploadBackup() = runBlocking {
        directoryResponse(MockResponse().setResponseCode(207).setHeadersDelay(500, TimeUnit.MILLISECONDS))
        val result = helper().uploadBackup(file)
        assertTrue("An unknown directory state must stop the backup", result.isFailure)
        assertEquals(WebDavErrorKind.Timeout, WebDavErrorClassifier.classify(result.exceptionOrNull()).kind)
        assertOnlyDirectoryCheck()
    }

    @Test fun droppedConnectionDoesNotCreateDirectoryOrUploadBackup() = runBlocking {
        directoryResponse(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val result = helper().uploadBackup(file)
        assertTrue("A dropped directory response must stop the backup", result.isFailure)
        assertNotEquals(WebDavErrorKind.NotFound, WebDavErrorClassifier.classify(result.exceptionOrNull()).kind)
        assertOnlyDirectoryCheck()
    }

    @Test fun confirmedMissingDirectoryIsCreatedBeforeUploadingOriginalBytes() = runBlocking {
        directoryResponse(MockResponse().setResponseCode(404))
        assertTrue(helper().uploadBackup(file).isSuccess)
        assertEquals(3, server.requestCount)
        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals("MKCOL", server.takeRequest().method)
        val uploaded = server.takeRequest()
        assertEquals("PUT", uploaded.method)
        assertArrayEquals(bytes, uploaded.body.readByteArray())
        assertEquals("*", uploaded.getHeader("If-None-Match"))
    }

    @Test fun existingDirectoryUploadsWithoutCreatingIt() = runBlocking {
        directoryResponse(MockResponse().setResponseCode(207))
        assertTrue(helper().uploadBackup(file).isSuccess)
        assertEquals(2, server.requestCount)
        assertEquals("PROPFIND", server.takeRequest().method)
        val uploaded = server.takeRequest()
        assertEquals("PUT", uploaded.method)
        assertArrayEquals(bytes, uploaded.body.readByteArray())
    }

    private fun directoryResponse(response: MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "PROPFIND") response else MockResponse().setResponseCode(201)
        }
    }

    private fun assertOnlyDirectoryCheck() {
        assertEquals("Directory uncertainty must not trigger MKCOL or PUT", 1, server.requestCount)
        assertEquals("PROPFIND", server.takeRequest(1, TimeUnit.SECONDS)?.method)
    }

    private fun helper(): WebDavHelper {
        val url = server.url("/dav").toString()
        val credentials = WebDavCredentials("fixture-user", "fixture-password")
        val client = WebDavGateway.buildHttpClient(credentials, url).newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(200, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        // Configure only the real upload transport; credential storage is unrelated
        // to this regression and Robolectric cannot create Android Keystore keys.
        return WebDavHelper(RuntimeEnvironment.getApplication()).also { helper ->
            setField(helper, "serverUrl", url)
            setField(helper, "username", credentials.username)
            setField(helper, "password", credentials.password)
            setField(helper, "sardine", OkHttpSardine(client))
        }
    }

    private fun setField(helper: WebDavHelper, name: String, value: Any) {
        WebDavHelper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(helper, value)
    }
}
