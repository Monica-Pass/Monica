package takagi.ru.monica.update

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReleaseNotesMarkdownTest {
    @Test fun commonMarkKeepsHeadingsNestedEmphasisCodeAndReferenceLinks() {
        val parsed = parseReleaseNotesMarkdown("""
            ## Changes

            **Strong and *emphasized***, `literal <tag>` and [release][r].
            Soft line
            continues.

            [r]: https://example.com/releases/327 "Release"
        """.trimIndent())

        assertFalse(parsed.truncated)
        val heading = parsed.blocks[0] as ReleaseNotesBlock.Prose
        assertEquals(2, heading.heading)
        assertEquals("Changes", heading.spans.text())
        val prose = parsed.blocks[1] as ReleaseNotesBlock.Prose
        assertTrue(prose.spans.any { it.text == "Strong and " && it.bold && !it.italic })
        assertTrue(prose.spans.any { it.text == "emphasized" && it.bold && it.italic })
        assertTrue(prose.spans.any { it.text == "literal <tag>" && it.code })
        assertTrue(prose.spans.any { it.text == "release" && it.link == "https://example.com/releases/327" })
        assertTrue(prose.spans.text().endsWith("Soft line continues."))
    }

    @Test fun listsPreserveStartingNumberNestingAndReadOnlyTaskMarkers() {
        val parsed = parseReleaseNotesMarkdown("""
            7. First
            8. Second
               - Nested

            - [x] **Completed** item
            - [ ] Remaining item
            - Ordinary item
        """.trimIndent())

        assertFalse(parsed.truncated)
        val ordered = parsed.blocks[0] as ReleaseNotesBlock.Items
        assertEquals(7, ordered.start)
        assertEquals(2, ordered.items.size)
        val nested = ordered.items[1].blocks[1] as ReleaseNotesBlock.Items
        assertNull(nested.start)
        assertEquals("Nested", (nested.items[0].blocks[0] as ReleaseNotesBlock.Prose).spans.text())
        val tasks = parsed.blocks[1] as ReleaseNotesBlock.Items
        assertEquals(listOf(true, false, null), tasks.items.map { it.checked })
        val first = tasks.items[0].blocks[0] as ReleaseNotesBlock.Prose
        assertEquals("Completed item", first.spans.text())
        assertTrue(first.spans.any { it.text == "Completed" && it.bold })
    }

    @Test fun tablesStrikethroughQuotesAndFencedCodeKeepTheirContents() {
        val parsed = parseReleaseNotesMarkdown("""
            > Read ~~old~~ **new** notes.

            | Feature | State |
            | :--- | ---: |
            | Notes | ~~Plain~~ Markdown |

            ```kotlin
            val example = "<script>literal</script>"
            ```

            ---
        """.trimIndent())

        assertFalse(parsed.truncated)
        val quote = parsed.blocks[0] as ReleaseNotesBlock.Quote
        assertTrue((quote.blocks[0] as ReleaseNotesBlock.Prose).spans.any { it.text == "old" && it.strike })
        val table = parsed.blocks[1] as ReleaseNotesBlock.Table
        assertEquals(2, table.rows.size)
        assertTrue(table.rows[0].all { it.header })
        assertEquals(ReleaseNotesAlignment.RIGHT, table.rows[1][1].alignment)
        assertTrue(table.rows[1][1].spans.any { it.text == "Plain" && it.strike })
        assertEquals("val example = \"<script>literal</script>\"", (parsed.blocks[2] as ReleaseNotesBlock.CodeBlock).text)
        assertEquals(ReleaseNotesBlock.Divider, parsed.blocks[3])
    }

    @Test fun imageDestinationsAreDiscardedAndHtmlIsOnlyText() {
        val parsed = parseReleaseNotesMarkdown("""
            ![Screenshot **caption**](https://tracker.example/pixel.png)

            <script>alert('not executed')</script>

            An <img src="https://tracker.example/inline.png"> inline tag.
        """.trimIndent())

        val image = parsed.blocks[0] as ReleaseNotesBlock.Prose
        assertEquals("Screenshot caption", image.spans.text())
        assertTrue(image.spans.all { it.link == null })
        assertFalse(image.toString().contains("tracker.example"))
        assertEquals("<script>alert('not executed')</script>",
            (parsed.blocks[1] as ReleaseNotesBlock.Prose).spans.text().trim())
        assertTrue((parsed.blocks[2] as ReleaseNotesBlock.Prose).spans.text()
            .contains("<img src=\"https://tracker.example/inline.png\">"))
    }

    @Test fun unsafeLinksRemainReadableButNeverBecomeClickTargets() {
        val parsed = parseReleaseNotesMarkdown("""
            [JS](javascript:alert%281%29) [file](file:///sdcard/notes) [intent](intent://app)
            [relative](/release) [opaque](https:example.com) [credential](https://user:pass@example.com/)
            [valid](https://example.com/releases?q=327#changes) <https://example.com/plain>
        """.trimIndent())
        val spans = (parsed.blocks[0] as ReleaseNotesBlock.Prose).spans
        assertEquals(listOf("https://example.com/releases?q=327#changes", "https://example.com/plain"),
            spans.mapNotNull { it.link })
        for (label in listOf("JS", "file", "intent", "relative", "opaque", "credential")) {
            assertTrue(spans.any { it.text == label && it.link == null })
        }
        for (url in listOf("//example.com", "https://", "https://a b/", "https://example.com/\nsecret",
            "https://example.com:65536/", "https://example.com:0/", "https://example.com\\@evil.example")) {
            assertNull(url, releaseNotesHttpUrl(url))
        }
        assertEquals("HTTP://example.com:8080/path", releaseNotesHttpUrl("HTTP://example.com:8080/path"))
    }

    @Test fun excessiveTextBlocksDepthAndColumnsReportTruncation() {
        val text = "a".repeat(RELEASE_NOTES_MAX_CHARS - 1) + "\uD83D\uDD11" + "important tail"
        val bounded = parseReleaseNotesMarkdown(text)
        assertTrue(bounded.truncated)
        val visible = (bounded.blocks[0] as ReleaseNotesBlock.Prose).spans.text()
        assertEquals(RELEASE_NOTES_MAX_CHARS - 1, visible.length)
        assertFalse(visible.last().isHighSurrogate())

        val manyBlocks = parseReleaseNotesMarkdown((1..300).joinToString("\n\n") { "Paragraph $it" })
        assertTrue(manyBlocks.truncated)
        assertTrue(manyBlocks.blocks.size < 300)
        assertEquals("Paragraph 1", (manyBlocks.blocks[0] as ReleaseNotesBlock.Prose).spans.text())

        val deep = parseReleaseNotesMarkdown("> ".repeat(100) + "Deeply nested text")
        assertTrue(deep.truncated)

        val wide = parseReleaseNotesMarkdown(
            (1..20).joinToString("|", "|", "|\n") { "Column $it" } +
                (1..20).joinToString("|", "|", "|") { "---" })
        assertTrue(wide.truncated)
        assertEquals(16, (wide.blocks[0] as ReleaseNotesBlock.Table).rows[0].size)
    }

    @Test fun excessiveInlineNodesReportTruncationWithoutLosingInitialText() {
        val parsed = parseReleaseNotesMarkdown("Initial " + "**word** ".repeat(1_100))
        assertTrue(parsed.truncated)
        val spans = (parsed.blocks[0] as ReleaseNotesBlock.Prose).spans
        assertTrue(spans.text().startsWith("Initial "))
        assertTrue(spans.count { it.bold } < 1_100)
    }

    @Test fun cancellationIsPropagatedRatherThanPublishingAPartialDocument() {
        var checkpoints = 0
        val cancelled = CancellationException("replaced release")
        try {
            parseReleaseNotesMarkdown("**First** and [next](https://example.com)") {
                if (++checkpoints == 4) throw cancelled
            }
            fail("Cancelled parse must not return a document")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancelled)
        }
    }

    private fun List<ReleaseNotesSpan>.text() = joinToString("") { it.text }
}
