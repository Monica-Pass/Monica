package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import takagi.ru.monica.ui.components.MonicaSlider as Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.util.ImageManager

@Composable
fun ImageImportConfirmDialog(
    imageManager: ImageManager,
    bitmap: Bitmap,
    originalSizeBytes: Long?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val context = LocalContext.current
    val formattedOriginalSize = remember(originalSizeBytes, context) {
        originalSizeBytes?.let { Formatter.formatShortFileSize(context, it) }
    }
    var quality by rememberSaveable(bitmap.generationId) { mutableIntStateOf(80) }
    var estimatedSizeBytes by remember(bitmap.generationId) { mutableStateOf<Long?>(null) }
    val previewAspectRatio = remember(bitmap.width, bitmap.height) {
        (bitmap.width.toFloat() / bitmap.height.toFloat()).coerceIn(0.75f, 1.8f)
    }
    val formattedEstimatedSize = remember(estimatedSizeBytes, context) {
        estimatedSizeBytes?.let { Formatter.formatShortFileSize(context, it) }
    }

    LaunchedEffect(imageManager, bitmap.generationId, quality) {
        estimatedSizeBytes = imageManager.estimateSavedImageSize(
            bitmap = bitmap,
            compressionFormat = Bitmap.CompressFormat.JPEG,
            compressionQuality = quality
        )
    }

    AlertDialog(
        onDismissRequest = {
            if (!isSaving) {
                onDismiss()
            }
        },
        title = {
            Text(stringResource(R.string.photo_import_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 2.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 260.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.view_image),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = if (previewAspectRatio > 1.3f) {
                                ContentScale.FillWidth
                            } else {
                                ContentScale.Fit
                            }
                        )
                    }
                }

                formattedOriginalSize?.let { sizeText ->
                    Text(
                        text = stringResource(R.string.photo_import_dialog_original_size, sizeText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                formattedEstimatedSize?.let { sizeText ->
                    Text(
                        text = stringResource(R.string.photo_import_dialog_estimated_size, sizeText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = stringResource(R.string.photo_import_dialog_quality, quality),
                    style = MaterialTheme.typography.titleSmall
                )

                Slider(
                    value = quality.toFloat(),
                    onValueChange = { quality = it.toInt().coerceIn(40, 100) },
                    enabled = !isSaving,
                    valueRange = 40f..100f,
                    steps = 11
                )

                Text(
                    text = stringResource(R.string.photo_import_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving,
                onClick = { onConfirm(quality) }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.photo_import_dialog_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
