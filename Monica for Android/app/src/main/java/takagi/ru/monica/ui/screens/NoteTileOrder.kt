package takagi.ru.monica.ui.screens

/** Merge a filtered reorder into the complete note order without moving hidden items. */
internal fun mergeVisibleNoteOrder(
    allItemIds: List<Long>,
    reorderedVisibleItemIds: List<Long>
): List<Long> {
    if (allItemIds.isEmpty() || reorderedVisibleItemIds.isEmpty()) return allItemIds
    val allIds = allItemIds.toSet()
    val reorderedVisible = ArrayList<Long>(reorderedVisibleItemIds.size)
    val seenVisible = HashSet<Long>()
    reorderedVisibleItemIds.forEach { id ->
        if (id in allIds && seenVisible.add(id)) reorderedVisible.add(id)
    }
    if (reorderedVisible.isEmpty()) return allItemIds
    val visibleSet = reorderedVisible.toSet()
    val result = ArrayList<Long>(allItemIds.size)
    var reorderedIndex = 0
    allItemIds.forEach { id ->
        if (id in visibleSet && reorderedIndex < reorderedVisible.size) {
            result.add(reorderedVisible[reorderedIndex++])
        } else {
            result.add(id)
        }
    }
    return result
}

internal fun <T> orderNoteItemsByIds(
    items: List<T>,
    orderedIds: List<Long>,
    idOf: (T) -> Long
): List<T> {
    if (items.isEmpty() || orderedIds.isEmpty()) return items
    val byId = items.associateBy(idOf)
    val ordered = ArrayList<T>(items.size)
    val seen = HashSet<Long>()
    orderedIds.forEach { id ->
        val item = byId[id]
        if (item != null && seen.add(id)) ordered.add(item)
    }
    if (ordered.size == items.size) return ordered
    items.forEach { item ->
        if (seen.add(idOf(item))) ordered.add(item)
    }
    return ordered
}

/** Keep a pending order usable when the source list gains or loses items. */
internal fun normalizeNoteOrder(
    preferredIds: List<Long>,
    currentIds: List<Long>
): List<Long> {
    if (currentIds.isEmpty()) return emptyList()
    val currentSet = HashSet<Long>(currentIds.size)
    currentIds.forEach { currentSet.add(it) }
    val normalized = ArrayList<Long>(currentIds.size)
    val seen = HashSet<Long>(currentIds.size)
    preferredIds.forEach { id ->
        if (id in currentSet && seen.add(id)) normalized.add(id)
    }
    currentIds.forEach { id ->
        if (seen.add(id)) normalized.add(id)
    }
    return normalized
}
