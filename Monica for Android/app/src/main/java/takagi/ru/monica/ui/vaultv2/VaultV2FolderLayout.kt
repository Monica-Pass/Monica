package takagi.ru.monica.ui.vaultv2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.ui.PasswordQuickFolderNode
import takagi.ru.monica.ui.PasswordQuickFolderShortcut
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.viewmodel.CategoryFilter

internal enum class VaultV2FolderRowKind {
    LOCAL_SOURCE,
    KEEPASS_SOURCE,
    BITWARDEN_SOURCE,
    MDBX_SOURCE,
    FOLDER,
}

internal data class VaultV2FolderRowModel(
    val key: String,
    val title: String,
    val sourceName: String,
    val itemCount: Int,
    val targetFilter: CategoryFilter,
    val kind: VaultV2FolderRowKind,
)

internal data class VaultV2HierarchicalContent(
    val folderRows: List<VaultV2FolderRowModel> = emptyList(),
    val directItems: List<VaultV2Item> = emptyList(),
    val sections: List<Pair<String, List<VaultV2Item>>> = emptyList(),
)

internal fun UnifiedCategoryFilterSelection.supportsVaultV2FolderHierarchy(): Boolean = when (this) {
    UnifiedCategoryFilterSelection.All,
    UnifiedCategoryFilterSelection.Local,
    is UnifiedCategoryFilterSelection.Custom,
    is UnifiedCategoryFilterSelection.KeePassDatabaseFilter,
    is UnifiedCategoryFilterSelection.KeePassGroupFilter,
    is UnifiedCategoryFilterSelection.BitwardenVaultFilter,
    is UnifiedCategoryFilterSelection.BitwardenFolderFilter,
    is UnifiedCategoryFilterSelection.MdbxDatabaseFilter,
    is UnifiedCategoryFilterSelection.MdbxFolderFilter -> true

    else -> false
}

internal fun buildVaultV2HierarchicalContent(
    storageSelection: UnifiedCategoryFilterSelection,
    allItems: List<VaultV2Item>,
    filteredItems: List<VaultV2Item>,
    folderShortcuts: List<PasswordQuickFolderShortcut>,
    quickFolderNodes: List<PasswordQuickFolderNode>,
    keepassDatabases: List<LocalKeePassDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    mdbxDatabases: List<LocalMdbxDatabase>,
    selectedMdbxFolders: List<MdbxStoredFolderEntry>,
    localSourceTitle: String,
    includeExternalSources: Boolean,
): VaultV2HierarchicalContent {
    if (!storageSelection.supportsVaultV2FolderHierarchy()) {
        return VaultV2HierarchicalContent(
            directItems = filteredItems,
            sections = buildVaultV2Sections(filteredItems),
        )
    }

    val directItems = filterVaultV2DirectItems(storageSelection, filteredItems)
    val folderRows = if (storageSelection is UnifiedCategoryFilterSelection.All) {
        buildVaultV2SourceRows(
            allItems = allItems,
            keepassDatabases = keepassDatabases,
            bitwardenVaults = bitwardenVaults,
            mdbxDatabases = mdbxDatabases,
            localSourceTitle = localSourceTitle,
            includeExternalSources = includeExternalSources,
        )
    } else {
        val visibleShortcuts = when (storageSelection) {
            is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> emptyList()
            else -> folderShortcuts.filter { shortcut ->
                !shortcut.isBack && shortcut.isDirectHierarchyChildOf(
                    storageSelection = storageSelection,
                    quickFolderNodes = quickFolderNodes,
                    selectedMdbxFolders = selectedMdbxFolders,
                )
            }
        }
        visibleShortcuts.map { shortcut ->
            VaultV2FolderRowModel(
                key = "hierarchy:${shortcut.key}",
                title = shortcut.title,
                sourceName = folderSourceName(
                    targetFilter = shortcut.targetFilter,
                    localSourceTitle = localSourceTitle,
                    keepassDatabases = keepassDatabases,
                    bitwardenVaults = bitwardenVaults,
                    mdbxDatabases = mdbxDatabases,
                ),
                itemCount = countVaultV2FolderDescendants(
                    targetFilter = shortcut.targetFilter,
                    allItems = allItems,
                    quickFolderNodes = quickFolderNodes,
                    selectedMdbxFolders = selectedMdbxFolders,
                ),
                targetFilter = shortcut.targetFilter,
                kind = VaultV2FolderRowKind.FOLDER,
            )
        }
    }

    return VaultV2HierarchicalContent(
        folderRows = folderRows,
        directItems = directItems,
        sections = buildVaultV2Sections(directItems),
    )
}

