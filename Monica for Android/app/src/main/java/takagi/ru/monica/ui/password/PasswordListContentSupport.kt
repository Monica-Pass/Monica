package takagi.ru.monica.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.data.PasswordListQuickFilterItem
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.PasskeyBindingCodec
import takagi.ru.monica.data.model.isSshKeyEntry
import takagi.ru.monica.data.model.isBarcodeEntry
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.ui.password.PasswordAggregateCardStyle
import takagi.ru.monica.ui.password.PasswordAggregateListItemUi
import takagi.ru.monica.ui.password.PasswordAggregateRetainedState
import takagi.ru.monica.ui.password.PasswordAggregateSnapshotKey
import takagi.ru.monica.ui.password.PasswordAggregateWalletItemType
import takagi.ru.monica.ui.password.PasswordListAggregateConfig
import takagi.ru.monica.ui.password.StackCardMode
import takagi.ru.monica.ui.password.appendAggregateContentQuickFilterItems
import takagi.ru.monica.ui.password.buildPasswordAggregateItems
import takagi.ru.monica.ui.password.filterPasswordAggregateItemsByContentTypes
import takagi.ru.monica.ui.password.getGroupKeyForMode
import takagi.ru.monica.ui.password.getPasswordInfoKey
import takagi.ru.monica.ui.password.passwordSelectionKey
import takagi.ru.monica.ui.password.resolveNonEmptyAggregateContentTypes
import takagi.ru.monica.ui.password.resolvePasswordPageDisplayedTypes
import takagi.ru.monica.ui.password.resolvePasswordPageQuickFilterTypes
import takagi.ru.monica.ui.password.toPasswordPageContentTypeOrNull
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.BillingAddressViewModel
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasskeyViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.TotpViewModel
import takagi.ru.monica.utils.KeePassGroupInfo

private const val FAST_SCROLL_LOG_TAG = "PasswordFastScroll"
private const val PASSWORD_SCROLL_LOG_TAG = "PasswordScrollDebug"
private const val MANUAL_STACK_GROUP_KEY_PREFIX = "manual_stack:"
private const val NO_STACK_GROUP_KEY_PREFIX = "no_stack:"

internal data class PasswordListScrollSnapshot(
    val allowPersistence: Boolean,
    val pendingRestore: Boolean,
    val totalItems: Int,
    val index: Int,
    val offset: Int,
    val anchorKey: String?,
    val isScrollInProgress: Boolean
)

internal data class PasswordListScrollPersistenceKey(
    val allowPersistence: Boolean,
    val pendingRestore: Boolean,
    val totalItems: Int,
    val index: Int,
    val offsetWhenIdle: Int?,
    val anchorKey: String?,
    val isScrollInProgress: Boolean
)

internal fun PasswordListScrollSnapshot.persistenceKey(): PasswordListScrollPersistenceKey {
    return PasswordListScrollPersistenceKey(
        allowPersistence = allowPersistence,
        pendingRestore = pendingRestore,
        totalItems = totalItems,
        index = index,
        offsetWhenIdle = offset.takeUnless { isScrollInProgress },
        anchorKey = anchorKey,
        isScrollInProgress = isScrollInProgress
    )
}

internal data class PasswordListAggregateUiState(
    val visibleContentTypes: List<PasswordPageContentType>,
    val selectedContentTypes: Set<PasswordPageContentType>,
    val contentTypeFilterTypes: Set<PasswordPageContentType>,
    val quickFilterTypes: List<PasswordPageContentType>,
    val displayedContentTypes: Set<PasswordPageContentType>,
    val hasActiveContentTypeFilter: Boolean,
    val cardStyle: PasswordAggregateCardStyle,
    val visibleItems: List<PasswordAggregateListItemUi>,
    val bankCards: List<SecureItem>,
    val documents: List<SecureItem>,
    val billingAddresses: List<SecureItem>,
    val totpItems: List<SecureItem>,
    val notes: List<SecureItem>,
    val passkeys: List<PasskeyEntry>,
    val totpViewModel: TotpViewModel?,
    val bankCardViewModel: BankCardViewModel?,
    val documentViewModel: DocumentViewModel?,
    val billingAddressViewModel: BillingAddressViewModel?,
    val noteViewModel: NoteViewModel?,
    val passkeyViewModel: PasskeyViewModel?
)

internal data class PasswordGroupingConfig(
    val isLocalOnlyView: Boolean,
    val effectiveStackCardMode: StackCardMode,
    val effectiveGroupMode: String,
    val websiteStackMatchMode: String,
    val effectiveNoStackEntryIds: Set<Long>,
    val effectiveManualStackGroupByEntryId: Map<Long, String>,
    val untitledLabel: String
)

internal data class FavoriteSelectionToggleRequest(
    val context: Context,
    val viewModel: PasswordViewModel,
    val selectedPasswords: Set<Long>,
    val passwordEntries: List<PasswordEntry>,
    val selectedSupplementaryItems: List<PasswordAggregateListItemUi>,
    val aggregateUiState: PasswordListAggregateUiState,
    val nativeEntries: List<takagi.ru.monica.data.NativeApiTokenSummary> = emptyList(),
    val nativeViewModel: takagi.ru.monica.viewmodel.MdbxViewModel? = null,
)

internal data class PasswordListInitialRenderState(
    val isPasswordListDataLoaded: Boolean,
    val isHeaderDataLoaded: Boolean,
    val isPasswordPageListModelReady: Boolean,
    val shouldGateInitialContent: Boolean
)

internal fun resolvePasswordGroupsForRender(
    groupedPasswords: Map<String, List<PasswordEntry>>,
    hasGroupedPasswordsReadyForCurrentInputs: Boolean,
): Map<String, List<PasswordEntry>> =
    if (hasGroupedPasswordsReadyForCurrentInputs || groupedPasswords.isNotEmpty()) {
        groupedPasswords
    } else {
        emptyMap()
    }

internal data class PasswordListQuickFolderUiState(
    val nodes: List<PasswordQuickFolderNode>,
    val nodeByPath: Map<String, PasswordQuickFolderNode>,
    val currentPath: String?,
    val rootFilter: CategoryFilter,
    val systemBackTarget: CategoryFilter?,
    val shortcuts: List<PasswordQuickFolderShortcut>,
    val categoryMenuShortcuts: List<PasswordQuickFolderShortcut>,
    val breadcrumbs: List<PasswordQuickFolderBreadcrumb>
)

@Composable
internal fun <T> rememberAsyncComputed(
    vararg keys: Any?,
    initialValue: T,
    compute: suspend () -> T
): T {
    val state = remember { mutableStateOf(initialValue) }
    val latestCompute by rememberUpdatedState(compute)

    LaunchedEffect(*keys) {
        state.value = withContext(Dispatchers.Default) {
            latestCompute()
        }
    }

    return state.value
}

