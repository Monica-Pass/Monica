package takagi.ru.monica.ui.cardwallet

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.WalletStack

class WalletSelectionStackOrderTest {
    private fun cards(vararg ids: Long) = ids.map { id -> WalletListItem(
        id, WalletListItemType.BANK_CARD,
        SecureItem(id = id, itemType = ItemType.BANK_CARD, title = "Fixture $id", itemData = "{}")
    ) }
    private val stacks = listOf(WalletStack("a", listOf(1, 2), 2), WalletStack("b", listOf(3, 4), 4))
    private fun rows(cards: List<WalletListItem>, collapsed: Set<String> = setOf("a", "b")) =
        prepareWalletSelectionEntries(projectWalletStacks(cards, stacks, selectionMode = true), cards, collapsed)

    @Test fun collapseRetainsMembersCoverAndPartialSelectionWhileHidingOnlyTheRows() {
        val cards = cards(1, 2, 3, 4, 5)
        val before = projectWalletStacks(cards, stacks, selectionMode = true)
        val after = prepareWalletSelectionEntries(before, cards, setOf("a"))
        assertEquals(listOf("stack:a", "stack:b", "card:3", "card:4", "selection:unstacked", "card:5"), after.map { it.key })
        val header = after.first() as WalletStackListEntry.SelectionHeader
        assertEquals(listOf(1L, 2L), header.cards.map { it.id })
        assertEquals(2L, header.cover!!.id)
        assertEquals(setOf(1L, 2L, 5L), toggleWalletSelectionGroup(setOf(1L, 5L), header))
        assertEquals(before, prepareWalletSelectionEntries(before, cards, emptySet()))
    }

    @Test fun closedStacksMoveInBothDirectionsAndUpdateBeforeTheAsyncProjection() {
        val cards = cards(1, 2, 3, 4, 5)
        val before = rows(cards)
        val moved = reorderWalletSelectionCards(cards, before[0], before[1])
        assertEquals(listOf(3L, 4L, 1L, 2L, 5L), moved.map { it.id })
        val immediate = prepareWalletSelectionEntries(before, moved, setOf("a", "b"))
        assertEquals(listOf("stack:b", "stack:a", "selection:unstacked", "card:5"), immediate.map { it.key })
        assertEquals(immediate, rows(moved))
        assertEquals(cards, reorderWalletSelectionCards(moved, immediate[1], immediate[0]))
        assertEquals(listOf(1L, 2L), stacks[0].memberIds)
        assertEquals(2L, stacks[0].coverId)
    }

    @Test fun filteredDragPreservesHiddenSlotsAndPersistedMembership() {
        val visible = cards(2, 4, 5)
        val entries = rows(visible)
        val moved = reorderWalletSelectionCards(visible, entries[0], entries[1])
        assertEquals(listOf(4L, 2L, 5L), moved.map { it.id })
        assertEquals(listOf(1L, 4L, 3L, 2L, 5L, 6L),
            mergeVisibleWalletOrder(listOf(1L, 2L, 3L, 4L, 5L, 6L), moved.map { it.id }))
        assertEquals(listOf(3L, 4L), stacks[1].memberIds)
    }

    @Test fun expandedSectionsAndIndependentCardsCannotBeMistakenForClosedStacks() {
        val cards = cards(1, 2, 3, 4, 5, 6)
        val mixed = rows(cards, setOf("a"))
        assertSame(cards, reorderWalletSelectionCards(cards, mixed[0], mixed[1]))
        assertSame(cards, reorderWalletSelectionCards(cards, mixed[0], mixed.last()))
        assertSame(cards, reorderWalletSelectionCards(cards, mixed.last(), mixed[0]))
        val singles = mixed.filterIsInstance<WalletStackListEntry.Single>().filter { it.selectionStackId == null }
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L, 5L),
            reorderWalletSelectionCards(cards, singles[0], singles[1]).map { it.id })
    }
}
