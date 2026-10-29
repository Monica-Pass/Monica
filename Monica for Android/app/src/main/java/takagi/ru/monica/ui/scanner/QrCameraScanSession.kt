package takagi.ru.monica.ui.scanner

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.zxing.BarcodeFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class QrCameraScanSession(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    allowedFormats: Collection<BarcodeFormat>,
    private val generation: Int,
    private val diagnostics: QrScannerDiagnostics?,
    private val onCandidates: (List<String>, barcodeCount: Int, durationMs: Long) -> Boolean,
    private val onRestartRequested: (QrScanRestartReason) -> Unit,
    preserveWhitespace: Boolean = false,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val controller = LifecycleCameraController(appContext)
    private val decoder = ZxingBarcodeDecoder(allowedFormats, preserveWhitespace)
    private val allowedFormatCount = allowedFormats.size
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val healthPolicy = QrScanHealthPolicy()
    private val active = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val processingFrame = AtomicBoolean(false)
    private val resultPending = AtomicBoolean(false)
    private val foreground = AtomicBoolean(false)
    private val lifecycleGeneration = AtomicInteger(0)
    private val previewStreaming = AtomicBoolean(false)
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                healthPolicy.onSessionStarted(SystemClock.elapsedRealtime())
                foreground.set(true)
            }
            Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                foreground.set(false)
                lifecycleGeneration.incrementAndGet()
            }
            else -> Unit
        }
    }
    private val previewObserver = Observer<PreviewView.StreamState> { state ->
        val streaming = state == PreviewView.StreamState.STREAMING
        val changed = previewStreaming.getAndSet(streaming) != streaming
        if (changed) diagnostics?.logPreviewState(streaming)
        if (streaming) {
            requestCenterFocus(reason = "preview_streaming")
        }
    }

    fun start() {
        check(active.compareAndSet(false, true)) { "QR camera session already started" }
        val startedAt = SystemClock.elapsedRealtime()
        healthPolicy.onSessionStarted(startedAt)
        diagnostics?.logSessionStarted(generation)
        diagnostics?.logCameraControllerRequested(allowedFormatCount)
        Log.d("qr_scan_perf", "session start gen=$generation")
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        controller.setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        )
        controller.setTapToFocusEnabled(true)
        controller.setImageAnalysisAnalyzer(analysisExecutor, ::analyzeFrame)

        previewView.controller = controller
        previewView.previewStreamState.observeForever(previewObserver)

        runCatching {
            controller.bindToLifecycle(lifecycleOwner)
        }.onSuccess {
            Log.d("qr_scan_perf", "bind ok")
        }.onFailure { error ->
            Log.d("qr_scan_perf", "bind failed", error)
            diagnostics?.logCameraBindFailed(error)
            requestRestart(QrScanRestartReason.FrameStreamStopped)
            return
        }

        controller.initializationFuture.addListener(
            {
                if (!active.get()) return@addListener
                runCatching { controller.initializationFuture.get() }
                    .onSuccess {
                        Log.d("qr_scan_perf", "init ok")
                        diagnostics?.logCameraBindSuccess(SystemClock.elapsedRealtime() - startedAt)
                        previewView.post { requestCenterFocus(reason = "session_start") }
                    }
                    .onFailure { error ->
                        Log.d("qr_scan_perf", "init failed", error)
                        diagnostics?.logCameraProviderFailed(error)
                        requestRestart(QrScanRestartReason.FrameStreamStopped)
                    }
            },
            mainExecutor
        )
    }

    fun tick(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!active.get()) return
        when (val action = healthPolicy.nextAction(nowMs, previewStreaming.get(), foreground.get())) {
            QrScanHealthAction.None -> Unit
            QrScanHealthAction.Refocus -> {
                if (requestCenterFocus(reason = "periodic")) {
                    healthPolicy.onRefocusRequested(nowMs)
                }
            }
            is QrScanHealthAction.Restart -> requestRestart(action.reason)
        }
    }

    fun isProcessingFrame(): Boolean = processingFrame.get()

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!active.get() || !foreground.get()) {
            imageProxy.close()
            return
        }
        if (!processingFrame.compareAndSet(false, true)) {
            diagnostics?.logFrameSkipped()
            imageProxy.close()
            return
        }

        val frameStartedAt = SystemClock.elapsedRealtime()
        val frameGeneration = lifecycleGeneration.get()
        healthPolicy.onFrameStarted(frameStartedAt)
        var succeeded = false
        val candidates = try {
            diagnostics?.logFrameStarted(imageProxy.imageInfo.rotationDegrees)
            decoder.decodeFrame(imageProxy).also { succeeded = true }
        } catch (error: Exception) {
            Log.d("qr_scan_perf", "decode error", error)
            diagnostics?.logFrameFailure(error)
            emptyList()
        } finally {
            if (active.get() && frameGeneration == lifecycleGeneration.get()) {
                healthPolicy.onFrameCompleted(SystemClock.elapsedRealtime(), succeeded)
            }
            try {
                imageProxy.close()
            } finally {
                processingFrame.set(false)
            }
        }

        if (!succeeded || !active.get() || frameGeneration != lifecycleGeneration.get()) return
        val durationMs = SystemClock.elapsedRealtime() - frameStartedAt
        if (candidates.isEmpty()) {
            diagnostics?.logFrameSuccess(durationMs, 0, 0, matched = false)
            return
        }
        // Release the camera frame before navigation. Only one result may wait on
        // the UI thread; paused/replaced sessions must never deliver a stale code.
        if (!resultPending.compareAndSet(false, true)) return
        mainExecutor.execute {
            try {
                if (!active.get() || !foreground.get() || frameGeneration != lifecycleGeneration.get()) {
                    return@execute
                }
                val matched = try {
                    onCandidates(candidates, candidates.size, durationMs)
                } catch (error: Exception) {
                    diagnostics?.logResultDeliveryFailed(error)
                    false
                }
                diagnostics?.logFrameSuccess(durationMs, candidates.size, candidates.size, matched)
            } finally {
                resultPending.set(false)
            }
        }
    }

    private fun requestCenterFocus(reason: String): Boolean {
        if (!active.get() || !foreground.get() || !previewStreaming.get()) return false
        if (previewView.width <= 0 || previewView.height <= 0) return false

        return runCatching {
            val point = previewView.meteringPointFactory.createPoint(
                previewView.width / 2f,
                previewView.height / 2f,
                FOCUS_POINT_SIZE
            )
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or
                    FocusMeteringAction.FLAG_AE or
                    FocusMeteringAction.FLAG_AWB
            )
                .setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
                .build()
            val cameraControl = controller.cameraControl ?: return@runCatching false
            cameraControl.startFocusAndMetering(action)
            diagnostics?.logRefocusRequested(reason)
            true
        }.onFailure { error ->
            diagnostics?.logRefocusFailed(error)
        }.getOrDefault(false)
    }

    private fun requestRestart(reason: QrScanRestartReason) {
        if (!active.compareAndSet(true, false)) return
        Log.d("qr_scan_perf", "restart requested reason=$reason")
        diagnostics?.logSessionRestartRequested(reason)
        closeResources()
        mainExecutor.execute {
            if (!disposed.get()) onRestartRequested(reason)
        }
    }

    override fun close() {
        Log.d("qr_scan_perf", "close()")
        disposed.set(true)
        active.set(false)
        closeResources()
    }

    private fun closeResources() {
        if (!closed.compareAndSet(false, true)) return
        diagnostics?.logDispose(processingFrame.get())
        foreground.set(false)
        lifecycleGeneration.incrementAndGet()
        previewStreaming.set(false)
        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        runCatching { previewView.previewStreamState.removeObserver(previewObserver) }
        runCatching { controller.clearImageAnalysisAnalyzer() }
        runCatching {
            if (previewView.controller === controller) previewView.controller = null
        }
        runCatching { controller.unbind() }
        analysisExecutor.shutdown()
    }

    private companion object {
        private const val ANALYSIS_WIDTH = 1280
        private const val ANALYSIS_HEIGHT = 960
        private const val FOCUS_POINT_SIZE = 0.24f
        private const val FOCUS_AUTO_CANCEL_SECONDS = 3L
    }
}
