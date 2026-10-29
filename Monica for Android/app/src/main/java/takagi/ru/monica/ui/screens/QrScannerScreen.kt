package takagi.ru.monica.ui.screens

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.zxing.BarcodeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.ui.scanner.QrCameraScanSession
import takagi.ru.monica.ui.scanner.QrScanRestartReason
import takagi.ru.monica.ui.scanner.QrScannerDiagnostics
import takagi.ru.monica.ui.scanner.ZxingBarcodeDecoder
import java.util.concurrent.atomic.AtomicBoolean

private val DEFAULT_SCANNER_FORMATS = listOf(
    BarcodeFormat.QR_CODE,
    BarcodeFormat.CODE_128,
    BarcodeFormat.CODE_39,
    BarcodeFormat.CODE_93,
    BarcodeFormat.EAN_13,
    BarcodeFormat.EAN_8,
    BarcodeFormat.UPC_A,
    BarcodeFormat.UPC_E,
    BarcodeFormat.ITF,
    BarcodeFormat.CODABAR,
    BarcodeFormat.DATA_MATRIX,
    BarcodeFormat.AZTEC,
    BarcodeFormat.PDF_417
)

/**
 * QR码扫描屏幕
 * 用于扫描TOTP密钥的QR码
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun QrScannerScreen(
    onQrCodeScanned: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    allowedFormats: Collection<BarcodeFormat> = DEFAULT_SCANNER_FORMATS,
    resultValidator: (String) -> Boolean = { true },
    invalidResultMessage: String? = null,
    diagnosticLabel: String? = null,
    onDiagnostic: ((String) -> Unit)? = null,
    preserveWhitespace: Boolean = false,
    bottomContent: @Composable (launchGallery: () -> Unit) -> Unit = { launchGallery ->
        DefaultQrScannerBottomContent(launchGallery = launchGallery)
    }
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    
    // 添加权限状态监听，在页面显示时自动检查权限
    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }
    
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        when {
            cameraPermissionState.status.isGranted -> {
                QrCodeScanner(
                    onQrCodeScanned = onQrCodeScanned,
                    onNavigateBack = onNavigateBack,
                    title = title ?: stringResource(R.string.scan_qr_code_title),
                    subtitle = subtitle ?: stringResource(R.string.qr_align_hint),
                    allowedFormats = allowedFormats,
                    resultValidator = resultValidator,
                    invalidResultMessage = invalidResultMessage,
                    diagnosticLabel = diagnosticLabel,
                    onDiagnostic = onDiagnostic,
                    preserveWhitespace = preserveWhitespace,
                    bottomContent = bottomContent
                )
            }
            else -> {
                CameraPermissionRequest(
                    permissionState = cameraPermissionState,
                    onNavigateBack = onNavigateBack
                )
            }
        }
    }
}

/**
 * 请求相机权限界面
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CameraPermissionRequest(
    permissionState: PermissionState,
    onNavigateBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.qr_camera_permission_title),
            style = MaterialTheme.typography.headlineSmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = stringResource(R.string.camera_permission_required),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = { permissionState.launchPermissionRequest() }
        ) {
            Text(stringResource(R.string.grant_permission))
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TextButton(onClick = onNavigateBack) {
            Text(stringResource(R.string.go_back))
        }
    }
}

@Composable
private fun QrCodeScanner(
    onQrCodeScanned: (String) -> Unit,
    onNavigateBack: () -> Unit,
    title: String,
    subtitle: String,
    allowedFormats: Collection<BarcodeFormat>,
    resultValidator: (String) -> Boolean,
    invalidResultMessage: String?,
    diagnosticLabel: String?,
    onDiagnostic: ((String) -> Unit)?,
    preserveWhitespace: Boolean,
    bottomContent: @Composable (launchGallery: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val galleryDecoder = remember(allowedFormats, preserveWhitespace) {
        ZxingBarcodeDecoder(allowedFormats, preserveWhitespace)
    }
    val scope = rememberCoroutineScope()
    val scanConsumed = remember { AtomicBoolean(false) }
    val diagnostics = remember(diagnosticLabel, onDiagnostic) {
        if (diagnosticLabel != null && onDiagnostic != null) {
            QrScannerDiagnostics(diagnosticLabel, onDiagnostic)
        } else {
            null
        }
    }
    val currentValidator = rememberUpdatedState(resultValidator)
    val currentOnQrCodeScanned = rememberUpdatedState(onQrCodeScanned)
    val currentInvalidResultMessage = rememberUpdatedState(invalidResultMessage)
    var scanGeneration by remember { mutableIntStateOf(0) }
    var pendingRestartReason by remember { mutableStateOf<QrScanRestartReason?>(null) }
    // AndroidView keeps its factory result. Recovery must rebind the visible view,
    // rather than create a new PreviewView that never gets attached to the window.
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // A navigation destination already animates the complete screen.
    val usesScreenTransition = takagi.ru.monica.ui.LocalAnimatedVisibilityScope.current != null
    var showOverlay by remember { mutableStateOf(usesScreenTransition) }
    var invalidCandidateSeen by remember { mutableStateOf(false) }

    val acceptResult: (String?) -> Boolean = acceptResult@{ raw ->
        val value = raw?.let { if (preserveWhitespace) it else it.trim() }
            ?.takeIf { it.isNotBlank() } ?: return@acceptResult false
        if (!currentValidator.value(value) || !scanConsumed.compareAndSet(false, true)) {
            return@acceptResult false
        }
        try {
            currentOnQrCodeScanned.value(value)
            diagnostics?.logResultAccepted()
            true
        } catch (error: Exception) {
            scanConsumed.set(false)
            diagnostics?.logResultDeliveryFailed(error)
            false
        }
    }

    val cameraSession = remember(
        context,
        lifecycleOwner,
        previewView,
        allowedFormats,
        preserveWhitespace,
        scanGeneration,
        diagnostics
    ) {
        QrCameraScanSession(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            allowedFormats = allowedFormats,
            preserveWhitespace = preserveWhitespace,
            generation = scanGeneration,
            diagnostics = diagnostics,
            onCandidates = { candidates, _, _ ->
                val value = candidates.firstOrNull(currentValidator.value)
                if (value == null && candidates.isNotEmpty()) invalidCandidateSeen = true
                // The camera session dispatches validation and acceptance together
                // on the main thread, after releasing the frame and checking liveness.
                value != null && acceptResult(value)
            },
            onRestartRequested = { reason ->
                if (!scanConsumed.get() && pendingRestartReason == null) {
                    pendingRestartReason = reason
                }
            }
        )
    }
    val currentCameraSession = rememberUpdatedState(cameraSession)

    // 图片选择器 - 使用 GetContent 以兼容所有设备
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            diagnostics?.logGalleryStart()
            scope.launch {
                processImageWithZxing(
                    context = context,
                    uri = uri,
                    decoder = galleryDecoder,
                    diagnostics = diagnostics,
                    resultValidator = currentValidator.value,
                    onResult = { acceptResult(it) },
                    onInvalid = {
                        Toast.makeText(
                            context,
                            currentInvalidResultMessage.value ?: context.getString(R.string.qr_not_found),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onNotFound = {
                        Toast.makeText(context, context.getString(R.string.qr_not_found), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    DisposableEffect(cameraSession) {
        cameraSession.start()
        onDispose { cameraSession.close() }
    }

    LaunchedEffect(cameraSession, scanConsumed) {
        while (!scanConsumed.get()) {
            delay(QR_SCAN_HEALTH_TICK_MS)
            cameraSession.tick()
        }
    }

    LaunchedEffect(pendingRestartReason) {
        pendingRestartReason ?: return@LaunchedEffect
        delay(QR_SCAN_SESSION_RESTART_DELAY_MS)
        scanGeneration += 1
        pendingRestartReason = null
    }

    LaunchedEffect(diagnostics) {
        val activeDiagnostics = diagnostics ?: return@LaunchedEffect
        activeDiagnostics.logScannerStarted(
            requestedFormats = allowedFormats.size
        )
        while (!scanConsumed.get()) {
            delay(QR_SCAN_DIAG_HEARTBEAT_MS)
            activeDiagnostics.logHeartbeat(currentCameraSession.value.isProcessingFrame())
        }
    }

    LaunchedEffect(Unit) {
        showOverlay = true
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.46f),
                            Color.Transparent,
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.66f)
                        )
                    )
                )
        )

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(animationSpec = tween(280)) + slideInVertically(
                animationSpec = tween(280),
                initialOffsetY = { -it / 8 }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                    Column(
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (invalidCandidateSeen && invalidResultMessage != null) {
                            Text(invalidResultMessage, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        ScannerFrame(
            modifier = Modifier.align(Alignment.Center)
        )

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(animationSpec = tween(320)) + slideInVertically(
                animationSpec = tween(320),
                initialOffsetY = { it / 6 }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    bottomContent { photoPickerLauncher.launch("image/*") }
                }
            }
        }
    }
}

@Composable
private fun DefaultQrScannerBottomContent(
    launchGallery: () -> Unit
) {
    Text(
        text = stringResource(R.string.qr_align_hint),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    FilledTonalButton(
        onClick = launchGallery,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Image,
            contentDescription = stringResource(R.string.qr_pick_from_gallery)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(stringResource(R.string.qr_pick_from_gallery))
    }
}

@Composable
private fun ScannerFrame(
    modifier: Modifier = Modifier
) {
    val cornerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f)

    Box(
        modifier = modifier
            .size(268.dp)
            .clip(RoundedCornerShape(28.dp))
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                shape = RoundedCornerShape(28.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.08f))
        )

        ScannerCorner(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp),
            color = cornerColor,
            position = ScannerCornerPosition.TopStart
        )
        ScannerCorner(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp),
            color = cornerColor,
            position = ScannerCornerPosition.TopEnd
        )
        ScannerCorner(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
            color = cornerColor,
            position = ScannerCornerPosition.BottomStart
        )
        ScannerCorner(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
            color = cornerColor,
            position = ScannerCornerPosition.BottomEnd
        )
    }
}

private enum class ScannerCornerPosition {
    TopStart,
    TopEnd,
    BottomStart,
    BottomEnd
}

@Composable
private fun ScannerCorner(
    modifier: Modifier = Modifier,
    color: Color,
    position: ScannerCornerPosition
) {
    val horizontalAlignment = when (position) {
        ScannerCornerPosition.TopStart, ScannerCornerPosition.BottomStart -> Alignment.CenterStart
        ScannerCornerPosition.TopEnd, ScannerCornerPosition.BottomEnd -> Alignment.CenterEnd
    }
    val verticalAlignment = when (position) {
        ScannerCornerPosition.TopStart, ScannerCornerPosition.TopEnd -> Alignment.TopCenter
        ScannerCornerPosition.BottomStart, ScannerCornerPosition.BottomEnd -> Alignment.BottomCenter
    }
    val cornerAlignment = when (position) {
        ScannerCornerPosition.TopStart -> Alignment.TopStart
        ScannerCornerPosition.TopEnd -> Alignment.TopEnd
        ScannerCornerPosition.BottomStart -> Alignment.BottomStart
        ScannerCornerPosition.BottomEnd -> Alignment.BottomEnd
    }

    Box(modifier = modifier.size(30.dp)) {
        Box(
            modifier = Modifier
                .align(verticalAlignment)
                .height(5.dp)
                .width(30.dp)
                .clip(CircleShape)
                .background(color)
        )
        Box(
            modifier = Modifier
                .align(horizontalAlignment)
                .width(5.dp)
                .height(30.dp)
                .clip(CircleShape)
                .background(color)
        )
        Box(
            modifier = Modifier
                .align(cornerAlignment)
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

private suspend fun processImageWithZxing(
    context: Context,
    uri: Uri,
    decoder: ZxingBarcodeDecoder,
    diagnostics: QrScannerDiagnostics?,
    resultValidator: (String) -> Boolean,
    onResult: (String) -> Unit,
    onInvalid: () -> Unit,
    onNotFound: () -> Unit
) {
    val startedAt = SystemClock.elapsedRealtime()
    val bitmap = withContext(Dispatchers.IO) { loadDownsampledBitmap(context, uri) }
    if (bitmap == null) {
        diagnostics?.logGalleryDecodeFailed(IllegalStateException("bitmap_decode_failed"))
        onNotFound()
        return
    }

    val candidates = try {
        withContext(Dispatchers.Default) { decoder.decodeBitmap(bitmap) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        diagnostics?.logGalleryScanFailed(error)
        onNotFound()
        return
    } finally {
        bitmap.recycle()
    }

    when (val value = candidates.firstOrNull(resultValidator)) {
        null -> {
            if (candidates.isEmpty()) {
                diagnostics?.logGalleryResult(
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                    barcodeCount = candidates.size,
                    candidateCount = candidates.size,
                    matched = false,
                    invalid = false
                )
                onNotFound()
            } else {
                diagnostics?.logGalleryResult(
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                    barcodeCount = candidates.size,
                    candidateCount = candidates.size,
                    matched = false,
                    invalid = true
                )
                onInvalid()
            }
        }
        else -> {
            diagnostics?.logGalleryResult(
                durationMs = SystemClock.elapsedRealtime() - startedAt,
                barcodeCount = candidates.size,
                candidateCount = candidates.size,
                matched = true,
                invalid = false
            )
            onResult(value)
        }
    }
}

private fun loadDownsampledBitmap(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > GALLERY_MAX_DIMENSION) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }.getOrNull()
}

private const val QR_SCAN_DIAG_HEARTBEAT_MS = 30_000L
private const val QR_SCAN_HEALTH_TICK_MS = 500L
private const val QR_SCAN_SESSION_RESTART_DELAY_MS = 450L
private const val GALLERY_MAX_DIMENSION = 2048
