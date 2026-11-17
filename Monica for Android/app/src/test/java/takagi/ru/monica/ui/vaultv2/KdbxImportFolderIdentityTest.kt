package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.data.*
import takagi.ru.monica.utils.KeePassGroupInfo
import takagi.ru.monica.utils.buildKeePassPathKey
import java.util.UUID

/** Old import projection versus the authenticated catalogue and the refreshed click filter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class KdbxImportFolderIdentityTest {
    @Test fun importedNonAsciiFolderHasOneCountAndOneNavigationIdentity() {
        val groupId = UUID.randomUUID()
        val name = "合成分组"
        val imported = buildVaultV2PasswordItems(listOf(PasswordEntry(id = 1, title = "Synthetic login",
            username = "", website = "", password = "", keepassDatabaseId = 3, keepassGroupPath = name)))
        val canonicalPath = buildKeePassPathKey(null, name)
        val canonicalRows = imported.map { it.copy(passwordEntry = it.passwordEntry!!.copy(
            keepassGroupPath = canonicalPath, keepassGroupUuid = groupId.toString())) }
        val result = buildVaultOverviewSnapshot(imported, listOf(VaultOverviewSource("keepass:3", "Synthetic", "KeePass")),
            "keepass:3", VaultOverviewConfig(), emptyMap(), emptyMap(), emptyList(), emptyList(), emptyMap(), emptyList(),
            mapOf(3L to listOf(KeePassGroupInfo(name, canonicalPath, groupId.toString()))), aggregate = ::aggregateVaultOverviewKotlin)
        val diagnostic = result.folders.map { folder -> "${folder.key}: count=${folder.count}, opened=" +
            filterVaultV2DirectItems(folder.target, canonicalRows).size }
        assertEquals("Import + native reread must agree: $diagnostic", 1, result.folders.size)
        assertEquals(1, result.folders.single().count)
        assertEquals(1, filterVaultV2DirectItems(result.folders.single().target, canonicalRows).size)
    }

    @Test fun legacyNestedSlashesPlusAndLiteralPercentDecodeExactlyOnce() {
        val path = "Parent/A%2FB%2B%2520"
        val result = snapshot("Parent/A/B+%20", listOf(KeePassGroupInfo("A/B+%20", path, "g", 1)))
        assertEquals(1, result.folders.size)
        assertEquals(1, result.folders.single().count)
        assertEquals(path, (result.folders.single().target as takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection.KeePassGroupFilter).groupPath)
    }

    @Test fun ambiguousLegacySameNamedGroupsRetainItemsWithoutAssigningThem() {
        val path = buildKeePassPathKey(null, "合成分组")
        val result = snapshot("合成分组", listOf(KeePassGroupInfo("合成分组", path, "a"), KeePassGroupInfo("合成分组", path, "b")))
        assertEquals(1, result.items.size)
        assertEquals(2, result.folders.size)
        assertTrue(result.folders.all { it.count == 0 })
    }

    @Test fun slashAliasCannotChooseBetweenLiteralAndNestedGroups() {
        val result = snapshot("A/B", listOf(KeePassGroupInfo("A/B", "A%2FB", "literal"),
            KeePassGroupInfo("B", "A/B", "nested", 1)))
        assertEquals(1, result.items.size)
        assertEquals(2, result.folders.size)
        assertTrue(result.folders.all { it.count == 0 })
    }

    @Test fun uuidKeepsSameNamedFoldersSeparateEvenForOldPaths() {
        val path = buildKeePassPathKey(null, "合成分组")
        val result = snapshot("合成分组", listOf(KeePassGroupInfo("合成分组", path, "a"), KeePassGroupInfo("合成分组", path, "b")), "b")
        assertEquals(2, result.folders.size)
        assertEquals(0, result.folders.single { it.key == "keepass:3/a" }.count)
        assertEquals(1, result.folders.single { it.key == "keepass:3/b" }.count)
    }

    @Test fun catalogueAndItemOrderCannotSwapTargetsOrCounts() {
        val groups = listOf(KeePassGroupInfo("合成分组", buildKeePassPathKey(null, "合成分组"), "g"),
            KeePassGroupInfo("Empty", "Empty", "empty"))
        val first = snapshot("合成分组", groups)
        assertEquals(first.folders, snapshot("合成分组", groups.reversed()).folders)
        assertEquals(0, first.folders.single { it.key == "keepass:3/empty" }.count)
        assertEquals(1, first.folders.single { it.key == "keepass:3/g" }.count)
    }

    private fun snapshot(path: String, groups: List<KeePassGroupInfo>, uuid: String? = null): VaultOverviewSnapshot =
        buildVaultOverviewSnapshot(buildVaultV2PasswordItems(listOf(PasswordEntry(id = 1, title = "Synthetic",
            username = "", website = "", password = "", keepassDatabaseId = 3, keepassGroupPath = path, keepassGroupUuid = uuid))),
            listOf(VaultOverviewSource("keepass:3", "Synthetic", "KeePass")), "keepass:3", VaultOverviewConfig(),
            emptyMap(), emptyMap(), emptyList(), emptyList(), emptyMap(), emptyList(), mapOf(3L to groups),
            aggregate = ::aggregateVaultOverviewKotlin)
}
