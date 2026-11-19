package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection

class VaultPasswordProjectsTest {
    private fun entry(id: Long, project: String? = "project") = PasswordEntry(
        id = id, title = "Same title", username = "account", password = "unused", website = "",
        passwordGroupId = project, categoryId = 10)
    private fun rows() = listOf(entry(1), entry(2), entry(3, null))
    private fun config(query: String = "") = VaultV2VisibleListConfig(
        storageSelection = UnifiedCategoryFilterSelection.All, localCategoryIdsInScope = emptySet(),
        displayedContentTypes = setOf(PasswordPageContentType.PASSWORD),
        configuredQuickFilterItems = PasswordListQuickFilterItem.DEFAULT_ORDER,
        quickFilterFavorite = false, quickFilter2fa = false, quickFilterNotes = false, quickFilterPasskey = false,
        quickFilterBoundNote = false, quickFilterAttachments = false, activeAttachmentParentIds = emptySet(),
        quickFilterUncategorized = false, quickFilterLocalOnly = false, quickFilterManualStackOnly = false,
        quickFilterNeverStack = false, quickFilterUnstacked = false, manualStackGroupByEntryId = emptyMap(),
        noStackEntryIds = emptySet(), normalizedQuery = query, isArchiveView = false)

    @Test fun flatListCollapsesProjectButRetainsAllRowsAndIdenticalIndependentItem() {
        val result = buildVaultV2DisplayListState(emptyList(), rows(), config())
        assertEquals(3, result.allItemsRaw.size)
        assertEquals(listOf("password:1", "password:3"), result.visibleListState.filteredItems.map { it.key })
        assertEquals(2, result.visibleListState.sectionLayouts.sumOf { it.items.size })
        val selected = expandVaultV2ProjectSelection(result.visibleListState.filteredItems.take(1), result.allItemsRaw)
        assertEquals(listOf(1L, 2L), buildVaultV2BatchMovePlan(selected).passwordEntries.map { it.id })
    }

    @Test fun secondaryPasswordSearchStillFindsAndSelectsTheWholeProject() {
        val source = listOf(entry(1), entry(2).copy(username = "needle"), entry(3, null))
        val result = buildVaultV2DisplayListState(emptyList(), source, config("needle"))
        assertEquals(listOf("password:2"), result.visibleListState.filteredItems.map { it.key })
        val selected = expandVaultV2ProjectSelection(result.visibleListState.filteredItems, result.allItemsRaw)
        assertEquals(setOf(1L, 2L), selected.map { it.passwordEntry!!.id }.toSet())
    }

    @Test fun quickFilterOnASiblingDoesNotDiscardItsProjectOrSelectOtherSources() {
        val source = rows() + entry(4).copy(keepassDatabaseId = 20)
        val config = config().copy(quickFilterAttachments = true, activeAttachmentParentIds = setOf(2),
            configuredQuickFilterItems = listOf(PasswordListQuickFilterItem.ATTACHMENTS))
        val result = buildVaultV2DisplayListState(emptyList(), source, config)
        assertEquals(listOf("password:2"), result.visibleListState.filteredItems.map { it.key })
        assertEquals(setOf(1L, 2L), expandVaultV2ProjectSelection(result.visibleListState.filteredItems,
            result.allItemsRaw).map { it.passwordEntry!!.id }.toSet())
    }

    @Test fun projectIdentityNeverCrossesStorageOrDatabaseBoundaries() {
        val source = listOf(entry(1), entry(2), entry(3).copy(keepassDatabaseId = 1),
            entry(4).copy(keepassDatabaseId = 2), entry(5).copy(bitwardenVaultId = 1), entry(6).copy(mdbxDatabaseId = 1))
        assertEquals(5, collapseVaultV2PasswordProjects(buildVaultV2PasswordItems(source)).size)
    }

    @Test fun overviewCountsProjectsAndPreservesPinAndUsageOfSecondaryPassword() {
        val source = rows().map { it.copy(isFavorite = true) }
        val raw = buildVaultV2PasswordItems(source)
        val identity = raw[1].overviewIdentity()
        val snapshot = buildVaultOverviewSnapshot(raw, listOf(VaultOverviewSource("local", "Local", "Monica")),
            "all", VaultOverviewConfig(pinnedItems = listOf(identity), recommendItems = false),
            mapOf(identity to VaultOverviewUsage(identity, 3, 100)), emptyMap(),
            listOf(Category(id = 10, name = "Folder")), emptyList(), emptyMap(), source.take(2),
            aggregate = ::aggregateVaultOverviewKotlin)
        assertEquals(2, snapshot.items.size)
        assertEquals(2, snapshot.recentItems.size)
        assertEquals(2, snapshot.favorites.size)
        assertEquals(2, snapshot.typeCounts[VaultV2ItemType.PASSWORD])
        assertEquals(2, snapshot.sourceCounts["local"])
        assertEquals(2, snapshot.folders.single().count)
        assertEquals(1, snapshot.archiveCount)
        assertEquals(listOf("password:2"), snapshot.frequentItems.map { it.key })
        assertEquals(setOf(1L, 2L), expandVaultV2ProjectSelection(snapshot.frequentItems, raw)
            .map { it.passwordEntry!!.id }.toSet())
    }

    @Test fun latestRowsReplaceRetainedProjectAndDeletedSiblingCannotBeSelected() {
        val old = buildVaultV2DisplayListState(emptyList(), rows(), config())
        val current = buildVaultV2DisplayListState(old.allItemsRaw, listOf(entry(1).copy(title = "Updated")), config())
        assertEquals(listOf("Updated"), current.visibleListState.filteredItems.map { it.title })
        assertEquals(listOf(1L), expandVaultV2ProjectSelection(current.visibleListState.filteredItems, current.allItemsRaw)
            .map { it.passwordEntry!!.id })
    }

    @Test fun overviewSearchAndPinPickerKeepSecondaryAccountSearchWithoutLeakingNotes() {
        val source = listOf(entry(1), entry(2).copy(username = "needle", notes = "private-note"), entry(3, null))
        val raw = buildVaultV2PasswordItems(source)
        val sources = listOf(VaultOverviewSource("local", "Local", "Monica"))
        val snapshot = buildVaultOverviewSnapshot(raw, sources, "all", VaultOverviewConfig(), emptyMap(), emptyMap(),
            emptyList(), emptyList(), emptyMap(), emptyList(), aggregate = ::aggregateVaultOverviewKotlin)
        val picker = prepareOverviewPicker(snapshot.searchItems, sources, cards = false,
            priorityIdentities = listOf(raw[1].overviewIdentity()), openNative = { null })
        try {
            assertEquals(2, picker.rows.size)
            assertEquals("password:2", picker.filter("needle", "all").single().item.key)
            assertTrue(picker.filter("private-note", "all").isEmpty())
        } finally { picker.close() }
    }
}
