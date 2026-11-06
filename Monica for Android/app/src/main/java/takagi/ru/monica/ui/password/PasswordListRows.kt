package takagi.ru.monica.ui

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.data.PasswordSwipeSelectionMode
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.ui.cardwallet.CardBrandIcon
import takagi.ru.monica.ui.password.PasswordAggregateWalletItemType
import takagi.ru.monica.ui.password.PasswordGroupListItemUi
import takagi.ru.monica.ui.password.PasswordListAggregateConfig
import takagi.ru.monica.ui.password.PasswordListCardBadge
import takagi.ru.monica.ui.password.PasswordManualStackGroup
import takagi.ru.monica.ui.password.PasswordManualStackGroupListItemUi
import takagi.ru.monica.ui.password.PasswordPageCardItemUi
import takagi.ru.monica.ui.password.PasswordPageListItemUi
import takagi.ru.monica.ui.password.PasswordListSingleCardItem
import takagi.ru.monica.ui.password.PasswordSupplementaryListItemUi
import takagi.ru.monica.ui.password.StackCardMode
import takagi.ru.monica.ui.password.StackedPasswordGroup
import takagi.ru.monica.ui.password.contentTypeKey
import takagi.ru.monica.ui.password.passwordSelectionKey
import takagi.ru.monica.ui.password.expandPasswordProjectSelection
import takagi.ru.monica.ui.password.selectionKeysForPasswords
import takagi.ru.monica.ui.password.toPasswordPageCardItemUi
import takagi.ru.monica.viewmodel.PasswordViewModel

