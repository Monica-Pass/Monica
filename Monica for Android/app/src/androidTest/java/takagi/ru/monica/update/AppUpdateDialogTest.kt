package takagi.ru.monica.update

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.suggestions.CommonInfoTestActivity
import takagi.ru.monica.utils.UpdateCheckResult
import takagi.ru.monica.utils.UpdateDownloadProgress

@RunWith(AndroidJUnit4::class)
class AppUpdateDialogTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<ControllerFixture>()

    @After fun cleanup() {
        show {}
        compose.runOnUiThread {
            fixtures.forEach { fixture ->
                fixture.responses.forEach { it.complete(Result.failure(IllegalStateException("test finished"))) }
                fixture.scope.cancel()
            }
        }
    }

    @Test fun fourActionsAndParsedMarkdownLinkReachTheirCallbacks() {
        val calls = mutableListOf<String>()
        show {
            MaterialTheme {
                AppUpdateDialog(
                    availableState("## Synthetic improvements\n\n**Stronger** notes.\n\n[Synthetic release link](https://example.com/release)"),
                    onDismiss = { calls += "later" }, onUpdate = { calls += "update" },
                    onSkip = { calls += "skip" }, onDetails = { calls += "details" },
                    onOpenLink = { calls += it },
                )
            }
        }
        awaitText("Synthetic release link")
        compose.onNodeWithText("Synthetic improvements").assertIsDisplayed()
        val link = compose.onNodeWithText("Synthetic release link")
        link.performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        link.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(1, layouts.single().layoutInput.text.getLinkAnnotations(0, layouts.single().layoutInput.text.length).size)
        val character = layouts.single().getBoundingBox(1)
        link.performTouchInput { click(Offset(character.center.x, character.center.y)) }
        for (tag in listOf("primary", "skip", "details", "later")) {
            compose.onNodeWithTag("app-update-$tag").assertIsDisplayed().performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("https://example.com/release", "update", "skip", "details", "later"), calls)
        }
        capture("normal-markdown-actions")
    }

    @Test fun longNotesScrollWhileDownloadProgressAndActionsStayFixed() {
        var dismissed = 0
        show {
            MaterialTheme {
                AppUpdateDialog(availableState(longNotes()).copy(
                    downloading = true, progress = UpdateDownloadProgress(37, 100)),
                    onDismiss = { dismissed++ }, onUpdate = { fail("disabled") },
                    onSkip = { fail("disabled") }, onDetails = { fail("disabled") }, onOpenLink = {},
                )
            }
        }
        awaitText("Synthetic final paragraph")
        val progress = compose.onNodeWithTag("app-update-progress")
        val action = compose.onNodeWithTag("app-update-primary")
        progress.assertIsDisplayed()
        compose.onNodeWithText("37%").assertIsDisplayed()
        val progressBefore = progress.fetchSemanticsNode().boundsInWindow
        val actionBefore = action.fetchSemanticsNode().boundsInWindow
        compose.onNodeWithText("Synthetic final paragraph").performScrollTo().assertIsDisplayed()
        assertEquals(progressBefore, progress.fetchSemanticsNode().boundsInWindow)
        assertEquals(actionBefore, action.fetchSemanticsNode().boundsInWindow)
        action.assertIsNotEnabled()
        compose.onNodeWithTag("app-update-skip").assertIsNotEnabled()
        compose.onNodeWithTag("app-update-details").assertIsNotEnabled()
        capture("long-notes-progress")
        compose.onNodeWithTag("app-update-later").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun smallLargeTextRtlDarkDialogKeepsTextAndActionsReachable() {
        val calls = mutableListOf<String>()
        show {
            val metrics = context.resources.displayMetrics
            val density = metrics.widthPixels / 320f
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 320
                screenHeightDp = (metrics.heightPixels / density).toInt()
                fontScale = 1.5f
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density, 1.5f),
                LocalConfiguration provides configuration,
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    AppUpdateDialog(availableState("## Synthetic heading\n\nA readable paragraph with **emphasis**."),
                        onDismiss = { calls += "later" }, onUpdate = { calls += "update" },
                        onSkip = { calls += "skip" }, onDetails = { calls += "details" }, onOpenLink = {})
                }
            }
        }
        awaitText("Synthetic heading")
        compose.onNodeWithTag("app-update-dialog").assertIsDisplayed()
        for (id in listOf(R.string.update_now, R.string.update_skip_version,
            R.string.update_view_details, R.string.update_later)) {
            assertTextNotClipped(context.getString(id))
        }
        assertTextNotClipped(context.getString(R.string.update_check_update_available_title))
        compose.onNodeWithText("Synthetic heading").performScrollTo().assertIsDisplayed()
        capture("small-large-rtl-dark")
        for (tag in listOf("primary", "skip", "details", "later")) {
            compose.onNodeWithTag("app-update-$tag").performScrollTo().assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("update", "skip", "details", "later"), calls) }
    }

    @Test fun fdroidOffersItsStoreInsteadOfApkInstallText() {
        var store = 0
        show {
            MaterialTheme(colorScheme = lightColorScheme()) {
                AppUpdateDialog(availableState("## F-Droid release", fdroid = true),
                    onDismiss = {}, onUpdate = { store++ }, onSkip = {}, onDetails = {}, onOpenLink = {})
            }
        }
        awaitText("F-Droid release")
        compose.onNodeWithText(context.getString(R.string.update_fdroid_source)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_now)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.update_download_and_install)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.update_open_fdroid)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, store) }
        capture("fdroid-source")
    }

    @Test fun shortHeightLargeTextDownloadCanScrollToItsProgressAndCancel() {
        var dismissed = 0
        val density = context.resources.displayMetrics.widthPixels / 320f
        show {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 320
                screenHeightDp = 360
                fontScale = 1.5f
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, 1.5f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    AppUpdateDialog(availableState(longNotes()).copy(
                        downloading = true, progress = UpdateDownloadProgress(61, 100)),
                        onDismiss = { dismissed++ }, onUpdate = { fail("disabled") },
                        onSkip = { fail("disabled") }, onDetails = { fail("disabled") }, onOpenLink = {})
                }
            }
        }
        awaitText("Synthetic final paragraph")
        compose.onNodeWithTag("app-update-scroll-container").assertIsDisplayed()
        val dialog = compose.onNodeWithTag("app-update-dialog").fetchSemanticsNode()
        assertTrue("The short-height fixture must constrain the actual dialog", dialog.size.height / density <= 329f)
        compose.onNodeWithText("Synthetic final paragraph").performScrollTo().assertIsDisplayed()
        assertTextNotClipped("61%")
        for (tag in listOf("primary", "skip", "details")) {
            compose.onNodeWithTag("app-update-$tag").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        }
        assertTextNotClipped(context.getString(R.string.cancel))
        compose.onNodeWithTag("app-update-later").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("short-height-large-download")
        compose.onNodeWithTag("app-update-later").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun dialogUsesActualSystemBlurOrItsCurrentSystemFallbackOverSyntheticGrid() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        show {
            MaterialTheme {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0xFFE7F0FF))
                    val step = 28f
                    var x = 0f
                    while (x < size.width) {
                        drawLine(Color(0xFF163A65), Offset(x, 0f), Offset(x, size.height), 4f)
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawLine(Color(0xFF163A65), Offset(0f, y), Offset(size.width, y), 4f)
                        y += step
                    }
                }
                AppUpdateDialog(availableState("## Blur verification\n\nOnly synthetic grid content behind this dialog."),
                    onDismiss = {}, onUpdate = {}, onSkip = {}, onDetails = {}, onOpenLink = {})
            }
        }
        awaitText("Blur verification")
        val evidence = compose.runOnIdle {
            // Public Android view/window APIs only; no hidden reflection or global setting edits.
            val views = ArrayDeque<View>()
            views.addAll(WindowInspector.getGlobalWindowViews())
            var provider: DialogWindowProvider? = null
            while (views.isNotEmpty()) {
                val view = views.removeFirst()
                if (view is DialogWindowProvider && view.window.decorView.isShown) provider = view
                if (view is ViewGroup) repeat(view.childCount) { views.addLast(view.getChildAt(it)) }
            }
            val window = requireNotNull(provider) { "Production Compose dialog window must exist" }.window
            assertTrue(window !== compose.activity.window)
            val enabled = context.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
            val attrs = window.attributes
            if (enabled) {
                assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
                assertTrue(attrs.blurBehindRadius > 0)
                assertEquals(0.24f, attrs.dimAmount, 0.001f)
            } else {
                assertEquals(0, attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                assertEquals(0, attrs.blurBehindRadius)
                assertEquals(0.48f, attrs.dimAmount, 0.001f)
            }
            "api=${Build.VERSION.SDK_INT}\nsystemBlurEnabled=$enabled\nblurBehindRadius=${attrs.blurBehindRadius}\ndimAmount=${attrs.dimAmount}\nflags=${attrs.flags}\n"
        }
        val directory = File(context.filesDir, "update-dialog-327")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "actual-window-blur.txt").writeText(evidence)
        val screen = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory, "actual-window-blur-grid.png").outputStream().use {
                check(screen.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screen.recycle() }
    }

    @Test fun hostBackgroundCancelsCheckAndRejectsItsLateResponse() {
        val fixture = fixture()
        val owner = ResumedOwner()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        show {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { AppUpdateHost(fixture.controller, eligible = true, allowAutomatic = false) }
            }
        }
        compose.waitUntil { fixture.controller.state.value.loaded }
        compose.runOnIdle { fixture.controller.checkManually() }
        compose.waitUntil { fixture.requests.get() == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil { !fixture.controller.state.value.checking }
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        fixture.responses[0].complete(Result.success(release("Late background release")))
        compose.waitUntil { fixture.returned.get() == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(fixture.controller.state.value.result)
            assertFalse(fixture.controller.state.value.dialogVisible)
        }
    }

    @Test fun hostLockHidesVisibleDialogAndRevokesPendingResponse() {
        val fixture = fixture()
        val owner = ResumedOwner()
        var eligible by mutableStateOf(true)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        show {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { AppUpdateHost(fixture.controller, eligible, allowAutomatic = false) }
            }
        }
        compose.waitUntil { fixture.controller.state.value.loaded }
        compose.runOnIdle { fixture.controller.checkManually() }
        compose.waitUntil { fixture.requests.get() == 1 }
        fixture.responses[0].complete(Result.success(release("Visible synthetic release")))
        awaitText("Visible synthetic release")
        compose.onNodeWithTag("app-update-dialog").assertIsDisplayed()
        compose.runOnIdle { eligible = false }
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        compose.runOnIdle { eligible = true }
        compose.runOnIdle { fixture.controller.checkManually() }
        compose.waitUntil { fixture.requests.get() == 2 }
        compose.runOnIdle { eligible = false }
        fixture.responses[1].complete(Result.success(release("Late locked release")))
        compose.waitUntil { fixture.returned.get() == 2 }
        compose.runOnIdle { eligible = true }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        compose.runOnIdle { assertNull(fixture.controller.state.value.result) }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread { CommonInfoTestActivity.content = content }
        compose.waitForIdle()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertTextNotClipped(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        node.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val semantics = node.fetchSemanticsNode()
        val diagnostic = buildString {
            appendLine("text=$text")
            appendLine("semanticsSize=${semantics.size}")
            appendLine("boundsInRoot=${semantics.boundsInRoot}")
            appendLine("boundsInWindow=${semantics.boundsInWindow}")
            layouts.forEachIndexed { index, layout ->
                appendLine("layout[$index] size=${layout.size} paragraphWidth=${layout.multiParagraph.width} paragraphHeight=${layout.multiParagraph.height}")
                appendLine("constraints=${layout.layoutInput.constraints} density=${layout.layoutInput.density} direction=${layout.layoutInput.layoutDirection}")
                appendLine("overflowWidth=${layout.didOverflowWidth} overflowHeight=${layout.didOverflowHeight} maxLinesExceeded=${layout.multiParagraph.didExceedMaxLines}")
                appendLine("style=${layout.layoutInput.style} maxLines=${layout.layoutInput.maxLines} softWrap=${layout.layoutInput.softWrap}")
                repeat(layout.lineCount) { line ->
                    appendLine("line[$line] left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)} start=${layout.getLineStart(line)} end=${layout.getLineEnd(line)} ellipsized=${layout.isLineEllipsized(line)}")
                }
                layout.layoutInput.text.text.indices.forEach { offset ->
                    appendLine("character[$offset] bounds=${layout.getBoundingBox(offset)}")
                }
            }
        }
        val directory = File(context.filesDir, "update-dialog-327")
        check(directory.isDirectory || directory.mkdirs())
        val diagnosticName = "text-layout-${text.hashCode().toUInt().toString(16)}"
        File(directory, "$diagnosticName.txt").writeText(diagnostic)
        capture(diagnosticName)
        layouts.forEach { layout ->
            assertEquals(1.5f, layout.layoutInput.density.fontScale, 0f)
            assertFalse("$text must fit vertically\n$diagnostic", layout.didOverflowHeight)
            assertFalse("$text must keep every line", layout.multiParagraph.didExceedMaxLines)
            assertEquals("Measured text width matches the actual node", semantics.size.width, layout.size.width)
            assertEquals("Measured text height matches the actual node", semantics.size.height, layout.size.height)
            // GetTextLayoutResult for String Text reconstructs a MultiParagraph using the old
            // maximum constraint, while retaining the actual intrinsic layoutSize. Therefore
            // didOverflowWidth compares different layout widths. Test every real line and
            // character against the actual laid-out node instead, including left-edge clipping.
            fun assertVisible(left: Float, top: Float, right: Float, bottom: Float, part: String) {
                val tolerance = 0.5f // Only half a physical pixel for float/int layout rounding.
                assertTrue("$part must stay inside the left edge\n$diagnostic", left >= -tolerance)
                assertTrue("$part must stay inside the right edge\n$diagnostic", right <= layout.size.width + tolerance)
                assertTrue("$part must stay inside the top edge\n$diagnostic", top >= -tolerance)
                assertTrue("$part must stay inside the bottom edge\n$diagnostic", bottom <= layout.size.height + tolerance)
                assertTrue("$part must have finite ordered bounds\n$diagnostic",
                    left.isFinite() && right.isFinite() && top.isFinite() && bottom.isFinite() &&
                        left <= right && top <= bottom)
            }
            repeat(layout.lineCount) { line ->
                assertFalse("$text line $line must not be ellipsized", layout.isLineEllipsized(line))
                assertVisible(layout.getLineLeft(line), layout.getLineTop(line),
                    layout.getLineRight(line), layout.getLineBottom(line), "$text line $line")
            }
            layout.layoutInput.text.text.forEachIndexed { offset, character ->
                if (character != '\n' && character != '\r') {
                    val bounds = layout.getBoundingBox(offset)
                    assertVisible(bounds.left, bounds.top, bounds.right, bounds.bottom, "$text character $offset")
                }
            }
        }
        assertEquals("$text remains vertically visible", semantics.size.height.toFloat(), semantics.boundsInRoot.height, 1f)
        assertEquals("$text remains horizontally visible", semantics.size.width.toFloat(), semantics.boundsInRoot.width, 1f)
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("app-update-dialog").captureToImage().asAndroidBitmap()
        try {
            val directory = File(context.filesDir, "update-dialog-327")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun availableState(notes: String, fdroid: Boolean = false) = AppUpdateState(
        loaded = true, result = release(notes, fdroid), dialogVisible = true,
    )

    private fun release(notes: String, fdroid: Boolean = false) = UpdateCheckResult(
        currentVersion = "1.0.326", latestVersion = "1.0.327", releaseName = "Synthetic release",
        releaseUrl = if (fdroid) "https://f-droid.org/packages/takagi.ru.monica.fdroid/" else "https://example.com/release",
        apkAssetName = if (fdroid) null else "synthetic.apk",
        apkDownloadUrl = if (fdroid) null else "https://example.com/synthetic.apk",
        releaseNotes = notes, isUpdateAvailable = true, releaseIdentity = "synthetic-327", isFdroid = fdroid,
    )

    private fun longNotes() = (1..50).joinToString("\n\n") { "Paragraph $it with **synthetic** release details." } +
        "\n\nSynthetic final paragraph"

    private fun fixture(): ControllerFixture {
        val responses = List(2) { CompletableDeferred<Result<UpdateCheckResult>>() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requests = AtomicInteger()
        val returned = AtomicInteger()
        val controller = AppUpdateController(
            scope, readPreferences = { UpdatePreferencesState(autoCheckEnabled = false) },
            writeEnabled = {}, writeAttempt = {}, writeSkipped = {},
            fetch = {
                // Intentionally ignore cancellation in the fake transport to exercise the
                // controller's final publication gate, not only a cooperative request cancel.
                val response = responses[requests.getAndIncrement()]
                withContext(NonCancellable) {
                    response.await().also { returned.incrementAndGet() }
                }
            },
            download = { _, _ -> error("No network download in UI fixtures") },
            install = { error("No installer in UI fixtures") },
        )
        return ControllerFixture(controller, scope, responses, requests, returned).also { fixtures += it }
    }

    private data class ControllerFixture(
        val controller: AppUpdateController,
        val scope: CoroutineScope,
        val responses: List<CompletableDeferred<Result<UpdateCheckResult>>>,
        val requests: AtomicInteger,
        val returned: AtomicInteger,
    )

    private class ResumedOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
