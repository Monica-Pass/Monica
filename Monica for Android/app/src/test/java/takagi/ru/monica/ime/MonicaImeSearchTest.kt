package takagi.ru.monica.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonicaImeSearchTest {

    @Test
    fun blankQueryMatchesEveryPasswordEntry() {
        assertTrue(imePasswordEntryMatchesQuery(sampleEntry(), "   "))
    }

    @Test
    fun searchIgnoresCaseAndMatchesTermsAcrossFields() {
        val entry = sampleEntry(
            title = "Monica Account",
            username = "Alice@example.com",
            website = "https://vault.example.com"
        )

        assertTrue(imePasswordEntryMatchesQuery(entry, "  monica ALICE  "))
        assertTrue(imePasswordEntryMatchesQuery(entry, "alice vault"))
        assertFalse(imePasswordEntryMatchesQuery(entry, "alice missing"))
    }

    @Test
    fun searchIncludesApplicationBindingsButLeavesDatabaseNamesToTheSourceSelector() {
        val entry = sampleEntry(
            packageName = "com.example.mobile",
            sourceLabel = "Work KeePass"
        )

        assertTrue(imePasswordEntryMatchesQuery(entry, "example.mobile"))
        assertFalse(imePasswordEntryMatchesQuery(entry, "work keepass"))
    }

    @Test
    fun searchInputLimitAndBackspacePreserveUnicodeCharacters() {
        val limited = appendImeSearchQuery("a".repeat(79), "😀x")

        assertEquals(80, limited.codePointCount(0, limited.length))
        assertTrue(limited.endsWith("😀"))
        assertEquals("密码", removeLastImeSearchCharacter("密码😀"))
        assertEquals("", removeLastImeSearchCharacter(""))
    }

    @Test
    fun alphabeticalModeUsesStrictTitleOrderWhileRelevanceKeepsMatchingPriority() {
        val favorite = sampleEntry(id = 1L, title = "Zulu", isFavorite = true)
        val alphabeticalFirst = sampleEntry(id = 2L, title = "Alpha")
        val packageMatch = sampleEntry(
            id = 3L,
            title = "Middle",
            packageName = "com.target.app"
        )
        val entries = listOf(favorite, alphabeticalFirst, packageMatch)

        assertEquals(
            listOf(3L, 1L, 2L),
            sortImePasswordEntries(
                entries,
                MonicaImePasswordSortMode.RELEVANCE,
                "com.target.app"
            ).map { it.id }
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            sortImePasswordEntries(
                entries,
                MonicaImePasswordSortMode.ALPHABETICAL,
                "com.target.app"
            ).map { it.id }
        )
    }

    @Test
    fun letterIndexKeepsOnlyTheFirstAnchorForEachLetter() {
        val titles = listOf("Alpha", "Another", "Beta", "Again", "123")

        assertEquals(
            listOf("A" to 2, "B" to 4, "#" to 6),
            buildImeLetterIndex(
                itemCount = titles.size,
                itemOffset = 2,
                titleAt = titles::get
            )
        )
    }

    @Test
    fun letterIndexNeverUsesNonAsciiCharactersAsAnchors() {
        val titles = listOf("高德地图", "百度网盘", "Alpha")

        val index = buildImeLetterIndex(
            itemCount = titles.size,
            titleAt = titles::get
        )

        assertTrue(
            index.all { group ->
                (group.first.length == 1 && group.first[0] in 'A'..'Z') ||
                    group.first == "#"
            }
        )
        assertEquals("A", index.last().first)
    }

    @Test
    fun normalizedImeSortKeyKeepsAsciiFastPathAndBlankFallback() {
        assertEquals("Alpha 2", normalizedImeSortKey(" Alpha 2 "))
        assertEquals("#", normalizedImeSortKey("   "))
    }

    private fun sampleEntry(
        id: Long = 1L,
        title: String = "Example",
        username: String = "user",
        website: String = "https://example.com",
        packageName: String = "com.example.app",
        sourceLabel: String = "Monica",
        isFavorite: Boolean = false
    ): MonicaImePasswordEntry = MonicaImePasswordEntry(
        id = id,
        title = title,
        username = username,
        website = website,
        packageName = packageName,
        password = "secret",
        isFavorite = isFavorite,
        sourceLabel = sourceLabel
    )
}
