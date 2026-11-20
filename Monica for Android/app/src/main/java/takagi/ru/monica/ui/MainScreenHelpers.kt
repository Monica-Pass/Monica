package takagi.ru.monica.ui

import androidx.compose.runtime.saveable.Saver
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.ui.password.sanitizeSelectedPasswordPageTypes
import takagi.ru.monica.viewmodel.CategoryFilter

internal enum class PasswordHistoryPageMode {
    NONE,
    // Android can restore this enum from an activity saved by a development build.
    @Deprecated("Only retained to decode old saved state; there is no timeline route")
    TIMELINE,
    TRASH
}

internal val PasswordHistoryPageMode.isVisible: Boolean
    get() = this == PasswordHistoryPageMode.TRASH

internal val passwordPageContentTypeSetSaver = Saver<Set<PasswordPageContentType>, ArrayList<String>>(
    save = { selectedTypes ->
        ArrayList(selectedTypes.map(PasswordPageContentType::name))
    },
    restore = { savedNames ->
        savedNames
            .mapNotNull { name -> PasswordPageContentType.entries.find { it.name == name } }
            .toSet()
    }
)

internal fun togglePasswordPageContentType(
    currentTypes: Set<PasswordPageContentType>,
    toggledType: PasswordPageContentType,
    visibleTypes: List<PasswordPageContentType>
): Set<PasswordPageContentType> {
    val nextTypes = if (toggledType in currentTypes && currentTypes.size == 1) {
        emptySet()
    } else {
        setOf(toggledType)
    }
    return sanitizeSelectedPasswordPageTypes(
        visibleTypes = visibleTypes,
        selectedTypes = nextTypes
    )
}

internal data class NewItemStorageDefaults(
    val categoryId: Long? = null,
    val keepassDatabaseId: Long? = null,
    val keepassGroupPath: String? = null,
    val mdbxDatabaseId: Long? = null,
    val mdbxFolderId: String? = null,
    val bitwardenVaultId: Long? = null,
    val bitwardenFolderId: String? = null,
    val explicit: Boolean = false,
)

internal fun NewItemStorageDefaults.hasAnyValue(): Boolean {
    return explicit || categoryId != null ||
        keepassDatabaseId != null ||
        !keepassGroupPath.isNullOrBlank() ||
        mdbxDatabaseId != null ||
        !mdbxFolderId.isNullOrBlank() ||
        bitwardenVaultId != null ||
        !bitwardenFolderId.isNullOrBlank()
}

internal fun defaultsFromTotpFilter(filter: takagi.ru.monica.viewmodel.TotpCategoryFilter): NewItemStorageDefaults {
    return when (filter) {
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.Custom -> {
            NewItemStorageDefaults(categoryId = filter.categoryId)
        }
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabase -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassGroupFilter -> {
            NewItemStorageDefaults(
                keepassDatabaseId = filter.databaseId,
                keepassGroupPath = filter.groupPath
            )
        }
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVault -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenFolderFilter -> {
            NewItemStorageDefaults(
                bitwardenVaultId = filter.vaultId,
                bitwardenFolderId = filter.folderId
            )
        }
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.MdbxDatabase -> {
            NewItemStorageDefaults(mdbxDatabaseId = filter.databaseId)
        }
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseStarred -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseUncategorized -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultStarred -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        else -> NewItemStorageDefaults()
    }
}

internal fun defaultsFromPasswordFilter(filter: CategoryFilter): NewItemStorageDefaults {
    return when (filter) {
        is CategoryFilter.Custom -> {
            NewItemStorageDefaults(categoryId = filter.categoryId)
        }
        is CategoryFilter.KeePassDatabase -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is CategoryFilter.KeePassGroupFilter -> {
            NewItemStorageDefaults(
                keepassDatabaseId = filter.databaseId,
                keepassGroupPath = filter.groupPath
            )
        }
        is CategoryFilter.KeePassDatabaseStarred -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is CategoryFilter.KeePassDatabaseUncategorized -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is CategoryFilter.BitwardenVault -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        is CategoryFilter.BitwardenFolderFilter -> {
            NewItemStorageDefaults(
                bitwardenVaultId = filter.vaultId,
                bitwardenFolderId = filter.folderId
            )
        }
        is CategoryFilter.MdbxDatabase -> {
            NewItemStorageDefaults(mdbxDatabaseId = filter.databaseId)
        }
        is CategoryFilter.MdbxFolderFilter -> {
            NewItemStorageDefaults(
                mdbxDatabaseId = filter.databaseId,
                mdbxFolderId = filter.folderId
            )
        }
        is CategoryFilter.BitwardenVaultStarred -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        is CategoryFilter.BitwardenVaultUncategorized -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        else -> NewItemStorageDefaults()
    }
}
