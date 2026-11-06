package takagi.ru.monica.ui

import takagi.ru.monica.ui.screens.NativeTokenListUi
import takagi.ru.monica.ui.screens.NativeTokenFilterChip
import takagi.ru.monica.ui.screens.rememberNativeTokenList


import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.runtime.LaunchedEffect
import takagi.ru.monica.ui.password.LocalKeePassListInteraction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.PasswordListQuickFilterItem
import takagi.ru.monica.data.PasswordListQuickFolderStyle
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.viewmodel.CategoryFilter

internal const val PASSWORD_LIST_QUICK_FILTERS_KEY = "quick_filters"
internal const val PASSWORD_LIST_QUICK_FOLDER_SHORTCUTS_KEY = "quick_folder_shortcuts"
private const val PASSWORD_LIST_EMPTY_STATE_WITH_HEADERS_KEY = "empty_state_with_quick_headers"
private const val PASSWORD_LIST_BOTTOM_SPACER_KEY = "password_list_bottom_spacer"

@Composable
internal fun PasswordListScrollableContent(
    nativeTokens: NativeTokenListUi? = null,
    listState: LazyListState,
    modifier: Modifier,
    isPasswordPageListModelReady: Boolean,
    hasVisibleQuickFilters: Boolean,
    hasVisibleCategoryQuickFilters: Boolean,
    appSettings: AppSettings,
    configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
    aggregateUiState: PasswordListAggregateUiState,
    quickFilterFavorite: Boolean,
    onQuickFilterFavoriteChange: (Boolean) -> Unit,
    quickFilter2fa: Boolean,
    onQuickFilter2faChange: (Boolean) -> Unit,
    quickFilterNotes: Boolean,
    onQuickFilterNotesChange: (Boolean) -> Unit,
    quickFilterPasskey: Boolean,
    onQuickFilterPasskeyChange: (Boolean) -> Unit,
    quickFilterBoundNote: Boolean,
    onQuickFilterBoundNoteChange: (Boolean) -> Unit,
    quickFilterAttachments: Boolean,
    onQuickFilterAttachmentsChange: (Boolean) -> Unit,
    quickFilterUncategorized: Boolean,
    onQuickFilterUncategorizedChange: (Boolean) -> Unit,
    quickFilterLocalOnly: Boolean,
    onQuickFilterLocalOnlyChange: (Boolean) -> Unit,
    quickFilterManualStackOnly: Boolean,
    onQuickFilterManualStackOnlyChange: (Boolean) -> Unit,
    quickFilterNeverStack: Boolean,
    onQuickFilterNeverStackChange: (Boolean) -> Unit,
    quickFilterUnstacked: Boolean,
    onQuickFilterUnstackedChange: (Boolean) -> Unit,
    quickFilterWifi: Boolean,
    onQuickFilterWifiChange: (Boolean) -> Unit,
    wifiQuickFilterVisible: Boolean,
    quickFilterSshKey: Boolean = false,
    onQuickFilterSshKeyChange: (Boolean) -> Unit = {},
    sshKeyQuickFilterVisible: Boolean = false,
    quickFilterBarcode: Boolean = false,
    onQuickFilterBarcodeChange: (Boolean) -> Unit = {},
    barcodeQuickFilterVisible: Boolean = false,
    onToggleAggregateType: ((PasswordPageContentType) -> Unit)?,
    categoryQuickFilterShortcuts: List<PasswordQuickFolderShortcut>,
    quickFolderShortcuts: List<PasswordQuickFolderShortcut>,
    quickFolderStyle: PasswordListQuickFolderStyle,
    currentFilter: CategoryFilter,
    onNavigateFilter: (CategoryFilter) -> Unit,
    hasVisibleListItems: Boolean,
    showEmptyState: Boolean,
    searchQuery: String,
    emptyStateMessage: PasswordListEmptyStateMessage,
    renderPasswordRows: LazyListScope.() -> Unit
) {
    val canInteract = LocalKeePassListInteraction.current
    val interactionEnabled = canInteract()
    LaunchedEffect(interactionEnabled) { if (!interactionEnabled) listState.stopScroll() }
    val categoryQuickFilterScrollState = rememberScrollState()

    LazyColumn(
        userScrollEnabled = interactionEnabled,
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        if (hasVisibleQuickFilters || hasVisibleCategoryQuickFilters) {
            item(key = PASSWORD_LIST_QUICK_FILTERS_KEY) {
                val filterSectionSpacing =
                    if (hasVisibleQuickFilters && hasVisibleCategoryQuickFilters) 0.dp else 0.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp, bottom = 4.dp)
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(filterSectionSpacing)
                    ) {
                        if (hasVisibleQuickFilters) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {

                                if (appSettings.passwordListQuickFiltersEnabled) {
                                    configuredQuickFilterItems.forEach { item ->
                                        if (shouldShowQuickFilterItem(item, aggregateUiState.visibleContentTypes)) {
                                            PasswordQuickFilterChipItem(
                                                item = item,
                                                categoryEditMode = false,
                                                quickFilterFavorite = quickFilterFavorite,
                                                onQuickFilterFavoriteChange = onQuickFilterFavoriteChange,
                                                quickFilter2fa = quickFilter2fa,
                                                onQuickFilter2faChange = onQuickFilter2faChange,
                                                quickFilterNotes = quickFilterNotes,
                                                onQuickFilterNotesChange = onQuickFilterNotesChange,
                                                quickFilterPasskey = quickFilterPasskey,
                                                onQuickFilterPasskeyChange = onQuickFilterPasskeyChange,
                                                quickFilterBoundNote = quickFilterBoundNote,
                                                onQuickFilterBoundNoteChange = onQuickFilterBoundNoteChange,
                                                quickFilterAttachments = quickFilterAttachments,
                                                onQuickFilterAttachmentsChange = onQuickFilterAttachmentsChange,
                                                quickFilterUncategorized = quickFilterUncategorized,
                                                onQuickFilterUncategorizedChange = onQuickFilterUncategorizedChange,
                                                quickFilterLocalOnly = quickFilterLocalOnly,
                                                onQuickFilterLocalOnlyChange = onQuickFilterLocalOnlyChange,
                                                quickFilterManualStackOnly = quickFilterManualStackOnly,
                                                onQuickFilterManualStackOnlyChange = onQuickFilterManualStackOnlyChange,
                                                quickFilterNeverStack = quickFilterNeverStack,
                                                onQuickFilterNeverStackChange = onQuickFilterNeverStackChange,
                                                quickFilterUnstacked = quickFilterUnstacked,
                                                onQuickFilterUnstackedChange = onQuickFilterUnstackedChange,
                                                aggregateSelectedTypes = aggregateUiState.selectedContentTypes,
                                                aggregateVisibleTypes = aggregateUiState.visibleContentTypes,
                                                onToggleAggregateType = onToggleAggregateType
                                            )
                                        }
                                    }
                                }
                                if (wifiQuickFilterVisible) {
                                    PasswordQuickFilterChip(
                                        selected = quickFilterWifi,
                                        onClick = { onQuickFilterWifiChange(!quickFilterWifi) },
                                        label = stringResource(R.string.entry_type_wifi),
                                        leadingIcon = Icons.Default.Wifi
                                    )
                                }
                                if (sshKeyQuickFilterVisible) {
                                    PasswordQuickFilterChip(
                                        selected = quickFilterSshKey,
                                        onClick = { onQuickFilterSshKeyChange(!quickFilterSshKey) },
                                        label = stringResource(R.string.password_list_quick_filter_ssh_key),
                                        leadingIcon = Icons.Default.Key
                                    )
                                }
                                if (barcodeQuickFilterVisible) {
                                    PasswordQuickFilterChip(
                                        selected = quickFilterBarcode,
                                        onClick = { onQuickFilterBarcodeChange(!quickFilterBarcode) },
                                        label = stringResource(R.string.password_list_quick_filter_barcode),
                                        leadingIcon = Icons.Default.QrCode2
                                    )
                                }
                            }
                        }

                        if (hasVisibleCategoryQuickFilters) {
                            PasswordQuickFolderChipRow(
                                params = PasswordQuickFolderChipRowParams(
                                    currentFilter = currentFilter,
                                    quickFolderShortcuts = categoryQuickFilterShortcuts,
                                    onSelectFilter = onNavigateFilter
                                ),
                                scrollState = categoryQuickFilterScrollState
                            )
                        }
                    }
                }
            }
        }

        if (quickFolderShortcuts.isNotEmpty()) {
            val quickFolderUseM3CardStyle =
                quickFolderStyle == PasswordListQuickFolderStyle.M3_CARD
            item(key = PASSWORD_LIST_QUICK_FOLDER_SHORTCUTS_KEY) {
                PasswordQuickFolderShortcutsSection(
                    shortcuts = quickFolderShortcuts,
                    currentFilter = currentFilter,
                    useM3CardStyle = quickFolderUseM3CardStyle,
                    onNavigate = onNavigateFilter
                )
            }
        }

        if (showEmptyState && nativeTokens?.onlyTokens != true && nativeTokens?.entries.orEmpty().isEmpty() &&
            nativeTokens?.loading != true && nativeTokens?.failed != true) {
            item(key = PASSWORD_LIST_EMPTY_STATE_WITH_HEADERS_KEY) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 84.dp, bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PasswordListEmptyState(
                        message = if (aggregateUiState.hasActiveContentTypeFilter) {
                            PasswordListEmptyStateMessage(titleRes = R.string.no_results)
                        } else {
                            emptyStateMessage
                        }
                    )
                }
            }
        } else {
            renderPasswordRows()
        }

        if (nativeTokens?.failed == true) item("native_token_error") {
            TextButton(onClick = nativeTokens.onRetry) { Text(stringResource(R.string.api_token_reload)) }
        }

        item(key = PASSWORD_LIST_BOTTOM_SPACER_KEY) {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
