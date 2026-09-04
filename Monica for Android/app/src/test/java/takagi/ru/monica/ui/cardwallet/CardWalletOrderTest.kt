package takagi.ru.monica.ui.cardwallet

import org.junit.Assert.assertEquals
import org.junit.Test

class CardWalletOrderTest {
    private data class TestItem(val id: Long)

    @Test
    fun `reorders incoming items using pending wallet order`() {
        val items = listOf(TestItem(1), TestItem(2), TestItem(3))

        val result = orderItemsByIds(
            items = items,
            orderedIds = listOf(3, 1, 2),
            idOf = TestItem::id
        )

        assertEquals(listOf(3L, 1L, 2L), result.map(TestItem::id))
    }

    @Test
    fun `retains newly emitted items that are absent from pending order`() {
        val items = listOf(TestItem(1), TestItem(4), TestItem(2), TestItem(3))

        val result = orderItemsByIds(
            items = items,
            orderedIds = listOf(3, 1, 2),
            idOf = TestItem::id
        )

        assertEquals(listOf(3L, 1L, 2L, 4L), result.map(TestItem::id))
    }

    @Test
    fun `merges a filtered reorder without moving hidden items`() {
        val result = mergeVisibleWalletOrder(
            allItemIds = listOf(1, 2, 3, 4),
            reorderedVisibleItemIds = listOf(3, 1)
        )

        assertEquals(listOf(3L, 2L, 1L, 4L), result)
    }
}
