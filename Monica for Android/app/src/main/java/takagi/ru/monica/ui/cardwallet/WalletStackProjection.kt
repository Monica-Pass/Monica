package takagi.ru.monica.ui.cardwallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.WalletStack
import takagi.ru.monica.rustcore.RustWalletStackCore

@Composable
internal fun rememberWalletStackEntries(
    cards: List<WalletListItem>,
    stacks: List<WalletStack>,
    showIndividualCards: Boolean,
    selectionMode: Boolean = false
): State<WalletStackProjection> = produceState(
    initialValue = WalletStackProjection(emptyList(), selectionMode),
    System.identityHashCode(cards), stacks, showIndividualCards, selectionMode
) {
    value = withContext(Dispatchers.Default) {
        WalletStackProjection(
            projectWalletStacks(cards, stacks, showIndividualCards, selectionMode),
            selectionMode
        )
    }
}

internal data class WalletStackProjection(
    val entries: List<WalletStackListEntry>,
    val selectionMode: Boolean
)

internal sealed interface WalletStackListEntry {
    val key: String

    data class Single(
        val card: WalletListItem,
        val selectionStackId: String? = null,
        val isLastInSelectionStack: Boolean = false
    ) : WalletStackListEntry {
        override val key: String = "card:${card.id}"
    }

    data class Stack(val stack: WalletStack, val cards: List<WalletListItem>) : WalletStackListEntry {
        override val key: String = "stack:${stack.id}"
        val cover: WalletListItem get() = cards.firstOrNull { it.id == stack.coverId } ?: cards.first()
    }

    data class SelectionHeader(
        val stack: WalletStack?,
        val cards: List<WalletListItem>,
        val collapsed: Boolean = false
    ) : WalletStackListEntry {
        // Keep the stack's identity when its presentation changes to a section header.
        override val key: String = stack?.let { "stack:${it.id}" } ?: "selection:unstacked"
        val cover: WalletListItem? get() = cards.firstOrNull { it.id == stack?.coverId } ?: cards.firstOrNull()
    }
}

/** Pin groups ahead of the sorted singles; filters never rewrite persisted membership. */
internal fun projectWalletStacks(
    visibleCards: List<WalletListItem>,
    stacks: List<WalletStack>,
    showIndividualCards: Boolean = false,
    selectionMode: Boolean = false
): List<WalletStackListEntry> {
    if (visibleCards.size >= 256 && stacks.isNotEmpty() && (!showIndividualCards || selectionMode)) {
        projectWalletStacksNative(visibleCards, stacks, selectionMode)?.let { return it }
    }
    return projectWalletStacksKotlin(visibleCards, stacks, showIndividualCards, selectionMode)
}

/** Includes packing, JNI, validation and mapping; never used by the animation's frame loop. */
internal fun projectWalletStacksNative(
    visibleCards: List<WalletListItem>,
    stacks: List<WalletStack>,
    selectionMode: Boolean
): List<WalletStackListEntry>? {
    val indices = RustWalletStackCore.projectIndices(visibleCards, stacks, selectionMode, WalletListItem::id)
        ?: return null
    return decodeWalletStackProjection(visibleCards, stacks, selectionMode, indices)
}

/** Require a complete, unique index permutation before accepting a native projection. */
internal fun decodeWalletStackProjection(
    visibleCards: List<WalletListItem>,
    stacks: List<WalletStack>,
    selectionMode: Boolean,
    indices: IntArray
): List<WalletStackListEntry>? {
    if (indices.size < 3 || indices[0] != 1) return null
    val groupCount = indices[1]
    if (groupCount !in 0..minOf(stacks.size, visibleCards.size)) return null
    val seenCards = BooleanArray(visibleCards.size)
    val seenGroups = BooleanArray(stacks.size)
    var cursor = 2
    var acceptedCards = 0
    fun readCards(count: Int): List<WalletListItem>? {
        if (count < 0 || count > indices.size - cursor || count > visibleCards.size - acceptedCards) return null
        val cards = ArrayList<WalletListItem>(count)
        repeat(count) {
            val index = indices[cursor++]
            if (index !in visibleCards.indices || seenCards[index]) return null
            seenCards[index] = true
            cards.add(visibleCards[index])
        }
        acceptedCards += count
        return cards
    }
    val entries = ArrayList<WalletStackListEntry>()
    repeat(groupCount) {
        if (indices.size - cursor < 2) return null
        val groupIndex = indices[cursor++]
        val memberCount = indices[cursor++]
        if (groupIndex !in stacks.indices || seenGroups[groupIndex] ||
            memberCount < (if (selectionMode) 1 else 2)
        ) return null
        seenGroups[groupIndex] = true
        val stack = stacks[groupIndex]
        val members = readCards(memberCount) ?: return null
        if (selectionMode) {
            entries.add(WalletStackListEntry.SelectionHeader(stack, members))
            members.forEachIndexed { index, card ->
                entries.add(WalletStackListEntry.Single(card, stack.id, index == members.lastIndex))
            }
        } else {
            entries.add(WalletStackListEntry.Stack(stack, members))
        }
    }
    if (cursor >= indices.size) return null
    val singleCount = indices[cursor++]
    if (singleCount != indices.size - cursor) return null
    val singles = readCards(singleCount) ?: return null
    if (acceptedCards != visibleCards.size) return null
    if (selectionMode && groupCount > 0 && singles.isNotEmpty()) {
        entries.add(WalletStackListEntry.SelectionHeader(null, singles))
    }
    singles.forEach { entries.add(WalletStackListEntry.Single(it)) }
    return entries
}

