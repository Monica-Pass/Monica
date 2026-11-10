package takagi.ru.monica.nfc.card

/** Form-only projection. Never put this object in saved state, an Intent or a navigation route. */
internal class NfcCardFields(
    val number: String,
    val month: String,
    val year: String,
    val holder: String,
    val hasNameParts: Boolean = false,
) {
    override fun toString() = "NfcCardFields(redacted)"

    fun conflictsWith(card: EmvCardReadResult.BankCard): Boolean =
        (number.isNotBlank() && number.filterNot { it.isWhitespace() || it == '-' } != card.number) ||
            (month.isNotBlank() && card.expiryMonth != null && month.toIntOrNull() != card.expiryMonth) ||
            (year.isNotBlank() && card.expiryYear != null && year.toIntOrNull()?.let { if (it < 100) 2000 + it else it } != card.expiryYear) ||
            (!card.holder.isNullOrBlank() && (hasNameParts || (holder.isNotBlank() && holder.trim() != card.holder.trim())))

    fun merge(card: EmvCardReadResult.BankCard) = NfcCardFields(
        card.number,
        card.expiryMonth?.toString()?.padStart(2, '0') ?: month,
        card.expiryYear?.toString() ?: year,
        card.holder?.takeIf(String::isNotBlank) ?: holder,
    )
}

internal enum class NfcCardEditorKind { BANK, DOCUMENT }

internal enum class NfcCardRouteDecision { FILL, REPLACE, NEW_CARD, CONFIRM_NEW_CARD, BUSY }

internal fun nfcCardRouteDecision(
    editor: NfcCardEditorKind?, busy: Boolean, conflict: Boolean, allowNew: Boolean,
): NfcCardRouteDecision = when {
    busy -> NfcCardRouteDecision.BUSY
    editor == NfcCardEditorKind.BANK -> if (conflict) NfcCardRouteDecision.REPLACE else NfcCardRouteDecision.FILL
    editor == NfcCardEditorKind.DOCUMENT -> NfcCardRouteDecision.CONFIRM_NEW_CARD
    allowNew -> NfcCardRouteDecision.NEW_CARD
    else -> NfcCardRouteDecision.BUSY
}
