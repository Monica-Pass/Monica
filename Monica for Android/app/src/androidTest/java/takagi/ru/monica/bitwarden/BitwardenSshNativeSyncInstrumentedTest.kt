package takagi.ru.monica.bitwarden

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.mapper.BitwardenSshKeyMapper
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.bitwarden.service.SyncResult
import takagi.ru.monica.bitwarden.service.UploadResult
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.LOGIN_TYPE_SSH_KEY
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.data.model.SshKeyDataCodec
import takagi.ru.monica.security.SessionManager

/** Actual service HTTP, encrypted key fields and Room cold reload; synthetic data only. */
@RunWith(AndroidJUnit4::class)
class BitwardenSshNativeSyncInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = PasswordDatabase.getDatabase(context)
    private val sourceData = SshKeyData(
        algorithm = "ED25519", keySize = 256,
        publicKeyOpenSsh = "ssh-ed25519 AAAAsynthetic user@synthetic.invalid",
        privateKeyOpenSsh = "-----BEGIN OPENSSH PRIVATE KEY-----\nSYNTHETIC-ONLY\n-----END OPENSSH PRIVATE KEY-----\n",
        fingerprintSha256 = "SHA256:synthetic", comment = "Synthetic comment 😀",
        additionalFields = mapOf("future" to JsonObject(mapOf("preserve" to JsonPrimitive("synthetic-extra"))))
    )

    @Test fun createsNativeSshAndColdReloadsThenEditsAllMaterialAndCustomFields(): Unit = runBlocking {
        scenario {
            val id = db.passwordEntryDao().insert(local(sourceData))
            db.customFieldDao().insert(CustomField(entryId = id, title = "User field", value = "Synthetic value", isProtected = false))
            val created = service.uploadLocalEntries(vault, token, key) as UploadResult.Success
            assertEquals(1, created.uploaded)
            assertEquals(0, created.failed)
            val create = bodies.single()
            assertEquals("5", create.getValue("type").jsonPrimitive.content)
            assertEquals(account, create.getValue("encryptedFor").jsonPrimitive.content)
            val bound = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            assertEquals(5, bound.bitwardenCipherType)
            assertNotNull(bound.bitwardenCipherId)
            assertFalse(bound.bitwardenLocalModified)
            assertFalse(create.toString().contains(sourceData.privateKeyOpenSsh))

            // Delete this fixture's local projection, then read the encrypted server document again.
            db.passwordEntryDao().deleteAllByBitwardenVaultId(vault.id)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val pulled = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "synthetic-ssh"))
            assertEquals(sourceData, SshKeyDataCodec.decode(pulled.sshKeyData))
            assertEquals(5, pulled.bitwardenCipherType)
            assertEquals(LOGIN_TYPE_SSH_KEY, pulled.loginType)
            assertEquals(listOf("User field"), db.customFieldDao().getFieldsByEntryIdSync(pulled.id).map { it.title })

            val changed = sourceData.copy(privateKeyOpenSsh = sourceData.privateKeyOpenSsh + "\n", comment = "edited comment", keySize = 384)
            db.passwordEntryDao().update(pulled.copy(title = "Edited SSH", sshKeyData = SshKeyDataCodec.encode(changed), bitwardenLocalModified = true))
            val uploaded = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(1, uploaded.uploaded)
            assertEquals(0, uploaded.failed)
            val update = bodies.last()
            assertEquals("5", update.getValue("type").jsonPrimitive.content)
            assertEquals(revision1, update.getValue("lastKnownRevisionDate").jsonPrimitive.content)
            val response = remote.getValue("synthetic-ssh")
            assertEquals(changed, BitwardenSshKeyMapper.decodeNative(requireNotNull(response.sshKey), response.fields, key))
            assertEquals("Edited SSH", BitwardenCrypto.decryptToString(requireNotNull(response.name), key))
            db.passwordEntryDao().deleteAllByBitwardenVaultId(vault.id)
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val final = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "synthetic-ssh"))
            assertEquals(changed, SshKeyDataCodec.decode(final.sshKeyData))
            assertEquals(listOf("User field"), db.customFieldDao().getFieldsByEntryIdSync(final.id).map { it.title })
        }
    }

    @Test fun serverFailureKeepsNativeSshChangesPendingAndNeverFallsBackToLogin(): Unit = runBlocking {
        scenario {
            seedNative()
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "synthetic-ssh"))
            val changed = sourceData.copy(privateKeyOpenSsh = "synthetic-edited-private-key")
            db.passwordEntryDao().update(entry.copy(sshKeyData = SshKeyDataCodec.encode(changed), bitwardenLocalModified = true))
            updateStatus = 400
            val result = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(0, result.uploaded)
            assertEquals(1, result.failed)
            assertEquals(1, bodies.size)
            assertEquals("5", bodies.single().getValue("type").jsonPrimitive.content)
            val latest = requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id))
            assertEquals(changed, SshKeyDataCodec.decode(latest.sshKeyData))
            assertTrue(latest.bitwardenLocalModified)
            assertEquals(revision1, latest.bitwardenRevisionDate)
            assertEquals(sourceData, BitwardenSshKeyMapper.decodeNative(requireNotNull(remote.getValue("synthetic-ssh").sshKey), remote.getValue("synthetic-ssh").fields, key))
        }
    }

    @Test fun staleLocalRevisionNeverOverwritesRemoteAndRepeatedPullRetainsOneCompleteConflict(): Unit = runBlocking {
        scenario {
            seedNative()
            assertTrue(service.fullSync(vault, token, key) is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "synthetic-ssh"))
            val changed = sourceData.copy(privateKeyOpenSsh = "synthetic-local-conflict-key")
            db.passwordEntryDao().update(entry.copy(title = "Synthetic local conflict", sshKeyData = SshKeyDataCodec.encode(changed), bitwardenLocalModified = true))
            remote["synthetic-ssh"] = remote.getValue("synthetic-ssh").copy(
                revisionDate = "2026-11-04T02:00:00Z", name = BitwardenCrypto.encryptString("Synthetic newer remote", key))
            val upload = service.uploadModifiedEntries(vault, token, key) as UploadResult.Success
            assertEquals(0, upload.uploaded)
            assertEquals(1, upload.failed)
            assertTrue("No PUT is allowed using the newer GET as the edit baseline", bodies.isEmpty())
            repeat(2) { assertTrue(service.fullSync(vault, token, key) is SyncResult.Success) }
            val local = requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id))
            assertEquals(changed, SshKeyDataCodec.decode(local.sshKeyData))
            assertTrue(local.bitwardenLocalModified)
            assertEquals(revision1, local.bitwardenRevisionDate)
            val conflicts = db.bitwardenConflictBackupDao().getUnresolvedConflictsByVault(vault.id)
            assertEquals(1, conflicts.size)
            assertFalse(conflicts.single().localDataJson.contains("synthetic-local-conflict-key"))
            val security = takagi.ru.monica.security.SecurityManager(context)
            val localJson = security.decryptData(org.json.JSONObject(conflicts.single().localDataJson).getString("sealed"))
            assertTrue(localJson.contains("synthetic-local-conflict-key"))
            val serverJson = security.decryptData(org.json.JSONObject(requireNotNull(conflicts.single().serverDataJson)).getString("sealed"))
            val storedRemote = jsonForTest.decodeFromString<CipherApiResponse>(serverJson)
            assertEquals("Synthetic newer remote", BitwardenCrypto.decryptToString(requireNotNull(storedRemote.name), key))
            assertNotNull(storedRemote.sshKey)
        }
    }

    private val jsonForTest = Json { ignoreUnknownKeys = true }

    private suspend fun scenario(block: suspend Fixture.() -> Unit) {
        val wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            try { fixture.initialize(); fixture.block() }
            finally { fixture.close(); if (!wasUnlocked) SessionManager.markLocked() }
        }
    }

    private inner class Fixture(private val server: MockWebServer) {
        val account = UUID.randomUUID().toString()
        val token = "synthetic-ssh-token"
        val key = SymmetricCryptoKey(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { (it + 33).toByte() })
        val service = BitwardenSyncService(context)
        val remote = ConcurrentHashMap<String, CipherApiResponse>()
        val bodies = CopyOnWriteArrayList<JsonObject>()
        val revision1 = "2026-11-04T00:00:00Z"
        private val revision2 = "2026-11-04T01:00:00Z"
        var updateStatus = 200
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        lateinit var vault: BitwardenVault
        lateinit var profile: ProfileResponse

        suspend fun initialize() {
            val endpoint = server.url("/").toString()
            val id = db.bitwardenVaultDao().insert(BitwardenVault(email = "$account@example.invalid", userId = account,
                accountKey = account, serverUrl = endpoint, identityUrl = endpoint, apiUrl = endpoint))
            vault = requireNotNull(db.bitwardenVaultDao().getVaultById(id))
            profile = ProfileResponse(id = account, email = vault.email, securityStamp = "synthetic-stamp")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401)
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path == "/accounts/profile") return ok(profile)
                    if (path == "/sync") return ok(SyncResponse(profile = profile, ciphers = remote.values.toList()))
                    if (path == "/ciphers" && request.method == "POST") {
                        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        bodies += body
                        if (body["encryptedFor"]?.jsonPrimitive?.content != account || body["type"]?.jsonPrimitive?.content != "5") return MockResponse().setResponseCode(400)
                        val cipher = json.decodeFromJsonElement(CipherApiResponse.serializer(), body).copy(id = "synthetic-ssh", revisionDate = revision1)
                        remote[cipher.id] = cipher
                        return ok(cipher)
                    }
                    val source = remote[path.removePrefix("/ciphers/")] ?: return MockResponse().setResponseCode(404)
                    if (request.method == "GET") return ok(source)
                    if (request.method == "PUT") {
                        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        bodies += body
                        if (updateStatus != 200) return MockResponse().setResponseCode(updateStatus)
                        val update = json.decodeFromJsonElement(CipherUpdateRequest.serializer(), body)
                        if (update.lastKnownRevisionDate != source.revisionDate || update.type != source.type || update.encryptedFor != account) return MockResponse().setResponseCode(409)
                        val next = source.copy(name = update.name, notes = update.notes, fields = update.fields, sshKey = update.sshKey,
                            login = update.login, favorite = update.favorite, archivedDate = update.archivedDate, revisionDate = revision2)
                        remote[source.id] = next
                        return ok(next)
                    }
                    return MockResponse().setResponseCode(405)
                }
            }
        }

        fun local(data: SshKeyData) = PasswordEntry(title = "Synthetic SSH", website = "", username = "", password = "",
            loginType = LOGIN_TYPE_SSH_KEY, sshKeyData = SshKeyDataCodec.encode(data), bitwardenVaultId = vault.id, bitwardenLocalModified = true)

        fun seedNative() {
            val create = BitwardenSshKeyMapper.create(local(sourceData), key, null, null)
            remote["synthetic-ssh"] = CipherApiResponse(id = "synthetic-ssh", type = 5, name = create.name, notes = create.notes,
                sshKey = create.sshKey, fields = create.fields, revisionDate = revision1)
        }

        private inline fun <reified T> ok(value: T) = MockResponse().setHeader("Content-Type", "application/json").setBody(json.encodeToString(value))

        suspend fun close() {
            if (::vault.isInitialized) {
                db.passwordEntryDao().deleteAllByBitwardenVaultId(vault.id)
                db.secureItemDao().deleteAllByBitwardenVaultId(vault.id)
                db.passkeyDao().deleteAllByBitwardenVaultId(vault.id)
                db.bitwardenConflictBackupDao().deleteByVault(vault.id)
                db.bitwardenSyncRawEntryRecordDao().deleteByVault(vault.id)
                db.bitwardenVaultDao().deleteById(vault.id)
            }
            key.clear()
        }
    }
}
