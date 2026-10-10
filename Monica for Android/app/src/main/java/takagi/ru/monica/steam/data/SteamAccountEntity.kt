package takagi.ru.monica.steam.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "steam_accounts",
    indices = [
        Index(value = ["steam_id"], unique = false, name = "index_steam_accounts_steam_id")
    ]
)
data class SteamAccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "steam_id")
    val steamId: String,
    val accountName: String,
    val displayName: String,
    val deviceId: String,
    val sharedSecret: String,
    val identitySecret: String?,
    val revocationCode: String?,
    val tokenGid: String?,
    val accessToken: String?,
    val refreshToken: String?,
    val steamLoginSecure: String?,
    val rawSteamGuardJson: String,
    val selected: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "NULL") val categoryId: Long? = null
)

data class SteamAccount(
    val id: Long,
    val steamId: String,
    val accountName: String,
    val displayName: String,
    val deviceId: String,
    val sharedSecret: String,
    val identitySecret: String?,
    val revocationCode: String?,
    val tokenGid: String?,
    val accessToken: String?,
    val refreshToken: String?,
    val steamLoginSecure: String?,
    val rawSteamGuardJson: String,
    val selected: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val categoryId: Long? = null,
    val storageFolderId: String? = null
) {
    val hasRealSteamId: Boolean
        get() = steamId.matches(Regex("""7656119\d{10}"""))

    val visibleSteamId: String
        get() = steamId.takeIf { hasRealSteamId }.orEmpty()

    val canUseConfirmations: Boolean
        get() = hasRealSteamId &&
            !identitySecret.isNullOrBlank() &&
            (!accessToken.isNullOrBlank() || !refreshToken.isNullOrBlank())

    val canApproveLogins: Boolean
        get() = hasRealSteamId &&
            sharedSecret.isNotBlank() &&
            (!accessToken.isNullOrBlank() || !refreshToken.isNullOrBlank())
}
