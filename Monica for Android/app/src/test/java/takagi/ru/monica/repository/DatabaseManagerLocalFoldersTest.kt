package takagi.ru.monica.repository

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.data.Category

class DatabaseManagerLocalFoldersTest {
    @Test fun missingAncestorsAreBrowsableWithoutCreatingCategories() {
        val category = Category(17, "Bitwarden test/child/third")
        val folders = DatabaseManagerLocalFolders(listOf(category))
        val index = MdbxBrowserIndex(folders.nodes)
        val parent = index.children(null).single()
        assertEquals("Bitwarden test", parent.name)
        val child = index.children(parent.id).single()
        assertEquals("child", child.name)
        val third = index.children(child.id).single()
        assertEquals("third", third.name)
        assertEquals("folder:17", third.id)
        assertEquals(listOf(parent, child, third), index.ancestors(third.id))
        assertEquals(listOf(category), folders.categories)
        assertEquals("folder:17", folders.parentForEntry(17))
        assertNull(folders.parentForEntry(99))
    }

    @Test fun explicitParentsAndForeignNamesKeepTheirIdentitiesAndScope() {
        val folders = DatabaseManagerLocalFolders(listOf(Category(1, "A"), Category(2, "A/B"), Category(3, "AB"),
            Category(4, "A/remote", bitwardenVaultId = 7), Category(5, "A/native", mdbxDatabaseId = 8)))
        val index = MdbxBrowserIndex(folders.nodes)
        assertEquals(setOf("folder:1", "folder:3"), index.children(null).map { it.id }.toSet())
        assertEquals(listOf("folder:2"), index.children("folder:1").map { it.id })
        assertEquals(listOf("folder:1", "folder:2"), index.ancestors("folder:2").map { it.id })
    }

    @Test fun renameAndMovePlansPreserveIdsAndAllOtherCategoryFields() {
        val original = Category(9, "A/B/C", sortOrder = 4, syncItemTypes = "opaque")
        val folders = DatabaseManagerLocalFolders(listOf(Category(1, "A"), original, Category(10, "AB"), Category(11, "D")))
        val changes = folders.rebase("folder:1", "Renamed")
        assertEquals(original.copy(name = "Renamed/B/C"), changes.single { it.id == 9L })
        assertEquals(setOf(1L, 9L), changes.map { it.id }.toSet())
        val moved = folders.rebase("local-path:A/B", folders.nestedPath("folder:11", "B"))
        assertEquals(listOf(original.copy(name = "D/B/C")), moved)
    }

    @Test fun collisionsCyclesAndAmbiguousPathsRefuseMutationPlans() {
        val folders = DatabaseManagerLocalFolders(listOf(Category(1, "A"), Category(2, "A/B"), Category(3, "D/B/C")))
        assertThrows(IllegalStateException::class.java) { folders.rebase("folder:1", "A/B/X") }
        assertThrows(IllegalStateException::class.java) { folders.rebase("folder:2", "D/B") }
        assertThrows(IllegalStateException::class.java) { folders.requireNewPath("D") }
        val duplicates = DatabaseManagerLocalFolders(listOf(Category(4, " A / B "), Category(5, "A/B")))
        assertEquals(setOf("folder:4", "folder:5"), MdbxBrowserIndex(duplicates.nodes).children("local-path:A").map { it.id }.toSet())
        assertThrows(IllegalStateException::class.java) { duplicates.descendants("local-path:A") }
        assertThrows(IllegalStateException::class.java) { duplicates.nestedPath("folder:5", "child") }
        val blank = DatabaseManagerLocalFolders(listOf(Category(6, " / ")))
        assertThrows(IllegalStateException::class.java) { blank.nestedPath("folder:6", "child") }
    }

    @Test fun invalidOrOverlongPathsNeverAddressAnotherCategory() {
        val folders = DatabaseManagerLocalFolders(listOf(Category(1, "A/B")))
        assertThrows(IllegalStateException::class.java) { folders.path("local-path:Missing") }
        assertThrows(IllegalStateException::class.java) { folders.path("folder:invalid") }
        assertThrows(IllegalArgumentException::class.java) { folders.nestedPath(null, " / / ") }
        assertThrows(IllegalArgumentException::class.java) { folders.rebase("local-path:A", "X".repeat(512)) }
        assertEquals("A/C/D", folders.nestedPath("local-path:A", " / C / D / "))
    }

    @Test fun formerlyImplicitFolderResolvesAfterMaterialization() {
        val nodes = DatabaseManagerLocalFolders(listOf(Category(1, "A/B"), Category(2, "A"))).nodes
        val snapshot = DatabaseManagerSnapshot(DatabaseManagerLocation(ImportDestination.Local), "Monica", nodes)
        assertEquals("folder:2", snapshot.resolveLocalFolderId("local-path:A"))
        assertEquals("folder:1", snapshot.resolveLocalFolderId("folder:1"))
        val ambiguous = snapshot.copy(nodes = nodes + nodes.single { it.id == "folder:2" }.copy(id = "folder:3"))
        assertEquals("local-path:A", ambiguous.resolveLocalFolderId("local-path:A"))
    }
}
