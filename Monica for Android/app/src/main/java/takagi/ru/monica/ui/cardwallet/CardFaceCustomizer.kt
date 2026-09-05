package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardFaceAttachment
import takagi.ru.monica.data.model.CardFaceConfig
import takagi.ru.monica.data.model.CardFaceDisplayMode
import takagi.ru.monica.ui.components.MonicaItemCardShape

data class CardFaceEditResult(
    val config: CardFaceConfig?,
    val imageBytes: ByteArray?,
    val previewBitmap: Bitmap?
)

@Composable
fun CardFaceCustomizer(
    title: String,
    cardData: BankCardData,
    initialConfig: CardFaceConfig?,
    initialBitmap: Bitmap?,
    initialImageBytes: ByteArray? = null,
    imageSelectionAllowed: Boolean,
    imageSelectionWarning: String?,
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onApply: (CardFaceEditResult) -> Unit
) = CardFaceCustomizer(
    title = title,
    previewData = bankCardFacePreviewData(title, cardData),
    initialConfig = initialConfig,
    initialBitmap = initialBitmap,
    initialImageBytes = initialImageBytes,
    imageSelectionAllowed = imageSelectionAllowed,
    imageSelectionWarning = imageSelectionWarning,
    isSaving = isSaving,
    onDismiss = onDismiss,
    onApply = onApply
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardFaceCustomizer(
    title: String,
    previewData: CardFacePreviewData,
    initialConfig: CardFaceConfig?,
    initialBitmap: Bitmap?,
    initialImageBytes: ByteArray? = null,
    imageSelectionAllowed: Boolean = true,
    imageSelectionWarning: String? = null,
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onApply: (CardFaceEditResult) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(initialConfig) }
    var preview by remember { mutableStateOf(initialBitmap) }
    var preparedBytes by remember { mutableStateOf(initialImageBytes?.copyOf()) }
    var isProcessing by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<Int?>(null) }
    val currentBytes by rememberUpdatedState(preparedBytes)
    DisposableEffect(Unit) { onDispose { currentBytes?.fill(0) } }
    LaunchedEffect(initialBitmap) {
        if (preparedBytes == null && config != null && config?.imageAttachmentName == initialConfig?.imageAttachmentName) preview = initialBitmap
    }
    fun removeImage() {
        preparedBytes?.fill(0)
        preparedBytes = null
        preview = null
        config = null
        imageError = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isProcessing = true
        imageError = null
        scope.launch {
            try {
                val result = CardFaceImageProcessor.prepare(context, uri)
                val prepared = result.getOrNull()
                if (prepared == null) {
                    imageError = when ((result.exceptionOrNull() as? CardFaceImageProcessor.ImportException)?.reason) {
                        CardFaceImageProcessor.Failure.UNREADABLE -> R.string.card_face_image_unreadable
                        CardFaceImageProcessor.Failure.TOO_LARGE -> R.string.card_face_image_too_large
                        CardFaceImageProcessor.Failure.UNSUPPORTED -> R.string.card_face_image_unsupported
                        else -> R.string.card_face_image_invalid
                    }
                } else {
                    preparedBytes?.fill(0)
                    preparedBytes = prepared.bytes
                    preview = prepared.preview
                    val fileName = CardFaceAttachment.newFileName()
                    config = config?.copy(imageAttachmentName = fileName) ?: CardFaceConfig(fileName)
                }
            } finally {
                isProcessing = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isProcessing && !isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.card_face_customize)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss, enabled = !isProcessing && !isSaving) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    // The editor owns its buffer; the caller receives an independent copy.
                                    onApply(CardFaceEditResult(config, preparedBytes?.copyOf(), preview))
                                },
                                enabled = !isProcessing && !isSaving
                            ) { Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold) }
                        }
                    )
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (config != null) {
                            CardFaceArtwork(
                                previewData = previewData.copy(title = title),
                                bitmap = preview,
                                displayMode = config!!.displayMode,
                                showBrandIcon = config!!.showBrandIcon,
                                modifier = Modifier.fillMaxWidth().aspectRatio(CardFaceImageProcessor.CARD_ASPECT_RATIO)
                            )
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth().aspectRatio(CardFaceImageProcessor.CARD_ASPECT_RATIO),
                                shape = MonicaItemCardShape,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                Column(
                                    Modifier.fillMaxSize().padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Image, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(12.dp))
                                    Text(stringResource(R.string.card_face_empty_preview), style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        stringResource(R.string.card_face_crop_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        FilledTonalButton(
                            onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                            enabled = imageSelectionAllowed && !isProcessing && !isSaving,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isProcessing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Image, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(
                                if (isProcessing) R.string.card_face_processing
                                else if (config == null) R.string.card_face_choose_image
                                else R.string.card_face_replace_image
                            ))
                        }
                        Text(
                            stringResource(R.string.card_face_image_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        imageError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                        if (!imageSelectionAllowed && !imageSelectionWarning.isNullOrBlank()) {
                            Text(imageSelectionWarning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        config?.let { current ->
                            Text(stringResource(R.string.card_face_display_title), style = MaterialTheme.typography.titleSmall)
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                Column(Modifier.selectableGroup()) {
                                    DisplayModeOption(
                                        current.displayMode == CardFaceDisplayMode.ALL,
                                        stringResource(R.string.card_face_display_all),
                                        stringResource(R.string.card_face_display_all_description),
                                        !isProcessing && !isSaving
                                    ) { config = current.copy(displayMode = CardFaceDisplayMode.ALL) }
                                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                    DisplayModeOption(
                                        current.displayMode == CardFaceDisplayMode.CARD_NUMBER_ONLY,
                                        stringResource(R.string.card_face_display_identifier, previewData.identifierLabel),
                                        stringResource(R.string.card_face_display_identifier_description, previewData.identifierLabel),
                                        !isProcessing && !isSaving
                                    ) { config = current.copy(displayMode = CardFaceDisplayMode.CARD_NUMBER_ONLY) }
                                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                    DisplayModeOption(
                                        current.displayMode == CardFaceDisplayMode.HIDDEN,
                                        stringResource(R.string.card_face_display_hidden),
                                        stringResource(R.string.card_face_display_hidden_description),
                                        !isProcessing && !isSaving
                                    ) { config = current.copy(displayMode = CardFaceDisplayMode.HIDDEN) }
                                }
                            }
                            if (previewData.brand != null) {
                                val showBrand = current.showBrandIcon && current.displayMode != CardFaceDisplayMode.HIDDEN
                                val enabled = !isProcessing && !isSaving && current.displayMode != CardFaceDisplayMode.HIDDEN
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .toggleable(
                                                value = showBrand,
                                                enabled = enabled,
                                                role = Role.Switch,
                                                onValueChange = { config = current.copy(showBrandIcon = it) }
                                            ).padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(stringResource(R.string.card_face_show_brand), style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                stringResource(if (current.displayMode == CardFaceDisplayMode.HIDDEN)
                                                    R.string.card_face_brand_hidden_description else R.string.card_face_brand_description),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(checked = showBrand, onCheckedChange = null, enabled = enabled)
                                    }
                                }
                            }
                            TextButton(onClick = ::removeImage, enabled = !isProcessing && !isSaving, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.card_face_remove))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayModeOption(
    selected: Boolean,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
