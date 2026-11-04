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
) {
    companion object {
        fun from(vault: BitwardenVault) = BitwardenSendAccountIdentity(vault.accountKey,
            vault.userId, BitwardenVaultIdentity.resolveCanonicalEmail(vault),
            vault.serverUrl, vault.apiUrl, vault.identityUrl)
    }
}

internal object BitwardenSendScope {
    fun shareMatches(send: BitwardenSend, serverUrl: String): Boolean {
        return runCatching {
        if (!send.accessId.matches(Regex("[A-Za-z0-9_-]+")) || send.bitwardenSendId.isBlank()) return false
        val encoded = requireNotNull(send.keyBase64)
        val key = Base64.decode(encoded, Base64.NO_WRAP)
        try {
            if (key.size != 16 || Base64.encodeToString(key, Base64.NO_WRAP) != encoded) return false
            val urlKey = Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val base = when {
                BitwardenApiFactory.isOfficialEuServer(serverUrl) -> "https://send.bitwarden.eu"
                BitwardenApiFactory.isOfficialServer(serverUrl) -> "https://send.bitwarden.com"
                else -> serverUrl.trimEnd('/')
            }
            send.shareUrl == "$base/#/send/${send.accessId}/$urlKey"
        } finally { key.fill(0) }
        }.getOrDefault(false)
    }
}
