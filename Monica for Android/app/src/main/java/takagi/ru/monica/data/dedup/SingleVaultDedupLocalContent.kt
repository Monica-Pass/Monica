package takagi.ru.monica.data.dedup

import java.util.Base64
import kotlinx.serialization.json.*
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.model.*

/** Known local schemas only. Unknown fields may describe assets or references, so retain them. */
internal object SingleVaultDedupLocalContent {
    fun secureStackKeys(id: Long): Set<String> = setOf("secure:$id", "bank:$id", "document:$id", "billing:$id", "note:$id", "totp:$id", "payment:$id")
    private val strict = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    fun knownSchema(type: ItemType, value: JsonElement): Boolean = runCatching {
        when (type) {
            ItemType.TOTP -> strict.decodeFromJsonElement(TotpData.serializer(), value)
            ItemType.BANK_CARD -> strict.decodeFromJsonElement(BankCardData.serializer(), value)
            ItemType.DOCUMENT -> strict.decodeFromJsonElement(DocumentData.serializer(), value)
            ItemType.BILLING_ADDRESS -> strict.decodeFromJsonElement(BillingAddressData.serializer(), value)
            ItemType.PAYMENT_ACCOUNT -> strict.decodeFromJsonElement(PaymentAccountData.serializer(), value)
            ItemType.NOTE -> strict.decodeFromJsonElement(NoteData.serializer(), value)
            ItemType.PASSWORD -> error("Unsupported secure-item schema")
        }
    }.isSuccess

    fun referencedPasswordIds(value: JsonElement, depth: Int = 0): List<Long> {
        require(depth < 64)
        return when (value) {
            is JsonObject -> value.flatMap { (key, child) ->
                if (key in setOf("boundPasswordId", "ssoRefEntryId")) {
                    if (child == JsonNull) emptyList() else {
                        require(child is JsonPrimitive && !child.isString)
                        listOf(requireNotNull(child.longOrNull).also { require(it > 0) })
                    }
                } else referencedPasswordIds(child, depth + 1)
            }
            is JsonArray -> value.flatMap { referencedPasswordIds(it, depth + 1) }
            else -> emptyList()
        }
    }

    fun externalAssets(value: JsonElement, availableNames: Set<String>, depth: Int = 0): Boolean {
        require(depth < 64)
        return when (value) {
            is JsonObject -> value.any { (key, child) ->
                key == "customIconType" && child !in setOf(JsonPrimitive("NONE"), JsonPrimitive("SIMPLE_ICON"), JsonNull) ||
                    key in setOf("frontImagePath", "backImagePath", "imagePath", "frontImage", "backImage", "attachmentId") && child !in setOf(JsonNull, JsonPrimitive("")) ||
                    key == "imageAttachmentName" && (child as? JsonPrimitive)?.content !in availableNames ||
                    externalAssets(child, availableNames, depth + 1)
            }
            is JsonArray -> value.any { externalAssets(it, availableNames, depth + 1) }
            is JsonPrimitive -> value.isString && value.content.contains("monica-image://", ignoreCase = true)
        }
    }

    fun decodeJson(value: JsonElement, decode: (String) -> String, depth: Int = 0): JsonElement {
        require(depth < 64)
        return when (value) {
            is JsonObject -> JsonObject(value.mapValues { decodeJson(it.value, decode, depth + 1) })
            is JsonArray -> JsonArray(value.map { decodeJson(it, decode, depth + 1) })
            is JsonPrimitive -> if (value.isString) JsonPrimitive(decode(value.content)) else value
        }
    }

    /** Unprefixed legacy GCM is indistinguishable from long base64 text; it cannot prove deletion. */
    fun couldBeLegacyCiphertext(value: String): Boolean {
        if (value.length < 38 || !value.all { it.isLetterOrDigit() && it.code < 128 || it in "+/=\r\n\t " }) return false
        return runCatching { Base64.getMimeDecoder().decode(value).size >= 28 }.getOrDefault(false)
    }
}
