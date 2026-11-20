package takagi.ru.monica.ui.scanner

/** Duplicate detections of the same payload are one choice, regardless of barcode format. */
internal fun selectScanCandidates(
    candidates: List<DecodedBarcode>,
    requireSingleResult: Boolean,
    validator: (String) -> Boolean
): List<DecodedBarcode> {
    if (!requireSingleResult) return listOfNotNull(candidates.firstOrNull { validator(it.text) })
    return candidates.distinctBy { it.text }.filter { validator(it.text) }.take(2)
}
