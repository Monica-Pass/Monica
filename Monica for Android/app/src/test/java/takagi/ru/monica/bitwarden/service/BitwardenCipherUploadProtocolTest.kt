package takagi.ru.monica.bitwarden.service

import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.data.bitwarden.BitwardenVault

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BitwardenCipherUploadProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val account = "32500000-0000-4000-8000-000000000099"
    private val urls = listOf("http://192.0.2.1:8080/login?x=A%20B#section", "https://[2001:db8::1]:8443/路径?q=😀", "androidapp://test.synthetic")
    private fun key(seed: Int = 1) = SymmetricCryptoKey(ByteArray(32) { (it + seed).toByte() }, ByteArray(32) { (it + seed + 32).toByte() })
    private fun login(k: SymmetricCryptoKey) = CipherLoginApiData(uris = urls.mapIndexed { index, url ->
        CipherUriApiData(uri = BitwardenCrypto.encryptString(url, k), match = index)
    })
    private fun hash(raw: String) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)))
    private fun verifies(login: CipherLoginApiData, k: SymmetricCryptoKey, expected: List<String> = urls) {
        assertEquals(expected, login.uris!!.map { BitwardenCrypto.decryptToString(it.uri!!, k) })
        for ((index, item) in login.uris!!.withIndex()) {
            assertEquals(hash(expected[index]), BitwardenCrypto.decryptToString(item.uriChecksum!!, k))
            assertTrue(item.uriChecksum!!.startsWith("2."))
        }
    }
    private fun api(server: MockWebServer) = Retrofit.Builder().baseUrl(server.url("/"))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BitwardenVaultApi::class.java)
    private fun expectRejected(block: () -> Unit) {
        try { block(); fail("Unsafe request accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} catch (_: SecurityException) {}
    }

    @Test fun createAndUpdateUseRealRetrofitBodiesWithEncryptedUriChecksums(): Unit = runBlocking {
        val k = key()
        try {
            MockWebServer().use { server ->
                repeat(2) { server.enqueue(MockResponse().setBody("""{"Id":"cipher","Type":1,"Name":"encrypted"}""").setHeader("Content-Type", "application/json")) }
                val create = BitwardenCipherUploadProtocol.create(CipherCreateRequest(type = 1, name = BitwardenCrypto.encryptString("Synthetic", k), login = login(k)), account, k)
                val update = BitwardenCipherUploadProtocol.update(CipherUpdateRequest(type = 1, name = create.name, login = create.login, lastKnownRevisionDate = "2026-11-03T00:00:00Z"), account, k)
                assertTrue(api(server).createCipher("Bearer synthetic", create).isSuccessful)
                assertTrue(api(server).updateCipher("Bearer synthetic", "cipher", update).isSuccessful)
                for (name in listOf("create", "update")) {
                    val captured = server.takeRequest()
                    val body = captured.body.readUtf8()
                    val document = json.parseToJsonElement(body).jsonObject
                    assertEquals(account, document["encryptedFor"]!!.jsonPrimitive.content)
                    val decoded = json.decodeFromJsonElement<CipherLoginApiData>(document["login"]!!)
                    verifies(decoded, k)
                    assertEquals(listOf(0, 1, 2), decoded.uris!!.map { it.match })
                    assertFalse(body.contains(urls[0]))
                    assertEquals(if (name == "create") "/ciphers" else "/ciphers/cipher", captured.path)
                    System.getProperty("monica.compat.evidence")?.let { folder: String ->
                        Files.createDirectories(Paths.get(folder))
                        Files.write(Paths.get(folder, "$name.json"), (body + "\n").toByteArray(Charsets.UTF_8))
                    }
                }
            }
        } finally { k.clear() }
    }

    @Test fun allItemTypesReceiveAccountMetadataWithoutInventingLoginData() {
        val k = key()
        try {
            for (type in 1..5) {
                val create = BitwardenCipherUploadProtocol.create(CipherCreateRequest(type = type, name = "encrypted"), account, k)
                val update = BitwardenCipherUploadProtocol.update(CipherUpdateRequest(type = type, name = "encrypted"), account, k)
                assertEquals(account, create.encryptedFor); assertEquals(account, update.encryptedFor)
                assertNull(create.login); assertNull(update.login)
            }
        } finally { k.clear() }
    }

    @Test fun unchangedChecksumsAndCiphertextRemainByteForByte() {
        val k = key()
        try {
            val first = BitwardenCipherUploadProtocol.create(CipherCreateRequest(type = 1, name = "encrypted", login = login(k)), account, k)
            val again = BitwardenCipherUploadProtocol.create(first, account, k)
            assertEquals(first, again)
            assertEquals(login(k).uris!!.map { it.match }, again.login!!.uris!!.map { it.match })
        } finally { k.clear() }
    }

    @Test fun invalidChecksumIsRepairedWithoutChangingUriCiphertext() {
        val k = key()
        try {
            val input = login(k).copy(uris = login(k).uris!!.map { it.copy(uriChecksum = BitwardenCrypto.encryptString("wrong", k)) })
            val output = BitwardenCipherUploadProtocol.create(CipherCreateRequest(type = 1, name = "encrypted", login = input), account, k)
            assertEquals(input.uris!!.map { it.uri }, output.login!!.uris!!.map { it.uri })
            verifies(output.login!!, k)
        } finally { k.clear() }
    }

    @Test fun exactPlaintextAndDuplicateUrisKeepTheirOwnChecksumsAndRules() {
        val k = key()
        try {
            val values = listOf("https://EXAMPLE.test/A%2fb?q=1", "https://example.test/A%2fb?q=1", "https://EXAMPLE.test/A%2fb?q=1")
            val input = CipherLoginApiData(uris = values.mapIndexed { index, value -> CipherUriApiData(BitwardenCrypto.encryptString(value, k), index) })
            val output = BitwardenCipherUploadProtocol.create(CipherCreateRequest(type = 1, name = "encrypted", login = input), account, k)
            verifies(output.login!!, k, values)
            assertEquals(listOf(0, 1, 2), output.login!!.uris!!.map { it.match })
        } finally { k.clear() }
    }

    @Test fun updatesRetainOriginalMatchRulesOnlyForRemainingExactUris() {
        val k = key()
        try {
            val source = login(k)
            val changed = CipherLoginApiData(uris = listOf(source.uris!![1].copy(match = null), CipherUriApiData(BitwardenCrypto.encryptString("https://new.test", k)), source.uris!![0].copy(match = 5)))
            val result = BitwardenCipherUploadProtocol.update(CipherUpdateRequest(type = 1, name = "encrypted", login = changed, organizationId = "team", key = "wrapped", collectionIds = listOf("collection")), account, k, source)
            assertEquals(listOf(1, null, 5), result.login!!.uris!!.map { it.match })
            assertEquals("team", result.organizationId); assertEquals("wrapped", result.key); assertEquals(listOf("collection"), result.collectionIds)
            verifies(result.login!!, k, listOf(urls[1], "https://new.test", urls[0]))
        } finally { k.clear() }
    }

    @Test fun corruptOrWrongKeyUriNeverProducesAnUpload() {
        val k = key(); val itemKey = key(80)
        try {
            val request = CipherCreateRequest(type = 1, name = "encrypted", login = login(itemKey))
            expectRejected { BitwardenCipherUploadProtocol.create(request, account, k) }
            val correct = BitwardenCipherUploadProtocol.create(request, account, itemKey)
            verifies(correct.login!!, itemKey)
            expectRejected { BitwardenCrypto.decryptToString(correct.login!!.uris!![0].uriChecksum!!, k) }
        } finally { k.clear(); itemKey.clear() }
    }

    @Test fun nullUriAndAbsentLoginKeepTheirShape() {
        val k = key()
        try {
            val input = CipherLoginApiData(uris = listOf(CipherUriApiData(uri = null, match = 5)))
            val output = BitwardenCipherUploadProtocol.create(CipherCreateRequest(type = 1, name = "encrypted", login = input), account, k)
            assertEquals(input, output.login)
        } finally { k.clear() }
    }

    @Test fun missingAndMalformedAccountIdsAreNeverReplacedByEmailOrOrganization() {
        for (value in listOf(null, "", "test@example.test", "organization", "32500000-0-4-8-99", " $account")) expectRejected { BitwardenCipherUploadProtocol.requireUserId(value) }
    }

    @Test fun cachedProfileIdentityDoesNotNeedAnotherNetworkRequest(): Unit = runBlocking {
        MockWebServer().use { server ->
            val vault = BitwardenVault(email = "synthetic@example.test", userId = account)
            assertEquals(account, BitwardenCipherUploadProtocol.userId(vault, api(server), "synthetic"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun missingCachedIdentityUsesAuthenticatedProfileWithMatchingEmail(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Id":"$account","Email":"SYNTHETIC@example.test"}""").setHeader("Content-Type", "application/json"))
            val vault = BitwardenVault(email = "synthetic@example.test")
            assertEquals(account, BitwardenCipherUploadProtocol.userId(vault, api(server), "synthetic"))
            val request = server.takeRequest()
            assertEquals("/accounts/profile", request.path); assertEquals("Bearer synthetic", request.getHeader("Authorization"))
        }
    }

    @Test fun changedProfileAccountAndUnauthorizedProfilePreventUpload(): Unit = runBlocking {
        for (response in listOf(MockResponse().setBody("""{"Id":"$account","Email":"other@example.test"}"""), MockResponse().setResponseCode(401))) {
            MockWebServer().use { server ->
                server.enqueue(response.setHeader("Content-Type", "application/json"))
                try { BitwardenCipherUploadProtocol.userId(BitwardenVault(email = "synthetic@example.test"), api(server), "synthetic"); fail("Unsafe profile accepted") }
                catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} catch (_: SecurityException) {}
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun rawRequestsKeepUnknownFieldsAndServerReadbackIgnoresRequestOnlyAccount() {
        val original = json.parseToJsonElement("""{"id":"cipher","type":1,"folderId":"old","name":"ciphertext","login":{"uris":[{"uri":"ciphertext","uriChecksum":"checksum","futureUri":42}],"futureLogin":"keep"},"futureCipher":{"keep":true},"revisionDate":"old"}""").jsonObject
        val request = BitwardenCipherUploadProtocol.documentAccount(original, account)
        assertEquals(original, JsonObject(request - "encryptedFor"))
        assertTrue(PasskeyCipherUpdate.confirms(request, original))
        val folder = BitwardenCipherUploadProtocol.documentAccount(folderCopyDocument(original, "new"), account)
        assertTrue(folderCopyPreserved(folder, JsonObject(folder - "encryptedFor")))
    }

    @Test fun updatingLoginPreservesExistingEncryptedPasswordHistory() {
        val source = json.decodeFromString<CipherApiResponse>("""{"id":"cipher","type":1,"name":"old-encrypted-name","revisionDate":"2026-11-04T00:00:00Z","passwordHistory":[{"password":"2.encrypted-history","lastUsedDate":"2026-11-01T00:00:00Z","futureHistoryField":"keep"}]}""")
        val request = BitwardenCipherOwnership.preserve(
            CipherUpdateRequest(type = 1, name = "new-encrypted-name"), source
        )
        val body = json.encodeToJsonElement(request).jsonObject
        val history = body["passwordHistory"]?.jsonArray
        assertNotNull("A normal cipher update must not clear the server password history", history)
        assertEquals("2.encrypted-history", history!![0].jsonObject["password"]?.jsonPrimitive?.content)
        assertEquals("keep", history[0].jsonObject["futureHistoryField"]?.jsonPrimitive?.content)
        assertEquals("2026-11-04T00:00:00Z", body["lastKnownRevisionDate"]?.jsonPrimitive?.content)
    }
}