@Composable
internal fun rememberPasswordListQuickFolderUiState(
    context: Context,
    appSettings: AppSettings,
    currentFilter: CategoryFilter,
    categories: List<Category>,
    searchQuery: String,
    passwordEntries: List<PasswordEntry>,
    allPasswords: List<PasswordEntry>,
    keepassDatabases: List<LocalKeePassDatabase>,
    keepassGroupsForSelectedDb: List<KeePassGroupInfo>,
    bitwardenVaults: List<BitwardenVault>,
    selectedBitwardenFolders: List<BitwardenFolder>,
    mdbxDatabases: List<LocalMdbxDatabase>,
    selectedMdbxFolders: List<MdbxStoredFolderEntry>,
    quickFolderRootKey: String,
    quickFoldersEnabledForCurrentFilter: Boolean,
    quickFolderPathBannerEnabledForCurrentFilter: Boolean
): PasswordListQuickFolderUiState {
    val quickFolderStyle = appSettings.passwordListQuickFolderStyle
    val quickFolderNodes = remember(categories) {
        buildPasswordQuickFolderNodes(categories)
    }
    val quickFolderNodeByPath = remember(quickFolderNodes) {
        quickFolderNodes.associateBy { it.path }
    }
    val quickFolderCurrentPath = remember(currentFilter, quickFolderNodes) {
        when (val filter = currentFilter) {
            is CategoryFilter.Custom -> quickFolderNodes
                .firstOrNull { it.category.id == filter.categoryId }
                ?.path

            else -> null
        }
    }
    val quickFolderRootFilter = remember(quickFolderRootKey) {
        quickFolderRootKey.toQuickFolderRootFilter()
    }
    val quickFolderSystemBackTarget =
        if (!appSettings.passwordListSystemBackToParentFolderEnabled) {
            null
        } else {
            when (val filter = currentFilter) {
                is CategoryFilter.Custom -> {
                    val currentPath = quickFolderCurrentPath
                    if (currentPath == null) {
                        null
                    } else {
                        val parentPath = passwordQuickFolderParentPath(currentPath)
                        if (parentPath == null) {
                            quickFolderRootFilter
                        } else {
                            quickFolderNodeByPath[parentPath]?.category?.let { parentNode ->
                                CategoryFilter.Custom(parentNode.id)
                            } ?: quickFolderRootFilter
                        }
                    }
                }

                is CategoryFilter.KeePassGroupFilter -> {
                    val currentPath = filter.groupPath.trim('/').trim()
                    if (currentPath.isBlank()) {
                        null
                    } else {
                        val parentPath = currentPath.substringBeforeLast('/', missingDelimiterValue = "")
                        if (parentPath.isBlank()) {
                            CategoryFilter.KeePassDatabase(filter.databaseId)
                        } else {
                            CategoryFilter.KeePassGroupFilter(filter.databaseId, parentPath)
                        }
                    }
                }

                is CategoryFilter.BitwardenFolderFilter -> {
                    CategoryFilter.BitwardenVault(filter.vaultId)
                }

                else -> null
            }
        }
    val quickFolderSourceEntries = remember(searchQuery, passwordEntries, allPasswords) {
        if (searchQuery.isNotBlank()) {
            passwordEntries
        } else {
            allPasswords
        }
    }
    val baseQuickFolderPasswordCountByCategoryId = rememberAsyncComputed(
        quickFolderSourceEntries,
        categories,
        initialValue = emptyMap()
    ) {
        buildLocalQuickFolderPasswordCountByCategoryId(
            entries = quickFolderSourceEntries,
            categories = categories
        )
    }
    val quickFolderPasswordCountByCategoryId = remember(
        baseQuickFolderPasswordCountByCategoryId,
        quickFoldersEnabledForCurrentFilter,
        currentFilter
    ) {
        if (!quickFoldersEnabledForCurrentFilter || !currentFilter.supportsQuickFolders()) {
            emptyMap()
        } else {
            baseQuickFolderPasswordCountByCategoryId
        }
    }
    val categoryMenuQuickFolderPasswordCountByCategoryId = remember(
        baseQuickFolderPasswordCountByCategoryId,
        currentFilter
    ) {
        if (!currentFilter.supportsQuickFolders()) {
            emptyMap()
        } else {
            baseQuickFolderPasswordCountByCategoryId
        }
    }
    val quickFolderShortcuts = rememberAsyncComputed(
        appSettings.passwordListQuickFoldersEnabled,
        quickFolderStyle,
        currentFilter,
        quickFolderCurrentPath,
        quickFolderNodes,
        quickFolderNodeByPath,
        quickFolderRootFilter,
        quickFolderPasswordCountByCategoryId,
        allPasswords,
        passwordEntries,
        searchQuery,
        keepassDatabases,
        keepassGroupsForSelectedDb,
        bitwardenVaults,
        selectedBitwardenFolders,
        selectedMdbxFolders,
        categories,
        initialValue = emptyList()
    ) {
        buildQuickFolderShortcuts(
            context = context,
            quickFoldersEnabledForCurrentFilter = quickFoldersEnabledForCurrentFilter,
            includeBackNavigation = false,
            currentFilter = currentFilter,
            quickFolderStyle = quickFolderStyle,
            quickFolderCurrentPath = quickFolderCurrentPath,
            quickFolderNodes = quickFolderNodes,
            quickFolderNodeByPath = quickFolderNodeByPath,
            quickFolderRootFilter = quickFolderRootFilter,
            quickFolderPasswordCountByCategoryId = quickFolderPasswordCountByCategoryId,
            allPasswords = allPasswords,
            searchScopedPasswords = passwordEntries,
            isSearchActive = searchQuery.isNotBlank(),
            keepassDatabases = keepassDatabases,
            keepassGroupsForSelectedDb = keepassGroupsForSelectedDb,
            bitwardenVaults = bitwardenVaults,
            selectedBitwardenFolders = selectedBitwardenFolders,
            selectedMdbxFolders = selectedMdbxFolders,
            categories = categories
        )
    }
    val categoryMenuQuickFolderShortcuts = rememberAsyncComputed(
        currentFilter,
        quickFolderCurrentPath,
        quickFolderNodes,
        quickFolderNodeByPath,
        categoryMenuQuickFolderPasswordCountByCategoryId,
        allPasswords,
        passwordEntries,
        searchQuery,
        keepassDatabases,
        keepassGroupsForSelectedDb,
        bitwardenVaults,
        selectedBitwardenFolders,
        selectedMdbxFolders,
        categories,
        initialValue = emptyList()
    ) {
        buildCategoryMenuFolderShortcuts(
            context = context,
            currentFilter = currentFilter,
            quickFolderCurrentPath = quickFolderCurrentPath,
            quickFolderNodes = quickFolderNodes,
            quickFolderNodeByPath = quickFolderNodeByPath,
            quickFolderPasswordCountByCategoryId = categoryMenuQuickFolderPasswordCountByCategoryId,
            allPasswords = allPasswords,
            searchScopedPasswords = passwordEntries,
            isSearchActive = searchQuery.isNotBlank(),
            keepassDatabases = keepassDatabases,
            keepassGroupsForSelectedDb = keepassGroupsForSelectedDb,
            bitwardenVaults = bitwardenVaults,
            selectedBitwardenFolders = selectedBitwardenFolders,
            selectedMdbxFolders = selectedMdbxFolders,
            categories = categories
        )
    }
    val quickFolderBreadcrumbs = rememberAsyncComputed(
        appSettings.passwordListQuickFolderPathBannerEnabled,
        currentFilter,
        quickFolderCurrentPath,
        quickFolderNodeByPath,
        quickFolderRootFilter,
        keepassDatabases,
        mdbxDatabases,
        bitwardenVaults,
        selectedBitwardenFolders,
        selectedMdbxFolders,
        categories,
        initialValue = emptyList()
    ) {
        buildQuickFolderBreadcrumbs(
            context = context,
            quickFolderPathBannerEnabledForCurrentFilter = quickFolderPathBannerEnabledForCurrentFilter,
            currentFilter = currentFilter,
            quickFolderCurrentPath = quickFolderCurrentPath,
            quickFolderNodeByPath = quickFolderNodeByPath,
            quickFolderRootFilter = quickFolderRootFilter,
            keepassDatabases = keepassDatabases,
            mdbxDatabases = mdbxDatabases,
            selectedMdbxFolders = selectedMdbxFolders,
            bitwardenVaults = bitwardenVaults,
            selectedBitwardenFolders = selectedBitwardenFolders,
            categories = categories
        )
    }

    return PasswordListQuickFolderUiState(
        nodes = quickFolderNodes,
        nodeByPath = quickFolderNodeByPath,
        currentPath = quickFolderCurrentPath,
        rootFilter = quickFolderRootFilter,
        systemBackTarget = quickFolderSystemBackTarget,
        shortcuts = quickFolderShortcuts,
        categoryMenuShortcuts = categoryMenuQuickFolderShortcuts,
        breadcrumbs = quickFolderBreadcrumbs
    )
}

