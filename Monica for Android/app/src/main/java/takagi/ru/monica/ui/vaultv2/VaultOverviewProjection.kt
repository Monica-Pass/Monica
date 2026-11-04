package takagi.ru.monica.ui.vaultv2

import takagi.ru.monica.data.Category
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordQuickAccessRecord
import takagi.ru.monica.data.VaultOverviewConfig
import takagi.ru.monica.data.VaultOverviewUsage
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.vaultOverviewKey
import takagi.ru.monica.data.vaultOverviewSourceKey
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.utils.KeePassGroupInfo
import takagi.ru.monica.utils.decodeKeePassPathForDisplay

internal data class VaultOverviewSource(
    val key: String,
    val name: String,
    val provider: String,
    val locked: Boolean = false,
)

internal data class VaultOverviewFolder(
    val key: String,
    val name: String,
    val sourceKey: String,
    val target: UnifiedCategoryFilterSelection,
    val count: Int = 0,
    val isTopLevel: Boolean = true,
)

internal data class VaultOverviewSnapshot(
    val scope: String,
    val accessibleSources: Set<String> = emptySet(),
    val items: List<VaultV2Item> = emptyList(),
    val cards: List<VaultV2Item> = emptyList(),
    val frequentItems: List<VaultV2Item> = emptyList(),
    val recentItems: List<VaultV2Item> = emptyList(),
    val favorites: List<VaultV2Item> = emptyList(),
    val typeCounts: Map<VaultV2ItemType, Int> = emptyMap(),
    val sourceCounts: Map<String, Int> = emptyMap(),
    val folders: List<VaultOverviewFolder> = emptyList(),
    val archiveCount: Int = 0,
)

/** Read-only metadata shared by scope changes; no strings or item decoding cross JNI. */
internal class VaultOverviewPreparedData(
    val items: List<VaultV2Item>,
    val sources: List<VaultOverviewSource>,
    val accessibleSources: Set<String>,
    val sourceIndices: Map<String, Int>,
    val folders: List<VaultOverviewFolder>,
    val metadata: LongArray,
    val archiveCounts: IntArray,
)

internal val overviewCardTypes = setOf(VaultV2ItemType.BANK_CARD, VaultV2ItemType.DOCUMENT, VaultV2ItemType.BILLING_ADDRESS)

internal fun VaultV2Item.overviewIdentity(): String = passwordEntry?.vaultOverviewKey()
    ?: (secureItem ?: totpItem)?.vaultOverviewKey() ?: passkeyEntry?.vaultOverviewKey() ?: key

internal fun VaultV2Item.overviewSource(): String =
    vaultOverviewSourceKey(bitwardenVaultId(), keepassDatabaseId(), mdbxDatabaseId())

internal fun overviewScopeSelection(scope: String): UnifiedCategoryFilterSelection {
    val id = scope.substringAfter(':', "").toLongOrNull()
    return when {
        scope == "all" -> UnifiedCategoryFilterSelection.All
        scope.startsWith("bitwarden:") && id != null -> UnifiedCategoryFilterSelection.BitwardenVaultFilter(id)
        scope.startsWith("keepass:") && id != null -> UnifiedCategoryFilterSelection.KeePassDatabaseFilter(id)
        scope.startsWith("mdbx:") && id != null -> UnifiedCategoryFilterSelection.MdbxDatabaseFilter(id)
        else -> UnifiedCategoryFilterSelection.Local
    }
}

internal fun UnifiedCategoryFilterSelection.overviewScope(): String = when (this) {
    UnifiedCategoryFilterSelection.All -> "all"
    is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> "bitwarden:$vaultId"
    is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> "bitwarden:$vaultId"
    is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> "bitwarden:$vaultId"
    is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> "bitwarden:$vaultId"
    is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> "keepass:$databaseId"
    is UnifiedCategoryFilterSelection.KeePassGroupFilter -> "keepass:$databaseId"
    is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> "keepass:$databaseId"
    is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> "keepass:$databaseId"
    is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> "mdbx:$databaseId"
    is UnifiedCategoryFilterSelection.MdbxFolderFilter -> "mdbx:$databaseId"
    else -> "local"
}

