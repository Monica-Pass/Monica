package takagi.ru.monica.steam.ui

import takagi.ru.monica.ui.haptic.switchClick

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.steam.market.SteamBatchPriceMode
import takagi.ru.monica.steam.market.SteamBatchPricing
import takagi.ru.monica.steam.market.SteamBatchSellEntry
import takagi.ru.monica.steam.market.SteamInventoryItemStack
import takagi.ru.monica.steam.market.SteamWalletInfo
import takagi.ru.monica.ui.components.MonicaModalBottomSheet

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SteamBatchSellSheet(
    stacks: List<SteamInventoryItemStack>,
    wallet: SteamWalletInfo,
    marketState: SteamInventoryMarketUiState,
    onDismissRequest: () -> Unit,
    onLoadQuotes: () -> Unit,
    onSell: (entries: List<SteamBatchSellEntry>, autoConfirm: Boolean) -> Unit,
    onSellSucceeded: () -> Unit,
    onConsumeActionResult: () -> Unit
) {
    var priceMode by remember { mutableStateOf(SteamBatchPriceMode.LOWEST_LISTING) }
    var manualReceiveText by remember { mutableStateOf("") }
    var itemReceiveOverrides by remember(stacks) {
        mutableStateOf<Map<String, Int>>(emptyMap())
    }
    var editingStack by remember { mutableStateOf<SteamInventoryItemStack?>(null) }
    var editingReceiveText by remember { mutableStateOf("") }
    var editingPriceError by remember { mutableStateOf(false) }
    var autoConfirm by remember { mutableStateOf(false) }
    var localError by remember(stacks) { mutableStateOf<String?>(null) }
    val listFailedText = stringResource(R.string.steam_market_list_failed)

    LaunchedEffect(stacks.map { it.item.stackKey }) {
        onLoadQuotes()
    }

    LaunchedEffect(marketState.lastActionResult) {
        val result = marketState.lastActionResult ?: return@LaunchedEffect
        if (result.action != SteamMarketActionType.SELL) return@LaunchedEffect
        if (result.success) {
            onSellSucceeded()
        } else {
            localError = result.message ?: listFailedText
            onConsumeActionResult()
        }
    }

    val manualReceive = SteamBatchPricing.parseLocalizedPriceMinorUnits(manualReceiveText)
    val entries = remember(
        stacks,
        priceMode,
        manualReceive,
        wallet,
        marketState.batchQuotes,
        itemReceiveOverrides
    ) {
        SteamBatchPricing.resolveEntries(
            stacks = stacks,
            mode = priceMode,
            quotes = marketState.batchQuotes,
            wallet = wallet,
            manualReceive = manualReceive,
            itemReceiveOverrides = itemReceiveOverrides
        )
    }
    val entriesByStackKey = entries.associateBy { it.stack.item.stackKey }
    val missingCount = (stacks.size - entries.size).coerceAtLeast(0)
    val canSubmit = entries.size == stacks.size && entries.isNotEmpty()
    val modes = SteamBatchPriceMode.entries

    editingStack?.let { stack ->
        val stackKey = stack.item.stackKey
        AlertDialog(
            onDismissRequest = {
                editingStack = null
                editingPriceError = false
            },
            title = { Text(stringResource(R.string.steam_market_batch_item_price_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stack.item.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    OutlinedTextField(
                        value = editingReceiveText,
                        onValueChange = {
                            editingReceiveText = it
                            editingPriceError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(R.string.steam_market_batch_manual_receive))
                        },
                        supportingText = {
                            Text(
                                if (editingPriceError) {
                                    stringResource(R.string.steam_market_batch_item_price_invalid)
                                } else {
                                    stringResource(R.string.steam_market_batch_item_price_hint)
                                }
                            )
                        },
                        isError = editingPriceError,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(18.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val receive = SteamBatchPricing.parseLocalizedPriceMinorUnits(
                            editingReceiveText
                        )
                        if (receive == null || receive <= 0) {
                            editingPriceError = true
                        } else {
                            itemReceiveOverrides = itemReceiveOverrides + (stackKey to receive)
                            editingStack = null
                            editingPriceError = false
                            localError = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                Row {
                    if (stackKey in itemReceiveOverrides) {
                        TextButton(
                            onClick = {
                                itemReceiveOverrides = itemReceiveOverrides - stackKey
                                editingStack = null
                                editingPriceError = false
                                localError = null
                            }
                        ) {
                            Text(stringResource(R.string.steam_market_batch_item_price_reset))
                        }
                    }
                    TextButton(
                        onClick = {
                            editingStack = null
                            editingPriceError = false
                        }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
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
                .heightIn(max = 760.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.steam_market_batch_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.steam_inventory_selected_count, stacks.size),
                        style = MaterialTheme.typography.bodyMedium,
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

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(modes, key = { it.name }) { mode ->
                    FilterChip(
                        selected = priceMode == mode,
                        enabled = !marketState.actionLoading,
                        onClick = {
                            priceMode = mode
                            localError = null
                        },
                        label = { Text(stringResource(mode.labelResource())) }
                    )
                }
            }

            if (priceMode == SteamBatchPriceMode.MANUAL) {
                OutlinedTextField(
                    value = manualReceiveText,
                    onValueChange = {
                        manualReceiveText = it
                        localError = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    label = { Text(stringResource(R.string.steam_market_batch_manual_receive)) },
                    supportingText = { Text(stringResource(R.string.steam_market_batch_manual_note)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(18.dp)
                )
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (marketState.batchQuoteLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        text = stringResource(
                            R.string.steam_market_batch_quote_progress,
                            marketState.batchQuoteCompleted,
                            marketState.batchQuoteTotal
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!marketState.batchQuoteLoading && missingCount > 0) {
                        Text(
                            text = stringResource(R.string.steam_market_batch_quote_missing, missingCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    marketState.batchQuoteError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.steam_market_batch_items_title),
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(stacks, key = { it.item.stackKey }) { stack ->
                    val stackKey = stack.item.stackKey
                    val entry = entriesByStackKey[stackKey]
                    val shape = RoundedCornerShape(18.dp)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .clickable(enabled = !marketState.actionLoading) {
                                editingStack = stack
                                editingReceiveText = itemReceiveOverrides[stackKey]
                                    ?.let(::minorUnitsText)
                                    ?: entry?.priceReceive?.let(::minorUnitsText)
                                    .orEmpty()
                                editingPriceError = false
                            },
                        shape = shape,
                        color = if (stackKey in itemReceiveOverrides) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SteamMarketRemoteImage(
                                imageUrl = stack.item.iconUrl,
                                contentDescription = stack.item.name,
                                modifier = Modifier.size(52.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stack.item.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = entry?.let {
                                        stringResource(
                                            R.string.steam_market_batch_item_receive,
                                            walletCurrencyPrefix(wallet.currency) +
                                                minorUnitsText(it.priceReceive)
                                        )
                                    } ?: stringResource(
                                        R.string.steam_market_batch_item_price_missing
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (entry == null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(
                                    R.string.steam_market_batch_item_price_edit
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.steam_market_batch_each_one),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.steam_market_batch_fee_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
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
                    Spacer(modifier = Modifier.width(12.dp))
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
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(52.dp),
                enabled = !marketState.actionLoading &&
                    (priceMode == SteamBatchPriceMode.MANUAL || !marketState.batchQuoteLoading) &&
                    canSubmit,
                onClick = {
                    localError = null
                    onSell(entries, autoConfirm)
                }
            ) {
                if (marketState.actionLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.steam_market_verify_and_list))
                }
            }
        }
    }
}

private fun SteamBatchPriceMode.labelResource(): Int = when (this) {
    SteamBatchPriceMode.LOWEST_LISTING -> R.string.steam_market_price_mode_lowest
    SteamBatchPriceMode.MEDIAN -> R.string.steam_market_price_mode_median
    SteamBatchPriceMode.RECENT_HIGH -> R.string.steam_market_price_mode_recent_high
    SteamBatchPriceMode.RECENT_LOW -> R.string.steam_market_price_mode_recent_low
    SteamBatchPriceMode.MANUAL -> R.string.steam_market_price_mode_manual
}
