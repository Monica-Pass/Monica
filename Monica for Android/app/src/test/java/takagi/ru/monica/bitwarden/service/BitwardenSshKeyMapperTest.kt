package takagi.ru.monica.bitwarden.service

import java.util.Date
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import takagi.ru.monica.bitwarden.mapper.BitwardenSshKeyMapper
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.LOGIN_TYPE_SSH_KEY
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.data.model.SshKeyDataCodec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BitwardenSshKeyMapperTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val account = "32500000-0000-4000-8000-000000000055"
    private val archive = "2026-11-04T01:02:03Z"
    private val revision = "2026-11-04T00:00:00Z"
    private val data = SshKeyData(
        algorithm = "ED25519", keySize = 256,
        publicKeyOpenSsh = "ssh-ed25519 AAAAsynthetic Synthetic 用户 😀",
        privateKeyOpenSsh = "-----BEGIN OPENSSH PRIVATE KEY-----\nSYNTHETIC-ONLY\n-----END OPENSSH PRIVATE KEY-----\n",
        fingerprintSha256 = "SHA256:synthetic-fingerprint", comment = " user \n comment 😀 ",
        additionalFields = mapOf("future" to JsonObject(mapOf("keep" to JsonPrimitive("synthetic-sensitive-value"))))
    )
    private fun key(seed: Int = 1) = SymmetricCryptoKey(ByteArray(32) { (it + seed).toByte() }, ByteArray(32) { (it + seed + 32).toByte() })
    private fun entry(type: Int = 5) = PasswordEntry(
        title = "Synthetic SSH 😀", notes = " note \n ", website = "", username = "", password = "",
        loginType = LOGIN_TYPE_SSH_KEY, sshKeyData = SshKeyDataCodec.encode(data), bitwardenCipherType = type,
        bitwardenVaultId = 1L, bitwardenCipherId = "synthetic-ssh", bitwardenFolderId = "folder",
        bitwardenRevisionDate = revision, isFavorite = true, isArchived = true, archivedAt = Date(1793715723000L)
    )
    private fun enc(value: String, k: SymmetricCryptoKey) = BitwardenCrypto.encryptString(value, k)
    private fun fields(k: SymmetricCryptoKey) = listOf(
        CipherFieldApiData(enc("duplicate", k), enc("one", k), 0),
        CipherFieldApiData(enc("duplicate", k), enc("two", k), 1),
        CipherFieldApiData(enc("future-linked", k), enc("opaque", k), 3, 101)
    )

    @Test fun nativeCreateAndUpdateUseRealEncryptedRetrofitBodiesAndReadBackAllMaterial(): Unit = runBlocking {
        val k = key()
        try {
            MockWebServer().use { server ->
                repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":"synthetic-ssh","type":5,"revisionDate":"$revision"}""")) }
                val api = Retrofit.Builder().baseUrl(server.url("/"))
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BitwardenVaultApi::class.java)
                val originalFields = fields(k)
                val create = BitwardenCipherUploadProtocol.create(BitwardenSshKeyMapper.create(entry(), k, originalFields, archive), account, k)
                val update = BitwardenCipherUploadProtocol.update(BitwardenSshKeyMapper.update(entry(), k, originalFields, archive).copy(lastKnownRevisionDate = revision), account, k)
                assertTrue(api.createCipher("Bearer synthetic", create).isSuccessful)
                assertTrue(api.updateCipher("Bearer synthetic", "synthetic-ssh", update).isSuccessful)
                repeat(2) { index ->
                    val request = server.takeRequest()
                    val raw = request.body.readUtf8()
                    val body = json.parseToJsonElement(raw).jsonObject
                    assertEquals("5", body.getValue("type").jsonPrimitive.content)
                    assertEquals(account, body.getValue("encryptedFor").jsonPrimitive.content)
                    assertEquals(archive, body.getValue("archivedDate").jsonPrimitive.content)
                    assertEquals("folder", body.getValue("folderId").jsonPrimitive.content)
                    assertEquals("true", body.getValue("favorite").jsonPrimitive.content)
                    assertEquals(if (index == 0) "/ciphers" else "/ciphers/synthetic-ssh", request.path)
                    if (index == 1) assertEquals(revision, body.getValue("lastKnownRevisionDate").jsonPrimitive.content)
                    val cipher = json.decodeFromJsonElement(CipherApiResponse.serializer(), body)
                    assertNull(cipher.login)
                    assertEquals(entry().title, BitwardenCrypto.decryptToString(requireNotNull(cipher.name), k))
                    assertEquals(entry().notes, BitwardenCrypto.decryptToString(requireNotNull(cipher.notes), k))
                    val decoded = BitwardenSshKeyMapper.decodeNative(requireNotNull(cipher.sshKey), cipher.fields, k)
                    assertEquals(data, decoded)
                    assertEquals(originalFields, cipher.fields!!.take(originalFields.size))
                    for (secret in listOf(data.privateKeyOpenSsh, data.publicKeyOpenSsh, data.fingerprintSha256, "synthetic-sensitive-value", "duplicate")) {
                        assertFalse("Plaintext SSH content entered HTTP", raw.contains(secret))
                    }
                }
            }
        } finally { k.clear() }
    }

    @Test fun nativeItemEncryptionCannotBeOpenedWithItsParentUserKey() {
        val itemKey = key(71)
        val userKey = key(1)
        try {
            val request = BitwardenSshKeyMapper.create(entry(), itemKey, fields(itemKey), null)
            assertEquals(data, BitwardenSshKeyMapper.decodeNative(requireNotNull(request.sshKey), request.fields, itemKey))
            assertTrue(runCatching { BitwardenSshKeyMapper.decodeNative(requireNotNull(request.sshKey), request.fields, userKey) }.isFailure)
        } finally { itemKey.clear(); userKey.clear() }
    }

    @Test fun existingCompatibilityLoginStaysTypeOneAndPreservesAllEncryptedCustomFields() {
        val k = key()
        try {
            val sourceFields = fields(k)
            val request = BitwardenSshKeyMapper.update(entry(1), k, sourceFields, archive)
            assertEquals(1, request.type)
            assertNull(request.sshKey)
            assertNotNull(request.login)
            assertEquals(sourceFields, request.fields)
            assertEquals(archive, request.archivedDate)
        } finally { k.clear() }
    }

    @Test fun nativeMetadataReplacedOnceWithoutTouchingUnknownOrRepeatedFieldCiphertext() {
        val k = key()
        try {
            val originalFields = fields(k)
            val first = BitwardenSshKeyMapper.create(entry(), k, originalFields, null)
            val editedData = data.copy(comment = "edited comment", keySize = 384)
            val update = BitwardenSshKeyMapper.update(entry().copy(sshKeyData = SshKeyDataCodec.encode(editedData)), k, first.fields, null)
            assertEquals(originalFields, update.fields!!.take(originalFields.size))
            assertEquals(1, update.fields!!.count { BitwardenCrypto.decryptToString(requireNotNull(it.name), k) == BitwardenSshKeyMapper.METADATA_FIELD })
            assertEquals(editedData, BitwardenSshKeyMapper.decodeNative(requireNotNull(update.sshKey), update.fields, k))
        } finally { k.clear() }
    }

    @Test fun nativeKeysOverrideSpoofedMetadataAndAmbiguousMetadataRejects() {
        val k = key()
        try {
            val request = BitwardenSshKeyMapper.create(entry(), k, null, null)
            val metadata = request.fields!!.single()
            val spoofed = metadata.copy(value = enc("""{"algorithm":"RSA","publicKeyOpenSsh":"spoof-public","privateKeyOpenSsh":"spoof-private"}""", k))
            val decoded = BitwardenSshKeyMapper.decodeNative(requireNotNull(request.sshKey), listOf(spoofed), k)
            assertEquals(data.publicKeyOpenSsh, decoded.publicKeyOpenSsh)
            assertEquals(data.privateKeyOpenSsh, decoded.privateKeyOpenSsh)
            assertTrue(runCatching { BitwardenSshKeyMapper.decodeNative(requireNotNull(request.sshKey), listOf(metadata, metadata), k) }.isFailure)
            assertTrue(runCatching { BitwardenSshKeyMapper.decodeNative(requireNotNull(request.sshKey), listOf(metadata.copy(type = 0)), k) }.isFailure)
        } finally { k.clear() }
    }

    @Test fun unreadableEmptyOrDifferentSourceTypeNeverProducesRequest() {
        val k = key()
        try {
            for (raw in listOf("broken-json", "{}", """{"algorithm":"RSA","fingerprintSha256":"alone"}""")) {
                assertTrue(runCatching { BitwardenSshKeyMapper.create(entry().copy(sshKeyData = raw), k, null, null) }.isFailure)
            }
            assertTrue(runCatching { BitwardenSshKeyMapper.update(entry(4), k, null, null) }.isFailure)
            assertTrue(runCatching { BitwardenSshKeyMapper.create(entry().copy(loginType = "PASSWORD"), k, null, null) }.isFailure)
        } finally { k.clear() }
    }

    @Test fun legacyMetadataWithoutSidecarAndPrivateOnlyNativeKeyRemainReadable() {
        val k = key()
        try {
            val cipher = CipherSshKeyApiData(privateKey = enc(data.privateKeyOpenSsh, k))
            val oldFields = listOf(
                CipherFieldApiData(enc("monica_ssh_comment", k), enc(data.comment, k)),
                CipherFieldApiData(enc("monica_ssh_key_size", k), enc("4096", k)),
                CipherFieldApiData(enc("monica_ssh_algorithm", k), enc("RSA", k))
            )
            val decoded = BitwardenSshKeyMapper.decodeNative(cipher, oldFields, k)
            assertEquals(data.privateKeyOpenSsh, decoded.privateKeyOpenSsh)
            assertEquals("", decoded.publicKeyOpenSsh)
            assertEquals(data.comment, decoded.comment)
            assertEquals(4096, decoded.keySize)
            assertEquals("RSA", decoded.algorithm)
        } finally { k.clear() }
    }
}