internal fun buildVaultOverviewSnapshot(
    allItems: List<VaultV2Item>,
    sources: List<VaultOverviewSource>,
    scope: String,
    config: VaultOverviewConfig,
    usage: Map<String, VaultOverviewUsage>,
    passwordUsage: Map<Long, PasswordQuickAccessRecord>,
    categories: List<Category>,
    bitwardenFolders: List<BitwardenFolder>,
    mdbxFolders: Map<Long, List<MdbxStoredFolderEntry>>,
    archivedPasswords: List<PasswordEntry>,
    keepassGroups: Map<Long, List<KeePassGroupInfo>> = emptyMap(),
    aggregate: (LongArray) -> VaultOverviewAggregation = ::aggregateVaultOverview,
): VaultOverviewSnapshot = projectVaultOverview(
    prepareVaultOverview(allItems, sources, config, usage, passwordUsage, categories, bitwardenFolders,
        mdbxFolders, archivedPasswords, keepassGroups), scope, aggregate)

internal fun prepareVaultOverview(
    allItems: List<VaultV2Item>,
    sources: List<VaultOverviewSource>,
    config: VaultOverviewConfig,
    usage: Map<String, VaultOverviewUsage>,
    passwordUsage: Map<Long, PasswordQuickAccessRecord>,
    categories: List<Category>,
    bitwardenFolders: List<BitwardenFolder>,
    mdbxFolders: Map<Long, List<MdbxStoredFolderEntry>>,
    archivedPasswords: List<PasswordEntry>,
    keepassGroups: Map<Long, List<KeePassGroupInfo>> = emptyMap(),
    archivedSecureItems: List<takagi.ru.monica.data.SecureItem> = emptyList(),
): VaultOverviewPreparedData {
    val availableSources = sources.filterNot { it.locked }.mapTo(hashSetOf()) { it.key }
    val sourceIndices = sources.withIndex().associate { it.value.key to it.index }
    val folders = linkedMapOf<String, VaultOverviewFolder>()
    fun addFolder(folder: VaultOverviewFolder) {
        if (folder.sourceKey in availableSources) folders[folder.key] = folder
    }
    val localCategories = categories.filter { it.bitwardenVaultId == null && it.mdbxDatabaseId == null }
    val localPaths = localCategories.map { takagi.ru.monica.ui.normalizePasswordQuickFolderPath(it.name) }.toSet()
    localCategories.forEach {
        addFolder(VaultOverviewFolder("local/${it.id}", it.name, "local", UnifiedCategoryFilterSelection.Custom(it.id),
            isTopLevel = takagi.ru.monica.ui.passwordQuickFolderParentPath(it.name).let { parent -> parent == null || parent !in localPaths }))
    }
    categories.filter { it.mdbxDatabaseId != null }.forEach {
        val source = "mdbx:${it.mdbxDatabaseId}"
        val folderId = "category:${it.id}"
        addFolder(VaultOverviewFolder("$source/$folderId", it.name, source,
            UnifiedCategoryFilterSelection.MdbxFolderFilter(it.mdbxDatabaseId!!, folderId)))
    }
    bitwardenFolders.forEach {
        val source = "bitwarden:${it.vaultId}"
        addFolder(VaultOverviewFolder("$source/${it.bitwardenFolderId}", it.name, source,
            UnifiedCategoryFilterSelection.BitwardenFolderFilter(it.vaultId, it.bitwardenFolderId)))
    }
    mdbxFolders.forEach { (databaseId, entries) -> entries.filter { it.folderId != "root" }.forEach {
        val source = "mdbx:$databaseId"
        addFolder(VaultOverviewFolder("$source/${it.folderId}", it.name, source,
            UnifiedCategoryFilterSelection.MdbxFolderFilter(databaseId, it.folderId),
            isTopLevel = it.parentFolderId?.trim().let { parent -> parent.isNullOrBlank() || parent.equals("root", ignoreCase = true) }))
    } }
    // Passkeys carry a path but no group UUID. Resolve both representations to the
    // same folder identity before assigning numeric indices to Kotlin/Rust aggregation.
    val ambiguousKeePassPaths = keepassGroups.flatMap { (id, groups) ->
        groups.groupBy { it.path }.filterValues { it.size > 1 }.keys.map { id to it }
    }.toSet()
    val keepassFolderKeysByPath = mutableMapOf<Pair<Long, String>, String>()
    keepassGroups.forEach { (databaseId, groups) -> groups.filter { it.path.isNotBlank() }.forEach { group ->
        val source = "keepass:$databaseId"
        val key = "$source/${group.uuid?.takeIf(String::isNotBlank) ?: group.path}"
        if ((databaseId to group.path) !in ambiguousKeePassPaths) keepassFolderKeysByPath[databaseId to group.path] = key
        addFolder(VaultOverviewFolder(key, group.displayPath, source,
            UnifiedCategoryFilterSelection.KeePassGroupFilter(databaseId, group.path, group.uuid),
            isTopLevel = !group.path.trim('/').contains('/')))
    } }
    // Also cover the initial snapshot before the group catalogue has loaded. A
    // two-pass lookup makes identity independent of password/passkey item order.
    allItems.forEach { item ->
        val databaseId = item.keepassDatabaseId() ?: return@forEach
        val path = item.keepassGroupPath()?.takeIf(String::isNotBlank) ?: return@forEach
        val uuid = item.keepassGroupUuid()?.takeIf(String::isNotBlank) ?: return@forEach
        keepassFolderKeysByPath.putIfAbsent(databaseId to path, "keepass:$databaseId/$uuid")
    }
    // Resolve paths and identities once in the background. Native code only sees numeric indices.
    val itemSources = Array(allItems.size) { allItems[it].overviewSource() }
    val itemFolderKeys = arrayOfNulls<String>(allItems.size)
    allItems.forEachIndexed { index, item ->
        val source = itemSources[index]
        if (source !in availableSources) return@forEachIndexed
        val folderKey = when {
            source == "local" -> item.categoryId()?.let { "local/$it" }
            source.startsWith("bitwarden:") -> item.bitwardenFolderId()?.let { "$source/$it" }
            source.startsWith("mdbx:") -> item.mdbxFolderId()?.takeIf(String::isNotBlank)?.let { "$source/$it" }
                ?: item.categoryId()?.let { "$source/category:$it" }
            source.startsWith("keepass:") -> item.keepassGroupPath()?.takeIf(String::isNotBlank)?.let { path ->
                val databaseId = item.keepassDatabaseId()!!
                val uuid = item.keepassGroupUuid()?.takeIf(String::isNotBlank)
                if (uuid == null && (databaseId to path) in ambiguousKeePassPaths) return@let null
                val key = uuid?.let { "$source/$it" }
                    ?: keepassFolderKeysByPath[databaseId to path] ?: "$source/$path"
                val resolvedUuid = uuid ?: key.removePrefix("$source/").takeIf { it != path }
                folders.getOrPut(key) {
                    VaultOverviewFolder(key, decodeKeePassPathForDisplay(path), source,
                        UnifiedCategoryFilterSelection.KeePassGroupFilter(databaseId, path, resolvedUuid),
                        isTopLevel = !path.trim('/').contains('/'))
                }
                key
            }
            else -> null
        }
        itemFolderKeys[index] = folderKey
    }
    val folderList = folders.values.sortedWith(compareBy<VaultOverviewFolder> { it.sourceKey }.thenBy { it.name })
    val folderIndices = folderList.withIndex().associate { it.value.key to it.index }
    val cardPins = config.pinnedCards.withIndex().associate { it.value to it.index }
    val itemPins = config.pinnedItems.withIndex().associate { it.value to it.index }
    val batch = LongArray(OVERVIEW_HEADER + allItems.size * OVERVIEW_ROW_WIDTH)
    batch[0] = 1
    batch[1] = sources.size.toLong()
    batch[2] = folderList.size.toLong()
    batch[3] = -1
    batch[4] = (if (config.recommendCards) 1L else 0L) or (if (config.recommendItems) 2L else 0L)
    batch[5] = VaultV2ItemType.entries.size.toLong()
    allItems.forEachIndexed { index, item ->
        val offset = OVERVIEW_HEADER + index * OVERVIEW_ROW_WIDTH
        val source = itemSources[index]
        val identity = item.overviewIdentity()
        val record = usage[identity]
        val legacy = item.passwordEntry?.id?.let(passwordUsage::get)
        val card = item.type in overviewCardTypes
        val excluded = !card && identity in config.excludedFrequentItems
        batch[offset] = if (source in availableSources) (sourceIndices[source] ?: -1).toLong() else -1
        batch[offset + 1] = item.type.ordinal.toLong()
        batch[offset + 2] = (itemFolderKeys[index]?.let(folderIndices::get) ?: -1).toLong()
        batch[offset + 3] = if (item.isFavorite) 1 else 0
        // Both aggregators skip unpinned rows with zero usage, without changing vault counts or favorites.
        batch[offset + 4] = if (excluded) -1 else ((if (card) cardPins else itemPins)[identity] ?: -1).toLong()
        batch[offset + 5] = if (excluded) 0 else maxOf(record?.count ?: 0, legacy?.openCount ?: 0, item.passkeyEntry?.useCount ?: 0).toLong().coerceAtLeast(0)
        batch[offset + 6] = maxOf(record?.lastOpenedAt ?: 0, legacy?.lastOpenedAt ?: 0,
            item.passkeyEntry?.takeIf { it.useCount > 0 }?.lastUsedAt ?: 0).coerceAtLeast(0)
        batch[offset + 7] = if (card) 1 else 0
    }
    val archiveCounts = IntArray(sources.size)
    archivedPasswords.forEach {
        val source = vaultOverviewSourceKey(it.bitwardenVaultId, it.keepassDatabaseId, it.mdbxDatabaseId)
        if (source in availableSources) sourceIndices[source]?.let { index -> archiveCounts[index]++ }
    }
    archivedSecureItems.forEach {
        val source = vaultOverviewSourceKey(it.bitwardenVaultId, it.keepassDatabaseId, it.mdbxDatabaseId)
        if (source in availableSources) sourceIndices[source]?.let { index -> archiveCounts[index]++ }
    }
    return VaultOverviewPreparedData(allItems, sources, availableSources, sourceIndices, folderList, batch, archiveCounts)
}

