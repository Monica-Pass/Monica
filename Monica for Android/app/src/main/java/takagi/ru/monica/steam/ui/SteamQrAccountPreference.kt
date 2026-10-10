package takagi.ru.monica.steam.ui

import android.content.Context
import takagi.ru.monica.steam.data.SteamStorageSource

private const val STEAM_QR_PREFS_NAME = "steam_qr_preferences"
private const val KEY_LAST_ACCOUNT_ID = "last_account_id"
private const val KEY_STORAGE_SOURCE_TYPE = "storage_source_type"
private const val KEY_STORAGE_SOURCE_MDBX_ID = "storage_source_mdbx_id"
private const val KEY_STORAGE_SOURCE_EXTERNAL_ID = "storage_source_external_id"
private const val STORAGE_SOURCE_LOCAL = "local"
private const val STORAGE_SOURCE_MDBX = "mdbx"
private const val STORAGE_SOURCE_KEEPASS = "keepass"
private const val STORAGE_SOURCE_BITWARDEN = "bitwarden"

internal fun readLastSteamQrAccountId(context: Context): Long? {
    val accountId = context.applicationContext
        .getSharedPreferences(STEAM_QR_PREFS_NAME, Context.MODE_PRIVATE)
        .getLong(KEY_LAST_ACCOUNT_ID, 0L)
    return accountId.takeIf { it != 0L }
}

internal fun saveLastSteamQrAccountId(context: Context, accountId: Long?) {
    context.applicationContext
        .getSharedPreferences(STEAM_QR_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .apply {
            if (accountId != null && accountId != 0L) {
                putLong(KEY_LAST_ACCOUNT_ID, accountId)
            } else {
                remove(KEY_LAST_ACCOUNT_ID)
            }
        }
        .apply()
}

internal fun readSteamStorageSource(context: Context): SteamStorageSource {
    val prefs = context.applicationContext.getSharedPreferences(STEAM_QR_PREFS_NAME, Context.MODE_PRIVATE)
    return when (prefs.getString(KEY_STORAGE_SOURCE_TYPE, STORAGE_SOURCE_LOCAL)) {
        STORAGE_SOURCE_MDBX -> {
            val databaseId = prefs.getLong(KEY_STORAGE_SOURCE_MDBX_ID, 0L)
            if (databaseId > 0L) SteamStorageSource.Mdbx(databaseId) else SteamStorageSource.Local
        }
        STORAGE_SOURCE_KEEPASS -> prefs.getLong(KEY_STORAGE_SOURCE_EXTERNAL_ID, 0L)
            .takeIf { it > 0L }
            ?.let { SteamStorageSource.KeePass(it) }
            ?: SteamStorageSource.Local
        STORAGE_SOURCE_BITWARDEN -> prefs.getLong(KEY_STORAGE_SOURCE_EXTERNAL_ID, 0L)
            .takeIf { it > 0L }
            ?.let { SteamStorageSource.Bitwarden(it) }
            ?: SteamStorageSource.Local
        else -> SteamStorageSource.Local
    }
}

internal fun saveSteamStorageSource(context: Context, source: SteamStorageSource) {
    context.applicationContext
        .getSharedPreferences(STEAM_QR_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .apply {
            when (source) {
                SteamStorageSource.Local -> {
                    putString(KEY_STORAGE_SOURCE_TYPE, STORAGE_SOURCE_LOCAL)
                    remove(KEY_STORAGE_SOURCE_MDBX_ID)
                    remove(KEY_STORAGE_SOURCE_EXTERNAL_ID)
                }
                is SteamStorageSource.Mdbx -> {
                    putString(KEY_STORAGE_SOURCE_TYPE, STORAGE_SOURCE_MDBX)
                    putLong(KEY_STORAGE_SOURCE_MDBX_ID, source.databaseId)
                    remove(KEY_STORAGE_SOURCE_EXTERNAL_ID)
                }
                is SteamStorageSource.KeePass -> {
                    putString(KEY_STORAGE_SOURCE_TYPE, STORAGE_SOURCE_KEEPASS)
                    putLong(KEY_STORAGE_SOURCE_EXTERNAL_ID, source.databaseId)
                    remove(KEY_STORAGE_SOURCE_MDBX_ID)
                }
                is SteamStorageSource.Bitwarden -> {
                    putString(KEY_STORAGE_SOURCE_TYPE, STORAGE_SOURCE_BITWARDEN)
                    putLong(KEY_STORAGE_SOURCE_EXTERNAL_ID, source.vaultId)
                    remove(KEY_STORAGE_SOURCE_MDBX_ID)
                }
            }
        }
        .apply()
}

private fun steamFolderPreferenceKey(source: SteamStorageSource): String = when (source) {
    SteamStorageSource.Local -> "folder_local"
    is SteamStorageSource.Mdbx -> "folder_mdbx_${source.databaseId}"
    is SteamStorageSource.KeePass -> "folder_keepass_${source.databaseId}"
    is SteamStorageSource.Bitwarden -> "folder_bitwarden_${source.vaultId}"
}

internal fun readSteamFolderId(context: Context, source: SteamStorageSource): String? =
    context.applicationContext.getSharedPreferences(STEAM_QR_PREFS_NAME, Context.MODE_PRIVATE)
        .getString(steamFolderPreferenceKey(source), null)

internal fun saveSteamFolderId(context: Context, source: SteamStorageSource, folderId: String?) {
    context.applicationContext.getSharedPreferences(STEAM_QR_PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(steamFolderPreferenceKey(source), folderId).apply()
}