private fun PasswordEntry.hasBoundAuthenticator(): Boolean = authenticatorKey.isNotBlank()

private fun PasswordEntry.hasBoundPasskey(): Boolean =
    PasskeyBindingCodec.decodeList(passkeyBindings).isNotEmpty()

private fun PasswordEntry.hasBoundNote(): Boolean = boundNoteId != null

private fun PasswordEntry.matchesLinkedAggregateContentTypes(
    selectedTypes: Set<PasswordPageContentType>,
    embeddedTypes: Set<PasswordPageContentType> = emptySet()
): Boolean {
    val includeAuthenticator =
        PasswordPageContentType.AUTHENTICATOR in selectedTypes && hasBoundAuthenticator()
    val includePasskey =
        PasswordPageContentType.PASSKEY in selectedTypes && hasBoundPasskey()
    val includeNote =
        PasswordPageContentType.NOTE in selectedTypes && hasBoundNote()
    return includeAuthenticator || includePasskey || includeNote ||
        takagi.ru.monica.data.model.matchesCredentialContentTypes(this, selectedTypes, embeddedTypes)
}

internal fun filterPreStackPasswordEntries(
    passwordEntries: List<PasswordEntry>,
    deletedItemIds: Set<Long>,
    quickFoldersEnabledForCurrentFilter: Boolean,
    currentFilter: CategoryFilter,
    configuredQuickFilterItems: Collection<PasswordListQuickFilterItem>,
    quickFilterFavorite: Boolean,
    quickFilter2fa: Boolean,
    quickFilterNotes: Boolean,
    quickFilterPasskey: Boolean,
    quickFilterBoundNote: Boolean,
    quickFilterAttachments: Boolean,
    activeAttachmentParentIds: Set<Long>,
    quickFilterUncategorized: Boolean,
    quickFilterLocalOnly: Boolean,
    quickFilterNeverStack: Boolean,
    quickFilterWifi: Boolean,
    quickFilterSshKey: Boolean,
    quickFilterBarcode: Boolean,
    effectiveNoStackEntryIds: Set<Long>,
    hasActiveContentTypeFilter: Boolean,
    contentTypeFilterTypes: Set<PasswordPageContentType>,
    contentBlockTypes: Map<Long, Set<PasswordPageContentType>> = emptyMap()
): List<PasswordEntry> {
    var filtered = passwordEntries.filter { it.id !in deletedItemIds }

    if (quickFoldersEnabledForCurrentFilter) {
        filtered = applyQuickFolderRootVisibility(
            entries = filtered,
            currentFilter = currentFilter
        )
    }

    if (quickFilterFavorite && PasswordListQuickFilterItem.FAVORITE in configuredQuickFilterItems) {
        filtered = filtered.filter { it.isFavorite }
    }
    if (quickFilter2fa && PasswordListQuickFilterItem.TWO_FA in configuredQuickFilterItems) {
        filtered = filtered.filter { it.hasBoundAuthenticator() }
    }
    if (quickFilterNotes && PasswordListQuickFilterItem.NOTES in configuredQuickFilterItems) {
        filtered = filtered.filter { it.notes.isNotBlank() }
    }
    if (quickFilterPasskey && PasswordListQuickFilterItem.PASSKEY in configuredQuickFilterItems) {
        filtered = filtered.filter { it.hasBoundPasskey() }
    }
    if (quickFilterBoundNote && PasswordListQuickFilterItem.NOTE in configuredQuickFilterItems) {
        filtered = filtered.filter { it.hasBoundNote() }
    }
    if (quickFilterAttachments && PasswordListQuickFilterItem.ATTACHMENTS in configuredQuickFilterItems) {
        filtered = filtered.filter { it.id in activeAttachmentParentIds }
    }
    if (quickFilterWifi) {
        filtered = filtered.filter { it.isWifiEntry() }
    }
    if (quickFilterSshKey) {
        filtered = filtered.filter { it.isSshKeyEntry() }
    }
    if (quickFilterBarcode) {
        filtered = filtered.filter { it.isBarcodeEntry() }
    }
    if (quickFilterUncategorized && PasswordListQuickFilterItem.UNCATEGORIZED in configuredQuickFilterItems) {
        filtered = filtered.filter { entry ->
            when (val filter = currentFilter) {
                is CategoryFilter.KeePassDatabase ->
                    entry.keepassDatabaseId == filter.databaseId &&
                        entry.keepassGroupPath?.trim().isNullOrBlank()
                is CategoryFilter.BitwardenVault ->
                    entry.bitwardenVaultId == filter.vaultId &&
                        entry.bitwardenFolderId?.trim().isNullOrBlank()
                else -> entry.categoryId == null
            }
        }
    }
    if (quickFilterLocalOnly && PasswordListQuickFilterItem.LOCAL_ONLY in configuredQuickFilterItems) {
        filtered = filtered.filter {
            it.isLocalOnlyEntry()
        }
    }
    if (quickFilterNeverStack && PasswordListQuickFilterItem.NEVER_STACK in configuredQuickFilterItems) {
        filtered = filtered.filter { it.id in effectiveNoStackEntryIds }
    }
    if (hasActiveContentTypeFilter) {
        filtered = filtered.filter { entry ->
            entry.matchesLinkedAggregateContentTypes(contentTypeFilterTypes, contentBlockTypes[entry.id].orEmpty())
        }
    }
    return filtered
}

