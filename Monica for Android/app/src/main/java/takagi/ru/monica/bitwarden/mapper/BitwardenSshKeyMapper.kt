package takagi.ru.monica.bitwarden.mapper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import takagi.ru.monica.bitwarden.api.CipherCreateRequest
import takagi.ru.monica.bitwarden.api.CipherFieldApiData
import takagi.ru.monica.bitwarden.api.CipherLoginApiData
import takagi.ru.monica.bitwarden.api.CipherSshKeyApiData
import takagi.ru.monica.bitwarden.api.CipherUpdateRequest
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.LOGIN_TYPE_SSH_KEY
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.data.model.SshKeyDataCodec

/** Native SSH fields use the resolved item key; metadata remains encrypted custom data. */
internal object BitwardenSshKeyMapper {
    const val METADATA_FIELD = "monica_ssh_metadata"

    fun create(
        entry: PasswordEntry,
        key: SymmetricCryptoKey,
        fields: List<CipherFieldApiData>?,
        archivedDate: String?
    ): CipherCreateRequest {
        val ssh = requireSsh(entry)
        return CipherCreateRequest(
            type = 5,
            folderId = entry.bitwardenFolderId,
            name = BitwardenCrypto.encryptString(entry.title, key),
            notes = encryptOptional(entry.notes, key),
            sshKey = encryptNative(ssh, key),
            fields = withMetadata(fields, ssh, key),
            favorite = entry.isFavorite,
            archivedDate = archivedDate
        )
    }

    fun update(
        entry: PasswordEntry,
        key: SymmetricCryptoKey,
        fields: List<CipherFieldApiData>?,
        archivedDate: String?
    ): CipherUpdateRequest {
        val ssh = requireSsh(entry)
        require(entry.bitwardenCipherType == 1 || entry.bitwardenCipherType == 5) {
            "SSH source type is unsupported"
        }
        // Existing compatibility logins keep their type and custom-key fields. New items are native.
        val native = entry.bitwardenCipherType == 5
        return CipherUpdateRequest(
            type = if (native) 5 else 1,
            folderId = entry.bitwardenFolderId,
            name = BitwardenCrypto.encryptString(entry.title, key),
            notes = encryptOptional(entry.notes, key),
            login = if (native) null else CipherLoginApiData(uris = emptyList(), fido2Credentials = emptyList()),
            sshKey = if (native) encryptNative(ssh, key) else null,
            fields = if (native) withMetadata(fields, ssh, key) else fields,
            favorite = entry.isFavorite,
            archivedDate = archivedDate
        )
    }

    /** The native fields override metadata, so a metadata member cannot replace a key. */
    fun decodeNative(
        ssh: CipherSshKeyApiData,
        fields: List<CipherFieldApiData>?,
        key: SymmetricCryptoKey
    ): SshKeyData {
        val decodedFields = fields.orEmpty().mapNotNull { field ->
            field.name?.let { name -> BitwardenCrypto.decryptToString(name, key) to field }
        }
        val metadataFields = decodedFields.filter { it.first == METADATA_FIELD }.map { it.second }
        require(metadataFields.size <= 1) { "Ambiguous SSH metadata" }
        val metadata = metadataFields.singleOrNull()?.let { field ->
            require(field.type == 1 && field.linkedId == null && field.value != null) { "Invalid SSH metadata" }
            Json.parseToJsonElement(BitwardenCrypto.decryptToString(requireNotNull(field.value), key)).jsonObject
        } ?: JsonObject(emptyMap())
        fun legacy(name: String): String? = decodedFields.firstOrNull { it.first == name }?.second?.value
            ?.let { BitwardenCrypto.decryptToString(it, key) }
        val publicKey = decryptOptional(ssh.publicKey, key)
        val privateKey = decryptOptional(ssh.privateKey, key)
        val fingerprint = decryptOptional(ssh.keyFingerprint, key)
        val fallback = linkedMapOf(
            "algorithm" to JsonPrimitive(legacy("monica_ssh_algorithm") ?: inferAlgorithm(publicKey)),
            "keySize" to JsonPrimitive(legacy("monica_ssh_key_size")?.toIntOrNull() ?: 0),
            "comment" to JsonPrimitive(legacy("monica_ssh_comment").orEmpty()),
            "format" to JsonPrimitive(legacy("monica_ssh_format") ?: SshKeyData.FORMAT_OPENSSH)
        )
        val document = JsonObject(fallback + metadata + mapOf(
            "publicKeyOpenSsh" to JsonPrimitive(publicKey),
            "privateKeyOpenSsh" to JsonPrimitive(privateKey),
            "fingerprintSha256" to JsonPrimitive(fingerprint)
        ))
        return requireNotNull(SshKeyDataCodec.decode(document.toString())) { "Empty SSH key data" }
    }

    private fun requireSsh(entry: PasswordEntry): SshKeyData {
        require(entry.loginType.equals(LOGIN_TYPE_SSH_KEY, ignoreCase = true)) { "Not an SSH key entry" }
        val ssh = requireNotNull(SshKeyDataCodec.decode(entry.sshKeyData)) { "SSH key data is unreadable" }
        require(ssh.publicKeyOpenSsh.isNotBlank() || ssh.privateKeyOpenSsh.isNotBlank()) { "SSH key material is empty" }
        return ssh
    }

    private fun encryptNative(ssh: SshKeyData, key: SymmetricCryptoKey) = CipherSshKeyApiData(
        privateKey = encryptOptional(ssh.privateKeyOpenSsh, key),
        publicKey = encryptOptional(ssh.publicKeyOpenSsh, key),
        keyFingerprint = encryptOptional(ssh.fingerprintSha256, key)
    )

    private fun withMetadata(fields: List<CipherFieldApiData>?, ssh: SshKeyData, key: SymmetricCryptoKey): List<CipherFieldApiData> {
        val metadata = JsonObject(ssh.additionalFields + mapOf(
            "algorithm" to JsonPrimitive(ssh.algorithm), "keySize" to JsonPrimitive(ssh.keySize),
            "comment" to JsonPrimitive(ssh.comment), "format" to JsonPrimitive(ssh.format),
            "schema" to JsonPrimitive(ssh.schema)
        ))
        val preserved = fields.orEmpty().filterNot { field ->
            field.name?.let { runCatching { BitwardenCrypto.decryptToString(it, key) == METADATA_FIELD }.getOrDefault(false) } == true
        }
        return preserved + CipherFieldApiData(
            name = BitwardenCrypto.encryptString(METADATA_FIELD, key),
            value = BitwardenCrypto.encryptString(metadata.toString(), key), type = 1
        )
    }

    private fun encryptOptional(value: String, key: SymmetricCryptoKey): String? =
        value.takeUnless(String::isEmpty)?.let { BitwardenCrypto.encryptString(it, key) }

    private fun decryptOptional(value: String?, key: SymmetricCryptoKey): String =
        value?.let { BitwardenCrypto.decryptToString(it, key) }.orEmpty()

    private fun inferAlgorithm(publicKey: String): String = when {
        publicKey.startsWith("ssh-rsa") -> SshKeyData.ALGORITHM_RSA
        publicKey.startsWith("ssh-ed25519") -> SshKeyData.ALGORITHM_ED25519
        else -> ""
    }
}
