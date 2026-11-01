package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.utils.KeePassGroupInfo
import java.util.Locale

class VaultFolderNavigationTest {
    @Test fun alphabeticalFoldersFormOneStableBlockInEitherDirection() {
        fun folder(key: String, name: String) = VaultOverviewFolder(key, name, "local",
            UnifiedCategoryFilterSelection.Custom(1))
        val rows = listOf(folder("z", "zebra"), folder("b", "alpha"), folder("a", "Alpha"), folder("m", "Beta"))
        assertEquals(listOf("a", "b", "m", "z"), topLevelVaultOverviewFolders(rows, VaultListSort.TITLE_ASC, Locale.US).map { it.key })
        assertEquals(listOf("z", "m", "a", "b"), topLevelVaultOverviewFolders(rows, VaultListSort.TITLE_DESC, Locale.US).map { it.key })
        assertEquals(topLevelVaultOverviewFolders(rows, VaultListSort.TITLE_ASC, Locale.US),
            topLevelVaultOverviewFolders(rows.reversed(), VaultListSort.TITLE_ASC, Locale.US))
        VaultListSort.entries.filterNot { it.isAlphabetical }.forEach { sort ->
            assertEquals(rows, topLevelVaultOverviewFolders(rows, sort, Locale.US))
        }
    }

    @Test fun regionalCollationUsesNamesAndKeepsIdentities() {
        val names = listOf("zebra", "Árvore", "Banana")
        val rows = names.mapIndexed { index, name -> VaultV2FolderRowModel("$index", name, "",
            0, takagi.ru.monica.viewmodel.CategoryFilter.Custom(index.toLong()), VaultV2FolderRowKind.FOLDER) }
        assertEquals(listOf("Árvore", "Banana", "zebra"), sortVaultV2Folders(rows, VaultListSort.TITLE_ASC, Locale.forLanguageTag("pt-BR")).map { it.title })
        assertSame(rows, sortVaultV2Folders(rows, VaultListSort.UPDATED_DESC))
    }

    @Test fun overviewHidesChildrenButPreservesCompleteAggregationCatalogue() {
        val root = KeePassGroupInfo("A/B", "A%2FB", "p")
        val child = KeePassGroupInfo("Child", "A%2FB/Child", "c", depth = 1, parentUuid = "p")
        val sources = listOf(VaultOverviewSource("local", "Local", "Monica"),
            VaultOverviewSource("keepass:3", "KDBX", "KeePass"),
            VaultOverviewSource("mdbx:4", "MDBX", "MDBX"),
            VaultOverviewSource("bitwarden:5", "BW", "Bitwarden"))
        val entry = item(1, db = 3, path = child.path, uuid = "c")
        val result = buildVaultOverviewSnapshot(listOf(entry), sources, "all", VaultOverviewConfig(),
            emptyMap(), emptyMap(), listOf(Category(id = 10, name = "Local"), Category(id = 11, name = "Local/Child")),
            listOf(BitwardenFolder(id = 1, vaultId = 5, bitwardenFolderId = "flat", name = "Flat/Name", revisionDate = "2026-11-02T00:00:00Z")),
            mapOf(4L to listOf(MdbxStoredFolderEntry("root", null, "Root", "", 1),
                MdbxStoredFolderEntry("m", "root", "M", "M", 1), MdbxStoredFolderEntry("n", "m", "N", "M/N", 1))),
            emptyList(), mapOf(3L to listOf(root, child)), aggregate = ::aggregateVaultOverviewKotlin)
        assertEquals(7, result.folders.size)
        val displayed = topLevelVaultOverviewFolders(result.folders, VaultListSort.TITLE_ASC)
        assertEquals(setOf("local/10", "keepass:3/p", "mdbx:4/m", "bitwarden:5/flat"), displayed.map { it.key }.toSet())
        assertEquals(1, result.folders.single { it.key == "keepass:3/c" }.count)
        assertEquals(1, result.sourceCounts["keepass:3"])
    }

    @Test fun directFolderEntriesExcludeDescendantsAndOtherSources() {
        val local = buildVaultV2PasswordItems(listOf(PasswordEntry(id = 1, title = "Direct", website = "", username = "", password = "", categoryId = 10),
            PasswordEntry(id = 2, title = "Child", website = "", username = "", password = "", categoryId = 11),
            PasswordEntry(id = 3, title = "Foreign", website = "", username = "", password = "", categoryId = 10, bitwardenVaultId = 7, bitwardenCipherId = "synthetic-cipher")))
        assertEquals(listOf("password:1"), filterVaultV2DirectItems(UnifiedCategoryFilterSelection.Custom(10), local).map { it.key })
        val native = listOf(item(4, 3, "Same", "a"), item(5, 3, "Same", "b"), item(6, 3, "Same/Child", "c"), item(7, 4, "Same", "a"))
        assertEquals(listOf("password:4"), filterVaultV2DirectItems(UnifiedCategoryFilterSelection.KeePassGroupFilter(3, "Same", "a"), native).map { it.key })
    }