// Keeps aggregate-card state assembly outside the main password list composable.
@Composable
internal fun rememberPasswordAggregateUiState(
    aggregateConfig: PasswordListAggregateConfig?,
    searchQuery: String,
    currentFilter: CategoryFilter,
    localCategoryIdsInScope: Set<Long>,
    appSettings: AppSettings,
    retainedState: PasswordAggregateRetainedState,
): PasswordListAggregateUiState {
    val emptySecureItems = remember { emptyList<SecureItem>() }
    val emptyPasskeys = remember { emptyList<PasskeyEntry>() }
    val aggregateTotpItemsState =
        aggregateConfig?.totpViewModel?.totpItems?.collectAsState()
            ?: remember { mutableStateOf(emptySecureItems) }
    val aggregateBankCardsState =
        aggregateConfig?.bankCardViewModel?.allCards?.collectAsState()
            ?: remember { mutableStateOf(emptySecureItems) }
    val aggregateDocumentsState =
        aggregateConfig?.documentViewModel?.allDocuments?.collectAsState()
            ?: remember { mutableStateOf(emptySecureItems) }
    val aggregateBillingAddressesState =
        aggregateConfig?.billingAddressViewModel?.allBillingAddresses?.collectAsState()
            ?: remember { mutableStateOf(emptySecureItems) }
    val aggregateNotesState =
        aggregateConfig?.noteViewModel?.allNotes?.collectAsState()
            ?: remember { mutableStateOf(emptySecureItems) }
    val aggregatePasskeysState =
        aggregateConfig?.passkeyViewModel?.allPasskeys?.collectAsState()
            ?: remember { mutableStateOf(emptyPasskeys) }
    val aggregateTotpItems by aggregateTotpItemsState
    val aggregateBankCards by aggregateBankCardsState
    val aggregateDocuments by aggregateDocumentsState
    val aggregateBillingAddresses by aggregateBillingAddressesState
    val aggregateNotes by aggregateNotesState
    val aggregatePasskeys by aggregatePasskeysState
    val aggregateVisibleContentTypes = remember(
        aggregateConfig?.visibleContentTypes,
        aggregateBankCards,
        aggregateDocuments,
        aggregateBillingAddresses,
        aggregateNotes,
        aggregateTotpItems,
        aggregatePasskeys,
        currentFilter,
        localCategoryIdsInScope,
    ) {
        resolveNonEmptyAggregateContentTypes(
            configuredTypes = aggregateConfig?.visibleContentTypes ?: emptyList(),
            bankCards = aggregateBankCards,
            documents = aggregateDocuments,
            billingAddresses = aggregateBillingAddresses,
            notes = aggregateNotes,
            totpItems = aggregateTotpItems,
            passkeys = aggregatePasskeys,
            categoryFilter = currentFilter,
            localCategoryIdsInScope = localCategoryIdsInScope,
            parseTotpData = aggregateConfig?.totpViewModel?.let { viewModel ->
                { item: SecureItem -> viewModel.parseTotpDataForDisplay(item) }
            } ?: {
                takagi.ru.monica.util.TotpDataResolver.parseStoredItemData(
                    itemData = it.itemData,
                    fallbackIssuer = it.title
                )
            }
        )
    }
    val aggregateSelectedContentTypes = aggregateConfig?.selectedContentTypes ?: emptySet()
    val effectiveQuickFilterItems = remember(
        appSettings.passwordPageAggregateEnabled,
        aggregateVisibleContentTypes
    ) {
        appendAggregateContentQuickFilterItems(
            configuredItems = PasswordListQuickFilterItem.DEFAULT_ORDER,
            visibleTypes = aggregateVisibleContentTypes,
            aggregateEnabled = appSettings.passwordPageAggregateEnabled
        )
    }
    val aggregateQuickFilterTypes = remember(
        aggregateVisibleContentTypes,
        effectiveQuickFilterItems
    ) {
        val enabledQuickFilterTypes = effectiveQuickFilterItems
            .mapNotNull { item -> item.toPasswordPageContentTypeOrNull() }
            .toSet()
        resolvePasswordPageQuickFilterTypes(aggregateVisibleContentTypes)
            .filter { type -> type in enabledQuickFilterTypes }
    }
    val aggregateContentTypeFilterTypes = remember(
        aggregateQuickFilterTypes,
        aggregateSelectedContentTypes
    ) {
        aggregateSelectedContentTypes.filterTo(linkedSetOf()) { type ->
            type in aggregateQuickFilterTypes
        }
    }
    val aggregateDisplayedContentTypes = remember(
        aggregateVisibleContentTypes,
        aggregateQuickFilterTypes
    ) {
        resolvePasswordPageDisplayedTypes(
            visibleTypes = buildList {
                add(PasswordPageContentType.PASSWORD)
                addAll(aggregateQuickFilterTypes)
            },
            selectedTypes = emptySet()
        )
    }
    val aggregateCardStyle = remember(
        appSettings.iconCardsEnabled,
        appSettings.passwordPageIconEnabled,
        appSettings.unmatchedIconHandlingStrategy,
        appSettings.passwordCardDisplayMode,
        appSettings.passwordCardDisplayFields,
        appSettings.passwordCardShowAuthenticator,
        appSettings.passwordCardHideOtherContentWhenAuthenticator,
        appSettings.totpTimeOffset,
        appSettings.validatorSmoothProgress
    ) {
        PasswordAggregateCardStyle(
            iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
            unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
            passwordCardDisplayMode = appSettings.passwordCardDisplayMode,
            passwordCardDisplayFields = appSettings.passwordCardDisplayFields,
            showAuthenticator = appSettings.passwordCardShowAuthenticator,
            hideOtherContentWhenAuthenticator = appSettings.passwordCardHideOtherContentWhenAuthenticator,
            totpTimeOffsetSeconds = appSettings.totpTimeOffset,
            smoothAuthenticatorProgress = appSettings.validatorSmoothProgress
        )
    }
    val aggregateSnapshotKey = remember(
        aggregateDisplayedContentTypes,
        searchQuery,
        currentFilter,
        localCategoryIdsInScope,
    ) {
        PasswordAggregateSnapshotKey(
            displayedContentTypes = aggregateDisplayedContentTypes,
            searchQuery = searchQuery,
            categoryFilter = currentFilter,
            localCategoryIdsInScope = localCategoryIdsInScope,
        )
    }
    val aggregateSnapshotSeed = remember(retainedState, aggregateSnapshotKey) {
        retainedState.seed(aggregateSnapshotKey)
    }
    val aggregateSnapshotGeneration = remember(retainedState, aggregateSnapshotKey) {
        retainedState.currentGeneration()
    }
    val aggregateAllVisibleItemsAsync = rememberPasswordAggregateAsyncItems(
        aggregateBankCards,
        aggregateDocuments,
        aggregateBillingAddresses,
        aggregateNotes,
        aggregateTotpItems,
        aggregatePasskeys,
        searchQuery,
        currentFilter,
        localCategoryIdsInScope,
        aggregateDisplayedContentTypes,
        stateKey = aggregateSnapshotKey,
        initialValue = aggregateSnapshotSeed.items,
        onComputed = { items ->
            retainedState.updateIfCurrent(
                expectedGeneration = aggregateSnapshotGeneration,
                key = aggregateSnapshotKey,
                items = items,
            )
        },
    ) {
        withContext(Dispatchers.Default) {
            buildPasswordAggregateItems(
                selectedContentTypes = aggregateDisplayedContentTypes,
                bankCards = aggregateBankCards,
                documents = aggregateDocuments,
                billingAddresses = aggregateBillingAddresses,
                notes = aggregateNotes,
                totpItems = aggregateTotpItems,
                passkeys = aggregatePasskeys,
                searchQuery = searchQuery,
                categoryFilter = currentFilter,
                localCategoryIdsInScope = localCategoryIdsInScope,
                parseBankCardData = aggregateConfig?.bankCardViewModel?.let { viewModel ->
                    { item: SecureItem -> viewModel.parseCardData(item.itemData) }
                } ?: {
                    takagi.ru.monica.data.model.CardWalletDataCodec.parseBankCardData(it.itemData)
                },
                parseDocumentData = aggregateConfig?.documentViewModel?.let { viewModel ->
                    { item: SecureItem -> viewModel.parseDocumentData(item.itemData) }
                } ?: {
                    takagi.ru.monica.data.model.CardWalletDataCodec.parseDocumentData(it.itemData)
                },
                parseBillingAddressData = aggregateConfig?.billingAddressViewModel?.let { viewModel ->
                    { item: SecureItem -> viewModel.parseAddressData(item.itemData) }
                } ?: {
                    takagi.ru.monica.data.model.CardWalletDataCodec.parseBillingAddressData(it.itemData)
                },
                parseTotpData = aggregateConfig?.totpViewModel?.let { viewModel ->
                    { item: SecureItem -> viewModel.parseTotpDataForDisplay(item) }
                } ?: {
                    takagi.ru.monica.util.TotpDataResolver.parseStoredItemData(
                        itemData = it.itemData,
                        fallbackIssuer = it.title
                    )
                }
            )
        }
    }
    val aggregateVisibleItems = remember(
        aggregateAllVisibleItemsAsync,
        aggregateContentTypeFilterTypes,
    ) {
        filterPasswordAggregateItemsByContentTypes(
            items = aggregateAllVisibleItemsAsync,
            selectedTypes = aggregateContentTypeFilterTypes,
        )
    }

    return PasswordListAggregateUiState(
        visibleContentTypes = aggregateVisibleContentTypes,
        selectedContentTypes = aggregateSelectedContentTypes,
        contentTypeFilterTypes = aggregateContentTypeFilterTypes,
        quickFilterTypes = aggregateQuickFilterTypes,
        displayedContentTypes = aggregateDisplayedContentTypes,
        hasActiveContentTypeFilter = aggregateContentTypeFilterTypes.isNotEmpty(),
        cardStyle = aggregateCardStyle,
        visibleItems = aggregateVisibleItems,
        bankCards = aggregateBankCards,
        documents = aggregateDocuments,
        billingAddresses = aggregateBillingAddresses,
        totpItems = aggregateTotpItems,
        notes = aggregateNotes,
        passkeys = aggregatePasskeys,
        totpViewModel = aggregateConfig?.totpViewModel,
        bankCardViewModel = aggregateConfig?.bankCardViewModel,
        documentViewModel = aggregateConfig?.documentViewModel,
        billingAddressViewModel = aggregateConfig?.billingAddressViewModel,
        noteViewModel = aggregateConfig?.noteViewModel,
        passkeyViewModel = aggregateConfig?.passkeyViewModel
    )
}

