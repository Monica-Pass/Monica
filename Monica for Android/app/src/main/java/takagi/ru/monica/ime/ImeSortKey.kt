package takagi.ru.monica.ime

import android.icu.text.Transliterator
import android.os.Build

internal val imeSortKeyTransliterator: Transliterator? by lazy {
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
            // A cancelled background refresh can overlap its successor. ICU is not thread-safe.
            runCatching { synchronized(transliterator) { transliterator.transliterate(trimmed) } }.getOrDefault(trimmed)
        }
    } else {
        trimmed
    }
    return normalizeImeSortText(source, trimmed)
}

/** ICU script setup dominates short titles. Share it across a bounded batch of independent titles. */
internal fun normalizedImeSortKeys(raw: List<String>): List<String> {
    val trimmed = raw.map(String::trim)
    val result = trimmed.map { normalizeImeSortText(it, it) }.toMutableList()
    val nonAscii = trimmed.indices.filter { index -> trimmed[index].any { it.code > 0x7F } }
    if (nonAscii.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return result
    val transliterator = imeSortKeyTransliterator ?: return result
    // NUL survives Any-Latin/Latin-ASCII and separates script contexts. A title containing it
    // uses the individual path, so user text can never be mistaken for a batch boundary.
    nonAscii.filter { '\u0000' in trimmed[it] }.forEach { result[it] = normalizedImeSortKey(trimmed[it]) }
    nonAscii.filter { '\u0000' !in trimmed[it] }.chunked(64).forEach { indices ->
        val source = indices.joinToString("\u0000") { trimmed[it] }
        val latin = runCatching {
            synchronized(transliterator) { transliterator.transliterate(source) }
        }.getOrNull()?.split('\u0000')
        if (latin?.size == indices.size) {
            indices.forEachIndexed { offset, index -> result[index] = normalizeImeSortText(latin[offset], trimmed[index]) }
        } else {
            indices.forEach { result[it] = normalizedImeSortKey(trimmed[it]) }
        }
    }
    return result
}

private fun normalizeImeSortText(source: String, fallback: String): String {
    if (fallback.isEmpty()) return "#"
    return buildString(source.length) {
        source.forEach { char ->
            when {
                char.isLetterOrDigit() -> append(char)
                char.isWhitespace() && isNotEmpty() && last() != ' ' -> append(' ')
            }
        }
    }.trim().ifEmpty { fallback }
}

internal fun imeIndexLetter(sortKey: String): String {
    val first = sortKey.firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}
