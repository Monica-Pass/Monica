package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.ui.screens.mergeVisibleNoteOrder
import takagi.ru.monica.ui.screens.normalizeNoteOrder

class NoteTileLayoutTest {

    @Test
    fun filteredTileReorderKeepsHiddenNotesInTheirSlots() {
        assertEquals(
            listOf(3L, 2L, 1L, 4L),
            mergeVisibleNoteOrder(
                allItemIds = listOf(1L, 2L, 3L, 4L),
                reorderedVisibleItemIds = listOf(3L, 1L)
            )
        )
    }

    @Test
    fun tileReorderIgnoresUnknownAndDuplicateIds() {
        assertEquals(
            listOf(2L, 1L, 3L),
            mergeVisibleNoteOrder(
                allItemIds = listOf(1L, 2L, 3L),
                reorderedVisibleItemIds = listOf(2L, 99L, 2L, 1L)
            )
        )
    }

    @Test
    fun pendingOrderFollowsSourceChangesWithoutLosingNewItems() {
        assertEquals(
            listOf(3L, 1L, 4L),
            normalizeNoteOrder(
                preferredIds = listOf(3L, 2L, 1L),
                currentIds = listOf(1L, 3L, 4L)
            )
        )
    }

    @Test
    fun notesReuseTheAuthenticatorTileGridAndCardSurface() {
        val tileSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteTileGridContent.kt"
        ).readText()
        val screenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteListScreen.kt"
        ).readText()

        assertTrue(tileSource.contains("MonicaTileGrid("))
        assertFalse(tileSource.contains("LazyVerticalStaggeredGrid("))
        assertTrue(tileSource.contains("MonicaItemCard("))
        assertFalse(tileSource.contains("private fun NoteCardSurface("))
        assertTrue(screenSource.contains("R.string.authenticator_layout_standard"))
        assertTrue(screenSource.contains("R.string.authenticator_layout_tile"))
        assertFalse(screenSource.contains("R.string.switch_to_list"))
        assertFalse(screenSource.contains("R.string.switch_to_grid"))
    }

    @Test
    fun noteTilesUseFixedCardsAndAuthenticatorStyleReordering() {
        val tileSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteTileGridContent.kt"
        ).readText()
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/NoteViewModel.kt"
        ).readText()

        assertTrue(tileSource.contains("rememberReorderableLazyGridState"))
        assertTrue(tileSource.contains("ReorderableItem"))
        assertTrue(tileSource.contains("onUpdateSortOrders"))
        assertTrue(tileSource.contains("NoteTileCardHeight"))
        assertTrue(viewModelSource.contains("fun updateSortOrders(items: List<Pair<Long, Int>>)") )
    }

    @Test
    fun noteTilePreviewDoesNotExposeNestedClickTargets() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteTileGridContent.kt"
        ).readText().replace("\r\n", "\n")
        val tileCard = source
            .substringAfter("internal fun NoteTileCard(")
            .substringBefore("internal fun NoteTileGridContent(")

        assertTrue(tileCard.contains("combinedClickable"))
        assertFalse(tileCard.contains("MarkdownPreviewText"))
        assertFalse(tileCard.contains("onInlineImageClick"))
        assertFalse(tileCard.contains("onNonLinkClick"))
    }

    private fun projectFile(path: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, path)
    }
}