@Composable
internal fun rememberPasswordAggregateAsyncItems(
    vararg keys: Any?,
    stateKey: Any?,
    initialValue: List<PasswordAggregateListItemUi>,
    onComputed: (List<PasswordAggregateListItemUi>) -> Unit,
    compute: suspend () -> List<PasswordAggregateListItemUi>,
): List<PasswordAggregateListItemUi> {
    var value by remember(stateKey) { mutableStateOf(initialValue) }
    val latestCompute by rememberUpdatedState(compute)
    val latestOnComputed by rememberUpdatedState(onComputed)

    LaunchedEffect(*keys) {
        val computed = latestCompute()
        value = computed
        latestOnComputed(computed)
    }

    return value
}

// Centralizes list scroll bookkeeping so the main screen body stays readable.
@Composable
internal fun rememberPasswordListLazyListState(
    viewModel: PasswordViewModel,
    currentListItemKeys: List<String>,
    scrollToTopRequestKey: Int,
    fastScrollRequestKey: Int,
    fastScrollProgress: Float,
    allowScrollPositionPersistence: Boolean,
    onBackToTopVisibilityChange: (Boolean) -> Unit
): LazyListState {
    // These values seed a new list only. Collecting their every-frame persistence
    // updates would recompose the complete password page during a fling.
    val savedScrollIndex = remember(viewModel) { viewModel.passwordListScrollIndex.value }
    val savedScrollOffset = remember(viewModel) { viewModel.passwordListScrollOffset.value }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollIndex,
        initialFirstVisibleItemScrollOffset = savedScrollOffset
    )
    val currentListItemKeySet = remember(currentListItemKeys) {
        currentListItemKeys.toSet()
    }
    val backToTopVisibilityCallback by rememberUpdatedState(onBackToTopVisibilityChange)
    val latestAllowScrollPositionPersistence by rememberUpdatedState(allowScrollPositionPersistence)
    val latestListItemKeySet by rememberUpdatedState(currentListItemKeySet)
    var lastHandledScrollToTopRequestKey by rememberSaveable {
        mutableStateOf(scrollToTopRequestKey)
    }
    var lastHandledFastScrollRequestKey by remember {
        mutableIntStateOf(fastScrollRequestKey)
    }
    var hasAppliedDeferredScrollRestore by remember {
        mutableStateOf(false)
    }
    // Observe scroll pixels in an effect, outside the containing page's composition.
    // Keep hysteresis local to the collector and publish only visibility changes.
    LaunchedEffect(listState) {
        var shouldShowBackToTop = false
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
                .coerceAtLeast(1)
            val firstVisibleItemSize =
                layoutInfo.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1)
                    ?: viewportHeight
            val estimatedScrollPx = (listState.firstVisibleItemIndex * firstVisibleItemSize) +
                listState.firstVisibleItemScrollOffset
            if (shouldShowBackToTop) {
                listState.firstVisibleItemIndex > 0 || estimatedScrollPx >= (viewportHeight * 1.6f).toInt()
            } else {
                estimatedScrollPx >= viewportHeight * 2
            }
        }.collect { visible ->
            shouldShowBackToTop = visible
            backToTopVisibilityCallback(visible)
        }
    }

    DisposableEffect(listState, viewModel) {
        onDispose {
            backToTopVisibilityCallback(false)
            if (
                latestAllowScrollPositionPersistence &&
                listState.layoutInfo.totalItemsCount > 0
            ) {
                val topVisibleKey = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { item -> item.key.toString() in latestListItemKeySet }
                    ?.key
                    ?.toString()
                viewModel.updatePasswordListScrollPosition(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    topVisibleKey,
                    source = "v1_dispose_persist"
                )
            }
        }
    }

    LaunchedEffect(scrollToTopRequestKey) {
        if (scrollToTopRequestKey > lastHandledScrollToTopRequestKey) {
            try {
                listState.animateScrollToItem(index = 0)
            } finally {
                lastHandledScrollToTopRequestKey = scrollToTopRequestKey
            }
        }
    }

    LaunchedEffect(fastScrollRequestKey) {
        if (fastScrollRequestKey <= lastHandledFastScrollRequestKey) {
            return@LaunchedEffect
        }

        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems <= 0) {
            Log.d(
                PASSWORD_SCROLL_LOG_TAG,
                "source=v1_fast_scroll_skip_empty requestKey=$fastScrollRequestKey progress=$fastScrollProgress"
            )
            lastHandledFastScrollRequestKey = fastScrollRequestKey
            return@LaunchedEffect
        }

        val targetIndex = (fastScrollProgress.coerceIn(0f, 1f) * (totalItems - 1))
            .roundToInt()
            .coerceIn(0, totalItems - 1)
        if (listState.firstVisibleItemIndex == targetIndex) {
            Log.d(
                PASSWORD_SCROLL_LOG_TAG,
                "source=v1_fast_scroll_skip_same_target requestKey=$fastScrollRequestKey target=$targetIndex current=${listState.firstVisibleItemIndex}"
            )
            lastHandledFastScrollRequestKey = fastScrollRequestKey
            return@LaunchedEffect
        }

        Log.d(
            PASSWORD_SCROLL_LOG_TAG,
            "source=v1_fast_scroll_apply requestKey=$fastScrollRequestKey progress=$fastScrollProgress target=$targetIndex current=${listState.firstVisibleItemIndex} total=$totalItems"
        )

        runCatching {
            listState.scrollToItem(index = targetIndex)
        }.onFailure { throwable ->
            if (throwable is CancellationException) return@onFailure
            Log.e(
                FAST_SCROLL_LOG_TAG,
                "scrollToItem failed: targetIndex=$targetIndex totalItems=${listState.layoutInfo.totalItemsCount}",
                throwable
            )
        }.also {
            lastHandledFastScrollRequestKey = fastScrollRequestKey
        }
    }

    // layoutInfo changes on every scroll frame; restoration only needs count changes.
    val totalListItemsCount by remember(listState) {
        derivedStateOf { listState.layoutInfo.totalItemsCount }
    }
    LaunchedEffect(
        allowScrollPositionPersistence,
        currentListItemKeys,
        savedScrollIndex,
        savedScrollOffset,
        totalListItemsCount
    ) {
        if (hasAppliedDeferredScrollRestore) return@LaunchedEffect
        if (!allowScrollPositionPersistence) return@LaunchedEffect
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems <= 0) return@LaunchedEffect
        Log.d(
            PASSWORD_SCROLL_LOG_TAG,
            "source=v1_restore_check saved=$savedScrollIndex/$savedScrollOffset current=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset} total=$totalItems"
        )
        val hasSavedPosition = savedScrollIndex != 0 || savedScrollOffset != 0
        if (!hasSavedPosition) {
            if (listState.firstVisibleItemIndex != 0 ||
                listState.firstVisibleItemScrollOffset != 0
            ) {
                Log.d(
                    PASSWORD_SCROLL_LOG_TAG,
                    "source=v1_restore_no_saved_force_top current=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset} total=$totalItems"
                )
                runCatching {
                    listState.scrollToItem(0, 0)
                }.onSuccess {
                    viewModel.updatePasswordListScrollPosition(
                        0,
                        0,
                        null,
                        source = "v1_restore_no_saved_force_top"
                    )
                }
            }
            hasAppliedDeferredScrollRestore = true
            return@LaunchedEffect
        }

        val isSavedIndexOutOfBounds = savedScrollIndex !in 0 until totalItems
        val targetIndex = if (isSavedIndexOutOfBounds) 0 else savedScrollIndex
        val targetOffset = if (isSavedIndexOutOfBounds) 0 else savedScrollOffset

        if (isSavedIndexOutOfBounds) {
            Log.w(
                PASSWORD_SCROLL_LOG_TAG,
                "source=v1_restore_saved_out_of_bounds saved=$savedScrollIndex/$savedScrollOffset total=$totalItems -> 0/0"
            )
            runCatching {
                listState.scrollToItem(targetIndex, targetOffset)
            }.onSuccess {
                viewModel.updatePasswordListScrollPosition(
                    0,
                    0,
                    null,
                    source = "v1_restore_saved_out_of_bounds"
                )
                hasAppliedDeferredScrollRestore = true
            }
            return@LaunchedEffect
        }

        if (listState.firstVisibleItemIndex == targetIndex &&
            listState.firstVisibleItemScrollOffset == targetOffset
        ) {
            Log.d(
                PASSWORD_SCROLL_LOG_TAG,
                "source=v1_restore_skip_already_at_target target=$targetIndex/$targetOffset"
            )
            hasAppliedDeferredScrollRestore = true
            return@LaunchedEffect
        }
        Log.d(
            PASSWORD_SCROLL_LOG_TAG,
            "source=v1_restore_apply target=$targetIndex/$targetOffset current=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset} total=$totalItems"
        )
        runCatching {
            listState.scrollToItem(targetIndex, targetOffset)
        }.onSuccess {
            Log.d(
                PASSWORD_SCROLL_LOG_TAG,
                "source=v1_restore_applied target=$targetIndex/$targetOffset"
            )
            hasAppliedDeferredScrollRestore = true
        }
    }

    LaunchedEffect(
        listState,
        allowScrollPositionPersistence,
        hasAppliedDeferredScrollRestore,
        savedScrollIndex,
        savedScrollOffset
    ) {
        snapshotFlow {
            val pendingRestore =
                !hasAppliedDeferredScrollRestore &&
                    (
                        savedScrollIndex != 0 ||
                            savedScrollOffset != 0
                        )
            val topVisibleKey = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { item -> item.key.toString() in currentListItemKeySet }
                ?.key
                ?.toString()
            PasswordListScrollSnapshot(
                allowPersistence = allowScrollPositionPersistence,
                pendingRestore = pendingRestore,
                totalItems = listState.layoutInfo.totalItemsCount,
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
                anchorKey = topVisibleKey,
                isScrollInProgress = listState.isScrollInProgress
            )
        }
            .distinctUntilChangedBy { snapshot -> snapshot.persistenceKey() }
            .collect { snapshot ->
                if (!snapshot.allowPersistence || snapshot.pendingRestore || snapshot.totalItems <= 0) {
                    return@collect
                }
                viewModel.updatePasswordListScrollPosition(
                    snapshot.index,
                    snapshot.offset,
                    snapshot.anchorKey,
                    source = "v1_snapshot_persist"
                )
            }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems <= 1) {
                0f
            } else {
                (listState.firstVisibleItemIndex.toFloat() / (totalItems - 1).toFloat())
                    .coerceIn(0f, 1f)
            }
        }
            .distinctUntilChanged()
            .collect { progress ->
                viewModel.updateFastScrollProgress(progress)
            }
    }

    return listState
}