internal fun filterVaultV2DirectItems(
    storageSelection: UnifiedCategoryFilterSelection,
    filteredItems: List<VaultV2Item>,
): List<VaultV2Item> = when (storageSelection) {
    UnifiedCategoryFilterSelection.All -> emptyList()
    UnifiedCategoryFilterSelection.Local -> filteredItems.filter { it.categoryId().let { id -> id == null } }
    is UnifiedCategoryFilterSelection.Custom -> filteredItems
    is UnifiedCategoryFilterSelection.KeePassDatabaseFilter ->
        filteredItems.filter { it.keepassGroupPath().isNullOrBlank() }
    is UnifiedCategoryFilterSelection.KeePassGroupFilter -> filteredItems
    is UnifiedCategoryFilterSelection.BitwardenVaultFilter ->
        filteredItems.filter { it.bitwardenFolderId().isNullOrBlank() }
    is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> filteredItems
    is UnifiedCategoryFilterSelection.MdbxDatabaseFilter ->
        filteredItems.filter { it.mdbxFolderId().isNullOrBlank() && it.categoryId() == null }
    is UnifiedCategoryFilterSelection.MdbxFolderFilter -> filteredItems
    else -> filteredItems
}

internal fun countVaultV2FolderDescendants(
    targetFilter: CategoryFilter,
    allItems: List<VaultV2Item>,
    quickFolderNodes: List<PasswordQuickFolderNode>,
    selectedMdbxFolders: List<MdbxStoredFolderEntry>,
): Int {
    return vaultV2ItemsInFolder(targetFilter, allItems, quickFolderNodes, selectedMdbxFolders).size
}

internal fun vaultV2ItemsInFolder(
    targetFilter: CategoryFilter,
    allItems: List<VaultV2Item>,
    quickFolderNodes: List<PasswordQuickFolderNode>,
    selectedMdbxFolders: List<MdbxStoredFolderEntry>,
): List<VaultV2Item> {
    return when (targetFilter) {
        CategoryFilter.Local -> allItems.filter(VaultV2Item::isLocalOnly)
        is CategoryFilter.KeePassDatabase -> allItems.filter { it.keepassDatabaseId() == targetFilter.databaseId }
        is CategoryFilter.BitwardenVault -> allItems.filter { it.bitwardenVaultId() == targetFilter.vaultId }
        is CategoryFilter.MdbxDatabase -> allItems.filter { it.mdbxDatabaseId() == targetFilter.databaseId }
        is CategoryFilter.Custom -> {
            val pathByCategoryId = quickFolderNodes.associate { it.category.id to it.path }
            val targetPath = pathByCategoryId[targetFilter.categoryId]
            allItems.filter { item ->
                if (!item.isLocalOnly()) return@filter false
                val itemCategoryId = item.categoryId() ?: return@filter false
                if (itemCategoryId == targetFilter.categoryId) return@filter true
                val itemPath = pathByCategoryId[itemCategoryId] ?: return@filter false
                targetPath != null && (itemPath == targetPath || itemPath.startsWith("$targetPath/"))
            }
        }

        is CategoryFilter.KeePassGroupFilter -> {
            val targetSegments = encodedKeePassPathSegments(targetFilter.groupPath)
            allItems.filter { item ->
                if (item.keepassDatabaseId() != targetFilter.databaseId) return@filter false
                val itemSegments = encodedKeePassPathSegments(item.keepassGroupPath())
                itemSegments.size >= targetSegments.size &&
                    itemSegments.take(targetSegments.size) == targetSegments
            }
        }

        is CategoryFilter.BitwardenFolderFilter -> allItems.filter { item ->
            item.bitwardenVaultId() == targetFilter.vaultId &&
                item.bitwardenFolderId()?.trim() == targetFilter.folderId.trim()
        }

        is CategoryFilter.MdbxFolderFilter -> {
            val descendantFolderIds = collectMdbxDescendantFolderIds(
                rootFolderId = targetFilter.folderId,
                folders = selectedMdbxFolders,
            )
            allItems.filter { item ->
                item.mdbxDatabaseId() == targetFilter.databaseId &&
                    descendantFolderIds.any { folderId ->
                        item.matchesMdbxFolder(targetFilter.databaseId, folderId)
                    }
            }
        }

        else -> emptyList()
    }
}

internal fun buildVaultV2Sections(
    items: List<VaultV2Item>,
): List<Pair<String, List<VaultV2Item>>> {
    val groupedItems = items.groupBy { item -> firstLetterGroup(item.sortKey) }
    return groupedItems.keys
        .sortedWith(compareBy<String> { if (it == "#") 1 else 0 }.thenBy { it })
        .map { section -> section to groupedItems[section].orEmpty() }
}

