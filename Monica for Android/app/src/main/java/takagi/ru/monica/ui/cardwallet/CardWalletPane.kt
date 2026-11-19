package takagi.ru.monica.ui

import takagi.ru.monica.ui.common.layout.AdaptiveListDetailLayout
import androidx.compose.foundation.layout.Column
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.unit.Dp
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.BillingAddressViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel

@Composable
internal fun CardWalletPane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    saveableStateHolder: SaveableStateHolder,
    bankCardViewModel: BankCardViewModel,
    documentViewModel: DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    passwordViewModel: takagi.ru.monica.viewmodel.PasswordViewModel,
    bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel? = null,
    contentState: CardWalletContentState,
    isAddingBankCardInline: Boolean,
    inlineBankCardEditorId: Long?,
    onInlineBankCardEditorBack: () -> Unit,
    isAddingDocumentInline: Boolean,
    inlineDocumentEditorId: Long?,
    onInlineDocumentEditorBack: () -> Unit,
    isAddingBillingAddressInline: Boolean,
    inlineBillingAddressEditorId: Long?,
    onInlineBillingAddressEditorBack: () -> Unit,
    selectedBankCardId: Long?,
    onClearSelectedBankCard: () -> Unit,
    onEditBankCard: (Long) -> Unit,
    selectedDocumentId: Long?,
    onClearSelectedDocument: () -> Unit,
    onEditDocument: (Long) -> Unit,
    selectedBillingAddressId: Long?,
    onClearSelectedBillingAddress: () -> Unit,
    onEditBillingAddress: (Long) -> Unit,
    initialCategoryId: Long? = null,
    initialStorageExplicit: Boolean = false,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialMdbxDatabaseId: Long? = null,
    initialMdbxFolderId: String? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {}
) {
    val listPaneContent: @Composable ColumnScope.() -> Unit = {
        CardWalletContent(
            saveableStateHolder = saveableStateHolder,
            bankCardViewModel = bankCardViewModel,
            documentViewModel = documentViewModel,
            billingAddressViewModel = billingAddressViewModel,
            passwordViewModel = passwordViewModel,
            bitwardenViewModel = bitwardenViewModel,
            state = contentState,
            showStandaloneSettingsEntry = showStandaloneSettingsEntry,
            onOpenStandaloneSettings = onOpenStandaloneSettings
        )
    }

    val hasAdaptiveDetail = isAddingBankCardInline || inlineBankCardEditorId != null || selectedBankCardId != null || isAddingDocumentInline || inlineDocumentEditorId != null || selectedDocumentId != null || isAddingBillingAddressInline || inlineBillingAddressEditorId != null || selectedBillingAddressId != null
    BackHandler(enabled = isCompactWidth && hasAdaptiveDetail) { when { isAddingBankCardInline || inlineBankCardEditorId != null -> onInlineBankCardEditorBack(); isAddingDocumentInline || inlineDocumentEditorId != null -> onInlineDocumentEditorBack(); isAddingBillingAddressInline || inlineBillingAddressEditorId != null -> onInlineBillingAddressEditorBack(); selectedBankCardId != null -> onClearSelectedBankCard(); selectedDocumentId != null -> onClearSelectedDocument(); else -> onClearSelectedBillingAddress() } }
    AdaptiveListDetailLayout(
        compact = isCompactWidth,
        listWidth = wideListPaneWidth,
        hasDetail = hasAdaptiveDetail,
        list = { Column { listPaneContent() } },
        detail = { CardWalletDetailPaneContent(
                    bankCardViewModel = bankCardViewModel,
                    documentViewModel = documentViewModel,
                    billingAddressViewModel = billingAddressViewModel,
                    isAddingBankCardInline = isAddingBankCardInline,
                    inlineBankCardEditorId = inlineBankCardEditorId,
                    onInlineBankCardEditorBack = onInlineBankCardEditorBack,
                    isAddingDocumentInline = isAddingDocumentInline,
                    inlineDocumentEditorId = inlineDocumentEditorId,
                    onInlineDocumentEditorBack = onInlineDocumentEditorBack,
                    isAddingBillingAddressInline = isAddingBillingAddressInline,
                    inlineBillingAddressEditorId = inlineBillingAddressEditorId,
                    onInlineBillingAddressEditorBack = onInlineBillingAddressEditorBack,
                    selectedBankCardId = selectedBankCardId,
                    onClearSelectedBankCard = onClearSelectedBankCard,
                    onEditBankCard = onEditBankCard,
                    selectedDocumentId = selectedDocumentId,
                    onClearSelectedDocument = onClearSelectedDocument,
                    onEditDocument = onEditDocument,
                    selectedBillingAddressId = selectedBillingAddressId,
                    onClearSelectedBillingAddress = onClearSelectedBillingAddress,
                    onEditBillingAddress = onEditBillingAddress,
                    initialCategoryId = initialCategoryId,
                    initialStorageExplicit = initialStorageExplicit,
                    initialKeePassDatabaseId = initialKeePassDatabaseId,
                    initialKeePassGroupPath = initialKeePassGroupPath,
                    initialMdbxDatabaseId = initialMdbxDatabaseId,
                    initialMdbxFolderId = initialMdbxFolderId,
                    initialBitwardenVaultId = initialBitwardenVaultId,
                    initialBitwardenFolderId = initialBitwardenFolderId
                ) }
    )
}
