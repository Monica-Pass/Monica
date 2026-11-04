package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.mapper.BitwardenSendMapper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BitwardenSendEditTest {
    private val now = 1_800_000_000_000L
    private val material = ByteArray(16) { (it + 3).toByte() }
    private fun draft() = BitwardenSendDraft(" updated ", "  text\n ", " note ",
        deletionMillis = now + 8 * 86_400_000L, expirationMillis = null)
    private fun source(key: BitwardenCrypto.SymmetricCryptoKey, type: Int = 0): JsonObject {
        val sendKey = BitwardenCrypto.deriveSendKey(material)
        try {
            return Json.parseToJsonElement("""{
                "id":"synthetic-send","accessId":"access-stays","type":$type,
                "key":"${BitwardenCrypto.encrypt(material, key)}",
                "name":"${BitwardenCrypto.encryptString("old", sendKey)}",
                "revisionDate":"2027-01-15T00:00:00Z","accessCount":1,
                "password":"server-password-marker","authType":1,
                "text":{"text":"old-encrypted","hidden":false,"unknownText":42},
                "file":{"id":"file-stays","fileName":"encrypted-name","key":"file-key","size":"123","unknownFile":[1,2]},
                "future":{"nested":"must-stay"},"deletionDate":"2027-01-20T00:00:00Z"
            }""").jsonObject
        } finally { sendKey.clear() }
    }
    private inline fun withVault(block: (BitwardenCrypto.SymmetricCryptoKey) -> Unit) {
        val key = BitwardenCrypto.SymmetricCryptoKey(ByteArray(32) { 7 }, ByteArray(32) { 9 })
        try { block(key) } finally { key.clear() }
    }

    @Test fun editedTextUsesOriginalKeyAndPreservesWhitespace() = withVault { vault ->
        val before = source(vault)
        val after = BitwardenSendMapper.buildUpdatePayload(before, draft(), vault)
        val sendKey = BitwardenCrypto.deriveSendKey(material)
        try {
            assertEquals(draft().title, BitwardenCrypto.decryptToString(BitwardenSendEdit.string(after, "name")!!, sendKey))
            assertEquals(draft().notes, BitwardenCrypto.decryptToString(BitwardenSendEdit.string(after, "notes")!!, sendKey))
            val text = after["text"]!!.jsonObject
            assertEquals(draft().text, BitwardenCrypto.decryptToString(BitwardenSendEdit.string(text, "text")!!, sendKey))
            assertEquals(JsonPrimitive(42), text["unknownText"])
            assertEquals(before["key"], after["key"])
            assertEquals(before["accessId"], after["accessId"])
        } finally { sendKey.clear() }
    }

    @Test fun fileEditRetainsAttachmentKeyIdAndOpaqueFields() = withVault { vault ->
        val before = source(vault, 1)
        val after = BitwardenSendMapper.buildUpdatePayload(before, draft(), vault)
        assertEquals(before["file"], after["file"])
        assertEquals(before["text"], after["text"])
        assertEquals(before["future"], after["future"])
        assertEquals(JsonPrimitive(1), after["type"])
    }

    @Test fun keepPasswordSendsNullAndPreservesAuthentication() = withVault { vault ->
        val after = BitwardenSendMapper.buildUpdatePayload(source(vault), draft(), vault)
        assertEquals(JsonNull, after["password"])
        assertEquals(JsonPrimitive(1), after["authType"])
    }

    @Test fun passwordReplacementHashesExactSpacesWithOriginalMaterial() = withVault { vault ->
        val edited = draft().copy(passwordAction = SendPasswordAction.REPLACE, password = "  new secret  ")
        val after = BitwardenSendMapper.buildUpdatePayload(source(vault), edited, vault)
        assertEquals(BitwardenCrypto.hashSendPassword("  new secret  ", material), BitwardenSendEdit.string(after, "password"))
        assertNotEquals(BitwardenCrypto.hashSendPassword("new secret", material), BitwardenSendEdit.string(after, "password"))
        assertEquals(JsonPrimitive(1), after["authType"])
        assertEquals(JsonNull, after["emails"])
    }

    @Test fun removePasswordDoesNotSubstituteAnEmptyPasswordHash() = withVault { vault ->
        // Cover both the legacy action-only caller and the native editor's explicit NONE choice.
        for (auth in listOf(SendAuthAction.KEEP, SendAuthAction.NONE)) {
            val before = source(vault)
            val after = BitwardenSendMapper.buildUpdatePayload(before,
                draft().copy(passwordAction = SendPasswordAction.REMOVE, authAction = auth), vault)
            assertEquals(JsonNull, after["password"])
            assertNotEquals(BitwardenCrypto.hashSendPassword("", material), BitwardenSendEdit.string(after, "password"))
            // Removal is performed through the dedicated endpoint, not by downgrading this PUT.
            assertEquals(JsonPrimitive(1), after["authType"])
            assertEquals(before["emails"], after["emails"])
        }
    }

    @Test fun emailAndUnknownAuthRemainOpaqueWhenPasswordIsUnchanged() = withVault { vault ->
        for (auth in listOf(0, 99)) {
            val before = JsonObject(source(vault) + mapOf("authType" to JsonPrimitive(auth), "emails" to JsonPrimitive("allowed@synthetic.test")))
            val after = BitwardenSendMapper.buildUpdatePayload(before, draft(), vault)
            assertEquals(before["authType"], after["authType"])
            assertEquals(before["emails"], after["emails"])
        }
    }

    @Test fun datesAreIndependentAndNoExpirySerializesNull() = withVault { vault ->
        val edited = draft().copy(expirationMillis = now + 2 * 86_400_000L)
        val after = BitwardenSendMapper.buildUpdatePayload(source(vault), edited, vault)
        assertEquals(java.time.Instant.ofEpochMilli(edited.deletionMillis).toString(), BitwardenSendEdit.string(after, "deletionDate"))
        assertEquals(java.time.Instant.ofEpochMilli(edited.expirationMillis!!).toString(), BitwardenSendEdit.string(after, "expirationDate"))
        val noExpiry = BitwardenSendMapper.buildUpdatePayload(source(vault), draft(), vault)
        assertEquals(JsonNull, noExpiry["expirationDate"])
    }

    @Test fun upperCaseResponseDoesNotLeaveDuplicateChangedFields() {
        val before = Json.parseToJsonElement("""{"Name":"old","Password":"old","Text":{"Text":"old","Hidden":false,"extra":true}}""").jsonObject
        val after = BitwardenSendEdit.patch(before, draft().copy(disabled = true, hiddenText = true), "encrypted", null, "new", null)
        assertFalse(after.containsKey("Name"))
        assertFalse(after.containsKey("Password"))
        assertFalse(after.containsKey("Text"))
        assertEquals(JsonPrimitive(true), after["disabled"])
        assertEquals(JsonPrimitive(true), after["text"]!!.jsonObject["hidden"])
        assertEquals(JsonPrimitive(true), after["text"]!!.jsonObject["extra"])
    }

    @Test fun concurrentRemoteChangesIncludingOpaqueFieldsRejectBaseline() = withVault { vault ->
        val before = source(vault)
        assertFalse(BitwardenSendEdit.sameBaseline(before, JsonObject(before + ("revisionDate" to JsonPrimitive("new")))))
        assertFalse(BitwardenSendEdit.sameBaseline(before, JsonObject(before + ("future" to JsonPrimitive("changed")))))
        assertTrue(BitwardenSendEdit.sameBaseline(before, JsonObject(before + ("accessCount" to JsonPrimitive(2)))))
    }

    @Test fun invalidDatesLimitsAndEmptyReplacementAreRejected() {
        val invalid = listOf(draft().copy(deletionMillis = now), draft().copy(deletionMillis = now + 32 * 86_400_000L),
            draft().copy(expirationMillis = now + 9 * 86_400_000L), draft().copy(maxAccessCount = 0),
            draft().copy(passwordAction = SendPasswordAction.REPLACE, password = ""))
        invalid.forEach { value ->
            assertTrue(runCatching { value.validate(now, true) }.exceptionOrNull() is IllegalArgumentException)
        }
        draft().copy(passwordAction = SendPasswordAction.REPLACE, password = " ").validate(now, true)
        draft().copy(text = " ").validate(now, true)
    }
}
