package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.model.PasswordUrlRules

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BitwardenLoginUriRulesTest {
    private val key get() = SymmetricCryptoKey(ByteArray(32) { 4 }, ByteArray(32) { 8 })
    private val account = "32500000-0000-4000-8000-000000000001"

    @Test fun allNativeModesAndRepeatedUrisKeepTheirExactOrderAndSpelling() {
        val key = key
        try {
            val raw = " http://192.0.2.1:8040/Path?q=A%20B#fragment "
            val source = listOf(null, 0, 1, 2, 3, 4, 5).map { CipherUriApiData(BitwardenCrypto.encryptString(raw, key), it) }
            val fields = BitwardenLoginUriRules.fromRemote(emptyList(), source, key)
            val rows = BitwardenLoginUriRules.encrypted(fields.single().value, key)
            assertEquals(source.map { it.match }, rows.map { it.match })
            assertEquals(List(7) { raw }, rows.map { BitwardenCrypto.decryptToString(it.uri!!, key) })
        } finally { key.clear() }
    }

    @Test fun defaultAndEmptyNativeIntentSurviveSharedEditorWhileOrdinaryDefaultsStayCompact() {
        val rows = listOf(PasswordUrlRules.Rule("https://same.invalid"), PasswordUrlRules.Rule("https://same.invalid"))
        assertTrue(PasswordUrlRules.put(emptyList(), rows).isEmpty())
        val native = PasswordUrlRules.put(emptyList(), rows, native = true)
        assertEquals(rows, PasswordUrlRules.read(native))
        val empty = PasswordUrlRules.put(native, emptyList(), native = true)
        assertEquals(emptyList<PasswordUrlRules.Rule>(), PasswordUrlRules.read(empty))
    }

    @Test fun explicitDefaultDoesNotReinstateOldExactMatchAndChecksumUsesExactText() {
        val key = key
        try {
            val raw = "https://synthetic.invalid/Path"
            val value = PasswordUrlRules.encode(listOf(PasswordUrlRules.Rule(raw)), native = true)
            val fields = listOf(CipherFieldApiData(BitwardenCrypto.encryptString(PasswordUrlRules.FIELD, key), BitwardenCrypto.encryptString(value, key), 0))
            val request = CipherUpdateRequest(type = 1, name = BitwardenCrypto.encryptString("test", key),
                login = CipherLoginApiData(uris = BitwardenLoginUriRules.encrypted(value, key)), fields = fields)
            val original = CipherLoginApiData(uris = listOf(CipherUriApiData(BitwardenCrypto.encryptString(raw, key), 3)))
            val result = BitwardenCipherUploadProtocol.update(request, account, key, original).login!!.uris!!.single()
            assertNull(result.match)
            val expected = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()))
            assertEquals(expected, BitwardenCrypto.decryptToString(result.uriChecksum!!, key))
        } finally { key.clear() }
    }

    @Test fun unsupportedNativeRuleOrMetadataCannotBecomeDefault() {
        val key = key
        try {
            assertThrows(IllegalStateException::class.java) {
                BitwardenLoginUriRules.fromRemote(emptyList(), listOf(CipherUriApiData(BitwardenCrypto.encryptString("https://a.invalid", key), 99)), key)
            }
            assertThrows(IllegalArgumentException::class.java) {
                BitwardenLoginUriRules.fromRemote(listOf(MonicaPlainCustomField(PasswordUrlRules.FIELD, "{\"version\":99}", false)), emptyList(), key)
            }
        } finally { key.clear() }
    }

    @Test fun futureMetadataKeysAreRejectedInsteadOfSilentlyDiscarded() {
        val key = key
        try {
            val original = Json.parseToJsonElement(PasswordUrlRules.encode(listOf(
                PasswordUrlRules.Rule("https://native.invalid", PasswordUrlRules.Mode.EXACT)), native = true)).jsonObject
            val futureRoot = JsonObject(original + ("futureVersionHint" to JsonPrimitive(42)))
            val row = original.getValue("urls").jsonArray.single().jsonObject
            val futureRow = JsonObject(original + ("urls" to JsonArray(listOf(JsonObject(row + ("opaque" to JsonPrimitive("retain")))))))
            for (value in listOf(futureRoot, futureRow)) {
                assertNull(PasswordUrlRules.decode(value.toString()))
                assertThrows(IllegalArgumentException::class.java) {
                    BitwardenLoginUriRules.fromRemote(listOf(MonicaPlainCustomField(PasswordUrlRules.FIELD, value.toString(), false)), emptyList(), key)
                }
            }
        } finally { key.clear() }
    }

    @Test fun sourceNativeRulesOverrideOldCompatibleMonicaMetadata() {
        val key = key
        try {
            val old = PasswordUrlRules.encode(listOf(PasswordUrlRules.Rule("old", PasswordUrlRules.Mode.NEVER)))
            val result = BitwardenLoginUriRules.fromRemote(listOf(MonicaPlainCustomField(PasswordUrlRules.FIELD, old, false)),
                listOf(CipherUriApiData(BitwardenCrypto.encryptString("https://native.invalid", key), 1)), key)
            assertEquals(listOf(PasswordUrlRules.Rule("https://native.invalid", PasswordUrlRules.Mode.HOST)), PasswordUrlRules.decode(result.single().value))
        } finally { key.clear() }
    }

    @Test fun changingOneNativeMatchKeepsThatUrisUnknownMetadata() {
        val key = key
        try {
            val uri = BitwardenCrypto.encryptString("https://native.invalid", key)
            val source = buildJsonObject {
                put("id", "cipher"); put("type", 1); put("name", "sealed"); put("revisionDate", "2026-11-04T01:00:00Z")
                put("login", buildJsonObject { put("uris", buildJsonArray { add(buildJsonObject {
                    put("uri", uri); put("match", 3); put("FutureUri", buildJsonObject { put("opaque", 42) })
                }) }) })
            }
            val request = CipherUpdateRequest(type = 1, name = "sealed", lastKnownRevisionDate = "2026-11-04T01:00:00Z",
                login = CipherLoginApiData(uris = listOf(CipherUriApiData(BitwardenCrypto.encryptString("https://native.invalid", key), 5))))
            val result = BitwardenCipherDocument.update(source, request, key)
            val row = result["login"]!!.jsonObject["uris"]!!.jsonArray.single().jsonObject
            assertEquals(JsonPrimitive(5), row["match"])
            assertEquals(JsonPrimitive(42), row["FutureUri"]!!.jsonObject["opaque"])
        } finally { key.clear() }
    }
}
