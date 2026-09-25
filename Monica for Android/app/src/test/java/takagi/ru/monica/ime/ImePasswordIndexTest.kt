package takagi.ru.monica.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ImePasswordIndexTest {
    @Test fun repeatedQueriesAndScopesNeverRecomputeAlphabeticalKeys() {
        var conversions = 0
        val entries = listOf(entry(1, "Zulu", 1), entry(2, "Alpha", 2), entry(3, "Beta", 1))
        val index = ImePasswordIndex(entries) { conversions++; it }
        repeat(5) {
            assertEquals(listOf(2L, 3L, 1L), index.query("").map { it.id })
            assertEquals(listOf(3L, 1L), index.query("", MonicaImeDatabaseScope.Bitwarden(1)).map { it.id })
            assertEquals(listOf(3L), index.query("beta user").map { it.id })
        }
        assertEquals("ICU work is bounded by the snapshot, not sort comparisons or query count", entries.size, conversions)
    }

    @Test fun matchingUsesContentAndKeepsSourceSelectionIndependent() {
        val index = ImePasswordIndex(listOf(entry(1, "Mail", 1), entry(2, "Bitwarden", 2)))
        assertEquals(listOf(2L), index.query("bitwarden").map { it.id })
        assertEquals(emptyList<Long>(), index.query("bitwarden", MonicaImeDatabaseScope.Bitwarden(1)).map { it.id })
        assertEquals(listOf(1L, 2L), index.query("example.app", sortMode = MonicaImePasswordSortMode.RELEVANCE,
            activePackageName = "com.example.app").map { it.id }.sorted())
    }

    @Test fun preparedLetterRailDoesNotTransliterateOnTheUiThread() {
        val letters = listOf("A", "A", "B", "#")
        assertEquals(listOf("A" to 0, "B" to 2, "#" to 3),
            buildImeLetterIndex(letters.size, cachedLetterAt = { letters[it] }) {
                error("A prepared keyboard snapshot must not run ICU while composing the list")
            })
    }

    private fun entry(id: Long, title: String, vault: Long) =
        MonicaImePasswordEntry(id, title, "user", "", "com.example.app", "encrypted", false,
            "Bitwarden", bitwardenVaultId = vault)
}
