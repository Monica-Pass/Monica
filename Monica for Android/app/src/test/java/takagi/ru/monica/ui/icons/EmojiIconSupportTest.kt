package takagi.ru.monica.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiIconSupportTest {
    @Test
    fun acceptsSingleEmojiAndTrimsWhitespace() {
        assertEquals("🔑", normalizeEmojiIcon("🔑"))
        assertEquals("🔑", normalizeEmojiIcon("  🔑 \n"))
        assertEquals("©", normalizeEmojiIcon("©"))
    }

    @Test
    fun acceptsVariationSelectorSkinToneFlagKeycapAndZwjSequences() {
        assertEquals("✈️", normalizeEmojiIcon("✈️"))
        assertEquals("👍🏽", normalizeEmojiIcon("👍🏽"))
        assertEquals("🇯🇵", normalizeEmojiIcon("🇯🇵"))
        assertEquals("1️⃣", normalizeEmojiIcon("1️⃣"))
        assertEquals("👨‍👩‍👧", normalizeEmojiIcon("👨‍👩‍👧"))
        assertEquals("🏳️‍🌈", normalizeEmojiIcon("🏳️‍🌈"))
    }

    @Test
    fun rejectsEmptyPlainTextAndMultipleEmoji() {
        assertNull(normalizeEmojiIcon(null))
        assertNull(normalizeEmojiIcon(""))
        assertNull(normalizeEmojiIcon("   "))
        assertNull(normalizeEmojiIcon("a"))
        assertNull(normalizeEmojiIcon("abc"))
        assertNull(normalizeEmojiIcon("1"))
        assertNull(normalizeEmojiIcon("🔑🔐"))
        assertNull(normalizeEmojiIcon("🔑 🔐"))
        assertNull(normalizeEmojiIcon("🔑a"))
        assertNull(normalizeEmojiIcon("🇯🇵🇺🇸"))
    }

    @Test
    fun rejectsDanglingModifiersAndControlCharacters() {
        assertNull(normalizeEmojiIcon("‍🔑"))
        assertNull(normalizeEmojiIcon("️"))
        assertNull(normalizeEmojiIcon("🔑\u0000"))
        assertNull(normalizeEmojiIcon("🔑‍‍🔐"))
    }

    @Test
    fun rejectsMisplacedJoinersEvenWhenTheCountMatches() {
        // 🔑 ZWJ ZWJ 🔐 🔒：ZWJ 数量等于基字符数 - 1，但位置错误
        assertNull(normalizeEmojiIcon("🔑‍‍🔐🔒"))
        // 👍 ZWJ 🏽 👍：肤色修饰符不能直接跟在 ZWJ 后面
        assertNull(normalizeEmojiIcon("👍‍🏽👍"))
        // 键帽符号只能跟在 # * 0-9 后面
        assertNull(normalizeEmojiIcon("🔑⃣"))
    }

    @Test
    fun acceptsTagFlagsAndRejectsHalfFlags() {
        assertEquals("🏴󠁧󠁢󠁥󠁮󠁧󠁿", normalizeEmojiIcon("🏴󠁧󠁢󠁥󠁮󠁧󠁿"))
        assertEquals("#️⃣", normalizeEmojiIcon("#️⃣"))
        assertEquals("🧑🏽‍💻", normalizeEmojiIcon("🧑🏽‍💻"))
        assertNull(normalizeEmojiIcon("🇯"))
        assertNull(normalizeEmojiIcon("🇯🇵‍🔑"))
    }

    @Test
    fun quickPickIconsAreAllValid() {
        QUICK_PICK_EMOJI_ICONS.forEach { emoji ->
            assertEquals(emoji, normalizeEmojiIcon(emoji))
        }
    }

    @Test
    fun rejectsStandaloneRepeatedAndUnsupportedModifiers() {
        listOf("🏽", "👍🏽🏽", "🔑🏽", "✈️️", "👍🏽️", "👍‍🏽", "🔑\uDB40\uDC67").forEach {
            assertNull(it, normalizeEmojiIcon(it))
        }
    }

    @Test
    fun rejectsNonEmojiSymbolsFromOtherwiseEmojiContainingBlocks() {
        listOf("⌂", "⌀", "↔a", "⥀", "🂡").forEach {
            assertNull(it, normalizeEmojiIcon(it))
        }
    }

    @Test
    fun acceptsCompleteTagFlagAndRejectsIncompleteTags() {
        val england = "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
        assertEquals(england, normalizeEmojiIcon(england))
        assertNull(normalizeEmojiIcon(england.dropLast(2)))
        assertNull(normalizeEmojiIcon("🔑" + england.drop(2)))
    }

    @Test
    fun rejectsUnregisteredJoinedEmojiAndFlags() {
        listOf("🔑‍🔐", "👍‍👍", "👨‍🐱", "🇦🇦", "🏴\uDB40\uDC78\uDB40\uDC78\uDB40\uDC7F").forEach {
            assertNull(it, normalizeEmojiIcon(it))
        }
    }

    @Test
    fun acceptsRegisteredJoinedEmojiWithOptionalPresentationAndMixedSkinTones() {
        listOf("🏳‍🌈", "👩‍❤️‍💋‍👩", "🫱🏽‍🫲🏻", "🐦‍🔥", "👩🏽‍🦽‍➡️").forEach {
            assertEquals(it, normalizeEmojiIcon(it))
        }
    }
}