internal fun buildVaultV2SectionLayouts(
    sections: List<Pair<String, List<VaultV2Item>>>,
    leadingItemCount: Int,
): List<VaultV2SectionLayout> {
    var itemStartIndex = 0
    var lazyIndex = leadingItemCount.coerceAtLeast(0)
    return sections.map { (sectionTitle, itemsInSection) ->
        VaultV2SectionLayout(
            title = sectionTitle,
            items = itemsInSection,
            itemStartIndex = itemStartIndex,
            firstItemLazyIndex = lazyIndex + 1,
        ).also {
            itemStartIndex += itemsInSection.size
            lazyIndex += itemsInSection.size + 1
        }
    }
}

private fun buildVaultV2SourceRows(
    allItems: List<VaultV2Item>,
    keepassDatabases: List<LocalKeePassDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    mdbxDatabases: List<LocalMdbxDatabase>,
    localSourceTitle: String,
    includeExternalSources: Boolean,
): List<VaultV2FolderRowModel> = buildList {
    add(
        VaultV2FolderRowModel(
            key = "hierarchy:source:local",
            title = localSourceTitle,
            sourceName = "Monica",
            itemCount = allItems.count(VaultV2Item::isLocalOnly),
            targetFilter = CategoryFilter.Local,
            kind = VaultV2FolderRowKind.LOCAL_SOURCE,
        )
    )
    if (!includeExternalSources) return@buildList

    keepassDatabases.forEach { database ->
        add(
            VaultV2FolderRowModel(
                key = "hierarchy:source:keepass:${database.id}",
                title = database.name.ifBlank { "KeePass" },
                sourceName = "KeePass",
                itemCount = allItems.count { it.keepassDatabaseId() == database.id },
                targetFilter = CategoryFilter.KeePassDatabase(database.id),
                kind = VaultV2FolderRowKind.KEEPASS_SOURCE,
            )
        )
    }
    bitwardenVaults.forEach { vault ->
        add(
            VaultV2FolderRowModel(
                key = "hierarchy:source:bitwarden:${vault.id}",
                title = vault.displayName?.takeIf(String::isNotBlank) ?: vault.email,
                sourceName = "Bitwarden",
                itemCount = allItems.count { it.bitwardenVaultId() == vault.id },
                targetFilter = CategoryFilter.BitwardenVault(vault.id),
                kind = VaultV2FolderRowKind.BITWARDEN_SOURCE,
            )
        )
    }
    mdbxDatabases.forEach { database ->
        add(
            VaultV2FolderRowModel(
                key = "hierarchy:source:mdbx:${database.id}",
                title = database.name.ifBlank { "MDBX" },
                sourceName = "MDBX",
                itemCount = allItems.count { it.mdbxDatabaseId() == database.id },
                targetFilter = CategoryFilter.MdbxDatabase(database.id),
                kind = VaultV2FolderRowKind.MDBX_SOURCE,
            )
        )
    }
}

private fun folderSourceName(
    targetFilter: CategoryFilter,
    localSourceTitle: String,
    keepassDatabases: List<LocalKeePassDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    mdbxDatabases: List<LocalMdbxDatabase>,
): String = when (targetFilter) {
    is CategoryFilter.Custom -> localSourceTitle
    is CategoryFilter.KeePassGroupFilter ->
        keepassDatabases.firstOrNull { it.id == targetFilter.databaseId }?.name ?: "KeePass"
    is CategoryFilter.BitwardenFolderFilter ->
        bitwardenVaults.firstOrNull { it.id == targetFilter.vaultId }
            ?.let { it.displayName?.takeIf(String::isNotBlank) ?: it.email }
            ?: "Bitwarden"
    is CategoryFilter.MdbxFolderFilter ->
        mdbxDatabases.firstOrNull { it.id == targetFilter.databaseId }?.name ?: "MDBX"
    else -> ""
}

