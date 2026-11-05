package takagi.ru.monica.bitwarden

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.service.*

class BitwardenAccountMutationMetadataTest {
    private val binding = BitwardenAccountMutationBinding(1, "user", "synthetic@example.test", "environment", "revision", null, 1, 2, "stamp")
    private val kdf = BitwardenAccountMutationKdf(0, 600000)
    private fun profile(extra: Map<String, JsonElement> = emptyMap()) = JsonObject(mapOf(
        "id" to JsonPrimitive("user"), "email" to JsonPrimitive("synthetic@example.test"),
        "securityStamp" to JsonPrimitive("stamp"), "key" to JsonPrimitive("2.iv|cipher|mac"),
        "organizations" to JsonArray(emptyList()), "name" to JsonPrimitive("Synthetic")) + extra)
    @Test fun absentHintDiffersFromExplicitClear() {
        assertFalse(BitwardenAccountMutationMetadata.snapshot(binding, kdf, profile()).hintPresent)
        val explicit = BitwardenAccountMutationMetadata.snapshot(binding, kdf, profile(mapOf("masterPasswordHint" to JsonNull)))
        assertTrue(explicit.hintPresent); assertNull(explicit.hint)
    }
    @Test fun nonStringHintCannotBeInterpretedAsUserText() {
        listOf(JsonPrimitive(true), JsonPrimitive(1), JsonArray(emptyList())).forEach {
            assertTrue(runCatching { BitwardenAccountMutationMetadata.snapshot(binding, kdf,
                profile(mapOf("masterPasswordHint" to it))) }.isFailure)
        }
    }
    @Test fun duplicateCasingCannotReplaceAccountIdentity() {
        assertTrue(runCatching { BitwardenAccountMutationMetadata.snapshot(binding, kdf,
            profile(mapOf("Id" to JsonPrimitive("other")))) }.isFailure)
    }
    @Test fun foreignAccountStampAndEmailAreRejected() {
        listOf("id", "email", "securityStamp").forEach { field ->
            assertTrue(runCatching { BitwardenAccountMutationMetadata.snapshot(binding, kdf,
                profile(mapOf(field to JsonPrimitive("other")))) }.isFailure)
        }
    }
    @Test fun organizationAndUnknownKeysCannotBypassTheirPolicies() {
        listOf(mapOf("organizations" to JsonArray(listOf(buildJsonObject { put("id", "org") }))),
            mapOf("accountKeys" to buildJsonObject { put("unknown", true) }),
            mapOf("usesKeyConnector" to JsonPrimitive(true))).forEach {
            assertTrue(runCatching { BitwardenAccountMutationMetadata.snapshot(binding, kdf, profile(it)) }.isFailure)
        }
    }
    @Test fun alternateUnlockSaltIsNeverSilentlyReplacedByEmail() {
        val options = buildJsonObject {
            put("hasMasterPassword", true)
            put("masterPasswordUnlock", buildJsonObject { put("salt", "non-email-salt") })
        }
        assertTrue(runCatching { BitwardenAccountMutationMetadata.snapshot(binding, kdf,
            profile(mapOf("userDecryptionOptions" to options))) }.isFailure)
    }
}
