package takagi.ru.monica.ui.password

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordPageContentType

class PasswordStackOrderTest {
    private fun entry(id: Long) = PasswordEntry(id = id, title = "Item $id", username = "", password = "", website = "")

    @Test fun `stacks remain together before favorite singles and mixed content keeps order`() {
        val rows = (1L..8L).associateWith(::entry)
        val groups = linkedMapOf("single-a" to listOf(rows.getValue(1).copy(isFavorite = true)),
            "stack-a" to listOf(rows.getValue(2), rows.getValue(3)),
            "single-b" to listOf(rows.getValue(4)), "stack-b" to listOf(rows.getValue(5), rows.getValue(6)))
        val mixed = PasswordManualStackGroupListItemUi("mixed", listOf(rows.getValue(7), rows.getValue(8)).map { it.toPasswordPageCardItemUi() })
        val items = buildPasswordPageListItems(setOf(PasswordPageContentType.PASSWORD), groups, emptyList(), "title", listOf(mixed))
        assertEquals(listOf("group:stack-a", "group:stack-b", "manual_group:mixed", "group:single-a", "group:single-b"), items.map { it.key })
        assertEquals(listOf(2L, 3L, 5L, 6L, 7L, 8L, 1L, 4L), flattenPasswordPageCardItems(items).map { it.entry.id })
        assertEquals(listOf("single-a", "stack-a", "single-b", "stack-b"), groups.keys.toList())
    }

    @Test fun `expanded singles and filtered empty lists preserve existing order`() {
        val groups = linkedMapOf("b" to listOf(entry(2)), "a" to listOf(entry(1)))
        assertEquals(listOf("group:b", "group:a"), buildPasswordPageListItems(setOf(PasswordPageContentType.PASSWORD), groups, emptyList(), "title").map { it.key })
        assertTrue(buildPasswordPageListItems(emptySet(), groups, emptyList(), "title").isEmpty())
    }
}
