package takagi.ru.monica.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.sync.SyncStatus
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.CardFaceDisplayMode
import takagi.ru.monica.ui.cardwallet.CardFaceArtwork
import takagi.ru.monica.ui.cardwallet.CardFaceImageProcessor
import takagi.ru.monica.ui.cardwallet.CardFacePreviewData
import takagi.ru.monica.ui.cardwallet.rememberCardFaceBitmap

/**
 * Shared, fixed-ratio card-face surface for wallet item types.
 *
 * Images are sampled and cached by [rememberCardFaceBitmap]; this composable never creates a
 * plaintext thumbnail file and keeps the legacy item cards untouched when no card face exists.
 */
@Composable
fun CustomCardFaceCard(
    item: SecureItem,
    previewData: CardFacePreviewData,
    imageAttachmentName: String,
    displayMode: CardFaceDisplayMode,
    showBrandIcon: Boolean = true,
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onToggleFavorite: ((Long, Boolean) -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    val bitmap = rememberCardFaceBitmap(
        item = item,
        imageAttachmentName = imageAttachmentName,
        maxDimension = 640
    )

    MonicaItemCard(
        modifier = modifier.aspectRatio(CardFaceImageProcessor.CARD_ASPECT_RATIO),
        isSelected = false,
        transparentContainer = true
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            CardFaceArtwork(
                previewData = previewData,
                bitmap = bitmap,
                displayMode = displayMode,
                showBrandIcon = showBrandIcon,
                reservedTopEndWidth = 64.dp,
                modifier = Modifier.fillMaxSize()
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.bitwardenVaultId != null) {
                    SyncStatusIcon(
                        status = item.cardFaceSyncStatus(),
                        size = 16.dp
                    )
                }
                if (!isSelectionMode && item.isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = stringResource(R.string.favorite),
                        tint = Color.White,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(20.dp)
                    )
                }
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = Color.White,
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                } else if (onDelete != null) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_options),
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            onToggleFavorite?.let { toggleFavorite ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (item.isFavorite) {
                                                    R.string.remove_from_favorites
                                                } else {
                                                    R.string.add_to_favorites
                                                }
                                            )
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        toggleFavorite(item.id, !item.isFavorite)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (item.isFavorite) {
                                                Icons.Default.FavoriteBorder
                                            } else {
                                                Icons.Default.Favorite
                                            },
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                            onMoveUp?.let { move ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.move_up)) },
                                    onClick = {
                                        expanded = false
                                        move()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                                    }
                                )
                            }
                            onMoveDown?.let { move ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.move_down)) },
                                    onClick = {
                                        expanded = false
                                        move()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
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
                                        imageVector = Icons.Default.Delete,
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
    }
}

private fun SecureItem.cardFaceSyncStatus(): SyncStatus = when (syncStatus) {
    "PENDING" -> SyncStatus.PENDING
    "SYNCING" -> SyncStatus.SYNCING
    "SYNCED" -> SyncStatus.SYNCED
    "FAILED" -> SyncStatus.FAILED
    "CONFLICT" -> SyncStatus.CONFLICT
    else -> if (bitwardenLocalModified) SyncStatus.PENDING else SyncStatus.SYNCED
}