private fun PasswordQuickFolderShortcut.isDirectHierarchyChildOf(
    storageSelection: UnifiedCategoryFilterSelection,
    quickFolderNodes: List<PasswordQuickFolderNode>,
    selectedMdbxFolders: List<MdbxStoredFolderEntry>,
): Boolean {
    return when (storageSelection) {
        UnifiedCategoryFilterSelection.Local -> {
            val categoryId = (targetFilter as? CategoryFilter.Custom)?.categoryId ?: return false
            quickFolderNodes.firstOrNull { it.category.id == categoryId }?.parentPath == null
        }

        is UnifiedCategoryFilterSelection.Custom -> {
            val currentPath = quickFolderNodes
                .firstOrNull { it.category.id == storageSelection.categoryId }
                ?.path
                ?: return false
            val categoryId = (targetFilter as? CategoryFilter.Custom)?.categoryId ?: return false
            quickFolderNodes.firstOrNull { it.category.id == categoryId }?.parentPath == currentPath
        }

        is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> {
            val target = targetFilter as? CategoryFilter.KeePassGroupFilter ?: return false
            target.databaseId == storageSelection.databaseId &&
                target.groupPath.trim('/').substringBeforeLast('/', missingDelimiterValue = "").isBlank()
        }

        is UnifiedCategoryFilterSelection.KeePassGroupFilter -> {
            val target = targetFilter as? CategoryFilter.KeePassGroupFilter ?: return false
            target.databaseId == storageSelection.databaseId &&
                target.groupPath.trim('/').substringBeforeLast('/', missingDelimiterValue = "") ==
                storageSelection.groupPath.trim('/')
        }

        is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> {
            val target = targetFilter as? CategoryFilter.BitwardenFolderFilter ?: return false
            target.vaultId == storageSelection.vaultId
        }

        is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> {
            val target = targetFilter as? CategoryFilter.MdbxFolderFilter ?: return false
            target.databaseId == storageSelection.databaseId &&
                selectedMdbxFolders.firstOrNull { it.folderId == target.folderId }
                    ?.parentFolderId
                    .let(::normalizeMdbxFolderParentId) == null
        }

        is UnifiedCategoryFilterSelection.MdbxFolderFilter -> {
            val target = targetFilter as? CategoryFilter.MdbxFolderFilter ?: return false
            target.databaseId == storageSelection.databaseId &&
                selectedMdbxFolders.firstOrNull { it.folderId == target.folderId }
                    ?.parentFolderId
                    .let(::normalizeMdbxFolderParentId) == storageSelection.folderId.trim()
        }

        else -> false
    }
}

private fun collectMdbxDescendantFolderIds(
    rootFolderId: String,
    folders: List<MdbxStoredFolderEntry>,
): Set<String> {
    val normalizedRoot = rootFolderId.trim()
    if (normalizedRoot.isBlank()) return emptySet()
    val childrenByParent = folders
        .filter { it.folderId.isNotBlank() }
        .groupBy { normalizeMdbxFolderParentId(it.parentFolderId) }
    val result = linkedSetOf(normalizedRoot)
    val pending = ArrayDeque<String>().apply { add(normalizedRoot) }
    while (pending.isNotEmpty() && result.size <= folders.size + 1) {
        val parentId = pending.removeFirst()
        childrenByParent[parentId].orEmpty().forEach { child ->
            if (result.add(child.folderId)) {
                pending.add(child.folderId)
            }
        }
    }
    return result
}

private fun normalizeMdbxFolderParentId(value: String?): String? {
    val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    return normalized.takeUnless { it.equals("root", ignoreCase = true) }
}

private fun encodedKeePassPathSegments(path: String?): List<String> = path
    ?.trim('/')
    ?.split('/')
    ?.map(String::trim)
    ?.filter(String::isNotBlank)
    .orEmpty()

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VaultV2FolderRow(
    row: VaultV2FolderRowModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
) {
    val isSource = row.kind != VaultV2FolderRowKind.FOLDER
    val icon = row.kind.icon()
    val keePassGroup = row.targetFilter as? CategoryFilter.KeePassGroupFilter
    val keePassIcon = takagi.ru.monica.ui.icons.rememberKeePassCustomIcon(
        databaseId = keePassGroup?.databaseId,
        groupUuid = keePassGroup?.groupUuid,
        groupPath = keePassGroup?.groupPath,
    )
    val iconContainerColor = if (isSource) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val iconContentColor = if (isSource) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val itemCountText = pluralStringResource(
        id = R.plurals.vault_v2_hierarchy_item_count,
        count = row.itemCount,
        row.itemCount,
    )
    val supportingText = listOf(row.sourceName, itemCountText)
        .filter(String::isNotBlank)
        .joinToString(" · ")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .testTag("vault_folder_${row.key}")
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = if (isSource) 1.dp else 0.dp,
        shadowElevation = if (isSource) 1.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = iconContainerColor,
                contentColor = iconContentColor,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (keePassIcon != null) {
                        takagi.ru.monica.ui.icons.KeePassCustomIconImage(keePassIcon, Modifier.size(36.dp))
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun VaultV2FolderRowKind.icon(): ImageVector = when (this) {
    VaultV2FolderRowKind.LOCAL_SOURCE -> Icons.Default.Folder
    VaultV2FolderRowKind.KEEPASS_SOURCE -> Icons.Default.Lock
    VaultV2FolderRowKind.BITWARDEN_SOURCE -> Icons.Default.Security
    VaultV2FolderRowKind.MDBX_SOURCE -> Icons.Default.Storage
    VaultV2FolderRowKind.FOLDER -> Icons.Default.Folder
}
