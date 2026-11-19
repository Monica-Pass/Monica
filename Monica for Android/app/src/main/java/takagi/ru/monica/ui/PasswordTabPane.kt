package takagi.ru.monica.ui

import takagi.ru.monica.ui.common.layout.AdaptiveListDetailLayout
import androidx.compose.foundation.layout.Column
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.data.UnmatchedIconHandlingStrategy
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.model.TimelineEvent
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.password.PasswordListAggregateConfig
import takagi.ru.monica.ui.password.StackCardMode
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.screens.HistoryTab
import takagi.ru.monica.ui.screens.PasswordDetailScreen
import takagi.ru.monica.ui.screens.TimelineScreen
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.BillingAddressViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasskeyViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel
import takagi.ru.monica.viewmodel.TimelineViewModel
import takagi.ru.monica.viewmodel.MdbxViewModel

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun PasswordTabPane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    passwordViewModel: PasswordViewModel,
    settingsViewModel: SettingsViewModel,
    securityManager: SecurityManager,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<LocalMdbxDatabase> = emptyList(),
    bitwardenVaults: List<BitwardenVault>,
    localKeePassViewModel: takagi.ru.monica.viewmodel.LocalKeePassViewModel,
    mdbxViewModel: MdbxViewModel? = null,
    timelineViewModel: TimelineViewModel?,
    groupMode: String,
    stackCardMode: StackCardMode,
    visibleContentTypes: List<PasswordPageContentType>,
    selectedContentTypes: Set<PasswordPageContentType>,
    onToggleContentType: (PasswordPageContentType) -> Unit,
    onCreateApiToken: (takagi.ru.monica.data.model.StorageTarget.Mdbx?) -> Unit = {},
    onOpenApiTokens: (Long?, String?) -> Unit = { _, _ -> },
    onPasswordOpen: (Long) -> Unit,
    onNavigateToAddTotp: (Long?) -> Unit,
    onNavigateToBankCardDetail: (Long) -> Unit,
    onNavigateToDocumentDetail: (Long) -> Unit,
    onNavigateToBillingAddressDetail: (Long) -> Unit,
    onNavigateToAddNote: (Long?) -> Unit,
    onNavigateToNoteDetail: (Long) -> Unit,
    onNavigateToPasskeyDetail: (Long) -> Unit,
    onOpenMdbxCommitHistory: (Long) -> Unit,
    onOpenHistoryPage: () -> Unit,
    onOpenTrashPage: () -> Unit,
    onOpenCommonAccountTemplatesPage: () -> Unit,
    onScanFidoQr: () -> Unit,
    onCloseHistoryPage: () -> Unit,
    passwordHistoryPageMode: PasswordHistoryPageMode,
    passwordHistoryInitialTrashScopeKey: String?,
    onTimelineLogSelected: (TimelineEvent.StandardLog) -> Unit,
    onSelectionModeChange: (
        Boolean,
        Int,
        () -> Unit,
        () -> Unit,
        (() -> Unit)?,
        (() -> Unit)?,
        (() -> Unit)?,
        () -> Unit
    ) -> Unit,
    onBackToTopVisibilityChange: (Boolean) -> Unit,
    scrollToTopRequestKey: Int,
    isAddingPasswordInline: Boolean,
    inlinePasswordEditorId: Long?,
    selectedPasswordId: Long?,
    passwordNewItemDefaults: NewItemStorageDefaults,
    onInlinePasswordEditorBack: () -> Unit,
    onNavigateToAddWifi: (Long?) -> Unit = {},
    onNavigateToAddSshKey: (Long?) -> Unit = {},
    pendingPasswordAuthenticatorQrResult: String? = null,
    onConsumePendingPasswordAuthenticatorQrResult: () -> Unit = {},
    onScanPasswordAuthenticatorQrCode: () -> Unit = {},
    totpViewModel: takagi.ru.monica.viewmodel.TotpViewModel,
    bankCardViewModel: BankCardViewModel,
    noteViewModel: NoteViewModel,
    documentViewModel: DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    passkeyViewModel: PasskeyViewModel,
    biometricEnabled: Boolean,
    iconCardsEnabled: Boolean,
    unmatchedIconHandlingStrategy: UnmatchedIconHandlingStrategy,
    onClearSelectedPassword: () -> Unit,
    onEditPassword: (Long) -> Unit,
    showStandaloneSettingsEntry: Boolean,
    onOpenStandaloneSettings: () -> Unit,
    onTrashSelectionModeChange: (Boolean) -> Unit = {}
) {
    val appSettings by settingsViewModel.settings.collectAsState()

    val listPaneContent: @Composable ColumnScope.() -> Unit = {
        PasswordListContent(
            onOpenApiTokens = onOpenApiTokens,
            viewModel = passwordViewModel,
            settingsViewModel = settingsViewModel,
            securityManager = securityManager,
            keepassDatabases = keepassDatabases,
            mdbxDatabases = mdbxDatabases,
            bitwardenVaults = bitwardenVaults,
            localKeePassViewModel = localKeePassViewModel,
            mdbxViewModel = mdbxViewModel,
            groupMode = groupMode,
            stackCardMode = stackCardMode,
            onRenameCategory = { category ->
                passwordViewModel.updateCategory(category)
            },
            onDeleteCategory = { category ->
                passwordViewModel.deleteCategory(category)
            },
            onPasswordClick = { password ->
                onPasswordOpen(password.id)
            },
            onSelectionModeChange = onSelectionModeChange,
            onBackToTopVisibilityChange = onBackToTopVisibilityChange,
            scrollToTopRequestKey = scrollToTopRequestKey,
            onOpenMdbxCommitHistory = onOpenMdbxCommitHistory,
            onOpenHistory = onOpenHistoryPage,
            onOpenTrash = onOpenTrashPage,
            onOpenCommonAccountTemplates = onOpenCommonAccountTemplatesPage,
            onScanFidoQr = onScanFidoQr,
            showStandaloneSettingsEntry = showStandaloneSettingsEntry,
            onOpenStandaloneSettings = onOpenStandaloneSettings,
            aggregateConfig = PasswordListAggregateConfig(
                visibleContentTypes = visibleContentTypes,
                selectedContentTypes = selectedContentTypes,
                onToggleContentType = onToggleContentType,
                totpViewModel = totpViewModel,
                bankCardViewModel = bankCardViewModel,
                documentViewModel = documentViewModel,
                billingAddressViewModel = billingAddressViewModel,
                noteViewModel = noteViewModel,
                passkeyViewModel = passkeyViewModel,
                onOpenTotp = { onNavigateToAddTotp(it) },
                onOpenBankCard = onNavigateToBankCardDetail,
                onOpenDocument = onNavigateToDocumentDetail,
                onOpenBillingAddress = onNavigateToBillingAddressDetail,
                onOpenNote = onNavigateToAddNote,
                onOpenPasskey = onNavigateToPasskeyDetail
            )
        )
    }

    if (passwordHistoryPageMode.isVisible) {
        TimelineScreen(
            onMdbxHistory = onOpenMdbxCommitHistory,
            viewModel = timelineViewModel,
            onLogSelected = onTimelineLogSelected,
            splitPaneMode = false,
            initialTab = passwordHistoryPageMode.tab ?: HistoryTab.TIMELINE,
            initialTrashScopeKey = passwordHistoryInitialTrashScopeKey,
            enableTabSwitch = false,
            showBackButton = true,
            onNavigateBack = onCloseHistoryPage,
            showReturnFab = false,
            onTrashSelectionModeChange = onTrashSelectionModeChange
        )
        return
    }

    val hasAdaptiveDetail = isAddingPasswordInline || inlinePasswordEditorId != null || selectedPasswordId != null
    BackHandler(enabled = isCompactWidth && hasAdaptiveDetail) { if (isAddingPasswordInline || inlinePasswordEditorId != null) onInlinePasswordEditorBack() else onClearSelectedPassword() }
    AdaptiveListDetailLayout(
        compact = isCompactWidth,
        listWidth = wideListPaneWidth,
        hasDetail = hasAdaptiveDetail,
        list = { Column { listPaneContent() } },
        detail = { PasswordDetailPaneContent(
                    onCreateApiToken = onCreateApiToken,
                    isAddingPasswordInline = isAddingPasswordInline,
                    inlinePasswordEditorId = inlinePasswordEditorId,
                    selectedPasswordId = selectedPasswordId,
                    passwordViewModel = passwordViewModel,
                    totpViewModel = totpViewModel,
                    bankCardViewModel = bankCardViewModel,
                    noteViewModel = noteViewModel,
                    localKeePassViewModel = localKeePassViewModel,
                    mdbxViewModel = mdbxViewModel,
                    passwordNewItemDefaults = passwordNewItemDefaults,
                    mdbxDatabases = mdbxDatabases,
                    pendingPasswordAuthenticatorQrResult = pendingPasswordAuthenticatorQrResult,
                    onConsumePendingPasswordAuthenticatorQrResult = onConsumePendingPasswordAuthenticatorQrResult,
                    onScanPasswordAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode,
                    onInlinePasswordEditorBack = onInlinePasswordEditorBack,
                    onNavigateToAddWifi = onNavigateToAddWifi,
                    onNavigateToAddSshKey = onNavigateToAddSshKey,
                    passkeyViewModel = passkeyViewModel,
                    biometricEnabled = biometricEnabled,
                    iconCardsEnabled = iconCardsEnabled,
                    unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
                    onClearSelectedPassword = onClearSelectedPassword,
                    onNavigateToNoteDetail = onNavigateToNoteDetail,
                    onEditPassword = onEditPassword
                ) }
    )
}

