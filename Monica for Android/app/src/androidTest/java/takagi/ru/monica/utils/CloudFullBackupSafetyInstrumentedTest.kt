package takagi.ru.monica.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import java.io.File
import java.io.IOException
import java.util.UUID

class CloudFullBackupSafetyInstrumentedTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private class IsolatedContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val prefix = "cloud-safety-${UUID.randomUUID()}-"
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
    private val prefs = BackupPreferences(includePasswords = true, includeNotes = true,
        includeAuthenticators = false, includeDocuments = false, includeBankCards = false,
        includePasskeys = false, includeGeneratorHistory = false, includeImages = false,
        includeTimeline = false, includeTrash = false, includeTrashAndHistory = false,
        includeWebDavConfig = false, includeLocalKeePass = false)
    private fun field(helper: WebDavHelper, name: String, value: Any) {
        WebDavHelper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(helper, value)
    }
    @Test fun realWebDavServerFullArchiveRoundTrip() = runBlocking {
        val endpoint = InstrumentationRegistry.getArguments().getString("fullBackupWebDavUrl")
        org.junit.Assume.assumeTrue("No isolated real WebDAV endpoint provided", !endpoint.isNullOrBlank())
        IsolatedContext(base).use { context ->
            val helper = WebDavHelper(context)
            field(helper, "serverUrl", endpoint!! + "/" + UUID.randomUUID())
            val client = com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine()
            val rootField = WebDavHelper::class.java.getDeclaredField("serverUrl").apply { isAccessible = true }
            val root = rootField.get(helper) as String
            client.createDirectory(root)
            field(helper, "sardine", client)
            val (archive, report) = helper.createBackupZip(listOf(PasswordEntry(title = "live-webdav-fixture",
                username = "synthetic", password = "synthetic-secret", website = "https://example.invalid")),
                emptyList(), prefs, backupEncryptionPassword = "synthetic-password").getOrThrow()
            val downloaded = File.createTempFile("live-webdav-", ".zip", base.cacheDir)
            try {
                assertTrue(report.success)
                val name = helper.uploadBackup(archive, true).getOrThrow()
                val listed = helper.listBackups().getOrThrow().single { it.name == name }
                helper.downloadBackup(listed, downloaded).getOrThrow()
                assertArrayEquals(archive.readBytes(), downloaded.readBytes())
                val restored = helper.restoreFromBackupFile(downloaded, "synthetic-password",
                    restoreMonicaConfig = false, importDataOnly = true).getOrThrow()
                assertEquals("synthetic-secret", restored.content.passwords.single().password)
            } finally { archive.delete(); downloaded.delete(); client.delete(root) }
        }
    }
    @Test fun realOneDriveFullArchiveRoundTripWhenSignedIn() = runBlocking {
        val session = OneDriveAuthManager(base).getCachedSession()
        org.junit.Assume.assumeTrue("No cached OneDrive account; authenticated full backup remains unverified", session != null)
        val account = requireNotNull(session)
        val source = OneDriveKeePassFileSource(base, account.accountId)
        val directory = "monica-full-backup-test-${UUID.randomUUID()}"
        source.createDirectory(null, directory)
        try {
            IsolatedContext(base).use { context ->
                val helper = OneDriveBackupHelper(context) { id, path -> OneDriveKeePassFileSource(base, id, remotePath = path) }
                helper.saveConfig(account, directory)
                val codec = WebDavHelper(context)
                // Incompressible synthetic content exercises the file/chunk upload path (>2 MiB).
                val largeNotes = android.util.Base64.encodeToString(
                    kotlin.random.Random(317).nextBytes(3 * 1024 * 1024), android.util.Base64.NO_WRAP)
                val (archive, report) = codec.createBackupZip(listOf(PasswordEntry(title = "live-onedrive-fixture",
                    username = "synthetic", password = "synthetic-secret", website = "https://example.invalid", notes = largeNotes)),
                    emptyList(), prefs, backupEncryptionPassword = "synthetic-password").getOrThrow()
                val downloaded = File.createTempFile("live-onedrive-", ".zip", base.cacheDir)
                try {
                    assertTrue(report.success)
                    assertTrue("Fixture must exercise chunked OneDrive upload", archive.length() > 2 * 1024 * 1024)
                    val uploaded = helper.uploadBackup(archive, true).getOrThrow()
                    val listed = helper.listBackups().getOrThrow().single { it.name == uploaded.name }
                    helper.downloadBackup(listed, downloaded).getOrThrow()
                    assertArrayEquals(archive.readBytes(), downloaded.readBytes())
                    val restored = codec.restoreFromBackupFile(downloaded, "synthetic-password",
                        restoreMonicaConfig = false, importDataOnly = true).getOrThrow()
                    assertEquals("synthetic-secret", restored.content.passwords.single().password)
                    assertEquals(largeNotes, restored.content.passwords.single().notes)
                } finally { archive.delete(); downloaded.delete() }
            }
        } finally { source.deleteEntry(directory) }
    }
    @Test fun oneDriveCleanupPreservesUnrelatedZipAndJustUploadedArchive() = runBlocking {
        IsolatedContext(base).use { context ->
            MockWebServer().use { server ->
                fun entry(name: String, year: Int) = """{"id":"$name","name":"$name","file":{},"lastModifiedDateTime":"$year-01-01T00:00:00Z"}"""
                val listing = java.util.concurrent.atomic.AtomicReference(
                    (1..12).joinToString(",") { entry("family-$it.zip", 2000) })
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                        if (request.method == "GET" && request.path == "/me/drive/root/children")
                            MockResponse().setBody("""{"value":[${listing.get()}]}""")
                        else MockResponse().setResponseCode(500).setBody("Unexpected deletion request")
                }
                server.start()
                val helper = OneDriveBackupHelper(context) { id, path ->
                    OneDriveKeePassFileSource(accountIdentifier = id, remotePath = path,
                        accessTokenProvider = OneDriveAccessTokenProvider { "synthetic" },
                        httpClient = OkHttpClient(), graphBaseUrl = server.url("/").toString().trimEnd('/'),
                        cacheDirectory = base.cacheDir, strings = AppLocaleStringResolver(context))
                }
                helper.saveConfig(OneDriveAccountSession("synthetic", "fixture", "Fixture"), "/")
                assertEquals(0, helper.cleanupBackups().getOrThrow())
                val protected = "monica_backup_20260930_120000.zip"
                listing.set((1..10).map { entry("monica_backup_20200930_${it.toString().padStart(6, '0')}.zip", 2020) }
                    .plus(entry(protected, 2000)).joinToString(","))
                assertEquals(0, helper.cleanupBackups(protectedBackupName = protected).getOrThrow())
                assertEquals("Cleanup must only list; no delete or file lookup", 2, server.requestCount)
            }
        }
    }
    @Test fun oneDirectoryLoadSuppliesFoldersAndBackupsWithoutRefetching() = runBlocking {
        IsolatedContext(base).use { context ->
            MockWebServer().use { server ->
                server.start()
                var tokenCalls = 0
                val helper = OneDriveBackupHelper(context) { id, path ->
                    OneDriveKeePassFileSource(accountIdentifier = id, remotePath = path,
                        accessTokenProvider = OneDriveAccessTokenProvider {
                            tokenCalls++
                            "synthetic-$id"
                        }, httpClient = OkHttpClient(),
                        graphBaseUrl = server.url("/").toString().trimEnd('/'),
                        cacheDirectory = base.cacheDir, strings = AppLocaleStringResolver(context))
                }
                helper.saveConfig(OneDriveAccountSession("first", "fixture", "Fixture"), "/")
                server.enqueue(MockResponse().setBody("""{"value":[
                    {"id":"folder","name":"folder.zip","folder":{}},
                    {"id":"old","name":"old.zip","size":10,"file":{},"lastModifiedDateTime":"2025-01-01T00:00:00Z"},
                    {"id":"text","name":"readme.txt","file":{}}
                ],"@odata.nextLink":"${server.url("/page2")}"}"""))
                server.enqueue(MockResponse().setBody("""{"value":[
                    {"id":"new","name":"new.ZIP","size":20,"file":{},"lastModifiedDateTime":"2026-01-01T00:00:00Z"}
                ]}"""))
                val directory = helper.loadBackupDirectory("first", "/")
                assertEquals(listOf("folder.zip"), directory.folders.map { it.name })
                assertEquals(listOf("new.ZIP", "old.zip"), directory.backups.map { it.name })
                assertEquals("new.ZIP", directory.backups.first().path)
                assertEquals(20L, directory.backups.first().size)
                assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
                    directory.backups.first().modified.time)
                assertEquals("Each Graph page is fetched once for both views", 2, server.requestCount)
                assertEquals("Pagination reuses the token", 1, tokenCalls)
                assertEquals("/me/drive/root/children", server.takeRequest().path)
                assertEquals("/page2", server.takeRequest().path)

                // Another account/path must perform a new request, with that account's token.
                server.enqueue(MockResponse().setBody("""{"value":[
                    {"id":"other","name":"other.zip","file":{}}
                ]}"""))
                val other = helper.loadBackupDirectory("second", "nested")
                assertEquals("nested/other.zip", other.backups.single().path)
                assertEquals(0L, other.backups.single().size)
                assertEquals(3, server.requestCount)
                val otherRequest = server.takeRequest()
                assertEquals("/me/drive/root:/nested:/children", otherRequest.path)
                assertEquals("Bearer synthetic-second", otherRequest.getHeader("Authorization"))

                // Explicit refresh still sees remote changes in the configured account/folder.
                server.enqueue(MockResponse().setBody("""{"value":[
                    {"id":"fresh","name":"fresh.zip","file":{}}
                ]}"""))
                assertEquals("fresh.zip", helper.listBackups().getOrThrow().single().name)
                assertEquals(4, server.requestCount)
                val refreshRequest = server.takeRequest()
                assertEquals("/me/drive/root/children", refreshRequest.path)
                assertEquals("Bearer synthetic-first", refreshRequest.getHeader("Authorization"))
                assertEquals(3, tokenCalls)

                server.enqueue(MockResponse().setResponseCode(503))
                assertTrue("A failed refresh must not silently reuse stale results", helper.listBackups().isFailure)
                assertEquals(5, server.requestCount)
            }
        }
    }
    @Test fun rootFolderAndLegacyRootRemainConfigured() {
        IsolatedContext(base).use { context ->
            val helper = OneDriveBackupHelper(context)
            assertFalse(helper.isConfigured())
            helper.saveConfig(OneDriveAccountSession("synthetic", "fixture", "Fixture"), "/")
            assertEquals("", OneDriveBackupHelper(context).getConfig()!!.folderPath)
            SecurityManager(context).removeProtectedString("onedrive_backup_folder_path")
            assertEquals("", OneDriveBackupHelper(context).getConfig()!!.folderPath)
            helper.saveConfig(OneDriveAccountSession("synthetic", "fixture", "Fixture"), "nested/backups")
            assertEquals("nested/backups", OneDriveBackupHelper(context).getConfig()!!.folderPath)
        }
    }
    @Test fun failedLocalWritePreservesCompleteArchive() {
        val file = File.createTempFile("atomic-backup-", ".zip", base.cacheDir)
        try {
            file.writeText("previous-complete-archive")
            assertTrue(runCatching { writeBackupAtomically(file) {
                it.write("partial".toByteArray()); throw IOException("synthetic interruption")
            } }.isFailure)
            assertEquals("previous-complete-archive", file.readText())
            writeBackupAtomically(file) { it.write("replacement".toByteArray()) }
            assertEquals("replacement", file.readText())
        } finally { file.delete() }
    }
    @Test fun encryptedArchiveRoundTripsThroughWebDavAndGraphHttp() = runBlocking {
        encryptedArchiveRoundTrip(includeOneDrive = true)
    }
    @Test fun webDavEncryptedArchiveRoundTrip() = runBlocking {
        encryptedArchiveRoundTrip(includeOneDrive = false)
    }
    private suspend fun encryptedArchiveRoundTrip(includeOneDrive: Boolean) {
        IsolatedContext(base).use { context ->
            val archiveHelper = WebDavHelper(context)
            val passwords = listOf("  first-secret  ", "second-secret").mapIndexed { index, secret ->
                PasswordEntry(id = -9000L - index, title = "Synthetic cloud fixture", username = "alice",
                    password = secret, website = "https://example.invalid", passwordGroupId = "fixture-group",
                    notes = "line one\n  line two  ", authenticatorKey = "JBSWY3DPEHPK3PXP")
            }
            val (archive, report) = archiveHelper.createBackupZip(passwords, emptyList(), prefs,
                backupEncryptionPassword = "synthetic archive password").getOrThrow()
            val downloaded = File.createTempFile("cloud-download-", ".zip", base.cacheDir)
            try {
                assertTrue(report.success)
                suspend fun verify() {
                    assertArrayEquals(archive.readBytes(), downloaded.readBytes())
                    assertTrue(archiveHelper.restoreFromBackupFile(downloaded, "wrong-password",
                        restoreMonicaConfig = false, importDataOnly = true).isFailure)
                    val result = archiveHelper.restoreFromBackupFile(downloaded, "synthetic archive password",
                        restoreMonicaConfig = false, importDataOnly = true).getOrThrow()
                    // The archive stores separate files; ZIP enumeration is not record order.
                    val restored = result.content.passwords
                    assertEquals(passwords.size, restored.size)
                    assertEquals(restored.size, restored.map { it.id }.toSet().size)
                    assertTrue("Each restored ID must preserve its entire record, including exact whitespace",
                        passwords.associateBy { it.id } == restored.associateBy { it.id })
                    assertTrue(result.content.passwords.all { it.passwordGroupId == "fixture-group" })
                    assertTrue(result.content.passwords.all { it.authenticatorKey == "JBSWY3DPEHPK3PXP" })
                }
                MockWebServer().use { server ->
                    server.start()
                    val webdav = WebDavHelper(context)
                    field(webdav, "serverUrl", server.url("/").toString().trimEnd('/'))
                    field(webdav, "sardine", com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine())
                    server.enqueue(MockResponse().setResponseCode(200)) // existence PROPFIND
                    server.enqueue(MockResponse().setResponseCode(201)) // PUT
                    val name = webdav.uploadBackup(archive, isPermanent = true).getOrThrow()
                    val head = server.takeRequest()
                    assertEquals("PROPFIND", head.method)
                    val upload = server.takeRequest()
                    assertEquals("PUT", upload.method)
                    assertArrayEquals(archive.readBytes(), upload.body.readByteArray())
                    val backup = BackupFile(name, name, archive.length(), java.util.Date())
                    server.enqueue(MockResponse().setBody(okio.Buffer().write(archive.readBytes())))
                    webdav.downloadBackup(backup, downloaded).getOrThrow()
                    assertEquals("GET", server.takeRequest().method)
                    verify()
                    val previous = downloaded.readBytes()
                    server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(65536)))
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                    assertTrue(webdav.downloadBackup(backup, downloaded).isFailure)
                    server.takeRequest()
                    assertArrayEquals(previous, downloaded.readBytes())
                }
                if (includeOneDrive) MockWebServer().use { server ->
                    server.start()
                    val oneDrive = OneDriveBackupHelper(context) { account, path ->
                        OneDriveKeePassFileSource(accountIdentifier = account, remotePath = path,
                            accessTokenProvider = OneDriveAccessTokenProvider { "synthetic-token" },
                            httpClient = OkHttpClient(), graphBaseUrl = server.url("/").toString().trimEnd('/'),
                            cacheDirectory = base.cacheDir, strings = AppLocaleStringResolver(context))
                    }
                    oneDrive.saveConfig(OneDriveAccountSession("synthetic", "fixture", "Fixture"), "/")
                    val name = archive.name.replace(".zip", "_permanent.zip")
                    server.enqueue(MockResponse().setResponseCode(404)) // target does not exist
                    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"fixture","name":"$name","size":${archive.length()}}"""))
                    server.enqueue(MockResponse().setBody("""{"value":[]}""")) // retention listing
                    val backup = oneDrive.uploadBackup(archive, true).getOrThrow()
                    assertEquals("GET", server.takeRequest().method) // existence check
                    val upload = server.takeRequest()
                    assertEquals("PUT", upload.method)
                    assertTrue(upload.path!!.contains("conflictBehavior=fail"))
                    assertArrayEquals(archive.readBytes(), upload.body.readByteArray())
                    assertEquals("/me/drive/root/children", server.takeRequest().path)
                    server.enqueue(MockResponse().setBody(okio.Buffer().write(archive.readBytes())))
                    oneDrive.downloadBackup(backup, downloaded).getOrThrow()
                    assertEquals("GET", server.takeRequest().method)
                    verify()
                    val previous = downloaded.readBytes()
                    server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
                    assertTrue(oneDrive.downloadBackup(backup, downloaded).isFailure)
                    server.takeRequest()
                    assertArrayEquals(previous, downloaded.readBytes())
                    server.enqueue(MockResponse().setResponseCode(404))
                    server.enqueue(MockResponse().setResponseCode(507).setBody("quota exceeded"))
                    assertTrue(oneDrive.uploadBackup(archive, false).isFailure)
                    assertEquals("GET", server.takeRequest().method)
                    assertEquals("PUT", server.takeRequest().method)
                    assertEquals(7, server.requestCount) // no cleanup after failed upload
                }
                downloaded.writeBytes(archive.readBytes().copyOf(archive.length().toInt() / 2))
                assertTrue(archiveHelper.restoreFromBackupFile(downloaded, "synthetic archive password",
                    restoreMonicaConfig = false, importDataOnly = true).isFailure)
            } finally { archive.delete(); downloaded.delete() }
        }
    }
}
