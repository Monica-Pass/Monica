package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.ui.PasswordQuickFolderNode
import takagi.ru.monica.ui.PasswordQuickFolderShortcut
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.viewmodel.CategoryFilter

class VaultV2FolderLayoutTest {

    @Test
    fun `all root contains storage source rows and no direct items`() {
        val localItem = passwordItem(id = 1, title = "Root")

        val result = buildVaultV2HierarchicalContent(
            storageSelection = UnifiedCategoryFilterSelection.All,
            allItems = listOf(localItem),
            filteredItems = listOf(localItem),
            folderShortcuts = emptyList(),
            quickFolderNodes = emptyList(),
            keepassDatabases = emptyList(),
            bitwardenVaults = emptyList(),
            mdbxDatabases = emptyList(),
            selectedMdbxFolders = emptyList(),
            localSourceTitle = "Monica local",
            includeExternalSources = false,
        )

        assertEquals(1, result.folderRows.size)
        assertEquals(1, result.folderRows.single().itemCount)
        assertTrue(result.directItems.isEmpty())
        assertTrue(result.sections.isEmpty())
    }

    @Test
    fun `local folder count includes every item type in its subtree`() {
        val work = PasswordQuickFolderNode(
            category = Category(id = 10, name = "Work"),
            path = "Work",
            parentPath = null,
            displayName = "Work",
        )
        val personal = PasswordQuickFolderNode(
            category = Category(id = 11, name = "Work/Personal"),
            path = "Work/Personal",
            parentPath = "Work",
            displayName = "Personal",
        )
        val rootItem = passwordItem(id = 1, title = "Root")
        val password = passwordItem(id = 2, title = "Account", categoryId = 10)
        val note = secureItem(id = 3, title = "Note", categoryId = 11)

        val result = buildVaultV2HierarchicalContent(
            storageSelection = UnifiedCategoryFilterSelection.Local,
            allItems = listOf(rootItem, password, note),
            filteredItems = listOf(rootItem, password, note),
            folderShortcuts = listOf(folderShortcut("work", "Work", CategoryFilter.Custom(10))),
            quickFolderNodes = listOf(work, personal),
            keepassDatabases = emptyList(),
            bitwardenVaults = emptyList(),
            mdbxDatabases = emptyList(),
            selectedMdbxFolders = emptyList(),
            localSourceTitle = "Monica local",
            includeExternalSources = true,
        )

        assertEquals(listOf(rootItem), result.directItems)
        assertEquals(2, result.folderRows.single().itemCount)
    }

    @Test
    fun `keepass database shows root items and counts nested groups`() {
        val rootItem = passwordItem(id = 1, title = "Root", keepassDatabaseId = 7)
        val parentItem = passwordItem(
            id = 2,
            title = "Parent",
            keepassDatabaseId = 7,
            keepassGroupPath = "group-a",
        )
        val childItem = passwordItem(
            id = 3,
            title = "Child",
            keepassDatabaseId = 7,
            keepassGroupPath = "group-a/group-b",
        )

        val result = buildVaultV2HierarchicalContent(
            storageSelection = UnifiedCategoryFilterSelection.KeePassDatabaseFilter(7),
            keepassGroups = listOf(takagi.ru.monica.utils.KeePassGroupInfo("Group A", "group-a", null),
                takagi.ru.monica.utils.KeePassGroupInfo("Group B", "group-a/group-b", null, 1)),
            allItems = listOf(rootItem, parentItem, childItem),
            filteredItems = listOf(rootItem, parentItem, childItem),
            folderShortcuts = listOf(
                folderShortcut(
                    key = "group-a",
                    title = "Group A",
                    target = CategoryFilter.KeePassGroupFilter(7, "group-a"),
                )
            ),
            quickFolderNodes = emptyList(),
            keepassDatabases = emptyList(),
            bitwardenVaults = emptyList(),
            mdbxDatabases = emptyList(),
            selectedMdbxFolders = emptyList(),
            localSourceTitle = "Monica local",
            includeExternalSources = true,
        )

        assertEquals(listOf(rootItem), result.directItems)
        assertEquals(2, result.folderRows.single().itemCount)
    }

