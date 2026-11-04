package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.api.SendApiResponse
import takagi.ru.monica.data.bitwarden.BitwardenSend

class BitwardenSendAuthTest {
    private fun draft() = BitwardenSendDraft("Title", "Text", null, deletionMillis = 1_000_000)
    private fun send() = BitwardenSend(vaultId = 7, bitwardenSendId = "send", accessId = "access", name = "Title")

    @Test fun emailInputIsBoundedAndNormalizedWithoutSilentlyDroppingRecipients() {
        assertEquals(listOf("one@example.test", "Two@example.test"),
            BitwardenSendAuth.recipients(listOf(" one@example.test ", "Two@example.test")))
        listOf(emptyList(), List(101) { "$it@example.test" }, listOf("bad"),
            listOf("one@example.test", " one@example.test "), listOf("one@example.test,other@example.test"),
            listOf("one@exam ple.test"), listOf("a".repeat(321) + "@example.test")).forEach { invalid ->
            assertTrue(runCatching { BitwardenSendAuth.recipients(invalid) }.isFailure)
        }
    }

    @Test fun explicitEmailAndNoneCannotAccidentallyCarryPasswordReplacement() {
        val replacement = draft().copy(passwordAction = SendPasswordAction.REPLACE, password = "secret")
        assertTrue(runCatching { BitwardenSendAuth.validate(replacement.copy(authAction = SendAuthAction.EMAIL,
            recipientEmails = listOf("one@example.test"))) }.isFailure)
        assertTrue(runCatching { BitwardenSendAuth.validate(replacement.copy(authAction = SendAuthAction.NONE)) }.isFailure)
        val spaces = replacement.copy(password = "   ", authAction = SendAuthAction.PASSWORD)
        BitwardenSendAuth.validate(spaces)
        assertEquals(SendAuthAction.PASSWORD, BitwardenSendAuth.requested(spaces, true))
    }

    @Test fun unknownAuthenticationSurvivesKeepPatchIncludingOpaqueFields() {
        val before = Json.parseToJsonElement("""{"key":"key","type":0,"authType":91,
            "emails":"future@example.test","future":{"nested":7},"text":{"text":"old","opaque":true}}""") as JsonObject
        val patched = BitwardenSendEdit.patch(before, draft(), "new", null, "new text", null)
        assertEquals(before["authType"], patched["authType"])
        assertEquals(before["emails"], patched["emails"])
        assertEquals(before["future"], patched["future"])
        assertEquals(JsonNull, patched["password"])
        val existing = send().copy(authType = 91, recipientEmails = "future@example.test")
        assertTrue(BitwardenSendAuth.matches(existing, draft(), existing))
        assertFalse(BitwardenSendAuth.matches(existing.copy(authType = 2), draft(), existing))
    }

    @Test fun emailReadbackRequiresExplicitModeRecipientsAndNoPassword() {
        val desired = draft().copy(authAction = SendAuthAction.EMAIL, recipientEmails = listOf("one@example.test"))
        val valid = send().copy(authType = 0, recipientEmails = "one@example.test")
        assertTrue(BitwardenSendAuth.matches(valid, desired))
        assertFalse(BitwardenSendAuth.matches(valid.copy(authType = null), desired))
        assertFalse(BitwardenSendAuth.matches(valid.copy(recipientEmails = "other@example.test"), desired))
        assertFalse(BitwardenSendAuth.matches(valid.copy(hasPassword = true), desired))
        assertFalse(BitwardenSendAuth.matchesApi(SendApiResponse(authType = 2), SendAuthAction.EMAIL, "one@example.test"))
    }

    @Test fun legacyMissingAuthTypeNeedsPasswordEvidenceForKnownModes() {
        assertTrue(BitwardenSendAuth.matchesApi(SendApiResponse(password = "server marker"), SendAuthAction.PASSWORD, null))
        assertFalse(BitwardenSendAuth.matchesApi(SendApiResponse(), SendAuthAction.PASSWORD, null))
        assertTrue(BitwardenSendAuth.matchesApi(SendApiResponse(), SendAuthAction.NONE, null))
        assertFalse(BitwardenSendAuth.matchesApi(SendApiResponse(password = "server marker"), SendAuthAction.NONE, null))
    }
}