@Composable
internal fun PasswordDetailPaneContent(
    onCreateApiToken: (takagi.ru.monica.data.model.StorageTarget.Mdbx?) -> Unit = {},
    isAddingPasswordInline: Boolean,
    inlinePasswordEditorId: Long?,
    selectedPasswordId: Long?,
    passwordViewModel: PasswordViewModel,
    totpViewModel: takagi.ru.monica.viewmodel.TotpViewModel,
    bankCardViewModel: BankCardViewModel,
    noteViewModel: NoteViewModel,
    localKeePassViewModel: takagi.ru.monica.viewmodel.LocalKeePassViewModel,
    mdbxViewModel: MdbxViewModel? = null,
    passwordNewItemDefaults: NewItemStorageDefaults,
    mdbxDatabases: List<LocalMdbxDatabase>,
    pendingPasswordAuthenticatorQrResult: String?,
    onConsumePendingPasswordAuthenticatorQrResult: () -> Unit,
    onScanPasswordAuthenticatorQrCode: () -> Unit,
    onInlinePasswordEditorBack: () -> Unit,
    onNavigateToAddWifi: (Long?) -> Unit,
    onNavigateToAddSshKey: (Long?) -> Unit,
    passkeyViewModel: PasskeyViewModel,
    biometricEnabled: Boolean,
    iconCardsEnabled: Boolean,
    unmatchedIconHandlingStrategy: UnmatchedIconHandlingStrategy,
    onClearSelectedPassword: () -> Unit,
    onNavigateToNoteDetail: (Long) -> Unit,
    onEditPassword: (Long) -> Unit
) {
    val detailContent = remember(
        isAddingPasswordInline,
        inlinePasswordEditorId,
        selectedPasswordId
    ) {
        when {
            isAddingPasswordInline -> PasswordDetailContent.Add
            inlinePasswordEditorId != null -> PasswordDetailContent.Edit(inlinePasswordEditorId)
            selectedPasswordId != null -> PasswordDetailContent.Detail(selectedPasswordId)
            else -> PasswordDetailContent.Empty
        }
    }
    when (val content = detailContent) {
        PasswordDetailContent.Empty -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.select_password_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        PasswordDetailContent.Add,
        is PasswordDetailContent.Edit -> {
            val editorId = (content as? PasswordDetailContent.Edit)?.passwordId
            val templateNavigation = takagi.ru.monica.ui.components.LocalTemplateNavigation.current
            key(content) {
                CompositionLocalProvider(takagi.ru.monica.ui.components.LocalTemplateNavigation provides
                    templateNavigation?.let { navigate -> { type, targets ->
                        onInlinePasswordEditorBack()
                        navigate(type, targets)
                    } }) {
                AddEditPasswordScreen(
                    onSwitchToApiToken = { target -> onInlinePasswordEditorBack(); onCreateApiToken(target) },
                    viewModel = passwordViewModel,
                    totpViewModel = totpViewModel,
                    bankCardViewModel = bankCardViewModel,
                    noteViewModel = noteViewModel,
                    localKeePassViewModel = localKeePassViewModel,
                    localMdbxViewModel = mdbxViewModel,
                    passwordId = editorId,
                    initialCategoryId = passwordNewItemDefaults.categoryId,
                    initialStorageExplicit = passwordNewItemDefaults.explicit,
                    initialKeePassDatabaseId = passwordNewItemDefaults.keepassDatabaseId,
                    initialKeePassGroupPath = passwordNewItemDefaults.keepassGroupPath,
                    initialBitwardenVaultId = passwordNewItemDefaults.bitwardenVaultId,
                    initialBitwardenFolderId = passwordNewItemDefaults.bitwardenFolderId,
                    initialMdbxDatabaseId = passwordNewItemDefaults.mdbxDatabaseId,
                    initialMdbxFolderId = passwordNewItemDefaults.mdbxFolderId,
                    mdbxDatabasesFallback = mdbxDatabases,
                    pendingQrResult = pendingPasswordAuthenticatorQrResult,
                    onConsumePendingQrResult = onConsumePendingPasswordAuthenticatorQrResult,
                    onScanAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode,
                    onSwitchToWifi = { targetId ->
                        onInlinePasswordEditorBack()
                        onNavigateToAddWifi(targetId)
                    },
                    onSwitchToSshKey = { targetId ->
                        onInlinePasswordEditorBack()
                        onNavigateToAddSshKey(targetId)
                    },
                    onNavigateBack = onInlinePasswordEditorBack
                )
                }
            }
        }
        is PasswordDetailContent.Detail -> {
            CompositionLocalProvider(
                LocalSharedTransitionScope provides null,
                LocalAnimatedVisibilityScope provides null
            ) {
                PasswordDetailScreen(
                    viewModel = passwordViewModel,
                    passkeyViewModel = passkeyViewModel,
                    noteViewModel = noteViewModel,
                    passwordId = content.passwordId,
                    biometricEnabled = biometricEnabled,
                    iconCardsEnabled = iconCardsEnabled,
                    unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
                    enableSharedBounds = false,
                    onNavigateBack = onClearSelectedPassword,
                    onOpenBoundNote = onNavigateToNoteDetail,
                    onEditPassword = onEditPassword,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private sealed interface PasswordDetailContent {
    val isEmpty: Boolean
        get() = this == Empty
    val isEditor: Boolean
        get() = this == Add || this is Edit

    data object Empty : PasswordDetailContent
    data object Add : PasswordDetailContent
    data class Edit(val passwordId: Long) : PasswordDetailContent
    data class Detail(val passwordId: Long) : PasswordDetailContent
}
