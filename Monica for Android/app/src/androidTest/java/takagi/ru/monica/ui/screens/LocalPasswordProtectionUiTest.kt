package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.security.LocalPasswordAlgorithm
import takagi.ru.monica.security.SecurityManager

@RunWith(AndroidJUnit4::class)
class LocalPasswordProtectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<Fixture>()

    @Before fun prepareWindow() {
        compose.runOnUiThread {
            compose.activity.setShowWhenLocked(true)
            compose.activity.setTurnScreenOn(true)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @After fun finishWorkers() {
        compose.runOnIdle { fixtures.forEach { it.shown = false } }
        compose.waitForIdle()
        fixtures.forEach { fixture ->
            fixture.release.countDown()
            if (fixture.started.count == 0L) assertTrue(fixture.finished.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun changedAlgorithmAndPasswordAreRequiredAndSuccessClearsInput() {
        val f = fixture()
        show(f)
        awaitReady()
        compose.onNodeWithTag("local-protection-update").assertIsNotEnabled()
        compose.onNodeWithTag("local-protection-password").assertDoesNotExist()
        selectArgon()
        compose.onNodeWithTag("local-protection-update").assertIsNotEnabled()
        inputPassword()
        capture("confirmation")
        compose.onNodeWithTag("local-protection-update").performClick()
        awaitTag("local-protection-success")
        compose.onNodeWithTag("local-protection-password").assertDoesNotExist()
        assertEquals(1, f.writes.get())
        assertEquals(LocalPasswordAlgorithm.ARGON2ID, f.actual.get())
        capture("success")
        compose.onNodeWithTag("local-protection-close").performClick()
        compose.runOnIdle { assertEquals(1, f.dismissed) }
    }

    @Test fun unconfirmedChangeRequiresReadingActualAlgorithmAgain() {
        val f = fixture().apply { returnUnconfirmed = true }
        show(f)
        submitArgon()
        awaitTag("local-protection-error")
        compose.onNodeWithTag("local-protection-current").assertDoesNotExist()
        compose.onNodeWithTag("local-protection-password").assertDoesNotExist()
        capture("unconfirmed")
        compose.onNodeWithTag("local-protection-retry").performClick()
        awaitReady()
        compose.onNodeWithTag("local-protection-ARGON2ID").assertIsSelected()
        compose.onNodeWithTag("local-protection-update").assertIsNotEnabled()
        assertEquals(2, f.reads.get())
    }

    @Test fun cancelRevokesDelayedOperationBeforeItsCommit() {
        val f = fixture().apply { blocked = true }
        show(f)
        submitArgon()
        assertTrue(f.started.await(5, TimeUnit.SECONDS))
        compose.onNodeWithTag("local-protection-password").assertDoesNotExist()
        compose.onNodeWithTag("local-protection-cancel").performClick()
        finishRejected(f)
        compose.runOnIdle { assertEquals(1, f.dismissed) }
    }

    @Test fun backgroundClearsInputAndRevokesDelayedOperation() {
        val f = fixture().apply { blocked = true }
        show(f)
        submitArgon()
        assertTrue(f.started.await(5, TimeUnit.SECONDS))
        compose.runOnIdle { f.lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        finishRejected(f)
        compose.runOnIdle { assertEquals(1, f.dismissed) }
    }

    @Test fun lockGenerationHidesDialogAndRejectsDelayedOperation() {
        val f = fixture().apply { blocked = true }
        show(f)
        submitArgon()
        assertTrue(f.started.await(5, TimeUnit.SECONDS))
        compose.runOnIdle { f.generation++ }
        finishRejected(f)
        compose.runOnIdle { assertEquals(1, f.dismissed) }
    }

    @Test fun leavingCompositionRevokesDelayedOperation() {
        val f = fixture().apply { blocked = true }
        show(f)
        submitArgon()
        assertTrue(f.started.await(5, TimeUnit.SECONDS))
        compose.runOnIdle { f.shown = false }
        compose.waitForIdle()
        finishRejected(f)
        compose.runOnIdle { assertEquals(0, f.dismissed) }
    }

    @Test fun timeoutRevokesDelayedWriteAndDoesNotClaimOldAlgorithm() {
        val f = fixture().apply { blocked = true }
        show(f, timeout = 400)
        submitArgon()
        assertTrue(f.started.await(5, TimeUnit.SECONDS))
        awaitTag("local-protection-error")
        compose.onNodeWithTag("local-protection-current").assertDoesNotExist()
        compose.onNodeWithTag("local-protection-password").assertDoesNotExist()
        f.release.countDown()
        assertTrue(f.finished.await(5, TimeUnit.SECONDS))
        assertEquals(0, f.writes.get())
        compose.onNodeWithTag("local-protection-success").assertDoesNotExist()
    }

    @Test fun savedStateDoesNotContainOrRestorePassword() {
        val f = fixture()
        val restoration = StateRestorationTester(compose)
        var registry: SaveableStateRegistry? = null
        restoration.setContent {
            registry = LocalSaveableStateRegistry.current
            Content(f)
        }
        awaitReady()
        selectArgon()
        inputPassword()
        val saved = compose.runOnIdle { checkNotNull(registry).performSave().toString() }
        assertFalse(saved.contains(SYNTHETIC_PASSWORD))
        restoration.emulateSavedInstanceStateRestore()
        awaitReady()
        selectArgon()
        compose.onNodeWithTag("local-protection-password").performScrollTo().assertTextContains("")
        compose.onNodeWithTag("local-protection-update").assertIsNotEnabled()
        assertEquals(0, f.writes.get())
    }

    @Test fun largeDarkRtlOptionsAndWarningsStayReachable() {
        val f = fixture()
        compose.setContent {
            val metrics = context.resources.displayMetrics
            val density = metrics.widthPixels / 320f
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 320
                screenHeightDp = (metrics.heightPixels / density).toInt()
                fontScale = 1.5f
            }
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f),
                LocalConfiguration provides config, LocalLayoutDirection provides LayoutDirection.Rtl) {
                Content(f, dark = true)
            }
        }
        awaitReady()
        selectArgon()
        compose.onNodeWithTag("local-protection-downgrade").performScrollTo().assertIsDisplayed()
        assertTextFits(context.getString(R.string.local_password_protection_downgrade))
        assertTextFits(context.getString(R.string.local_password_protection_argon_description))
        capture("large-dark-rtl")
        inputPassword()
        compose.onNodeWithTag("local-protection-update").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("local-protection-cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, f.dismissed) }
    }

    @Test fun unreadableCurrentProtectionOffersRetryWithoutPasswordField() {
        val f = fixture().apply { readFails = true }
        show(f)
        awaitTag("local-protection-error")
        compose.onNodeWithTag("local-protection-password").assertDoesNotExist()
        compose.onNodeWithTag("local-protection-update").assertDoesNotExist()
        f.readFails = false
        compose.onNodeWithTag("local-protection-retry").performClick()
        awaitReady()
        assertEquals(0, f.writes.get())
    }

    @Test fun resetUsesOneVerifiedOperationAndClearsAllPasswordFields() {
        val f = fixture().apply { resetScreen = true }
        show(f)
        fillReset()
        compose.onNodeWithTag("reset-password-submit").performScrollTo().performClick()
        awaitTag("reset-password-success")
        assertEquals(1, f.resets.get())
        assertEquals(0, f.authorizedResets.get())
        assertEquals(0, f.reads.get())
        for (tag in listOf("current", "new", "confirm"))
            compose.onNodeWithTag("reset-password-$tag").assertDoesNotExist()
        compose.onNodeWithTag("reset-password-success").performClick()
        compose.runOnIdle { assertEquals(1, f.successes) }
    }

    @Test fun authorizedResetRetainsExistingRouteWithoutOldPasswordRead() {
        val f = fixture().apply { resetScreen = true; skipCurrent = true }
        show(f)
        compose.onNodeWithTag("reset-password-current").assertDoesNotExist()
        fillReset(skipCurrent = true)
        compose.onNodeWithTag("reset-password-submit").performScrollTo().performClick()
        awaitTag("reset-password-success")
        assertEquals(0, f.resets.get())
        assertEquals(1, f.authorizedResets.get())
        assertEquals(0, f.reads.get())
    }

    @Test fun resetBackRevokesWorkerBeforeCommit() {
        val f = fixture().apply { resetScreen = true; blocked = true }
        show(f)
        fillReset()
        compose.onNodeWithTag("reset-password-submit").performScrollTo().performClick()
        assertTrue(f.started.await(5, TimeUnit.SECONDS))
        for (tag in listOf("current", "new", "confirm"))
            compose.onNodeWithTag("reset-password-$tag").assertDoesNotExist()
        compose.onNodeWithTag("reset-password-back").performClick()
        f.release.countDown()
        assertTrue(f.finished.await(5, TimeUnit.SECONDS))
        assertEquals(0, f.writes.get())
        compose.runOnIdle { assertEquals(1, f.dismissed); assertEquals(0, f.successes) }
    }

    @Test fun resetBackgroundRevokesWorkerBeforeCommit() {
        val f = fixture().apply { resetScreen = true; blocked = true }
        show(f)
        fillReset()
        compose.onNodeWithTag("reset-password-submit").performScrollTo().performClick()
        assertTrue(f.started.await(5, TimeUnit.SECONDS))
        compose.runOnIdle { f.lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        f.release.countDown()
        assertTrue(f.finished.await(5, TimeUnit.SECONDS))
        assertEquals(0, f.writes.get())
        compose.onNodeWithTag("reset-password-submit").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, f.dismissed); assertEquals(0, f.successes) }
    }

    @Test fun resetSavedStateContainsNeitherOldNorNewPassword() {
        val f = fixture().apply { resetScreen = true }
        val restoration = StateRestorationTester(compose)
        var registry: SaveableStateRegistry? = null
        restoration.setContent {
            registry = LocalSaveableStateRegistry.current
            Content(f)
        }
        fillReset()
        val saved = compose.runOnIdle { checkNotNull(registry).performSave().toString() }
        assertFalse(saved.contains(SYNTHETIC_PASSWORD))
        assertFalse(saved.contains(REPLACEMENT_PASSWORD))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("reset-password-submit").performScrollTo().assertIsNotEnabled()
        assertEquals(0, f.writes.get())
    }

    private fun fillReset(skipCurrent: Boolean = false) {
        if (!skipCurrent) compose.onNodeWithTag("reset-password-current")
            .performScrollTo().performTextInput(SYNTHETIC_PASSWORD)
        compose.onNodeWithTag("reset-password-new").performScrollTo().performTextInput(REPLACEMENT_PASSWORD)
        compose.onNodeWithTag("reset-password-confirm").performScrollTo().performTextInput(REPLACEMENT_PASSWORD)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
    }

    private fun fixture() = Fixture().also { f ->
        compose.runOnUiThread { f.lifecycle = TestLifecycle() }
        fixtures += f
    }

    private fun show(f: Fixture, timeout: Long = 15_000) {
        compose.setContent { Content(f, timeout) }
    }

    @Composable private fun Content(f: Fixture, timeout: Long = 15_000, dark: Boolean = false) {
        CompositionLocalProvider(LocalLifecycleOwner provides f.lifecycle) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else MaterialTheme.colorScheme) {
                if (f.shown && f.resetScreen) ResetPasswordScreenContent(
                    generation = f.generation, isCurrent = { it == f.generation },
                    resetPassword = f::resetPassword, authorizedReset = f::authorizedReset,
                    onNavigateBack = { f.dismissed++; f.shown = false },
                    onResetSuccess = { f.successes++; f.shown = false }, skipCurrentPassword = f.skipCurrent,
                )
                else if (f.shown) LocalPasswordProtectionDialogContent(
                    generation = f.generation, isCurrent = { it == f.generation },
                    readAlgorithm = f::read, changeAlgorithm = f::change,
                    onDismiss = { f.dismissed++; f.shown = false }, operationTimeoutMillis = timeout,
                )
            }
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitReady() = awaitTag("local-protection-current")
    private fun selectArgon() {
        compose.onNodeWithTag("local-protection-ARGON2ID").performScrollTo().performClick().assertIsSelected()
    }
    private fun inputPassword() {
        compose.onNodeWithTag("local-protection-password").performScrollTo().performTextInput(SYNTHETIC_PASSWORD)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
    }
    private fun submitArgon() {
        awaitReady(); selectArgon(); inputPassword()
        compose.onNodeWithTag("local-protection-update").performClick()
    }
    private fun finishRejected(f: Fixture) {
        compose.onNodeWithTag("local-protection-dialog").assertDoesNotExist()
        f.release.countDown()
        assertTrue(f.finished.await(5, TimeUnit.SECONDS))
        assertEquals(0, f.writes.get())
        assertNotNull(f.attempt.get())
        assertTrue(runCatching { f.attempt.get()!!.ensureCurrent() }.isFailure)
        compose.onNodeWithTag("local-protection-success").assertDoesNotExist()
    }

    private fun assertTextFits(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(1.5f, layout.layoutInput.density.fontScale, 0f)
        assertFalse(layout.didOverflowHeight)
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        val size = node.fetchSemanticsNode().size
        assertEquals(size.width, layout.size.width)
        assertEquals(size.height, layout.size.height)
        repeat(layout.lineCount) { line ->
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineLeft(line) >= -0.5f)
            assertTrue(layout.getLineRight(line) <= size.width + 0.5f)
            assertTrue(layout.getLineBottom(line) <= size.height + 0.5f)
        }
    }

    private fun capture(name: String) {
        if (name !in setOf("confirmation", "large-dark-rtl")) return
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val directory = File(context.filesDir, "local-password-kdf-327/ui").apply { check(isDirectory || mkdirs()) }
            File(directory, "local-$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class Fixture {
        var shown by mutableStateOf(true)
        var generation by mutableStateOf(0L)
        var dismissed = 0
        var successes = 0
        var resetScreen = false
        var skipCurrent = false
        lateinit var lifecycle: TestLifecycle
        @Volatile var blocked = false
        @Volatile var readFails = false
        @Volatile var returnUnconfirmed = false
        val actual = AtomicReference(LocalPasswordAlgorithm.PBKDF2_SHA256)
        val attempt = AtomicReference<SecurityManager.LocalPasswordChangeAttempt?>()
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val resets = AtomicInteger()
        val authorizedResets = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        fun read(): LocalPasswordAlgorithm {
            check(Looper.myLooper() != Looper.getMainLooper())
            reads.incrementAndGet()
            check(!readFails)
            return actual.get()
        }
        fun change(password: String, algorithm: LocalPasswordAlgorithm,
            changeAttempt: SecurityManager.LocalPasswordChangeAttempt, ensureActive: () -> Unit): Boolean {
            try {
                check(Looper.myLooper() != Looper.getMainLooper())
                check(password == SYNTHETIC_PASSWORD)
                attempt.set(changeAttempt)
                started.countDown()
                if (blocked) check(release.await(5, TimeUnit.SECONDS))
                ensureActive()
                changeAttempt.ensureCurrent()
                actual.set(algorithm)
                writes.incrementAndGet()
                return !returnUnconfirmed
            } finally { finished.countDown() }
        }
        fun resetPassword(old: String, replacement: String, changeAttempt: SecurityManager.LocalPasswordChangeAttempt,
            ensureActive: () -> Unit): Boolean {
            check(old == SYNTHETIC_PASSWORD)
            check(replacement == REPLACEMENT_PASSWORD)
            resets.incrementAndGet()
            return change(old, LocalPasswordAlgorithm.PBKDF2_SHA256, changeAttempt, ensureActive)
        }
        fun authorizedReset(replacement: String, changeAttempt: SecurityManager.LocalPasswordChangeAttempt,
            ensureActive: () -> Unit): Boolean {
            check(replacement == REPLACEMENT_PASSWORD)
            authorizedResets.incrementAndGet()
            return change(SYNTHETIC_PASSWORD, LocalPasswordAlgorithm.PBKDF2_SHA256, changeAttempt, ensureActive)
        }
    }

    companion object {
        private const val SYNTHETIC_PASSWORD = "Synthetic327Password!"
        private const val REPLACEMENT_PASSWORD = "Replacement327Password!"
    }
}
