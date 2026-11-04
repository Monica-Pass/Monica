package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.bitwarden.api.CipherCreateRequest
import takagi.ru.monica.bitwarden.api.CipherUpdateRequest
import takagi.ru.monica.bitwarden.api.CipherUriApiData
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BitwardenCipherProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val userId = "32500000-0000-4000-8000-000000000099"

    @Test fun updateModelRetainsAuthenticatedEncryptionAccount() {
        val raw = """{"type":1,"name":"synthetic","encryptedFor":"$userId"}"""
        val outgoing = json.encodeToString(json.decodeFromString<CipherUpdateRequest>(raw))
        assertEquals(userId, json.parseToJsonElement(outgoing).jsonObject["encryptedFor"]?.jsonPrimitive?.content)
    }

    @Test fun createModelRetainsAuthenticatedEncryptionAccount() {
        val raw = """{"type":2,"name":"synthetic","encryptedFor":"$userId"}"""
        val outgoing = json.encodeToString(json.decodeFromString<CipherCreateRequest>(raw))
        assertEquals(userId, json.parseToJsonElement(outgoing).jsonObject["encryptedFor"]?.jsonPrimitive?.content)
    }

    @Test fun uriModelRetainsOfficialEncryptedChecksumAndMatchRule() {
        val key = BitwardenCrypto.SymmetricCryptoKey(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        try {
            val encryptedUri = BitwardenCrypto.encryptString("https://synthetic.test/路径?q=a", key)
            val checksum = BitwardenCrypto.encryptString("synthetic checksum", key)
            val raw = """{"uri":"$encryptedUri","uriChecksum":"$checksum","match":3}"""
            val outgoing = json.encodeToString(json.decodeFromString<CipherUriApiData>(raw))
            val result = json.parseToJsonElement(outgoing).jsonObject
            assertEquals(checksum, result["uriChecksum"]?.jsonPrimitive?.content)
            assertEquals("3", result["match"]?.jsonPrimitive?.content)
            assertEquals(encryptedUri, result["uri"]?.jsonPrimitive?.content)
        } finally { key.clear() }
    }
}