internal fun projectWalletStacksKotlin(
    visibleCards: List<WalletListItem>,
    stacks: List<WalletStack>,
    showIndividualCards: Boolean = false,
    selectionMode: Boolean = false
): List<WalletStackListEntry> {
    if (stacks.isEmpty() || showIndividualCards && !selectionMode) {
        return visibleCards.map { WalletStackListEntry.Single(it) }
    }
    val visibleById = visibleCards.associateBy(WalletListItem::id)
    val minimumVisibleMembers = if (selectionMode) 1 else 2
    val groupsByMember = buildMap<Long, WalletStackListEntry.Stack> {
        stacks.forEach { stack ->
            val members = stack.memberIds.distinct().mapNotNull(visibleById::get)
            if (members.size >= minimumVisibleMembers) {
                val entry = WalletStackListEntry.Stack(stack, members)
                members.forEach { put(it.id, entry) }
            }
        }
    }
    val emittedGroups = mutableSetOf<String>()
    val singles = ArrayList<WalletStackListEntry.Single>()
    val groupedEntries = buildList<WalletStackListEntry> {
        visibleCards.forEach { card ->
            val group = groupsByMember[card.id]
            if (group == null) singles.add(WalletStackListEntry.Single(card))
            else if (emittedGroups.add(group.stack.id)) add(group)
        }
        addAll(singles)
    }
    if (!selectionMode || emittedGroups.isEmpty()) return groupedEntries

    return buildList {
        groupedEntries.filterIsInstance<WalletStackListEntry.Stack>().forEach { entry ->
            add(WalletStackListEntry.SelectionHeader(entry.stack, entry.cards))
            entry.cards.forEachIndexed { index, card ->
                add(WalletStackListEntry.Single(card, entry.stack.id, index == entry.cards.lastIndex))
            }
        }
        if (singles.isNotEmpty()) {
            add(WalletStackListEntry.SelectionHeader(null, singles.map { it.card }))
            addAll(singles)
        }
    }
}

/** A group action only affects cards visible in the current search and category. */
internal fun toggleWalletSelectionGroup(
    selectedIds: Set<Long>,
    group: WalletStackListEntry.SelectionHeader
): Set<Long> {
    val groupIds = group.cards.mapTo(mutableSetOf(), WalletListItem::id)
    return if (selectedIds.containsAll(groupIds)) selectedIds - groupIds else selectedIds + groupIds
}

/** Section headers and stack members are not destinations for single-card reordering. */
internal fun reorderWalletSingleCards(
    cards: List<WalletListItem>,
    from: WalletStackListEntry?,
    to: WalletStackListEntry?
): List<WalletListItem> {
    if (from !is WalletStackListEntry.Single || to !is WalletStackListEntry.Single ||
        from.selectionStackId != null || to.selectionStackId != null || from.card.id == to.card.id
    ) return cards
    val fromIndex = cards.indexOfFirst { it.id == from.card.id }
    val toIndex = cards.indexOfFirst { it.id == to.card.id }
    if (fromIndex < 0 || toIndex < 0) return cards
    return cards.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}

/** Apply local drag order without waiting for background membership projection.
 * Headers and grouped members keep their exact slots and identities.
 */
internal fun orderWalletIndependentEntries(
    entries: List<WalletStackListEntry>,
    cards: List<WalletListItem>
): List<WalletStackListEntry> {
    val singles = entries.filterIsInstance<WalletStackListEntry.Single>()
        .filter { it.selectionStackId == null }
    if (singles.size < 2) return entries
    val byId = singles.associateBy { it.card.id }
    val ordered = cards.mapNotNull { byId[it.id] }
    // A filter/membership refresh may still be in flight. Do not drop or duplicate rows.
    if (ordered.size != singles.size || ordered == singles) return entries
    var index = 0
    return entries.map { entry ->
        if (entry is WalletStackListEntry.Single && entry.selectionStackId == null) {
            ordered[index++]
        } else entry
    }
}
