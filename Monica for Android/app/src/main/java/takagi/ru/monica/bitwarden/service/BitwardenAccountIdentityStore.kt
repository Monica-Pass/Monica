package takagi.ru.monica.bitwarden.service

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.bitwarden.api.ProfileResponse
import takagi.ru.monica.bitwarden.BitwardenVaultIdentity
import takagi.ru.monica.bitwarden.crypto.BitwardenAccountFingerprint
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.data.bitwarden.BitwardenVault

/** Only the public RSA identity is retained, sealed with the account key for offline use. */
internal class BitwardenAccountIdentityStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Boolean,
    private val remove: (String) -> Unit,
) {
    @Serializable
    private data class Material(val account: String, val userId: String, val stamp: String?, val publicKey: String)

    private val json = Json { ignoreUnknownKeys = false }
    private fun storageKey(id: Long) = "bitwarden_account_identity_v1_$id"
    private fun account(vault: BitwardenVault) =
        BitwardenVaultIdentity.normalizeServerUrl(vault.serverUrl) + "\u0000" +
            BitwardenVaultIdentity.canonicalizeEmail(vault.email)

    fun capture(vault: BitwardenVault, profile: ProfileResponse, key: SymmetricCryptoKey, current: () -> Boolean) {
        // Some profile endpoints omit key material. A complete sync supplies it.
        val envelope = profile.privateKey ?: return
        if (profile.id.isBlank() || !current()) return
        val encoded = try {
            val publicKey = BitwardenAccountFingerprint.publicKeyFromEncryptedPrivateKey(envelope, key)
            try {
                BitwardenCrypto.encryptString(json.encodeToString(Material(account(vault), profile.id,
                    profile.securityStamp, Base64.getEncoder().encodeToString(publicKey))), key)
            } finally { publicKey.fill(0) }
        } catch (_: Exception) {
            if (current()) clear(vault.id)
            return
        }
        if (current() && !runCatching { write(storageKey(vault.id), encoded) }.getOrDefault(false)) clear(vault.id)
    }

    fun publicKey(vault: BitwardenVault, key: SymmetricCryptoKey): ByteArray? = runCatching {
        val sealed = read(storageKey(vault.id)) ?: return null
        require(sealed.length <= 16384 && sealed.startsWith("2."))
        val bytes = BitwardenCrypto.decrypt(sealed, key)
        val material = try { json.decodeFromString<Material>(bytes.toString(Charsets.UTF_8)) }
            finally { bytes.fill(0) }
        require(material.account == account(vault) && material.userId == vault.userId && material.stamp == vault.revisionDate)
        Base64.getDecoder().decode(material.publicKey)
    }.getOrNull()

    fun clear(vaultId: Long) { runCatching { remove(storageKey(vaultId)) } }
}