    @Test fun sameNamedKdbxParentsKeepSeparateChildrenAndSubtreeCounts() {
        val groups = listOf(KeePassGroupInfo("Same", "Same", "a"), KeePassGroupInfo("Same", "Same", "b"),
            KeePassGroupInfo("Child", "Same/Child", "ac", 1, parentUuid = "a"),
            KeePassGroupInfo("Child", "Same/Child", "bc", 1, parentUuid = "b"))
        assertEquals(listOf("ac"), directKeePassHierarchyGroups(groups, "Same", "a").map { it.uuid })
        assertEquals(listOf("bc"), directKeePassHierarchyGroups(groups, "Same", "b").map { it.uuid })
        assertTrue(directKeePassHierarchyGroups(groups, "Same", null).isEmpty())
        val rows = listOf(item(1, 3, "Same", "a"), item(2, 3, "Same/Child", "ac"),
            item(3, 3, "Same/Child", "bc"), item(4, 4, "Same/Child", "ac"))
        assertEquals(2, countKeePassHierarchyItems(3, groups[0], groups, rows))
        assertEquals(1, countKeePassHierarchyItems(3, groups[1], groups, rows))
    }

    @Test fun uuidLessLegacyPathsOnlyResolveWhenUnambiguous() {
        val unique = listOf(KeePassGroupInfo("A/B", "A%2FB", "a"),
            KeePassGroupInfo("Child", "A%2FB/Child", "c", 1))
        assertEquals(listOf("c"), directKeePassHierarchyGroups(unique, "A%2FB", "a").map { it.uuid })
        assertEquals(1, countKeePassHierarchyItems(3, unique[0], unique, listOf(item(1, 3, "A%2FB/Child", null))))
        val duplicate = unique + unique[1].copy(uuid = "other")
        assertEquals(0, countKeePassHierarchyItems(3, unique[0], duplicate, listOf(item(1, 3, "A%2FB/Child", null))))
    }

    @Test fun missingLocalParentRemainsReachableAtOverviewRoot() {
        val result = buildVaultOverviewSnapshot(emptyList(), listOf(VaultOverviewSource("local", "Local", "Monica")),
            "local", VaultOverviewConfig(), emptyMap(), emptyMap(), listOf(Category(id = 10, name = "Missing/Child")),
            emptyList(), emptyMap(), emptyList(), aggregate = ::aggregateVaultOverviewKotlin)
        assertEquals(listOf("local/10"), topLevelVaultOverviewFolders(result.folders, VaultListSort.TITLE_ASC).map { it.key })
    }

    @Test fun ambiguousPathOnlyItemsAreNotAssignedToEitherUuidFolder() {
        val groups = listOf(KeePassGroupInfo("Same", "Same", "a"), KeePassGroupInfo("Same", "Same", "b"))
        val rows = listOf(item(1, 3, "Same", null))
        val result = buildVaultOverviewSnapshot(rows, listOf(VaultOverviewSource("keepass:3", "KDBX", "KeePass")),
            "keepass:3", VaultOverviewConfig(), emptyMap(), emptyMap(), emptyList(), emptyList(), emptyMap(), emptyList(),
            mapOf(3L to groups), aggregate = ::aggregateVaultOverviewKotlin)
        assertEquals(1, result.items.size)
        assertEquals(2, result.folders.size)
        assertTrue(result.folders.all { it.count == 0 })
        assertTrue(filterVaultV2DirectItems(UnifiedCategoryFilterSelection.KeePassGroupFilter(3, "Same", "a"), rows, groups).isEmpty())
    }

    @Test fun browseModeRestoresWithoutChangingLegacySaverFields() {
        val values = listOf<Any?>(3, 7, 0, 0f, 0, VAULT_V2_STORAGE_FILTER_KEEPASS_GROUP,
            3L, "Parent", true, 0, false, null, null, null, "uuid", null,
            true, "keepass:3", null, false, 2, 8, null)
        val saver = vaultV2PaneStateSaver(VaultV2RetainedState())
        assertFalse(saver.restore(values)!!.overviewFolderBrowsing)
        val restored = saver.restore(values + true)!!
        assertTrue(restored.overviewFolderBrowsing)
        assertEquals("uuid", restored.storageFilterIdentityKey)
        assertEquals(3, restored.scrollIndex)
        restored.clearRetainedListSnapshots()
        assertNull(restored.overviewSnapshot)
    }

    private fun item(id: Long, db: Long, path: String, uuid: String?) = buildVaultV2PasswordItems(listOf(
        PasswordEntry(id = id, title = "Entry $id", website = "", username = "", password = "",
            keepassDatabaseId = db, keepassGroupPath = path, keepassGroupUuid = uuid))).single()
}
