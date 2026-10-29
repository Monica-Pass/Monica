package takagi.ru.monica.keepass

import java.util.UUID
import takagi.ru.monica.viewmodel.CategoryFilter

/** A selected vault is read from UUID records, independently of the Room overview. */
internal data class KeePassPasswordNativeScope(
    val databaseId: Long,
    val groupPath: String? = null,
    val groupUuid: String? = null,
    val starred: Boolean = false,
    val uncategorized: Boolean = false,
    val passwordsOnly: Boolean = true,
) {
    fun initialGroup(browser: KeePassNativeBrowserSnapshot): KeePassNativeGroupIdentity {
        require(browser.databaseId == databaseId)
        if (groupUuid != null) {
            val identity = KeePassNativeGroupIdentity(databaseId, UUID.fromString(groupUuid))
            require(browser.groupsByIdentity[identity]?.size == 1) { "KeePass group is missing or ambiguous" }
            return identity
        }
        if (!groupPath.isNullOrBlank()) {
            return browser.groups.singleOrNull { it.legacyPath == groupPath }?.identity
                ?: error("KeePass group is missing or ambiguous")
        }
        return browser.rootGroup.identity
    }

    fun entries(
        browser: KeePassNativeBrowserSnapshot,
        group: KeePassNativeGroupIdentity,
    ): List<KeePassNativeEntryRecord> {
        require(browser.databaseId == databaseId)
        val groups = browser.descendantGroupIdentities(group)
        return browser.entries.filter { entry ->
            (!passwordsOnly || (browser.passwordEntryUuids?.contains(entry.identity.entryUuid)
                ?: (entry.kind == KeePassNativeEntryKind.PASSWORD || entry.kind == KeePassNativeEntryKind.UNKNOWN))) &&
                (passwordsOnly || entry.kind != KeePassNativeEntryKind.TEMPLATE) &&
                !entry.isInRecycleBin &&
                entry.parentGroup in groups &&
                (!starred || entry.field("MonicaIsFavorite")?.displayValue.equals("true", ignoreCase = true)) &&
                (!uncategorized || entry.parentGroup == browser.rootGroup.identity)
        }
    }

    companion object {
        fun from(filter: CategoryFilter): KeePassPasswordNativeScope? = when (filter) {
            is CategoryFilter.KeePassDatabase -> KeePassPasswordNativeScope(filter.databaseId)
            is CategoryFilter.KeePassGroupFilter -> KeePassPasswordNativeScope(filter.databaseId, filter.groupPath, filter.groupUuid)
            is CategoryFilter.KeePassDatabaseStarred -> KeePassPasswordNativeScope(filter.databaseId, starred = true)
            is CategoryFilter.KeePassDatabaseUncategorized -> KeePassPasswordNativeScope(filter.databaseId, uncategorized = true)
            else -> null
        }
    }
}
