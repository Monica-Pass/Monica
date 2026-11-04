package takagi.ru.monica.bitwarden

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.service.*
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Real HTTP -> key chain -> sync processors -> Room, with only synthetic accounts and content. */
@RunWith(AndroidJUnit4::class)
class BitwardenOrganizationSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = PasswordDatabase.getDatabase(context)

    @Test fun importsPersonalAndTwoTeamsWithAllSupportedCipherTypesAndAttachmentMetadata(): Unit = runBlocking {
        scenario {
            val result = sync()
            assertTrue(result.toString(), result is SyncResult.Success)
            for (id in listOf("personal", "team-login", "team-legacy", "read-only", "ssh")) {
                assertNotNull(id, db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, id))
            }
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "team-login"))
            assertEquals("team-password", security.decryptData(entry.password))
            assertEquals("Synthetic team login", entry.title)
            for (id in listOf("note", "card", "identity")) {
                assertNotNull(id, db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, id))
            }
            val files = AttachmentContainer.facade(context).list(AttachmentOwner.password(entry.id))
            assertEquals("synthetic-team.txt", files.single().fileName)
            val key = BitwardenAttachmentKeyResolver.resolve(api(), token, "team-login", userKey)
            try { assertEquals(itemKey, key) } finally { key.clear() }
            assertTrue(sync() is SyncResult.Success)
            assertEquals(5, db.passwordEntryDao().getBitwardenEntriesCount(vault.id))
        }
    }

    @Test fun editsUseOrganizationItemKeyAndPreserveCollectionsWhileReadonlyAndHttpFailuresStayPending(): Unit = runBlocking {
        scenario {
            assertTrue(sync() is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "team-login"))
            db.passwordEntryDao().update(entry.copy(password = security.encryptData("updated-team-password"), bitwardenLocalModified = true))
            val note = requireNotNull(db.secureItemDao().getByBitwardenCipherIdInVault(vault.id, "note"))
            db.secureItemDao().update(note.copy(title = "Updated team note", bitwardenLocalModified = true))
            val result = service.uploadModifiedEntries(vault, token, userKey) as UploadResult.Success
            assertEquals(0, result.failed)
            assertEquals(2, result.uploaded)
            val uploaded = updates.getValue("team-login")
            assertEquals("team-a", uploaded.organizationId)
            assertEquals(listOf("collection-a", "collection-b"), uploaded.collectionIds)
            assertEquals(remote.getValue("team-login").key, uploaded.key)
            assertEquals("updated-team-password", BitwardenCrypto.decryptToString(requireNotNull(uploaded.login?.password), itemKey))
            assertEquals(listOf("team-password"), BitwardenPasswordHistory.decode(uploaded.passwordHistory, itemKey).map { it.password })
            assertNotNull(runCatching { BitwardenCrypto.decryptToString(requireNotNull(uploaded.login?.password), userKey) }.exceptionOrNull())
            assertEquals("Updated team note", BitwardenCrypto.decryptToString(updates.getValue("note").name, orgKey))

            val readOnly = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "read-only"))
            db.passwordEntryDao().update(readOnly.copy(title = "Local pending title", bitwardenLocalModified = true))
            assertTrue((service.uploadModifiedEntries(vault, token, userKey) as UploadResult.Success).failed > 0)
            assertFalse(updates.containsKey("read-only"))
            assertTrue(requireNotNull(db.passwordEntryDao().getPasswordEntryById(readOnly.id)).bitwardenLocalModified)
            for (code in listOf(403, 409)) {
                val latest = requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id))
                db.passwordEntryDao().update(latest.copy(title = "Retry after $code", bitwardenLocalModified = true))
                updateStatus = code
                assertTrue((service.uploadModifiedEntries(vault, token, userKey) as UploadResult.Success).failed > 0)
                assertTrue(requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id)).bitwardenLocalModified)
            }
        }
    }

    @Test fun missingOrganizationKeysPreserveCachedDataAndPersonalSyncThenRecoverWithoutDuplicates(): Unit = runBlocking {
        scenario {
            assertTrue(sync() is SyncResult.Success)
            val entry = requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "team-login"))
            val valid = profile
            profile = profile.copy(organizations = profile.organizations.filterNot { it.id == "team-a" })
            remote["personal"] = remote.getValue("personal").copy(name = enc("Personal still syncs", userKey), revisionDate = revision2)
            assertTrue(sync() is SyncResult.Error)
            assertEquals("team-password", security.decryptData(requireNotNull(db.passwordEntryDao().getPasswordEntryById(entry.id)).password))
            assertEquals("Personal still syncs", requireNotNull(db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "personal")).title)
            profile = valid
            assertTrue(sync() is SyncResult.Success)
            assertEquals(entry.id, db.passwordEntryDao().getByBitwardenCipherIdInVault(vault.id, "team-login")?.id)
            remote.remove("team-login")
            assertTrue(sync() is SyncResult.Success)
            assertNull(db.passwordEntryDao().getPasswordEntryById(entry.id))
        }
    }

    private suspend fun scenario(block: suspend Fixture.() -> Unit) {
        val unlocked = SessionManager.isUnlocked.value
        val mdkCache = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val originalMdk = mdkCache.get(null)
        val syntheticMdk = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        mdkCache.set(null, syntheticMdk)
        SessionManager.markUnlocked()
        try {
            MockWebServer().use { server ->
                val fixture = Fixture(server)
                try { fixture.initialize(); fixture.block() }
                finally { fixture.close() }
            }
        } finally {
            if (!unlocked) SessionManager.markLocked()
            mdkCache.set(null, originalMdk)
            syntheticMdk.fill(0)
        }
    }

    private inner class Fixture(private val server: MockWebServer) {
        val token = "synthetic-organization-token"
        val userKey = key(1)
        val orgKey = key(71)
        val itemKey = key(151)
        private val legacyOrgKey = key(211)
        val security = SecurityManager(context)
        val service = BitwardenSyncService(context)
        val remote = ConcurrentHashMap<String, CipherApiResponse>()
        val updates = ConcurrentHashMap<String, CipherUpdateRequest>()
        var updateStatus = 200
        val revision2 = "2026-10-17T02:00:00.000Z"
        private val revision1 = "2026-10-17T01:00:00.000Z"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        lateinit var vault: BitwardenVault
        lateinit var profile: ProfileResponse
        private val repository by lazy { takagi.ru.monica.bitwarden.repository.BitwardenRepository.getInstance(context) }
        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String): MutableMap<Long, T> =
            repository.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(repository) as MutableMap<Long, T>
        fun api() = BitwardenApiFactory.createVaultApi(vault.apiUrl)

        suspend fun initialize() {
            val uuid = UUID.randomUUID().toString()
            val endpoint = server.url("/").toString()
            val id = db.bitwardenVaultDao().insert(BitwardenVault(email = "$uuid@example.invalid", userId = uuid, accountKey = uuid,
                serverUrl = endpoint, identityUrl = endpoint, apiUrl = endpoint))
            vault = requireNotNull(db.bitwardenVaultDao().getVaultById(id))
            // Imported login history requires the same authenticated MDK and vault session as production.
            cache<SymmetricCryptoKey>("symmetricKeyCache")[id] = userKey
            cache<String>("accessTokenCache")[id] = token
            assertTrue(security.encryptData("synthetic-history-session-check").startsWith("MDK|"))
            val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            fun org(id: String, key: SymmetricCryptoKey, type: Int): OrganizationResponse {
                val digest = if (type == 3) "SHA-256" else "SHA-1"
                val bytes = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
                    init(Cipher.ENCRYPT_MODE, rsa.public, OAEPParameterSpec(digest, "MGF1", MGF1ParameterSpec(digest), PSource.PSpecified.DEFAULT))
                    doFinal(key.encKey + key.macKey)
                }
                return OrganizationResponse(id, "Synthetic $id", "$type.${Base64.getEncoder().encodeToString(bytes)}")
            }
            profile = ProfileResponse(id = uuid, email = vault.email, securityStamp = "synthetic-organization-stamp",
                privateKey = BitwardenCrypto.encrypt(rsa.private.encoded, userKey),
                organizations = listOf(org("team-a", orgKey, 3), org("team-b", legacyOrgKey, 4)))
            fun login(id: String, owner: String?, key: SymmetricCryptoKey, password: String) = CipherApiResponse(
                id = id, organizationId = owner, name = enc("Synthetic $id", key), edit = true,
                revisionDate = revision1, login = CipherLoginApiData(username = enc("synthetic@example.invalid", key), password = enc(password, key)))
            remote["personal"] = login("personal", null, userKey, "personal-password")
            remote["team-login"] = login("team-login", "team-a", itemKey, "team-password").copy(
                name = enc("Synthetic team login", itemKey), collectionIds = listOf("collection-a", "collection-b"),
                key = BitwardenCrypto.encrypt(itemKey.encKey + itemKey.macKey, orgKey),
                attachments = listOf(CipherAttachmentApiData(id = "file", fileName = enc("synthetic-team.txt", itemKey),
                    size = "42", key = BitwardenCrypto.encrypt(key(33).encKey + key(33).macKey, itemKey))))
            remote["team-legacy"] = login("team-legacy", "team-b", legacyOrgKey, "legacy-password")
            remote["read-only"] = login("read-only", "team-a", orgKey, "read-only-password").copy(edit = false)
            remote["note"] = CipherApiResponse(id = "note", organizationId = "team-a", type = 2, edit = true,
                name = enc("Synthetic note", orgKey), notes = enc("Synthetic note body", orgKey), revisionDate = revision1,
                secureNote = CipherSecureNoteApiData())
            remote["card"] = CipherApiResponse(id = "card", organizationId = "team-a", type = 3, edit = true,
                name = enc("Synthetic card", orgKey), revisionDate = revision1,
                card = CipherCardApiData(cardholderName = enc("Test Holder", orgKey), number = enc("4111111111111111", orgKey)))
            remote["identity"] = CipherApiResponse(id = "identity", organizationId = "team-a", type = 4, edit = true,
                name = enc("Synthetic identity", orgKey), revisionDate = revision1,
                identity = CipherIdentityApiData(firstName = enc("Test", orgKey), lastName = enc("Person", orgKey)))
            remote["ssh"] = CipherApiResponse(id = "ssh", organizationId = "team-a", type = 5, edit = true,
                name = enc("Synthetic ssh", orgKey), revisionDate = revision1,
                sshKey = CipherSshKeyApiData(publicKey = enc("ssh-ed25519 synthetic", orgKey), privateKey = enc("synthetic-private-key", orgKey)))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401)
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path == "/sync") return ok(SyncResponse(profile = profile, ciphers = remote.values.toList()))
                    if (path == "/accounts/profile") return ok(profile)
                    val id = path.removePrefix("/ciphers/")
                    val source = remote[id] ?: return MockResponse().setResponseCode(404)
                    if (request.method == "GET") return ok(source)
                    if (request.method == "PUT") {
                        if (updateStatus != 200) return MockResponse().setResponseCode(updateStatus)
                        val update = json.decodeFromString<CipherUpdateRequest>(request.body.readUtf8())
                        if (update.organizationId != source.organizationId || update.key != source.key ||
                            update.collectionIds != source.collectionIds || update.lastKnownRevisionDate != source.revisionDate) {
                            return MockResponse().setResponseCode(400)
                        }
                        updates[id] = update
                        val next = source.copy(name = update.name, notes = update.notes, login = update.login,
                            card = update.card, identity = update.identity, fields = update.fields,
                            passwordHistory = update.passwordHistory, revisionDate = revision2)
                        remote[id] = next
                        return ok(next)
                    }
                    return MockResponse().setResponseCode(405)
                }
            }
        }
        private inline fun <reified T> ok(value: T) = MockResponse().setHeader("Content-Type", "application/json").setBody(json.encodeToString(value))
        suspend fun sync() = service.fullSync(vault, token, userKey)
        suspend fun close() {
            if (::vault.isInitialized) {
                cache<SymmetricCryptoKey>("symmetricKeyCache").remove(vault.id)
                cache<String>("accessTokenCache").remove(vault.id)
                db.passwordEntryDao().deleteAllByBitwardenVaultId(vault.id)
                db.secureItemDao().deleteAllByBitwardenVaultId(vault.id)
                db.passkeyDao().deleteAllByBitwardenVaultId(vault.id)
                db.bitwardenVaultDao().deleteById(vault.id)
            }
            userKey.clear(); orgKey.clear(); itemKey.clear(); legacyOrgKey.clear()
        }
    }
    private fun key(offset: Int) = SymmetricCryptoKey(ByteArray(32) { (it + offset).toByte() }, ByteArray(32) { (it + offset + 32).toByte() })
    private fun enc(value: String, key: SymmetricCryptoKey) = BitwardenCrypto.encryptString(value, key)
}
