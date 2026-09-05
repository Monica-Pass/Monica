package takagi.ru.monica.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.BillingAddressData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.formatForDisplay
import takagi.ru.monica.data.model.toBillingAddress
import takagi.ru.monica.ui.cardwallet.billingAddressCardFacePreviewData

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BillingAddressCard(
    item: SecureItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    onToggleFavorite: ((Long, Boolean) -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    addressData: BillingAddressData? = null
) {
    val resolvedData = addressData ?: remember(item.itemData) {
        CardWalletDataCodec.parseBillingAddressData(item.itemData) ?: BillingAddressData()
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val interactionModifier = if (isSelectionMode) {
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

    resolvedData.cardFace?.let { face ->
        CustomCardFaceCard(
            item = item,
            previewData = billingAddressCardFacePreviewData(item.title, resolvedData),
            imageAttachmentName = face.imageAttachmentName,
            displayMode = face.displayMode,
            modifier = interactionModifier,
            isSelectionMode = isSelectionMode,
            isSelected = isSelected,
            onClick = onClick,
            onDelete = onDelete,
            onToggleFavorite = onToggleFavorite
        )
        return
    }

    MonicaItemCard(
        modifier = interactionModifier,
        isSelected = isSelected
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOf(resolvedData.fullName, resolvedData.company)
                            .filter { it.isNotBlank() }
                            .joinToString(" / ")
                            .ifBlank { stringResource(R.string.billing_address) },
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
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
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_options),
                                    tint = contentColor
                                )
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                if (onToggleFavorite != null) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (item.isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites
                                                )
                                            )
                                        },
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

            val addressDisplay = resolvedData.toBillingAddress().formatForDisplay()
            if (addressDisplay.isNotBlank()) {
                Text(
                    text = addressDisplay,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val contactDisplay = listOf(resolvedData.email, resolvedData.phone)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (contactDisplay.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = contactDisplay,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
