package takagi.ru.monica.data.dedup

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.ItemType

class SingleVaultDedupLocalContentTest {
    @Test fun unknownAssetFieldsAndInlineImagesCannotAuthorizeCleanup() {
        assertTrue(SingleVaultDedupLocalContent.knownSchema(ItemType.NOTE, Json.parseToJsonElement("{\"content\":\"text\"}")))
        assertFalse(SingleVaultDedupLocalContent.knownSchema(ItemType.NOTE, Json.parseToJsonElement("{\"content\":\"text\",\"futureAsset\":\"file\"}")))
        assertTrue(SingleVaultDedupLocalContent.externalAssets(JsonPrimitive("![](monica-image://local-image)"), emptySet()))
        val face = Json.parseToJsonElement("{\"imageAttachmentName\":\"card.png\"}")
        assertTrue(SingleVaultDedupLocalContent.externalAssets(face, emptySet()))
        assertFalse(SingleVaultDedupLocalContent.externalAssets(face, setOf("card.png")))
    }
    @Test fun malformedReferenceValuesAreNotTreatedAsAbsent() {
        listOf("\"12\"", "false", "{}", "[]", "-1").forEach { value ->
            assertTrue(runCatching { SingleVaultDedupLocalContent.referencedPasswordIds(Json.parseToJsonElement("{\"boundPasswordId\":$value}")) }.isFailure)
        }
        assertEquals(listOf(12L), SingleVaultDedupLocalContent.referencedPasswordIds(Json.parseToJsonElement("{\"boundPasswordId\":12}")))
        assertTrue(SingleVaultDedupLocalContent.referencedPasswordIds(Json.parseToJsonElement("{\"boundPasswordId\":null}")).isEmpty())
    }
    @Test fun ambiguousUnprefixedLegacyCiphertextIsNotSilentlyPlaintext() {
        assertTrue(SingleVaultDedupLocalContent.couldBeLegacyCiphertext(java.util.Base64.getEncoder().encodeToString(ByteArray(28))))
        assertFalse(SingleVaultDedupLocalContent.couldBeLegacyCiphertext("normal short text"))
        assertFalse(SingleVaultDedupLocalContent.couldBeLegacyCiphertext("user@example.invalid"))
    }
}
