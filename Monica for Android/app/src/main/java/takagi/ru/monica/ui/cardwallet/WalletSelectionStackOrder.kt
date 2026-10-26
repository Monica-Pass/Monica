package takagi.ru.monica.ui.cardwallet

/** Apply drag order immediately, retaining each expanded section as one contiguous block.
 * Collapse is presentation only: the full visible membership stays on the header.
 */
internal fun prepareWalletSelectionEntries(
    entries: List<WalletStackListEntry>,
    cards: List<WalletListItem>,
    collapsedStackIds: Set<String>
): List<WalletStackListEntry> {
    val orderedEntries = orderWalletIndependentEntries(entries, cards)
    val headers = orderedEntries.filterIsInstance<WalletStackListEntry.SelectionHeader>()
        .filter { it.stack != null }
    if (headers.isEmpty()) return orderedEntries
    val ranks = cards.withIndex().associate { it.value.id to it.index }
    val members = orderedEntries.filterIsInstance<WalletStackListEntry.Single>()
        .filter { it.selectionStackId != null }.groupBy { it.selectionStackId }
    return buildList {
        headers.sortedBy { header -> header.cards.minOfOrNull { ranks[it.id] ?: Int.MAX_VALUE } }.forEach { header ->
            val stackId = header.stack!!.id
            val collapsed = stackId in collapsedStackIds
            add(header.copy(collapsed = collapsed))
            if (!collapsed) addAll(members[stackId].orEmpty())
        }
        addAll(orderedEntries.filter { entry ->
            when (entry) {
                is WalletStackListEntry.SelectionHeader -> entry.stack == null
                is WalletStackListEntry.Single -> entry.selectionStackId == null
                is WalletStackListEntry.Stack -> true
            }
        })
    }
}

/** Move a closed stack as a block in the existing card sort order. Persisting still uses
 * mergeVisibleWalletOrder, so cards outside the current filter retain their slots.
 * Membership and cover are never rewritten by a list drag.
 */
internal fun reorderWalletSelectionCards(
    cards: List<WalletListItem>,
    from: WalletStackListEntry?,
    to: WalletStackListEntry?
): List<WalletListItem> {
    if (from is WalletStackListEntry.Single) return reorderWalletSingleCards(cards, from, to)
    if (from !is WalletStackListEntry.SelectionHeader || to !is WalletStackListEntry.SelectionHeader ||
        from.stack == null || to.stack == null || !from.collapsed || !to.collapsed || from.key == to.key
    ) return cards
    val movingIds = from.cards.mapTo(hashSetOf(), WalletListItem::id)
    val targetIds = to.cards.mapTo(hashSetOf(), WalletListItem::id)
    if (movingIds.any { it in targetIds }) return cards
    val fromIndex = cards.indexOfFirst { it.id in movingIds }
    val toIndex = cards.indexOfFirst { it.id in targetIds }
    if (fromIndex < 0 || toIndex < 0) return cards
    val moving = cards.filter { it.id in movingIds }
    val remaining = cards.filterNot { it.id in movingIds }.toMutableList()
    val destination = if (fromIndex < toIndex) {
        remaining.indexOfLast { it.id in targetIds } + 1
    } else remaining.indexOfFirst { it.id in targetIds }
    if (destination !in 0..remaining.size) return cards
    remaining.addAll(destination, moving)
    return remaining
}
