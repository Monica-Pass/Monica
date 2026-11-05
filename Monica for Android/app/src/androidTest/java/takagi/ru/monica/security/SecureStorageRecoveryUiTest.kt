package takagi.ru.monica.security

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import takagi.ru.monica.ui.screens.SecureStorageRecoveryScreen
import takagi.ru.monica.ui.theme.MonicaTheme

class SecureStorageRecoveryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun passwordRecoveryHandlesFailureWithoutExposingPasswordOrRunningAutomatically() {
        var calls = 0
        compose.setContent {
            MonicaTheme {
                SecureStorageRecoveryScreen({}, {}, {}, onRecover = {
                    calls++
                    throw java.io.IOException("Synthetic failed recovery")
                })
            }
        }
        compose.onNodeWithTag("secure_startup_recover").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput("synthetic-password")
        compose.onNodeWithTag("secure_startup_recover").performClick()
        compose.onNodeWithTag("secure_startup_recovery_confirm").performClick()
        compose.onNodeWithTag("secure_startup_recovery_error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("secure_startup_recover").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "local-recovery-error.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun blockedScreenKeepsActionsReachableWithLargeTextAndDoesNotRunThemAutomatically() {
        var night by mutableStateOf(false)
        var retries = 0
        var copies = 0
        var exits = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MonicaTheme(darkTheme = night) {
                    Box(Modifier.fillMaxSize().testTag("secure_recovery_frame")) {
                        SecureStorageRecoveryScreen({ retries++ }, { copies++ }, { exits++ })
                    }
                }
            }
        }
        for (dark in listOf(false, true)) {
            compose.runOnIdle { night = dark }
            compose.onNodeWithTag("secure_startup_blocked").assertIsDisplayed()
            compose.onNodeWithTag("secure_startup_retry").assertIsDisplayed()
            compose.onNodeWithTag("secure_startup_diagnostic").assertIsDisplayed()
            val bitmap = compose.onNodeWithTag("secure_recovery_frame").captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "secure-startup-${if (dark) "dark" else "light"}.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.runOnIdle { assertEquals(0, retries + copies + exits) }
        compose.onNodeWithTag("secure_startup_retry").performClick()
        compose.onNodeWithTag("secure_startup_diagnostic").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, copies); assertEquals(0, exits) }
    }
    @Test fun recoveryNeedsSecondConfirmationAndCancelDoesNotCallRecovery() {
        var calls = 0
        compose.setContent {
            MonicaTheme { SecureStorageRecoveryScreen({}, {}, {}, onRecover = { calls++; true }) }
        }
        compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput("synthetic-confirm-password")
        compose.onNodeWithTag("secure_startup_recover").performClick()
        compose.runOnIdle { assertEquals("First click must not recreate keys", 0, calls) }
        compose.onNodeWithTag("secure_startup_recovery_confirm").assertIsDisplayed()
        compose.onNodeWithTag("secure_startup_recovery_cancel").performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("secure_startup_recover").assertIsNotEnabled()
    }

    @Test fun confirmationIsDiscardedWhenRecoveryPageLeavesComposition() {
        var visible by mutableStateOf(true)
        var calls = 0
        compose.setContent {
            MonicaTheme { if (visible) SecureStorageRecoveryScreen({}, {}, {}, onRecover = { calls++; true }) }
        }
        compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput("synthetic-confirm-password")
        compose.onNodeWithTag("secure_startup_recover").performClick()
        compose.runOnIdle { assertEquals("Consent is still pending", 0, calls); visible = false }
        compose.runOnIdle { visible = true }
        compose.onNodeWithTag("secure_startup_recovery_confirm").assertDoesNotExist()
        compose.onNodeWithTag("secure_startup_recover").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }



    @Test fun explicitConfirmationExecutesOnceAndClearsPassword() {
        var calls = 0
        val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
        compose.setContent {
            MonicaTheme { SecureStorageRecoveryScreen({}, {}, {}, onRecover = { calls++; result.await() }) }
        }
        compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput("synthetic-confirm-password")
        compose.onNodeWithTag("secure_startup_recover").performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("secure_startup_recovery_confirm").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithTag("secure_startup_recovery_confirm").assertDoesNotExist()
        compose.onNodeWithTag("secure_startup_recover").assertIsNotEnabled()
        result.complete(false)
        compose.onNodeWithTag("secure_startup_recovery_error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("secure_startup_recover").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun systemBackDismissesConfirmationWithoutRecovery() {
        var calls = 0
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val diagnostic = StringBuffer()
        var stage = "opening"
        fun record(label: String): RecoveryBackWindows = recoveryBackWindows().also {
            diagnostic.append("stage=$label; uptime=${SystemClock.uptimeMillis()}\n${it.description}")
        }
        compose.setContent {
            MonicaTheme { SecureStorageRecoveryScreen({}, {}, {}, onRecover = { calls++; true }) }
        }
        try {
            compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput("synthetic-back-password")
            record("password-entered")
            compose.onNodeWithTag("secure_startup_recover").performClick()
            compose.onNodeWithTag("secure_startup_recovery_confirm").assertIsDisplayed()
            stage = "await-dialog-focus-and-ime-hidden"
            // Semantics visibility does not mean the separate Dialog Window owns input.
            // Observe production clearFocus()/keyboard.hide(); do not hide IME with Back.
            if (Build.VERSION.SDK_INT >= 29) {
                var stableSince = 0L
                var previous = ""
                compose.waitUntil(5_000) {
                    val windows = recoveryBackWindows()
                    if (windows.description != previous) {
                        diagnostic.append("stage=$stage; uptime=${SystemClock.uptimeMillis()}\n${windows.description}")
                        previous = windows.description
                    }
                    if (windows.ready) {
                        if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                        SystemClock.uptimeMillis() - stableSince >= 200
                    } else {
                        stableSince = 0L
                        false
                    }
                }
            }
            stage = "single-system-back"
            record("before-back")
            // One system key only, through Android's normal focused-window dispatch.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            record("after-back")
            stage = "await-confirmation-dismissal"
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("secure_startup_recovery_confirm")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
            }
            compose.onNodeWithTag("secure_startup_recovery_confirm").assertDoesNotExist()
            compose.onNodeWithTag("secure_startup_recover").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(0, calls) }
            stage = "passed"
        } catch (failure: Throwable) {
            runCatching { record("failed-$stage") }
            // No node text, password, window title, or user data is included in this evidence.
            runCatching {
                instrumentation.uiAutomation.windows.forEach { window ->
                    diagnostic.append("accessibility-window type=${window.type}; focused=${window.isFocused}; " +
                        "active=${window.isActive}; layer=${window.layer}\n")
                    window.recycle()
                }
            }
            throw AssertionError("Recovery Back failed at $stage.\n$diagnostic").apply { initCause(failure) }
        } finally {
            runCatching { record("finished-$stage") }
            File(instrumentation.targetContext.filesDir, "secure-recovery-back-diagnostic.txt")
                .writeText(diagnostic.toString())
        }
    }

    private data class RecoveryBackWindows(val ready: Boolean, val description: String)

    private fun recoveryBackWindows(): RecoveryBackWindows {
        if (Build.VERSION.SDK_INT < 29) {
            // Keep the same single-Back/cancellation assertions on older Android versions.
            return RecoveryBackWindows(false, "WindowInspector unavailable below API29\n")
        }
        var result: RecoveryBackWindows? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val roots = WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.isShown }
            val dialogs = roots.mapNotNull(::recoveryDialogWindow).distinct()
            val dialog = dialogs.singleOrNull()
            val imeHidden = roots.isNotEmpty() && roots.all {
                ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
            val ready = dialog != null && dialog.decorView.hasWindowFocus() && imeHidden &&
                dialog.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
            result = RecoveryBackWindows(ready, buildString {
                appendLine("composeDialogs=${dialogs.size}; imeKnownHidden=$imeHidden; ready=$ready")
                roots.forEachIndexed { index, root ->
                    val parameters = root.layoutParams as? WindowManager.LayoutParams
                    val ime = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime())
                    appendLine("root=$index; dialog=${recoveryDialogWindow(root) != null}; " +
                        "class=${root.javaClass.simpleName}; focus=${root.hasWindowFocus()}; viewFocus=${root.hasFocus()}; " +
                        "visibility=${root.windowVisibility}; imeVisible=$ime; type=${parameters?.type}; " +
                        "flags=${parameters?.flags}; softInputMode=${parameters?.softInputMode}; size=${root.width}x${root.height}")
                }
            })
        }
        return checkNotNull(result)
    }

    private fun recoveryDialogWindow(view: View): Window? {
        if (view is DialogWindowProvider) return view.window
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                recoveryDialogWindow(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    @Test fun confirmationDialogRemainsReadableWithLargeTextAndBothThemes() {
        var night by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MonicaTheme(darkTheme = night) { SecureStorageRecoveryScreen({}, {}, {}, onRecover = { false }) }
            }
        }
        compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput("synthetic-render-password")
        compose.onNodeWithTag("secure_startup_recover").performClick()
        for (dark in listOf(false, true)) {
            compose.runOnIdle { night = dark }
            compose.onNodeWithTag("secure_startup_recovery_confirm").assertIsDisplayed()
            compose.onNodeWithTag("secure_startup_recovery_cancel").assertIsDisplayed()
            val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "secure-recovery-confirm-${if (dark) "dark" else "light"}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }


    private class RecoveryLifecycle : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry(this)
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @Test fun leavingForegroundClearsPendingConfirmation() {
        val owner = RecoveryLifecycle()
        var calls = 0
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                MonicaTheme { SecureStorageRecoveryScreen({}, {}, {}, onRecover = { calls++; true }) }
            }
        }
        compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput("synthetic-foreground-password")
        compose.onNodeWithTag("secure_startup_recover").performClick()
        compose.onNodeWithTag("secure_startup_recovery_confirm").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.onNodeWithTag("secure_startup_recovery_confirm").assertDoesNotExist()
        compose.onNodeWithTag("secure_startup_recover").assertIsNotEnabled()
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED; assertEquals(0, calls) }
        compose.onNodeWithTag("secure_startup_recovery_confirm").assertDoesNotExist()
    }

    @Test fun leavingForegroundCancelsConfirmedRecoveryAndDoesNotRestartIt() {
        val owner = RecoveryLifecycle()
        val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var calls = 0
        var cancelled = false
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                MonicaTheme {
                    SecureStorageRecoveryScreen({}, {}, {}, onRecover = {
                        calls++
                        try { result.await() }
                        catch (error: kotlinx.coroutines.CancellationException) { cancelled = true; throw error }
                    })
                }
            }
        }
        compose.onNodeWithTag("secure_startup_password").performScrollTo().performTextInput("synthetic-foreground-password")
        compose.onNodeWithTag("secure_startup_recover").performClick()
        compose.onNodeWithTag("secure_startup_recovery_confirm").performClick()
        compose.runOnIdle { assertEquals(1, calls); owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.runOnIdle { org.junit.Assert.assertTrue(cancelled); owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        result.complete(true)
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithTag("secure_startup_recovery_confirm").assertDoesNotExist()
        compose.onNodeWithTag("secure_startup_recover").assertIsNotEnabled()
    }

}
