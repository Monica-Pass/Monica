package takagi.ru.monica.ui.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp

/** 常用快捷 Emoji，供图标对话框一键选择。 */
val QUICK_PICK_EMOJI_ICONS: List<String> = listOf(
    "🔑", "🔐", "💳", "🏦", "🎁", "🛒", "📧", "📱",
    "🎮", "🎵", "🎬", "📚", "🏠", "🚗", "✈️", "🏥",
    "💼", "🏫", "☁️", "🌐", "⭐", "❤️", "🍔", "🐱"
)

/**
 * 归一化用户输入的 Emoji，用于存入 PasswordEntry.customIconValue。
 * 只接受恰好一个用户可见的 Emoji（含 ZWJ 组合、肤色、旗帜、键帽），否则返回 null。
 * 不依赖平台 BreakIterator，保证 JVM 单测与 Android 上行为一致。
 */
fun normalizeEmojiIcon(input: String?): String? {
    val value = input?.trim().orEmpty()
    if (value.isEmpty() || value.length > 32) return null
    val codePoints = value.codePoints().toArray()
    if (codePoints.any { it.isWhitespaceOrControl() }) return null

    // 按 ZWJ 切段：每段必须是一个基字符加若干修饰符，空段意味着 ZWJ 位于首尾或相邻
    val segments = codePoints.toList().split(ZERO_WIDTH_JOINER)
    if (segments.any { it.isEmpty() }) return null

    if (segments.size == 1) {
        val segment = segments.single()
        val base = segment.first()
        // 键帽序列：# * 0-9 + FE0F? + 20E3
        if (base.isKeycapBase()) {
            val tail = segment.drop(1)
            val isKeycap = tail == listOf(COMBINING_ENCLOSING_KEYCAP) ||
                tail == listOf(VARIATION_SELECTOR_16, COMBINING_ENCLOSING_KEYCAP)
            return if (isKeycap) value else null
        }
        // 旗帜：恰好两个区域指示符
        if (base in REGIONAL_INDICATORS) {
            return if (segment.size == 2 && segment[1] in REGIONAL_INDICATORS && isRegisteredEmojiSequence(value)) value else null
        }
        if (segment.any { it in TAG_CHARACTERS }) {
            val validTagFlag = base == 0x1F3F4 && segment.size >= 4 &&
                segment.last() == 0xE007F &&
                segment.subList(1, segment.lastIndex).all { it in 0xE0061..0xE007A || it in 0xE0030..0xE0039 }
            return if (validTagFlag && isRegisteredEmojiSequence(value)) value else null
        }
    }

    val valid = segments.all { segment ->
        val base = segment.first()
        if (!isEmojiCodePoint(base) || base in REGIONAL_INDICATORS || base in SKIN_TONE_MODIFIERS || base.isKeycapBase()) {
            false
        } else {
            var index = 1
            if (segment.getOrNull(index) == VARIATION_SELECTOR_16) index++
            if (segment.getOrNull(index)?.let { it in SKIN_TONE_MODIFIERS } == true) {
                if (!isEmojiModifierBase(base)) return@all false
                index++
            }
            index == segment.size
        }
    }
    // A grapheme boundary alone is insufficient: arbitrary ZWJ chains can render as several icons.
    return if (valid && (segments.size == 1 || isRegisteredEmojiSequence(value))) value else null
}

private fun List<Int>.split(separator: Int): List<List<Int>> {
    val result = mutableListOf(mutableListOf<Int>())
    forEach { if (it == separator) result += mutableListOf<Int>() else result.last() += it }
    return result
}

/** 以文本形式渲染 Emoji 图标，各列表/详情页共用。 */
@Composable
fun EmojiIconText(
    emoji: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    // This text is artwork: keep it the same size as adjacent dp-based icons.
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = emoji,
            fontSize = fontSize,
            lineHeight = fontSize,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

private const val ZERO_WIDTH_JOINER = 0x200D
private const val VARIATION_SELECTOR_16 = 0xFE0F
private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3
private val SKIN_TONE_MODIFIERS = 0x1F3FB..0x1F3FF
private val TAG_CHARACTERS = 0xE0020..0xE007F
private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

private fun Int.isWhitespaceOrControl(): Boolean =
    Character.isWhitespace(this) || Character.isISOControl(this)

private fun Int.isKeycapBase(): Boolean =
    this == '#'.code || this == '*'.code || this in '0'.code..'9'.code
