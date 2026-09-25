package takagi.ru.monica.keepass

import app.keemobile.kotpass.models.Entry
import java.util.Locale

object KeePassEntryFingerprint {
    private const val REMOTE_CONFLICT_TITLE_SUFFIX = "[远端冲突副本]"

    fun build(entry: Entry): String {
        val normalizedFieldPairs = entry.fields
            .map { (key, value) ->
                val normalizedValue = if (key == "Title") {
                    normalizeRemoteConflictTitleForSignature(value.content)
                } else {
                    value.content
                }
                key.lowercase(Locale.ROOT) to normalizedValue
            }
            .sortedBy { it.first }
        return normalizedFieldPairs.joinToString("\u001F") { (key, value) -> "$key=$value" }
    }

    fun buildPresentation(entry: Entry): String = buildString {
        append(entry.icon?.name.orEmpty())
        append('\u001F')
        append(entry.customIconUuid?.toString().orEmpty())
        append('\u001F')
        append(entry.autoType?.enabled ?: "")
        append('\u001F')
        append(entry.autoType?.obfuscation?.name.orEmpty())
        append('\u001F')
        append(entry.autoType?.defaultSequence.orEmpty())
        entry.autoType?.items.orEmpty().forEach { item ->
            append('\u001F')
            append(item.window)
            append('=')
            append(item.keystrokeSequence)
        }
    }

    // Kept separate from presentation signatures persisted by older queued icon patches.
    fun buildProperties(entry: Entry): String = buildString {
        append(entry.times?.expires ?: false)
        append(':')
        append(entry.times?.expiryTime?.toEpochMilli())
        entry.tags.forEach { tag ->
            append(':')
            append(tag.length)
            append(':')
            append(tag)
        }
    }

    private fun normalizeRemoteConflictTitleForSignature(title: String): String {
        val suffixPattern = Regex("\\s*\\Q$REMOTE_CONFLICT_TITLE_SUFFIX\\E(?:\\s*\\Q$REMOTE_CONFLICT_TITLE_SUFFIX\\E)*\\s*$")
        val baseTitle = title
            .replace(suffixPattern, "")
            .trim()
        val hadSuffix = suffixPattern.containsMatchIn(title)
        return if (hadSuffix) {
            val normalizedBase = baseTitle.ifBlank { "Untitled" }
            "$normalizedBase $REMOTE_CONFLICT_TITLE_SUFFIX"
        } else {
            baseTitle
        }
    }
}
