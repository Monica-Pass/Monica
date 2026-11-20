package takagi.ru.monica.ui.scanner

import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.PreviewView
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import takagi.ru.monica.steam.network.SteamQrChallenge
import takagi.ru.monica.ui.screens.QrScannerScreen

/** Real shared scanner, CameraX ownership, lifecycle and navigation; only camera pixels are synthetic. */
@SdkSuppress(minSdkVersion = 28)
@RunWith(AndroidJUnit4::class)
class QrScannerSessionScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val received = CopyOnWriteArrayList<String>()
    private val launchedIntents = CopyOnWriteArrayList<Intent>()
    private val receivedFormats = CopyOnWriteArrayList<BarcodeFormat>()
    private val deliveredOnMain = AtomicBoolean(false)
    private val validationOnMain = AtomicBoolean(true)
    private val decodingOnMain = AtomicBoolean(false)
    private val candidateFrameClosed = AtomicBoolean(false)
    private val deliveredAfterFrameClosed = AtomicBoolean(false)
    private val acceptanceAttempts = AtomicInteger()
    private val executors = mutableListOf<ExecutorService>()
    private val diagnosticSink: (String) -> Unit = { diagnostics += it }
    private lateinit var navigation: NavHostController

    @Before fun allowCamera() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
    }

    @After fun saveEvidence() {
        val directory = instrumentation.targetContext.getExternalFilesDir("scanner-verification")!!
        File(directory, "${testName.methodName}.log").writeText(diagnostics.joinToString("\n"))
        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
            File(directory, "${testName.methodName}.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
        executors.forEach { it.shutdown() }
    }

    private fun showScanner(steam: Boolean = true, failFirstAcceptance: Boolean = false, boarding: Boolean = false, unified: Boolean = false, noHandler: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                navigation = rememberNavController()
                NavHost(navigation, startDestination = "home") {
                    composable("home") {
                        Button(onClick = { navigation.navigate("scan") }, modifier = Modifier.testTag("scanner_home")) { Text("Scan") }
                    }
                    composable("scan") {
                        val onResult: (String) -> Unit = { payload ->
                            deliveredOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                            deliveredAfterFrameClosed.set(candidateFrameClosed.get())
                            if (acceptanceAttempts.incrementAndGet() == 1 && failFirstAcceptance) {
                                throw IllegalStateException("Simulated navigation failure")
                            }
                            received += payload
                            navigation.previousBackStackEntry?.savedStateHandle?.set("qr_result", payload)
                            navigation.popBackStack()
                        }
                        val validator: (String) -> Boolean = {
                            if (Looper.myLooper() != Looper.getMainLooper()) validationOnMain.set(false)
                            !steam || SteamQrChallenge.parse(it) != null
                        }
                        if (unified) {
                            val base = LocalContext.current
                            val launchContext = androidx.compose.runtime.remember(base) {
                                object : ContextWrapper(base) {
                                    override fun startActivity(intent: Intent) {
                                        if (noHandler) throw ActivityNotFoundException("Synthetic missing handler")
                                        launchedIntents += intent
                                    }
                                }
                            }
                            CompositionLocalProvider(LocalContext provides launchContext) {
                                UnifiedQrScannerScreen(
                                    onNavigateBack = { navigation.popBackStack() },
                                    onAuthenticatorScanned = onResult,
                                    diagnosticLabel = "unified_test", onDiagnostic = diagnosticSink
                                )
                            }
                        }
                        else if (boarding) QrScannerScreen({ error("Legacy callback must not receive typed results") }, { navigation.popBackStack() },
                            onBarcodeScanned = { value, format -> receivedFormats += format; onResult(value) },
                            allowedFormats = listOf(BarcodeFormat.PDF_417, BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX),
                            preserveWhitespace = true,
                            resultValidator = { takagi.ru.monica.data.model.BoardingPassParser.parse(it) != null },
                            diagnosticLabel = "boarding_test", onDiagnostic = diagnosticSink)
                        else if (steam) QrScannerScreen(onResult, { navigation.popBackStack() },
                            allowedFormats = listOf(BarcodeFormat.QR_CODE),
                            resultValidator = validator,
                            diagnosticLabel = "steam_test", onDiagnostic = diagnosticSink)
                        else QrScannerScreen(onResult, { navigation.popBackStack() },
                            resultValidator = validator,
                            diagnosticLabel = "authenticator_test", onDiagnostic = diagnosticSink)
                    }
                }
            }
        }
        compose.onNodeWithTag("scanner_home").performClick()
        awaitStreaming()
        compose.waitUntil(20_000) { diagnostics.any { "event=first_frame" in it } }
    }

    private fun preview(): PreviewView? {
        fun find(view: View): PreviewView? {
            if (view is PreviewView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        var result: PreviewView? = null
        compose.runOnIdle { result = find(compose.activity.window.decorView) }
        return result
    }

    private fun awaitStreaming() {
        compose.waitUntil(20_000) {
            val view = preview()
            var ready = false
            compose.runOnIdle { ready = view?.isAttachedToWindow == true && view.controller != null &&
                view.previewStreamState.value == PreviewView.StreamState.STREAMING }
            ready
        }
    }

    @Test fun aStoppedFrameStreamRecoversOnTheAttachedPreview() {
        showScanner()
        val view = requireNotNull(preview())
        val initialSessions = diagnostics.count { "event=session_started" in it }
        compose.runOnIdle { requireNotNull(view.controller).clearImageAnalysisAnalyzer() }
        compose.waitUntil(12_000) { diagnostics.count { "event=session_started" in it } > initialSessions }
        awaitStreaming()
        val recoveredView = requireNotNull(preview())
        compose.runOnIdle {
            assertNotNull("Recovery must attach its controller to the visible PreviewView", recoveredView.controller)
        }
        val feed = feedCamera()
        feed.fixture.set(QrScannerFixtures.Frame().code(QrScannerFixtures.STEAM))
        assertReturned(QrScannerFixtures.STEAM)
    }

    @Test fun boardingCameraDeliversOriginalPayloadAndFormatExactlyOnce() {
        val raw = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 10C>5180  W0D  "
        showScanner(boarding = true)
        val feed = feedCamera()
        feed.fixture.set(QrScannerFixtures.Frame().code(raw, format = BarcodeFormat.PDF_417))
        assertReturned(raw)
        assertEquals(listOf(BarcodeFormat.PDF_417), receivedFormats.toList())
    }

    @Test fun steamScannerCanNavigateAfterAnIdleCameraSession() = scanAfterIdle(steam = true)

    @Test fun authenticatorScannerCanNavigateAfterAnIdleCameraSession() = scanAfterIdle(steam = false)

    private fun scanAfterIdle(steam: Boolean) {
        showScanner(steam)
        val feed = feedCamera()
        feed.useRealCameraPixels.set(InstrumentationRegistry.getArguments().getString("scannerRealFrames") == "true")
        val sessions = diagnostics.count { "event=session_started" in it }
        val idleMs = InstrumentationRegistry.getArguments().getString("scannerIdleMs")?.toLong() ?: 10_000L
        val startedAt = SystemClock.elapsedRealtime()
        waitWithHealthChecks(idleMs)
        assertTrue("Camera must continue delivering empty frames throughout idle", feed.frames.get() >= 10)
        assertEquals("Healthy empty scanning must not restart the camera", sessions, diagnostics.count { "event=session_started" in it })
        diagnostics += "test_idle duration_ms=${SystemClock.elapsedRealtime() - startedAt} frames=${feed.frames.get()} real_frames=${feed.useRealCameraPixels.get()} max_decode_ms=${feed.maxDecodeMs.get()}"
        val payload = if (steam) QrScannerFixtures.STEAM else QrScannerFixtures.TOTP
        feed.fixture.set(QrScannerFixtures.Frame().code(payload))
        feed.useRealCameraPixels.set(false)
        assertReturned(payload)
    }

    @Test fun steamSelectsItsCodeAmongNormalAndInvertedCandidates() {
        showScanner()
        val feed = feedCamera()
        feed.fixture.set(QrScannerFixtures.Frame()
            .code(QrScannerFixtures.UNRELATED, left = 70, top = 290, size = 380)
            .code(QrScannerFixtures.STEAM, left = 830, top = 290, size = 380, inverted = true))
        assertReturned(QrScannerFixtures.STEAM)
    }

    @Test fun failedAcceptanceDoesNotPermanentlyConsumeScanning() {
        showScanner(failFirstAcceptance = true)
        val feed = feedCamera()
        feed.fixture.set(QrScannerFixtures.Frame().code(QrScannerFixtures.STEAM))
        assertReturned(QrScannerFixtures.STEAM)
        assertEquals(2, acceptanceAttempts.get())
        assertTrue(diagnostics.any { "event=result_delivery_failed" in it })
    }

    @Test fun aMalformedFrameIsReleasedAndLaterFramesStillDecode() {
        showScanner()
        val feed = feedCamera()
        feed.corruptNextFrame.set(true)
        compose.waitUntil(5_000) { feed.corruptFrameClosed.get() }
        assertTrue(diagnostics.any { "event=frame_scan_failed" in it })
        feed.fixture.set(QrScannerFixtures.Frame().code(QrScannerFixtures.STEAM))
        assertReturned(QrScannerFixtures.STEAM)
    }

    @Test fun backgroundingDoesNotRestartAndResumeStillScans() {
        showScanner()
        val feed = feedCamera()
        val sessions = diagnostics.count { "event=session_started" in it }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitWithHealthChecks(10_000L)
        assertEquals(sessions, diagnostics.count { "event=session_started" in it })
        assertTrue(received.isEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitStreaming()
        feed.fixture.set(QrScannerFixtures.Frame().code(QrScannerFixtures.STEAM))
        assertReturned(QrScannerFixtures.STEAM)
    }

    @Test fun aQueuedResultCannotNavigateAfterLeavingTheScanner() {
        showScanner()
        val view = requireNotNull(preview())
        val frameClosed = AtomicBoolean(false)
        val frame = QrScannerFixtures.Frame()
            .code(QrScannerFixtures.STEAM, left = 70, top = 290, size = 380)
            .code(QrScannerFixtures.STEAM, left = 830, top = 290, size = 380, inverted = true)
        val proxy = object : ImageProxy by frame.proxy() {
            override fun close() { frameClosed.set(true) }
        }
        compose.runOnIdle {
            val controller = requireNotNull(view.controller)
            val analyzer = analyzer(controller)
            val executorField = CameraController::class.java.getDeclaredField("mAnalysisExecutor").apply { isAccessible = true }
            val executor = executorField.get(controller) as Executor
            controller.clearImageAnalysisAnalyzer()
            // Hold UI delivery until decoding has completed, then leave the route
            // before the queued result gets its turn on the main executor.
            val decode = FutureTask { analyzer.analyze(proxy) }
            executor.execute(decode)
            decode.get(5, TimeUnit.SECONDS)
            assertTrue(frameClosed.get())
            navigation.popBackStack()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("scanner_home").assertIsDisplayed()
        assertTrue("A retired camera session must not submit a queued code", received.isEmpty())
    }

    @Test fun unifiedCameraRoutesAuthenticatorExactlyOnce() {
        showScanner(unified = true)
        val feed = feedCamera()
        feed.fixture.set(QrScannerFixtures.Frame().code(QrScannerFixtures.TOTP))
        assertReturned(QrScannerFixtures.TOTP)
        assertTrue(launchedIntents.isEmpty())
    }

    @Test fun unifiedCameraRoutesPasskeyToSystemOnly() {
        showScanner(unified = true)
        val code = UnifiedScannerFixtures.fido()
        val feed = feedCamera()
        feed.fixture.set(QrScannerFixtures.Frame().code(code))
        compose.waitUntil(15_000) { launchedIntents.isNotEmpty() }
        compose.onNodeWithTag("scanner_home").assertIsDisplayed()
        assertEquals(1, launchedIntents.size)
        assertEquals(Intent.ACTION_VIEW, launchedIntents.single().action)
        assertEquals(code, launchedIntents.single().dataString)
        assertTrue(received.isEmpty())
    }

    @Test fun unifiedCameraRejectsUnknownThenAcceptsValidCode() {
        showScanner(unified = true)
        val feed = feedCamera()
        feed.fixture.set(QrScannerFixtures.Frame().code(QrScannerFixtures.UNRELATED))
        compose.waitUntil(15_000) { compose.onAllNodesWithText(instrumentation.targetContext.getString(takagi.ru.monica.R.string.qr_credentials_invalid)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(received.isEmpty())
        assertTrue(launchedIntents.isEmpty())
        feed.fixture.set(QrScannerFixtures.Frame().code(QrScannerFixtures.TOTP))
        assertReturned(QrScannerFixtures.TOTP)
    }

    @Test fun unifiedCameraRequiresOneCodeAndCanRetry() {
        showScanner(unified = true)
        val feed = feedCamera()
        feed.fixture.set(QrScannerFixtures.Frame()
            .code(QrScannerFixtures.TOTP, left = 70, top = 290, size = 380)
            .code(UnifiedScannerFixtures.fido(), left = 830, top = 290, size = 380))
        compose.waitUntil(15_000) { compose.onAllNodesWithText(instrumentation.targetContext.getString(takagi.ru.monica.R.string.qr_multiple_codes)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(received.isEmpty())
        assertTrue(launchedIntents.isEmpty())
        captureUnified("multiple")
        feed.fixture.set(QrScannerFixtures.Frame().code(QrScannerFixtures.TOTP))
        assertReturned(QrScannerFixtures.TOTP)
    }

    @Test fun unifiedCameraTreatsDuplicateCodesAsOne() {
        showScanner(unified = true)
        val feed = feedCamera()
        feed.fixture.set(QrScannerFixtures.Frame()
            .code(QrScannerFixtures.TOTP, left = 70, top = 290, size = 380)
            .code(QrScannerFixtures.TOTP, left = 830, top = 290, size = 380))
        assertReturned(QrScannerFixtures.TOTP)
    }

    @Test fun unifiedMissingSystemHandlerDoesNotCreateAuthenticator() {
        showScanner(unified = true, noHandler = true)
        val feed = feedCamera()
        feed.fixture.set(QrScannerFixtures.Frame().code(UnifiedScannerFixtures.fido()))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("scanner_home").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(received.isEmpty())
        assertTrue(launchedIntents.isEmpty())
    }

    @Test fun unifiedScannerShowsGreenCornersAndBackLeavesWithoutResult() {
        showScanner(unified = true)
        feedCamera()
        compose.onNodeWithTag("scanner_frame").assertIsDisplayed()
        captureUnified("camera")
        compose.onNodeWithContentDescription(instrumentation.targetContext.getString(takagi.ru.monica.R.string.go_back)).performClick()
        compose.onNodeWithTag("scanner_home").assertIsDisplayed()
        assertTrue(received.isEmpty())
        assertTrue(launchedIntents.isEmpty())
    }

    @Test fun unifiedScannerLargeTextDarkThemeKeepsControlsVisible() {
        var wentBack = false
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    UnifiedQrScannerScreen(onNavigateBack = { wentBack = true }, onAuthenticatorScanned = { error("No fixture was presented") })
                }
            }
        }
        awaitStreaming()
        compose.onNodeWithTag("scanner_frame").assertIsDisplayed()
        compose.onNodeWithText(instrumentation.targetContext.getString(takagi.ru.monica.R.string.qr_credentials_hint)).assertIsDisplayed()
        compose.onNodeWithText(instrumentation.targetContext.getString(takagi.ru.monica.R.string.qr_pick_from_gallery)).assertIsDisplayed()
        val titleImage = compose.onNodeWithText(instrumentation.targetContext.getString(takagi.ru.monica.R.string.scan_qr_code_title)).captureToImage().asAndroidBitmap()
        val titlePixels = IntArray(titleImage.width * titleImage.height)
        titleImage.getPixels(titlePixels, 0, titleImage.width, 0, 0, titleImage.width, titleImage.height)
        assertTrue("Dark theme title must have light foreground pixels", titlePixels.count {
            android.graphics.Color.red(it) > 180 && android.graphics.Color.green(it) > 180 && android.graphics.Color.blue(it) > 180
        } > titlePixels.size / 100)
        titleImage.recycle()
        captureUnified("camera-dark-large")
        compose.onNodeWithContentDescription(instrumentation.targetContext.getString(takagi.ru.monica.R.string.go_back)).performClick()
        compose.runOnIdle { assertTrue(wentBack) }
    }

    @Test fun unifiedGalleryButtonDeliversSelectedOtpThroughActivityResult() {
        showScanner(unified = true)
        val file = File(instrumentation.targetContext.cacheDir, "unified-picker-fixture.png")
        val bitmap = QrScannerFixtures.Frame().code(QrScannerFixtures.TOTP).bitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_GET_CONTENT) android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,
                    Intent().setData(android.net.Uri.fromFile(file))) else null
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithText(instrumentation.targetContext.getString(takagi.ru.monica.R.string.qr_pick_from_gallery)).performClick()
            compose.waitUntil(15_000) { received.isNotEmpty() }
            compose.onNodeWithTag("scanner_home").assertIsDisplayed()
            assertEquals(listOf(QrScannerFixtures.TOTP), received.toList())
            assertTrue(launchedIntents.isEmpty())
        } finally { instrumentation.removeMonitor(monitor); file.delete() }
    }

    @Test fun cancellingGalleryLeavesUnifiedScannerReady() {
        showScanner(unified = true)
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_GET_CONTENT) android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null) else null
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithText(instrumentation.targetContext.getString(takagi.ru.monica.R.string.qr_pick_from_gallery)).performClick()
            compose.onNodeWithTag("scanner_frame").assertIsDisplayed()
            assertTrue(received.isEmpty())
            assertTrue(launchedIntents.isEmpty())
            val feed = feedCamera()
            feed.fixture.set(QrScannerFixtures.Frame().code(QrScannerFixtures.TOTP))
            assertReturned(QrScannerFixtures.TOTP)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun captureUnified(name: String) {
        // Let any platform toast from the previous missing-handler test expire.
        SystemClock.sleep(4_000)
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, "unified-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private class CameraFeed {
        val blank = QrScannerFixtures.Frame()
        val fixture = AtomicReference(blank)
        val frames = AtomicInteger()
        val corruptNextFrame = AtomicBoolean(false)
        val corruptFrameClosed = AtomicBoolean(false)
        val useRealCameraPixels = AtomicBoolean(false)
        val maxDecodeMs = AtomicLong()
    }

    private fun feedCamera(): CameraFeed {
        val feed = CameraFeed()
        val view = requireNotNull(preview())
        val controller = compose.runOnIdle { requireNotNull(view.controller) }
        // CameraController exposes no analyzer getter. Wrap its existing analyzer
        // so actual CameraX frames still exercise the production session and close().
        val delegate = analyzer(controller)
        val executor = Executors.newSingleThreadExecutor().also(executors::add)
        compose.runOnIdle {
            controller.setImageAnalysisAnalyzer(executor) { original ->
                if (Looper.myLooper() == Looper.getMainLooper()) decodingOnMain.set(true)
                feed.frames.incrementAndGet()
                val pixels = if (feed.useRealCameraPixels.get()) null else feed.fixture.get()
                val corrupt = feed.corruptNextFrame.compareAndSet(true, false)
                val proxy = pixels?.replacePixels(original) ?: original
                val startedAt = SystemClock.elapsedRealtime()
                delegate.analyze(object : ImageProxy by proxy {
                    override fun getPlanes(): Array<ImageProxy.PlaneProxy> =
                        if (corrupt) emptyArray() else proxy.planes

                    override fun close() {
                        proxy.close()
                        if (corrupt) feed.corruptFrameClosed.set(true)
                        if (pixels != null && pixels !== feed.blank) candidateFrameClosed.set(true)
                    }
                })
                feed.maxDecodeMs.updateAndGet { maxOf(it, SystemClock.elapsedRealtime() - startedAt) }
            }
        }
        return feed
    }

    private fun analyzer(controller: CameraController): ImageAnalysis.Analyzer {
        val field = CameraController::class.java.getDeclaredField("mAnalysisAnalyzer").apply { isAccessible = true }
        return field.get(controller) as ImageAnalysis.Analyzer
    }

    private fun waitWithHealthChecks(durationMs: Long) {
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < durationMs) {
            compose.mainClock.advanceTimeBy(100)
            SystemClock.sleep(100)
        }
    }

    private fun assertReturned(payload: String) {
        compose.waitUntil(15_000) { received.isNotEmpty() }
        assertTrue("Scanner result/navigation must run on the main thread", deliveredOnMain.get())
        assertTrue("Caller validation belongs on the UI thread", validationOnMain.get())
        assertFalse("Camera decoding must stay off the UI thread", decodingOnMain.get())
        assertTrue("Release the accepted camera frame before navigating", deliveredAfterFrameClosed.get())
        compose.onNodeWithTag("scanner_home").assertIsDisplayed()
        assertEquals(listOf(payload), received.toList())
    }
}