internal fun buildGroupedPasswordsForEntries(
    sourceEntries: List<PasswordEntry>,
    config: PasswordGroupingConfig
): Map<String, List<PasswordEntry>> {
    if (sourceEntries.size >= NATIVE_PASSWORD_GROUPING_THRESHOLD) {
        tryBuildNativePasswordGroups(sourceEntries, config)?.let { return it }
    }
    return buildKotlinPasswordGroups(sourceEntries, config)
}

internal fun buildKotlinPasswordGroups(
    sourceEntries: List<PasswordEntry>,
    config: PasswordGroupingConfig,
): Map<String, List<PasswordEntry>> {
    if (config.isLocalOnlyView) {
        return sourceEntries.sortedBy { it.sortOrder }.associate { "entry_${it.id}" to listOf(it) }
    }
    val scoreCache = java.util.IdentityHashMap<List<PasswordEntry>, Int>()
    fun score(passwords: List<PasswordEntry>): Int = scoreCache.getOrPut(passwords) {
        val firstInfo = getPasswordInfoKey(passwords.first())
        val distinctInfo = passwords.asSequence().drop(1).any { getPasswordInfoKey(it) != firstInfo }
        val cardType = if (distinctInfo) 3 else if (passwords.size > 1) 2 else 1
        (if (passwords.any { it.isFavorite }) 10 else 0) + cardType
    }
    val mergedByInfo = if (config.effectiveStackCardMode == StackCardMode.ALWAYS_EXPANDED) {
        sourceEntries.sortedBy { it.sortOrder }.map { listOf(it) }
    } else {
        sourceEntries
            .groupBy { entry ->
                if (entry.id in config.effectiveNoStackEntryIds) {
                    "$NO_STACK_GROUP_KEY_PREFIX${entry.id}"
                } else {
                    config.effectiveManualStackGroupByEntryId[entry.id]
                        ?.let { groupId -> "$MANUAL_STACK_GROUP_KEY_PREFIX$groupId" }
                        ?: getPasswordInfoKey(entry)
                }
            }
            .map { (_, entries) -> entries.sortedBy { it.sortOrder } }
    }

    val groupedAndSorted = if (config.isLocalOnlyView) {
        sourceEntries
            .sortedBy { it.sortOrder }
            .associate { entry -> "entry_${entry.id}" to listOf(entry) }
    } else {
        when (config.effectiveGroupMode) {
            "title" -> mergedByInfo
                .groupBy { entries ->
                    val first = entries.first()
                    if (first.id in config.effectiveNoStackEntryIds) {
                        "$NO_STACK_GROUP_KEY_PREFIX${first.id}"
                    } else {
                        config.effectiveManualStackGroupByEntryId[first.id]
                            ?.let { groupId -> "$MANUAL_STACK_GROUP_KEY_PREFIX$groupId" }
                            ?: first.title.ifBlank { config.untitledLabel }
                    }
                }
                .mapValues { (_, groups) -> groups.flatten() }
                .toList()
                .sortedWith(
                    compareByDescending<Pair<String, List<PasswordEntry>>> { (_, passwords) ->
                        score(passwords)
                    }.thenBy { (title, _) -> title }
                )
                .toMap()

            else -> mergedByInfo
                .groupBy { entries ->
                    val first = entries.first()
                    if (first.id in config.effectiveNoStackEntryIds) {
                        "$NO_STACK_GROUP_KEY_PREFIX${first.id}"
                    } else {
                        config.effectiveManualStackGroupByEntryId[first.id]
                            ?.let { groupId -> "$MANUAL_STACK_GROUP_KEY_PREFIX$groupId" }
                            ?: getGroupKeyForMode(
                                first,
                                config.effectiveGroupMode,
                                config.websiteStackMatchMode
                            )
                    }
                }
                .mapValues { (_, groups) -> groups.flatten() }
                .toList()
                .sortedWith(
                    compareByDescending<Pair<String, List<PasswordEntry>>> { (_, passwords) ->
                        score(passwords)
                    }.thenBy { (_, passwords) ->
                        passwords.firstOrNull()?.sortOrder ?: Int.MAX_VALUE
                    }
                )
                .toMap()
        }
    }

    return if (config.effectiveStackCardMode == StackCardMode.ALWAYS_EXPANDED) {
        groupedAndSorted.values.flatten()
            .map { entry -> "entry_${entry.id}" to listOf(entry) }
            .toMap()
    } else {
        groupedAndSorted
    }
}

