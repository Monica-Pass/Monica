package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.model.BoardingPassBarcode
import takagi.ru.monica.data.model.BoardingPassParser

internal val BoardingPassFormats = listOf(
    BarcodeFormat.PDF_417, BarcodeFormat.AZTEC, BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX
)

/** Never substitute a different symbology or derive a boarding code from editable fields. */
internal fun renderBoardingPassBarcode(code: BoardingPassBarcode, width: Int, height: Int, rotateToFit: Boolean = false): Bitmap? {
    if (width <= 0 || height <= 0 || code.payload.length > 8192 ||
        code.payload.any { it.code > 127 } || BoardingPassParser.parse(code.payload) == null) return null
    return try {
        val format = BoardingPassFormats.singleOrNull { it.name == code.format } ?: return null
        val matrix = MultiFormatWriter().encode(code.payload, format, 0, 0,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "ISO-8859-1"))
        // A uniform integer scale keeps modules sharp and preserves the entire quiet zone.
        val quietZone = 4 // Data Matrix ignores the writer MARGIN hint.
        val rotate = rotateToFit && height > width && matrix.width > matrix.height * 2
        val availableWidth = if (rotate) height else width
        val availableHeight = if (rotate) width else height
        val scale = minOf(availableWidth / (matrix.width + quietZone * 2), availableHeight / (matrix.height + quietZone * 2))
        if (scale < 1) return null
        val w = (matrix.width + quietZone * 2) * scale
        val h = (matrix.height + quietZone * 2) * scale
        val pixels = IntArray(w * h) { index ->
            val x = index % w / scale - quietZone
            val y = index / w / scale - quietZone
            if (x in 0 until matrix.width && y in 0 until matrix.height && matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        if (rotate) Bitmap.createBitmap(bitmap, 0, 0, w, h,
            android.graphics.Matrix().apply { postRotate(90f) }, false).also { bitmap.recycle() }
        else bitmap
    } catch (_: Exception) { null }
}

@Composable
internal fun BoardingPassCodePage(code: BoardingPassBarcode, onDismiss: () -> Unit) {
    var expanded by remember(code) { mutableStateOf(false) }
    // These labels describe the stored code, even after the itinerary has been edited.
    val original = remember(code) { BoardingPassParser.parse(code.payload) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(code, size, expanded) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(code, size, expanded) { mutableStateOf(true) }
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp > configuration.screenHeightDp
    LaunchedEffect(code, size, expanded) {
        if (size.width > 0 && size.height > 0) {
            bitmap = withContext(Dispatchers.Default) { renderBoardingPassBarcode(code, size.width, size.height, rotateToFit = expanded) }
            loading = false
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val originalLabels: @Composable () -> Unit = {
            if (original != null) {
                Text("${original.origin} → ${original.destination}", style = MaterialTheme.typography.headlineMedium)
                Text("${original.serviceNumber} · ${original.passenger}", style = MaterialTheme.typography.bodyLarge)
            }
        }
        val originalHint: @Composable () -> Unit = {
            Text(when (code.format) {
                "PDF_417" -> "PDF417"
                "QR_CODE" -> "QR"
                "DATA_MATRIX" -> "Data Matrix"
                "AZTEC" -> "Aztec"
                else -> code.format
            }, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.boarding_code_original_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val preview: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier.background(Color.White, RoundedCornerShape(28.dp)).padding(if (expanded) 4.dp else 20.dp)
                .onSizeChanged { size = it }, contentAlignment = Alignment.Center) {
                val rendered = bitmap
                when {
                    rendered != null -> Image(rendered.asImageBitmap(), stringResource(R.string.boarding_code_show),
                        Modifier.fillMaxSize().testTag("boarding_pass_barcode"), contentScale = ContentScale.Inside,
                        filterQuality = FilterQuality.None)
                    loading -> CircularProgressIndicator(color = Color.Black)
                    else -> Text(stringResource(R.string.boarding_code_unavailable), color = Color.Black,
                        modifier = Modifier.verticalScroll(rememberScrollState()).testTag("boarding_pass_code_error"))
                }
            }
        }
        Surface(Modifier.fillMaxSize().testTag("boarding_pass_code_page"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    Text(stringResource(R.string.boarding_code_show), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                }
                if (wide) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!expanded) Column(Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            originalLabels()
                            originalHint()
                        }
                        preview(Modifier.weight(if (expanded) 1f else 0.6f).fillMaxHeight())
                    }
                } else {
                    if (!expanded && original != null) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            originalLabels()
                        }
                    }
                    if (expanded) preview(Modifier.weight(1f).fillMaxWidth())
                    else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // Keep the viewport stable while its spinner is replaced by the bitmap.
                        preview(Modifier.fillMaxWidth().height(if (code.format == "PDF_417") 220.dp else 360.dp))
                    }
                    if (!expanded) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 112.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            originalHint()
                        }
                    }
                }
                FilledTonalButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag("boarding_pass_code_expand")) {
                    Icon(if (expanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (expanded) R.string.boarding_code_collapse else R.string.boarding_code_expand))
                }
            }
        }
    }
}
