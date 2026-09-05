package takagi.ru.monica.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import takagi.ru.monica.R
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardBrandDetector
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.CardType
import takagi.ru.monica.data.model.CardFaceDisplayMode
import takagi.ru.monica.data.model.formatForDisplay
import takagi.ru.monica.data.model.isEmpty
import takagi.ru.monica.bitwarden.sync.SyncStatus
import takagi.ru.monica.ui.cardwallet.bankCardFacePreviewData
import takagi.ru.monica.ui.cardwallet.CardBrandIcon
import takagi.ru.monica.ui.cardwallet.CardFaceImageProcessor
import takagi.ru.monica.ui.cardwallet.rememberCardFaceBitmap

/**
 * 银行卡卡片组件
 * 显示卡号（脱敏）、有效期等信息
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BankCardCard(
    item: SecureItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    onToggleFavorite: ((Long, Boolean) -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    cardData: BankCardData? = null
) {
    val resolvedCardData = cardData ?: remember(item.itemData) {
        CardWalletDataCodec.parseBankCardData(item.itemData) ?: emptyBankCardData()
    }
    val billingAddress = remember(resolvedCardData.billingAddress) {
        CardWalletDataCodec.parseBillingAddress(resolvedCardData.billingAddress)
    }
    val hasBillingAddress = remember(billingAddress) { !billingAddress.isEmpty() }
    val cardBrand = remember(
        resolvedCardData.cardNumber,
        resolvedCardData.brand,
        resolvedCardData.nickname,
        resolvedCardData.bankName,
        item.title
    ) {
        CardBrandDetector.detectStoredCard(
            number = resolvedCardData.cardNumber,
            storedBrand = listOf(
                resolvedCardData.brand,
                item.title,
                resolvedCardData.nickname,
                resolvedCardData.bankName
            ).joinToString(" ")
        )
    }
    
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    
    val cardInteractionModifier = if (isSelectionMode) {
        modifier
            .fillMaxWidth()
            .clickable { onClick() }
    } else {
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    }

    resolvedCardData.cardFace?.let { cardFace ->
        CustomCardFaceCard(
            item = item,
            previewData = bankCardFacePreviewData(item.title, resolvedCardData),
            imageAttachmentName = cardFace.imageAttachmentName,
            displayMode = cardFace.displayMode,
            showBrandIcon = cardFace.showBrandIcon,
            modifier = cardInteractionModifier,
            isSelectionMode = isSelectionMode,
            isSelected = isSelected,
            onClick = onClick,
            onDelete = onDelete,
            onToggleFavorite = onToggleFavorite,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown
        )
        return
    }

    MonicaItemCard(
        modifier = cardInteractionModifier,
        isSelected = isSelected
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
        ) {
            // 标题和菜单
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                    if (resolvedCardData.bankName.isNotBlank()) {
                        Text(
                            text = resolvedCardData.bankName,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f)
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Bitwarden 同步状态指示器
                    if (item.bitwardenVaultId != null) {
                        val syncStatus = when (item.syncStatus) {
                            "PENDING" -> SyncStatus.PENDING
                            "SYNCING" -> SyncStatus.SYNCING
                            "SYNCED" -> SyncStatus.SYNCED
                            "FAILED" -> SyncStatus.FAILED
                            "CONFLICT" -> SyncStatus.CONFLICT
                            else -> if (item.bitwardenLocalModified) SyncStatus.PENDING else SyncStatus.SYNCED
                        }
                        SyncStatusIcon(
                            status = syncStatus,
                            size = 16.dp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    
                    if (!isSelectionMode && item.isFavorite) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = stringResource(R.string.favorite),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onClick() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = contentColor.copy(alpha = 0.6f),
                                checkmarkColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    } else if (onDelete != null) {
                        // 菜单按钮
                        var expanded by remember { mutableStateOf(false) }
                        
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_options)
                                )
                            }
                            
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                // 收藏选项
                                if (onToggleFavorite != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (item.isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites)) },
                                        onClick = {
                                            expanded = false
                                            onToggleFavorite(item.id, !item.isFavorite)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (item.isFavorite) Icons.Default.FavoriteBorder else Icons.Default.Favorite,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    )
                                }
                                
                                // 上移选项
                                if (onMoveUp != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.move_up)) },
                                        onClick = {
                                            expanded = false
                                            onMoveUp()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.KeyboardArrowUp,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }
                                
                                // 下移选项
                                if (onMoveDown != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.move_down)) },
                                        onClick = {
                                            expanded = false
                                            onMoveDown()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }
                                
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = {
                                        expanded = false
                                        onDelete()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = maskCardNumber(resolvedCardData.cardNumber),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = contentColor
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 卡组织、持卡人和有效期
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.BottomStart
                ) {
                    CardBrandIcon(
                        brand = cardBrand,
                        tint = contentColor,
                        modifier = Modifier.size(width = 52.dp, height = 34.dp)
                    )
                }
                
                if (resolvedCardData.expiryMonth.isNotBlank() && resolvedCardData.expiryYear.isNotBlank()) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.expiry_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${resolvedCardData.expiryMonth}/${resolvedCardData.expiryYear}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                    }
                }
            }

            if (hasBillingAddress) {
                Spacer(modifier = Modifier.height(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.billing_address),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = billingAddress.formatForDisplay(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor
                    )
                }
            }
        }
    }
}

private fun emptyBankCardData() = BankCardData(
    cardNumber = "",
    cardholderName = "",
    expiryMonth = "",
    expiryYear = ""
)

/**
 * 卡号脱敏处理
 * 例如: 1234567890123456 -> **** **** **** 3456
 */
private fun maskCardNumber(cardNumber: String): String {
    if (cardNumber.length < 4) return "****"
    
    // 移除所有空格
    val cleanNumber = cardNumber.replace(" ", "")
    
    // 只显示最后4位
    val lastFour = cleanNumber.takeLast(4)
    
    // 格式化为: **** **** **** 3456
    return "**** **** **** $lastFour"
}
