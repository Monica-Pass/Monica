package takagi.ru.monica.ui

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.rustcore.RustPasswordGroupingCore
import takagi.ru.monica.ui.password.StackCardMode
import takagi.ru.monica.ui.password.getGroupKeyForMode
import takagi.ru.monica.ui.password.getPasswordInfoKey

internal const val NATIVE_PASSWORD_GROUPING_THRESHOLD = 256

/** String normalization stays in Kotlin, preserving the existing matching rules. */
internal fun tryBuildNativePasswordGroups(
    entries: List<PasswordEntry>,
    config: PasswordGroupingConfig,
    project: (IntArray) -> IntArray? = RustPasswordGroupingCore::project,
): Map<String, List<PasswordEntry>>? {
    if (config.isLocalOnlyView || entries.size !in 1..RustPasswordGroupingCore.MAX_ENTRIES) return null
    val infoIds = HashMap<String, Int>()
    val bucketIds = HashMap<String, Int>()
    val outerIds = LinkedHashMap<String, Int>()
    val expanded = config.effectiveStackCardMode == StackCardMode.ALWAYS_EXPANDED
    val titleMode = config.effectiveGroupMode == "title"
    val batch = IntArray(RustPasswordGroupingCore.HEADER + entries.size * RustPasswordGroupingCore.WIDTH)
    batch[0] = 1
    batch[1] = entries.size
    batch[2] = if (expanded) 1 else 0
    batch[3] = if (titleMode) 1 else 0
    entries.forEachIndexed { index, entry ->
        val infoKey = getPasswordInfoKey(entry)
        val forcedKey = when {
            entry.id in config.effectiveNoStackEntryIds -> "no_stack:${entry.id}"
            else -> config.effectiveManualStackGroupByEntryId[entry.id]?.let { "manual_stack:$it" }
        }
        val bucketKey = forcedKey ?: infoKey
        val outerKey = forcedKey ?: if (titleMode) {
            entry.title.ifBlank { config.untitledLabel }
        } else {
            getGroupKeyForMode(entry, config.effectiveGroupMode, config.websiteStackMatchMode)
        }
        val offset = RustPasswordGroupingCore.HEADER + index * RustPasswordGroupingCore.WIDTH
        batch[offset] = bucketIds.getOrPut(bucketKey) { bucketIds.size }
        batch[offset + 1] = infoIds.getOrPut(infoKey) { infoIds.size }
        batch[offset + 2] = outerIds.getOrPut(outerKey) { outerIds.size }
        batch[offset + 4] = entry.sortOrder
        batch[offset + 5] = if (entry.isFavorite) 1 else 0
    }
    val outerKeys = outerIds.keys.toList()
    if (titleMode) {
        // Kotlin String ordering is UTF-16. Send numeric ranks to preserve it in Rust.
        val ranks = IntArray(outerKeys.size)
        outerKeys.indices.sortedBy { outerKeys[it] }.forEachIndexed { rank, id -> ranks[id] = rank }
        entries.indices.forEach { index ->
            val offset = RustPasswordGroupingCore.HEADER + index * RustPasswordGroupingCore.WIDTH
            batch[offset + 3] = ranks[batch[offset + 2]]
        }
    }
    val output = project(batch) ?: return null
    return decodePasswordGrouping(output, entries, outerKeys, expanded)
}

internal fun decodePasswordGrouping(
    output: IntArray,
    entries: List<PasswordEntry>,
    outerKeys: List<String>,
    expanded: Boolean,
): Map<String, List<PasswordEntry>>? {
    if (output.size < 2 || output[0] != 1 || output[1] !in 0..entries.size) return null
    val groupCount = output[1]
    if (output.size.toLong() != 2L + entries.size + groupCount * 2L) return null
    val seenEntries = BooleanArray(entries.size)
    val seenGroups = BooleanArray(outerKeys.size)
    val groups = LinkedHashMap<String, List<PasswordEntry>>(groupCount)
    var cursor = 2
    var covered = 0
    repeat(groupCount) {
        if (cursor + 2 > output.size) return null
        val key = output[cursor++]
        val count = output[cursor++]
        if (key !in outerKeys.indices || seenGroups[key] || count < 1 || count > output.size - cursor) return null
        seenGroups[key] = true
        val members = ArrayList<PasswordEntry>(count)
        repeat(count) {
            val index = output[cursor++]
            if (index !in entries.indices || seenEntries[index]) return null
            seenEntries[index] = true
            members.add(entries[index])
        }
        covered += count
        groups[outerKeys[key]] = members
    }
    if (cursor != output.size || covered != entries.size) return null
    return if (expanded) buildUnstackedPasswordProjectGroups(groups.values.flatten()) else groups
}
