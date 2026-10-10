package takagi.ru.monica.passkey

import takagi.ru.monica.utils.SavedCategoryFilterState

/** Scope both refresh work and its progress to the restored Passkey filter. */
internal object PasskeyPageSyncPolicy {
    fun bitwardenStatusVaultId(filter: SavedCategoryFilterState, ready: Boolean, activeVaultId: Long?): Long? {
        if (!ready) return null
        return when (filter.type) {
            "all" -> activeVaultId
            "bitwarden_vault", "bitwarden_folder", "bitwarden_vault_starred",
            "bitwarden_vault_uncategorized" -> filter.primaryId
            else -> null
        }
    }

    fun keePassDatabaseIds(filter: SavedCategoryFilterState, existingIds: List<Long>): List<Long> =
        when (filter.type) {
            "all", "starred", "uncategorized" -> existingIds
            "keepass_database", "keepass_group", "keepass_database_starred",
            "keepass_database_uncategorized" -> existingIds.filter { it == filter.primaryId }
            else -> emptyList()
        }
}
