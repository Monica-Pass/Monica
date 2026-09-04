package takagi.ru.monica.ui.cardwallet

internal fun mergeVisibleWalletOrder(
    allItemIds: List<Long>,
    reorderedVisibleItemIds: List<Long>
): List<Long> {
    if (allItemIds.isEmpty() || reorderedVisibleItemIds.isEmpty()) return allItemIds
    val visibleIds = reorderedVisibleItemIds.toSet()
    val reordered = reorderedVisibleItemIds.iterator()
    return allItemIds.map { itemId ->
        if (itemId in visibleIds && reordered.hasNext()) reordered.next() else itemId
    }
}

internal fun <T> orderItemsByIds(
    items: List<T>,
    orderedIds: List<Long>,
    idOf: (T) -> Long
): List<T> {
    if (items.size < 2 || orderedIds.isEmpty()) return items

    val itemsById = items.associateBy(idOf)
    val addedIds = HashSet<Long>(items.size)
    return buildList(items.size) {
        orderedIds.forEach { id ->
            val item = itemsById[id]
            if (item != null && addedIds.add(id)) add(item)
        }
        items.forEach { item ->
            if (addedIds.add(idOf(item))) add(item)
        }
    }
}