internal fun LazyListScope.passwordPageListRows(
    canInteract: () -> Boolean = { true },
    passwordPageListItems: List<PasswordPageListItemUi>,
    effectiveStackCardMode: StackCardMode,
    expandedGroups: Set<String>,
    itemToDelete: PasswordEntry?,
    onItemToDeleteChange: (PasswordEntry?) -> Unit,
    isSelectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    selectedItemKeys: Set<String>,
    onSelectedItemKeysChange: (Set<String>) -> Unit,
    swipeSelectionAnchorKey: String?,
    onSwipeSelectionAnchorKeyChange: (String?) -> Unit,
    selectedPasswords: Set<Long>,
    showBatchDeleteDialog: Boolean,
    onShowBatchDeleteDialogChange: (Boolean) -> Unit,
    viewModel: PasswordViewModel,
    haptic: takagi.ru.monica.ui.haptic.HapticFeedbackHelper,
    onPasswordClick: (PasswordEntry) -> Unit,
    appSettings: AppSettings,
    coroutineScope: CoroutineScope,
    context: Context,
    passwordEntries: List<PasswordEntry>,
    aggregateConfig: PasswordListAggregateConfig?,
    aggregateUiState: PasswordListAggregateUiState,
    decryptAuthenticatorKey: ((String) -> String)? = null,
    nativeTokens: takagi.ru.monica.ui.screens.NativeTokenListUi? = null
) {
    val orderedSelectionKeys = passwordPageListItems.flatMap { item ->
        when (item) {
            is PasswordGroupListItemUi ->
                selectionKeysForPasswords(item.passwords.map(PasswordEntry::id)).toList()
            is PasswordManualStackGroupListItemUi ->
                item.cards.map(PasswordPageCardItemUi::key)
            is PasswordSupplementaryListItemUi ->
                listOf(item.key)
        }
    }

    fun updateProjectSelection(keys: Set<String>) {
        if (!canInteract()) return
        onSelectedItemKeysChange(expandPasswordProjectSelection(keys, passwordEntries))
    }

    fun toggleSelectionForKey(key: String) {
        if (!canInteract()) return
        val projectKeys = expandPasswordProjectSelection(setOf(key), passwordEntries)
        updateProjectSelection(if (projectKeys.all { it in selectedItemKeys })
            selectedItemKeys - projectKeys else selectedItemKeys + projectKeys)
        onSwipeSelectionAnchorKeyChange(key)
    }

    fun toggleSelectionForCards(cards: List<PasswordPageCardItemUi>) {
        if (!canInteract()) return
        val cardKeys = expandPasswordProjectSelection(cards.mapTo(linkedSetOf(), PasswordPageCardItemUi::key), passwordEntries)
        val allSelected = cardKeys.all { it in selectedItemKeys }
        updateProjectSelection(
            if (allSelected) {
                selectedItemKeys - cardKeys
            } else {
                selectedItemKeys + cardKeys
            }
        )
        if (!allSelected) {
            cardKeys.firstOrNull()?.let(onSwipeSelectionAnchorKeyChange)
        }
    }

    fun selectSwipeRangeTo(targetKey: String) {
        if (!canInteract()) return
        if (appSettings.passwordSwipeSelectionMode != PasswordSwipeSelectionMode.CONTINUOUS) {
            toggleSelectionForKey(targetKey)
            return
        }

        val anchorKey = swipeSelectionAnchorKey
        val anchorIndex = orderedSelectionKeys.indexOf(anchorKey)
        val targetIndex = orderedSelectionKeys.indexOf(targetKey)
        if (anchorKey == null || anchorIndex == -1 || targetIndex == -1) {
            updateProjectSelection(setOf(targetKey))
            onSwipeSelectionAnchorKeyChange(targetKey)
            return
        }

        val range = if (anchorIndex <= targetIndex) {
            orderedSelectionKeys.subList(anchorIndex, targetIndex + 1)
        } else {
            orderedSelectionKeys.subList(targetIndex, anchorIndex + 1)
        }
        updateProjectSelection(range.toSet())
    }

    fun selectSwipeRangeToKeys(targetKeys: Set<String>) {
        if (!canInteract()) return
        if (targetKeys.isEmpty()) return

        if (appSettings.passwordSwipeSelectionMode != PasswordSwipeSelectionMode.CONTINUOUS) {
            val allSelected = targetKeys.all { it in selectedItemKeys }
            updateProjectSelection(
                if (allSelected) {
                    selectedItemKeys - targetKeys
                } else {
                    onSwipeSelectionAnchorKeyChange(targetKeys.firstOrNull())
                    selectedItemKeys + targetKeys
                }
            )
            return
        }

        val orderedTargetKeys = orderedSelectionKeys.filter { it in targetKeys }
        val targetKey = orderedTargetKeys.lastOrNull() ?: targetKeys.lastOrNull() ?: return
        val anchorKey = swipeSelectionAnchorKey
        val anchorIndex = orderedSelectionKeys.indexOf(anchorKey)
        val targetIndex = orderedSelectionKeys.indexOf(targetKey)
        if (anchorKey == null || anchorIndex == -1 || targetIndex == -1) {
            updateProjectSelection(targetKeys)
            orderedTargetKeys.firstOrNull()?.let(onSwipeSelectionAnchorKeyChange)
            return
        }

        val range = if (anchorIndex <= targetIndex) {
            orderedSelectionKeys.subList(anchorIndex, targetIndex + 1)
        } else {
            orderedSelectionKeys.subList(targetIndex, anchorIndex + 1)
        }
        updateProjectSelection((range + targetKeys).toSet())
    }

    fun openCard(card: PasswordPageCardItemUi) {
        if (!canInteract()) return
        when (card.type) {
            PasswordPageContentType.API_KEY, PasswordPageContentType.API_TOKEN, PasswordPageContentType.GPG_KEY, PasswordPageContentType.PASSWORD ->
                onPasswordClick(card.entry)

            PasswordPageContentType.AUTHENTICATOR ->
                card.secureItemId?.let { aggregateConfig?.onOpenTotp?.invoke(it) }

            PasswordPageContentType.CARD_WALLET ->
                card.secureItemId?.let { itemId ->
                    when (card.walletItemType) {
                        PasswordAggregateWalletItemType.BANK_CARD ->
                            aggregateConfig?.onOpenBankCard?.invoke(itemId)
                        PasswordAggregateWalletItemType.DOCUMENT ->
                            aggregateConfig?.onOpenDocument?.invoke(itemId)
                        PasswordAggregateWalletItemType.BILLING_ADDRESS ->
                            aggregateConfig?.onOpenBillingAddress?.invoke(itemId)
                        null -> Unit
                    }
                }

            PasswordPageContentType.NOTE ->
                aggregateConfig?.onOpenNote?.invoke(card.secureItemId)

            PasswordPageContentType.PASSKEY ->
                card.passkeyRecordId?.let { aggregateConfig?.onOpenPasskey?.invoke(it) }
        }
    }

    fun requestDeleteForCards(cards: List<PasswordPageCardItemUi>) {
        if (!canInteract()) return
        haptic.performWarning()
        if (!isSelectionMode) {
            onSelectionModeChange(true)
        }
        updateProjectSelection(cards.mapTo(linkedSetOf(), PasswordPageCardItemUi::key))
        if (!showBatchDeleteDialog) {
            onShowBatchDeleteDialogChange(true)
        }
    }

    fun toggleFavoriteForCard(card: PasswordPageCardItemUi) {
        if (!canInteract()) return
        if (nativeTokens?.favoriteDisplayEntry(card.entry) == true) return
        when (card.type) {
            PasswordPageContentType.API_KEY, PasswordPageContentType.API_TOKEN, PasswordPageContentType.GPG_KEY, PasswordPageContentType.PASSWORD ->
                card.passwordId?.let { passwordId ->
                    passwordEntries.firstOrNull { it.id == passwordId }?.let { password ->
                        viewModel.toggleFavorite(password.id, !password.isFavorite)
                    }
                }

            PasswordPageContentType.AUTHENTICATOR ->
                card.secureItemId?.let {
                    aggregateUiState.totpViewModel?.toggleFavorite(it, !card.entry.isFavorite)
                }

            PasswordPageContentType.CARD_WALLET ->
                card.secureItemId?.let { id ->
                    when (card.walletItemType) {
                        PasswordAggregateWalletItemType.BANK_CARD ->
                            aggregateUiState.bankCardViewModel?.toggleFavorite(id)
                        PasswordAggregateWalletItemType.DOCUMENT ->
                            aggregateUiState.documentViewModel?.toggleFavorite(id)
                        PasswordAggregateWalletItemType.BILLING_ADDRESS ->
                            aggregateUiState.billingAddressViewModel?.toggleFavorite(id)
                        null -> Unit
                    }
                }

            PasswordPageContentType.NOTE ->
                card.secureItemId?.let { noteId ->
                    aggregateUiState.notes.firstOrNull { it.id == noteId }?.let { note ->
                        val decoded = NoteContentCodec.decodeFromItem(note)
                        aggregateUiState.noteViewModel?.updateNote(
                            id = note.id,
                            content = decoded.content,
                            title = note.title,
                            tags = decoded.tags,
                            isMarkdown = decoded.isMarkdown,
                            isFavorite = !note.isFavorite,
                            createdAt = note.createdAt,
                            categoryId = note.categoryId,
                            imagePaths = note.imagePaths,
                            keepassDatabaseId = note.keepassDatabaseId,
                            keepassGroupPath = note.keepassGroupPath,
                            bitwardenVaultId = note.bitwardenVaultId,
                            bitwardenFolderId = note.bitwardenFolderId
                        )
                    }
                }

            PasswordPageContentType.PASSKEY -> Unit
        }
    }

    items(
        items = passwordPageListItems,
        key = { item -> item.key },
        contentType = { item -> item.contentTypeKey() }
    ) { listItem ->
        when (listItem) {
            is PasswordGroupListItemUi -> {
                val groupKey = listItem.groupKey
                val passwords = listItem.passwords
                val isExpanded = when (effectiveStackCardMode) {
                    StackCardMode.AUTO -> expandedGroups.contains(groupKey)
                    StackCardMode.ALWAYS_EXPANDED -> true
                }

                StackedPasswordGroup(
                    passwords = passwords,
                    displayTitle = listItem.displayTitle,
                    isExpanded = isExpanded,
                    stackCardMode = effectiveStackCardMode,
                    enableSharedBounds = false,
                    swipedItemId = itemToDelete?.id,
                    onToggleExpand = callback@ {
                        if (!canInteract()) return@callback
                        if (effectiveStackCardMode == StackCardMode.AUTO) {
                            viewModel.toggleExpandedGroup(groupKey)
                        }
                    },
                    onPasswordClick = callback@ { password ->
                        if (!canInteract()) return@callback
                        if (isSelectionMode) {
                            toggleSelectionForKey(passwordSelectionKey(password.id))
                        } else {
                            onPasswordClick(password)
                        }
                    },
                    onSwipeLeft = callback@ { password ->
                        if (!canInteract()) return@callback
                        if (password.loginType == "API_TOKEN") {
                            requestDeleteForCards(listOf(password.toPasswordPageCardItemUi()))
                        } else if (itemToDelete == null) {
                            haptic.performWarning()
                            onItemToDeleteChange(password)
                        }
                    },
                    onSwipeRight = callback@ { password ->
                        if (!canInteract()) return@callback
                        haptic.performSuccess()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                        }
                        selectSwipeRangeTo(passwordSelectionKey(password.id))
                    },
                    onGroupSwipeRight = callback@ { groupPasswords ->
                        if (!canInteract()) return@callback
                        haptic.performSuccess()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                        }
                        val groupSelectionKeys = selectionKeysForPasswords(
                            groupPasswords.map(PasswordEntry::id)
                        )
                        selectSwipeRangeToKeys(groupSelectionKeys)
                    },

                    onToggleFavorite = callback@ { password ->
                        if (!canInteract()) return@callback
                        if (nativeTokens?.favoriteDisplayEntry(password) != true) {
                            viewModel.toggleFavorite(password.id, !password.isFavorite)
                        }
                    },
                    onToggleGroupCover = callback@ { password ->
                        if (!canInteract()) return@callback
                        coroutineScope.launch {
                            if (!canInteract()) return@launch
                            if (passwords.any { it.id < 0 }) {
                                val db = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
                                takagi.ru.monica.repository.PasswordPageAggregateStackRepository(
                                    db.passwordPageAggregateStackDao()
                                ).applyManualStack(
                                    (listOf(password) + passwords.filterNot { it.id == password.id })
                                        .map { passwordSelectionKey(it.id) }
                                )
                                return@launch
                            }
                            val websiteKey = password.website.ifBlank {
                                context.getString(R.string.filter_uncategorized)
                            }
                            val newCoverState = !password.isGroupCover
                            if (newCoverState) {
                                val currentIndex = passwords.indexOfFirst { it.id == password.id }
                                if (currentIndex > 0) {
                                    val reordered = passwords.toMutableList()
                                    val movedPassword = reordered.removeAt(currentIndex)
                                    reordered.add(0, movedPassword)
                                    val firstItemInGroup = passwordEntries.firstOrNull {
                                        it.website.ifBlank {
                                            context.getString(R.string.filter_uncategorized)
                                        } == websiteKey
                                    } ?: return@launch
                                    val startSortOrder = passwordEntries.indexOf(firstItemInGroup)
                                    viewModel.updateSortOrders(
                                        reordered.mapIndexed { idx, entry ->
                                            entry.id to (startSortOrder + idx)
                                        }
                                    )
                                }
                            }
                            viewModel.toggleGroupCover(password.id, websiteKey, newCoverState)
                        }
                    },
                    isSelectionMode = isSelectionMode,
                    selectedPasswords = selectedPasswords,
                    onToggleSelection = callback@ { id ->
                        if (!canInteract()) return@callback
                        toggleSelectionForKey(passwordSelectionKey(id))
                    },
                    onOpenMultiPasswordDialog = callback@ { passwords ->
                        if (!canInteract()) return@callback
                        onPasswordClick(passwords.first())
                    },
                    onLongClick = callback@ { password ->
                        if (!canInteract()) return@callback
                        haptic.performLongPress()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                            val selectionKey = passwordSelectionKey(password.id)
                            updateProjectSelection(setOf(selectionKey))
                            onSwipeSelectionAnchorKeyChange(selectionKey)
                        }
                    },
                    iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
                    unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                    passwordCardDisplayMode = appSettings.passwordCardDisplayMode,
                    passwordCardDisplayFields = appSettings.passwordCardDisplayFields,
                    showAuthenticator = appSettings.passwordCardShowAuthenticator,
                    hideOtherContentWhenAuthenticator = appSettings.passwordCardHideOtherContentWhenAuthenticator,
                    totpTimeOffsetSeconds = appSettings.totpTimeOffset,
                    smoothAuthenticatorProgress = appSettings.validatorSmoothProgress,
                    decryptAuthenticatorKey = decryptAuthenticatorKey
                )
            }

            is PasswordManualStackGroupListItemUi -> {
                PasswordManualStackGroup(
                    groupKey = listItem.groupKey,
                    cards = listItem.cards,
                    isSelectionMode = isSelectionMode,
                    selectedItemKeys = selectedItemKeys,
                    onCardClick = ::openCard,
                    onToggleCardSelection = callback@ { card ->
                        if (!canInteract()) return@callback
                        haptic.performSuccess()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                        }
                        toggleSelectionForKey(card.key)
                    },
                    onToggleGroupSelection = callback@ { cards ->
                        if (!canInteract()) return@callback
                        haptic.performSuccess()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                        }
                        toggleSelectionForCards(cards)
                    },
                    onRequestDelete = ::requestDeleteForCards,
                    onToggleFavorite = ::toggleFavoriteForCard,
                    iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
                    unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                    passwordCardDisplayMode = appSettings.passwordCardDisplayMode,
                    passwordCardDisplayFields = appSettings.passwordCardDisplayFields,
                    showAuthenticator = appSettings.passwordCardShowAuthenticator,
                    hideOtherContentWhenAuthenticator = appSettings.passwordCardHideOtherContentWhenAuthenticator,
                    totpTimeOffsetSeconds = appSettings.totpTimeOffset,
                    smoothAuthenticatorProgress = appSettings.validatorSmoothProgress,
                    decryptAuthenticatorKey = decryptAuthenticatorKey
                )
            }

            is PasswordSupplementaryListItemUi -> {
                val item = listItem.item
                val card = item.toPasswordPageCardItemUi()
                PasswordListSingleCardItem(
                    entry = item.entry,
                    onClick = callback@ {
                        if (!canInteract()) return@callback
                        if (isSelectionMode) {
                            toggleSelectionForKey(item.key)
                        } else {
                            openCard(card)
                        }
                    },
                    onLongClick = callback@ {
                        if (!canInteract()) return@callback
                        haptic.performLongPress()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                            updateProjectSelection(setOf(item.key))
                            onSwipeSelectionAnchorKeyChange(item.key)
                        }
                    },
                    onSwipeLeft = { requestDeleteForCards(listOf(card)) },
                    onSwipeRight = callback@ {
                        if (!canInteract()) return@callback
                        haptic.performSuccess()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                        }
                        selectSwipeRangeTo(item.key)
                    },
                    isSwiped = false,
                    isSelectionMode = isSelectionMode,
                    isSelected = item.key in selectedItemKeys,
                    onToggleFavorite = if (card.supportsFavorite) {
                        { toggleFavoriteForCard(card) }
                    } else {
                        null
                    },
                    unmatchedIconHandlingStrategy = aggregateUiState.cardStyle.unmatchedIconHandlingStrategy,
                    passwordCardDisplayMode = aggregateUiState.cardStyle.passwordCardDisplayMode,
                    passwordCardDisplayFields = aggregateUiState.cardStyle.passwordCardDisplayFields,
                    showAuthenticator = aggregateUiState.cardStyle.showAuthenticator,
                    hideOtherContentWhenAuthenticator = aggregateUiState.cardStyle.hideOtherContentWhenAuthenticator,
                    totpTimeOffsetSeconds = aggregateUiState.cardStyle.totpTimeOffsetSeconds,
                    smoothAuthenticatorProgress = aggregateUiState.cardStyle.smoothAuthenticatorProgress,
                    decryptAuthenticatorKey = decryptAuthenticatorKey,
                    iconCardsEnabled = aggregateUiState.cardStyle.iconCardsEnabled,
                    enableSharedBounds = false,
                    leadingIconOverride = card.bankCardBrand?.let { brand ->
                        {
                            CardBrandIcon(
                                brand = brand,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(width = 52.dp, height = 34.dp)
                            )
                        }
                    },
                    badge = PasswordListCardBadge(
                        text = item.badgeText,
                        color = item.badgeColor
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}
