package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.json.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.mapper.PasskeyNotesCodec
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.passkey.PasskeyCredentialIdCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport

/** Patch the original encrypted document; typed create DTOs discard unrelated and future fields. */
internal object PasskeyCipherUpdate {
    private val serverFields = setOf("id", "object", "creationdate", "revisiondate", "lastknownrevisiondate", "edit", "viewpassword")

    fun build(original: JsonObject, item: PasskeyEntry, cipherId: String, vaultKey: SymmetricCryptoKey,
        folderChange: takagi.ru.monica.passkey.PasskeyCipherFolderIntent.Change? = null): JsonObject {
        require(original.text("id") == cipherId && original.value("type")?.jsonPrimitive?.intOrNull == 1) { "Passkey source changed" }
        require(original.value("deletedDate") == null || original.value("deletedDate") == JsonNull) { "Passkey source was deleted" }
        // The caller currently has the account key only. Never encrypt organization data with it.
        require(original.text("organizationId").isNullOrBlank()) { "Organization passkey updates require an organization key" }
        require(original.value("edit")?.jsonPrimitive?.booleanOrNull != false) { "Passkey source is read-only" }
        require(item.backupEligible != false) { "Bitwarden cannot preserve backup-ineligible credentials" }
        require(item.publicKeyAlgorithm == PasskeyEntry.ALGORITHM_ES256) { "Unsupported Bitwarden passkey algorithm" }
        val revision = original.text("revisionDate")?.takeIf(String::isNotBlank)
            ?: error("Passkey source revision is missing")
        val key = original.text("key")?.takeIf(String::isNotBlank)?.let {
            BitwardenCrypto.decryptSymmetricKey(it, vaultKey)
        } ?: vaultKey
        try {
            fun decrypt(value: String?): String = value?.takeIf(String::isNotEmpty)?.let {
                BitwardenCrypto.decryptToString(it, key)
            }.orEmpty()
            val login = original.value("login") as? JsonObject ?: error("Passkey login is missing")
            val credentials = login.value("fido2Credentials") as? JsonArray ?: error("Passkey list is missing")
            val targetId = PasskeyCredentialIdCodec.normalize(item.credentialId) ?: error("Invalid passkey ID")
            val targets = credentials.indices.filter { index ->
                val credential = credentials[index] as? JsonObject ?: error("Invalid passkey list")
                PasskeyCredentialIdCodec.normalize(decrypt(credential.text("credentialId"))) == targetId
            }
            require(targets.size == 1) { "Passkey target is missing or ambiguous" }
            val index = targets.single()
            val target = credentials[index].jsonObject
            require(decrypt(target.text("rpId")).equals(item.rpId, ignoreCase = true)) { "Passkey relying party changed" }
            val remoteHandle = decrypt(target.text("userHandle"))
            require(PasskeyCredentialIdCodec.normalize(remoteHandle) == PasskeyCredentialIdCodec.normalize(item.userId)) {
                "Passkey user handle changed"
            }
            val remoteKey = PasskeyPrivateKeySupport.exportPkcs8Base64(decrypt(target.text("keyValue")))
            val localKey = PasskeyPrivateKeySupport.exportPkcs8Base64(item.privateKeyAlias)
            require(remoteKey != null && remoteKey == localKey) { "Passkey private key changed" }
            val counter = decrypt(target.text("counter")).toLongOrNull() ?: error("Invalid remote signature counter")
            require(counter in 0L..0xffffffffL && item.signCount in 0L..0xffffffffL) { "Invalid signature counter" }
            fun JsonObject.updateText(name: String, plain: String): JsonObject =
                if (decrypt(text(name)) == plain) this else replacing(name, JsonPrimitive(BitwardenCrypto.encryptString(plain, key)))
            // Identity, key, original ID representation, other credentials and login fields stay byte-for-byte intact.
            val patched = target.updateText("rpName", item.rpName)
                .updateText("userName", item.userName).updateText("userDisplayName", item.userDisplayName)
                .updateText("counter", maxOf(counter, item.signCount).toString())
            var request = original.replacing("login", login.replacing("fido2Credentials",
                JsonArray(credentials.mapIndexed { i, entry -> if (i == index) patched else entry })))
            if (PasskeyNotesCodec.decode(decrypt(original.text("notes"))) != item.notes) {
                request = request.replacing("notes", JsonPrimitive(BitwardenCrypto.encryptString(item.notes, key)))
            }
            if (folderChange != null) {
                require(folderChange.folderId == item.bitwardenFolderId) { "Passkey folder move changed" }
                request = request.replacing("folderId", folderChange.folderId?.let(::JsonPrimitive) ?: JsonNull)
            }
            return request.replacing("lastKnownRevisionDate", JsonPrimitive(revision))
        } finally {
            if (key !== vaultKey) key.clear()
        }
    }

    /** The server may change revision metadata, but must retain the submitted content. */
    fun confirms(expected: JsonObject, actual: JsonObject): Boolean {
        if (expected.text("id") != actual.text("id")) return false
        fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapKeys { it.key.lowercase() }.mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
        val content = expected.filterKeys { it.lowercase() !in serverFields }
        return content.all { (name, value) -> canonical(actual.value(name) ?: JsonNull) == canonical(value) }
    }

    private fun JsonObject.value(name: String): JsonElement? {
        val keys = keys.filter { it.equals(name, ignoreCase = true) }
        require(keys.size <= 1) { "Ambiguous cipher field" }
        return keys.singleOrNull()?.let(::get)
    }
    private fun JsonObject.text(name: String): String? = value(name)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.replacing(name: String, value: JsonElement): JsonObject {
        this.value(name) // reject duplicate aliases rather than updating only one representation
        val existing = keys.singleOrNull { it.equals(name, ignoreCase = true) } ?: name
        return JsonObject(toMutableMap().apply { put(existing, value) })
    }
}
