package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.CardFaceConfig
import takagi.ru.monica.data.model.CardFaceDisplayMode
import takagi.ru.monica.ui.components.MonicaItemCardShape

/** Owns only the unsaved image. Existing images are loaded through the shared attachment cache. */
@Stable
class CardFaceEditorState {
    var item by mutableStateOf<SecureItem?>(null)
        private set
    var config by mutableStateOf<CardFaceConfig?>(null)
        private set
    var originalConfig by mutableStateOf<CardFaceConfig?>(null)
        private set
    var imageBytes by mutableStateOf<ByteArray?>(null)
        private set
    var preview by mutableStateOf<Bitmap?>(null)
        private set

    val hasAttachmentChanges: Boolean
        get() = imageBytes != null || (config == null && originalConfig != null)

    fun load(item: SecureItem, config: CardFaceConfig?) {
        clearPendingBytes()
        this.item = item
        this.config = config
        originalConfig = config
        preview = null
    }

    fun apply(result: CardFaceEditResult) {
        clearPendingBytes()
        config = result.config
        imageBytes = result.imageBytes
        preview = result.previewBitmap
    }

    fun clearPendingBytes() {
        imageBytes?.fill(0)
        imageBytes = null
    }
}

@Composable
fun CardFaceDetailHeader(
    previewData: CardFacePreviewData,
    config: CardFaceConfig?,
    bitmap: Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().aspectRatio(CardFaceImageProcessor.CARD_ASPECT_RATIO),
            shape = MonicaItemCardShape
        ) {
            CardFaceArtwork(
                previewData = previewData,
                bitmap = bitmap,
                displayMode = config?.displayMode ?: CardFaceDisplayMode.ALL,
                showBrandIcon = config?.showBrandIcon ?: true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            stringResource(R.string.card_face_preview_hint),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun rememberCardFaceEditorState(itemId: Long?): CardFaceEditorState {
    val state = remember(itemId) { CardFaceEditorState() }
    DisposableEffect(state) { onDispose { state.clearPendingBytes() } }
    return state
}

/** A compact entry directly below storage selection; the full preview lives in the customizer. */
@Composable
fun CardFaceEditSection(
    state: CardFaceEditorState,
    previewData: CardFacePreviewData,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    imageSelectionAllowed: Boolean = true,
    imageSelectionWarning: String? = null
) {
    var showCustomizer by remember { mutableStateOf(false) }
    val bitmap = rememberCardFaceBitmap(
        item = state.item,
        imageAttachmentName = state.config?.imageAttachmentName,
        overrideBitmap = state.preview,
        maxDimension = 1000
    )
    CardFaceEditorEntry(
        config = state.config,
        bitmap = bitmap,
        previewData = previewData,
        enabled = enabled,
        onClick = { showCustomizer = true },
        modifier = modifier
    )
    if (showCustomizer) {
        CardFaceCustomizer(
            title = previewData.title,
            previewData = previewData,
            initialConfig = state.config,
            initialBitmap = bitmap,
            initialImageBytes = state.imageBytes,
            imageSelectionAllowed = imageSelectionAllowed,
            imageSelectionWarning = imageSelectionWarning,
            onDismiss = { showCustomizer = false },
            onApply = { result -> state.apply(result); showCustomizer = false }
        )
    }
}

@Composable
fun CardFaceEditorEntry(
    config: CardFaceConfig?,
    bitmap: Bitmap?,
    previewData: CardFacePreviewData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (config != null) {
                CardFaceArtwork(
                    previewData = previewData,
                    bitmap = bitmap,
                    displayMode = CardFaceDisplayMode.HIDDEN,
                    modifier = Modifier.width(72.dp).aspectRatio(CardFaceImageProcessor.CARD_ASPECT_RATIO)
                )
            } else {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.card_face_customize), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (config == null) R.string.card_face_customize_description else R.string.card_face_edit_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
