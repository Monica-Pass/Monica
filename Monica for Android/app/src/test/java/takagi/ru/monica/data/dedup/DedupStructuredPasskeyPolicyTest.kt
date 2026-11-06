package takagi.ru.monica.data.dedup

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DedupStructuredPasskeyPolicyTest {
    @Test fun explicitKeysAndNestedFieldLabelsArePreservedWithoutLookingAtSecretValues() {
        for (name in listOf("passkeyBindings", "KPEX_PASSKEY_PRIVATE_KEY_PEM", "fido2Credentials")) {
            assertTrue(DedupStructuredPasskeyPolicy.containsPasskeyContent(buildJsonObject { put(name, JsonNull) }))
        }
        for (collection in listOf("customFields", "custom_fields", "fields")) {
            for (label in listOf("label", "name", "title")) {
                val value = buildJsonObject { put("data", buildJsonObject { put(collection, buildJsonArray {
                    add(buildJsonObject { put(label, "FIDO2 credential"); put("value", "synthetic-secret"); put("type", "HIDDEN") })
                }) }) }
                val original = value.toString()
                assertTrue(DedupStructuredPasskeyPolicy.containsPasskeyContent(value))
                assertEquals(original, value.toString())
            }
        }
        assertTrue(DedupStructuredPasskeyPolicy.containsPasskeyContent(buildJsonObject {
            put("customFields", buildJsonArray { add(buildJsonObject { put("label", "Passkey") }) })
        }))
    }

    @Test fun ordinaryNamesAndFreeTextMentionsRemainEligible() {
        val value = buildJsonObject {
            put("title", "Passkey notes")
            put("fullName", "Fido Example")
            put("notes", "Notes can mention Passkey or FIDO without representing a credential field.")
            put("customFields", buildJsonArray { add(buildJsonObject {
                put("label", "姓名"); put("value", "Passkey FIDO mentioned in ordinary text"); put("type", "TEXT")
            }) })
        }
        assertFalse(DedupStructuredPasskeyPolicy.containsPasskeyContent(value))
        assertFalse(DedupStructuredPasskeyPolicy.containsPasskeyContent(JsonPrimitive("{\"passkey\":\"not parsed\"}")))
    }

    @Test fun explicitFieldObjectsAreDetectedButArbitraryStringBodiesAreNotParsed() {
        assertTrue(DedupStructuredPasskeyPolicy.containsPasskeyContent(buildJsonObject {
            put("label", "Passkey"); put("value", "synthetic-secret")
        }))
        assertFalse(DedupStructuredPasskeyPolicy.containsPasskeyContent(buildJsonObject {
            put("label", "姓名"); put("value", "{\"fido2Credentials\":[\"plain text\"]}")
        }))
    }

    @Test fun unsupportedDepthFailsClosedInsteadOfPretendingContentIsAbsent() {
        var value: JsonElement = JsonPrimitive("synthetic")
        repeat(64) { value = JsonArray(listOf(value)) }
        assertTrue(runCatching { DedupStructuredPasskeyPolicy.containsPasskeyContent(value) }.isFailure)
    }
}