internal fun projectVaultOverview(
    prepared: VaultOverviewPreparedData,
    scope: String,
    aggregate: (LongArray) -> VaultOverviewAggregation = ::aggregateVaultOverview,
): VaultOverviewSnapshot {
    // Unknown scopes have no accessible rows; the caller confirms removal before restoring local.
    val selected = if (scope == "all") -1 else prepared.sourceIndices[scope]
        ?: return VaultOverviewSnapshot(scope, prepared.accessibleSources)
    // Keep the cached batch immutable so cancelled/overlapping projections cannot alter each other's scope.
    val batch = if (selected == -1) prepared.metadata else prepared.metadata.copyOf().apply { this[3] = selected.toLong() }
    val result = aggregate(batch)
    fun IntArray.rows(): List<VaultV2Item> = map(prepared.items::get)
    return VaultOverviewSnapshot(
        scope = scope, accessibleSources = prepared.accessibleSources, items = result.visible.rows(),
        recentItems = recentVaultOverviewItems(result.visible.rows()),
        cards = result.cards.rows(), frequentItems = result.items.rows(), favorites = result.favorites.rows(),
        typeCounts = VaultV2ItemType.entries.associateWith { result.typeCounts[it.ordinal] },
        sourceCounts = prepared.sources.mapIndexed { index, source -> source.key to result.sourceCounts[index] }.toMap(),
        folders = prepared.folders.mapIndexedNotNull { index, folder ->
            if (scope == "all" || folder.sourceKey == scope) folder.copy(count = result.folderCounts[index]) else null
        },
        archiveCount = if (selected == -1) prepared.archiveCounts.sum() else prepared.archiveCounts[selected],
    )
}

/** Uses only already scoped, unlocked and non-archived rows. Does not mutate entry dates. */
internal fun recentVaultOverviewItems(items: List<VaultV2Item>): List<VaultV2Item> =
    sortVaultV2Items(items.filter { it.sortTimestamp(takagi.ru.monica.data.VaultListSort.RECENT_DESC) != null },
        takagi.ru.monica.data.VaultListSort.RECENT_DESC).take(OVERVIEW_PREVIEW_LIMIT)
