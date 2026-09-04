package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.sync.SyncStatus
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.notes.domain.DecodedNoteContent
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.notes.ui.model.NoteListItemUiModel
import takagi.ru.monica.ui.components.MarkdownPreviewText
import takagi.ru.monica.ui.components.MonicaItemCard
import takagi.ru.monica.ui.components.SyncStatusIcon
import takagi.ru.monica.util.ImageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExpressiveNoteCard(
    note: NoteListItemUiModel,
    isSelected: Boolean,
    isGridMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val previewImageMaxDimension = 512
    val context = LocalContext.current
    val imageManager = remember(context) { ImageManager(context) }
    val cardImageBitmaps = remember(note.id) { mutableStateMapOf<String, Bitmap>() }
    val markdownForCard = remember(note.rawContent, note.inlineImageIds, note.isMarkdown, note.previewText, isGridMode) {
        if (isGridMode && note.isMarkdown) {
            NoteContentCodec.appendInlineImageRefs(
                content = note.rawContent.trimEnd(),
                imageIds = note.inlineImageIds
            )
        } else {
            note.previewText
        }
    }

    LaunchedEffect(note.id, note.inlineImageIds, isGridMode) {
        if (!isGridMode) {
            cardImageBitmaps.clear()
            return@LaunchedEffect
        }
        note.inlineImageIds.take(3).forEach { imageId ->
            if (!cardImageBitmaps.containsKey(imageId)) {
                val bitmap = withContext(Dispatchers.IO) {
                    imageManager.loadImage(
                        fileName = imageId,
                        maxDimension = previewImageMaxDimension
                    )
                }
                bitmap?.let {
                    cardImageBitmaps[imageId] = bitmap
                }
            }
        }
    }

    val hasImageAttachment = note.hasImageAttachment

    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val secondaryContentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    MonicaItemCard(
        isSelected = isSelected,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(
            modifier = Modifier.padding(if (isGridMode) 12.dp else 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = note.title.ifEmpty { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (isGridMode) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor,
                    modifier = Modifier.weight(1f)
                )

                if (hasImageAttachment) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = stringResource(R.string.note_has_image),
                                modifier = Modifier.size(12.dp),
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.section_photos),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                        }
                    }
                }
            }

            if (note.previewText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                if (isGridMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    ) {
                        MarkdownPreviewText(
                            markdown = markdownForCard,
                            imageBitmaps = cardImageBitmaps,
                            onInlineImageClick = { onClick() },
                            onNonLinkClick = onClick,
                            renderImages = true,
                            maxElements = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Text(
                        text = note.previewText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = secondaryContentColor,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                    )
                }
            }

            if (note.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    note.tags.take(2).forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = {},
                            enabled = false,
                            label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryContentColor.copy(alpha = 0.8f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    note.syncStatus?.let { syncStatus ->
                        SyncStatusIcon(
                            status = syncStatus,
                            size = 14.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (hasImageAttachment) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = stringResource(R.string.note_has_image),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = stringResource(R.string.encrypted_storage),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
@Deprecated("Use ExpressiveNoteCard instead", ReplaceWith("ExpressiveNoteCard"))
fun NoteCard(
    note: NoteListItemUiModel,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ExpressiveNoteCard(
        note = note,
        isSelected = isSelected,
        isGridMode = true,
        onClick = onClick,
        onLongClick = onLongClick
    )
}

internal fun SecureItem.toNoteListItemUiModel(): NoteListItemUiModel {
    return toNoteListItemUiModel(NoteContentCodec.decodeFromItem(this))
}

internal fun SecureItem.toNoteListItemUiModel(decoded: DecodedNoteContent): NoteListItemUiModel {
    val resolvedSyncStatus = if (bitwardenVaultId != null) {
        when (syncStatus) {
            "PENDING" -> SyncStatus.PENDING
            "SYNCING" -> SyncStatus.SYNCING
            "SYNCED" -> SyncStatus.SYNCED
            "FAILED" -> SyncStatus.FAILED
            "CONFLICT" -> SyncStatus.CONFLICT
            else -> if (bitwardenLocalModified) SyncStatus.PENDING else SyncStatus.SYNCED
        }
    } else {
        null
    }
    return NoteListItemUiModel(
        id = id,
        title = title,
        rawContent = decoded.content,
        isMarkdown = decoded.isMarkdown,
        inlineImageIds = (
            NoteContentCodec.extractInlineImageIds(decoded.content) +
                NoteContentCodec.decodeImagePaths(imagePaths)
            ).distinct(),
        previewText = NoteContentCodec.toPlainPreview(decoded.content, decoded.isMarkdown),
        tags = decoded.tags,
        updatedAt = updatedAt,
        hasImageAttachment = NoteContentCodec.hasAnyImagePath(imagePaths),
        syncStatus = resolvedSyncStatus
    )
}
