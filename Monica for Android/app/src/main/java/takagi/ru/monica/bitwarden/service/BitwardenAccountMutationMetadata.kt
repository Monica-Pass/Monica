package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.json.*

/** Missing, null and a value of the wrong JSON type are intentionally different. */
internal object BitwardenAccountMutationMetadata {
    fun field(document: JsonObject, name: String): JsonElement? {
        val alternate = name.replaceFirstChar { it.uppercaseChar() }
        check(!document.containsKey(name) || !document.containsKey(alternate) || document[name] == document[alternate])
        return document[name] ?: document[alternate]
    }

    fun string(document: JsonObject, name: String): String? {
        val value = field(document, name) ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString)
        return value.content
    }

    fun snapshot(binding: BitwardenAccountMutationBinding, kdf: BitwardenAccountMutationKdf,
        profile: JsonObject): BitwardenAccountMutationSnapshot {
        require(string(profile, "id") == binding.userId &&
            string(profile, "email")?.trim()?.lowercase(java.util.Locale.ROOT) == binding.canonicalEmail &&
            string(profile, "securityStamp") == binding.securityStamp && !binding.securityStamp.isNullOrBlank())
        val organizations = field(profile, "organizations")
        require(organizations is JsonArray && organizations.isEmpty()) { "Organization account management is unsupported" }
        require(field(profile, "accountKeys") in listOf(null, JsonNull)) { "Account key format is unsupported" }
        require(field(profile, "usesKeyConnector") in listOf(null, JsonNull, JsonPrimitive(false)))
        val wrapped = requireNotNull(string(profile, "key"))
        val options = field(profile, "userDecryptionOptions")
        if (options != null && options != JsonNull) {
            require(options is JsonObject && field(options, "hasMasterPassword") == JsonPrimitive(true))
            val unlock = field(options, "masterPasswordUnlock")
            if (unlock != null && unlock != JsonNull) {
                require(unlock is JsonObject && string(unlock, "salt") == binding.canonicalEmail &&
                    string(unlock, "masterKeyWrappedUserKey") == wrapped &&
                    field(unlock, "containedKeyId") in listOf(null, JsonNull))
                val metadata = field(unlock, "kdf") as? JsonObject ?: error("Missing KDF metadata")
                require(field(metadata, "kdfType")?.jsonPrimitive?.intOrNull == kdf.type &&
                    field(metadata, "iterations")?.jsonPrimitive?.intOrNull == kdf.iterations &&
                    field(metadata, "memory")?.jsonPrimitive?.intOrNull == kdf.memoryMiB &&
                    field(metadata, "parallelism")?.jsonPrimitive?.intOrNull == kdf.parallelism)
            }
        }
        val hintPresent = field(profile, "masterPasswordHint") != null
        return BitwardenAccountMutationSnapshot(binding, kdf, binding.canonicalEmail, wrapped,
            hasMasterPassword = true, usesKeyConnector = false, legacyUserKey = wrapped.startsWith("2."),
            profileName = string(profile, "name"), hintPresent = hintPresent,
            hint = if (hintPresent) string(profile, "masterPasswordHint") else null,
            hintEditingAllowed = true)
    }
}
