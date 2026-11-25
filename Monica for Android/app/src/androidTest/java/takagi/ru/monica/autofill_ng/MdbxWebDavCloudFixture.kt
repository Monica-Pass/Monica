package takagi.ru.monica.autofill_ng

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.*
import takagi.ru.monica.webdav.WebDavCredentials
import takagi.ru.monica.webdav.WebDavGateway

/** Opt-in real provider, credentials supplied in a private app file, never runner arguments/assets. */
internal class MdbxWebDavCloudFixture private constructor(
    private val context: Context, private val db: PasswordDatabase, private val security: SecurityManager,
    private val credentials: JSONObject
) {
    private val runId = UUID.randomUUID().toString()
    private val root = credentials.optString("testParent").takeIf { it.isNotBlank() }?.let {
        require(it.matches(Regex("monica331-[0-9a-f]{8}")))
        "$it/case-${runId.take(8)}"
    } ?: "monica331-${runId.take(8)}"
    private val path = "$root/vault.mdbx"
    private val cache = File(context.cacheDir, root)
    private val state = MdbxSyncStateStore(db.mdbxSyncStateDao())
    private val coordinator = Mdbx2RemoteSyncCoordinator(cache,
        Mdbx2RepositorySyncSessionProvider(Mdbx2Repository(context, db.localMdbxDatabaseDao(), security)), state)
    private var databaseId: Long? = null
    private var sourceId: Long? = null
    private var peerId: Long? = null
    private val peerFile = File(cache, "peer.mdbx")
    private var remoteCreated = false
    private val peerPreferences = "mdbx2_vault_sessions-cloud-peer-$runId"
    private fun transport() = WebDavMdbxRemoteTransport(credentials.getString("url"),
        credentials.getString("username"), credentials.getString("password"), AppLocaleStringResolver(context))

    suspend fun prepare(id: Long) {
        databaseId = id
        val source = db.mdbxRemoteSourceDao().insertSource(MdbxRemoteSource(displayName = "Synthetic WebDAV test",
            remotePath = path, baseUrl = credentials.getString("url"),
            usernameEncrypted = security.encryptData(credentials.getString("username")),
            passwordEncrypted = security.encryptData(credentials.getString("password"))))
        sourceId = source
        val original = requireNotNull(db.localMdbxDatabaseDao().getDatabaseById(id))
        db.localMdbxDatabaseDao().updateDatabase(original.copy(filePath = path, sourceId = source))
        check(transport().stat(root) == null) { "Synthetic directory already exists" }
        remoteCreated = true // Also clean a partially created bootstrap directory on failure.
        coordinator.publishBootstrap(id, path, transport())
        coordinator.downloadBootstrapTo(path, transport(), peerFile)
    }

    suspend fun verifyDelivery(row: PasswordEntry, password: String, vaultPassword: String) {
        val id = requireNotNull(databaseId)
        verifyKnownCollisionProtection()
        val report = coordinator.synchronize(id, path, transport())
        assertEquals(0, report.blockedStreams)
        assertEquals(0, report.conflicts)
        assertTrue(report.uploadedSegments > 0)
        val registration = requireNotNull(db.localMdbxDatabaseDao().getDatabaseById(id))
        val peer = db.localMdbxDatabaseDao().insertDatabase(registration.copy(id = 0,
            name = "Independent cloud receiver", workingCopyPath = peerFile.path, cacheCopyPath = peerFile.path))
        peerId = peer
        val peerContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(if (name == "mdbx2_vault_sessions") peerPreferences else name, mode)
        }
        val receiver = Mdbx2RemoteSyncCoordinator(File(cache, "receiver"),
            Mdbx2RepositorySyncSessionProvider(Mdbx2Repository(peerContext, db.localMdbxDatabaseDao(), security)), state)
        receiver.registerDownloadedBootstrap(peer, path)
        val received = receiver.synchronize(peer, path, transport())
        assertEquals(0, received.blockedStreams)
        assertEquals(0, received.conflicts)
        assertTrue(received.downloadedSegments > 0)
        val vault = uniffi.mdbx_ffi.openVault(peerFile.path, vaultPassword, "cloud-independent-cold-reader-$runId")
        try {
            val payload = JSONObject(readMdbxNativeObject(vault, requireNotNull(row.replicaGroupId)).payload)
            assertEquals(row.username, payload.getString("username"))
            assertEquals(password, payload.getString("password_plain"))
        } finally { vault.close() }
        val retry = coordinator.synchronize(id, path, transport())
        assertEquals(0, retry.blockedStreams)
        assertEquals(0, retry.conflicts)
    }

    private suspend fun verifyKnownCollisionProtection() {
        val remote = "$root/immutable-probe.bin"
        val original = File(cache, "original-probe.bin").apply { writeText("synthetic-original") }
        val changed = File(cache, "changed-probe.bin").apply { writeText("synthetic-replaced") }
        val received = File(cache, "received-probe.bin")
        val connection = transport()
        connection.writeFrom(remote, original, MdbxRemoteWriteMode.CREATE_ONLY)
        connection.writeFrom(remote, original, MdbxRemoteWriteMode.CREATE_ONLY)
        assertNotNull(runCatching { connection.writeFrom(remote, changed, MdbxRemoteWriteMode.CREATE_ONLY) }.exceptionOrNull())
        assertNotNull(runCatching { connection.writeFrom(remote, changed, MdbxRemoteWriteMode.IF_MATCH, "\"synthetic-stale\"") }.exceptionOrNull())
        connection.readTo(remote, received)
        assertArrayEquals(original.readBytes(), received.readBytes())
    }

    suspend fun close() {
        try {
            if (remoteCreated) withContext(Dispatchers.IO) {
                require(root.matches(Regex("monica331-[0-9a-f]{8}(/case-[0-9a-f]{8})?")))
                try { WebDavGateway.buildClient(WebDavCredentials(credentials.getString("username"),
                    credentials.getString("password")), credentials.getString("url"))
                    .delete(credentials.getString("url").trimEnd('/') + "/" + root + "/")
                } catch (error: com.thegrizzlylabs.sardineandroid.impl.SardineException) {
                    if (error.statusCode != 404) throw error
                }
            }
        } finally {
            listOfNotNull(databaseId, peerId).forEach { id ->
                Mdbx2NativeReadSessions.invalidate(id)
                state.delete(id)
            }
            peerId?.let { db.localMdbxDatabaseDao().deleteDatabaseById(it) }
            sourceId?.let { db.mdbxRemoteSourceDao().deleteSourceById(it) }
            context.deleteSharedPreferences(peerPreferences)
            cache.deleteRecursively()
        }
    }

    companion object {
        fun optional(context: Context, db: PasswordDatabase, security: SecurityManager): MdbxWebDavCloudFixture? {
            val name = InstrumentationRegistry.getArguments().getString("mdbxCloudConfig") ?: return null
            require(name.matches(Regex("mdbx-cloud-test-[0-9a-f-]{36}\\.json")))
            val file = File(context.filesDir, name)
            return MdbxWebDavCloudFixture(context, db, security, JSONObject(file.readText()))
        }
    }
}
