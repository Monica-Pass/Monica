package takagi.ru.monica.ui.vaultv2

import takagi.ru.monica.utils.AppLocaleStringResolver

import takagi.ru.monica.ui.screens.NativeTokenListUi
import takagi.ru.monica.ui.screens.NativeTokenFilterChip
import takagi.ru.monica.ui.screens.rememberNativeTokenList


import android.icu.text.Transliterator
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.key
import takagi.ru.monica.ui.screens.toTrashScopeFilter
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.isUserVisibleSyncInProgress
import takagi.ru.monica.bitwarden.ui.BitwardenAutoSyncEffect
import takagi.ru.monica.bitwarden.ui.UnlockVaultDialog
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.VaultListSort
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.CategorySelectionUiMode
import takagi.ru.monica.data.VaultOverviewModule
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxCapability
import takagi.ru.monica.data.PasswordListQuickFilterItem
import takagi.ru.monica.data.PasswordListTopModule
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.data.isLocalOnlyPasskey
import takagi.ru.monica.data.supports
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.security.maskDocumentNumberForPreview
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.PasskeyBindingCodec
import takagi.ru.monica.data.VaultV2LayoutMode
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.PasswordListCategoryChipMenu
import takagi.ru.monica.ui.passwordPageContentTypeSetSaver
import takagi.ru.monica.ui.buildCategoryMenuQuickFilterBindings
import takagi.ru.monica.ui.components.UnifiedCategoryFilterBottomSheet
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuDropdown
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuOffset
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.ui.components.ExpressiveLazyListScrollbar
import takagi.ru.monica.ui.components.GroupedItemDefaults
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_NONE
import takagi.ru.monica.ui.icons.VaultItemIcon
import takagi.ru.monica.ui.common.pull.rememberPullActionState
import takagi.ru.monica.ui.common.pull.PullSearchDefaults
import takagi.ru.monica.ui.common.pull.PullSearchHint
import takagi.ru.monica.ui.PasswordQuickFolderBreadcrumb
import takagi.ru.monica.ui.PasswordQuickFolderBreadcrumbPath
import takagi.ru.monica.ui.PasswordQuickFolderBreadcrumbBanner
import takagi.ru.monica.ui.PasswordQuickFolderChipRow
import takagi.ru.monica.ui.PasswordQuickFolderChipRowParams
import takagi.ru.monica.ui.PasswordQuickFolderShortcut
import takagi.ru.monica.ui.PasswordListInitialLoadingIndicator
import takagi.ru.monica.ui.buildPasswordQuickFolderNodes
import takagi.ru.monica.ui.buildCategoryMenuFolderShortcuts
import takagi.ru.monica.ui.buildLocalQuickFolderPasswordCountByCategoryId
import takagi.ru.monica.ui.buildMdbxFolderPathLabel
import takagi.ru.monica.ui.buildQuickFolderBreadcrumbs
import takagi.ru.monica.ui.MdbxPathSyncActions
import takagi.ru.monica.ui.MdbxPathSyncState
import takagi.ru.monica.ui.mdbxPathPendingSyncCount
import takagi.ru.monica.ui.mdbxPathShouldFlushPendingUpload
import takagi.ru.monica.ui.PasswordQuickFilterChipCallbacks
import takagi.ru.monica.ui.PasswordQuickFilterChipState
import takagi.ru.monica.ui.PasswordBatchMoveViewModels
import takagi.ru.monica.ui.PasswordBatchTransferNotificationHelper
import takagi.ru.monica.ui.buildMoveTargetLabel
import takagi.ru.monica.ui.executeMixedPasswordBatchMove
import takagi.ru.monica.ui.executePasswordCategoryFolderTransfer
import takagi.ru.monica.utils.resolveLocalCategoryIdsInScope
import takagi.ru.monica.ui.resolvePasswordBatchMoveAction
import takagi.ru.monica.ui.shouldForceSupplementaryKeePassCopy
import takagi.ru.monica.ui.supportsQuickFolders
import takagi.ru.monica.ui.components.ExpressiveTopBar
import takagi.ru.monica.ui.components.QuickStatusBar
import takagi.ru.monica.ui.common.selection.SelectionActionBar
import takagi.ru.monica.ui.components.UnifiedMoveToCategoryBottomSheet
import takagi.ru.monica.ui.components.toUnifiedMoveInitialSource
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.gestures.SwipeActions
import takagi.ru.monica.ui.password.PasswordAuthenticatorDisplayState
import takagi.ru.monica.ui.password.BitwardenClearCacheTopActionsMenuItem
import takagi.ru.monica.ui.password.BitwardenLockTopActionsMenuItem
import takagi.ru.monica.ui.password.BitwardenReunlockTopActionsMenuItem
import takagi.ru.monica.ui.password.BitwardenSyncTopActionsMenuItem
import takagi.ru.monica.ui.password.CommonPasswordTopActionsMenuItems
import takagi.ru.monica.ui.password.KeepassRefreshTopActionsMenuItem
import takagi.ru.monica.ui.password.KeepassNativeManagerTopActionsMenuItem
import takagi.ru.monica.ui.password.MdbxCommitHistoryTopActionsMenuItem
import takagi.ru.monica.ui.password.MdbxCreateSnapshotTopActionsMenuItem
import takagi.ru.monica.ui.password.MdbxSyncTopActionsMenuItem
import takagi.ru.monica.ui.password.PasswordTopActionsDropdownMenu
import takagi.ru.monica.ui.password.StackCardMode
import takagi.ru.monica.ui.password.PasswordBatchTransferProgressTracker
import takagi.ru.monica.ui.password.PasswordBatchDeleteProgressTracker
import takagi.ru.monica.ui.password.PasswordBatchTransferGlobalProgressState
import takagi.ru.monica.ui.password.PasswordBatchDeleteGlobalProgressState
import takagi.ru.monica.ui.password.appendAggregateContentQuickFilterItems
import takagi.ru.monica.ui.password.resolvePasswordPageDisplayedTypes
import takagi.ru.monica.ui.password.resolvePasswordPageVisibleTypes
import takagi.ru.monica.ui.password.sanitizeSelectedPasswordPageTypes
import takagi.ru.monica.ui.password.rememberPasswordAuthenticatorDisplayState
import takagi.ru.monica.ui.rememberTotpTickerMillis
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.MdbxViewModel
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasskeyViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel
import takagi.ru.monica.viewmodel.TotpViewModel
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.utils.KEEPASS_DISPLAY_PATH_SEPARATOR
import takagi.ru.monica.utils.decodeKeePassPathSegments
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.planLocalCategoryMove
import takagi.ru.monica.utils.planLocalCategoryRename
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.ui.components.CreateCategoryDialog
import takagi.ru.monica.ui.components.CreateDialogTarget
import java.util.concurrent.CancellationException
import java.util.Locale
import kotlin.math.roundToInt

internal enum class VaultV2ItemType {
	PASSWORD,
	AUTHENTICATOR,
	NOTE,
	PASSKEY,
	BANK_CARD,
	DOCUMENT,
	BILLING_ADDRESS,
}

internal data class VaultV2Item(
	val key: String,
	val type: VaultV2ItemType,
	val title: String,
	val subtitle: String,
	val isFavorite: Boolean,
	val sortKey: String,
	val searchableValues: List<String>,
	val passwordEntry: PasswordEntry? = null,
	val totpItem: SecureItem? = null,
	val secureItem: SecureItem? = null,
	val passkeyEntry: PasskeyEntry? = null,
	val boundPasswordId: Long? = null,
	val nativeToken: takagi.ru.monica.data.NativeApiTokenSummary? = null,
	val stackedItems: List<VaultV2Item> = emptyList(),
)

internal fun buildVaultV2PasswordItems(entries: List<PasswordEntry>): List<VaultV2Item> {
	return entries.map { entry ->
		val displayTitle = entry.title.ifBlank { "(Untitled)" }
		val subtitle = entry.username.ifBlank { entry.website }.ifBlank { "-" }
		VaultV2Item(
			key = "password:${entry.id}",
			type = VaultV2ItemType.PASSWORD,
			title = displayTitle,
			subtitle = subtitle,
			isFavorite = entry.isFavorite,
			sortKey = normalizedVaultV2SortKey(displayTitle),
			searchableValues = listOf(
				displayTitle,
				entry.username,
				entry.website,
				entry.appName,
				entry.notes
			).filter { it.isNotBlank() },
			passwordEntry = entry,
		)
	}
}

internal fun mergeVaultV2ImmediatePasswordItems(
	computedItems: List<VaultV2Item>,
	currentPasswordItems: List<VaultV2Item>,
): List<VaultV2Item> {
	val currentPasswordIds = currentPasswordItems
		.mapNotNullTo(hashSetOf()) { it.passwordEntry?.id }
	val retainedSecondaryItems = computedItems.filter { item ->
		item.type != VaultV2ItemType.PASSWORD &&
			!(item.type == VaultV2ItemType.AUTHENTICATOR && item.boundPasswordId in currentPasswordIds)
	}
	return dedupeExactVaultItems(currentPasswordItems + retainedSecondaryItems)
		.sortedWith(
			compareBy<VaultV2Item> { it.sortKey.lowercase(Locale.ROOT) }
				.thenBy { it.type.ordinal }
				.thenBy { it.key }
		)
}

private fun parseVaultV2TotpItemData(
	item: SecureItem,
	securityManager: SecurityManager?
): TotpData? {
	val decryptIfNeeded = securityManager?.let { manager ->
		{ value: String ->
			runCatching { manager.decryptDataIfMonicaCiphertext(value) }.getOrDefault(value)
		}
	}
	return TotpDataResolver.parseStoredItemData(
		itemData = item.itemData,
		fallbackIssuer = item.title,
		decryptIfNeeded = decryptIfNeeded
	)
}

private fun parseVaultV2BankCardData(
	item: SecureItem,
	securityManager: SecurityManager?
): BankCardData? {
	val decryptIfNeeded = securityManager?.let { manager ->
		{ value: String ->
			runCatching { manager.decryptDataIfMonicaCiphertext(value) }.getOrDefault(value)
		}
	}
	return CardWalletDataCodec.parseBankCardData(
		raw = item.itemData,
		decryptIfNeeded = decryptIfNeeded
	)
}

private fun parseVaultV2DocumentData(
	item: SecureItem,
	securityManager: SecurityManager?
): DocumentData? {
	val decryptIfNeeded = securityManager?.let { manager ->
		{ value: String ->
			runCatching { manager.decryptDataIfMonicaCiphertext(value) }.getOrDefault(value)
		}
	}
	return CardWalletDataCodec.parseDocumentData(
		raw = item.itemData,
		decryptIfNeeded = decryptIfNeeded
	)
}

internal data class VaultV2SectionLayout(
	val title: String,
	val items: List<VaultV2Item>,
	val itemStartIndex: Int,
	val firstItemLazyIndex: Int,
)

internal data class VaultV2ComputedListState(
	val allItemsRaw: List<VaultV2Item> = emptyList(),
	val passwordById: Map<Long, PasswordEntry> = emptyMap(),
)

internal data class VaultV2VisibleListState(
	val filteredItems: List<VaultV2Item> = emptyList(),
	val sectionedItems: List<Pair<String, List<VaultV2Item>>> = emptyList(),
	val sectionLayouts: List<VaultV2SectionLayout> = emptyList(),
)

internal data class VaultV2DisplayListState(
	val allItemsRaw: List<VaultV2Item> = emptyList(),
	val visibleListState: VaultV2VisibleListState = VaultV2VisibleListState(),
)

private data class VaultV2AsyncComputedValue<T>(
	val value: T,
	val isComputing: Boolean,
	val hasComputed: Boolean,
)

internal data class VaultV2ComputedSnapshotKey(
	val isArchiveView: Boolean,
	val showOnlyLocalData: Boolean,
)

internal data class VaultV2ComputedSources(
	val passwords: List<PasswordEntry>,
	val totpItems: List<SecureItem>,
	val bankCardItems: List<SecureItem>,
	val documentItems: List<SecureItem>,
	val noteItems: List<SecureItem>,
	val passkeyItems: List<PasskeyEntry>,
	val billingAddressItems: List<SecureItem> = emptyList(),
)

internal data class VaultV2VisibleSnapshotKey(
	val storageSelection: UnifiedCategoryFilterSelection,
	val localCategoryIdsInScope: Set<Long>,
	val displayedContentTypes: Set<PasswordPageContentType>,
	val configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
	val quickFilterStates: List<Boolean>,
	val activeAttachmentParentIds: Set<Long>,
	val manualStackGroupByEntryId: Map<Long, String>,
	val noStackEntryIds: Set<Long>,
	val normalizedQuery: String,
	val isArchiveView: Boolean,
	val overviewItemType: VaultV2ItemType? = null,
	val contentBlockTypes: Map<Long, Set<PasswordPageContentType>> = emptyMap(),
	val nativeOnly: Boolean = false,
	val sort: VaultListSort = VaultListSort.TITLE_ASC,
)

private data class VaultV2SecondaryLists(
	val totp: List<VaultV2Item>,
	val notes: List<VaultV2Item>,
	val passkeys: List<VaultV2Item>,
	val bankCards: List<VaultV2Item>,
	val documents: List<VaultV2Item>,
	val billingAddresses: List<VaultV2Item>,
)

internal data class VaultV2VisibleListConfig(
	val storageSelection: UnifiedCategoryFilterSelection,
	val localCategoryIdsInScope: Set<Long> = emptySet(),
	val displayedContentTypes: Set<PasswordPageContentType>,
	val configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
	val quickFilterFavorite: Boolean,
	val quickFilter2fa: Boolean,
	val quickFilterNotes: Boolean,
	val quickFilterPasskey: Boolean,
	val quickFilterBoundNote: Boolean,
	val quickFilterAttachments: Boolean,
	val activeAttachmentParentIds: Set<Long>,
	val quickFilterUncategorized: Boolean,
	val quickFilterLocalOnly: Boolean,
	val quickFilterManualStackOnly: Boolean,
	val quickFilterNeverStack: Boolean,
	val quickFilterUnstacked: Boolean,
	val manualStackGroupByEntryId: Map<Long, String>,
	val noStackEntryIds: Set<Long>,
	val normalizedQuery: String,
	val isArchiveView: Boolean,
	val overviewItemType: VaultV2ItemType? = null,
	val contentBlockTypes: Map<Long, Set<PasswordPageContentType>> = emptyMap(),
	val nativeOnly: Boolean = false,
	val sort: VaultListSort = VaultListSort.TITLE_ASC,
)

private const val VAULT_V2_FAST_SCROLL_LOG_TAG = "VaultV2FastScroll"
private const val VAULT_V2_EMPTY_STATE_DEBOUNCE_MS = 220L
private const val VAULT_V2_CATEGORY_FILTER_SCOPE = "vault_v2"
private const val MONICA_MANUAL_STACK_GROUP_FIELD_TITLE = "__monica_manual_stack_group"
private const val MONICA_NO_STACK_FIELD_TITLE = "__monica_no_stack"
private val vaultV2Transliterator: Transliterator by lazy(LazyThreadSafetyMode.NONE) {
	Transliterator.getInstance("Any-Latin; Latin-ASCII")
}

internal fun shouldShowVaultV2InitialLoading(
	queryIsBlank: Boolean,
	hasVisibleSections: Boolean,
	hasRetainedSnapshot: Boolean,
	passwordEntriesReady: Boolean,
	computedListIsComputing: Boolean,
	hasPendingItems: Boolean,
): Boolean {
	if (!queryIsBlank || hasVisibleSections || hasRetainedSnapshot) return false

	return !passwordEntriesReady ||
		computedListIsComputing ||
		hasPendingItems
}

private fun PasswordEntry.isVaultV2LocalOnly(): Boolean {
	return isLocalOnlyEntry()
}

private fun SecureItem.isVaultV2LocalOnly(): Boolean {
	return isLocalOnlyItem()
}

private fun PasskeyEntry.isVaultV2LocalOnly(): Boolean {
	return isLocalOnlyPasskey()
}

private fun PasswordPageContentType.toVaultV2ItemTypes(): Set<VaultV2ItemType> = when (this) {
	PasswordPageContentType.API_KEY, PasswordPageContentType.API_TOKEN, PasswordPageContentType.GPG_KEY, PasswordPageContentType.PASSWORD -> setOf(VaultV2ItemType.PASSWORD)
	PasswordPageContentType.AUTHENTICATOR -> setOf(VaultV2ItemType.AUTHENTICATOR)
	PasswordPageContentType.NOTE -> setOf(VaultV2ItemType.NOTE)
	PasswordPageContentType.PASSKEY -> setOf(VaultV2ItemType.PASSKEY)
	PasswordPageContentType.CARD_WALLET -> setOf(VaultV2ItemType.BANK_CARD, VaultV2ItemType.DOCUMENT, VaultV2ItemType.BILLING_ADDRESS)
}

private fun VaultV2Item.toPasswordPageContentType(): PasswordPageContentType = when (type) {
	VaultV2ItemType.PASSWORD -> PasswordPageContentType.PASSWORD
	VaultV2ItemType.AUTHENTICATOR -> PasswordPageContentType.AUTHENTICATOR
	VaultV2ItemType.NOTE -> PasswordPageContentType.NOTE
	VaultV2ItemType.PASSKEY -> PasswordPageContentType.PASSKEY
	VaultV2ItemType.BANK_CARD,
	VaultV2ItemType.DOCUMENT,
	VaultV2ItemType.BILLING_ADDRESS -> PasswordPageContentType.CARD_WALLET
}

internal fun VaultV2PaneState.toUnifiedCategoryFilterSelection(): UnifiedCategoryFilterSelection {
	return when (storageFilterType) {
		VAULT_V2_STORAGE_FILTER_ALL -> UnifiedCategoryFilterSelection.All
		VAULT_V2_STORAGE_FILTER_LOCAL -> UnifiedCategoryFilterSelection.Local
		VAULT_V2_STORAGE_FILTER_STARRED -> UnifiedCategoryFilterSelection.Starred
		VAULT_V2_STORAGE_FILTER_UNCATEGORIZED -> UnifiedCategoryFilterSelection.Uncategorized
		VAULT_V2_STORAGE_FILTER_LOCAL_STARRED -> UnifiedCategoryFilterSelection.LocalStarred
		VAULT_V2_STORAGE_FILTER_LOCAL_UNCATEGORIZED -> UnifiedCategoryFilterSelection.LocalUncategorized
		VAULT_V2_STORAGE_FILTER_CUSTOM -> {
			storageFilterPrimaryId?.let(UnifiedCategoryFilterSelection::Custom)
				?: UnifiedCategoryFilterSelection.Local
		}
		VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE -> {
			storageFilterPrimaryId?.let(UnifiedCategoryFilterSelection::KeePassDatabaseFilter)
				?: UnifiedCategoryFilterSelection.Local
		}
		VAULT_V2_STORAGE_FILTER_KEEPASS_GROUP -> {
			val databaseId = storageFilterPrimaryId
			val groupPath = storageFilterSecondaryKey
			if (databaseId != null && !groupPath.isNullOrBlank()) {
                UnifiedCategoryFilterSelection.KeePassGroupFilter(
                    databaseId = databaseId,
                    groupPath = groupPath,
                    groupUuid = storageFilterIdentityKey
                )
			} else if (databaseId != null) {
				UnifiedCategoryFilterSelection.KeePassDatabaseFilter(databaseId)
			} else {
				UnifiedCategoryFilterSelection.Local
			}
		}
		VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_STARRED -> {
			storageFilterPrimaryId?.let(UnifiedCategoryFilterSelection::KeePassDatabaseStarredFilter)
				?: UnifiedCategoryFilterSelection.Local
		}
		VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_UNCATEGORIZED -> {
			storageFilterPrimaryId?.let(UnifiedCategoryFilterSelection::KeePassDatabaseUncategorizedFilter)
				?: UnifiedCategoryFilterSelection.Local
		}
		VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT -> {
			storageFilterPrimaryId?.let(UnifiedCategoryFilterSelection::BitwardenVaultFilter)
				?: UnifiedCategoryFilterSelection.Local
		}
		VAULT_V2_STORAGE_FILTER_BITWARDEN_FOLDER -> {
			val vaultId = storageFilterPrimaryId
			val folderId = storageFilterSecondaryKey
			if (vaultId != null && !folderId.isNullOrBlank()) {
				UnifiedCategoryFilterSelection.BitwardenFolderFilter(vaultId, folderId)
			} else if (vaultId != null) {
				UnifiedCategoryFilterSelection.BitwardenVaultFilter(vaultId)
			} else {
				UnifiedCategoryFilterSelection.Local
			}
		}
		VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_STARRED -> {
			storageFilterPrimaryId?.let(UnifiedCategoryFilterSelection::BitwardenVaultStarredFilter)
				?: UnifiedCategoryFilterSelection.Local
		}
		VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_UNCATEGORIZED -> {
			storageFilterPrimaryId?.let(UnifiedCategoryFilterSelection::BitwardenVaultUncategorizedFilter)
				?: UnifiedCategoryFilterSelection.Local
		}
		VAULT_V2_STORAGE_FILTER_MDBX_DATABASE -> {
			storageFilterPrimaryId?.let(UnifiedCategoryFilterSelection::MdbxDatabaseFilter)
				?: UnifiedCategoryFilterSelection.Local
		}
		VAULT_V2_STORAGE_FILTER_MDBX_FOLDER -> {
			val databaseId = storageFilterPrimaryId
			val folderId = storageFilterSecondaryKey
			if (databaseId != null && !folderId.isNullOrBlank()) {
				UnifiedCategoryFilterSelection.MdbxFolderFilter(databaseId, folderId)
			} else {
				UnifiedCategoryFilterSelection.Local
			}
		}
		else -> UnifiedCategoryFilterSelection.Local
	}
}

