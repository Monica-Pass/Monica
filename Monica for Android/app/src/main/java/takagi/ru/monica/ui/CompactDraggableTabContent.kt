package takagi.ru.monica.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.main.navigation.BottomNavItem
import takagi.ru.monica.ui.password.resolvePasswordPageVisibleTypes
import takagi.ru.monica.ui.password.sanitizeSelectedPasswordPageTypes
import takagi.ru.monica.ui.screens.CardWalletTab
import takagi.ru.monica.ui.screens.GeneratorScreen
import takagi.ru.monica.ui.screens.NoteListScreen
import takagi.ru.monica.ui.screens.PasskeyListScreen
import takagi.ru.monica.ui.screens.SendScreen
import takagi.ru.monica.steam.ui.SteamScreen
import takagi.ru.monica.ui.vaultv2.VaultV2Pane
import takagi.ru.monica.ui.vaultv2.VaultV2PaneState
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.BillingAddressViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.GeneratorViewModel
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasskeyViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel
import takagi.ru.monica.viewmodel.TimelineViewModel
import takagi.ru.monica.viewmodel.MdbxViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun CompactDraggableTabContent(
    paddingValues: PaddingValues,
    currentTab: BottomNavItem,
    showStandaloneSettingsEntry: Boolean,
    onOpenStandaloneSettings: () -> Unit,
    passwordViewModel: PasswordViewModel,
    settingsViewModel: SettingsViewModel,
    securityManager: SecurityManager,
    keepassDatabases: List<takagi.ru.monica.data.LocalKeePassDatabase>,
    mdbxDatabases: List<takagi.ru.monica.data.LocalMdbxDatabase> = emptyList(),
    bitwardenVaults: List<takagi.ru.monica.data.bitwarden.BitwardenVault>,
    localKeePassViewModel: takagi.ru.monica.viewmodel.LocalKeePassViewModel,
    mdbxViewModel: MdbxViewModel? = null,
    passwordGroupMode: String,
    stackCardMode: takagi.ru.monica.ui.password.StackCardMode,
    onPasswordOpen: (Long) -> Unit,
    onBankCardOpen: (Long) -> Unit,
    onDocumentOpen: (Long) -> Unit,
    onNoteOpen: (Long) -> Unit,
    onPasskeyOpen: (PasskeyEntry) -> Unit,
    onNavigateToAddTotp: (Long?) -> Unit,
    onNavigateToBankCardDetail: (Long) -> Unit,
    onNavigateToDocumentDetail: (Long) -> Unit,
    onNavigateToBillingAddressDetail: (Long) -> Unit,
    onNavigateToPasskeyDetail: (Long) -> Unit,
    onNavigateToMdbxCommitHistory: (Long) -> Unit,
    onPasswordSelectionModeChange: (
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
    passwordScrollToTopRequestKey: Int,
    totpViewModel: takagi.ru.monica.viewmodel.TotpViewModel,
    onTotpOpen: (Long) -> Unit,
    onNavigateToQuickTotpScan: () -> Unit,
    pendingSteamQrResult: String? = null,
    pendingSteamQrAccountId: Long? = null,
    onConsumePendingSteamQrResult: () -> Unit = {},
    onScanSteamQrCode: (Long?) -> Unit = {},
    onNavigateToFidoQrScan: () -> Unit,
    onTotpSelectionModeChange: (
        Boolean,
        Int,
        () -> Unit,
        () -> Unit,
        () -> Unit,
        () -> Unit
    ) -> Unit,
    cardWalletSaveableStateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    bankCardViewModel: BankCardViewModel,
    documentViewModel: DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    cardWalletContentState: CardWalletContentState,
    generatorViewModel: GeneratorViewModel,
    generatorRefreshRequestKey: Int,
    onGeneratorRefreshRequestConsumed: () -> Unit,
    noteViewModel: NoteViewModel,
    onNavigateToAddNote: (Long?) -> Unit,
    onNavigateToSearchedNote: (Long, String) -> Unit,
    onNavigateToNoteDetail: (Long) -> Unit,
    onNoteSelectionModeChange: (Boolean) -> Unit,
    timelineViewModel: TimelineViewModel,
    passkeyViewModel: PasskeyViewModel,
    onNavigateToPasswordDetail: (Long) -> Unit,
    onNavigateToAuthenticator: () -> Unit,
    bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel,
    onSendBitwardenEvent: (takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent) -> Boolean,
    onNavigateToChangePassword: () -> Unit,
    onNavigateToSecurityQuestion: () -> Unit,
    onNavigateToMasterPasswordLocking: () -> Unit,
    onNavigateToSyncBackup: () -> Unit,
    onNavigateToAutofill: () -> Unit,
    onNavigateToPasskeySettings: () -> Unit,
    onNavigateToBottomNavSettings: () -> Unit,
    onNavigateToColorScheme: () -> Unit,
    onSecurityAnalysis: () -> Unit,
    onNavigateToDeveloperSettings: () -> Unit,
    onNavigateToPermissionManagement: () -> Unit,
    onNavigateToMonicaPlus: () -> Unit,
    onNavigateToExtensions: () -> Unit,
    onNavigateToCommonAccountTemplates: () -> Unit,
    onNavigateToPageCustomization: () -> Unit,
    onOpenVaultV2HistoryPage: () -> Unit,
    onOpenVaultV2TrashPage: () -> Unit,
    onOpenVaultV2ArchivePage: () -> Unit,
    onClearAllData: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    cardWalletSubTab: CardWalletTab,
    passwordHistoryPageMode: PasswordHistoryPageMode,
    passwordHistoryInitialTrashScopeKey: String?,
    onOpenHistoryPage: () -> Unit,
    onOpenTrashPage: () -> Unit,
    onCloseHistoryPage: () -> Unit,
    isPasswordSelectionMode: Boolean,
    selectedPasswordCount: Int,
    onExitPasswordSelection: () -> Unit,
    onSelectAllPasswords: () -> Unit,
    onFavoriteSelectedPasswords: (() -> Unit)?,
    onMoveToCategoryPasswords: (() -> Unit)?,
    onManualStackPasswords: (() -> Unit)?,
    onDeleteSelectedPasswords: () -> Unit,
    isTotpSelectionMode: Boolean,
    selectedTotpCount: Int,
    onExitTotpSelection: () -> Unit,
    onSelectAllTotp: () -> Unit,
    onMoveToCategoryTotp: () -> Unit,
    onDeleteSelectedTotp: () -> Unit,
    isBankCardSelectionMode: Boolean,
    selectedBankCardCount: Int,
    onExitBankCardSelection: () -> Unit,
    onSelectAllBankCards: () -> Unit,
    onFavoriteBankCards: () -> Unit,
    onMoveToCategoryBankCards: () -> Unit,
    onDeleteSelectedBankCards: () -> Unit,
    isDocumentSelectionMode: Boolean,
    selectedDocumentCount: Int,
    onExitDocumentSelection: () -> Unit,
    onSelectAllDocuments: () -> Unit,
    onMoveToCategoryDocuments: () -> Unit,
    onDeleteSelectedDocuments: () -> Unit,
    vaultV2PaneState: VaultV2PaneState
) {
    val appSettings by settingsViewModel.settings.collectAsState()
    val currentFilter by passwordViewModel.categoryFilter.collectAsState()
    val passwordNewItemDefaults = remember(currentFilter) { defaultsFromPasswordFilter(currentFilter) }
    val passwordPageVisibleContentTypes = remember(
        appSettings.passwordPageAggregateEnabled,
        appSettings.passwordPageVisibleContentTypes
    ) {
        resolvePasswordPageVisibleTypes(
            aggregateEnabled = appSettings.passwordPageAggregateEnabled,
            configuredTypes = appSettings.passwordPageVisibleContentTypes
        )
    }
    var passwordPageSelectedContentTypes by rememberSaveable(
        stateSaver = passwordPageContentTypeSetSaver
    ) {
        mutableStateOf(emptySet())
    }
    LaunchedEffect(passwordPageVisibleContentTypes) {
        passwordPageSelectedContentTypes = sanitizeSelectedPasswordPageTypes(
            visibleTypes = passwordPageVisibleContentTypes,
            selectedTypes = passwordPageSelectedContentTypes
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        AuthenticatorPasskeyAnimatedContent(currentTab = currentTab) { displayedTab ->
        when (displayedTab) {
            BottomNavItem.VaultV2 -> {
                VaultV2Pane(
                    passwordViewModel = passwordViewModel,
                    totpViewModel = totpViewModel,
                    bankCardViewModel = bankCardViewModel,
                    documentViewModel = documentViewModel,
                    noteViewModel = noteViewModel,
                    passkeyViewModel = passkeyViewModel,
                    keepassDatabases = keepassDatabases,
                    mdbxDatabases = mdbxDatabases,
                    bitwardenVaults = bitwardenVaults,
                    localKeePassViewModel = localKeePassViewModel,
                    mdbxViewModel = mdbxViewModel,
                    settingsViewModel = settingsViewModel,
                    state = vaultV2PaneState,
                    onOpenPassword = onPasswordOpen,
                    onOpenTotp = onTotpOpen,
                    onOpenBankCard = onBankCardOpen,
                    onOpenDocument = onDocumentOpen,
                    onOpenNote = onNoteOpen,
                    onOpenPasskey = onNavigateToPasskeyDetail,
                    onOpenMdbxCommitHistory = onNavigateToMdbxCommitHistory,
                    onOpenHistory = onOpenVaultV2HistoryPage,
                    onOpenTrashPage = onOpenVaultV2TrashPage,
                    onOpenArchivePage = onOpenVaultV2ArchivePage,
                    onOpenCommonAccountTemplates = onNavigateToCommonAccountTemplates,
                    onScanFidoQr = onNavigateToFidoQrScan,
                    onOpenStandaloneSettings = onOpenStandaloneSettings,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    appSettings = appSettings,
                    securityManager = securityManager,
                    biometricEnabled = appSettings.biometricEnabled,
                    modifier = Modifier.fillMaxSize()
                )
            }
            BottomNavItem.Passwords -> {
                PasswordTabPane(
                    isCompactWidth = true,
                    wideListPaneWidth = 0.dp,
                    passwordViewModel = passwordViewModel,
                    settingsViewModel = settingsViewModel,
                    securityManager = securityManager,
                    keepassDatabases = keepassDatabases,
                    mdbxDatabases = mdbxDatabases,
                    bitwardenVaults = bitwardenVaults,
                    localKeePassViewModel = localKeePassViewModel,
                    mdbxViewModel = mdbxViewModel,
                    timelineViewModel = timelineViewModel,
                    groupMode = passwordGroupMode,
                    stackCardMode = stackCardMode,
                    visibleContentTypes = passwordPageVisibleContentTypes,
                    selectedContentTypes = passwordPageSelectedContentTypes,
                    onToggleContentType = { type ->
                        passwordPageSelectedContentTypes = togglePasswordPageContentType(
                            currentTypes = passwordPageSelectedContentTypes,
                            toggledType = type,
                            visibleTypes = passwordPageVisibleContentTypes
                        )
                    },
                    onPasswordOpen = onPasswordOpen,
                    onNavigateToAddTotp = onNavigateToAddTotp,
                    onNavigateToBankCardDetail = onNavigateToBankCardDetail,
                    onNavigateToDocumentDetail = onNavigateToDocumentDetail,
                    onNavigateToBillingAddressDetail = onNavigateToBillingAddressDetail,
                    onNavigateToAddNote = onNavigateToAddNote,
                    onNavigateToNoteDetail = onNavigateToNoteDetail,
                    onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                    onOpenMdbxCommitHistory = onNavigateToMdbxCommitHistory,
                    onOpenHistoryPage = onOpenHistoryPage,
                    onOpenTrashPage = onOpenTrashPage,
                    onOpenCommonAccountTemplatesPage = onNavigateToCommonAccountTemplates,
                    onScanFidoQr = onNavigateToFidoQrScan,
                    onCloseHistoryPage = onCloseHistoryPage,
                    passwordHistoryPageMode = passwordHistoryPageMode,
                    passwordHistoryInitialTrashScopeKey = passwordHistoryInitialTrashScopeKey,
                    onTimelineLogSelected = {},
                    onSelectionModeChange = onPasswordSelectionModeChange,
                    onBackToTopVisibilityChange = onBackToTopVisibilityChange,
                    scrollToTopRequestKey = passwordScrollToTopRequestKey,
                    isAddingPasswordInline = false,
                    inlinePasswordEditorId = null,
                    selectedPasswordId = null,
                    passwordNewItemDefaults = passwordNewItemDefaults,
                    onInlinePasswordEditorBack = {},
                    totpViewModel = totpViewModel,
                    bankCardViewModel = bankCardViewModel,
                    noteViewModel = noteViewModel,
                    documentViewModel = documentViewModel,
                    billingAddressViewModel = billingAddressViewModel,
                    passkeyViewModel = passkeyViewModel,
                    biometricEnabled = appSettings.biometricEnabled,
                    iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
                    unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                    onClearSelectedPassword = {},
                    onEditPassword = {},
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            BottomNavItem.Authenticator -> {
                TotpListContent(
                    viewModel = totpViewModel,
                    passwordViewModel = passwordViewModel,
                    appSettings = appSettings,
                    onAuthenticatorLayoutModeChange = settingsViewModel::updateAuthenticatorLayoutMode,
                    onTotpClick = onTotpOpen,
                    onDeleteTotp = { totp ->
                        totpViewModel.deleteTotpItem(totp)
                    },
                    onQuickScanTotp = onNavigateToQuickTotpScan,
                    onSelectionModeChange = onTotpSelectionModeChange,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            BottomNavItem.CardWallet -> {
                CardWalletContent(
                    saveableStateHolder = cardWalletSaveableStateHolder,
                    bankCardViewModel = bankCardViewModel,
                    documentViewModel = documentViewModel,
                    billingAddressViewModel = billingAddressViewModel,
                    passwordViewModel = passwordViewModel,
                    bitwardenViewModel = bitwardenViewModel,
                    state = cardWalletContentState,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            BottomNavItem.Generator -> {
                GeneratorScreen(
                    onNavigateBack = {},
                    viewModel = generatorViewModel,
                    passwordViewModel = passwordViewModel,
                    externalRefreshRequestKey = generatorRefreshRequestKey,
                    onRefreshRequestConsumed = onGeneratorRefreshRequestConsumed,
                    useExternalRefreshFab = true,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            BottomNavItem.Notes -> {
                NoteListScreen(
                    viewModel = noteViewModel,
                    settingsViewModel = settingsViewModel,
                    onNavigateToAddNote = onNavigateToAddNote,
                    onNavigateToSearchedNote = onNavigateToSearchedNote,
                    securityManager = securityManager,
                    passwordViewModel = passwordViewModel,
                    onSelectionModeChange = onNoteSelectionModeChange,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            BottomNavItem.Passkey -> {
                PasskeyListScreen(
                    viewModel = passkeyViewModel,
                    passwordViewModel = passwordViewModel,
                    onNavigateToPasswordDetail = onNavigateToPasswordDetail,
                    onPasskeyClick = {},
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings,
                    onNavigateToAuthenticator = onNavigateToAuthenticator
                )
            }
            BottomNavItem.Send -> {
                SendScreen(
                    bitwardenViewModel = bitwardenViewModel,
                    onBitwardenEvent = onSendBitwardenEvent,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            BottomNavItem.Steam -> {
                SteamScreen(
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings,
                    pendingSteamQrResult = pendingSteamQrResult,
                    pendingSteamQrAccountId = pendingSteamQrAccountId,
                    onConsumePendingSteamQrResult = onConsumePendingSteamQrResult,
                    onScanSteamQrCode = onScanSteamQrCode,
                    modifier = Modifier.fillMaxSize()
                )
            }
            BottomNavItem.Settings -> {
                SettingsTabContent(
                    isCompactWidth = true,
                    wideListPaneWidth = 0.dp,
                    viewModel = settingsViewModel,
                    onResetPassword = onNavigateToChangePassword,
                    onSecurityQuestions = onNavigateToSecurityQuestion,
                    onNavigateToMasterPasswordLocking = onNavigateToMasterPasswordLocking,
                    onNavigateToSyncBackup = onNavigateToSyncBackup,
                    onNavigateToAutofill = onNavigateToAutofill,
                    onNavigateToPasskeySettings = onNavigateToPasskeySettings,
                    onNavigateToBottomNavSettings = onNavigateToBottomNavSettings,
                    onNavigateToColorScheme = onNavigateToColorScheme,
                    onSecurityAnalysis = onSecurityAnalysis,
                    onNavigateToDeveloperSettings = onNavigateToDeveloperSettings,
                    onNavigateToPermissionManagement = onNavigateToPermissionManagement,
                    onNavigateToMonicaPlus = onNavigateToMonicaPlus,
                    onNavigateToExtensions = onNavigateToExtensions,
                    onNavigateToPageCustomization = onNavigateToPageCustomization,
                    onClearAllData = onClearAllData
                )
            }
        }
        }

        MainScreenSelectionBars(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            currentTab = currentTab,
            cardWalletSubTab = cardWalletSubTab,
            isPasswordSelectionMode = isPasswordSelectionMode,
            selectedPasswordCount = selectedPasswordCount,
            onExitPasswordSelection = onExitPasswordSelection,
            onSelectAllPasswords = onSelectAllPasswords,
            onFavoriteSelectedPasswords = onFavoriteSelectedPasswords,
            onMoveToCategoryPasswords = onMoveToCategoryPasswords,
            onManualStackPasswords = onManualStackPasswords,
            onDeleteSelectedPasswords = onDeleteSelectedPasswords,
            isTotpSelectionMode = isTotpSelectionMode,
            selectedTotpCount = selectedTotpCount,
            onExitTotpSelection = onExitTotpSelection,
            onSelectAllTotp = onSelectAllTotp,
            onMoveToCategoryTotp = onMoveToCategoryTotp,
            onDeleteSelectedTotp = onDeleteSelectedTotp,
            isBankCardSelectionMode = isBankCardSelectionMode,
            selectedBankCardCount = selectedBankCardCount,
            onExitBankCardSelection = onExitBankCardSelection,
            onSelectAllBankCards = onSelectAllBankCards,
            onFavoriteBankCards = onFavoriteBankCards,
            onMoveToCategoryBankCards = onMoveToCategoryBankCards,
            onDeleteSelectedBankCards = onDeleteSelectedBankCards,
            isDocumentSelectionMode = isDocumentSelectionMode,
            selectedDocumentCount = selectedDocumentCount,
            onExitDocumentSelection = onExitDocumentSelection,
            onSelectAllDocuments = onSelectAllDocuments,
            onMoveToCategoryDocuments = onMoveToCategoryDocuments,
            onDeleteSelectedDocuments = onDeleteSelectedDocuments
        )
    }
}
