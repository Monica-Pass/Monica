package takagi.ru.monica.ui.vaultv2

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import takagi.ru.monica.data.model.CardBrand
import takagi.ru.monica.data.model.CardBrandDetector
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.rustcore.RustVaultPickerCore

internal const val OVERVIEW_PICKER_NATIVE_THRESHOLD = 512

internal data class OverviewPickerEntry(
    val item: VaultV2Item,
    val identity: String,
    val source: String,
    val sourceIndex: Int,
    val detail: String,
    val cardLast4: String,
    val cardBrand: CardBrand?,
    val searchText: String,
)

/** Prepared once on a worker, shared by search and checkbox changes until the owner closes. */
internal class PreparedOverviewPicker(
    val rows: List<OverviewPickerEntry>,
    private val sourceIndices: Map<String, Int>,
    handle: Long?,
) : AutoCloseable {
    private val nativeHandle = AtomicLong(handle ?: 0)
    private val rowsBySource = rows.groupBy(OverviewPickerEntry::source)
    val usesNative: Boolean get() = nativeHandle.get() > 0

    fun filter(query: String, scope: String): List<OverviewPickerEntry> {
        val scoped = scopedRows(scope)
        val normalized = normalizePickerQuery(query)
        if (normalized.isEmpty() || scoped.isEmpty()) return scoped
        val source = if (scope == "all") -1 else sourceIndices[scope] ?: return emptyList()
        val handle = nativeHandle.get()
        val indices = if (handle > 0) RustVaultPickerCore.filter(handle, normalized, source) else null
        if (indices != null && validOverviewPickerIndices(indices, rows, source)) {
            return indices.map(rows::get)
        }
        return scoped.filter { it.searchText.contains(normalized) }
    }

    fun filterKotlin(query: String, scope: String): List<OverviewPickerEntry> {
        val normalized = normalizePickerQuery(query)
        val scoped = scopedRows(scope)
        return if (normalized.isEmpty()) scoped else scoped.filter { it.searchText.contains(normalized) }
    }

    private fun scopedRows(scope: String): List<OverviewPickerEntry> =
        if (scope == "all") rows else rowsBySource[scope].orEmpty()

    override fun close() {
        RustVaultPickerCore.close(nativeHandle.getAndSet(0))
    }
}

internal fun normalizePickerQuery(query: String): String = query.trim().lowercase(Locale.ROOT)

internal fun validOverviewPickerIndices(indices: IntArray, rows: List<OverviewPickerEntry>, source: Int): Boolean {
    if (indices.size > rows.size) return false
    var previous = -1
    for (index in indices) {
        if (index <= previous || index !in rows.indices || (source >= 0 && rows[index].sourceIndex != source)) return false
        previous = index
    }
    return true
}

internal fun prepareOverviewPicker(
    items: List<VaultV2Item>,
    sources: List<VaultOverviewSource>,
    cards: Boolean?,
    priorityIdentities: List<String> = emptyList(),
    decrypt: ((String) -> String)? = null,
    checkActive: () -> Unit = {},
    nativeThreshold: Int = OVERVIEW_PICKER_NATIVE_THRESHOLD,
    openNative: (ByteArray) -> Long? = RustVaultPickerCore::open,
): PreparedOverviewPicker {
    val sourceByKey = sources.associateBy(VaultOverviewSource::key)
    val sourceIndices = sources.withIndex().associate { it.value.key to it.index }
    val identities = hashSetOf<String>()
    val priorities = priorityIdentities.toSet()
    val priorityRows = hashMapOf<String, OverviewPickerEntry>()
    // Resolve the small priority prefix during preparation. Queries and checkbox
    // changes can then reuse the same row order and native index without sorting.
    val remainingRows = buildList {
        items.forEachIndexed { index, item ->
            if (index % 64 == 0) checkActive()
            if (cards != null && (item.type in overviewCardTypes) != cards) return@forEachIndexed
            val sourceKey = item.overviewSource()
            val source = sourceByKey[sourceKey]?.takeUnless { it.locked } ?: return@forEachIndexed
            val identity = item.overviewIdentity()
            if (!identities.add(identity)) return@forEachIndexed
            val bank = if (item.type == VaultV2ItemType.BANK_CARD) item.secureItem?.let {
                CardWalletDataCodec.parseBankCardData(it.itemData, decrypt)
            } else null
            val last4 = bank?.cardNumber?.filter(Char::isDigit)?.takeLast(4).orEmpty()
            val brand = if (item.type == VaultV2ItemType.BANK_CARD) {
                bank?.let {
                    CardBrandDetector.detectStoredCard(it.cardNumber, listOf(it.brand, item.title, it.nickname, it.bankName).joinToString(" "))
                } ?: CardBrand.UNKNOWN
            } else null
            val password = item.passwordEntry
            val detail = when (item.type) {
                VaultV2ItemType.BANK_CARD -> listOf(bank?.bankName.orEmpty(), bank?.cardholderName.orEmpty())
                    .filter(String::isNotBlank).distinct().joinToString(" · ")
                VaultV2ItemType.PASSWORD -> password?.username.orEmpty().ifBlank { password?.website.orEmpty() }
                VaultV2ItemType.NOTE -> ""
                else -> item.subtitle.takeUnless { it == "-" }.orEmpty()
            }
            // Deliberately exclude searchableValues: it can contain password notes,
            // full document/card numbers and other fields unnecessary for a picker.
            val text = listOf(item.title, source.name, bank?.bankName.orEmpty(), last4,
                password?.username.orEmpty(), password?.website.orEmpty(), password?.appName.orEmpty())
                .joinToString("\u0000").lowercase(Locale.ROOT)
            val row = OverviewPickerEntry(item, identity, sourceKey, sourceIndices.getValue(sourceKey), detail, last4, brand, text)
            if (identity in priorities) priorityRows[identity] = row else add(row)
        }
    }
    val orderedRows = if (priorityRows.isEmpty()) remainingRows else buildList(priorityRows.size + remainingRows.size) {
        priorities.forEach { identity -> priorityRows[identity]?.let { add(it) } }
        addAll(remainingRows)
    }
    val rows = orderedRows.groupBy { it.item.projectDisplayIdentity() }.values.map { project ->
        if (project.size == 1) project.single() else project.first().copy(
            searchText = project.joinToString("\u0000") { it.searchText })
    }
    checkActive()
    val handle = if (rows.size in nativeThreshold..100_000) openNative(encodeOverviewPickerMetadata(rows)) else null
    // No cancellation point after acquiring the handle: the owner must first receive it.
    return PreparedOverviewPicker(rows, sourceIndices, handle)
}

internal fun encodeOverviewPickerMetadata(rows: List<OverviewPickerEntry>): ByteArray {
    val output = ByteArrayOutputStream(8 + rows.size.coerceAtMost(2048) * 96)
    fun int(value: Int) {
        repeat(4) { output.write((value ushr (it * 8)) and 0xff) }
    }
    int(0x31504F4D)
    int(rows.size)
    for (row in rows) {
        val text = row.searchText.toByteArray(Charsets.UTF_8)
        int(row.sourceIndex)
        int(text.size)
        output.write(text)
    }
    return output.toByteArray()
}