internal fun filterPasswordEntriesByStackQuickFilters(
    items: List<PasswordEntry>,
    configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
    quickFilterManualStackOnly: Boolean,
    quickFilterUnstacked: Boolean,
    effectiveStackCardMode: StackCardMode,
    effectiveManualStackGroupByEntryId: Map<Long, String>,
    aggregateManualStackedItemKeys: Set<String>,
    aggregateManualStackedPasswordIds: Set<Long>,
    groupingConfig: PasswordGroupingConfig
): List<PasswordEntry> {
    var filtered = items

    if (
        quickFilterManualStackOnly &&
        PasswordListQuickFilterItem.MANUAL_STACK_ONLY in configuredQuickFilterItems
    ) {
        filtered = filtered.filter { entry ->
            effectiveManualStackGroupByEntryId.containsKey(entry.id) ||
                passwordSelectionKey(entry.id) in aggregateManualStackedItemKeys
        }
    }

    if (
        quickFilterUnstacked &&
        PasswordListQuickFilterItem.UNSTACKED in configuredQuickFilterItems &&
        effectiveStackCardMode != StackCardMode.ALWAYS_EXPANDED
    ) {
        val autoGroupingCandidates = filtered.filter { entry ->
            entry.id !in aggregateManualStackedPasswordIds
        }
        // This quick-filter projection runs synchronously during composition. Keep
        // JNI loading and dispatch on the background grouping path below.
        val singleCardEntryIds = buildKotlinPasswordGroups(
            sourceEntries = autoGroupingCandidates,
            config = groupingConfig
        )
            .values
            .asSequence()
            .filter { group -> group.size == 1 }
            .flatten()
            .map(PasswordEntry::id)
            .toSet()
        filtered = filtered.filter { entry ->
            entry.id !in aggregateManualStackedPasswordIds &&
                entry.id in singleCardEntryIds
        }
    }

    return filtered
}

