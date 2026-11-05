package takagi.ru.monica.bitwarden.service

import android.util.Base64
import kotlinx.serialization.Serializable
import takagi.ru.monica.bitwarden.BitwardenVaultIdentity
import takagi.ru.monica.bitwarden.api.BitwardenApiFactory
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.data.bitwarden.BitwardenVault

/** Includes actual endpoints; a stable accountKey alone does not authorize another account/config. */
@Serializable
data class BitwardenSendAccountIdentity(
    val accountKey: String,
    val userId: String?,
    val canonicalEmail: String,
    val serverUrl: String,
    val apiUrl: String,
    val identityUrl: String,
    val environmentPreset: String? = null,
    val webVaultUrl: String? = null,
    val notificationsUrl: String? = null,
    val transportRevision: String = "",
    val transportSecretRef: String? = null,
) {
    companion object {
        fun from(vault: BitwardenVault) = BitwardenSendAccountIdentity(vault.accountKey,
            vault.userId, BitwardenVaultIdentity.resolveCanonicalEmail(vault),
            vault.serverUrl, vault.apiUrl, vault.identityUrl, vault.environmentPreset, vault.webVaultUrl,
            vault.notificationsUrl, vault.transportRevision, vault.transportSecretRef)
    }
}

internal object BitwardenSendScope {
    fun shareMatches(send: BitwardenSend, serverUrl: String, prefix: String? = null): Boolean {
        return runCatching {
        if (!send.accessId.matches(Regex("[A-Za-z0-9_-]+")) || send.bitwardenSendId.isBlank()) return false
        val encoded = requireNotNull(send.keyBase64)
        val key = Base64.decode(encoded, Base64.NO_WRAP)
        try {
            if (key.size != 16 || Base64.encodeToString(key, Base64.NO_WRAP) != encoded) return false
            val urlKey = Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val resolvedPrefix = prefix ?: bitwardenSendUrlPrefix(BitwardenVault(email = "", serverUrl = serverUrl,
                apiUrl = "", identityUrl = ""))
            send.shareUrl == "$resolvedPrefix${send.accessId}/$urlKey"
        } finally { key.fill(0) }
        }.getOrDefault(false)
    }
}

/** Public link derivation only: this stripped environment is never exposed to a network client. */
internal fun bitwardenSendUrlPrefix(vault: BitwardenVault): String {
    val revision = "00000000-0000-0000-0000-000000000000"
    val checked = if (vault.environmentPreset == null) {
        takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver.resolveLegacy(
            takagi.ru.monica.bitwarden.api.BitwardenLegacyEnvironmentSnapshot(vault.serverUrl, vault.apiUrl, vault.identityUrl), revision)
    } else {
        val preset = takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset.entries.firstOrNull { it.name == vault.environmentPreset }
            ?: error("Invalid Bitwarden link configuration")
        takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver.resolve(
            takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft(preset, vault.serverUrl, vault.webVaultUrl,
                vault.apiUrl, vault.identityUrl, vault.notificationsUrl), revision)
    }
    return takagi.ru.monica.bitwarden.api.BitwardenTransportPolicy.sendUrlPrefix(requireNotNull(checked.value))
}