private fun VaultV2PaneState.updateStorageFilter(selection: UnifiedCategoryFilterSelection) {
	when (selection) {
		UnifiedCategoryFilterSelection.All -> updateStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
		UnifiedCategoryFilterSelection.Local -> updateStorageFilter(VAULT_V2_STORAGE_FILTER_LOCAL)
		UnifiedCategoryFilterSelection.Starred -> updateStorageFilter(VAULT_V2_STORAGE_FILTER_STARRED)
		UnifiedCategoryFilterSelection.Uncategorized -> {
			updateStorageFilter(VAULT_V2_STORAGE_FILTER_UNCATEGORIZED)
		}
		UnifiedCategoryFilterSelection.LocalStarred -> {
			updateStorageFilter(VAULT_V2_STORAGE_FILTER_LOCAL_STARRED)
		}
		UnifiedCategoryFilterSelection.LocalUncategorized -> {
			updateStorageFilter(VAULT_V2_STORAGE_FILTER_LOCAL_UNCATEGORIZED)
		}
		is UnifiedCategoryFilterSelection.Custom -> {
			updateStorageFilter(
				type = VAULT_V2_STORAGE_FILTER_CUSTOM,
				primaryId = selection.categoryId,
			)
		}
		is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> {
			updateStorageFilter(
				type = VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE,
				primaryId = selection.databaseId,
			)
		}
		is UnifiedCategoryFilterSelection.KeePassGroupFilter -> {
			updateStorageFilter(
				type = VAULT_V2_STORAGE_FILTER_KEEPASS_GROUP,
				primaryId = selection.databaseId,
				secondaryKey = selection.groupPath,
				identityKey = selection.groupUuid,
			)
		}
		is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> {
			updateStorageFilter(
				type = VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_STARRED,
				primaryId = selection.databaseId,
			)
		}
		is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> {
			updateStorageFilter(
				type = VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_UNCATEGORIZED,
				primaryId = selection.databaseId,
			)
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> {
			updateStorageFilter(
				type = VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT,
				primaryId = selection.vaultId,
			)
		}
		is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> {
			updateStorageFilter(
				type = VAULT_V2_STORAGE_FILTER_BITWARDEN_FOLDER,
				primaryId = selection.vaultId,
				secondaryKey = selection.folderId,
			)
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> {
			updateStorageFilter(
				type = VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_STARRED,
				primaryId = selection.vaultId,
			)
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> {
			updateStorageFilter(
				type = VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_UNCATEGORIZED,
				primaryId = selection.vaultId,
			)
		}
		is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> {
			updateStorageFilter(
				type = VAULT_V2_STORAGE_FILTER_MDBX_DATABASE,
				primaryId = selection.databaseId,
			)
		}
		is UnifiedCategoryFilterSelection.MdbxFolderFilter -> {
			updateStorageFilter(
				type = VAULT_V2_STORAGE_FILTER_MDBX_FOLDER,
				primaryId = selection.databaseId,
				secondaryKey = selection.folderId,
			)
		}
	}
}

internal fun UnifiedCategoryFilterSelection.toCategoryFilterOrNull(): CategoryFilter? {
	return when (this) {
		UnifiedCategoryFilterSelection.All -> CategoryFilter.All
		UnifiedCategoryFilterSelection.Local -> CategoryFilter.Local
		UnifiedCategoryFilterSelection.Starred -> CategoryFilter.Starred
		UnifiedCategoryFilterSelection.Uncategorized -> CategoryFilter.Uncategorized
		UnifiedCategoryFilterSelection.LocalStarred -> CategoryFilter.LocalStarred
		UnifiedCategoryFilterSelection.LocalUncategorized -> CategoryFilter.LocalUncategorized
		is UnifiedCategoryFilterSelection.Custom -> CategoryFilter.Custom(categoryId)
		is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> CategoryFilter.KeePassDatabase(databaseId)
		is UnifiedCategoryFilterSelection.KeePassGroupFilter -> {
			CategoryFilter.KeePassGroupFilter(databaseId, groupPath, groupUuid)
		}
		is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> {
			CategoryFilter.KeePassDatabaseStarred(databaseId)
		}
		is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> {
			CategoryFilter.KeePassDatabaseUncategorized(databaseId)
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> CategoryFilter.BitwardenVault(vaultId)
		is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> {
			CategoryFilter.BitwardenFolderFilter(folderId = folderId, vaultId = vaultId)
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> {
			CategoryFilter.BitwardenVaultStarred(vaultId)
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> {
			CategoryFilter.BitwardenVaultUncategorized(vaultId)
		}
		is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> CategoryFilter.MdbxDatabase(databaseId)
		is UnifiedCategoryFilterSelection.MdbxFolderFilter -> {
			CategoryFilter.MdbxFolderFilter(databaseId, folderId)
		}
	}
}

private fun CategoryFilter.toUnifiedCategoryFilterSelectionOrNull(): UnifiedCategoryFilterSelection? {
	return when (this) {
		is CategoryFilter.All -> UnifiedCategoryFilterSelection.All
		is CategoryFilter.Local -> UnifiedCategoryFilterSelection.Local
		is CategoryFilter.LocalOnly -> UnifiedCategoryFilterSelection.Local
		is CategoryFilter.Starred -> UnifiedCategoryFilterSelection.Starred
		is CategoryFilter.Uncategorized -> UnifiedCategoryFilterSelection.Uncategorized
		is CategoryFilter.LocalStarred -> UnifiedCategoryFilterSelection.LocalStarred
		is CategoryFilter.LocalUncategorized -> UnifiedCategoryFilterSelection.LocalUncategorized
		is CategoryFilter.Custom -> UnifiedCategoryFilterSelection.Custom(categoryId)
		is CategoryFilter.KeePassDatabase -> UnifiedCategoryFilterSelection.KeePassDatabaseFilter(databaseId)
		is CategoryFilter.KeePassGroupFilter -> {
			UnifiedCategoryFilterSelection.KeePassGroupFilter(databaseId, groupPath, groupUuid)
		}
		is CategoryFilter.KeePassDatabaseStarred -> {
			UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter(databaseId)
		}
		is CategoryFilter.KeePassDatabaseUncategorized -> {
			UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter(databaseId)
		}
		is CategoryFilter.BitwardenVault -> UnifiedCategoryFilterSelection.BitwardenVaultFilter(vaultId)
		is CategoryFilter.BitwardenFolderFilter -> {
			UnifiedCategoryFilterSelection.BitwardenFolderFilter(vaultId, folderId)
		}
		is CategoryFilter.BitwardenVaultStarred -> {
			UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter(vaultId)
		}
		is CategoryFilter.BitwardenVaultUncategorized -> {
			UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter(vaultId)
		}
		is CategoryFilter.MdbxDatabase -> UnifiedCategoryFilterSelection.MdbxDatabaseFilter(databaseId)
		is CategoryFilter.MdbxFolderFilter -> UnifiedCategoryFilterSelection.MdbxFolderFilter(databaseId, folderId)
		is CategoryFilter.Archived -> null
	}
}

private data class VaultV2SavedStorageFilter(
	val type: String,
	val primaryId: Long? = null,
	val secondaryKey: String? = null,
	val identityKey: String? = null,
)

private fun SavedCategoryFilterState.toVaultV2SavedStorageFilter(): VaultV2SavedStorageFilter {
	val savedType = type.lowercase(Locale.ROOT)
	return when (savedType) {
		VAULT_V2_STORAGE_FILTER_ALL -> VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
		"archived" -> VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
		VAULT_V2_STORAGE_FILTER_LOCAL,
		"local_only" -> VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_LOCAL)
		VAULT_V2_STORAGE_FILTER_STARRED -> VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_STARRED)
		VAULT_V2_STORAGE_FILTER_UNCATEGORIZED -> {
			VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_UNCATEGORIZED)
		}
		VAULT_V2_STORAGE_FILTER_LOCAL_STARRED -> {
			VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_LOCAL_STARRED)
		}
		VAULT_V2_STORAGE_FILTER_LOCAL_UNCATEGORIZED -> {
			VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_LOCAL_UNCATEGORIZED)
		}
		VAULT_V2_STORAGE_FILTER_CUSTOM -> {
			primaryId?.let {
				VaultV2SavedStorageFilter(type = VAULT_V2_STORAGE_FILTER_CUSTOM, primaryId = it)
			} ?: VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
		}
		VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE -> {
			primaryId?.let {
				VaultV2SavedStorageFilter(type = VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE, primaryId = it)
			} ?: VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
		}
		VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_STARRED -> {
			primaryId?.let {
				VaultV2SavedStorageFilter(
					type = VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_STARRED,
					primaryId = it
				)
			} ?: VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
		}
		VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_UNCATEGORIZED -> {
			primaryId?.let {
				VaultV2SavedStorageFilter(
					type = VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_UNCATEGORIZED,
					primaryId = it
				)
			} ?: VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
		}
		VAULT_V2_STORAGE_FILTER_KEEPASS_GROUP -> {
			val databaseId = primaryId
			val groupPath = text
			if (databaseId != null && !groupPath.isNullOrBlank()) {
				VaultV2SavedStorageFilter(
					type = VAULT_V2_STORAGE_FILTER_KEEPASS_GROUP,
					primaryId = databaseId,
					secondaryKey = groupPath,
					identityKey = groupUuid,
				)
			} else {
				VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
			}
		}
		VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT -> {
			primaryId?.let {
				VaultV2SavedStorageFilter(type = VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT, primaryId = it)
			} ?: VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
		}
		VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_STARRED -> {
			primaryId?.let {
				VaultV2SavedStorageFilter(
					type = VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_STARRED,
					primaryId = it
				)
			} ?: VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
		}
		VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_UNCATEGORIZED -> {
			primaryId?.let {
				VaultV2SavedStorageFilter(
					type = VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_UNCATEGORIZED,
					primaryId = it
				)
			} ?: VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
		}
		VAULT_V2_STORAGE_FILTER_BITWARDEN_FOLDER -> {
			val vaultId = secondaryId ?: primaryId
			val folderId = text
			if (vaultId != null && !folderId.isNullOrBlank()) {
				VaultV2SavedStorageFilter(
					type = VAULT_V2_STORAGE_FILTER_BITWARDEN_FOLDER,
					primaryId = vaultId,
					secondaryKey = folderId,
				)
			} else {
				VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
			}
		}
		VAULT_V2_STORAGE_FILTER_MDBX_DATABASE -> {
			primaryId?.let {
				VaultV2SavedStorageFilter(type = VAULT_V2_STORAGE_FILTER_MDBX_DATABASE, primaryId = it)
			} ?: VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
		}
		VAULT_V2_STORAGE_FILTER_MDBX_FOLDER -> {
			val databaseId = primaryId
			val folderId = text
			if (databaseId != null && !folderId.isNullOrBlank()) {
				VaultV2SavedStorageFilter(
					type = VAULT_V2_STORAGE_FILTER_MDBX_FOLDER,
					primaryId = databaseId,
					secondaryKey = folderId,
				)
			} else {
				VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
			}
		}
		else -> VaultV2SavedStorageFilter(VAULT_V2_STORAGE_FILTER_ALL)
	}
}

private fun VaultV2PaneState.toSavedCategoryFilterState(): SavedCategoryFilterState {
	return when (storageFilterType) {
		VAULT_V2_STORAGE_FILTER_ALL,
		VAULT_V2_STORAGE_FILTER_LOCAL,
		VAULT_V2_STORAGE_FILTER_STARRED,
		VAULT_V2_STORAGE_FILTER_UNCATEGORIZED,
		VAULT_V2_STORAGE_FILTER_LOCAL_STARRED,
		VAULT_V2_STORAGE_FILTER_LOCAL_UNCATEGORIZED -> {
			SavedCategoryFilterState(type = storageFilterType)
		}
		VAULT_V2_STORAGE_FILTER_CUSTOM,
		VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE,
		VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_STARRED,
		VAULT_V2_STORAGE_FILTER_KEEPASS_DATABASE_UNCATEGORIZED,
		VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT,
		VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_STARRED,
		VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT_UNCATEGORIZED,
		VAULT_V2_STORAGE_FILTER_MDBX_DATABASE -> {
			if (storageFilterPrimaryId != null) {
				SavedCategoryFilterState(
					type = storageFilterType,
					primaryId = storageFilterPrimaryId,
				)
			} else {
				SavedCategoryFilterState(type = VAULT_V2_STORAGE_FILTER_ALL)
			}
		}
		VAULT_V2_STORAGE_FILTER_MDBX_FOLDER -> {
			if (storageFilterPrimaryId != null && !storageFilterSecondaryKey.isNullOrBlank()) {
				SavedCategoryFilterState(
					type = VAULT_V2_STORAGE_FILTER_MDBX_FOLDER,
					primaryId = storageFilterPrimaryId,
					text = storageFilterSecondaryKey,
				)
			} else if (storageFilterPrimaryId != null) {
				SavedCategoryFilterState(
					type = VAULT_V2_STORAGE_FILTER_MDBX_DATABASE,
					primaryId = storageFilterPrimaryId,
				)
			} else {
				SavedCategoryFilterState(type = VAULT_V2_STORAGE_FILTER_ALL)
			}
		}
		VAULT_V2_STORAGE_FILTER_KEEPASS_GROUP -> {
			if (storageFilterPrimaryId != null && !storageFilterSecondaryKey.isNullOrBlank()) {
				SavedCategoryFilterState(
					type = VAULT_V2_STORAGE_FILTER_KEEPASS_GROUP,
					primaryId = storageFilterPrimaryId,
					text = storageFilterSecondaryKey,
					groupUuid = storageFilterIdentityKey,
				)
			} else {
				SavedCategoryFilterState(type = VAULT_V2_STORAGE_FILTER_ALL)
			}
		}
		VAULT_V2_STORAGE_FILTER_BITWARDEN_FOLDER -> {
			if (storageFilterPrimaryId != null && !storageFilterSecondaryKey.isNullOrBlank()) {
				SavedCategoryFilterState(
					type = VAULT_V2_STORAGE_FILTER_BITWARDEN_FOLDER,
					secondaryId = storageFilterPrimaryId,
					text = storageFilterSecondaryKey,
				)
			} else if (storageFilterPrimaryId != null) {
				SavedCategoryFilterState(
					type = VAULT_V2_STORAGE_FILTER_BITWARDEN_VAULT,
					primaryId = storageFilterPrimaryId,
				)
			} else {
				SavedCategoryFilterState(type = VAULT_V2_STORAGE_FILTER_ALL)
			}
		}
		else -> SavedCategoryFilterState(type = VAULT_V2_STORAGE_FILTER_ALL)
	}
}

@Composable
private fun <T> rememberVaultV2AsyncComputed(
	vararg keys: Any?,
	initialValue: T,
	compute: suspend () -> T,
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
private fun <T> rememberVaultV2AsyncComputedValue(
	computationKey: Any?,
	initialValue: T,
	initialHasComputed: Boolean = false,
	enabled: Boolean = true,
	compute: suspend () -> T,
): VaultV2AsyncComputedValue<T> {
	val notComputed = remember { Any() }
	var value by remember { mutableStateOf(initialValue) }
	var isComputing by remember { mutableStateOf(!initialHasComputed) }
	var computedKey by remember {
		mutableStateOf(if (initialHasComputed) computationKey else notComputed)
	}
	val latestCompute by rememberUpdatedState(compute)

	LaunchedEffect(computationKey, enabled) {
		if (!enabled) {
			isComputing = false
			return@LaunchedEffect
		}
		if (computedKey == computationKey) {
			isComputing = false
			return@LaunchedEffect
		}
		isComputing = true
		try {
			value = withContext(Dispatchers.Default) {
				latestCompute()
			}
			computedKey = computationKey
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (error: Throwable) {
			// Keep the retained value visible when one source item cannot be parsed.
			// The next source update will retry the computation without taking down the page.
			Log.e("VaultV2List", "Async list computation failed; retaining previous value", error)
		} finally {
			isComputing = false
		}
	}

	return VaultV2AsyncComputedValue(
		value = value,
		isComputing = enabled && isComputing,
		hasComputed = enabled && computedKey == computationKey,
	)
}

@Composable
private fun rememberVaultV2StorageFilterLabel(
	selected: UnifiedCategoryFilterSelection,
	categories: List<Category>,
	keepassDatabases: List<LocalKeePassDatabase>,
	mdbxDatabases: List<LocalMdbxDatabase>,
	bitwardenVaults: List<BitwardenVault>,
	bitwardenFolders: List<BitwardenFolder>,
	mdbxFolders: List<MdbxStoredFolderEntry>,
): String {
	val monica = stringResource(R.string.filter_monica)
	val context = LocalContext.current
	val bitwarden = stringResource(R.string.filter_bitwarden)
	val keepass = stringResource(R.string.filter_keepass)
	val starred = stringResource(R.string.filter_starred)
	val uncategorized = stringResource(R.string.filter_uncategorized)
	return when (selected) {
		UnifiedCategoryFilterSelection.All -> stringResource(R.string.category_all)
		UnifiedCategoryFilterSelection.Local -> monica
		UnifiedCategoryFilterSelection.Starred -> starred
		UnifiedCategoryFilterSelection.Uncategorized -> uncategorized
		UnifiedCategoryFilterSelection.LocalStarred -> "$monica · $starred"
		UnifiedCategoryFilterSelection.LocalUncategorized -> "$monica · $uncategorized"
		is UnifiedCategoryFilterSelection.Custom -> {
			val categoryLabel = categories.find { it.id == selected.categoryId }?.name
				?: stringResource(R.string.unknown_category)
			"$monica · $categoryLabel"
		}
		is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> {
			keepassDatabases.find { it.id == selected.databaseId }?.name ?: keepass
		}
		is UnifiedCategoryFilterSelection.KeePassGroupFilter -> {
			val databaseLabel = keepassDatabases.find { it.id == selected.databaseId }?.name ?: keepass
			val groupLabel = decodeKeePassPathSegments(selected.groupPath)
				.joinToString(KEEPASS_DISPLAY_PATH_SEPARATOR)
				.ifBlank { keepass }
			"$databaseLabel · $groupLabel"
		}
		is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> {
			"${keepassDatabases.find { it.id == selected.databaseId }?.name ?: keepass} · $starred"
		}
		is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> {
			"${keepassDatabases.find { it.id == selected.databaseId }?.name ?: keepass} · $uncategorized"
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> {
			bitwardenVaults.find { it.id == selected.vaultId }?.displayLabel() ?: bitwarden
		}
		is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> {
			val vaultLabel = bitwardenVaults.find { it.id == selected.vaultId }?.displayLabel() ?: bitwarden
			val folderLabel = bitwardenFolders.find { it.bitwardenFolderId == selected.folderId }?.name
			if (folderLabel.isNullOrBlank()) vaultLabel else "$vaultLabel · $folderLabel"
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> {
			"${bitwardenVaults.find { it.id == selected.vaultId }?.displayLabel() ?: bitwarden} · $starred"
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> {
			"${bitwardenVaults.find { it.id == selected.vaultId }?.displayLabel() ?: bitwarden} · $uncategorized"
		}
		is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> {
			mdbxDatabases.find { it.id == selected.databaseId }?.name ?: "MDBX"
		}
		is UnifiedCategoryFilterSelection.MdbxFolderFilter -> {
			val databaseLabel = mdbxDatabases.find { it.id == selected.databaseId }?.name ?: "MDBX"
			val folderLabel = buildMdbxFolderPathLabel(context, selected.folderId, mdbxFolders)
			if (folderLabel.isNullOrBlank()) databaseLabel else "$databaseLabel · $folderLabel"
		}
	}
}

private fun BitwardenVault.displayLabel(): String {
	return displayName?.takeIf { it.isNotBlank() } ?: email
}

internal fun VaultV2Item.matchesStorageFilter(
	selection: UnifiedCategoryFilterSelection,
	localCategoryIdsInScope: Set<Long>,
): Boolean {
	return when (selection) {
		UnifiedCategoryFilterSelection.All -> true
		UnifiedCategoryFilterSelection.Local -> isLocalOnly()
		UnifiedCategoryFilterSelection.Starred -> isFavorite
		UnifiedCategoryFilterSelection.Uncategorized -> categoryId() == null
		UnifiedCategoryFilterSelection.LocalStarred -> isLocalOnly() && isFavorite
		UnifiedCategoryFilterSelection.LocalUncategorized -> isLocalOnly() && categoryId() == null
		is UnifiedCategoryFilterSelection.Custom -> {
			val categoryIds = localCategoryIdsInScope.ifEmpty { setOf(selection.categoryId) }
			isLocalOnly() && categoryId() in categoryIds
		}
		is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> {
			keepassDatabaseId() == selection.databaseId
		}
		is UnifiedCategoryFilterSelection.KeePassGroupFilter -> {
			takagi.ru.monica.ui.KeePassGroupFilterIdentity(
				databaseId = selection.databaseId,
				groupPath = selection.groupPath,
				groupUuid = selection.groupUuid
			).matches(
				itemDatabaseId = keepassDatabaseId(),
				itemGroupPath = keepassGroupPath(),
				itemGroupUuid = keepassGroupUuid()
			)
		}
		is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> {
			keepassDatabaseId() == selection.databaseId && isFavorite
		}
		is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> {
			keepassDatabaseId() == selection.databaseId && keepassGroupPath().isNullOrBlank()
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> {
			bitwardenVaultId() == selection.vaultId
		}
		is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> {
			bitwardenVaultId() == selection.vaultId && bitwardenFolderId() == selection.folderId
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> {
			bitwardenVaultId() == selection.vaultId && isFavorite
		}
		is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> {
			bitwardenVaultId() == selection.vaultId && bitwardenFolderId().isNullOrBlank()
		}
		is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> {
			mdbxDatabaseId() == selection.databaseId
		}
		is UnifiedCategoryFilterSelection.MdbxFolderFilter -> {
			matchesMdbxFolder(selection.databaseId, selection.folderId)
		}
	}
}

internal fun VaultV2Item.isLocalOnly(): Boolean {
	return when (type) {
		VaultV2ItemType.PASSWORD -> passwordEntry?.isVaultV2LocalOnly() == true
		VaultV2ItemType.AUTHENTICATOR -> totpItem?.isVaultV2LocalOnly() == true
		VaultV2ItemType.NOTE,
		VaultV2ItemType.BANK_CARD,
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> secureItem?.isVaultV2LocalOnly() == true
		VaultV2ItemType.PASSKEY -> passkeyEntry?.isVaultV2LocalOnly() == true
	}
}

internal fun VaultV2Item.categoryId(): Long? {
	return when (type) {
		VaultV2ItemType.PASSWORD -> passwordEntry?.categoryId
		VaultV2ItemType.AUTHENTICATOR -> totpItem?.categoryId
		VaultV2ItemType.NOTE,
		VaultV2ItemType.BANK_CARD,
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> secureItem?.categoryId
		VaultV2ItemType.PASSKEY -> passkeyEntry?.categoryId
	}
}

internal fun VaultV2Item.keepassEntryUuid(): String? =
    passwordEntry?.keepassEntryUuid ?: totpItem?.keepassEntryUuid ?: secureItem?.keepassEntryUuid

internal fun VaultV2Item.keepassDatabaseId(): Long? {
	return when (type) {
		VaultV2ItemType.PASSWORD -> passwordEntry?.keepassDatabaseId
		VaultV2ItemType.AUTHENTICATOR -> totpItem?.keepassDatabaseId
		VaultV2ItemType.NOTE,
		VaultV2ItemType.BANK_CARD,
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> secureItem?.keepassDatabaseId
		VaultV2ItemType.PASSKEY -> passkeyEntry?.keepassDatabaseId
	}
}

internal fun VaultV2Item.keepassGroupPath(): String? {
	return when (type) {
		VaultV2ItemType.PASSWORD -> passwordEntry?.keepassGroupPath
		VaultV2ItemType.AUTHENTICATOR -> totpItem?.keepassGroupPath
		VaultV2ItemType.NOTE,
		VaultV2ItemType.BANK_CARD,
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> secureItem?.keepassGroupPath
		VaultV2ItemType.PASSKEY -> passkeyEntry?.keepassGroupPath
	}
}

internal fun VaultV2Item.keepassGroupUuid(): String? {
	return when (type) {
		VaultV2ItemType.PASSWORD -> passwordEntry?.keepassGroupUuid
		VaultV2ItemType.AUTHENTICATOR -> totpItem?.keepassGroupUuid
		VaultV2ItemType.NOTE,
		VaultV2ItemType.BANK_CARD,
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> secureItem?.keepassGroupUuid
		VaultV2ItemType.PASSKEY -> null
	}
}

internal fun VaultV2Item.bitwardenVaultId(): Long? {
	return when (type) {
		VaultV2ItemType.PASSWORD -> passwordEntry?.bitwardenVaultId
		VaultV2ItemType.AUTHENTICATOR -> totpItem?.bitwardenVaultId
		VaultV2ItemType.NOTE,
		VaultV2ItemType.BANK_CARD,
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> secureItem?.bitwardenVaultId
		VaultV2ItemType.PASSKEY -> passkeyEntry?.bitwardenVaultId
	}
}

internal fun VaultV2Item.bitwardenFolderId(): String? {
	return when (type) {
		VaultV2ItemType.PASSWORD -> passwordEntry?.bitwardenFolderId
		VaultV2ItemType.AUTHENTICATOR -> totpItem?.bitwardenFolderId
		VaultV2ItemType.NOTE,
		VaultV2ItemType.BANK_CARD,
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> secureItem?.bitwardenFolderId
		VaultV2ItemType.PASSKEY -> passkeyEntry?.bitwardenFolderId
	}
}

internal fun VaultV2Item.mdbxDatabaseId(): Long? {
	return when (type) {
		VaultV2ItemType.PASSWORD -> passwordEntry?.mdbxDatabaseId
		VaultV2ItemType.AUTHENTICATOR -> totpItem?.mdbxDatabaseId
		VaultV2ItemType.NOTE,
		VaultV2ItemType.BANK_CARD,
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> secureItem?.mdbxDatabaseId
		VaultV2ItemType.PASSKEY -> passkeyEntry?.mdbxDatabaseId
	}
}

internal fun VaultV2Item.mdbxFolderId(): String? {
	return when (type) {
		VaultV2ItemType.PASSWORD -> passwordEntry?.mdbxFolderId
		VaultV2ItemType.AUTHENTICATOR -> totpItem?.mdbxFolderId
		VaultV2ItemType.NOTE,
		VaultV2ItemType.BANK_CARD,
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> secureItem?.mdbxFolderId
		VaultV2ItemType.PASSKEY -> passkeyEntry?.mdbxFolderId
	}
}

internal fun VaultV2Item.matchesMdbxFolder(databaseId: Long, folderId: String): Boolean {
	if (mdbxDatabaseId() != databaseId) return false
	val normalizedFolderId = folderId.trim()
	val explicitFolderId = mdbxFolderId()?.trim().orEmpty()
	if (normalizedFolderId.equals("root", ignoreCase = true)) {
		return explicitFolderId.isBlank() && categoryId() == null
	}
	if (explicitFolderId.isNotBlank()) {
		return explicitFolderId == normalizedFolderId
	}
	val categoryIdFromFolder = normalizedFolderId
		.removePrefix("category:")
		.takeIf { it != normalizedFolderId }
		?.toLongOrNull()
	return categoryIdFromFolder != null && categoryId() == categoryIdFromFolder
}

private fun toggleVaultV2ContentType(
	currentTypes: Set<PasswordPageContentType>,
	toggledType: PasswordPageContentType,
	visibleTypes: List<PasswordPageContentType>
): Set<PasswordPageContentType> {
	val nextTypes = if (toggledType in currentTypes) {
		currentTypes - toggledType
	} else {
		currentTypes + toggledType
	}
	return sanitizeSelectedPasswordPageTypes(
		visibleTypes = visibleTypes,
		selectedTypes = nextTypes
	)
}

private fun VaultV2Item.matchesDisplayedTypes(
	displayedTypes: Set<PasswordPageContentType>,
	contentBlockTypes: Map<Long, Set<PasswordPageContentType>>
): Boolean {
	return toPasswordPageContentType() in displayedTypes ||
        passwordEntry?.let { takagi.ru.monica.data.model.matchesCredentialContentTypes(it, displayedTypes, contentBlockTypes[it.id].orEmpty()) } == true
}

private fun VaultV2Item.matchesPasswordQuickFilters(
	configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
	storageSelection: UnifiedCategoryFilterSelection,
	quickFilterFavorite: Boolean,
	quickFilter2fa: Boolean,
	quickFilterNotes: Boolean,
	quickFilterPasskey: Boolean,
	quickFilterBoundNote: Boolean,
	quickFilterAttachments: Boolean,
	activeAttachmentParentIds: Set<Long>,
	quickFilterUncategorized: Boolean,
	quickFilterLocalOnly: Boolean,
	quickFilterManualStackOnly: Boolean,
	quickFilterNeverStack: Boolean,
	quickFilterUnstacked: Boolean,
	manualStackGroupByEntryId: Map<Long, String>,
	noStackEntryIds: Set<Long>,
): Boolean {
	if (quickFilterFavorite && PasswordListQuickFilterItem.FAVORITE in configuredQuickFilterItems && !isFavorite) {
		return false
	}
	if (
		quickFilter2fa &&
		PasswordListQuickFilterItem.TWO_FA in configuredQuickFilterItems &&
		type != VaultV2ItemType.AUTHENTICATOR &&
		passwordEntry?.authenticatorKey.isNullOrBlank()
	) {
		return false
	}
	if (
		quickFilterNotes &&
		PasswordListQuickFilterItem.NOTES in configuredQuickFilterItems &&
		passwordEntry?.notes.isNullOrBlank()
	) {
		return false
	}
	if (
		quickFilterPasskey &&
		PasswordListQuickFilterItem.PASSKEY in configuredQuickFilterItems &&
		type != VaultV2ItemType.PASSKEY &&
		PasskeyBindingCodec.decodeList(passwordEntry?.passkeyBindings.orEmpty()).isEmpty()
	) {
		return false
	}
	if (
		quickFilterBoundNote &&
		PasswordListQuickFilterItem.NOTE in configuredQuickFilterItems &&
		passwordEntry?.boundNoteId == null
	) {
		return false
	}
	if (
		quickFilterAttachments &&
		PasswordListQuickFilterItem.ATTACHMENTS in configuredQuickFilterItems &&
		passwordEntry?.id !in activeAttachmentParentIds
	) {
		return false
	}
	if (
		quickFilterUncategorized &&
		PasswordListQuickFilterItem.UNCATEGORIZED in configuredQuickFilterItems &&
		!matchesUncategorizedQuickFilter(storageSelection)
	) {
		return false
	}
	if (
		quickFilterLocalOnly &&
		PasswordListQuickFilterItem.LOCAL_ONLY in configuredQuickFilterItems &&
		!isLocalOnly()
	) {
		return false
	}

	val passwordId = passwordEntry?.id
	if (
		quickFilterManualStackOnly &&
		PasswordListQuickFilterItem.MANUAL_STACK_ONLY in configuredQuickFilterItems &&
		(passwordId == null || passwordId !in manualStackGroupByEntryId)
	) {
		return false
	}
	if (
		quickFilterNeverStack &&
		PasswordListQuickFilterItem.NEVER_STACK in configuredQuickFilterItems &&
		(passwordId == null || passwordId !in noStackEntryIds)
	) {
		return false
	}
	if (
		quickFilterUnstacked &&
		PasswordListQuickFilterItem.UNSTACKED in configuredQuickFilterItems &&
		(passwordId == null || passwordId in manualStackGroupByEntryId)
	) {
		return false
	}

	return true
}

private fun VaultV2Item.matchesUncategorizedQuickFilter(
	storageSelection: UnifiedCategoryFilterSelection
): Boolean {
	return when (storageSelection) {
		is UnifiedCategoryFilterSelection.KeePassDatabaseFilter ->
			keepassDatabaseId() == storageSelection.databaseId && keepassGroupPath().isNullOrBlank()
		is UnifiedCategoryFilterSelection.BitwardenVaultFilter ->
			bitwardenVaultId() == storageSelection.vaultId && bitwardenFolderId().isNullOrBlank()
		else -> categoryId() == null
	}
}

internal fun buildVaultV2VisibleListState(
	allItems: List<VaultV2Item>,
	config: VaultV2VisibleListConfig,
): VaultV2VisibleListState {
	val filteredItems = allItems.asSequence().filter { !config.nativeOnly || it.nativeToken != null }.filter { item ->
		item.matchesStorageFilter(
			selection = config.storageSelection,
			localCategoryIdsInScope = config.localCategoryIdsInScope,
		)
	}.filter { item ->
		if (!item.matchesDisplayedTypes(config.displayedContentTypes, config.contentBlockTypes)) return@filter false
		if (!config.isArchiveView && config.overviewItemType != null && item.type != config.overviewItemType) return@filter false
		if (
			!config.isArchiveView &&
			!item.matchesPasswordQuickFilters(
				configuredQuickFilterItems = config.configuredQuickFilterItems,
				storageSelection = config.storageSelection,
				quickFilterFavorite = config.quickFilterFavorite,
				quickFilter2fa = config.quickFilter2fa,
				quickFilterNotes = config.quickFilterNotes,
				quickFilterPasskey = config.quickFilterPasskey,
				quickFilterBoundNote = config.quickFilterBoundNote,
				quickFilterAttachments = config.quickFilterAttachments,
				activeAttachmentParentIds = config.activeAttachmentParentIds,
				quickFilterUncategorized = config.quickFilterUncategorized,
				quickFilterLocalOnly = config.quickFilterLocalOnly,
				quickFilterManualStackOnly = config.quickFilterManualStackOnly,
				quickFilterNeverStack = config.quickFilterNeverStack,
				quickFilterUnstacked = config.quickFilterUnstacked,
				manualStackGroupByEntryId = config.manualStackGroupByEntryId,
				noStackEntryIds = config.noStackEntryIds,
			)
		) return@filter false

		config.normalizedQuery.isBlank() || item.searchableValues.any { value ->
			value.contains(config.normalizedQuery, ignoreCase = true)
		}
	}.toList()
	val sortedItems = sortVaultV2Items(filteredItems, config.sort)
	val sectionedItems = buildVaultV2SortedSections(sortedItems, config.sort)
	var itemStartIndex = 0
	var lazyIndex = 0
	val sectionLayouts = sectionedItems.map { (sectionTitle, itemsInSection) ->
		VaultV2SectionLayout(
			title = sectionTitle,
			items = itemsInSection,
			itemStartIndex = itemStartIndex,
			firstItemLazyIndex = lazyIndex + 1,
		).also {
			itemStartIndex += itemsInSection.size
			lazyIndex += itemsInSection.size + 1
		}
	}
	return VaultV2VisibleListState(
		filteredItems = sortedItems,
		sectionedItems = sectionedItems,
		sectionLayouts = sectionLayouts,
	)
}

internal fun buildVaultV2DisplayListState(
	computedItems: List<VaultV2Item>,
	currentPasswordEntries: List<PasswordEntry>,
	config: VaultV2VisibleListConfig,
): VaultV2DisplayListState {
	return buildVaultV2DisplayListStateFromItems(
		computedItems = computedItems,
		currentPasswordItems = buildVaultV2PasswordItems(currentPasswordEntries),
		config = config,
	)
}

private fun buildVaultV2DisplayListStateFromItems(
	computedItems: List<VaultV2Item>,
	currentPasswordItems: List<VaultV2Item>,
	config: VaultV2VisibleListConfig,
): VaultV2DisplayListState {
	val allItemsRaw = mergeVaultV2ImmediatePasswordItems(
		computedItems = computedItems,
		currentPasswordItems = currentPasswordItems,
	)
	return VaultV2DisplayListState(
		allItemsRaw = allItemsRaw,
		visibleListState = buildVaultV2VisibleListState(
			allItems = allItemsRaw,
			config = config,
		),
	)
}

internal fun buildVaultV2InitialDisplayListState(
	currentPasswordEntries: List<PasswordEntry>,
	config: VaultV2VisibleListConfig,
	retainedComputedItems: List<VaultV2Item>?,
	retainedVisibleListState: VaultV2VisibleListState?,
): VaultV2DisplayListState {
	if (retainedComputedItems != null && retainedVisibleListState != null) {
		return VaultV2DisplayListState(
			allItemsRaw = retainedComputedItems,
			visibleListState = retainedVisibleListState,
		)
	}
	return buildVaultV2DisplayListState(
		computedItems = retainedVisibleListState?.filteredItems.orEmpty(),
		currentPasswordEntries = currentPasswordEntries,
		config = config,
	)
}

private fun buildVaultV2InitialDisplayListStateFromItems(
	currentPasswordItems: Lazy<List<VaultV2Item>>,
	config: VaultV2VisibleListConfig,
	retainedComputedItems: List<VaultV2Item>?,
	retainedVisibleListState: VaultV2VisibleListState?,
): VaultV2DisplayListState {
	if (retainedComputedItems != null && retainedVisibleListState != null) {
		return VaultV2DisplayListState(
			allItemsRaw = retainedComputedItems,
			visibleListState = retainedVisibleListState,
		)
	}
	return buildVaultV2DisplayListStateFromItems(
		computedItems = retainedVisibleListState?.filteredItems.orEmpty(),
		currentPasswordItems = currentPasswordItems.value,
		config = config,
	)
}

@OptIn(
	ExperimentalMaterial3Api::class,
	ExperimentalFoundationApi::class,
)
@Composable
fun VaultV2Pane(
	passwordViewModel: PasswordViewModel,
	totpViewModel: TotpViewModel,
	bankCardViewModel: BankCardViewModel,
	documentViewModel: DocumentViewModel,
	billingAddressViewModel: takagi.ru.monica.viewmodel.BillingAddressViewModel,
	noteViewModel: NoteViewModel,
	passkeyViewModel: PasskeyViewModel,
	keepassDatabases: List<LocalKeePassDatabase>,
	mdbxDatabases: List<LocalMdbxDatabase>,
	bitwardenVaults: List<BitwardenVault>,
	localKeePassViewModel: LocalKeePassViewModel,
	mdbxViewModel: MdbxViewModel? = null,
	settingsViewModel: SettingsViewModel,
	state: VaultV2PaneState,
	onOpenPassword: (Long) -> Unit,
	onOpenTotp: (Long) -> Unit,
	onOpenBankCard: (Long) -> Unit,
	onOpenDocument: (Long) -> Unit,
	onOpenBillingAddress: (Long) -> Unit,
	onOpenNote: (Long) -> Unit,
	onOpenPasskey: (Long) -> Unit,
	onOpenApiTokens: (Long?, String?) -> Unit = { _, _ -> },
	onOpenMdbxCommitHistory: (Long) -> Unit,
	onOpenHistory: () -> Unit,
	onOpenTrashPage: () -> Unit,
	onOpenScopedTrashPage: ((String) -> Unit)? = null,
	onOpenArchivePage: () -> Unit,
	onOpenCommonAccountTemplates: () -> Unit,
	onScanFidoQr: () -> Unit = {},
	onOpenStandaloneSettings: () -> Unit = {},
	showStandaloneSettingsEntry: Boolean = false,
	useEmbeddedHistoryPages: Boolean = true,
	showOnlyLocalData: Boolean = false,
	isDetailVisible: Boolean = false,
	appSettings: AppSettings = AppSettings(),
	securityManager: SecurityManager,
	biometricEnabled: Boolean,
	modifier: Modifier = Modifier,
) {
	// 内嵌历史/回收站页面状态（不切换底部 tab）
	// 0 = 无, 1 = 时间线, 2 = 回收站
	val showOverview = appSettings.vaultOverviewEnabled && !state.overviewListOpen && !state.isArchiveView
	val overviewListState = rememberLazyListState(state.overviewScrollIndex, state.overviewScrollOffset)
	var vaultHistoryPageMode by rememberSaveable { mutableStateOf(0) }
	var vaultHistoryTrashScope by rememberSaveable { mutableStateOf<String?>(null) }
	val timelineViewModel: takagi.ru.monica.viewmodel.TimelineViewModel = viewModel()
	BackHandler(enabled = vaultHistoryPageMode != 0) {
		vaultHistoryPageMode = 0
	}

	// 历史/回收站页面优先渲染，覆盖整个 VaultV2Pane
	if (useEmbeddedHistoryPages && vaultHistoryPageMode != 0) {
		takagi.ru.monica.ui.screens.TimelineScreen(
			viewModel = timelineViewModel,
			onLogSelected = {},
			splitPaneMode = false,
			initialTab = if (vaultHistoryPageMode == 2) {
				takagi.ru.monica.ui.screens.HistoryTab.TRASH
			} else {
				takagi.ru.monica.ui.screens.HistoryTab.TIMELINE
			},
			initialTrashScopeKey = vaultHistoryTrashScope,
			enableTabSwitch = false,
			showBackButton = true,
			onNavigateBack = { vaultHistoryPageMode = 0 }
		)
		return
	}

	// 覆盖外部传入的导航回调，改为在 VaultV2 内部处理
	val handleOpenHistory: () -> Unit = if (useEmbeddedHistoryPages) {
		{ vaultHistoryPageMode = 1 }
	} else {
		onOpenHistory
	}
	val handleOpenTrashPage: () -> Unit = if (useEmbeddedHistoryPages) {
		{
			vaultHistoryTrashScope = state.toUnifiedCategoryFilterSelection().toTrashScopeFilter().key
			vaultHistoryPageMode = 2
		}
	} else {
		{ onOpenScopedTrashPage?.invoke(state.toUnifiedCategoryFilterSelection().toTrashScopeFilter().key) ?: onOpenTrashPage() }
	}
	val handleOpenArchivePage: () -> Unit = { state.openArchiveView() }

	var searchQuery by rememberSaveable { mutableStateOf("") }
	var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
	var isStorageFilterSheetVisible by rememberSaveable { mutableStateOf(false) }
	var isTopActionsMenuExpanded by rememberSaveable { mutableStateOf(false) }
	var showSortSheet by rememberSaveable { mutableStateOf(false) }
	var showBitwardenUnlockDialog by rememberSaveable { mutableStateOf(false) }
	var showClearBitwardenCacheDialog by rememberSaveable { mutableStateOf(false) }
	var nativeOnly by rememberSaveable { mutableStateOf(false) }
	var quickFilterFavorite by rememberSaveable { mutableStateOf(state.overviewFavorites) }
	var quickFilter2fa by rememberSaveable { mutableStateOf(false) }
	var quickFilterNotes by rememberSaveable { mutableStateOf(false) }
	var quickFilterPasskey by rememberSaveable { mutableStateOf(false) }
	var quickFilterBoundNote by rememberSaveable { mutableStateOf(false) }
	var quickFilterAttachments by rememberSaveable { mutableStateOf(false) }
	var quickFilterUncategorized by rememberSaveable { mutableStateOf(false) }
	var quickFilterLocalOnly by rememberSaveable { mutableStateOf(false) }
	var quickFilterManualStackOnly by rememberSaveable { mutableStateOf(false) }
	var quickFilterNeverStack by rememberSaveable { mutableStateOf(false) }
	var quickFilterUnstacked by rememberSaveable { mutableStateOf(false) }
	var selectedAggregateTypes by rememberSaveable(
		stateSaver = passwordPageContentTypeSetSaver
	) {
		mutableStateOf(emptySet())
	}
	val selectedKeys = remember { mutableStateListOf<String>() }
	val overviewSelection = remember { VaultOverviewSelectionState(selectedKeys) }
	LaunchedEffect(showOverview) { overviewSelection.clear() }
	fun resetOverviewFilters() {
		quickFilterFavorite = false
		quickFilter2fa = false; quickFilterNotes = false; quickFilterPasskey = false
		quickFilterBoundNote = false; quickFilterAttachments = false; quickFilterUncategorized = false
		quickFilterLocalOnly = false; quickFilterManualStackOnly = false; quickFilterNeverStack = false; quickFilterUnstacked = false
		selectedAggregateTypes = emptySet()
		selectedKeys.clear()
		searchQuery = ""; isSearchExpanded = false
		state.overviewItemType = null; state.overviewFavorites = false
	}
	fun openOverviewList(selection: UnifiedCategoryFilterSelection = state.toUnifiedCategoryFilterSelection(),
		type: VaultV2ItemType? = null, favorites: Boolean = false) {
		resetOverviewFilters()
		state.overviewListOpen = true
		state.overviewItemType = type?.name
		state.overviewFavorites = favorites
		quickFilterFavorite = favorites
		state.clearFolderNavigationHistory()
		state.updateStorageFilter(selection)
		state.requestScrollToTop()
	}
	fun closeOverviewList() {
		if (state.isArchiveView) state.closeArchiveView(scrollToTop = false)
		resetOverviewFilters()
		state.overviewListOpen = false
		state.clearFolderNavigationHistory()
		state.updateStorageFilter(overviewScopeSelection(state.overviewScope ?: appSettings.vaultOverviewConfig.scope))
	}
	LaunchedEffect(overviewListState) {
		snapshotFlow { overviewListState.firstVisibleItemIndex to overviewListState.firstVisibleItemScrollOffset }
			.collect { (index, offset) -> state.overviewScrollIndex = index; state.overviewScrollOffset = offset }
	}
	var showDeleteConfirmDialog by remember { mutableStateOf(false) }
	var categoryFolderTransferRunning by remember { mutableStateOf(false) }
	var categoryFolderTransferFailure by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
	var pendingFolderScrollRestore by remember {
		mutableStateOf<VaultV2FolderNavigationEntry?>(null)
	}
	var observedLayoutModeName by rememberSaveable {
		mutableStateOf(appSettings.vaultV2LayoutMode.name)
	}
	var observedArchiveView by remember { mutableStateOf(state.isArchiveView) }
	LaunchedEffect(state.isArchiveView) {
		if (observedArchiveView == state.isArchiveView) return@LaunchedEffect
		observedArchiveView = state.isArchiveView
		selectedKeys.clear()
		state.clearFolderNavigationHistory()
		pendingFolderScrollRestore = null
		isStorageFilterSheetVisible = false
		isTopActionsMenuExpanded = false
		isSearchExpanded = false
		searchQuery = ""
	}
	val listState = rememberLazyListState(
		initialFirstVisibleItemIndex = state.scrollIndex,
		initialFirstVisibleItemScrollOffset = state.scrollOffset
	)
	val context = LocalContext.current
	val quickStatusTransferState by PasswordBatchTransferProgressTracker.progress.collectAsState()
	val quickStatusDeleteState by PasswordBatchDeleteProgressTracker.progress.collectAsState()
	val database = remember(context) { PasswordDatabase.getDatabase(context) }
	val isAuthenticated by passwordViewModel.isAuthenticated.collectAsState()
	if (showSortSheet && isAuthenticated && !showOverview) {
		VaultV2SortSheet(
			sort = appSettings.vaultListSort,
			onSelect = { sort ->
				showSortSheet = false
				if (sort != appSettings.vaultListSort) {
					settingsViewModel.updateVaultListSort(sort)
					state.requestScrollToTop()
				}
			},
			onDismiss = { showSortSheet = false },
		)
	}
	LaunchedEffect(isAuthenticated) { if (!isAuthenticated) showSortSheet = false }
	val attachmentParentIds by database.attachmentDao()
		.observeParentsWithActiveAttachments()
		.collectAsState(initial = emptyList())
	val activeAttachmentParentIds = remember(attachmentParentIds) { attachmentParentIds.toSet() }
	val density = LocalDensity.current
	val scope = rememberCoroutineScope()
    val aggregateStackRepository = remember(database) {
        takagi.ru.monica.repository.PasswordPageAggregateStackRepository(database.passwordPageAggregateStackDao())
    }
    val aggregateStackEntries by aggregateStackRepository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var showStackDialog by remember { mutableStateOf(false) }
    var selectedStackMode by remember { mutableStateOf(takagi.ru.monica.ui.ManualStackDialogMode.STACK) }
	val bitwardenViewModel: BitwardenViewModel = viewModel()
	val bitwardenRepository = remember(context) {
		takagi.ru.monica.bitwarden.repository.BitwardenRepository.getInstance(context)
	}
	val bitwardenSyncStatusByVault by bitwardenViewModel.syncStatusByVault.collectAsState()
	val pullSearchTriggerDistance = remember(density) { with(density) { PullSearchDefaults.TriggerDistance.toPx() } }
	val pullSyncTriggerDistance = remember(density) { with(density) { 72.dp.toPx() } }
	val pullMaxDragDistance = remember(density) { with(density) { 100.dp.toPx() } }
	val pullAction = rememberPullActionState(
		isBitwardenDatabaseView = false,
		isSearchExpanded = isSearchExpanded,
		searchTriggerDistance = pullSearchTriggerDistance,
		syncTriggerDistance = pullSyncTriggerDistance,
		maxDragDistance = pullMaxDragDistance,
		bitwardenRepository = bitwardenRepository,
		onSearchTriggered = { isSearchExpanded = true },
	)
	val stackCardMode = remember(appSettings.stackCardMode) {
		runCatching { StackCardMode.valueOf(appSettings.stackCardMode) }.getOrDefault(StackCardMode.AUTO)
	}

	val passwordEntries by passwordViewModel.allPasswordsForUi.collectAsState()
	val passwordsReady by passwordViewModel.allPasswordsForUiReady.collectAsState()
	val archivedPasswordEntries by passwordViewModel.archivedPasswordsForUi.collectAsState()
	val archivedPasswordsReady by passwordViewModel.archivedPasswordsForUiReady.collectAsState()
	val categories by passwordViewModel.categories.collectAsState()
	var showCreateCategoryDialog by remember { mutableStateOf(false) }
	var selectedFolderKey by remember { mutableStateOf<String?>(null) }
	var folderActionCategory by remember { mutableStateOf<Category?>(null) }
	var folderActionRow by remember { mutableStateOf<VaultV2FolderRowModel?>(null) }
	var showFolderActionDialog by remember { mutableStateOf(false) }
	var showFolderMoveSheet by remember { mutableStateOf(false) }
	var showFolderDeleteConfirmDialog by remember { mutableStateOf(false) }
	LaunchedEffect(state.hasPendingCreateFolderDialogRequest) {
		if (state.consumeCreateFolderDialogRequest()) {
			showCreateCategoryDialog = true
		}
	}
	val totpItems by totpViewModel.allTotpItems.collectAsState()
	val bankCardItems by bankCardViewModel.allCards.collectAsState()
	val documentItems by documentViewModel.allDocuments.collectAsState()
	val billingAddressItems by billingAddressViewModel.allBillingAddresses.collectAsState()
	val noteItems by noteViewModel.allNotes.collectAsState()
	val passkeyItems by passkeyViewModel.allPasskeys.collectAsState()

	fun transferCategoryFolder(
		category: takagi.ru.monica.data.Category,
		target: UnifiedMoveCategoryTarget,
		action: UnifiedMoveAction,
	) {
		if (categoryFolderTransferRunning) {
			Toast.makeText(
				context,
				context.getString(R.string.category_folder_transfer_running),
				Toast.LENGTH_SHORT,
			).show()
			return
		}
		categoryFolderTransferRunning = true
		val targetLabel = buildMoveTargetLabel(
			context = context,
			target = target,
			categories = categories,
			keepassDatabases = keepassDatabases,
			mdbxDatabases = mdbxDatabases,
		)
		scope.launch {
			try {
				val result = executePasswordCategoryFolderTransfer(
					context = context,
					action = action,
					sourceCategory = category,
					selectedTarget = target,
					categories = categories,
					passwords = passwordEntries,
					secureItems = passwordViewModel.getAllActiveSecureItemsAwait(),
					passkeys = passkeyItems,
					keepassDatabases = keepassDatabases,
					mdbxDatabases = mdbxDatabases,
					bitwardenVaults = bitwardenVaults,
					localKeePassViewModel = localKeePassViewModel,
					securityManager = securityManager,
					passwordViewModel = passwordViewModel,
					aggregateViewModels = PasswordBatchMoveViewModels(
						totpViewModel = totpViewModel,
						bankCardViewModel = bankCardViewModel,
						documentViewModel = documentViewModel,
						billingAddressViewModel = billingAddressViewModel,
						noteViewModel = noteViewModel,
						passkeyViewModel = passkeyViewModel,
					),
					bitwardenRepository = bitwardenRepository,
					onProgress = { processed, total ->
						PasswordBatchTransferProgressTracker.update(
							action = action,
							targetLabel = targetLabel,
							processed = processed,
							total = total,
						)
					},
				)
				if (result.isCompleteSuccess) {
					PasswordBatchTransferProgressTracker.complete(
						action = action,
						targetLabel = targetLabel,
						successCount = result.successCount.coerceAtLeast(result.folderCount),
					)
					Toast.makeText(
						context,
						context.getString(
							R.string.category_folder_transfer_success,
							result.folderCount,
							result.successCount,
						),
						Toast.LENGTH_SHORT,
					).show()
				} else {
					PasswordBatchTransferProgressTracker.clear()
					categoryFolderTransferFailure = Triple(
						result.successCount,
						result.failedCount,
						result.failureMessages.joinToString("\n").take(1200),
					)
				}
			} catch (error: Exception) {
				PasswordBatchTransferProgressTracker.clear()
				categoryFolderTransferFailure = Triple(
					0,
					0,
					error.message ?: error::class.java.simpleName,
				)
			} finally {
				categoryFolderTransferRunning = false
			}
		}
	}
	val savedCategoryFilterFlow = remember(settingsViewModel) {
		settingsViewModel.categoryFilterStateFlow(VAULT_V2_CATEGORY_FILTER_SCOPE)
			.map<SavedCategoryFilterState, SavedCategoryFilterState?> { it }
	}
	val savedCategoryFilterState by savedCategoryFilterFlow.collectAsState(initial = null)
	val fastScrollRequestKey = state.fastScrollRequestKey
	LaunchedEffect(
		state,
		state.hasInitializedStorageFilter,
		savedCategoryFilterState,
		appSettings.vaultOverviewEnabled,
	) {
		if (appSettings.vaultOverviewEnabled) {
			if (state.overviewScope == null) state.overviewScope = appSettings.vaultOverviewConfig.scope
			if (!state.overviewListOpen && !state.isArchiveView) {
				state.updateStorageFilter(overviewScopeSelection(state.overviewScope!!))
			}
			return@LaunchedEffect
		}
		if (state.hasInitializedStorageFilter && state.overviewScope == null) return@LaunchedEffect
		val persistedFilter = savedCategoryFilterState ?: return@LaunchedEffect
		if (state.overviewScope != null) {
			if (state.isArchiveView) state.closeArchiveView(scrollToTop = false)
			resetOverviewFilters()
			state.overviewListOpen = false
			state.overviewScope = null
			state.clearFolderNavigationHistory()
		}
		val restoredFilter = persistedFilter.toVaultV2SavedStorageFilter()
		state.updateStorageFilter(
			type = restoredFilter.type,
			primaryId = restoredFilter.primaryId,
			secondaryKey = restoredFilter.secondaryKey,
			identityKey = restoredFilter.identityKey,
		)
	}
	LaunchedEffect(state.hasInitializedStorageFilter, appSettings.vaultOverviewEnabled) {
		if (!state.hasInitializedStorageFilter || appSettings.vaultOverviewEnabled) return@LaunchedEffect
		snapshotFlow {
			listOf(
				state.storageFilterType,
				state.storageFilterPrimaryId,
				state.storageFilterSecondaryKey,
				state.storageFilterIdentityKey,
			)
		}.distinctUntilChanged()
		 .drop(1) // skip the initial value (matches what Block A just restored)
		 .collect {
			val savedState = state.toSavedCategoryFilterState()
			settingsViewModel.updateCategoryFilterState(
				scope = VAULT_V2_CATEGORY_FILTER_SCOPE,
				state = savedState,
			)
		}
	}
	val storageSelection = remember(
		state.storageFilterType,
		state.storageFilterPrimaryId,
		state.storageFilterSecondaryKey,
		state.storageFilterIdentityKey,
	) {
		state.toUnifiedCategoryFilterSelection()
	}
	val nativeKeePassScope = storageSelection.toCategoryFilterOrNull()
		?.let { takagi.ru.monica.keepass.KeePassPasswordNativeScope.from(it) }
	if (!showOverview && !state.isArchiveView && nativeKeePassScope != null) {
		DisposableEffect(nativeKeePassScope.databaseId) {
			val nativeRead = passwordViewModel.holdNativeKeePassRead(nativeKeePassScope.databaseId)
			val lockVersion = localKeePassViewModel.nativeLockVersions.value[nativeKeePassScope.databaseId] ?: 0L
			onDispose {
				nativeRead.close()
				if ((localKeePassViewModel.nativeLockVersions.value[nativeKeePassScope.databaseId] ?: 0L) == lockVersion) {
					passwordViewModel.refreshKeePassOverviewAfterNativeRead(nativeKeePassScope.databaseId)
				}
			}
		}
	}
	val localCategoryIdsInScope = remember(
		storageSelection,
		categories,
	) {
		val selectedCategoryId = (storageSelection as? UnifiedCategoryFilterSelection.Custom)?.categoryId
		if (selectedCategoryId == null) {
			emptySet()
		} else {
			resolveLocalCategoryIdsInScope(
				categories = categories,
				selectedCategoryId = selectedCategoryId,
				// Password Library follows folder navigation semantics: child-folder
				// content appears only after entering that child folder.
				includeDescendants = false,
			)
		}
	}
	val selectedBitwardenVaultId = remember(storageSelection) {
		when (storageSelection) {
			is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> storageSelection.vaultId
			is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> storageSelection.vaultId
			else -> null
		}
	}
	LaunchedEffect(isAuthenticated) {
		if (!isAuthenticated) {
			isTopActionsMenuExpanded = false
			isStorageFilterSheetVisible = false
			showBitwardenUnlockDialog = false
			showClearBitwardenCacheDialog = false
		}
	}
	LaunchedEffect(selectedBitwardenVaultId) {
		if (selectedBitwardenVaultId == null) {
			isTopActionsMenuExpanded = false
			showBitwardenUnlockDialog = false
			showClearBitwardenCacheDialog = false
		}
	}
	BitwardenAutoSyncEffect(
		viewModel = bitwardenViewModel,
		selectedVaultId = selectedBitwardenVaultId,
		isAllView = storageSelection is UnifiedCategoryFilterSelection.All,
		enabled = state.hasInitializedStorageFilter && !showOverview,
	)
	DisposableEffect(Unit) {
		onDispose {
			isTopActionsMenuExpanded = false
			isStorageFilterSheetVisible = false
			showBitwardenUnlockDialog = false
			showClearBitwardenCacheDialog = false
		}
	}

	// 防御：如果当前筛选指向已删除的 Bitwarden vault，自动重置为 All
	LaunchedEffect(selectedBitwardenVaultId, bitwardenVaults) {
		if (selectedBitwardenVaultId != null && bitwardenVaults.none { it.id == selectedBitwardenVaultId }) {
			state.clearFolderNavigationHistory()
			state.updateStorageFilter(UnifiedCategoryFilterSelection.All)
		}
	}
	val selectedKeePassDatabaseId = remember(storageSelection) {
		when (storageSelection) {
			is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> storageSelection.databaseId
			is UnifiedCategoryFilterSelection.KeePassGroupFilter -> storageSelection.databaseId
			is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> storageSelection.databaseId
			is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> storageSelection.databaseId
			else -> null
		}
	}
	val selectedMdbxDatabaseId = remember(storageSelection) {
		when (storageSelection) {
			is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> storageSelection.databaseId
			is UnifiedCategoryFilterSelection.MdbxFolderFilter -> storageSelection.databaseId
			else -> null
		}
	}
	val selectedMdbxDatabase = remember(selectedMdbxDatabaseId, mdbxDatabases) {
		selectedMdbxDatabaseId?.let { databaseId ->
			mdbxDatabases.find { it.id == databaseId }
		}
	}
	val mdbxOperationState by (
		mdbxViewModel?.operationState
			?: flowOf(MdbxViewModel.OperationState.Idle)
	).collectAsState(initial = MdbxViewModel.OperationState.Idle)
	val mdbxPendingSyncCounts by remember(mdbxViewModel) {
		mdbxViewModel?.pendingSyncCounts ?: flowOf(emptyMap<Long, Int>())
	}.collectAsState(initial = emptyMap())
	val mdbxQuickStatusSyncState = remember(
		selectedMdbxDatabase,
		mdbxOperationState,
		mdbxPendingSyncCounts,
		mdbxViewModel
	) {
		val database = selectedMdbxDatabase
		val viewModel = mdbxViewModel
        if (
            database != null &&
            viewModel != null &&
            database.supports(MdbxCapability.REMOTE_SYNC)
        ) {
			MdbxPathSyncState(
				databaseId = database.id,
                lastSyncStatus = database.lastSyncStatus,
				pendingCount = database.mdbxPathPendingSyncCount(mdbxPendingSyncCounts[database.id]),
				isSyncing = mdbxOperationState is MdbxViewModel.OperationState.Loading,
				onSync = {
					if (database.mdbxPathShouldFlushPendingUpload()) {
						viewModel.flushPendingVaultUpload(database.id)
					} else {
						viewModel.syncVault(database.id)
					}
				}
			)
		} else {
			null
		}
	}
	LaunchedEffect(selectedMdbxDatabaseId, mdbxDatabases.map { it.id }, showOverview) {
		if (showOverview) return@LaunchedEffect
		selectedMdbxDatabaseId?.let { databaseId ->
			if (mdbxDatabases.any { it.id == databaseId }) {
				mdbxViewModel?.activateMdbxDatabase(databaseId)
				passwordViewModel.refreshMdbxFolders(databaseId)
				mdbxViewModel?.autoSyncVisibleVault(databaseId)
			}
		}
	}
	LaunchedEffect(selectedKeePassDatabaseId, keepassDatabases.map { it.id }, showOverview) {
		if (showOverview) return@LaunchedEffect
		val databaseId = selectedKeePassDatabaseId ?: return@LaunchedEffect
		if (keepassDatabases.none { it.id == databaseId }) {
			state.clearFolderNavigationHistory()
			state.updateStorageFilter(UnifiedCategoryFilterSelection.All)
			return@LaunchedEffect
		}
		passwordViewModel.syncKeePassDatabaseForVisibleVault(databaseId)
		bankCardViewModel.syncKeePassCards(databaseId)
		documentViewModel.syncKeePassDocuments(databaseId)
		noteViewModel.syncKeePassNotes(databaseId)
	}
	val isTopBarSyncing = selectedBitwardenVaultId?.let { vaultId ->
		bitwardenSyncStatusByVault[vaultId].isUserVisibleSyncInProgress()
	} == true
	var clearCacheRiskSummary by remember {
		mutableStateOf<BitwardenRepository.VaultCacheRiskSummary?>(null)
	}
	var isBitwardenMaintenanceActionRunning by remember { mutableStateOf(false) }
	val selectedBitwardenVault = selectedBitwardenVaultId?.let { vaultId ->
		bitwardenVaults.find { it.id == vaultId }
	}
	val selectedBitwardenFoldersFlow = remember(passwordViewModel, selectedBitwardenVaultId) {
		selectedBitwardenVaultId?.let(passwordViewModel::getBitwardenFolders) ?: flowOf(emptyList())
	}
	val selectedBitwardenFolders by selectedBitwardenFoldersFlow.collectAsState(initial = emptyList())
	val selectedMdbxFoldersFlow = remember(passwordViewModel, selectedMdbxDatabaseId) {
		selectedMdbxDatabaseId?.let(passwordViewModel::getMdbxFolders) ?: flowOf(emptyList())
	}
	val selectedMdbxFolders by selectedMdbxFoldersFlow.collectAsState(initial = emptyList())
	val quickFolderNodes = remember(categories) { buildPasswordQuickFolderNodes(categories) }
	val quickFolderNodeByPath = remember(quickFolderNodes) { quickFolderNodes.associateBy { it.path } }
	val breadcrumbCategoryFilter = remember(storageSelection) {
		storageSelection.toCategoryFilterOrNull()
	}
	val quickFolderCurrentPath = remember(breadcrumbCategoryFilter, quickFolderNodes) {
		when (val filter = breadcrumbCategoryFilter) {
			is CategoryFilter.Custom -> quickFolderNodes.firstOrNull { it.category.id == filter.categoryId }?.path
			else -> null
		}
	}
	val breadcrumbRootFilter = remember(breadcrumbCategoryFilter) {
		when (breadcrumbCategoryFilter) {
			is CategoryFilter.Custom,
			is CategoryFilter.Local,
			is CategoryFilter.LocalStarred,
			is CategoryFilter.LocalUncategorized -> CategoryFilter.Local
			else -> CategoryFilter.All
		}
	}
	val pathBreadcrumbs = rememberVaultV2AsyncComputed(
		breadcrumbCategoryFilter,
		quickFolderCurrentPath,
		quickFolderNodeByPath,
		keepassDatabases,
		bitwardenVaults,
		selectedBitwardenFolders,
		selectedMdbxFolders,
		categories,
		initialValue = emptyList<PasswordQuickFolderBreadcrumb>()
	) {
		val currentFilter = breadcrumbCategoryFilter ?: return@rememberVaultV2AsyncComputed emptyList()
		buildQuickFolderBreadcrumbs(
			context = context,
			quickFolderPathBannerEnabledForCurrentFilter = true,
			currentFilter = currentFilter,
			quickFolderCurrentPath = quickFolderCurrentPath,
			quickFolderNodeByPath = quickFolderNodeByPath,
			quickFolderRootFilter = breadcrumbRootFilter,
			keepassDatabases = keepassDatabases,
			mdbxDatabases = mdbxDatabases,
			selectedMdbxFolders = selectedMdbxFolders,
			bitwardenVaults = bitwardenVaults,
			selectedBitwardenFolders = selectedBitwardenFolders,
			categories = categories,
		)
	}
	val storageFilterLabel = rememberVaultV2StorageFilterLabel(
		selected = storageSelection,
		categories = categories,
		keepassDatabases = keepassDatabases,
		mdbxDatabases = mdbxDatabases,
		bitwardenVaults = bitwardenVaults,
		bitwardenFolders = selectedBitwardenFolders,
		mdbxFolders = selectedMdbxFolders,
	)

	val sourcePasswordEntries = if (state.isArchiveView) archivedPasswordEntries else passwordEntries
	val selectedPasswordEntriesReady = if (state.isArchiveView) archivedPasswordsReady else passwordsReady
	val visiblePasswordEntries = remember(sourcePasswordEntries, showOnlyLocalData) {
		if (showOnlyLocalData) {
			sourcePasswordEntries.filter { it.isVaultV2LocalOnly() }
		} else {
			sourcePasswordEntries
		}
	}
	val visibleTotpItems = remember(totpItems, showOnlyLocalData, state.isArchiveView) {
		if (state.isArchiveView) emptyList() else if (showOnlyLocalData) totpItems.filter { it.isVaultV2LocalOnly() } else totpItems
	}
	val visibleBankCardItems = remember(bankCardItems, showOnlyLocalData, state.isArchiveView) {
		if (state.isArchiveView) emptyList() else if (showOnlyLocalData) bankCardItems.filter { it.isVaultV2LocalOnly() } else bankCardItems
	}
	val visibleDocumentItems = remember(documentItems, showOnlyLocalData, state.isArchiveView) {
		if (state.isArchiveView) emptyList() else if (showOnlyLocalData) documentItems.filter { it.isVaultV2LocalOnly() } else documentItems
	}
	val visibleNoteItems = remember(noteItems, showOnlyLocalData, state.isArchiveView) {
		if (state.isArchiveView) emptyList() else if (showOnlyLocalData) noteItems.filter { it.isVaultV2LocalOnly() } else noteItems
	}
	val visibleBillingAddressItems = remember(billingAddressItems, showOnlyLocalData, state.isArchiveView) {
		if (state.isArchiveView) emptyList() else if (showOnlyLocalData) billingAddressItems.filter { it.isVaultV2LocalOnly() } else billingAddressItems
	}
	val visiblePasskeyItems = remember(passkeyItems, showOnlyLocalData, state.isArchiveView) {
		if (state.isArchiveView) emptyList() else if (showOnlyLocalData) passkeyItems.filter { it.isVaultV2LocalOnly() } else passkeyItems
	}
	val categoryMenuFilter = remember(storageSelection) {
		storageSelection.toCategoryFilterOrNull() ?: CategoryFilter.All
	}
	val navigateCategoryFilter: (CategoryFilter) -> Unit = remember(state) {
		{ filter ->
			filter.toUnifiedCategoryFilterSelectionOrNull()?.let { selection ->
				selectedKeys.clear()
				state.clearFolderNavigationHistory()
				state.updateStorageFilter(selection)
			}
		}
	}
	val selectedKeePassGroupsFlow = remember(localKeePassViewModel, selectedKeePassDatabaseId, showOverview) {
		if (showOverview) flowOf(emptyList())
		else selectedKeePassDatabaseId?.let(localKeePassViewModel::getGroups) ?: flowOf(emptyList())
	}
	val selectedKeePassGroups by selectedKeePassGroupsFlow.collectAsState(initial = emptyList())
	val quickFilterVisibleTypes = remember {
		resolvePasswordPageVisibleTypes(
			aggregateEnabled = true,
			configuredTypes = PasswordPageContentType.DEFAULT_VISIBLE_TYPES
		)
	}
	val configuredQuickFilterItems = remember(quickFilterVisibleTypes) {
		appendAggregateContentQuickFilterItems(
			configuredItems = PasswordListQuickFilterItem.DEFAULT_ORDER,
			visibleTypes = quickFilterVisibleTypes,
			aggregateEnabled = true
		)
	}
	LaunchedEffect(configuredQuickFilterItems, quickFilterVisibleTypes) {
		selectedAggregateTypes = sanitizeSelectedPasswordPageTypes(
			visibleTypes = quickFilterVisibleTypes,
			selectedTypes = selectedAggregateTypes
		)
		if (PasswordListQuickFilterItem.FAVORITE !in configuredQuickFilterItems) quickFilterFavorite = false
		if (PasswordListQuickFilterItem.TWO_FA !in configuredQuickFilterItems) quickFilter2fa = false
		if (PasswordListQuickFilterItem.NOTES !in configuredQuickFilterItems) quickFilterNotes = false
		if (PasswordListQuickFilterItem.PASSKEY !in configuredQuickFilterItems) quickFilterPasskey = false
		if (PasswordListQuickFilterItem.NOTE !in configuredQuickFilterItems) quickFilterBoundNote = false
		if (PasswordListQuickFilterItem.ATTACHMENTS !in configuredQuickFilterItems) quickFilterAttachments = false
		if (PasswordListQuickFilterItem.UNCATEGORIZED !in configuredQuickFilterItems) quickFilterUncategorized = false
		if (PasswordListQuickFilterItem.LOCAL_ONLY !in configuredQuickFilterItems) quickFilterLocalOnly = false
		if (PasswordListQuickFilterItem.MANUAL_STACK_ONLY !in configuredQuickFilterItems) quickFilterManualStackOnly = false
		if (PasswordListQuickFilterItem.NEVER_STACK !in configuredQuickFilterItems) quickFilterNeverStack = false
		if (PasswordListQuickFilterItem.UNSTACKED !in configuredQuickFilterItems) quickFilterUnstacked = false
	}
	val displayedContentTypes = remember(quickFilterVisibleTypes, selectedAggregateTypes, state.isArchiveView) {
		if (state.isArchiveView) {
			setOf(PasswordPageContentType.PASSWORD)
		} else {
			resolvePasswordPageDisplayedTypes(
				visibleTypes = quickFilterVisibleTypes,
				selectedTypes = selectedAggregateTypes
			)
		}
	}
	val hasVisibleQuickFilters = remember(configuredQuickFilterItems, quickFilterVisibleTypes) {
		configuredQuickFilterItems.any { item ->
			takagi.ru.monica.ui.shouldShowQuickFilterItem(item, quickFilterVisibleTypes)
		}
	}
	val shouldLoadManualStackMetadata = remember(
		showOverview, appSettings.stackCardMode,
		quickFilterManualStackOnly,
		quickFilterNeverStack,
		quickFilterUnstacked,
		configuredQuickFilterItems
	) {
		(!showOverview && appSettings.stackCardMode != "ALWAYS_EXPANDED") ||
		(quickFilterManualStackOnly && PasswordListQuickFilterItem.MANUAL_STACK_ONLY in configuredQuickFilterItems) ||
			(quickFilterNeverStack && PasswordListQuickFilterItem.NEVER_STACK in configuredQuickFilterItems) ||
			(quickFilterUnstacked && PasswordListQuickFilterItem.UNSTACKED in configuredQuickFilterItems)
	}
	val manualStackEntryRevisions = remember(visiblePasswordEntries, shouldLoadManualStackMetadata) {
		if (!shouldLoadManualStackMetadata) emptyList() else visiblePasswordEntries.map { entry ->
			VaultV2PasswordRevision(
				id = entry.id,
				updatedAtMillis = entry.updatedAt?.time ?: Long.MIN_VALUE,
			)
		}
	}
	val retainedManualStackMetadata = remember(
		manualStackEntryRevisions,
		shouldLoadManualStackMetadata,
	) {
		if (shouldLoadManualStackMetadata) {
			state.seedManualStackMetadata(manualStackEntryRevisions)
		} else {
			null
		}
	}
	var manualStackGroupByEntryId by remember(
		manualStackEntryRevisions,
		shouldLoadManualStackMetadata,
	) {
		mutableStateOf(retainedManualStackMetadata?.manualStackGroupByEntryId.orEmpty())
	}
	var noStackEntryIds by remember(
		manualStackEntryRevisions,
		shouldLoadManualStackMetadata,
	) {
		mutableStateOf(retainedManualStackMetadata?.noStackEntryIds.orEmpty())
	}
	var lastCustomFieldEntryRevisions by remember(
		manualStackEntryRevisions,
		shouldLoadManualStackMetadata,
	) {
		mutableStateOf(retainedManualStackMetadata?.revisions.orEmpty())
	}
	LaunchedEffect(manualStackEntryRevisions, shouldLoadManualStackMetadata) {
		if (!shouldLoadManualStackMetadata) {
			manualStackGroupByEntryId = emptyMap()
			noStackEntryIds = emptySet()
			lastCustomFieldEntryRevisions = emptyList()
			return@LaunchedEffect
		}
		val allIds = manualStackEntryRevisions.map(VaultV2PasswordRevision::id)
		if (allIds.isEmpty()) {
			manualStackGroupByEntryId = emptyMap()
			noStackEntryIds = emptySet()
			lastCustomFieldEntryRevisions = emptyList()
			return@LaunchedEffect
		}
		if (manualStackEntryRevisions == lastCustomFieldEntryRevisions) return@LaunchedEffect
		lastCustomFieldEntryRevisions = manualStackEntryRevisions
		val fieldMap = withContext(Dispatchers.IO) {
			passwordViewModel.getCustomFieldsByEntryIds(allIds)
		}
		val (manualStackMap, noStackIds) = withContext(Dispatchers.Default) {
			val manualStack = fieldMap.mapNotNull { (entryId, fields) ->
				val groupId = fields.firstOrNull {
					it.title == MONICA_MANUAL_STACK_GROUP_FIELD_TITLE
				}?.value?.takeIf(String::isNotBlank)
				groupId?.let { entryId to it }
			}.toMap()
			val noStack = fieldMap.mapNotNull { (entryId, fields) ->
				val hasNoStack = fields.any {
					it.title == MONICA_NO_STACK_FIELD_TITLE && it.value != "0"
				}
				if (hasNoStack) entryId else null
			}.toSet()
			manualStack to noStack
		}
		manualStackGroupByEntryId = manualStackMap
		noStackEntryIds = noStackIds
		state.updateManualStackMetadata(
			VaultV2ManualStackMetadata(
				revisions = manualStackEntryRevisions,
				manualStackGroupByEntryId = manualStackMap,
				noStackEntryIds = noStackIds,
			)
		)
	}
    val effectiveStackGroups = remember(manualStackGroupByEntryId, aggregateStackEntries) {
        manualStackGroupByEntryId + aggregateStackEntries.filter { it.stackOrder >= 0 }.mapNotNull {
            takagi.ru.monica.ui.password.passwordIdFromSelectionKey(it.itemKey)?.let { id -> id to it.stackGroupId }
        }.toMap()
    }
    val effectiveNoStackIds = remember(noStackEntryIds, aggregateStackEntries) {
        noStackEntryIds + aggregateStackEntries.filter { it.stackOrder < 0 }.mapNotNull {
            takagi.ru.monica.ui.password.passwordIdFromSelectionKey(it.itemKey)
        }
    }
	val baseQuickFolderPasswordCountByCategoryId = rememberVaultV2AsyncComputed(
		visiblePasswordEntries,
		categories,
		initialValue = emptyMap<Long, Int>()
	) {
		buildLocalQuickFolderPasswordCountByCategoryId(
			entries = visiblePasswordEntries,
			categories = categories
		)
	}
	val categoryMenuQuickFolderPasswordCountByCategoryId = remember(
		baseQuickFolderPasswordCountByCategoryId,
		categoryMenuFilter
	) {
		if (!categoryMenuFilter.supportsQuickFolders()) emptyMap() else baseQuickFolderPasswordCountByCategoryId
	}
	val categoryMenuQuickFolderShortcuts = rememberVaultV2AsyncComputed(
		categoryMenuFilter,
		quickFolderCurrentPath,
		quickFolderNodes,
		quickFolderNodeByPath,
		categoryMenuQuickFolderPasswordCountByCategoryId,
		visiblePasswordEntries,
		searchQuery,
		keepassDatabases,
		selectedKeePassGroups,
		bitwardenVaults,
		selectedBitwardenFolders,
		selectedMdbxFolders,
		categories,
		initialValue = emptyList()
	) {
		buildCategoryMenuFolderShortcuts(
			context = context,
			currentFilter = categoryMenuFilter,
			quickFolderCurrentPath = quickFolderCurrentPath,
			quickFolderNodes = quickFolderNodes,
			quickFolderNodeByPath = quickFolderNodeByPath,
			quickFolderPasswordCountByCategoryId = categoryMenuQuickFolderPasswordCountByCategoryId,
			allPasswords = visiblePasswordEntries,
			searchScopedPasswords = visiblePasswordEntries,
			isSearchActive = searchQuery.isNotBlank(),
			keepassDatabases = keepassDatabases,
			keepassGroupsForSelectedDb = selectedKeePassGroups,
			bitwardenVaults = bitwardenVaults,
			selectedBitwardenFolders = selectedBitwardenFolders,
			selectedMdbxFolders = selectedMdbxFolders,
			categories = categories
		)
	}
	val quickFilterBindings = remember(
		quickFilterFavorite,
		quickFilter2fa,
		quickFilterNotes,
		quickFilterPasskey,
		quickFilterBoundNote,
		quickFilterAttachments,
		quickFilterUncategorized,
		quickFilterLocalOnly,
		quickFilterManualStackOnly,
		quickFilterNeverStack,
		quickFilterUnstacked,
		selectedAggregateTypes,
		quickFilterVisibleTypes
	) {
		buildCategoryMenuQuickFilterBindings(
			quickFilterFavorite = quickFilterFavorite,
			onQuickFilterFavoriteChange = { quickFilterFavorite = it; state.overviewFavorites = it },
			quickFilter2fa = quickFilter2fa,
			onQuickFilter2faChange = { quickFilter2fa = it },
			quickFilterNotes = quickFilterNotes,
			onQuickFilterNotesChange = { quickFilterNotes = it; state.overviewItemType = null },
			quickFilterPasskey = quickFilterPasskey,
			onQuickFilterPasskeyChange = { quickFilterPasskey = it; state.overviewItemType = null },
			quickFilterBoundNote = quickFilterBoundNote,
			onQuickFilterBoundNoteChange = { quickFilterBoundNote = it },
			quickFilterAttachments = quickFilterAttachments,
			onQuickFilterAttachmentsChange = { quickFilterAttachments = it },
			quickFilterUncategorized = quickFilterUncategorized,
			onQuickFilterUncategorizedChange = { quickFilterUncategorized = it },
			quickFilterLocalOnly = quickFilterLocalOnly,
			onQuickFilterLocalOnlyChange = { quickFilterLocalOnly = it },
			quickFilterManualStackOnly = quickFilterManualStackOnly,
			onQuickFilterManualStackOnlyChange = { quickFilterManualStackOnly = it },
			quickFilterNeverStack = quickFilterNeverStack,
			onQuickFilterNeverStackChange = { quickFilterNeverStack = it },
			quickFilterUnstacked = quickFilterUnstacked,
			onQuickFilterUnstackedChange = { quickFilterUnstacked = it },
			aggregateSelectedTypes = selectedAggregateTypes,
			aggregateVisibleTypes = quickFilterVisibleTypes,
			onToggleAggregateType = { type ->
                state.overviewItemType = null
				selectedAggregateTypes = toggleVaultV2ContentType(
					currentTypes = selectedAggregateTypes,
					toggledType = type,
					visibleTypes = quickFilterVisibleTypes
				)
			}
		)
	}

    val contentBlockTypes by passwordViewModel.contentBlockTypes.collectAsStateWithLifecycle()
    val nativeTokens = rememberNativeTokenList(
        if (showOverview) null else mdbxViewModel,
        if (state.isArchiveView) CategoryFilter.Archived else categoryMenuFilter, searchQuery,
        nativeOnly, { nativeOnly = !nativeOnly; selectedKeys.clear() }, onOpenApiTokens,
        includeTokens = !quickFilter2fa && !quickFilterNotes && !quickFilterPasskey &&
            !quickFilterBoundNote && !quickFilterAttachments && !quickFilterLocalOnly &&
            !quickFilterUncategorized &&
            (state.overviewItemType == null || state.overviewItemType == VaultV2ItemType.PASSWORD.name),
        favoritesOnly = quickFilterFavorite || state.overviewFavorites
    )
    val nativeTypeLabel = stringResource(R.string.entry_type_api_token)
    val nativePasswordCards = remember(nativeTokens.entries, nativeTypeLabel) {
        nativeTokens.entries.map { it.asPasswordCard(nativeTypeLabel) }
    }

	val computedSnapshotKey = remember(state.isArchiveView, showOnlyLocalData) {
		VaultV2ComputedSnapshotKey(
			isArchiveView = state.isArchiveView,
			showOnlyLocalData = showOnlyLocalData,
		)
	}
	val computedSources = remember(
		nativePasswordCards,
		sourcePasswordEntries,
		totpItems,
		bankCardItems,
		documentItems,
		billingAddressItems,
		noteItems,
		passkeyItems,
	) {
		VaultV2ComputedSources(
			passwords = sourcePasswordEntries + nativePasswordCards,
			totpItems = totpItems,
			bankCardItems = bankCardItems,
			documentItems = documentItems,
			billingAddressItems = billingAddressItems,
			noteItems = noteItems,
			passkeyItems = passkeyItems,
		)
	}
	val currentPasswordItems = remember(visiblePasswordEntries, nativeTokens.entries, nativeTypeLabel) {
		lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
			buildVaultV2PasswordItems(visiblePasswordEntries) + buildVaultV2NativeTokenItems(nativeTokens.entries, nativeTypeLabel)
		}
	}
	val computedSnapshotSeed = remember(computedSnapshotKey, computedSources) {
		state.computedListSnapshots.seed(
			key = computedSnapshotKey,
			source = computedSources,
			fallback = VaultV2ComputedListState(),
		)
	}
	val computedListStateAsync = rememberVaultV2AsyncComputedValue(
		computationKey = computedSnapshotKey to computedSources,
		initialValue = computedSnapshotSeed.value,
		initialHasComputed = computedSnapshotSeed.hasSnapshot,
	) {
		val visiblePasswordIds = visiblePasswordEntries.mapTo(hashSetOf()) { it.id }

		val secondaryLists = coroutineScope {
			val totpDeferred = async(Dispatchers.Default) {
				visibleTotpItems.mapNotNull { item ->
			val data = parseVaultV2TotpItemData(item, securityManager)
			val boundPasswordId = data?.boundPasswordId
			if (boundPasswordId != null && boundPasswordId in visiblePasswordIds) return@mapNotNull null
			val subtitle = listOf(data?.issuer, data?.accountName)
				.filterNotNull()
				.map { it.trim() }
				.filter { it.isNotEmpty() }
				.joinToString(" · ")
				.ifBlank { item.notes.ifBlank { "-" } }

			val displayTitle = item.title.ifBlank { data?.issuer ?: "(Untitled)" }
			VaultV2Item(
				key = "totp:${item.id}",
				type = VaultV2ItemType.AUTHENTICATOR,
				title = displayTitle,
				subtitle = subtitle,
				isFavorite = item.isFavorite,
				sortKey = normalizedVaultV2SortKey(displayTitle),
				searchableValues = listOf(displayTitle, subtitle, item.notes, item.itemData)
					.filter { it.isNotBlank() },
				totpItem = item,
				boundPasswordId = data?.boundPasswordId,
			)
				}
			}

			val noteDeferred = async(Dispatchers.Default) {
				visibleNoteItems.map { item ->
			val displayTitle = item.title.ifBlank { "(Untitled)" }
			val decoded = NoteContentCodec.decodeFromItem(item)
			val previewText = vaultV2PlainSingleLine(
				NoteContentCodec.toPlainPreview(decoded.content, decoded.isMarkdown)
			)
			VaultV2Item(
				key = "note:${item.id}",
				type = VaultV2ItemType.NOTE,
				title = displayTitle,
				subtitle = previewText.ifBlank { item.notes.ifBlank { "-" } },
				isFavorite = item.isFavorite,
				sortKey = normalizedVaultV2SortKey(displayTitle),
				searchableValues = buildList {
					add(displayTitle)
					add(decoded.content)
					addAll(decoded.tags)
					add(item.notes)
				}.filter { it.isNotBlank() },
				secureItem = item,
			)
				}
			}

			val passkeyDeferred = async(Dispatchers.Default) {
				visiblePasskeyItems.map { passkey ->
			val displayTitle = passkey.rpName.ifBlank { passkey.rpId }.ifBlank { "(Untitled)" }
			val subtitle = listOf(
				passkey.userDisplayName.ifBlank { passkey.userName },
				passkey.userName.takeIf {
					it.isNotBlank() && it != passkey.userDisplayName
				},
				passkey.rpId.takeIf { it.isNotBlank() && it != displayTitle }
			)
				.filterNotNull()
				.filter { it.isNotBlank() }
				.joinToString(" · ")
				.ifBlank { "-" }
			VaultV2Item(
				key = "passkey:${passkey.credentialId}",
				type = VaultV2ItemType.PASSKEY,
				title = displayTitle,
				subtitle = subtitle,
				isFavorite = false,
				sortKey = normalizedVaultV2SortKey(displayTitle),
				searchableValues = listOf(
					displayTitle,
					passkey.rpId,
					passkey.userName,
					passkey.userDisplayName,
					passkey.notes,
				).filter { it.isNotBlank() },
				passkeyEntry = passkey,
				boundPasswordId = passkey.boundPasswordId,
			)
				}
			}

			val bankCardDeferred = async(Dispatchers.Default) {
				visibleBankCardItems.map { item ->
			val data = parseVaultV2BankCardData(item, securityManager)
			val displayTitle = item.title.ifBlank { data?.bankName ?: "(Untitled)" }
			val subtitle = vaultV2BankCardSubtitle(data = data, fallbackNotes = item.notes)
			VaultV2Item(
				key = "bank_card:${item.id}",
				type = VaultV2ItemType.BANK_CARD,
				title = displayTitle,
				subtitle = subtitle,
				isFavorite = item.isFavorite,
				sortKey = normalizedVaultV2SortKey(displayTitle),
				searchableValues = listOf(
					displayTitle,
					data?.bankName.orEmpty(),
					data?.bankBranch.orEmpty(),
					data?.cardholderName.orEmpty(),
					data?.cardNumber.orEmpty(),
					item.notes,
				).filter { it.isNotBlank() },
				secureItem = item,
			)
				}
			}

			val documentDeferred = async(Dispatchers.Default) {
				visibleDocumentItems.map { item ->
			val data = parseVaultV2DocumentData(item, securityManager)
			val displayTitle = item.title.ifBlank { data?.fullName ?: "(Untitled)" }
			val subtitle = vaultV2DocumentSubtitle(data = data, fallbackNotes = item.notes)
			VaultV2Item(
				key = "document:${item.id}",
				type = VaultV2ItemType.DOCUMENT,
				title = displayTitle,
				subtitle = subtitle,
				isFavorite = item.isFavorite,
				sortKey = normalizedVaultV2SortKey(displayTitle),
				searchableValues = listOf(
					displayTitle,
					data?.fullName.orEmpty(),
					data?.documentNumber.orEmpty(),
					data?.issuedBy.orEmpty(),
					item.notes,
				).filter { it.isNotBlank() },
				secureItem = item,
			)
				}
			}

			VaultV2SecondaryLists(
				totp = totpDeferred.await(),
				notes = noteDeferred.await(),
				passkeys = passkeyDeferred.await(),
				bankCards = bankCardDeferred.await(),
				documents = documentDeferred.await(),
				billingAddresses = visibleBillingAddressItems.map { item ->
					val data = billingAddressViewModel.parseAddressData(item.itemData)
					VaultV2Item(
						key = "billing_address:${item.id}", type = VaultV2ItemType.BILLING_ADDRESS,
						title = item.title, subtitle = data?.fullName.orEmpty(),
						isFavorite = item.isFavorite, sortKey = normalizedVaultV2SortKey(item.title),
						searchableValues = listOf(item.title, data?.fullName.orEmpty(), data?.city.orEmpty()),
						secureItem = item,
					)
				},
			)
		}

		val allItemsRaw = dedupeExactVaultItems(
			currentPasswordItems.value +
				secondaryLists.totp +
				secondaryLists.notes +
				secondaryLists.passkeys +
				secondaryLists.bankCards +
				secondaryLists.documents + secondaryLists.billingAddresses
		).sortedWith(
				compareBy<VaultV2Item> { it.sortKey.lowercase(Locale.ROOT) }
					.thenBy { it.type.ordinal }
					.thenBy { it.key }
			)
		VaultV2ComputedListState(
			allItemsRaw = allItemsRaw,
			passwordById = visiblePasswordEntries.associateBy { it.id },
		)
	}
	LaunchedEffect(
		computedSnapshotKey,
		computedSources,
		computedListStateAsync.value,
		computedListStateAsync.hasComputed,
	) {
		if (computedListStateAsync.hasComputed) {
			state.computedListSnapshots.update(
				key = computedSnapshotKey,
				source = computedSources,
				value = computedListStateAsync.value,
			)
		}
	}
	val computedListState = computedListStateAsync.value
	val passwordById = remember(visiblePasswordEntries, showOverview) {
		if (showOverview) emptyMap() else visiblePasswordEntries.associateBy { it.id }
	}

	var isAutoScrollingToTop by remember { mutableStateOf(false) }
	var lastHandledScrollToTopRequestKey by rememberSaveable { mutableStateOf(0) }
	var lastHandledFastScrollRequestKey by remember {
		mutableStateOf(fastScrollRequestKey)
	}

	val normalizedQuery = remember(searchQuery) { searchQuery.trim() }
	val hasHierarchyBreakingQuickFilter = remember(
		quickFilterFavorite,
		quickFilter2fa,
		quickFilterNotes,
		quickFilterPasskey,
		quickFilterBoundNote,
		quickFilterAttachments,
		quickFilterUncategorized,
		quickFilterLocalOnly,
		quickFilterManualStackOnly,
		quickFilterNeverStack,
		quickFilterUnstacked,
		selectedAggregateTypes,
	) {
		quickFilterFavorite ||
			quickFilter2fa ||
			quickFilterNotes ||
			quickFilterPasskey ||
			quickFilterBoundNote ||
			quickFilterAttachments ||
			quickFilterUncategorized ||
			quickFilterLocalOnly ||
			quickFilterManualStackOnly ||
			quickFilterNeverStack ||
			quickFilterUnstacked ||
			selectedAggregateTypes.isNotEmpty()
	}
	val useHierarchicalLayout = remember(
		showOverview,
		appSettings.vaultV2LayoutMode,
		normalizedQuery,
		state.isArchiveView,
		storageSelection,
		hasHierarchyBreakingQuickFilter,
		state.overviewItemType,
	) {
		!showOverview && appSettings.vaultV2LayoutMode == VaultV2LayoutMode.HIERARCHICAL &&
			normalizedQuery.isBlank() &&
			!state.isArchiveView &&
			!hasHierarchyBreakingQuickFilter && state.overviewItemType == null &&
			storageSelection.supportsVaultV2FolderHierarchy()
	}

	LaunchedEffect(appSettings.vaultV2LayoutMode) {
		val currentModeName = appSettings.vaultV2LayoutMode.name
		if (observedLayoutModeName != currentModeName) {
			observedLayoutModeName = currentModeName
			state.clearFolderNavigationHistory()
			pendingFolderScrollRestore = null
			state.requestScrollToTop()
		}
	}
	val onOpenKeePassNativeManager: () -> Unit = {
		selectedKeePassDatabaseId?.let { databaseId ->
            takagi.ru.monica.ui.screens.DatabaseManagerNavigation.open(takagi.ru.monica.credentialexchange.ImportDestination(takagi.ru.monica.credentialexchange.ImportDestinationKind.KEEPASS, databaseId))
        }
	}

	BackHandler(
		enabled = state.overviewListOpen || selectedKeys.isNotEmpty() ||
			state.isArchiveView ||
			isSearchExpanded ||
			(useHierarchicalLayout && state.hasFolderNavigationHistory)
	) {
		when {
			selectedKeys.isNotEmpty() -> selectedKeys.clear()
			isSearchExpanded -> {
				isSearchExpanded = false
				searchQuery = ""
			}
			useHierarchicalLayout && state.hasFolderNavigationHistory -> {
				state.popFolderNavigationPosition()?.let { entry ->
					pendingFolderScrollRestore = entry
					state.updateStorageFilter(
						type = entry.storageFilterType,
						primaryId = entry.storageFilterPrimaryId,
						secondaryKey = entry.storageFilterSecondaryKey,
						identityKey = entry.storageFilterIdentityKey,
					)
				}
			}
			state.overviewListOpen || appSettings.vaultOverviewEnabled -> closeOverviewList()
			else -> state.closeArchiveView()
		}
	}
	val overviewItemType = state.overviewItemType?.let { runCatching { VaultV2ItemType.valueOf(it) }.getOrNull() }
	val visibleSnapshotKey = remember(
		appSettings.vaultListSort,
		overviewItemType,
		nativeTokens.onlyTokens,
		storageSelection,
		localCategoryIdsInScope,
		contentBlockTypes,
		displayedContentTypes,
		configuredQuickFilterItems,
		quickFilterFavorite,
		quickFilter2fa,
		quickFilterNotes,
		quickFilterPasskey,
		quickFilterBoundNote,
		quickFilterAttachments,
		activeAttachmentParentIds,
		quickFilterUncategorized,
		quickFilterLocalOnly,
		quickFilterManualStackOnly,
		quickFilterNeverStack,
		quickFilterUnstacked,
		effectiveStackGroups,
		effectiveNoStackIds,
		normalizedQuery,
		state.isArchiveView,
	) {
		VaultV2VisibleSnapshotKey(
			sort = appSettings.vaultListSort,
			overviewItemType = overviewItemType,
			nativeOnly = nativeTokens.onlyTokens,
			storageSelection = storageSelection,
			localCategoryIdsInScope = localCategoryIdsInScope,
			contentBlockTypes = contentBlockTypes,
			displayedContentTypes = displayedContentTypes,
			configuredQuickFilterItems = configuredQuickFilterItems,
			quickFilterStates = listOf(
				quickFilterFavorite,
				quickFilter2fa,
				quickFilterNotes,
				quickFilterPasskey,
				quickFilterBoundNote,
				quickFilterAttachments,
				quickFilterUncategorized,
				quickFilterLocalOnly,
				quickFilterManualStackOnly,
				quickFilterNeverStack,
				quickFilterUnstacked,
			),
			activeAttachmentParentIds = activeAttachmentParentIds,
			manualStackGroupByEntryId = effectiveStackGroups,
			noStackEntryIds = effectiveNoStackIds,
			normalizedQuery = normalizedQuery,
			isArchiveView = state.isArchiveView,
		)
	}
	val visibleSnapshotSeed = remember(visibleSnapshotKey) {
		if (normalizedQuery.isBlank()) {
			state.visibleListSnapshots.seed(
				key = visibleSnapshotKey,
				fallback = VaultV2VisibleListState(),
			)
		} else {
			VaultV2SnapshotSeed(
				value = VaultV2VisibleListState(),
				hasSnapshot = false,
			)
		}
	}
	val visibleListConfig = remember(
		appSettings.vaultListSort,
		overviewItemType,
		nativeTokens.onlyTokens,
		storageSelection,
		localCategoryIdsInScope,
		contentBlockTypes,
		displayedContentTypes,
		configuredQuickFilterItems,
		quickFilterFavorite,
		quickFilter2fa,
		quickFilterNotes,
		quickFilterPasskey,
		quickFilterBoundNote,
		quickFilterAttachments,
		activeAttachmentParentIds,
		quickFilterUncategorized,
		quickFilterLocalOnly,
		quickFilterManualStackOnly,
		quickFilterNeverStack,
		quickFilterUnstacked,
		effectiveStackGroups,
		effectiveNoStackIds,
		normalizedQuery,
		state.isArchiveView,
	) {
		VaultV2VisibleListConfig(
			sort = appSettings.vaultListSort,
			overviewItemType = overviewItemType,
			nativeOnly = nativeTokens.onlyTokens,
			storageSelection = storageSelection,
			localCategoryIdsInScope = localCategoryIdsInScope,
			contentBlockTypes = contentBlockTypes,
			displayedContentTypes = displayedContentTypes,
			configuredQuickFilterItems = configuredQuickFilterItems,
			quickFilterFavorite = quickFilterFavorite,
			quickFilter2fa = quickFilter2fa,
			quickFilterNotes = quickFilterNotes,
			quickFilterPasskey = quickFilterPasskey,
			quickFilterBoundNote = quickFilterBoundNote,
			quickFilterAttachments = quickFilterAttachments,
			activeAttachmentParentIds = activeAttachmentParentIds,
			quickFilterUncategorized = quickFilterUncategorized,
			quickFilterLocalOnly = quickFilterLocalOnly,
			quickFilterManualStackOnly = quickFilterManualStackOnly,
			quickFilterNeverStack = quickFilterNeverStack,
			quickFilterUnstacked = quickFilterUnstacked,
			manualStackGroupByEntryId = effectiveStackGroups,
			noStackEntryIds = effectiveNoStackIds,
			normalizedQuery = normalizedQuery,
			isArchiveView = state.isArchiveView,
		)
	}
	val displayListSeed = remember(
		showOverview,
		computedSnapshotSeed,
		visibleSnapshotSeed,
		visibleListConfig,
		currentPasswordItems,
	) {
		// The overview consumes the shared source snapshot directly. Do not sort,
		// merge or group the hidden classic list during composition or scope changes.
		if (showOverview) VaultV2DisplayListState(
			allItemsRaw = computedSnapshotSeed.value.allItemsRaw,
			visibleListState = VaultV2VisibleListState(),
		) else buildVaultV2InitialDisplayListStateFromItems(
			currentPasswordItems = currentPasswordItems,
			config = visibleListConfig,
			retainedComputedItems = computedSnapshotSeed.value.allItemsRaw
				.takeIf { computedSnapshotSeed.hasSnapshot },
			retainedVisibleListState = visibleSnapshotSeed.value
				.takeIf { visibleSnapshotSeed.hasSnapshot },
		)
	}
	val displayListStateAsync = rememberVaultV2AsyncComputedValue(
		enabled = !showOverview,
		computationKey = Triple(
			computedListState.allItemsRaw,
			currentPasswordItems,
			visibleListConfig,
		),
		initialValue = displayListSeed,
		initialHasComputed = !showOverview && computedSnapshotSeed.hasSnapshot && visibleSnapshotSeed.hasSnapshot,
	) {
		buildVaultV2DisplayListStateFromItems(
			computedItems = computedListState.allItemsRaw,
			currentPasswordItems = currentPasswordItems.value,
			config = visibleListConfig,
		)
	}
	var displayedListState by remember(computedSnapshotKey) {
		mutableStateOf(displayListStateAsync.value)
	}
	var pendingDisplayListState by remember(computedSnapshotKey) {
		mutableStateOf<VaultV2DisplayListState?>(null)
	}
	LaunchedEffect(displayListStateAsync.value) {
		if (isAutoScrollingToTop) {
			pendingDisplayListState = displayListStateAsync.value
		} else {
			displayedListState = displayListStateAsync.value
		}
	}
	LaunchedEffect(isAutoScrollingToTop) {
		if (!isAutoScrollingToTop) {
			pendingDisplayListState?.let { buffered ->
				displayedListState = buffered
				pendingDisplayListState = null
			}
		}
	}
	// The overview can publish a fast first snapshot from password rows while secondary
	// item payloads are still being decoded. The full computed snapshot replaces it later.
	val overviewItems = if (computedListStateAsync.hasComputed) {
		computedListState.allItemsRaw
	} else {
		currentPasswordItems.value
	}
	val allItems = if (showOverview) overviewItems else displayedListState.allItemsRaw
	val visibleListState = displayedListState.visibleListState
	LaunchedEffect(
		visibleSnapshotKey,
		visibleListState,
		displayListStateAsync.hasComputed,
		computedListStateAsync.hasComputed,
		selectedPasswordEntriesReady,
	) {
		if (
			displayListStateAsync.hasComputed &&
			computedListStateAsync.hasComputed &&
			selectedPasswordEntriesReady &&
			normalizedQuery.isBlank()
		) {
			state.visibleListSnapshots.update(
				key = visibleSnapshotKey,
				value = visibleListState,
			)
		}
	}
	val baseFilteredItems = visibleListState.filteredItems
	val baseSectionedItems = visibleListState.sectionedItems
	val hierarchyLocalSourceTitle = stringResource(R.string.vault_v2_hierarchy_local_source)
	val hierarchicalContent = remember(
		useHierarchicalLayout,
		storageSelection,
		allItems,
		baseFilteredItems,
		categoryMenuQuickFolderShortcuts,
		quickFolderNodes,
		keepassDatabases,
		bitwardenVaults,
		mdbxDatabases,
		selectedMdbxFolders,
		hierarchyLocalSourceTitle,
		showOnlyLocalData,
	) {
		if (useHierarchicalLayout) {
			buildVaultV2HierarchicalContent(
				storageSelection = storageSelection,
				allItems = allItems,
				filteredItems = baseFilteredItems,
				folderShortcuts = categoryMenuQuickFolderShortcuts,
				quickFolderNodes = quickFolderNodes,
				keepassDatabases = keepassDatabases,
				bitwardenVaults = bitwardenVaults,
				mdbxDatabases = mdbxDatabases,
				selectedMdbxFolders = selectedMdbxFolders,
				localSourceTitle = hierarchyLocalSourceTitle,
				includeExternalSources = !showOnlyLocalData,
			)
		} else {
			VaultV2HierarchicalContent()
		}
	}
	val folderRows = if (useHierarchicalLayout) hierarchicalContent.folderRows else emptyList()
	val filteredItems = if (useHierarchicalLayout) {
		hierarchicalContent.directItems
	} else {
		baseFilteredItems
	}
	// This page always shows individual rows, independently of password-page stack settings.
	val unknownSortDate = stringResource(R.string.vault_sort_unknown_date)
	val sectionedItems = remember(showOverview, filteredItems, appSettings.vaultListSort, unknownSortDate) {
		if (showOverview) emptyList() else buildVaultV2SortedSections(filteredItems, appSettings.vaultListSort)
			.map { (title, items) -> title.ifEmpty { unknownSortDate } to items }
	}
	val showQuickFiltersInList = !state.isArchiveView && (hasVisibleQuickFilters || nativeTokens.visible)
	val showCategoryQuickFiltersInList =
		!state.isArchiveView && !useHierarchicalLayout && categoryMenuQuickFolderShortcuts.isNotEmpty()
	val hierarchyLeadingItemCount = if (useHierarchicalLayout) {
		(if (showQuickFiltersInList) 1 else 0) +
			folderRows.size +
			(if (sectionedItems.isNotEmpty()) 1 else 0)
	} else {
		0
	}
    val sectionLayouts = remember(sectionedItems, hierarchyLeadingItemCount, useHierarchicalLayout,
        showQuickFiltersInList, showCategoryQuickFiltersInList, nativeTokens.failed) {
        buildVaultV2SectionLayouts(sectionedItems,
            (if (useHierarchicalLayout) hierarchyLeadingItemCount else
                if (showQuickFiltersInList || showCategoryQuickFiltersInList) 1 else 0) +
                if (nativeTokens.failed) 1 else 0)
    }
	val hasDisplayedContent = folderRows.isNotEmpty() || sectionedItems.isNotEmpty()
	val isVaultListLoading = remember(
		displayListStateAsync.isComputing,
		computedListStateAsync.isComputing,
		pendingDisplayListState,
		hasDisplayedContent,
		normalizedQuery,
		selectedPasswordEntriesReady,
		visibleSnapshotSeed.hasSnapshot,
	) {
		shouldShowVaultV2InitialLoading(
			queryIsBlank = normalizedQuery.isBlank(),
			hasVisibleSections = hasDisplayedContent,
			hasRetainedSnapshot = visibleSnapshotSeed.hasSnapshot,
			passwordEntriesReady = selectedPasswordEntriesReady,
			computedListIsComputing = displayListStateAsync.isComputing || computedListStateAsync.isComputing,
			hasPendingItems = pendingDisplayListState != null,
		)
	}
	var showVaultEmptyState by remember(visibleSnapshotKey) {
		mutableStateOf(
			visibleSnapshotSeed.hasSnapshot &&
				visibleSnapshotSeed.value.sectionedItems.isEmpty() &&
				folderRows.isEmpty()
		)
	}
	LaunchedEffect(hasDisplayedContent, normalizedQuery, isVaultListLoading) {
		if (hasDisplayedContent) {
			showVaultEmptyState = false
			return@LaunchedEffect
		}
		if (normalizedQuery.isNotBlank()) {
			showVaultEmptyState = true
			return@LaunchedEffect
		}
		if (isVaultListLoading) {
			showVaultEmptyState = false
			return@LaunchedEffect
		}
		delay(VAULT_V2_EMPTY_STATE_DEBOUNCE_MS)
		showVaultEmptyState = true
	}
	val showVaultLoadingIndicator = !hasDisplayedContent && isVaultListLoading

	val selectedCount by remember { derivedStateOf { selectedKeys.size } }
	val selectionCandidates = if (showOverview) {
		overviewSelection.searchResults
			?: state.overviewSnapshot?.selectablePreview(overviewSelection.module, appSettings.vaultOverviewConfig).orEmpty()
	} else allItems
	val selectedItems by remember(selectionCandidates) {
		derivedStateOf {
			val keySet = selectedKeys.toSet()
			selectionCandidates.filter { it.key in keySet }
		}
	}
    if (showStackDialog) takagi.ru.monica.ui.PasswordStackModeDialog(
        selectedCount, selectedStackMode, { selectedStackMode = it },
        onDismiss = { showStackDialog = false },
        onConfirm = { scope.launch {
            try {
                takagi.ru.monica.ui.applySharedPasswordStackMode(selectedStackMode,
                    selectedItems.map { it.key }, aggregateStackRepository, passwordViewModel)
                selectedKeys.clear()
                showStackDialog = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, R.string.api_token_load_error, Toast.LENGTH_LONG).show() }
        } },
    )
	val currentSectionIndicatorLabel by remember(
		listState,
		sectionLayouts,
		useHierarchicalLayout,
		storageFilterLabel,
	) {
		derivedStateOf {
			if (sectionLayouts.isEmpty()) {
				if (useHierarchicalLayout) {
					storageFilterLabel.trim().take(2).uppercase(Locale.ROOT).ifBlank { "#" }
				} else {
					"#"
				}
			} else {
				vaultV2SectionTitleForLazyIndex(
					sectionLayouts = sectionLayouts,
					lazyIndex = listState.firstVisibleItemIndex,
				) ?: sectionLayouts.first().title
			}
		}
	}
	val showBackToTop by remember(listState) {
		derivedStateOf { listState.firstVisibleItemIndex > 3 }
	}

	LaunchedEffect(selectedCount) {
		state.updateSelectionCount(selectedCount)
		if (selectedCount == 0) showDeleteConfirmDialog = false
	}

	LaunchedEffect(showBackToTop, selectedCount, showOverview, overviewListState) {
		if (showOverview) snapshotFlow { overviewListState.firstVisibleItemIndex > 3 }
			.collect { state.showBackToTop = it && selectedCount == 0 }
		else state.showBackToTop = showBackToTop && selectedCount == 0
	}

	DisposableEffect(Unit) {
		onDispose {
			state.fastScrollIndicatorLabel = null
		}
	}

	LaunchedEffect(state.scrollToTopRequestKey) {
		if (showOverview) {
			if (state.scrollToTopRequestKey > lastHandledScrollToTopRequestKey) overviewListState.animateScrollToItem(0)
			lastHandledScrollToTopRequestKey = state.scrollToTopRequestKey
			return@LaunchedEffect
		}
		if (state.scrollToTopRequestKey > lastHandledScrollToTopRequestKey) {
			isAutoScrollingToTop = true
			try {
				runCatching {
					listState.animateScrollToItem(0)
				}
				listState.scrollToItem(0)
			} finally {
				isAutoScrollingToTop = false
				lastHandledScrollToTopRequestKey = state.scrollToTopRequestKey
			}
		}
	}

	LaunchedEffect(listState) {
		snapshotFlow {
			listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
		}
			.distinctUntilChanged()
			.collect { (index, offset) ->
				state.updateScrollPosition(index, offset)
			}
	}

	LaunchedEffect(
		storageSelection,
		pendingFolderScrollRestore,
		folderRows.size,
		sectionedItems,
	) {
		val restore = pendingFolderScrollRestore ?: return@LaunchedEffect
		pendingFolderScrollRestore = null
		runCatching {
			listState.scrollToItem(
				index = restore.scrollIndex,
				scrollOffset = restore.scrollOffset,
			)
		}.onFailure {
			runCatching { listState.scrollToItem(0) }
		}
	}

	LaunchedEffect(fastScrollRequestKey, sectionLayouts, filteredItems.size) {
		if (fastScrollRequestKey <= lastHandledFastScrollRequestKey) {
			return@LaunchedEffect
		}

		if (filteredItems.isEmpty() || sectionLayouts.isEmpty()) {
			lastHandledFastScrollRequestKey = fastScrollRequestKey
			return@LaunchedEffect
		}

		val targetItemIndex = (
			state.fastScrollProgress.coerceIn(0f, 1f) * (filteredItems.size - 1)
		).roundToInt().coerceIn(0, filteredItems.size - 1)
		val targetLazyIndex = vaultV2LazyIndexForItemIndex(
			sectionLayouts = sectionLayouts,
			targetItemIndex = targetItemIndex,
		)

		try {
			if (listState.firstVisibleItemIndex != targetLazyIndex) {
				runCatching {
					listState.scrollToItem(index = targetLazyIndex)
				}.onFailure { throwable ->
					if (throwable is CancellationException) return@onFailure
					Log.e(
						VAULT_V2_FAST_SCROLL_LOG_TAG,
						"scrollToItem failed: targetLazyIndex=$targetLazyIndex filteredSize=${filteredItems.size}",
						throwable
					)
				}
			}
		} finally {
			lastHandledFastScrollRequestKey = fastScrollRequestKey
		}
	}

	LaunchedEffect(listState, sectionLayouts, filteredItems.size) {
		snapshotFlow {
			if (filteredItems.size <= 1 || sectionLayouts.isEmpty()) {
				0f
			} else {
				val currentItemIndex = vaultV2ItemIndexForLazyIndex(
					sectionLayouts = sectionLayouts,
					lazyIndex = listState.firstVisibleItemIndex,
				).coerceIn(0, filteredItems.size - 1)
				(currentItemIndex.toFloat() / (filteredItems.size - 1).toFloat()).coerceIn(0f, 1f)
			}
		}
			.distinctUntilChanged()
			.collect { progress ->
				state.updateFastScrollProgress(progress)
			}
	}

	LaunchedEffect(listState, sectionLayouts, filteredItems.size) {
		snapshotFlow {
			if (filteredItems.isEmpty() || sectionLayouts.isEmpty()) {
				null
			} else {
				vaultV2SectionTitleForLazyIndex(
					sectionLayouts = sectionLayouts,
					lazyIndex = listState.firstVisibleItemIndex,
				)
			}
		}
			.distinctUntilChanged()
			.collect { sectionTitle ->
				state.fastScrollIndicatorLabel = sectionTitle
			}
	}
	val topBarTitle = if (state.isArchiveView) {
		stringResource(R.string.archive_page_title)
	} else {
		storageFilterLabel
	}
	val emptyStateText = if (state.isArchiveView && normalizedQuery.isBlank()) {
		stringResource(R.string.archive_empty_hint)
	} else {
		stringResource(R.string.no_results)
	}

	val pageMotion = rememberVaultOverviewPageMotion(
		showOverview = showOverview,
		enabled = appSettings.vaultOverviewEnabled && !appSettings.reduceAnimations,
	)
	Box(
		modifier = modifier
			.fillMaxSize()
			.clipToBounds()
			.then(pageMotion)
	) {
		if (showOverview) {
			VaultOverviewContent(
				itemsReady = selectedPasswordEntriesReady,
				allItems = allItems, archivedPasswords = archivedPasswordEntries, categories = categories,
				keepassDatabases = keepassDatabases, mdbxDatabases = mdbxDatabases, bitwardenVaults = bitwardenVaults,
				passwordViewModel = passwordViewModel, localKeePassViewModel = localKeePassViewModel,
				settingsViewModel = settingsViewModel, appSettings = appSettings, securityManager = securityManager,
				currentScope = state.overviewScope ?: appSettings.vaultOverviewConfig.scope,
				listState = overviewListState,
				selectedCardKey = state.overviewCardKey,
				onSelectedCardChange = { state.overviewCardKey = it },
				cardStackState = state.overviewCardStack,
				isDetailVisible = isDetailVisible,
				retainedSnapshot = state.overviewSnapshot,
				onSnapshotReady = { state.overviewSnapshot = it },
				onSelectScope = { selectedScope ->
					overviewSelection.clear()
					state.overviewScope = selectedScope
					state.overviewCardKey = null
					state.overviewCardStack.clear()
					state.updateStorageFilter(overviewScopeSelection(selectedScope))
					settingsViewModel.updateVaultOverviewConfig { it.copy(scope = selectedScope) }
					scope.launch { overviewListState.scrollToItem(0) }
				},
				onOpenSource = { openOverviewList(overviewScopeSelection(it)) },
				onOpenItem = { item -> when (item.type) {
					VaultV2ItemType.PASSWORD -> {
                            val token = item.nativeToken
                            if (token != null) onOpenApiTokens(token.databaseId, token.entryId)
                            else item.passwordEntry?.id?.let(onOpenPassword)
                        }
					VaultV2ItemType.AUTHENTICATOR -> item.totpItem?.id?.let(onOpenTotp)
						?: item.boundPasswordId?.let(onOpenPassword)
					VaultV2ItemType.NOTE -> item.secureItem?.id?.let(onOpenNote)
					VaultV2ItemType.PASSKEY -> item.passkeyEntry?.id?.takeIf { it > 0 }?.let(onOpenPasskey)
					VaultV2ItemType.BANK_CARD -> item.secureItem?.id?.let(onOpenBankCard)
					VaultV2ItemType.DOCUMENT -> item.secureItem?.id?.let(onOpenDocument)
					VaultV2ItemType.BILLING_ADDRESS -> item.secureItem?.id?.let(onOpenBillingAddress)
				} },
				onOpenType = { openOverviewList(type = it) },
				onOpenFolder = { openOverviewList(selection = it.target) },
				onFavorites = { openOverviewList(favorites = true) },
				onArchive = { openOverviewList(); state.openArchiveView() },
				onTrash = { overviewSelection.clear(); handleOpenTrashPage() },
				onAllItems = { openOverviewList() },
				onSearch = { overviewSelection.clear() },
				onScanFidoQr = onScanFidoQr,
				onUnlock = {
					if (selectedBitwardenVaultId != null) showBitwardenUnlockDialog = true
					else selectedKeePassDatabaseId?.let(localKeePassViewModel::openNativeManager)
				},
				selection = overviewSelection,
				onRequestDeleteItem = { item ->
					if (overviewSelection.searchResults != null) overviewSelection.selectSearch(listOf(item.key))
					else overviewSelection.selectAll(VaultOverviewModule.FAVORITES, listOf(item.key))
					showDeleteConfirmDialog = true
				},
			)
		} else {
		Column(modifier = Modifier.fillMaxSize()) {
			ExpressiveTopBar(
				title = when {
					state.isArchiveView -> topBarTitle
					overviewItemType != null -> stringResource(overviewItemType.titleRes())
					state.overviewFavorites -> stringResource(R.string.vault_overview_favorites)
					else -> topBarTitle
				},
				searchQuery = searchQuery,
				onSearchQueryChange = { searchQuery = it },
				isSearchExpanded = isSearchExpanded,
				searchBackEnabled = selectedCount == 0,
				onSearchExpandedChange = {
					isSearchExpanded = it
				},
				searchHint = stringResource(R.string.topbar_search_hint),
				actions = {
					if (state.isArchiveView && !appSettings.vaultOverviewEnabled) {
						IconButton(onClick = state::closeArchiveView) {
							Icon(
								imageVector = Icons.Default.Lock,
								contentDescription = stringResource(R.string.nav_passwords_short),
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
					if (!state.isArchiveView) {
						Box {
						IconButton(onClick = { isStorageFilterSheetVisible = true }) {
							Icon(
								imageVector = Icons.Default.Folder,
								contentDescription = stringResource(R.string.category),
							)
						}
						if (appSettings.categorySelectionUiMode == CategorySelectionUiMode.CHIP_MENU) {
						takagi.ru.monica.ui.PasswordFilterPanel(
							expanded = isStorageFilterSheetVisible,
							onDismissRequest = { isStorageFilterSheetVisible = false },
							) {
								PasswordListCategoryChipMenu(
									currentFilter = categoryMenuFilter,
									keepassDatabases = keepassDatabases,
									mdbxDatabases = mdbxDatabases,
									bitwardenVaults = bitwardenVaults,
									configuredQuickFilterItems = configuredQuickFilterItems,
									quickFilterFavorite = quickFilterFavorite,
									onQuickFilterFavoriteChange = { quickFilterFavorite = it; state.overviewFavorites = it },
									quickFilter2fa = quickFilter2fa,
									onQuickFilter2faChange = { quickFilter2fa = it },
									quickFilterNotes = quickFilterNotes,
									onQuickFilterNotesChange = { quickFilterNotes = it; state.overviewItemType = null },
									quickFilterPasskey = quickFilterPasskey,
									onQuickFilterPasskeyChange = { quickFilterPasskey = it; state.overviewItemType = null },
									quickFilterBoundNote = quickFilterBoundNote,
									onQuickFilterBoundNoteChange = { quickFilterBoundNote = it },
									quickFilterAttachments = quickFilterAttachments,
									onQuickFilterAttachmentsChange = { quickFilterAttachments = it },
									quickFilterUncategorized = quickFilterUncategorized,
									onQuickFilterUncategorizedChange = { quickFilterUncategorized = it },
									quickFilterLocalOnly = quickFilterLocalOnly,
									onQuickFilterLocalOnlyChange = { quickFilterLocalOnly = it },
									quickFilterManualStackOnly = quickFilterManualStackOnly,
									onQuickFilterManualStackOnlyChange = { quickFilterManualStackOnly = it },
									quickFilterNeverStack = quickFilterNeverStack,
									onQuickFilterNeverStackChange = { quickFilterNeverStack = it },
									quickFilterUnstacked = quickFilterUnstacked,
									onQuickFilterUnstackedChange = { quickFilterUnstacked = it },
									aggregateSelectedTypes = selectedAggregateTypes,
									aggregateVisibleTypes = quickFilterVisibleTypes,
									onToggleAggregateType = { type ->
                state.overviewItemType = null
										selectedAggregateTypes = toggleVaultV2ContentType(
											currentTypes = selectedAggregateTypes,
											toggledType = type,
											visibleTypes = quickFilterVisibleTypes
										)
									},
									quickFolderShortcuts = categoryMenuQuickFolderShortcuts,
									topModulesOrder = PasswordListTopModule.DEFAULT_ORDER,
									onTopModulesOrderChange = {},
									onQuickFilterItemsOrderChange = {},
									launchAnchorBounds = null,
									onDismiss = { isStorageFilterSheetVisible = false },
									onSelectFilter = { filter ->
										filter.toUnifiedCategoryFilterSelectionOrNull()?.let { selection ->
											selectedKeys.clear()
											state.clearFolderNavigationHistory()
											state.updateStorageFilter(selection)
										}
										isStorageFilterSheetVisible = false
									},
									categories = categories,
									onCreateCategory = {
										isStorageFilterSheetVisible = false
										showCreateCategoryDialog = true
									},
									onMoveCategory = { category, targetParentCategoryId ->
										runCatching {
											planLocalCategoryMove(
												categories = categories,
												sourceCategory = category,
												targetParentCategory = categories.find { it.id == targetParentCategoryId },
												strings = AppLocaleStringResolver(context),
											)
										}.onSuccess { plan ->
											plan.updatedCategories.forEach(passwordViewModel::updateCategory)
										}.onFailure { error ->
											Toast.makeText(
												context,
												context.getString(R.string.save_failed_with_error, error.message ?: ""),
												Toast.LENGTH_SHORT
											).show()
										}
									},
									onMoveCategoryToStorageTarget = { category, target ->
										when (target) {
											is StorageTarget.MonicaLocal -> {
												runCatching {
													planLocalCategoryMove(
														categories = categories,
														sourceCategory = category,
														targetParentCategory = categories.find { it.id == target.categoryId },
														strings = AppLocaleStringResolver(context),
													)
												}.onSuccess { plan ->
													plan.updatedCategories.forEach(passwordViewModel::updateCategory)
												}.onFailure { error ->
													Toast.makeText(
														context,
														context.getString(R.string.save_failed_with_error, error.message ?: ""),
														Toast.LENGTH_SHORT
													).show()
												}
											}
											is StorageTarget.Bitwarden -> {
												passwordViewModel.updateCategory(
													category.copy(
														bitwardenVaultId = target.vaultId,
														bitwardenFolderId = target.folderId.orEmpty()
													)
												)
											}
											is StorageTarget.KeePass -> {
												Toast.makeText(
													context,
													context.getString(R.string.save_failed_with_error, context.getString(R.string.legacy_ui_move_category_keepass_unsupported)),
													Toast.LENGTH_SHORT
												).show()
											}
											is StorageTarget.Mdbx -> {
												Toast.makeText(
													context,
													context.getString(R.string.save_failed_with_error, context.getString(R.string.legacy_ui_move_category_mdbx_unsupported)),
													Toast.LENGTH_SHORT
												).show()
											}
										}
									},
									onTransferCategory = ::transferCategoryFolder,
									onRenameCategory = { category, newLeafName ->
										runCatching {
											planLocalCategoryRename(
												categories = categories,
												sourceCategory = category,
												newLeafName = newLeafName,
												strings = AppLocaleStringResolver(context),
											)
										}.onSuccess { plan ->
											plan.updatedCategories.forEach(passwordViewModel::updateCategory)
										}.onFailure { error ->
											Toast.makeText(
												context,
												context.getString(R.string.save_failed_with_error, error.message ?: ""),
												Toast.LENGTH_SHORT,
											).show()
										}
									},
									onDeleteCategory = passwordViewModel::deleteCategory,
									getBitwardenFolders = passwordViewModel::getBitwardenFolders,
									getKeePassGroups = localKeePassViewModel::getGroups,
								)
							}
						}
						}
					}
					IconButton(onClick = { isSearchExpanded = true }) {
						Icon(
							imageVector = Icons.Default.Search,
							contentDescription = stringResource(R.string.search),
						)
					}
					Box {
						IconButton(
							onClick = {
								if (isAuthenticated) {
									isTopActionsMenuExpanded = true
								}
							},
							enabled = isAuthenticated
						) {
							Icon(
								imageVector = Icons.Default.MoreVert,
								contentDescription = stringResource(R.string.more_options),
							)
						}
					PasswordTopActionsDropdownMenu(
						expanded = isAuthenticated && isTopActionsMenuExpanded,
						onDismissRequest = { isTopActionsMenuExpanded = false }
						) {
                            takagi.ru.monica.ui.screens.DatabaseManagerMenuItem(selectedMdbxDatabaseId?.let {
                                takagi.ru.monica.credentialexchange.ImportDestination(takagi.ru.monica.credentialexchange.ImportDestinationKind.MDBX, it)
                            }) { isTopActionsMenuExpanded = false }

							VaultV2SortMenuItem(appSettings.vaultListSort) {
								isTopActionsMenuExpanded = false
								showSortSheet = true
							}
							selectedKeePassDatabaseId?.let { keepassDatabaseId ->
								KeepassRefreshTopActionsMenuItem(
									onClick = {
										isTopActionsMenuExpanded = false
										passwordViewModel.syncKeePassDatabaseForVisibleVault(
											databaseId = keepassDatabaseId,
											forceRefresh = true
										)
										bankCardViewModel.syncKeePassCards(keepassDatabaseId)
										documentViewModel.syncKeePassDocuments(keepassDatabaseId)
										noteViewModel.syncKeePassNotes(keepassDatabaseId)
									}
								)
								KeepassNativeManagerTopActionsMenuItem(
									onClick = {
										isTopActionsMenuExpanded = false
										onOpenKeePassNativeManager()
									}
								)
							}
                            if (
                                selectedMdbxDatabaseId != null &&
                                mdbxViewModel != null &&
                                selectedMdbxDatabase?.supports(MdbxCapability.REMOTE_SYNC) == true
                            ) {
								MdbxSyncTopActionsMenuItem(
									onClick = {
										isTopActionsMenuExpanded = false
										if (selectedMdbxDatabase?.mdbxPathShouldFlushPendingUpload() == true) {
											mdbxViewModel.flushPendingVaultUpload(selectedMdbxDatabaseId)
										} else {
											mdbxViewModel.syncVault(selectedMdbxDatabaseId)
										}
									}
								)
							}
							if (
								selectedMdbxDatabaseId != null &&
								mdbxViewModel != null &&
								selectedMdbxDatabase?.supports(MdbxCapability.SNAPSHOTS) == true
							) {
								MdbxCreateSnapshotTopActionsMenuItem(
									onClick = {
										isTopActionsMenuExpanded = false
										mdbxViewModel.createQuickSnapshot(selectedMdbxDatabaseId) { result ->
											val message = result.fold(
												onSuccess = { snapshot ->
													context.getString(
														R.string.mdbx_quick_snapshot_created,
														snapshot.name
													)
												},
												onFailure = { error ->
													context.getString(
														R.string.mdbx_quick_snapshot_failed,
														error.message ?: context.getString(R.string.mdbx_snapshot_unavailable)
													)
												}
											)
											Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
											mdbxViewModel.clearOperationState()
										}
									}
								)
							}
							if (
								selectedMdbxDatabaseId != null &&
								selectedMdbxDatabase?.supports(MdbxCapability.DELTA_HISTORY) == true
							) {
								MdbxCommitHistoryTopActionsMenuItem(
									onClick = {
										isTopActionsMenuExpanded = false
										onOpenMdbxCommitHistory(selectedMdbxDatabaseId)
									}
								)
							}
							selectedBitwardenVaultId?.let { selectedVaultId ->
								BitwardenSyncTopActionsMenuItem(
									isSyncing = isTopBarSyncing,
									enabled = !isTopBarSyncing && !isBitwardenMaintenanceActionRunning,
									onClick = {
										if (!isTopBarSyncing && !isBitwardenMaintenanceActionRunning) {
											isTopActionsMenuExpanded = false
											bitwardenViewModel.requestManualSync(selectedVaultId)
										}
									}
								)
								BitwardenReunlockTopActionsMenuItem(
									onClick = {
										isTopActionsMenuExpanded = false
										showBitwardenUnlockDialog = true
									}
								)
								BitwardenLockTopActionsMenuItem(
									onClick = {
										isTopActionsMenuExpanded = false
										scope.launch {
											runCatching {
												bitwardenRepository.forceLock(selectedVaultId)
											}.onSuccess {
												Toast.makeText(
													context,
													context.getString(R.string.current_database_locked),
													Toast.LENGTH_SHORT
												).show()
											}.onFailure { error ->
												Toast.makeText(
													context,
													context.getString(
														R.string.save_failed_with_error,
														error.message ?: ""
													),
													Toast.LENGTH_SHORT
												).show()
											}
										}
									}
								)
								BitwardenClearCacheTopActionsMenuItem(
									enabled = !isBitwardenMaintenanceActionRunning,
									onClick = {
										isTopActionsMenuExpanded = false
										scope.launch {
											runCatching {
												passwordViewModel.getBitwardenVaultCacheRiskSummary(selectedVaultId)
											}.onSuccess { summary ->
												clearCacheRiskSummary = summary
												showClearBitwardenCacheDialog = true
											}.onFailure { error ->
												Toast.makeText(
													context,
													context.getString(
														R.string.save_failed_with_error,
														error.message ?: ""
													),
													Toast.LENGTH_SHORT
												).show()
											}
										}
									}
								)
							}
							CommonPasswordTopActionsMenuItems(
								onDismissMenu = { isTopActionsMenuExpanded = false },
								onShowDisplayOptions = {},
								onOpenCommonAccountTemplates = onOpenCommonAccountTemplates,
								onScanFidoQr = onScanFidoQr,
								onOpenHistory = handleOpenHistory,
								onOpenTrash = handleOpenTrashPage,
								onOpenArchive = handleOpenArchivePage,
								showDisplayOptionsEntry = false,
								showArchiveEntry = !state.isArchiveView,
								showSettingsEntry = showStandaloneSettingsEntry,
								onOpenSettings = onOpenStandaloneSettings,
							)
						}
					}
				}
			)

			if (!state.isArchiveView) {
				VaultV2QuickStatusBar(
					pathLabel = storageFilterLabel,
					currentSectionLabel = currentSectionIndicatorLabel,
					dateSort = !appSettings.vaultListSort.isAlphabetical,
					breadcrumbs = pathBreadcrumbs,
					currentFilter = categoryMenuFilter,
					mdbxSyncState = mdbxQuickStatusSyncState,
					transferState = quickStatusTransferState,
					deleteState = quickStatusDeleteState,
					onNavigateFilter = navigateCategoryFilter,
				)
			}

			val contentPullOffset = pullAction.currentOffset.toInt()
			val listInteractionModifier = Modifier
				.offset { IntOffset(x = 0, y = contentPullOffset) }
				.then(pullAction.gestureModifier)
				.then(
					if (sectionedItems.isEmpty() && folderRows.isEmpty()) {
						Modifier.pointerInput(Unit) {
							detectVerticalDragGestures(
								onVerticalDrag = { _, dragAmount ->
									pullAction.onVerticalDrag(dragAmount)
								},
								onDragEnd = pullAction.onDragEnd,
								onDragCancel = pullAction.onDragCancel,
							)
						}
					} else {
						Modifier
					}
				)

			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.then(listInteractionModifier),
			) {
			PullSearchHint(
				currentOffset = pullAction.currentOffset,
				triggerDistance = pullSearchTriggerDistance,
				modifier = Modifier.offset { IntOffset(0, -contentPullOffset) },
			)
			VaultV2List(
                nativeTokens = nativeTokens,
				hasVisibleQuickFilters = showQuickFiltersInList || nativeTokens.visible,
				hasVisibleCategoryQuickFilters = showCategoryQuickFiltersInList,
				configuredQuickFilterItems = configuredQuickFilterItems,
				quickFilterChipState = quickFilterBindings.state,
				quickFilterChipCallbacks = quickFilterBindings.callbacks,
				categoryQuickFilterShortcuts = categoryMenuQuickFolderShortcuts,
				currentFilter = categoryMenuFilter,
				onNavigateFilter = navigateCategoryFilter,
				folderRows = folderRows,
				selectedFolderKey = selectedFolderKey,
				showCurrentFolderHeader = useHierarchicalLayout && sectionedItems.isNotEmpty(),
					onOpenFolder = { filter ->
					filter.toUnifiedCategoryFilterSelectionOrNull()?.let { selection ->
						state.pushFolderNavigationPosition(
							index = listState.firstVisibleItemIndex,
							offset = listState.firstVisibleItemScrollOffset,
						)
						selectedKeys.clear()
						pendingFolderScrollRestore = null
						state.updateStorageFilter(selection)
						state.requestScrollToTop()
					}
				},
				onLongClickFolder = { row ->
					selectedFolderKey = row.key
					folderActionRow = row
					folderActionCategory = (row.targetFilter as? CategoryFilter.Custom)?.categoryId
						?.let { id -> categories.firstOrNull { it.id == id } }
					val folderItems = vaultV2ItemsInFolder(
						targetFilter = row.targetFilter,
						allItems = allItems,
						quickFolderNodes = quickFolderNodes,
						selectedMdbxFolders = selectedMdbxFolders,
					)
					selectedKeys.clear()
					selectedKeys.addAll(folderItems.map(VaultV2Item::key))
					showFolderActionDialog = row.kind == VaultV2FolderRowKind.FOLDER
				},
				sections = sectionedItems,
				showLoadingIndicator = showVaultLoadingIndicator || (nativeTokens.loading && !hasDisplayedContent),
				showEmptyState = showVaultEmptyState && nativeTokens.entries.isEmpty() && !nativeTokens.loading && !nativeTokens.failed,
				emptyStateText = emptyStateText,
				listState = listState,
				passwordById = passwordById,
				appSettings = appSettings,
				securityManager = securityManager,
				selectedKeys = selectedKeys,
				onRequestDeleteItems = { items ->
					selectedKeys.clear()
					selectedKeys.addAll(items.map { it.key })
					showDeleteConfirmDialog = true
				},

				modifier = Modifier.fillMaxSize(),
				onOpenItem = { item ->
					when (item.type) {
						VaultV2ItemType.PASSWORD -> {
                            val token = item.nativeToken
                            if (token != null) onOpenApiTokens(token.databaseId, token.entryId)
                            else item.passwordEntry?.id?.let(onOpenPassword)
                        }
						VaultV2ItemType.AUTHENTICATOR -> {
							val totp = item.totpItem ?: return@VaultV2List
							if (totp.id > 0) {
								onOpenTotp(totp.id)
							} else {
								item.boundPasswordId?.let(onOpenPassword)
							}
						}
						VaultV2ItemType.NOTE -> item.secureItem?.id?.let(onOpenNote)
						VaultV2ItemType.PASSKEY -> {
							item.passkeyEntry?.id?.takeIf { it > 0L }?.let(onOpenPasskey)
						}
						VaultV2ItemType.BANK_CARD -> item.secureItem?.id?.let(onOpenBankCard)
						VaultV2ItemType.DOCUMENT -> item.secureItem?.id?.let(onOpenDocument)
						VaultV2ItemType.BILLING_ADDRESS -> item.secureItem?.id?.let(onOpenBillingAddress)
					}
				},
			)
			ExpressiveLazyListScrollbar(
				listState = listState,
				modifier = Modifier
					.align(Alignment.CenterEnd)
					.testTag("vault_scrollbar")
					.padding(vertical = 8.dp),
				labelForIndex = { lazyIndex ->
					vaultV2SectionTitleForLazyIndex(
						sectionLayouts = sectionLayouts,
						lazyIndex = lazyIndex,
					)?.trim()
				},
				onInteractionChange = state::updateFastScrollbarInteraction,
			)
		}
		}

		}
		if (appSettings.categorySelectionUiMode != CategorySelectionUiMode.CHIP_MENU) {
			UnifiedCategoryFilterBottomSheet(
				visible = isStorageFilterSheetVisible,
				onDismiss = { isStorageFilterSheetVisible = false },
				selected = storageSelection,
				onSelect = { selection ->
					selectedKeys.clear()
					state.clearFolderNavigationHistory()
					state.updateStorageFilter(selection)
					isStorageFilterSheetVisible = false
				},
				categories = categories,
				keepassDatabases = keepassDatabases,
				mdbxDatabases = mdbxDatabases,
				bitwardenVaults = bitwardenVaults,
				getBitwardenFolders = passwordViewModel::getBitwardenFolders,
				getMdbxFolders = passwordViewModel::getMdbxFolders,
				getKeePassGroups = localKeePassViewModel::getGroups,
				onCreateMdbxProject = { databaseId, parentFolderId, name ->
					scope.launch {
						passwordViewModel.createMdbxFolder(databaseId, name, parentFolderId ?: "root") { result ->
							result.exceptionOrNull()?.let { error ->
								Toast.makeText(
									context,
									context.getString(R.string.save_failed_with_error, error.message ?: ""),
									Toast.LENGTH_SHORT
								).show()
							}
						}
					}
				},
				quickFilterContent = {
					VaultV2QuickFilterFlow(
						configuredQuickFilterItems = configuredQuickFilterItems,
						chipState = quickFilterBindings.state,
						chipCallbacks = quickFilterBindings.callbacks,
					)
				},
			)
		}

		if (showBitwardenUnlockDialog && selectedBitwardenVault != null) {
			UnlockVaultDialog(
				email = selectedBitwardenVault.email,
				onUnlock = { masterPassword ->
					showBitwardenUnlockDialog = false
					scope.launch {
						when (val result = bitwardenRepository.unlock(
							selectedBitwardenVault.id,
							masterPassword
						)) {
							is BitwardenRepository.UnlockResult.Success -> {
								Toast.makeText(
									context,
									context.getString(R.string.current_database_unlocked),
									Toast.LENGTH_SHORT
								).show()
							}

							is BitwardenRepository.UnlockResult.Error -> {
								Toast.makeText(
									context,
									result.message,
									Toast.LENGTH_SHORT
								).show()
							}
						}
					}
				},
				onDismiss = { showBitwardenUnlockDialog = false }
			)
		}

		if (showClearBitwardenCacheDialog && selectedBitwardenVaultId != null && clearCacheRiskSummary != null) {
			val vaultId = selectedBitwardenVaultId
			val riskSummary = clearCacheRiskSummary!!
			val hasRisk = riskSummary.hasRisk
			val resetDialogState: () -> Unit = {
				showClearBitwardenCacheDialog = false
				clearCacheRiskSummary = null
			}

			AlertDialog(
				onDismissRequest = {
					if (!isBitwardenMaintenanceActionRunning) {
						resetDialogState()
					}
				},
				title = { Text(stringResource(R.string.bitwarden_clear_cache_confirm_title)) },
				text = {
					Text(
						if (hasRisk) {
							context.getString(
								R.string.bitwarden_clear_cache_confirm_message_with_risk,
								riskSummary.pendingOperationCount,
								riskSummary.passwordLocalModifiedCount,
								riskSummary.secureItemLocalModifiedCount,
								riskSummary.unresolvedConflictCount
							)
						} else {
							context.getString(R.string.bitwarden_clear_cache_confirm_message)
						}
					)
				},
				confirmButton = {
					TextButton(
						enabled = !isBitwardenMaintenanceActionRunning,
						onClick = {
							scope.launch {
								isBitwardenMaintenanceActionRunning = true
								runCatching {
									passwordViewModel.clearBitwardenVaultLocalCache(
										vaultId = vaultId,
										mode = if (hasRisk) {
											BitwardenRepository.CacheClearMode.SAFE_ONLY_SYNCED
										} else {
											BitwardenRepository.CacheClearMode.FULL_FORCE
										}
									)
								}.onSuccess { result ->
									val message = if (hasRisk) {
										context.getString(
											R.string.bitwarden_clear_cache_success_safe,
											result.totalClearedCount,
											result.protectedCipherCount
										)
									} else {
										context.getString(
											R.string.bitwarden_clear_cache_success,
											result.totalClearedCount
										)
									}
									Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
									resetDialogState()
								}.onFailure { error ->
									Toast.makeText(
										context,
										context.getString(
											R.string.bitwarden_clear_cache_failed,
											error.message ?: ""
										),
										Toast.LENGTH_SHORT
									).show()
								}
								isBitwardenMaintenanceActionRunning = false
							}
						}
					) {
						Text(
							stringResource(
								if (hasRisk) R.string.bitwarden_clear_cache_action_safe
								else R.string.bitwarden_clear_cache_action
							)
						)
					}
				},
				dismissButton = {
					Row {
						if (hasRisk) {
							TextButton(
								enabled = !isBitwardenMaintenanceActionRunning,
								onClick = {
									scope.launch {
										isBitwardenMaintenanceActionRunning = true
										runCatching {
											passwordViewModel.clearBitwardenVaultLocalCache(
												vaultId = vaultId,
												mode = BitwardenRepository.CacheClearMode.FULL_FORCE
											)
										}.onSuccess { result ->
											Toast.makeText(
												context,
												context.getString(
													R.string.bitwarden_clear_cache_force_success,
													result.totalClearedCount
												),
												Toast.LENGTH_SHORT
											).show()
											resetDialogState()
										}.onFailure { error ->
											Toast.makeText(
												context,
												context.getString(
													R.string.bitwarden_clear_cache_failed,
													error.message ?: ""
												),
												Toast.LENGTH_SHORT
											).show()
										}
										isBitwardenMaintenanceActionRunning = false
									}
								}
							) {
								Text(stringResource(R.string.bitwarden_clear_cache_action_force))
							}
						}
						TextButton(
							enabled = !isBitwardenMaintenanceActionRunning,
							onClick = { resetDialogState() }
						) {
							Text(stringResource(R.string.cancel))
						}
					}
				}
			)
		}

		categoryFolderTransferFailure?.let { (successCount, failedCount, details) ->
			AlertDialog(
				onDismissRequest = { categoryFolderTransferFailure = null },
				title = { Text(stringResource(R.string.category_folder_transfer_partial_title)) },
				text = {
					Text(
						if (failedCount > 0) {
							stringResource(
								R.string.category_folder_transfer_partial_message,
								successCount,
								failedCount,
								details,
							)
						} else {
							details
						}
					)
				},
				confirmButton = {
					TextButton(onClick = { categoryFolderTransferFailure = null }) {
						Text(stringResource(R.string.confirm))
					}
				},
			)
		}

		if (showFolderActionDialog) {
			val category = folderActionCategory
			val row = folderActionRow
			val canDeleteFolder = category != null || row?.targetFilter is CategoryFilter.KeePassGroupFilter ||
				row?.targetFilter is CategoryFilter.MdbxFolderFilter ||
				row?.targetFilter is CategoryFilter.BitwardenFolderFilter
			AlertDialog(
				onDismissRequest = {
					showFolderActionDialog = false
					selectedFolderKey = null
				},
				title = { Text(row?.title ?: category?.name ?: stringResource(R.string.v2_create_folder)) },
				text = { Text(stringResource(R.string.vault_v2_folder_action_hint)) },
				confirmButton = {
					TextButton(
						onClick = {
							showFolderActionDialog = false
							if (category != null) showFolderMoveSheet = true else selectedFolderKey = null
						}
					) { Text(stringResource(if (category != null) R.string.move_copy else R.string.vault_v2_folder_select_contents)) }
				},
				dismissButton = {
					Row {
						TextButton(
							enabled = canDeleteFolder,
							onClick = {
								showFolderActionDialog = false
								showFolderDeleteConfirmDialog = true
							}
						) { Text(stringResource(R.string.v2_delete_folder)) }
						TextButton(onClick = {
							showFolderActionDialog = false
							selectedFolderKey = null
						}) { Text(stringResource(R.string.cancel)) }
					}
				}
			)
		}

		if (showFolderDeleteConfirmDialog) {
			val row = folderActionRow
			AlertDialog(
				onDismissRequest = { showFolderDeleteConfirmDialog = false },
				title = { Text(stringResource(R.string.v2_delete_folder)) },
				text = { Text(stringResource(R.string.v2_delete_folder_confirm, row?.title.orEmpty())) },
				confirmButton = {
					TextButton(onClick = {
						when (val filter = row?.targetFilter) {
							is CategoryFilter.Custom -> folderActionCategory?.let(passwordViewModel::deleteCategory)
							is CategoryFilter.KeePassGroupFilter -> localKeePassViewModel.deleteGroup(
								databaseId = filter.databaseId,
								groupPath = filter.groupPath,
							)
							is CategoryFilter.MdbxFolderFilter -> passwordViewModel.deleteMdbxFolder(
								databaseId = filter.databaseId,
								folderId = filter.folderId,
							)
							is CategoryFilter.BitwardenFolderFilter -> scope.launch {
								bitwardenRepository.deleteFolder(filter.vaultId, filter.folderId)
							}
							else -> Unit
						}
						showFolderDeleteConfirmDialog = false
						folderActionRow = null
						folderActionCategory = null
						selectedFolderKey = null
						selectedKeys.clear()
					}) { Text(stringResource(R.string.v2_delete_folder)) }
				},
				dismissButton = {
					TextButton(onClick = { showFolderDeleteConfirmDialog = false }) {
						Text(stringResource(R.string.cancel))
					}
				},
			)
		}

		UnifiedMoveToCategoryBottomSheet(
			visible = showFolderMoveSheet && folderActionCategory != null,
			onDismiss = {
				showFolderMoveSheet = false
				folderActionCategory = null
				selectedFolderKey = null
			},
			initialSource = takagi.ru.monica.ui.components.UnifiedMoveInitialSource.MonicaLocal,
			categories = categories,
			keepassDatabases = keepassDatabases,
			mdbxDatabases = mdbxDatabases,
			bitwardenVaults = bitwardenVaults,
			getBitwardenFolders = passwordViewModel::getBitwardenFolders,
			getKeePassGroups = localKeePassViewModel::getGroups,
			getMdbxFolders = passwordViewModel::getMdbxFolders,
			allowCopy = true,
			allowMove = true,
			onTargetSelected = { target, action ->
				folderActionCategory?.let { category ->
					transferCategoryFolder(category, target, action)
				}
				showFolderMoveSheet = false
				folderActionCategory = null
				selectedFolderKey = null
			}
		)

		if (selectedCount > 0) {
			var showVaultMoveSheet by remember { mutableStateOf(false) }

			SelectionActionBar(
				modifier = Modifier
					.align(Alignment.BottomStart)
					.padding(start = 16.dp, bottom = 20.dp),
				selectedCount = selectedCount,
				onExit = { selectedKeys.clear() },
				onSelectAll = {
					val overviewModule = overviewSelection.module
					if (showOverview && overviewSelection.searchResults != null) {
						overviewSelection.selectSearch(selectionCandidates.map { it.key })
					} else if (showOverview && overviewModule != null) {
						overviewSelection.selectAll(overviewModule, selectionCandidates.map { it.key })
					} else {
						selectedKeys.clear()
						selectedKeys.addAll(filteredItems.map { it.key })
					}
				},
				onMoveToCategory = { showVaultMoveSheet = true },
                onStack = if (!showOverview && selectedItems.size >= 2 && selectedItems.all { it.type == VaultV2ItemType.PASSWORD }) ({
                    selectedStackMode = takagi.ru.monica.ui.ManualStackDialogMode.STACK
                    showStackDialog = true
                }) else null,
				onFavorite = {
					// 全部异步执行，避免主线程卡顿
					scope.launch {
						selectedItems.forEach { item ->
							when (item.type) {
								VaultV2ItemType.PASSWORD -> {
									item.passwordEntry?.let { entry ->
										if (item.nativeToken != null) {
                                            try { mdbxViewModel?.setNativeApiTokenFavorite(item.nativeToken, !item.isFavorite) }
                                            catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { Toast.makeText(context, R.string.api_token_load_error, Toast.LENGTH_LONG).show() }
                                        } else passwordViewModel.toggleFavorite(entry.id, !entry.isFavorite)
									}
								}

								VaultV2ItemType.AUTHENTICATOR -> {
									// 注意：不能用 return@forEach，改用 if 判断
									val id = item.totpItem?.id
									if (id != null && id > 0) {
										totpViewModel.toggleFavorite(id, !item.isFavorite)
									}
								}

								VaultV2ItemType.NOTE -> {
									item.secureItem?.let { note ->
										val decoded = NoteContentCodec.decodeFromItem(note)
										noteViewModel.updateNote(
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
											bitwardenFolderId = note.bitwardenFolderId,
										)
									}
								}

								VaultV2ItemType.PASSKEY -> Unit

								VaultV2ItemType.BANK_CARD -> {
									item.secureItem?.id?.let(bankCardViewModel::toggleFavorite)
								}

								VaultV2ItemType.DOCUMENT -> {
									item.secureItem?.id?.let(documentViewModel::toggleFavorite)
								}
								VaultV2ItemType.BILLING_ADDRESS -> {
									item.secureItem?.id?.let(billingAddressViewModel::toggleFavorite)
								}
							}
						}
						// 必须在协程内、所有操作完成后再清空，否则 selectedItems 会提前变空
						selectedKeys.clear()
					}
				},
				onRemoveFromFrequent = if (showOverview && overviewSelection.module == VaultOverviewModule.ITEMS) ({
					val identities = selectedItems.map { it.overviewIdentity() }
					settingsViewModel.updateVaultOverviewConfig { it.removeFrequentItems(identities) }
					overviewSelection.clear()
				}) else null,
				onDelete = if (showOverview && overviewSelection.module == VaultOverviewModule.ITEMS) null else ({
					// 先弹确认对话框，不直接删除
					if (quickStatusDeleteState?.phase != takagi.ru.monica.ui.password.PasswordBatchDeletePhase.RUNNING) {
						showDeleteConfirmDialog = true
					}
				}),
			)

			// 删除二次确认对话框（带指纹/密码验证）
			if (showDeleteConfirmDialog) {
				val doDelete = {
                    val selection = selectedItems.toList()
                    val passwords = selection.filter { it.type == VaultV2ItemType.PASSWORD && it.nativeToken == null }
                        .mapNotNull { it.passwordEntry }.distinctBy { it.id }
                    scope.launch {
                        if (passwords.isNotEmpty()) {
                            var completed = false
                            try {
                                val deleted = passwordViewModel.deletePasswordEntriesBatch(passwords) { processed, total ->
                                    PasswordBatchDeleteProgressTracker.update(processed, total)
                                }
                                PasswordBatchDeleteProgressTracker.complete(deleted)
                                completed = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                Toast.makeText(context, R.string.batch_delete_incomplete_title, Toast.LENGTH_LONG).show()
                            } finally {
                                if (!completed) PasswordBatchDeleteProgressTracker.fail()
                            }
                        }
					selection.forEach { item ->
						when (item.type) {
							VaultV2ItemType.PASSWORD -> {
								if (item.nativeToken != null) scope.launch {
                                    try {
                                        checkNotNull(mdbxViewModel).deleteNativeApiToken(item.nativeToken)
                                        aggregateStackRepository.clearManualStack(listOf(item.key))
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { Toast.makeText(context, R.string.api_token_load_error, Toast.LENGTH_LONG).show() }
                                }
							}
							VaultV2ItemType.AUTHENTICATOR -> {
								item.totpItem?.let { totp ->
									if (totp.id > 0) totpViewModel.deleteTotpItem(totp)
								}
							}
							VaultV2ItemType.NOTE -> {
								item.secureItem?.let(noteViewModel::deleteNote)
							}
							VaultV2ItemType.PASSKEY -> {
								item.passkeyEntry?.let { passkey ->
									scope.launch { passkeyViewModel.deletePasskey(passkey) }
								}
							}
							VaultV2ItemType.BANK_CARD -> {
								item.secureItem?.id?.let(bankCardViewModel::deleteCard)
							}
							VaultV2ItemType.DOCUMENT -> {
								item.secureItem?.id?.let(documentViewModel::deleteDocument)
							}
							VaultV2ItemType.BILLING_ADDRESS -> {
								item.secureItem?.id?.let(billingAddressViewModel::deleteAddress)
							}
						}
					}
					selectedKeys.removeAll(selection.map { it.key }.toSet())
                    }
				}
				takagi.ru.monica.ui.common.dialog.DeleteConfirmDialog(
					itemTitle = stringResource(R.string.selected_items, selectedCount),
					itemType = stringResource(R.string.vault_batch_delete_item_type),
					biometricEnabled = biometricEnabled,
					skipIdentityVerification = appSettings.disablePasswordVerification,
					onDismiss = { showDeleteConfirmDialog = false },
					onConfirmWithoutVerification = {
						showDeleteConfirmDialog = false
						doDelete()
					},
					onConfirmWithPassword = { password ->
						if (securityManager.unlockVaultWithPassword(password)) {
							showDeleteConfirmDialog = false
							doDelete()
						}
					},
					onConfirmWithBiometric = {
						showDeleteConfirmDialog = false
						doDelete()
					}
				)
			}

            val selectedNativeTokens = selectedItems.mapNotNull { it.nativeToken }
            if (mdbxViewModel != null) {
                takagi.ru.monica.ui.screens.NativeTokenBatchMoveSheet(
                    visible = showVaultMoveSheet && selectedNativeTokens.isNotEmpty(), viewModel = mdbxViewModel, entries = selectedNativeTokens,
                    onDismiss = { showVaultMoveSheet = false }, onCompleted = { selectedKeys.clear() },
                    onTransferOther = { target, action ->
                        val plan = buildVaultV2BatchMovePlan(selectedItems.filter { it.nativeToken == null })
                        val result = executeMixedPasswordBatchMove(context, action, target,
                            plan.passwordEntries, plan.aggregateSelection, categories, keepassDatabases,
                            localKeePassViewModel, securityManager, passwordViewModel,
                            PasswordBatchMoveViewModels(totpViewModel, bankCardViewModel, documentViewModel,
                                billingAddressViewModel, noteViewModel, passkeyViewModel), bitwardenRepository)
                        result.successCount to result.failedCount
                    })
            }
			// 移动/复制到其他文件夹/数据库
			UnifiedMoveToCategoryBottomSheet(
				visible = showVaultMoveSheet && selectedNativeTokens.isEmpty(),
				onDismiss = { showVaultMoveSheet = false },
				initialSource = storageSelection.toUnifiedMoveInitialSource(),
				categories = categories,
				keepassDatabases = keepassDatabases,
				mdbxDatabases = mdbxDatabases,
				bitwardenVaults = bitwardenVaults,
				getBitwardenFolders = passwordViewModel::getBitwardenFolders,
				getKeePassGroups = localKeePassViewModel::getGroups,
				getMdbxFolders = passwordViewModel::getMdbxFolders,
				allowCopy = true,
				allowMove = true,
				allowArchiveTarget = false,
				onTargetSelected = { target, requestedAction ->
					showVaultMoveSheet = false
					val movePlan = buildVaultV2BatchMovePlan(selectedItems.toList())
					if (movePlan.totalCount <= 0) {
						selectedKeys.clear()
					} else {
						val targetLabel = buildMoveTargetLabel(
							context = context,
							target = target,
							categories = categories,
							keepassDatabases = keepassDatabases,
							mdbxDatabases = mdbxDatabases,
						)
						val actionResolution = resolvePasswordBatchMoveAction(
							requestedAction = requestedAction,
							selectedEntries = movePlan.passwordEntries,
							target = target,
						)
						val effectiveAction = if (
							actionResolution.effectiveAction == UnifiedMoveAction.COPY ||
							shouldForceSupplementaryKeePassCopy(
								requestedAction = requestedAction,
								hasKeePassOwnedItems = movePlan.aggregateSelection.hasKeePassOwned,
								target = target,
							)
						) {
							UnifiedMoveAction.COPY
						} else {
							UnifiedMoveAction.MOVE
						}
						val notificationId =
							PasswordBatchTransferNotificationHelper.createNotificationId()
						val aggregateViewModels = PasswordBatchMoveViewModels(
							totpViewModel = totpViewModel,
							bankCardViewModel = bankCardViewModel,
							documentViewModel = documentViewModel,
							billingAddressViewModel = billingAddressViewModel,
							noteViewModel = noteViewModel,
							passkeyViewModel = passkeyViewModel,
						)
						val onProgressUpdate: (Int, Int) -> Unit = { processed, total ->
							val safeTotal = total.coerceAtLeast(movePlan.totalCount)
							val safeProcessed = processed.coerceIn(0, safeTotal)
							PasswordBatchTransferProgressTracker.update(
								action = effectiveAction,
								targetLabel = targetLabel,
								processed = safeProcessed,
								total = safeTotal,
							)
							PasswordBatchTransferNotificationHelper.showProgress(
								context = context,
								notificationId = notificationId,
								action = effectiveAction,
								processed = safeProcessed,
								total = safeTotal,
								targetLabel = targetLabel,
							)
						}
						onProgressUpdate(0, movePlan.totalCount)
						selectedKeys.clear()
						passwordViewModel.viewModelScope.launch {
							runCatching {
								executeMixedPasswordBatchMove(
									context = context,
									action = effectiveAction,
									target = target,
									selectedEntries = movePlan.passwordEntries,
									aggregateSelection = movePlan.aggregateSelection,
									categories = categories,
									keepassDatabases = keepassDatabases,
									localKeePassViewModel = localKeePassViewModel,
									securityManager = securityManager,
									viewModel = passwordViewModel,
									aggregateViewModels = aggregateViewModels,
									bitwardenRepository = bitwardenRepository,
									onProgress = onProgressUpdate,
								)
							}.onSuccess { result ->
								if (result.successCount > 0) {
									PasswordBatchTransferProgressTracker.complete(
										action = effectiveAction,
										targetLabel = targetLabel,
										successCount = result.successCount,
									)
								} else {
									PasswordBatchTransferProgressTracker.clear()
								}
								PasswordBatchTransferNotificationHelper.showCompleted(
									context = context,
									notificationId = notificationId,
									action = effectiveAction,
									successCount = result.successCount,
									failedCount = result.failedCount,
								)
								if (result.failedCount > 0) {
									Toast.makeText(
										context,
										context.getString(
											R.string.password_batch_transfer_partial_result,
											result.successCount,
											result.failedCount,
										),
										Toast.LENGTH_SHORT,
									).show()
								}
							}.onFailure { error ->
								Log.e("VaultV2BatchMove", "Unified vault move failed", error)
								PasswordBatchTransferProgressTracker.clear()
								PasswordBatchTransferNotificationHelper.showCompleted(
									context = context,
									notificationId = notificationId,
									action = effectiveAction,
									successCount = 0,
									failedCount = movePlan.totalCount,
								)
								Toast.makeText(
									context,
									context.getString(
										R.string.webdav_operation_failed,
										error.message.orEmpty(),
									),
									Toast.LENGTH_SHORT,
								).show()
							}
						}
					}
				}
			)
		}

	}

	if (showCreateCategoryDialog) {
		val currentSelection = state.toUnifiedCategoryFilterSelection()
		val initialLocalParentPath = (currentSelection as? UnifiedCategoryFilterSelection.Custom)?.let { filter ->
			categories.firstOrNull { it.id == filter.categoryId }?.name
		}
		val (initialDialogTarget, initialDialogKeePassDbId, initialDialogBitwardenVaultId) = when (val filter = currentSelection) {
			is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> Triple(CreateDialogTarget.KeePass, filter.databaseId, null)
			is UnifiedCategoryFilterSelection.KeePassGroupFilter -> Triple(CreateDialogTarget.KeePass, filter.databaseId, null)
			is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> Triple(CreateDialogTarget.KeePass, filter.databaseId, null)
			is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> Triple(CreateDialogTarget.KeePass, filter.databaseId, null)
			is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> Triple(CreateDialogTarget.Mdbx, filter.databaseId, null)
			is UnifiedCategoryFilterSelection.MdbxFolderFilter -> Triple(CreateDialogTarget.Mdbx, filter.databaseId, null)
			is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> Triple(CreateDialogTarget.Bitwarden, null, filter.vaultId)
			is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> Triple(CreateDialogTarget.Bitwarden, null, filter.vaultId)
			is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> Triple(CreateDialogTarget.Bitwarden, null, filter.vaultId)
			is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> Triple(CreateDialogTarget.Bitwarden, null, filter.vaultId)
			else -> Triple(null, null, null)
		}
		CreateCategoryDialog(
			visible = true,
			onDismiss = { showCreateCategoryDialog = false },
			categories = categories,
			keepassDatabases = keepassDatabases,
			mdbxDatabases = mdbxDatabases,
			bitwardenVaults = bitwardenVaults,
			getKeePassGroups = localKeePassViewModel::getGroups,
			getMdbxFolders = passwordViewModel::getMdbxFolders,
			onCreateCategoryWithName = { name -> passwordViewModel.addCategory(name) },
			onCreateBitwardenFolder = { vaultId, name ->
				scope.launch {
					val result = bitwardenRepository.createFolder(vaultId, name)
					result.exceptionOrNull()?.let { error ->
						Toast.makeText(
							context,
							context.getString(R.string.save_failed_with_error, error.message ?: ""),
							Toast.LENGTH_SHORT
						).show()
					}
				}
			},
			onCreateKeePassGroup = { databaseId, parentPath, name ->
				localKeePassViewModel.createGroup(
					databaseId = databaseId,
					groupName = name,
					parentPath = parentPath
				) { result ->
					result.exceptionOrNull()?.let { error ->
						Toast.makeText(
							context,
							context.getString(R.string.save_failed_with_error, error.message ?: ""),
							Toast.LENGTH_SHORT
						).show()
					}
				}
			},
			initialMdbxParentFolderId = (storageSelection as? UnifiedCategoryFilterSelection.MdbxFolderFilter)?.folderId,
			onCreateMdbxProject = { databaseId, parentFolderId, name ->
				scope.launch {
					passwordViewModel.createMdbxFolder(databaseId, name, parentFolderId ?: "root") { result ->
						result.exceptionOrNull()?.let { error ->
							Toast.makeText(
								context,
								context.getString(R.string.save_failed_with_error, error.message ?: ""),
								Toast.LENGTH_SHORT
							).show()
						}
					}
				}
			},
			initialLocalParentPath = initialLocalParentPath,
			initialTarget = initialDialogTarget,
			initialKeePassDbId = initialDialogKeePassDbId,
			initialKeePassParentPath = (currentSelection as? UnifiedCategoryFilterSelection.KeePassGroupFilter)?.groupPath,
			initialMdbxDbId = selectedMdbxDatabaseId,
			initialBitwardenVaultId = initialDialogBitwardenVaultId
		)
	}
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun VaultV2List(
    nativeTokens: NativeTokenListUi? = null,
	hasVisibleQuickFilters: Boolean,
	hasVisibleCategoryQuickFilters: Boolean,
	configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
	quickFilterChipState: PasswordQuickFilterChipState,
	quickFilterChipCallbacks: PasswordQuickFilterChipCallbacks,
	categoryQuickFilterShortcuts: List<PasswordQuickFolderShortcut>,
	currentFilter: CategoryFilter,
	onNavigateFilter: (CategoryFilter) -> Unit,
	folderRows: List<VaultV2FolderRowModel>,
	selectedFolderKey: String?,
	showCurrentFolderHeader: Boolean,
	onOpenFolder: (CategoryFilter) -> Unit,
	onLongClickFolder: (VaultV2FolderRowModel) -> Unit,
	sections: List<Pair<String, List<VaultV2Item>>>,
	showLoadingIndicator: Boolean,
	showEmptyState: Boolean,
	emptyStateText: String,
	listState: LazyListState,
	passwordById: Map<Long, PasswordEntry>,
	appSettings: AppSettings,
	securityManager: SecurityManager,
	selectedKeys: MutableList<String>,
	onRequestDeleteItems: (List<VaultV2Item>) -> Unit,
	modifier: Modifier = Modifier,
	onOpenItem: (VaultV2Item) -> Unit,
) {
	val categoryQuickFilterScrollState = rememberScrollState()

	LazyColumn(
		state = listState,
		modifier = modifier,
		contentPadding = PaddingValues(start = 12.dp, end = 28.dp, top = 8.dp, bottom = 96.dp),
		verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing),
	) {
		if (hasVisibleQuickFilters || hasVisibleCategoryQuickFilters) {
			item(key = "filter_row", contentType = "filter_row") {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 8.dp),
					verticalArrangement = Arrangement.spacedBy(0.dp)
				) {
					if (hasVisibleQuickFilters) {
						VaultV2QuickFilterRow(
                            nativeTokens = nativeTokens,
							configuredQuickFilterItems = configuredQuickFilterItems,
							chipState = quickFilterChipState,
							chipCallbacks = quickFilterChipCallbacks,
						)
					}

					if (hasVisibleCategoryQuickFilters) {
						PasswordQuickFolderChipRow(
							params = PasswordQuickFolderChipRowParams(
								currentFilter = currentFilter,
								quickFolderShortcuts = categoryQuickFilterShortcuts,
								onSelectFilter = onNavigateFilter,
							),
							scrollState = categoryQuickFilterScrollState,
						)
					}
				}
			}
		}

        if (nativeTokens?.failed == true) item("native_token_error") {
            TextButton(onClick = nativeTokens.onRetry) { Text(stringResource(R.string.api_token_reload)) }
        }
		itemsIndexed(
			items = folderRows,
			key = { _, row -> row.key },
			contentType = { _, _ -> "folder_row" },
		) { index, folderRow ->
			VaultV2FolderRow(
				row = folderRow,
				onClick = { onOpenFolder(folderRow.targetFilter) },
				onLongClick = { onLongClickFolder(folderRow) },
				selected = selectedFolderKey == folderRow.key,
				shape = GroupedItemDefaults.shape(index, folderRows.size),
			)
		}

		if (showCurrentFolderHeader && sections.isNotEmpty()) {
			item(key = "current_folder_header", contentType = "section_header") {
				Text(
					text = stringResource(R.string.vault_v2_hierarchy_current_folder),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
				)
			}
		}

		if (folderRows.isEmpty() && sections.isEmpty() && showLoadingIndicator) {
			item(key = "loading", contentType = "loading") {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 56.dp),
					contentAlignment = Alignment.Center,
				) {
					PasswordListInitialLoadingIndicator()
				}
			}
		}

		if (folderRows.isEmpty() && sections.isEmpty() && showEmptyState) {
			item(key = "empty", contentType = "empty") {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 40.dp),
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = emptyStateText,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}

		sections.forEach { (section, itemsInSection) ->
			item(key = "header:$section", contentType = "section_header") {
				Text(
					text = section,
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 12.dp, vertical = 8.dp),
				)
			}

			itemsIndexed(
				items = itemsInSection,
				key = { _, item -> item.key },
				contentType = { _, item -> item.type },
			) { index, item ->

				val selected = item.key in selectedKeys
				val cardShape = GroupedItemDefaults.shape(index, itemsInSection.size)
				SwipeActions(
					onSwipeLeft = { onRequestDeleteItems(listOf(item)) },
					onSwipeRight = {
						if (selected) {
							selectedKeys.remove(item.key)
						} else {
							selectedKeys.add(item.key)
						}
					},
					isSwiped = false,
					cardShape = cardShape,
				) {
					VaultV2ItemCard(
						item = item,
						boundPassword = when (item.type) {
							VaultV2ItemType.AUTHENTICATOR,
							VaultV2ItemType.PASSKEY -> item.boundPasswordId?.let(passwordById::get)
							else -> null
						},
						appSettings = appSettings,
						securityManager = securityManager,
						selected = selected,
						cardShape = cardShape,
						onClick = {
							if (selectedKeys.isNotEmpty()) {
								if (selected) {
									selectedKeys.remove(item.key)
								} else {
									selectedKeys.add(item.key)
								}
							} else {
								onOpenItem(item)
							}
						},
						onLongClick = {
							if (selected) {
								selectedKeys.remove(item.key)
							} else {
								selectedKeys.add(item.key)
							}
						}
					)
				}
			}
		}
	}
}

@Composable
private fun VaultV2QuickStatusBar(
	dateSort: Boolean,
	pathLabel: String,
	currentSectionLabel: String,
	breadcrumbs: List<PasswordQuickFolderBreadcrumb>,
	currentFilter: CategoryFilter,
	mdbxSyncState: MdbxPathSyncState?,
	transferState: PasswordBatchTransferGlobalProgressState?,
	deleteState: PasswordBatchDeleteGlobalProgressState?,
	onNavigateFilter: (CategoryFilter) -> Unit,
) {
	if (transferState != null || deleteState != null) {
		PasswordQuickFolderBreadcrumbBanner(
			breadcrumbs = breadcrumbs,
			currentFilter = currentFilter,
			onNavigate = onNavigateFilter,
			mdbxSyncState = mdbxSyncState,
			transferState = transferState,
			deleteState = deleteState,
		)
		return
	}
	QuickStatusBar(
		itemSpacing = 4.dp,
		indicator = {
			VaultV2QuickStatusIndicator(currentSectionLabel = currentSectionLabel, dateSort = dateSort)
		},
		breadcrumb = {
			VaultV2BreadcrumbPath(
				pathLabel = pathLabel,
				breadcrumbs = breadcrumbs,
				currentFilter = currentFilter,
				onNavigateFilter = onNavigateFilter,
				modifier = Modifier.weight(1f)
			)
		},
		actions = {
			mdbxSyncState?.let { state ->
				MdbxPathSyncActions(state = state)
			}
		}
	)
}

@Composable
private fun VaultV2QuickStatusIndicator(
	dateSort: Boolean,
	currentSectionLabel: String
) {
	Surface(
		shape = CircleShape,
		color = MaterialTheme.colorScheme.secondaryContainer,
		contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
		tonalElevation = 2.dp,
		shadowElevation = 0.dp,
		modifier = Modifier.size(32.dp)
	) {
		Box(contentAlignment = Alignment.Center) {
			if (dateSort) {
				Icon(Icons.Default.DateRange, contentDescription = currentSectionLabel, modifier = Modifier.size(20.dp))
			} else Text(
				text = currentSectionLabel.ifBlank { "#" },
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.SemiBold,
			)
		}
	}
}

@Composable
private fun VaultV2BreadcrumbPath(
	pathLabel: String,
	breadcrumbs: List<PasswordQuickFolderBreadcrumb>,
	currentFilter: CategoryFilter,
	onNavigateFilter: (CategoryFilter) -> Unit,
	modifier: Modifier = Modifier
) {
	if (breadcrumbs.isNotEmpty()) {
		PasswordQuickFolderBreadcrumbPath(
			breadcrumbs = breadcrumbs,
			currentFilter = currentFilter,
			onNavigate = onNavigateFilter,
			modifier = modifier,
			horizontalContentPadding = 6.dp,
		)
	} else {
		Box(
			modifier = modifier
				.height(36.dp)
				.clip(RoundedCornerShape(14.dp))
				.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
		) {
			Row(
				modifier = Modifier
					.fillMaxSize()
					.padding(horizontal = 6.dp, vertical = 6.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Box(
					modifier = Modifier
						.clip(RoundedCornerShape(10.dp))
						.background(MaterialTheme.colorScheme.primaryContainer)
						.padding(horizontal = 10.dp, vertical = 4.dp)
				) {
					Text(
						text = pathLabel,
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onPrimaryContainer,
					)
				}
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VaultV2ItemCard(
	item: VaultV2Item,
	boundPassword: PasswordEntry?,
	appSettings: AppSettings,
	securityManager: SecurityManager,
	selected: Boolean,
	cardShape: Shape,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val icon = when (item.type) {
		VaultV2ItemType.PASSWORD -> Icons.Default.Lock
		VaultV2ItemType.AUTHENTICATOR -> Icons.Default.Security
		VaultV2ItemType.NOTE -> Icons.Default.Description
		VaultV2ItemType.PASSKEY -> Icons.Default.VpnKey
		VaultV2ItemType.BANK_CARD -> Icons.Default.CreditCard
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> Icons.Default.Badge
	}
	val passwordIconSource = item.passwordEntry ?: boundPassword
	val totpData = remember(item.totpItem?.itemData, item.totpItem?.title, securityManager) {
		item.totpItem?.let { totpItem ->
			parseVaultV2TotpItemData(totpItem, securityManager)
		}
	}
	val iconWebsite = when {
		passwordIconSource != null -> passwordIconSource.website
		item.type == VaultV2ItemType.PASSKEY -> {
			val iconUrl = item.passkeyEntry?.iconUrl?.trim().orEmpty()
			if (iconUrl.isNotBlank()) {
				iconUrl
			} else {
				normalizeVaultV2PasskeyWebsite(item.passkeyEntry?.rpId)
			}
		}
		else -> totpData?.link?.trim().orEmpty()
	}
	val iconTitle = when {
		passwordIconSource != null -> passwordIconSource.title
		item.type == VaultV2ItemType.PASSKEY -> {
			item.passkeyEntry?.rpName?.ifBlank { item.title } ?: item.title
		}
		else -> totpData?.issuer?.ifBlank { item.title } ?: item.title
	}
	val iconAppPackage = when {
		passwordIconSource != null -> passwordIconSource.appPackageName
		else -> totpData?.associatedApp?.trim().orEmpty()
	}
	val customIconType = when {
		passwordIconSource != null -> passwordIconSource.customIconType
		else -> totpData?.customIconType ?: PASSWORD_ICON_TYPE_NONE
	}
	val customIconValue = when {
		passwordIconSource != null -> passwordIconSource.customIconValue
		else -> totpData?.customIconValue
	}
	val authenticatorState = when (item.type) {
		VaultV2ItemType.PASSWORD -> {
			val authenticatorKey = item.passwordEntry?.authenticatorKey.orEmpty()
			val fallbackIssuer = item.passwordEntry?.website.orEmpty().ifBlank {
				item.passwordEntry?.title.orEmpty()
			}
			val fallbackAccountName = item.passwordEntry?.username.orEmpty().ifBlank {
				item.passwordEntry?.title.orEmpty()
			}
			if (authenticatorKey.isBlank()) {
				null
			} else {
				rememberPasswordAuthenticatorDisplayState(
					authenticatorKey = authenticatorKey,
					fallbackIssuer = fallbackIssuer,
					fallbackAccountName = fallbackAccountName,
					timeOffsetSeconds = appSettings.totpTimeOffset,
					smoothProgress = appSettings.validatorSmoothProgress,
					decryptAuthenticatorKey = { value: String ->
						runCatching {
							securityManager.decryptDataIfMonicaCiphertext(value)
						}.getOrDefault(value)
					}
				)
			}
		}
		VaultV2ItemType.AUTHENTICATOR -> {
			rememberVaultV2TotpDisplayState(
				totpData = totpData,
				timeOffsetSeconds = appSettings.totpTimeOffset,
				smoothProgress = appSettings.validatorSmoothProgress
			)
		}
		else -> null
	}

	Surface(
		shape = cardShape,
		color = if (selected) {
			MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
		} else {
			MaterialTheme.colorScheme.surfaceContainerLow
		},
		modifier = Modifier
			.fillMaxWidth()
			.testTag("vault_item_${item.key}")
			.clip(cardShape)
			.combinedClickable(onClick = onClick, onLongClick = onLongClick),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 10.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			VaultItemIcon(
				website = iconWebsite,
				title = iconTitle,
				appPackageName = iconAppPackage,
				customIconType = customIconType,
				customIconValue = customIconValue,
				defaultIcon = icon,
				keepassDatabaseId = item.keepassDatabaseId(),
				keepassEntryUuid = item.keepassEntryUuid(),
			)
			Spacer(modifier = Modifier.width(2.dp))

			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Text(
					text = item.title,
					style = MaterialTheme.typography.titleSmall,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = item.subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				authenticatorState?.let { state ->
					VaultV2AuthenticatorInlineRow(
						state = state,
						smoothProgress = appSettings.validatorSmoothProgress,
					)
				}
			}

			if (item.isFavorite) {
				Icon(
					imageVector = Icons.Outlined.Favorite,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(16.dp),
				)
				Spacer(modifier = Modifier.width(2.dp))
			}

			Icon(
				imageVector = Icons.AutoMirrored.Filled.ArrowRight,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(18.dp),
			)
		}
	}
}

@Composable
private fun VaultV2AuthenticatorInlineRow(
	state: PasswordAuthenticatorDisplayState,
	smoothProgress: Boolean,
) {
	val progressColor = when {
		(state.remainingSeconds ?: Int.MAX_VALUE) <= 5 -> MaterialTheme.colorScheme.error
		else -> MaterialTheme.colorScheme.primary
	}

	Column(
		modifier = Modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				imageVector = Icons.Default.Security,
				contentDescription = null,
				modifier = Modifier.size(14.dp),
				tint = progressColor,
			)
			Text(
				text = state.code,
				style = MaterialTheme.typography.titleSmall.copy(
					fontWeight = FontWeight.SemiBold,
					fontFamily = FontFamily.Monospace,
				),
				color = progressColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			state.remainingSeconds?.let { remaining ->
				Text(
					text = stringResource(R.string.password_card_authenticator_seconds, remaining),
					style = MaterialTheme.typography.labelSmall.copy(
						fontFamily = FontFamily.Monospace,
					),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		state.progress?.let { progress ->
			val animatedProgress = if (smoothProgress) {
				animateFloatAsState(
					targetValue = progress.coerceIn(0f, 1f),
					animationSpec = tween(durationMillis = 80, easing = LinearEasing),
					label = "vault_v2_auth_progress",
				).value
			} else {
				progress.coerceIn(0f, 1f)
			}
			LinearProgressIndicator(
				progress = { animatedProgress },
				modifier = Modifier
					.fillMaxWidth()
					.height(4.dp),
				color = progressColor,
				trackColor = MaterialTheme.colorScheme.surfaceVariant,
			)
		}
	}
}

@Composable
private fun rememberVaultV2TotpDisplayState(
	totpData: TotpData?,
	timeOffsetSeconds: Int,
	smoothProgress: Boolean,
): PasswordAuthenticatorDisplayState? {
	val normalizedTotpData = remember(totpData) {
		totpData?.let(::normalizeVaultV2TotpData)
	} ?: return null

	val currentTimeMillis = rememberTotpTickerMillis(smoothProgress)
	val currentSeconds = currentTimeMillis / 1000L
	val rawCode = remember(normalizedTotpData, currentSeconds, timeOffsetSeconds) {
		when (normalizedTotpData.otpType) {
			OtpType.HOTP -> TotpGenerator.generateOtp(normalizedTotpData)
			else -> TotpGenerator.generateOtp(
				totpData = normalizedTotpData,
				timeOffset = timeOffsetSeconds,
				currentSeconds = currentSeconds,
			)
		}
	}
	val formattedCode = remember(rawCode, normalizedTotpData.otpType) {
		formatVaultV2OtpCode(rawCode, normalizedTotpData.otpType)
	}

	return if (normalizedTotpData.otpType == OtpType.HOTP) {
		PasswordAuthenticatorDisplayState(
			code = formattedCode,
			remainingSeconds = null,
			progress = null,
		)
	} else {
		val remainingSeconds = remember(normalizedTotpData, currentSeconds, timeOffsetSeconds) {
			TotpGenerator.getRemainingSeconds(
				period = normalizedTotpData.period,
				timeOffset = timeOffsetSeconds,
				currentSeconds = currentSeconds,
			)
		}
		val progress = remember(
			normalizedTotpData,
			currentTimeMillis,
			currentSeconds,
			timeOffsetSeconds,
			smoothProgress,
		) {
			if (smoothProgress) {
				val periodMillis = (normalizedTotpData.period * 1000L).coerceAtLeast(1000L)
				val correctedMillis = currentTimeMillis + (timeOffsetSeconds * 1000L)
				val elapsedInPeriod = ((correctedMillis % periodMillis) + periodMillis) % periodMillis
				(elapsedInPeriod.toFloat() / periodMillis.toFloat()).coerceIn(0f, 1f)
			} else {
				TotpGenerator.getProgress(
					period = normalizedTotpData.period,
					timeOffset = timeOffsetSeconds,
					currentSeconds = currentSeconds,
				).coerceIn(0f, 1f)
			}
		}
		PasswordAuthenticatorDisplayState(
			code = formattedCode,
			remainingSeconds = remainingSeconds,
			progress = progress,
		)
	}
}

private fun normalizeVaultV2TotpData(data: TotpData): TotpData {
	val safePeriod = data.period.takeIf { it > 0 } ?: 30
	val safeDigits = data.digits.coerceIn(4, 10)
	return if (safePeriod == data.period && safeDigits == data.digits) {
		data
	} else {
		data.copy(period = safePeriod, digits = safeDigits)
	}
}

private fun formatVaultV2OtpCode(code: String, otpType: OtpType): String {
	return when (otpType) {
		OtpType.STEAM -> {
			if (code.length == 5) {
				"${code.substring(0, 2)} ${code.substring(2)}"
			} else {
				code
			}
		}
		else -> {
			when (code.length) {
				6 -> "${code.substring(0, 3)} ${code.substring(3)}"
				8 -> "${code.substring(0, 4)} ${code.substring(4)}"
				else -> code
			}
		}
	}
}

private fun vaultV2PlainSingleLine(raw: String): String {
	return raw.lineSequence()
		.map { it.trim() }
		.firstOrNull { it.isNotEmpty() }
		.orEmpty()
}

private fun vaultV2BankCardSubtitle(
	data: BankCardData?,
	fallbackNotes: String,
): String {
	if (data == null) {
		return fallbackNotes.ifBlank { "-" }
	}
	return listOf(
		data.bankName.takeIf { it.isNotBlank() },
		data.cardholderName.takeIf { it.isNotBlank() },
		vaultV2MaskedCardNumber(data.cardNumber),
	).filterNotNull()
		.joinToString(" · ")
		.ifBlank { fallbackNotes.ifBlank { "-" } }
}

private fun vaultV2DocumentSubtitle(
	data: DocumentData?,
	fallbackNotes: String,
): String {
	if (data == null) {
		return fallbackNotes.ifBlank { "-" }
	}
	return listOf(
		data.fullName.takeIf { it.isNotBlank() },
		data.documentNumber.takeIf { it.isNotBlank() }?.let {
			maskDocumentNumberForPreview(it, data.documentType)
		},
		data.issuedBy.takeIf { it.isNotBlank() },
	).filterNotNull()
		.joinToString(" · ")
		.ifBlank { fallbackNotes.ifBlank { "-" } }
}

private fun vaultV2MaskedCardNumber(cardNumber: String): String? {
	val compact = cardNumber.filter { it.isDigit() }
	if (compact.isBlank()) return null
	val tail = compact.takeLast(4)
	return "•••• $tail"
}

private fun dedupeExactVaultItems(items: List<VaultV2Item>): List<VaultV2Item> {
	if (items.size <= 1) return items

	val indexByKey = items.mapIndexed { index, item -> item.key to index }.toMap()
	return items
		.groupBy(::buildVaultV2ExactDisplayKey)
		.values
		.mapNotNull(::pickBestVaultV2Item)
		.sortedBy { indexByKey[it.key] ?: Int.MAX_VALUE }
}

private fun buildVaultV2ExactDisplayKey(item: VaultV2Item): String {
	return when (item.type) {
		VaultV2ItemType.PASSWORD -> {
			val entry = item.passwordEntry
			listOf(
				item.type.name,
				buildVaultV2SourceKey(
					categoryId = entry?.categoryId,
					keepassDatabaseId = entry?.keepassDatabaseId,
					keepassEntryUuid = entry?.keepassEntryUuid,
					keepassGroupPath = entry?.keepassGroupPath,
					bitwardenVaultId = entry?.bitwardenVaultId,
					bitwardenCipherId = entry?.bitwardenCipherId,
					bitwardenFolderId = entry?.bitwardenFolderId,
				),
				normalizeVaultV2ComparableText(item.title),
				normalizeVaultV2ComparableText(entry?.username.orEmpty()),
				normalizeVaultV2Website(entry?.website.orEmpty()),
				entry?.replicaGroupId.orEmpty(),
				entry?.id?.toString().orEmpty(),
			).joinToString("|")
		}

		VaultV2ItemType.AUTHENTICATOR -> {
			val secureItem = item.totpItem
			listOf(
				item.type.name,
				buildVaultV2SourceKey(
					categoryId = secureItem?.categoryId,
					keepassDatabaseId = secureItem?.keepassDatabaseId,
					keepassEntryUuid = secureItem?.keepassEntryUuid,
					keepassGroupPath = secureItem?.keepassGroupPath,
					bitwardenVaultId = secureItem?.bitwardenVaultId,
					bitwardenCipherId = secureItem?.bitwardenCipherId,
					bitwardenFolderId = secureItem?.bitwardenFolderId,
				),
				normalizeVaultV2ComparableText(item.title),
				normalizeVaultV2ComparableText(item.subtitle),
				secureItem?.itemData.orEmpty(),
				secureItem?.notes.orEmpty(),
			).joinToString("|")
		}

		VaultV2ItemType.NOTE,
		VaultV2ItemType.BANK_CARD,
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> {
			val secureItem = item.secureItem
			listOf(
				item.type.name,
				buildVaultV2SourceKey(
					categoryId = secureItem?.categoryId,
					keepassDatabaseId = secureItem?.keepassDatabaseId,
					keepassEntryUuid = secureItem?.keepassEntryUuid,
					keepassGroupPath = secureItem?.keepassGroupPath,
					bitwardenVaultId = secureItem?.bitwardenVaultId,
					bitwardenCipherId = secureItem?.bitwardenCipherId,
					bitwardenFolderId = secureItem?.bitwardenFolderId,
				),
				normalizeVaultV2ComparableText(item.title),
				normalizeVaultV2ComparableText(item.subtitle),
				secureItem?.itemData.orEmpty(),
				secureItem?.notes.orEmpty(),
			).joinToString("|")
		}

		VaultV2ItemType.PASSKEY -> {
			val passkey = item.passkeyEntry
			listOf(
				item.type.name,
				normalizeVaultV2ComparableText(item.title),
				normalizeVaultV2ComparableText(item.subtitle),
				normalizeVaultV2ComparableText(passkey?.rpId.orEmpty()),
				normalizeVaultV2ComparableText(passkey?.userName.orEmpty()),
				normalizeVaultV2ComparableText(passkey?.userDisplayName.orEmpty()),
				normalizeVaultV2ComparableText(passkey?.notes.orEmpty()),
			).joinToString("|")
		}
	}
}

private fun pickBestVaultV2Item(candidates: List<VaultV2Item>): VaultV2Item? {
	return candidates.maxWithOrNull(
		compareBy<VaultV2Item> { it.subtitle.length }
			.thenBy { item -> item.searchableValues.sumOf(String::length) }
			.thenBy { if (it.isFavorite) 1 else 0 }
			.thenBy { vaultV2UpdatedAtMillis(it) }
	)
}

private fun buildVaultV2SourceKey(
	categoryId: Long?,
	keepassDatabaseId: Long?,
	keepassEntryUuid: String?,
	keepassGroupPath: String?,
	bitwardenVaultId: Long?,
	bitwardenCipherId: String?,
	bitwardenFolderId: String?,
): String {
	return when {
		keepassDatabaseId != null -> {
			"kp:$keepassDatabaseId:${keepassEntryUuid.orEmpty()}:${keepassGroupPath.orEmpty()}"
		}
		bitwardenVaultId != null -> {
			"bw:$bitwardenVaultId:${bitwardenCipherId.orEmpty()}:${bitwardenFolderId.orEmpty()}"
		}
		else -> "local:${categoryId ?: -1L}"
	}
}

private fun normalizeVaultV2ComparableText(value: String): String {
	return value.trim().lowercase(Locale.ROOT)
}

private fun normalizeVaultV2Website(value: String): String {
	val raw = value.trim()
	if (raw.isEmpty()) return ""
	return raw
		.lowercase(Locale.ROOT)
		.removePrefix("http://")
		.removePrefix("https://")
		.removePrefix("www.")
		.trimEnd('/')
}

private fun vaultV2UpdatedAtMillis(item: VaultV2Item): Long {
	return when (item.type) {
		VaultV2ItemType.PASSWORD -> item.passwordEntry?.updatedAt?.time ?: Long.MIN_VALUE
		VaultV2ItemType.AUTHENTICATOR -> item.totpItem?.updatedAt?.time ?: Long.MIN_VALUE
		VaultV2ItemType.NOTE,
		VaultV2ItemType.BANK_CARD,
		VaultV2ItemType.DOCUMENT,
		VaultV2ItemType.BILLING_ADDRESS -> item.secureItem?.updatedAt?.time ?: Long.MIN_VALUE
		VaultV2ItemType.PASSKEY -> item.passkeyEntry?.lastUsedAt ?: Long.MIN_VALUE
	}
}

private fun normalizeVaultV2PasskeyWebsite(rpId: String?): String {
	val trimmed = rpId?.trim().orEmpty()
	if (trimmed.isBlank()) return ""
	return if (trimmed.contains("://")) {
		trimmed
	} else {
		"https://$trimmed"
	}
}

internal fun firstLetterGroup(raw: String): String {
	val first = raw.trim().firstOrNull()?.uppercaseChar() ?: return "#"
	return if (first in 'A'..'Z') first.toString() else "#"
}

internal fun normalizeAsciiVaultV2SortKey(raw: String): String? {
	val trimmed = raw.trim()
	if (trimmed.isEmpty()) return "#"
	if (trimmed.any { char -> char.code > 0x7F }) return null
	return buildString(trimmed.length) {
		trimmed.forEach { char ->
			when {
				char.isLetterOrDigit() -> append(char)
				char.isWhitespace() && isNotEmpty() && last() != ' ' -> append(' ')
			}
		}
	}.trim().ifEmpty { trimmed }
}

private fun normalizedVaultV2SortKey(raw: String): String {
	normalizeAsciiVaultV2SortKey(raw)?.let { return it }
	val trimmed = raw.trim()
	val latin = vaultV2Transliterator.transliterate(trimmed)
	val normalized = buildString(latin.length) {
		latin.forEach { char ->
			when {
				char.isLetterOrDigit() -> append(char)
				char.isWhitespace() && isNotEmpty() && last() != ' ' -> append(' ')
			}
		}
	}.trim()
	return normalized.ifEmpty { trimmed }
}

private fun vaultV2LazyIndexForItemIndex(
	sectionLayouts: List<VaultV2SectionLayout>,
	targetItemIndex: Int,
): Int {
	for (section in sectionLayouts) {
		val sectionEnd = section.itemStartIndex + section.items.size
		if (targetItemIndex in section.itemStartIndex until sectionEnd) {
			return section.firstItemLazyIndex + (targetItemIndex - section.itemStartIndex)
		}
	}
	return sectionLayouts.lastOrNull()?.let { lastSection ->
		lastSection.firstItemLazyIndex + lastSection.items.lastIndex.coerceAtLeast(0)
	} ?: 0
}
