package takagi.ru.monica.ui.vaultv2

import takagi.ru.monica.utils.KeePassGroupInfo

/** UUIDs keep equally named groups independent. Legacy paths resolve only when unique. */
internal class KeePassFolderHierarchy(private val groups: List<KeePassGroupInfo>) {
    private val byUuid = groups.filter { !it.uuid.isNullOrBlank() }.groupBy { it.uuid }
    private val byPath = groups.groupBy { it.path }
    private val byParentUuid = groups.filter { !it.parentUuid.isNullOrBlank() }.groupBy { it.parentUuid }
    private val legacyChildren = groups.filter { it.parentUuid.isNullOrBlank() && byPath[it.path]?.size == 1 }
        .groupBy { it.path.substringBeforeLast('/', "") }

    fun children(currentPath: String? = null, currentUuid: String? = null): List<KeePassGroupInfo> {
        if (currentPath == null) return groups.filter { it.path.isNotBlank() && '/' !in it.path }
        val parent = if (!currentUuid.isNullOrBlank()) byUuid[currentUuid]?.singleOrNull()
            else byPath[currentPath]?.singleOrNull()
        if (parent == null) return emptyList()
        return parent.uuid?.let { byParentUuid[it] }.orEmpty() +
            if (byPath[parent.path]?.size == 1) legacyChildren[parent.path].orEmpty() else emptyList()
    }

    fun countItems(databaseId: Long, root: KeePassGroupInfo, items: List<VaultV2Item>): Int {
        val descendants = linkedSetOf(root)
        val pending = ArrayDeque<KeePassGroupInfo>().apply { add(root) }
        while (pending.isNotEmpty() && descendants.size <= groups.size) {
            val parent = pending.removeFirst()
            children(parent.path, parent.uuid).forEach { child ->
                if (descendants.add(child)) pending.add(child)
            }
        }
        val uuids = descendants.mapNotNull { it.uuid?.takeIf(String::isNotBlank) }.toSet()
        val paths = descendants.map { it.path }.filter { byPath[it]?.size == 1 }.toSet()
        return items.count { item ->
            item.keepassDatabaseId() == databaseId &&
                if (item.keepassGroupUuid().isNullOrBlank()) item.keepassGroupPath() in paths
                else item.keepassGroupUuid() in uuids
        }
    }
}

internal fun directKeePassHierarchyGroups(groups: List<KeePassGroupInfo>, currentPath: String? = null,
    currentUuid: String? = null): List<KeePassGroupInfo> = KeePassFolderHierarchy(groups).children(currentPath, currentUuid)

internal fun countKeePassHierarchyItems(databaseId: Long, root: KeePassGroupInfo,
    groups: List<KeePassGroupInfo>, items: List<VaultV2Item>): Int = KeePassFolderHierarchy(groups).countItems(databaseId, root, items)
