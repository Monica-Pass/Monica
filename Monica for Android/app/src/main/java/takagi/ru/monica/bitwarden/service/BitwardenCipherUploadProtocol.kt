package takagi.ru.monica.bitwarden.service

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import takagi.ru.monica.bitwarden.api.BitwardenVaultApi
import takagi.ru.monica.bitwarden.api.CipherCreateRequest
import takagi.ru.monica.bitwarden.api.CipherUpdateRequest
import takagi.ru.monica.bitwarden.api.CipherLoginApiData
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.data.bitwarden.BitwardenVault

/** Final request metadata uses the authenticated account and the already-resolved cipher key. */
internal object BitwardenCipherUploadProtocol {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun requireUserId(value: String?): String {
        require(value != null && uuid.matches(value)) { "Authenticated Bitwarden account identity unavailable; sync the account again" }
        return value
    }

    suspend fun userId(vault: BitwardenVault, api: BitwardenVaultApi, token: String): String {
        vault.userId?.takeIf(uuid::matches)?.let { return it }
        // A newly signed-in/stale entity may precede the first profile sync.
        val response = api.profile("Bearer $token")
        check(response.isSuccessful) { "Authenticated Bitwarden profile unavailable (${response.code()})" }
        val profile = response.body() ?: error("Authenticated Bitwarden profile unavailable")
        require(profile.email.trim().equals(vault.email.trim(), ignoreCase = true)) { "Authenticated Bitwarden account changed" }
        return requireUserId(profile.id)
    }

    fun create(request: CipherCreateRequest, account: String, key: SymmetricCryptoKey): CipherCreateRequest = request.copy(
        encryptedFor = requireUserId(account), login = checksums(request.login, key)
    )

    fun update(request: CipherUpdateRequest, account: String, key: SymmetricCryptoKey,
        originalLogin: CipherLoginApiData? = null): CipherUpdateRequest {
        val sourceRules = originalLogin?.uris.orEmpty().filter { !it.uri.isNullOrBlank() }.groupBy {
            BitwardenCrypto.decryptToString(requireNotNull(it.uri), key)
        }
        val explicitRules = BitwardenLoginUriRules.hasExplicitRules(request.fields, key)
        val login = request.login?.copy(uris = request.login.uris?.map { uri ->
            if (explicitRules || uri.match != null || uri.uri.isNullOrBlank()) uri else {
                val original = sourceRules[BitwardenCrypto.decryptToString(requireNotNull(uri.uri), key)]
                val rules = original.orEmpty().map { it.match }.distinct()
                require(rules.size <= 1) { "Ambiguous Bitwarden URI match rules; sync the account again" }
                uri.copy(match = rules.singleOrNull())
            }
        })
        return request.copy(encryptedFor = requireUserId(account), login = checksums(login, key))
    }

    /** Raw passkey/folder requests preserve every encrypted and unknown field byte-for-byte. */
    fun documentAccount(document: JsonObject, account: String): JsonObject = JsonObject(
        document.filterKeys { !it.equals("encryptedFor", ignoreCase = true) } +
            ("encryptedFor" to JsonPrimitive(requireUserId(account)))
    )

    private fun checksums(login: CipherLoginApiData?, key: SymmetricCryptoKey): CipherLoginApiData? = login?.copy(
        uris = login.uris?.map { item ->
            val encryptedUri = item.uri
            if (encryptedUri.isNullOrBlank()) item.copy(uriChecksum = null) else {
                // Never normalize or hash ciphertext: the official client hashes the exact UTF-8 URI.
                val bytes = BitwardenCrypto.decryptToString(encryptedUri, key).toByteArray(Charsets.UTF_8)
                val digest = try { MessageDigest.getInstance("SHA-256").digest(bytes) } finally { bytes.fill(0) }
                val hash = try { Base64.getEncoder().encodeToString(digest) } finally { digest.fill(0) }
                val validExisting = item.uriChecksum?.let { checksum ->
                    runCatching { BitwardenCrypto.decryptToString(checksum, key) == hash }.getOrDefault(false)
                } == true
                if (validExisting) item else item.copy(uriChecksum = BitwardenCrypto.encryptString(hash, key))
            }
        }
    )
}
