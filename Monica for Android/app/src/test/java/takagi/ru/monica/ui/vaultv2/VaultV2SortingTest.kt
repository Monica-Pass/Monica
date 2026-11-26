package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import java.util.Date

class VaultV2SortingTest {
    @Test fun `date sections use full numeric local dates regardless of locale`() {
        val previousLocale = java.util.Locale.getDefault()
        val previousZone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            val time = java.time.Instant.parse("2026-09-28T17:00:00Z").toEpochMilli()
            for (locale in listOf(java.util.Locale.CHINA, java.util.Locale.US, java.util.Locale.FRENCH, java.util.Locale("ar"), java.util.Locale.forLanguageTag("ar-EG"), java.util.Locale.forLanguageTag("ar-SA"))) {
                java.util.Locale.setDefault(locale)
                val items = buildVaultV2PasswordItems(listOf(PasswordEntry(id = 1, title = "Fixture",
                    username = "", password = "", website = "", createdAt = Date(time), updatedAt = Date(time))))
                VaultListSort.entries.filterNot { it.isAlphabetical }.forEach { sort ->
                    assertEquals("2026/09/29", buildVaultV2SortedSections(items, sort).single().first)
                }
            }
        } finally {
            java.util.Locale.setDefault(previousLocale)
            java.util.TimeZone.setDefault(previousZone)
        }
    }

    private val entries = listOf(
        entry(1, "Alpha", 3, 1), entry(2, "Zebra", 2, 3), entry(3, "Beta", 1, 2),
    )
    private val expected = mapOf(
        VaultListSort.RECENT_DESC to listOf(1L, 2L, 3L),
        VaultListSort.TITLE_ASC to listOf(1L, 3L, 2L),
        VaultListSort.TITLE_DESC to listOf(2L, 3L, 1L),
        VaultListSort.CREATED_DESC to listOf(1L, 2L, 3L),
        VaultListSort.CREATED_ASC to listOf(3L, 2L, 1L),
        VaultListSort.UPDATED_DESC to listOf(2L, 3L, 1L),
        VaultListSort.UPDATED_ASC to listOf(1L, 3L, 2L),
    )

    @Test fun `six modes order rows and rendered sections consistently`() {
        expected.forEach { (sort, ids) ->
            val result = buildVaultV2DisplayListState(emptyList(), entries, config(sort))
            assertEquals(sort.name, ids, result.visibleListState.filteredItems.map { it.passwordEntry!!.id })
            assertEquals(sort.name, ids, result.visibleListState.sectionedItems.flatMap { it.second }.map { it.passwordEntry!!.id })
            val layouts = buildVaultV2SectionLayouts(result.visibleListState.sectionedItems, 2)
            var next = 3
            layouts.forEach { section ->
                assertEquals(next, section.firstItemLazyIndex)
                next += section.items.size + 1
            }
        }
        assertEquals(listOf(3L, 2L, 1L), entries.map { it.createdAt.time / 86_400_000L })
    }

    @Test fun `all source folders and search retain selected chronology`() {
        val scopes = listOf(
            UnifiedCategoryFilterSelection.Custom(9L) to entries.map { it.copy(categoryId = 9) },
            UnifiedCategoryFilterSelection.MdbxFolderFilter(9, "folder") to entries.map { it.copy(mdbxDatabaseId = 9, mdbxFolderId = "folder") },
            UnifiedCategoryFilterSelection.KeePassGroupFilter(9, "folder") to entries.map { it.copy(keepassDatabaseId = 9, keepassGroupPath = "folder") },
            UnifiedCategoryFilterSelection.BitwardenFolderFilter(9, "folder") to entries.map { it.copy(bitwardenVaultId = 9, bitwardenFolderId = "folder") },
        )
        scopes.forEach { (scope, scoped) ->
            expected.forEach { (sort, ids) ->
                val result = buildVaultV2DisplayListState(emptyList(), scoped + entry(99, "Outside", 9, 9),
                    config(sort).copy(storageSelection = scope, normalizedQuery = "fixture"))
                val direct = filterVaultV2DirectItems(scope, result.visibleListState.filteredItems)
                assertEquals("$scope / $sort", ids, direct.map { it.passwordEntry!!.id })
            }
        }
    }

    @Test fun `equal timestamps and titles use stable identities in either input order`() {
        val items = buildVaultV2PasswordItems(listOf(entry(2, "Same", 1, 1), entry(1, "Same", 1, 1)))
        VaultListSort.entries.forEach { sort ->
            assertEquals(listOf("password:1", "password:2"), sortVaultV2Items(items, sort).map { it.key })
            assertEquals(sortVaultV2Items(items, sort), sortVaultV2Items(items.reversed(), sort))
        }
    }

    @Test fun `missing times stay last and alphabetical groups keep symbols last`() {
        val items = buildVaultV2PasswordItems(listOf(entry(1, "Alpha", 1, 1), entry(2, "123", 0, 0), entry(3, "zebra", 2, 2)))
        VaultListSort.entries.filterNot { it.isAlphabetical }.forEach { sort ->
            val sorted = sortVaultV2Items(items, sort)
            assertEquals("password:2", sorted.last().key)
            assertEquals("", buildVaultV2SortedSections(sorted, sort).last().first)
        }
        val sorted = sortVaultV2Items(items, VaultListSort.TITLE_DESC)
        assertEquals(listOf("Z", "A", "#"), buildVaultV2SortedSections(sorted, VaultListSort.TITLE_DESC).map { it.first })
    }

    @Test fun `sort changes invalidate retained view and unknown preference is compatible`() {
        assertEquals(VaultListSort.TITLE_ASC, VaultListSort.fromStoredValue(null))
        assertEquals(VaultListSort.TITLE_ASC, VaultListSort.fromStoredValue("future-sort"))
        VaultListSort.entries.forEach { assertEquals(it, VaultListSort.fromStoredValue(it.name)) }
        val a = config(VaultListSort.TITLE_ASC)
        assertNotEquals(a, a.copy(sort = VaultListSort.CREATED_DESC))
        val key = VaultV2VisibleSnapshotKey(a.storageSelection, emptySet(), a.displayedContentTypes,
            emptyList(), emptyList(), emptySet(), emptyMap(), emptySet(), "", false)
        assertNotEquals(key, key.copy(sort = VaultListSort.CREATED_DESC))
    }

    @Test fun `secure items share timestamps and native token missing creation remains unknown`() {
        val secure = SecureItem(id = 8, itemType = ItemType.NOTE, title = "Note", itemData = "{}",
            createdAt = Date(1234), updatedAt = Date(5678))
        val item = VaultV2Item("note:8", VaultV2ItemType.NOTE, "Note", "", false, "Note", emptyList(), secureItem = secure)
        assertEquals(1234L, item.sortTimestamp(VaultListSort.CREATED_DESC))
        assertEquals(5678L, item.sortTimestamp(VaultListSort.UPDATED_DESC))
        val token = NativeApiTokenSummary(1, "id", "folder", "Folder", "Token", updatedAt = 9012)
        val native = item.copy(nativeToken = token, passwordEntry = token.asPasswordCard("API Key"))
        assertNull(native.sortTimestamp(VaultListSort.CREATED_DESC))
        assertEquals(9012L, native.sortTimestamp(VaultListSort.UPDATED_DESC))
    }

    @Test fun recentCombinesCreationAndEditingWithoutChangingStoredDates() {
        val rows = listOf(entry(1,"Old",1,1), entry(2,"Created",5,0), entry(3,"Edited",1,6))
        fun sorted(input: List<PasswordEntry>) = buildVaultV2DisplayListState(emptyList(), input, config(VaultListSort.RECENT_DESC))
            .visibleListState.filteredItems
        assertEquals(listOf(3L,2L,1L), sorted(rows).map { it.passwordEntry!!.id })
        val changed = rows.map { if (it.id == 1L) it.copy(updatedAt = Date(7 * 86_400_000L)) else it }
        assertEquals(listOf(1L,3L,2L), sorted(changed).map { it.passwordEntry!!.id })
        assertEquals(0L, rows[1].updatedAt.time)
        val many = buildVaultV2PasswordItems((1L..12L).map { entry(it,"Item $it",it.toInt(),0) })
        assertEquals((12L downTo 5L).toList(),recentVaultOverviewItems(many).map { it.passwordEntry!!.id })
        assertTrue(recentVaultOverviewItems(buildVaultV2PasswordItems(listOf(entry(50,"Unknown",0,0)))).isEmpty())
    }

    private fun entry(id: Long, title: String, created: Int, updated: Int) = PasswordEntry(
        id = id, title = title, username = "fixture", website = "", password = "",
        createdAt = Date(created * 86_400_000L), updatedAt = Date(updated * 86_400_000L),
    )

    private fun config(sort: VaultListSort) = VaultV2VisibleListConfig(
        storageSelection = UnifiedCategoryFilterSelection.All, displayedContentTypes = setOf(PasswordPageContentType.PASSWORD),
        configuredQuickFilterItems = emptyList(), quickFilterFavorite = false, quickFilter2fa = false,
        quickFilterNotes = false, quickFilterPasskey = false, quickFilterBoundNote = false,
        quickFilterAttachments = false, activeAttachmentParentIds = emptySet(), quickFilterUncategorized = false,
        quickFilterLocalOnly = false, quickFilterManualStackOnly = false, quickFilterNeverStack = false,
        quickFilterUnstacked = false, manualStackGroupByEntryId = emptyMap(), noStackEntryIds = emptySet(),
        normalizedQuery = "", isArchiveView = false, sort = sort,
    )
}
