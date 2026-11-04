package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BitwardenSendSubmissionTest {
    private val json = Json
    private fun request(): JsonObject = json.parseToJsonElement("""{
        "key":"2.prepared-key","type":0,"name":"2.encrypted-name","notes":null,
        "password":null,"authType":0,"emails":"one@example.test,two@example.test",
        "disabled":false,"hideEmail":false,"maxAccessCount":null,
        "deletionDate":"2026-11-10T00:00:00Z","expirationDate":null,
        "text":{"text":"2.encrypted-content","hidden":false}}
    """) as JsonObject
    private fun response(request: JsonObject = request()): JsonObject = JsonObject(request +
        mapOf("id" to JsonPrimitive("remote-id"), "accessId" to JsonPrimitive("access-id")))

    @Test fun onlyExactPreparedKeyAndContentCanReconcile() {
        val prepared = request()
        val sameTitle = JsonObject(response() + ("key" to JsonPrimitive("2.different-key")))
        assertEquals(response(), BitwardenSendSubmission.uniqueCandidate(prepared, listOf(sameTitle, response())))
        assertNull(BitwardenSendSubmission.uniqueCandidate(prepared, listOf(sameTitle)))
        val changedContent = JsonObject(response() + ("name" to JsonPrimitive("2.changed-ciphertext")))
        assertNull(BitwardenSendSubmission.uniqueCandidate(prepared, listOf(changedContent)))
    }

    @Test fun ambiguousExactKeysNeverConfirmEvenWhenOnePayloadMatches() {
        val changed = JsonObject(response() + ("name" to JsonPrimitive("2.changed")))
        assertNull(BitwardenSendSubmission.uniqueCandidate(request(), listOf(response(), changed)))
        assertNull(BitwardenSendSubmission.uniqueCandidate(request(), listOf(response(), response())))
    }

    @Test fun emailAuthIgnoredByServerCannotConfirmProtectedSend() {
        val ignored = JsonObject(response().filterKeys { it !in setOf("authType", "emails") })
        assertFalse(BitwardenSendSubmission.matches(request(), ignored))
        val none = JsonObject(response() + ("authType" to JsonPrimitive(2)))
        assertFalse(BitwardenSendSubmission.matches(request(), none))
        val changedRecipients = JsonObject(response() + ("emails" to JsonPrimitive("other@example.test")))
        assertFalse(BitwardenSendSubmission.matches(request(), changedRecipients))
    }

    @Test fun equivalentDateEncodingIsAcceptedButChangedLimitIsNot() {
        val equivalent = JsonObject(response() + ("deletionDate" to JsonPrimitive("2026-11-10T00:00:00.000Z")))
        assertTrue(BitwardenSendSubmission.matches(request(), equivalent))
        assertFalse(BitwardenSendSubmission.matches(request(), JsonObject(equivalent + ("maxAccessCount" to JsonPrimitive(8)))))
    }

    @Test fun explicitRemovalReconcilesOnlyPasswordFreeFinalStateWithTheExactPreparedContent() {
        val prepared = JsonObject(request() + mapOf("authType" to JsonPrimitive(1), "emails" to kotlinx.serialization.json.JsonNull))
        val retained = JsonObject(response(prepared) + ("password" to JsonPrimitive("server-password-marker")))
        val removed = JsonObject(response(prepared) + ("authType" to JsonPrimitive(2)))
        assertFalse(BitwardenSendSubmission.matches(prepared, removed, previousHasPassword = true))
        assertFalse(BitwardenSendSubmission.matches(prepared, retained, previousHasPassword = true, passwordRemoved = true))
        assertTrue(BitwardenSendSubmission.matches(prepared, removed, previousHasPassword = true, passwordRemoved = true))
        assertFalse(BitwardenSendSubmission.matches(prepared, removed, previousHasPassword = false, passwordRemoved = true))
        for (change in listOf("key" to JsonPrimitive("different-key"), "name" to JsonPrimitive("changed-content"),
            "emails" to JsonPrimitive("unexpected@example.test"), "password" to JsonPrimitive("empty-password-hash"))) {
            assertFalse(BitwardenSendSubmission.matches(prepared, JsonObject(removed + change), previousHasPassword = true, passwordRemoved = true))
        }
        assertEquals(JsonPrimitive(1), prepared["authType"])
        assertEquals(kotlinx.serialization.json.JsonNull, prepared["password"])
    }

    @Test fun missingIdentityAndMalformedFieldsDoNotCrashOrConfirm() {
        assertFalse(BitwardenSendSubmission.matches(request(), JsonObject(response() + ("id" to JsonPrimitive("")))))
        assertFalse(BitwardenSendSubmission.matches(request(), JsonObject(response() + ("deletionDate" to JsonObject(emptyMap())))))
        assertNull(BitwardenSendSubmission.uniqueCandidate(JsonObject(emptyMap()), listOf(response())))
    }
}
