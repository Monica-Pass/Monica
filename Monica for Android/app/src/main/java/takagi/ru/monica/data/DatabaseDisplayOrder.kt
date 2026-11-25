package takagi.ru.monica.data

/** Reorders the visible subset in its current slots; unrelated sources keep their order. */
internal fun mergeDatabaseDisplayOrder(current: List<Long>, reordered: List<Long>): List<Long> {
    require(reordered.distinct().size == reordered.size)
    val currentIds = current.toSet()
    val surviving = reordered.filter { it in currentIds }
    val selected = surviving.toSet()
    val replacements = surviving.iterator()
    return current.map { if (it in selected) replacements.next() else it }
}
