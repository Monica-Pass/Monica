package takagi.ru.monica.ui.password

import androidx.compose.ui.graphics.Color
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.data.model.CardBrand
import takagi.ru.monica.security.maskPasswordNotePreview

internal sealed interface PasswordPageListItemUi {
    val key: String
}

internal fun PasswordPageListItemUi.contentTypeKey(): Any = when (this) {
    is PasswordGroupListItemUi -> if (passwords.size > 1) {
        "password_stack"
    } else {
        "password_single"
    }
    is PasswordManualStackGroupListItemUi -> "password_manual_stack"
    is PasswordSupplementaryListItemUi -> item.type
}

internal data class PasswordPageCardItemUi(
    val key: String,
    val entry: PasswordEntry,
    val type: PasswordPageContentType,
    val badgeText: String? = null,
    val badgeColor: Color? = null,
    val passwordId: Long? = null,
    val secureItemId: Long? = null,
    val passkeyRecordId: Long? = null,
    val walletItemType: PasswordAggregateWalletItemType? = null,
    val bankCardBrand: CardBrand? = null
) {
    val supportsFavorite: Boolean = type != PasswordPageContentType.PASSKEY
    val sortTime: Long = entry.updatedAt.time
}

internal data class PasswordGroupListItemUi(
    val groupKey: String,
    val passwords: List<PasswordEntry>,
    val displayTitle: String? = null
) : PasswordPageListItemUi {
    override val key: String = "group:$groupKey"
}

internal data class PasswordManualStackGroupListItemUi(
    val groupKey: String,
    val cards: List<PasswordPageCardItemUi>
) : PasswordPageListItemUi {
    override val key: String = "manual_group:$groupKey"
}

internal data class PasswordSupplementaryListItemUi(
    val item: PasswordAggregateListItemUi
) : PasswordPageListItemUi {
    override val key: String = item.key
}

internal fun buildPasswordPageListItems(
    selectedContentTypes: Set<PasswordPageContentType>,
    groupedPasswords: Map<String, List<PasswordEntry>>,
    supplementaryItems: List<PasswordAggregateListItemUi>,
    groupMode: String,
    manualStackGroups: List<PasswordManualStackGroupListItemUi> = emptyList()
): List<PasswordPageListItemUi> {
    if (selectedContentTypes.isEmpty()) return emptyList()

    val passwordItems = buildPasswordGroupListItems(
        groupedPasswords = groupedPasswords,
        groupMode = groupMode,
        includePasswords =
            PasswordPageContentType.PASSWORD in selectedContentTypes ||
                PasswordPageContentType.AUTHENTICATOR in selectedContentTypes ||
                PasswordPageContentType.PASSKEY in selectedContentTypes
    )
    val aggregateCards = supplementaryItems.map { item ->
        PasswordSupplementaryListItemUi(item = item)
    }
    val mixedStackGroups = manualStackGroups
        .filter { it.cards.isNotEmpty() }
        .sortedByDescending { group -> group.cards.maxOf { it.sortTime } }

    return buildList<PasswordPageListItemUi> {
        addAll(passwordItems)
        addAll(mixedStackGroups)
        addAll(aggregateCards)
    }.sortedBy { item ->
        // Stable partition: retain the user's ordering within stacks and single items.
        when (item) {
            is PasswordGroupListItemUi -> item.passwords.size <= 1
            is PasswordManualStackGroupListItemUi -> item.cards.size <= 1
            is PasswordSupplementaryListItemUi -> true
        }
    }
}

private fun buildPasswordGroupListItems(
    groupedPasswords: Map<String, List<PasswordEntry>>,
    groupMode: String,
    includePasswords: Boolean
): List<PasswordGroupListItemUi> {
    if (!includePasswords) return emptyList()

    return groupedPasswords.entries
        .filter { (_, passwords) -> passwords.isNotEmpty() }
        .map { (groupKey, passwords) ->
        PasswordGroupListItemUi(
            groupKey = groupKey,
            passwords = passwords,
            displayTitle = resolvePasswordGroupDisplayTitle(passwords, groupMode)
        )
    }
}

private fun resolvePasswordGroupDisplayTitle(
    passwords: List<PasswordEntry>,
    groupMode: String
): String? {
    val first = passwords.firstOrNull() ?: return null
    val noteLabel = getPasswordNoteStackLabel(first) ?: return null
    return when (groupMode) {
        "note" -> maskPasswordNotePreview(noteLabel)
        "smart" -> maskPasswordNotePreview(noteLabel)
        "folder" -> getPasswordFolderStackLabel(first)
        else -> null
    }
}

internal fun PasswordEntry.toPasswordPageCardItemUi(): PasswordPageCardItemUi {
    return PasswordPageCardItemUi(
        key = passwordSelectionKey(id),
        entry = this,
        type = PasswordPageContentType.PASSWORD,
        passwordId = id
    )
}

internal fun PasswordAggregateListItemUi.toPasswordPageCardItemUi(): PasswordPageCardItemUi {
    return PasswordPageCardItemUi(
        key = key,
        entry = entry,
        type = type,
        badgeText = badgeText,
        badgeColor = badgeColor,
        secureItemId = secureItemId,
        passkeyRecordId = passkeyRecordId,
        walletItemType = walletItemType,
        bankCardBrand = bankCardBrand
    )
}

internal fun flattenPasswordPageCardItems(
    items: List<PasswordPageListItemUi>
): List<PasswordPageCardItemUi> {
    return buildList {
        items.forEach { item ->
            when (item) {
                is PasswordGroupListItemUi -> {
                    item.passwords.forEach { password ->
                        add(password.toPasswordPageCardItemUi())
                    }
                }

                is PasswordManualStackGroupListItemUi -> addAll(item.cards)

                is PasswordSupplementaryListItemUi -> add(item.item.toPasswordPageCardItemUi())
            }
        }
    }
}

internal fun resolveSelectedPasswordPageCardItems(
    items: List<PasswordPageListItemUi>,
    selectedKeys: Set<String>
): List<PasswordPageCardItemUi> {
    if (selectedKeys.isEmpty()) return emptyList()
    return flattenPasswordPageCardItems(items)
        .filter { card -> card.key in selectedKeys }
}

internal fun PasswordPageCardItemUi.toSelectedSupplementaryItemOrNull(): PasswordAggregateListItemUi? {
    if (type == PasswordPageContentType.PASSWORD) return null
    return PasswordAggregateListItemUi(
        key = key,
        entry = entry,
        type = type,
        badgeText = badgeText.orEmpty(),
        badgeColor = badgeColor ?: Color.Unspecified,
        sortTime = sortTime,
        secureItemId = secureItemId,
        passkeyRecordId = passkeyRecordId,
        walletItemType = walletItemType,
        bankCardBrand = bankCardBrand
    )
}
