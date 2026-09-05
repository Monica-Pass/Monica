package takagi.ru.monica.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.security.maskDocumentNumberForPreview
import takagi.ru.monica.data.model.DocumentType
import takagi.ru.monica.data.model.displayFullName
import takagi.ru.monica.bitwarden.sync.SyncStatus
import takagi.ru.monica.ui.cardwallet.DocumentTypeIcon
import takagi.ru.monica.ui.cardwallet.documentCardFacePreviewData

/**
 * 证件卡片组件
 * 显示证件类型、号码（脱敏）等信息
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DocumentCard(
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
    documentData: DocumentData? = null
) {
    val resolvedDocumentData = documentData ?: remember(item.itemData) {
        CardWalletDataCodec.parseDocumentData(item.itemData) ?: emptyDocumentData()
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

    resolvedDocumentData.cardFace?.let { face ->
        CustomCardFaceCard(
            item = item,
            previewData = documentCardFacePreviewData(item.title, resolvedDocumentData),
            imageAttachmentName = face.imageAttachmentName,
            displayMode = face.displayMode,
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
                    Text(
                        text = resolvedDocumentData.displayFullName().ifBlank { getDocumentTypeName(resolvedDocumentData.documentType) },
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
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
                    
                    DocumentTypeIcon(
                        documentType = resolvedDocumentData.documentType,
                        modifier = Modifier.size(24.dp),
                        tint = contentColor.copy(alpha = 0.6f)
                    )
                    
                    if (!isSelectionMode && item.isFavorite) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = stringResource(R.string.favorite),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
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
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 证件号码（脱敏）
            Text(
                text = stringResource(R.string.document_number_label),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = maskDocumentNumberForPreview(
                    resolvedDocumentData.documentNumber,
                    resolvedDocumentData.documentType
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 持有人和有效期
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (resolvedDocumentData.fullName.isNotBlank()) {
                    Column {
                        Text(
                            text = stringResource(R.string.holder_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = resolvedDocumentData.fullName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                    }
                }
                
                if (resolvedDocumentData.expiryDate.isNotBlank()) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.valid_until),
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = resolvedDocumentData.expiryDate,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                    }
                }
            }
        }
    }
}

private fun emptyDocumentData() = DocumentData(
    documentNumber = "",
    documentType = DocumentType.ID_CARD,
    fullName = "",
    issuedDate = "",
    expiryDate = ""
)

/**
 * 获取证件类型名称
 */
@Composable
private fun getDocumentTypeName(type: DocumentType): String {
    return when (type) {
        DocumentType.ID_CARD -> stringResource(R.string.document_type_id_card)
        DocumentType.PASSPORT -> stringResource(R.string.document_type_passport)
        DocumentType.DRIVER_LICENSE -> stringResource(R.string.document_type_driver_license)
        DocumentType.SOCIAL_SECURITY -> stringResource(R.string.document_type_social_security)
        DocumentType.OTHER -> stringResource(R.string.document_type_other)
    }
}
