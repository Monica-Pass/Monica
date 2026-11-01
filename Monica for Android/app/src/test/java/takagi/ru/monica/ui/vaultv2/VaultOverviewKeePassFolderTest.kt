package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.data.*
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.utils.KeePassGroupInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class VaultOverviewKeePassFolderTest {
    private val path = "%E9%80%9A%E8%A1%8C%E5%AF%86%E9%92%A5"
    private fun password(db: Long = 3, folder: String = path) = buildVaultV2PasswordItems(listOf(
        PasswordEntry(id = db, title = "Password", username = "", password = "", website = "",
            keepassDatabaseId = db, keepassGroupPath = folder, keepassGroupUuid = "group-$db")
    )).single()
    private fun passkey(db: Long = 3, folder: String = path) = VaultV2Item(
        "passkey:$db", VaultV2ItemType.PASSKEY, "Passkey", "", false, "passkey", emptyList(),
        passkeyEntry = PasskeyEntry(id = db, credentialId = "credential-$db", rpId = "example.org",
            rpName = "Example", userId = "user", userName = "user", userDisplayName = "User",
            publicKey = "public", privateKeyAlias = "alias", keepassDatabaseId = db, keepassGroupPath = folder))
    private fun snapshot(items: List<VaultV2Item>, groups: Map<Long, List<KeePassGroupInfo>> = emptyMap()) =
        buildVaultOverviewSnapshot(items,
            listOf(3L, 4L).map { VaultOverviewSource("keepass:$it", "Database $it", "KeePass") },
            "all", VaultOverviewConfig(), emptyMap(), emptyMap(), emptyList(), emptyList(), emptyMap(), emptyList(),
            groups, aggregate = ::aggregateVaultOverviewKotlin)

    @Test fun passkeysShareCatalogueFolderWithPasswordsAndCombinedCount() {
        val result = snapshot(listOf(passkey(), password()), mapOf(3L to listOf(KeePassGroupInfo(name = "通行密钥", path = path, uuid = "group-3", displayPath = "通行密钥"))))
        val folder = result.folders.single()
        assertEquals("keepass:3/group-3", folder.key)
        assertEquals("通行密钥", folder.name)
        assertEquals(2, folder.count)
        assertEquals(UnifiedCategoryFilterSelection.KeePassGroupFilter(3, path, "group-3"), folder.target)
    }

    @Test fun passkeyOnlyUsesCatalogueUuid() {
        val folder = snapshot(listOf(passkey()), mapOf(3L to listOf(
            KeePassGroupInfo(name = "通行密钥", path = path, uuid = "group-3", displayPath = "通行密钥")))).folders.single()
        assertEquals("keepass:3/group-3", folder.key)
        assertEquals("通行密钥", folder.name)
        assertEquals(1, folder.count)
        assertEquals(UnifiedCategoryFilterSelection.KeePassGroupFilter(3, path, "group-3"), folder.target)
    }

    @Test fun initialSnapshotWithoutCatalogueIsOrderIndependent() {
        for (items in listOf(listOf(passkey(), password()), listOf(password(), passkey()))) {
            val folder = snapshot(items).folders.single()
            assertEquals("keepass:3/group-3", folder.key)
            assertEquals("通行密钥", folder.name)
            assertEquals(2, folder.count)
        }
    }

    @Test fun passkeyOnlyFallbackDecodesOnceAndKeepsEncodedNavigationPath() {
        val encoded = "Parent/A%2FB%2B%2520"
        val folder = snapshot(listOf(passkey(folder = encoded))).folders.single()
        assertEquals("Parent > A/B+%20", folder.name)
        assertEquals(UnifiedCategoryFilterSelection.KeePassGroupFilter(3, encoded, null), folder.target)
    }

    @Test fun identicalPathsInDifferentDatabasesRemainSeparate() {
        val result = snapshot(listOf(password(3), passkey(3), password(4), passkey(4)))
        assertEquals(setOf("keepass:3/group-3", "keepass:4/group-4"), result.folders.map { it.key }.toSet())
        assertEquals(listOf(2, 2), result.folders.map { it.count })
    }

    @Test fun differentUuidsRemainDistinctEvenWithIdenticalPaths() {
        val first = password()
        val second = first.copy(key = "password:other", passwordEntry = first.passwordEntry!!.copy(id = 22, keepassGroupUuid = "other"))
        val result = snapshot(listOf(first, second), mapOf(3L to listOf(
            KeePassGroupInfo(name = "通行密钥", path = path, uuid = "group-3", displayPath = "通行密钥"), KeePassGroupInfo(name = "通行密钥", path = path, uuid = "other", displayPath = "通行密钥"))))
        assertEquals(setOf("keepass:3/group-3", "keepass:3/other"), result.folders.map { it.key }.toSet())
        assertEquals(listOf(1, 1), result.folders.map { it.count })
    }
}
