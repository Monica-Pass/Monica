package takagi.ru.monica.data.bitwarden

import androidx.room.ColumnInfo

/** Non-secret state checked by transports before dispatch; account identity checks remain separate. */
data class BitwardenTransportState(
    val id: Long,
    @ColumnInfo(name = "server_url") val serverUrl: String,
    @ColumnInfo(name = "api_url") val apiUrl: String,
    @ColumnInfo(name = "identity_url") val identityUrl: String,
    @ColumnInfo(name = "environment_preset") val preset: String?,
    @ColumnInfo(name = "web_vault_url") val webUrl: String?,
    @ColumnInfo(name = "notifications_url") val notificationsUrl: String?,
    @ColumnInfo(name = "transport_revision") val revision: String,
    @ColumnInfo(name = "transport_secret_ref") val reference: String?,
    @ColumnInfo(name = "is_locked") val locked: Boolean,
    @ColumnInfo(name = "is_connected") val connected: Boolean,
) {
    fun matches(vault: BitwardenVault): Boolean = id == vault.id && serverUrl == vault.serverUrl &&
        apiUrl == vault.apiUrl && identityUrl == vault.identityUrl && preset == vault.environmentPreset &&
        webUrl == vault.webVaultUrl && notificationsUrl == vault.notificationsUrl &&
        revision == vault.transportRevision && reference == vault.transportSecretRef && !locked && connected
}
