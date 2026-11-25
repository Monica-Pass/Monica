package takagi.ru.monica.steam.ui

import takagi.ru.monica.ui.haptic.switchClick

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import takagi.ru.monica.R
import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.market.SteamInventoryGame
import takagi.ru.monica.steam.market.SteamInventoryItemStack
import takagi.ru.monica.steam.market.SteamMarketFees
import takagi.ru.monica.steam.market.SteamMarketHistoryPoint
import takagi.ru.monica.steam.market.SteamMarketListing
import takagi.ru.monica.steam.market.SteamWalletInfo
import takagi.ru.monica.ui.common.pull.PullToSearchStateHandle
import takagi.ru.monica.ui.common.pull.PullSearchHint
import takagi.ru.monica.ui.common.selection.SelectionActionBar
import takagi.ru.monica.ui.components.MonicaModalBottomSheet

@Composable
internal fun SteamInventoryContent(
    account: SteamAccount?,
    accounts: List<SteamAccount>,
    state: SteamInventoryMarketUiState,
    visibleStacks: List<SteamInventoryItemStack>,
    hasSearchQuery: Boolean,
    pullToSearch: PullToSearchStateHandle,
    selectedStackKeys: Set<String>,
    onSelectAccount: (Long) -> Unit,
    onSelectGame: (SteamInventoryGame) -> Unit,
    onLoadMore: () -> Unit,
    onToggleSelection: (SteamInventoryItemStack) -> Unit,
    onClearSelection: () -> Unit,
    onOpenBatchSell: () -> Unit,
    onOpenSell: (SteamInventoryItemStack) -> Unit
) {
    var showAccountPicker by remember { mutableStateOf(false) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    if (showAccountPicker) {
        SteamConfirmationAccountPickerSheet(
            accounts = accounts,
            selectedAccountId = account?.id,
            onSelectAccount = {
                showAccountPicker = false
                onSelectAccount(it.id)
            },
            onDismissRequest = { showAccountPicker = false }
        )
    }

    LaunchedEffect(gridState, state.inventoryHasMore, state.inventoryLoadingMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (
                    state.inventoryHasMore &&
                    !state.inventoryLoadingMore &&
                    lastVisible >= visibleStacks.lastIndex - 6
                ) {
                    onLoadMore()
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullSearchHint(currentOffset = pullToSearch.currentOffset)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, pullToSearch.currentOffset.toInt()) }
        ) {
        SteamConfirmationAccountCard(
            account = account,
            onClick = { showAccountPicker = true },
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
        )

        val games = state.overview?.games.orEmpty()
        if (games.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(games, key = { "${it.appId}_${it.contextId}" }) { game ->
                    FilterChip(
                        selected = game.appId == state.selectedGame?.appId &&
                            game.contextId == state.selectedGame.contextId,
                        onClick = { onSelectGame(game) },
                        label = {
                            Text(
                                text = game.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Badge { Text(game.itemCount.toString()) }
                        }
                    )
                }
            }
        }

        when {
            account == null || !account.hasSteamCommunitySession -> {
                SteamMarketEmptyGestureState(
                    text = stringResource(R.string.steam_market_session_required),
                    pullToSearch = pullToSearch
                )
            }
            state.inventoryLoading && state.inventoryStacks.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.inventoryError != null && state.inventoryStacks.isEmpty() -> {
                SteamMarketEmptyGestureState(
                    text = state.inventoryError,
                    pullToSearch = pullToSearch
                )
            }
            visibleStacks.isEmpty() -> {
                SteamMarketEmptyGestureState(
                    text = stringResource(
                        if (hasSearchQuery) R.string.no_results else R.string.steam_inventory_empty
                    ),
                    pullToSearch = pullToSearch
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(pullToSearch.gestureModifier),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 4.dp,
                        end = 16.dp,
                        bottom = if (selectedStackKeys.isEmpty()) 96.dp else 156.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visibleStacks, key = { it.item.stackKey }) { stack ->
                        SteamInventoryItemCard(
                            stack = stack,
                            selected = stack.item.stackKey in selectedStackKeys,
                            selectionMode = selectedStackKeys.isNotEmpty(),
                            onClick = {
                                if (selectedStackKeys.isEmpty()) onOpenSell(stack)
                                else onToggleSelection(stack)
                            },
                            onLongClick = { onToggleSelection(stack) }
                        )
                    }
                    if (state.inventoryLoadingMore) {
                        item(key = "inventory_loading_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(72.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }
        }

        if (selectedStackKeys.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onClearSelection) {
                        Text(stringResource(R.string.cancel))
                    }
                    Text(
                        text = stringResource(
                            R.string.steam_inventory_selected_count,
                            selectedStackKeys.size
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Button(onClick = onOpenBatchSell) {
                        Text(stringResource(R.string.steam_inventory_batch_sell))
                    }
                }
            }
        }
    }
}

@Composable
internal fun SteamMarketListingsContent(
    account: SteamAccount?,
    accounts: List<SteamAccount>,
    state: SteamInventoryMarketUiState,
    visibleListings: List<SteamMarketListing>,
    hasSearchQuery: Boolean,
    pullToSearch: PullToSearchStateHandle,
    selectedListingIds: Set<String>,
    onSelectAccount: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onToggleSelection: (SteamMarketListing) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onRequestCancelListings: (List<SteamMarketListing>) -> Unit
) {
    var showAccountPicker by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    if (showAccountPicker) {
        SteamConfirmationAccountPickerSheet(
            accounts = accounts,
            selectedAccountId = account?.id,
            onSelectAccount = {
                showAccountPicker = false
                onSelectAccount(it.id)
            },
            onDismissRequest = { showAccountPicker = false }
        )
    }

    LaunchedEffect(listState, state.listingsHasMore, state.listingsLoadingMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (
                    state.listingsHasMore &&
                    !state.listingsLoadingMore &&
                    lastVisible >= visibleListings.lastIndex - 4
                ) {
                    onLoadMore()
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullSearchHint(currentOffset = pullToSearch.currentOffset)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, pullToSearch.currentOffset.toInt()) }
        ) {
        SteamConfirmationAccountCard(
            account = account,
            onClick = { showAccountPicker = true },
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
        )

        when {
            account == null || !account.hasSteamCommunitySession -> {
                SteamMarketEmptyGestureState(
                    text = stringResource(R.string.steam_market_session_required),
                    pullToSearch = pullToSearch
                )
            }
            state.listingsLoading && state.listings.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.listingsError != null && state.listings.isEmpty() -> {
                SteamMarketEmptyGestureState(
                    text = state.listingsError,
                    pullToSearch = pullToSearch
                )
            }
            visibleListings.isEmpty() -> {
                SteamMarketEmptyGestureState(
                    text = stringResource(
                        if (hasSearchQuery) R.string.no_results else R.string.steam_market_no_listings
                    ),
                    pullToSearch = pullToSearch
                )
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(pullToSearch.gestureModifier),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 12.dp,
                        end = 16.dp,
                        bottom = if (selectedListingIds.isEmpty()) 96.dp else 156.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleListings, key = { it.listingId }) { listing ->
                        SteamMarketListingCard(
                            listing = listing,
                            wallet = state.overview?.wallet ?: SteamWalletInfo.Fallback,
                            actionLoading = state.actionLoading,
                            selected = listing.listingId in selectedListingIds,
                            selectionMode = selectedListingIds.isNotEmpty(),
                            onToggleSelection = { onToggleSelection(listing) },
                            onLongClick = { onToggleSelection(listing) },
                            onCancel = { onRequestCancelListings(listOf(listing)) }
                        )
                    }
                    if (state.listingsLoadingMore) {
                        item(key = "market_listings_loading_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }
        }

        if (selectedListingIds.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 24.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionActionBar(
                    selectedCount = selectedListingIds.size,
                    onExit = onClearSelection,
                    onSelectAll = onSelectAll,
                    onDelete = null
                )

                Spacer(modifier = Modifier.weight(1f))

                FloatingActionButton(
                    modifier = Modifier.semantics {
                        if (state.actionLoading) disabled()
                    },
                    onClick = {
                        if (!state.actionLoading) {
                            onRequestCancelListings(
                                state.listings.filter { it.listingId in selectedListingIds }
                            )
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    if (state.actionLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.RemoveCircleOutline,
                            contentDescription = stringResource(
                                R.string.steam_market_batch_cancel
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SteamSellItemSheet(
    stack: SteamInventoryItemStack,
    wallet: SteamWalletInfo,
    marketState: SteamInventoryMarketUiState,
    onDismissRequest: () -> Unit,
    onLoadQuote: () -> Unit,
    onSell: (priceReceive: Int, quantity: Int, autoConfirm: Boolean) -> Unit,
    onConsumeActionResult: () -> Unit
) {
    val fees = remember(wallet) { SteamMarketFees(wallet) }
    var receiveText by remember(stack.item.stackKey) { mutableStateOf("") }
    var buyerText by remember(stack.item.stackKey) { mutableStateOf("") }
    var quantity by remember(stack.item.stackKey) { mutableStateOf(1) }
    var autoConfirm by remember(stack.item.stackKey) { mutableStateOf(false) }
    var syncingPriceFields by remember { mutableStateOf(false) }
    var localError by remember(stack.item.stackKey) { mutableStateOf<String?>(null) }
    val listFailedText = stringResource(R.string.steam_market_list_failed)

    LaunchedEffect(stack.item.stackKey) {
        onLoadQuote()
    }

    LaunchedEffect(marketState.lastActionResult) {
        val result = marketState.lastActionResult ?: return@LaunchedEffect
        if (result.action != SteamMarketActionType.SELL) return@LaunchedEffect
        if (result.success) {
            onDismissRequest()
        } else {
            localError = result.message ?: listFailedText
            onConsumeActionResult()
        }
    }

    fun toMinorUnits(value: String): Int {
        return ((value.trim().replace(',', '.').toDoubleOrNull() ?: 0.0) * 100.0)
            .roundToInt()
    }

    MonicaModalBottomSheet(
        onDismissRequest = {
            if (!marketState.actionLoading) onDismissRequest()
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SteamMarketRemoteImage(
                    imageUrl = stack.item.iconUrl,
                    contentDescription = stack.item.name,
                    modifier = Modifier.size(56.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stack.item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stack.item.type,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    enabled = !marketState.actionLoading,
                    onClick = onDismissRequest
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                }
            }

            if (marketState.quoteLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                val price = marketState.quotePrice
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SteamMarketInfoBlock(
                        label = stringResource(R.string.steam_market_lowest),
                        value = price?.lowestPrice ?: stringResource(R.string.steam_market_price_unavailable),
                        modifier = Modifier.weight(1f)
                    )
                    SteamMarketInfoBlock(
                        label = stringResource(R.string.steam_market_median),
                        value = price?.medianPrice ?: stringResource(R.string.steam_market_price_unavailable),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (marketState.quoteHistory.size >= 2) {
                    SteamMarketPriceTrend(
                        points = marketState.quoteHistory,
                        wallet = wallet,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(108.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = receiveText,
                    onValueChange = { value ->
                        receiveText = value
                        if (!syncingPriceFields) {
                            syncingPriceFields = true
                            val receive = toMinorUnits(value)
                            buyerText = if (receive > 0) {
                                minorUnitsText(
                                    fees.buyerPays(receive, stack.item.publisherFeePercent)
                                )
                            } else {
                                ""
                            }
                            syncingPriceFields = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.steam_market_you_receive)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(18.dp)
                )
                OutlinedTextField(
                    value = buyerText,
                    onValueChange = { value ->
                        buyerText = value
                        if (!syncingPriceFields) {
                            syncingPriceFields = true
                            val buyerPays = toMinorUnits(value)
                            receiveText = if (buyerPays > 0) {
                                minorUnitsText(
                                    fees.receiveFromTotal(
                                        buyerPays,
                                        stack.item.publisherFeePercent
                                    )
                                )
                            } else {
                                ""
                            }
                            syncingPriceFields = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.steam_market_buyer_pays)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(18.dp)
                )
            }

            Text(
                text = stringResource(R.string.steam_market_fee_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (stack.count > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.steam_market_quantity))
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        enabled = quantity > 1 && !marketState.actionLoading,
                        onClick = { quantity-- }
                    ) {
                        Icon(Icons.Default.RemoveCircleOutline, contentDescription = null)
                    }
                    Text(
                        text = "$quantity / ${stack.count}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        enabled = quantity < stack.count && !marketState.actionLoading,
                        onClick = { quantity++ }
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = quantity < stack.count && !marketState.actionLoading,
                        onClick = { quantity = stack.count }
                    ) {
                        Text(stringResource(R.string.steam_market_max))
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !marketState.actionLoading,
                        onClick = switchClick(autoConfirm, !marketState.actionLoading) { autoConfirm = it }),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.steam_market_auto_confirm),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.steam_market_auto_confirm_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoConfirm,
                        enabled = !marketState.actionLoading,
                        onCheckedChange = { autoConfirm = it }
                    )
                }
            }

            localError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !marketState.actionLoading && toMinorUnits(receiveText) > 0,
                onClick = {
                    localError = null
                    onSell(toMinorUnits(receiveText), quantity, autoConfirm)
                }
            ) {
                if (marketState.actionLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.steam_market_list_for_sale))
                }
            }
        }
    }
}

@Composable
private fun SteamInventoryItemCard(
    stack: SteamInventoryItemStack,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                enabled = stack.item.marketable,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (stack.item.marketable) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.58f)
            }
        )
    ) {
        Box {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SteamMarketRemoteImage(
                    imageUrl = stack.item.iconUrl,
                    contentDescription = stack.item.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                )
                Text(
                    text = stack.item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (stack.item.marketable) {
                        stack.item.type
                    } else {
                        stringResource(R.string.steam_inventory_not_marketable)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (stack.count > 1) {
                Badge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text("×${stack.count}")
                }
            }
            if (selectionMode && stack.item.marketable) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(28.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    border = if (selected) null else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    }
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.padding(5.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamMarketListingCard(
    listing: SteamMarketListing,
    wallet: SteamWalletInfo,
    actionLoading: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onLongClick: () -> Unit,
    onCancel: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                enabled = !actionLoading,
                onClick = {
                    if (selectionMode) onToggleSelection()
                },
                onLongClick = onLongClick
            )
            .semantics { this.selected = selected },
        shape = shape,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selectionMode) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    border = if (selected) null else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    }
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.padding(5.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            SteamMarketRemoteImage(
                imageUrl = listing.iconUrl,
                contentDescription = listing.name,
                modifier = Modifier.size(64.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listing.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.steam_market_listing_price,
                        walletCurrencyPrefix(wallet.currency) + minorUnitsText(listing.buyerPrice),
                        walletCurrencyPrefix(wallet.currency) + minorUnitsText(listing.sellerReceives)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!selectionMode) {
                TextButton(enabled = !actionLoading, onClick = onCancel) {
                    Text(
                        text = stringResource(R.string.steam_market_cancel_listing),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun SteamMarketInfoBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SteamMarketPriceTrend(
    points: List<SteamMarketHistoryPoint>,
    wallet: SteamWalletInfo,
    modifier: Modifier = Modifier
) {
    val values = points.map { it.price }
    val low = values.minOrNull() ?: return
    val high = values.maxOrNull() ?: return
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val selectedLineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pointCenterColor = MaterialTheme.colorScheme.surfaceContainerLow
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }

    fun indexForX(x: Float, width: Float): Int {
        if (width <= 0f || points.size < 2) return 0
        return ((x.coerceIn(0f, width) / width) * points.lastIndex)
            .roundToInt()
            .coerceIn(0, points.lastIndex)
    }

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            val selected = selectedIndex?.let(points::getOrNull)
            if (selected != null) {
                Text(
                    text = selected.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = stringResource(R.string.steam_market_high_value, high),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = selected?.let {
                    walletCurrencyPrefix(wallet.currency) +
                        String.format(Locale.getDefault(), "%.2f", it.price)
                } ?: stringResource(R.string.steam_market_low_value, low),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                fontWeight = if (selected == null) FontWeight.Normal else FontWeight.SemiBold
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        selectedIndex = indexForX(offset.x, size.width.toFloat())
                    }
                }
                .pointerInput(points) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            selectedIndex = indexForX(offset.x, size.width.toFloat())
                        },
                        onHorizontalDrag = { change, _ ->
                            selectedIndex = indexForX(change.position.x, size.width.toFloat())
                            change.consume()
                        }
                    )
                }
        ) {
            if (values.size < 2) return@Canvas
            val range = (high - low).takeIf { it > 0.000001 } ?: 1.0
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = size.width * index / values.lastIndex
                val y = size.height - ((value - low) / range * size.height).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLine(
                color = gridColor,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx()
            )
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            selectedIndex?.let { index ->
                val value = values[index]
                val x = size.width * index / values.lastIndex
                val y = size.height - ((value - low) / range * size.height).toFloat()
                drawLine(
                    color = selectedLineColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx()
                )
                drawCircle(color = lineColor, radius = 5.dp.toPx(), center = Offset(x, y))
                drawCircle(
                    color = pointCenterColor,
                    radius = 2.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }
    }
}

@Composable
internal fun SteamMarketRemoteImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var image by remember(imageUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(imageUrl) {
        image = loadSteamConfirmationImage(context, imageUrl)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        val snapshot = image
        if (snapshot != null) {
            Image(
                bitmap = snapshot,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Inventory2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SteamMarketEmptyGestureState(
    text: String,
    pullToSearch: PullToSearchStateHandle
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount -> pullToSearch.onVerticalDrag(dragAmount) },
                    onDragEnd = pullToSearch.onDragEnd,
                    onDragCancel = pullToSearch.onDragCancel
                )
            },
        contentAlignment = Alignment.Center
    ) {
        EmptyState(text)
    }
}

private val SteamAccount.hasSteamCommunitySession: Boolean
    get() = hasRealSteamId && (
        !accessToken.isNullOrBlank() ||
            !refreshToken.isNullOrBlank() ||
            !steamLoginSecure.isNullOrBlank()
        )

internal fun minorUnitsText(value: Int): String {
    return String.format(Locale.US, "%.2f", value / 100.0)
}

internal fun walletCurrencyPrefix(currency: Int): String {
    return when (currency) {
        1 -> "$"
        2 -> "£"
        3 -> "€"
        23 -> "¥"
        8 -> "₽"
        else -> ""
    }
}