internal fun resolvePasswordListInitialRenderState(
    hasCompletedInitialPasswordListStabilization: Boolean,
    passwordEntriesReady: Boolean,
    allPasswordsForUiReady: Boolean,
    categoriesReady: Boolean,
    shouldRenderPasswordGroups: Boolean,
    hasGroupedPasswordsReadyForCurrentInputs: Boolean,
    visiblePasswordIds: List<Long>,
    groupedPasswordIds: List<Long>,
    displayedContentTypes: Set<PasswordPageContentType>,
    searchQuery: String
): PasswordListInitialRenderState {
    val isPasswordListDataLoaded = passwordEntriesReady && allPasswordsForUiReady
    val isHeaderDataLoaded = isPasswordListDataLoaded && categoriesReady
    val isGroupedPasswordModelReady =
        !shouldRenderPasswordGroups ||
            visiblePasswordIds.isEmpty() ||
            (
                hasGroupedPasswordsReadyForCurrentInputs &&
                    groupedPasswordIds.size == visiblePasswordIds.size &&
                    groupedPasswordIds.toSet() == visiblePasswordIds.toSet()
                )
    val isPasswordPageListModelReady = if (!isPasswordListDataLoaded) {
        false
    } else {
        isGroupedPasswordModelReady
    }
    val shouldGateInitialContent =
        !hasCompletedInitialPasswordListStabilization &&
            (!isPasswordPageListModelReady || !isHeaderDataLoaded) &&
            PasswordPageContentType.PASSWORD in displayedContentTypes &&
            searchQuery.isEmpty()

    return PasswordListInitialRenderState(
        isPasswordListDataLoaded = isPasswordListDataLoaded,
        isHeaderDataLoaded = isHeaderDataLoaded,
        isPasswordPageListModelReady = isPasswordPageListModelReady,
        shouldGateInitialContent = shouldGateInitialContent
    )
}

internal suspend fun applyFavoriteSelectionToggle(
    request: FavoriteSelectionToggleRequest
): Int {
    val selectedEntries = request.passwordEntries.filter { it.id in request.selectedPasswords }
    val selectedNative = request.nativeEntries.filter { it.displayId in request.selectedPasswords }
    val favoriteTargets = selectedEntries.size + selectedNative.size + request.selectedSupplementaryItems.count {
        it.type != PasswordPageContentType.PASSKEY
    }
    if (favoriteTargets <= 0) {
        return 0
    }

    val allFavorited = selectedEntries.all { it.isFavorite } && selectedNative.all { it.isFavorite } &&
        request.selectedSupplementaryItems.all { item ->
            when (item.type) {
                PasswordPageContentType.AUTHENTICATOR,
                PasswordPageContentType.CARD_WALLET,
                PasswordPageContentType.NOTE -> item.entry.isFavorite
                PasswordPageContentType.PASSKEY,
                PasswordPageContentType.API_KEY, PasswordPageContentType.API_TOKEN, PasswordPageContentType.GPG_KEY, PasswordPageContentType.API_KEY, PasswordPageContentType.API_TOKEN, PasswordPageContentType.GPG_KEY, PasswordPageContentType.PASSWORD -> true
            }
        }
    val newFavoriteState = !allFavorited

    selectedNative.forEach { token ->
        checkNotNull(request.nativeViewModel).setNativeApiTokenFavorite(token, newFavoriteState)
    }

    selectedEntries.forEach { entry ->
        request.viewModel.toggleFavorite(entry.id, newFavoriteState)
    }

    request.selectedSupplementaryItems.forEach { item ->
        when (item.type) {
            PasswordPageContentType.AUTHENTICATOR -> {
                item.secureItemId?.let { id ->
                    request.aggregateUiState.totpViewModel?.toggleFavorite(id, newFavoriteState)
                }
            }

            PasswordPageContentType.CARD_WALLET -> {
                item.secureItemId?.let { id ->
                    when (item.walletItemType) {
                        PasswordAggregateWalletItemType.BANK_CARD ->
                            request.aggregateUiState.bankCardViewModel?.toggleFavorite(id)
                        PasswordAggregateWalletItemType.DOCUMENT ->
                            request.aggregateUiState.documentViewModel?.toggleFavorite(id)
                        PasswordAggregateWalletItemType.BILLING_ADDRESS ->
                            request.aggregateUiState.billingAddressViewModel?.toggleFavorite(id)
                        null -> Unit
                    }
                }
            }

            PasswordPageContentType.NOTE -> {
                item.secureItemId?.let { noteId ->
                    request.aggregateUiState.notes
                        .firstOrNull { it.id == noteId }
                        ?.let { note ->
                            val decoded = NoteContentCodec.decodeFromItem(note)
                            request.aggregateUiState.noteViewModel?.updateNote(
                                id = note.id,
                                content = decoded.content,
                                title = note.title,
                                tags = decoded.tags,
                                isMarkdown = decoded.isMarkdown,
                                isFavorite = newFavoriteState,
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
            }

            PasswordPageContentType.PASSKEY,
            PasswordPageContentType.API_KEY, PasswordPageContentType.API_TOKEN, PasswordPageContentType.GPG_KEY, PasswordPageContentType.API_KEY, PasswordPageContentType.API_TOKEN, PasswordPageContentType.GPG_KEY, PasswordPageContentType.PASSWORD -> Unit
        }
    }

    val messageRes = if (newFavoriteState) {
        R.string.batch_favorited
    } else {
        R.string.batch_unfavorited
    }
    Toast.makeText(
        request.context,
        request.context.getString(messageRes, favoriteTargets),
        Toast.LENGTH_SHORT
    ).show()
    return favoriteTargets
}
