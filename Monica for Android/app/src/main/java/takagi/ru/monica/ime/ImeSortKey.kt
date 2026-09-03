package takagi.ru.monica.ime

import android.icu.text.Transliterator
import android.os.Build

internal val imeSortKeyTransliterator: Transliterator? by lazy(LazyThreadSafetyMode.NONE) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
    } else {
        null
    }
}

internal fun normalizedImeSortKey(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "#"
    val source = if (trimmed.none { it.code > 0x7F }) {
        trimmed
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val transliterator = imeSortKeyTransliterator
        if (transliterator == null) {
            trimmed
        } else {
            runCatching { transliterator.transliterate(trimmed) }.getOrDefault(trimmed)
        }
    } else {
        trimmed
    }
    return buildString(source.length) {
        source.forEach { char ->
            when {
                char.isLetterOrDigit() -> append(char)
                char.isWhitespace() && isNotEmpty() && last() != ' ' -> append(' ')
            }
        }
    }.trim().ifEmpty { trimmed }
}

internal fun imeIndexLetter(sortKey: String): String {
    val first = sortKey.firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}