    @Test
    fun `mdbx parent folder count includes descendant folders`() {
        val folders = listOf(
            MdbxStoredFolderEntry("folder-a", null, "A", "a", 1),
            MdbxStoredFolderEntry("folder-b", "folder-a", "B", "a/b", 1),
        )
        val rootItem = passwordItem(id = 1, title = "Root", mdbxDatabaseId = 9)
        val parentItem = passwordItem(
            id = 2,
            title = "Parent",
            mdbxDatabaseId = 9,
            mdbxFolderId = "folder-a",
        )
        val childItem = passwordItem(
            id = 3,
            title = "Child",
            mdbxDatabaseId = 9,
            mdbxFolderId = "folder-b",
        )

        val result = buildVaultV2HierarchicalContent(
            storageSelection = UnifiedCategoryFilterSelection.MdbxDatabaseFilter(9),
            allItems = listOf(rootItem, parentItem, childItem),
            filteredItems = listOf(rootItem, parentItem, childItem),
            folderShortcuts = listOf(
                folderShortcut(
                    key = "folder-a",
                    title = "A",
                    target = CategoryFilter.MdbxFolderFilter(9, "folder-a"),
                )
            ),
            quickFolderNodes = emptyList(),
            keepassDatabases = emptyList(),
            bitwardenVaults = emptyList(),
            mdbxDatabases = emptyList(),
            selectedMdbxFolders = folders,
            localSourceTitle = "Monica local",
            includeExternalSources = true,
        )

        assertEquals(listOf(rootItem), result.directItems)
        assertEquals(2, result.folderRows.single().itemCount)
    }

    @Test
    fun `aggregate filters keep classic flat layout`() {
        assertFalse(UnifiedCategoryFilterSelection.Starred.supportsVaultV2FolderHierarchy())
        assertFalse(UnifiedCategoryFilterSelection.Uncategorized.supportsVaultV2FolderHierarchy())
        assertTrue(UnifiedCategoryFilterSelection.Local.supportsVaultV2FolderHierarchy())
    }

    private fun folderShortcut(
        key: String,
        title: String,
        target: CategoryFilter,
    ) = PasswordQuickFolderShortcut(
        key = key,
        title = title,
        subtitle = "",
        isBack = false,
        targetFilter = target,
        passwordCount = 0,
    )

    private fun passwordItem(
        id: Long,
        title: String,
        categoryId: Long? = null,
        keepassDatabaseId: Long? = null,
        keepassGroupPath: String? = null,
        mdbxDatabaseId: Long? = null,
        mdbxFolderId: String? = null,
    ): VaultV2Item {
        val entry = PasswordEntry(
            id = id,
            title = title,
            website = "",
            username = "",
            password = "",
            categoryId = categoryId,
            keepassDatabaseId = keepassDatabaseId,
            keepassGroupPath = keepassGroupPath,
            mdbxDatabaseId = mdbxDatabaseId,
            mdbxFolderId = mdbxFolderId,
        )
        return VaultV2Item(
            key = "password:$id",
            type = VaultV2ItemType.PASSWORD,
            title = title,
            subtitle = "",
            isFavorite = false,
            sortKey = title,
            searchableValues = listOf(title),
            passwordEntry = entry,
        )
    }

    private fun secureItem(
        id: Long,
        title: String,
        categoryId: Long?,
    ): VaultV2Item {
        val item = SecureItem(
            id = id,
            itemType = ItemType.NOTE,
            title = title,
            itemData = "{}",
            categoryId = categoryId,
        )
        return VaultV2Item(
            key = "note:$id",
            type = VaultV2ItemType.NOTE,
            title = title,
            subtitle = "",
            isFavorite = false,
            sortKey = title,
            searchableValues = listOf(title),
            secureItem = item,
        )
    }
}
