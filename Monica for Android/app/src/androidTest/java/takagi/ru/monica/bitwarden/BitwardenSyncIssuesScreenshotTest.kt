package takagi.ru.monica.bitwarden

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.service.BitwardenSyncIssueReport
import takagi.ru.monica.bitwarden.ui.BitwardenSyncIssuesSheet
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.ScreenshotProtectionUtil
import takagi.ru.monica.utils.SettingsManager

/** Checks the actual separate dialog window, not a Compose-only screenshot of its content. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BitwardenSyncIssuesScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val settings by lazy { SettingsManager(instrumentation.targetContext) }
    private var previousProtection = true
    private var previousParentFlags = 0
    private var visible by mutableStateOf(false)

    @Before fun rememberSettingAndWindow() {
        previousProtection = runBlocking { settings.settingsFlow.first().screenshotProtectionEnabled }
        compose.runOnIdle { previousParentFlags = compose.activity.window.attributes.flags }
    }

    @After fun restoreSettingAndWindow() {
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        runBlocking { settings.updateScreenshotProtectionEnabled(previousProtection) }
        compose.runOnIdle {
            compose.activity.window.setFlags(previousParentFlags and secureFlag, secureFlag)
        }
    }

    @Test fun parentClearAndDisabledSettingReleaseAlreadyMountedSheet() {
        setProtection(true)
        showSheet()
        val dialog = awaitWindowProtection(true, true, "mounted-protected")

        // Deliberately update the real parent window after the sheet has mounted.
        // The old sheet sampled that window only when its parameters last changed.
        setProtection(false)
        assertSame(dialog, awaitWindowProtection(false, false, "parent-cleared-setting-disabled"))
        dismissAndAssertParent(false)
    }

    @Test fun settingChangesUpdateSameDialogFalseTrueFalse() {
        setProtection(false)
        showSheet()
        val dialog = awaitWindowProtection(false, false, "initial-unprotected")

        setProtection(true)
        assertSame(dialog, awaitWindowProtection(true, true, "setting-enabled"))
        setProtection(false)
        assertSame(dialog, awaitWindowProtection(false, false, "setting-disabled-again"))
        dismissAndAssertParent(false)
    }

    @Test fun disabledSettingCannotBypassForcedParentProtection() {
        setProtection(false)
        compose.runOnIdle { ScreenshotProtectionUtil.acquireForcedProtection(compose.activity.window) }
        try {
            showSheet()
            val dialog = awaitWindowProtection(true, true, "forced-parent-setting-disabled")
            // The ordinary setting writer must preserve the independently owned protection.
            compose.runOnIdle { ScreenshotProtectionUtil.disableScreenshotProtection(compose.activity) }
            assertSame(dialog, awaitWindowProtection(true, true, "forced-owner-retained"))
            // An initially protected default must not turn off after the setting's async emission.
            val deadline = SystemClock.elapsedRealtime() + 500
            do {
                val snapshot = readWindows()
                assertTrue("Forced protection changed: ${snapshot.describe()}", snapshot.parentSecure)
                assertSame(dialog, snapshot.dialogs.single())
                assertTrue("The sheet must remain protected for its forced parent", isSecure(dialog))
                SystemClock.sleep(20)
            } while (SystemClock.elapsedRealtime() < deadline)
            dismissAndAssertParent(true)
        } finally {
            compose.runOnIdle { ScreenshotProtectionUtil.releaseForcedProtection(compose.activity.window) }
        }
        compose.runOnIdle {
            assertFalse("Releasing the only forced owner restores the disabled setting", isSecure(compose.activity.window))
        }
    }

    @Test fun disabledSettingIsIndependentOfAStaleOrdinaryParentFlag() {
        setProtection(false)
        compose.runOnIdle { compose.activity.window.addFlags(secureFlag) }
        showSheet()
        awaitWindowProtection(true, false, "disabled-setting-stale-parent")
        dismissAndAssertParent(true)
    }

    @Test fun forcedProtectionAcquiredAndReleasedWhileOpenUpdatesTheSameSheet() {
        setProtection(false)
        showSheet()
        val dialog = awaitWindowProtection(false, false, "before-forced-owner")
        compose.runOnIdle { ScreenshotProtectionUtil.acquireForcedProtection(compose.activity.window) }
        try {
            assertSame(dialog, awaitWindowProtection(true, true, "forced-owner-acquired"))
        } finally {
            compose.runOnIdle { ScreenshotProtectionUtil.releaseForcedProtection(compose.activity.window) }
        }
        assertSame(dialog, awaitWindowProtection(false, false, "forced-owner-released"))
        // Window attributes can update before SurfaceFlinger applies the secure-surface change.
        // Check the actual native capture as well; an all-black bitmap is not screenshot success.
        val bitmap = awaitVisibleNativeScreenshot()
        try {
            val file = java.io.File(instrumentation.targetContext.filesDir, "sync-issues-unprotected.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        dismissAndAssertParent(false)
    }

    private fun awaitVisibleNativeScreenshot(): android.graphics.Bitmap {
        val start = SystemClock.elapsedRealtime()
        var attempts = 0
        var visibleSamples = 0
        do {
            compose.onNodeWithTag("bitwarden_sync_issues").assertIsDisplayed()
            val windows = readWindows()
            assertFalse(windows.parentSecure)
            assertFalse(isSecure(windows.dialogs.single()))
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            attempts++
            visibleSamples = 0
            // Ignore system bars. A protected all-black surface must not pass on a clock/icon.
            for (y in 1..20) for (x in 1..20) {
                val color = bitmap.getPixel(bitmap.width * x / 21, bitmap.height * y / 21)
                if (android.graphics.Color.red(color) > 24 || android.graphics.Color.green(color) > 24 ||
                    android.graphics.Color.blue(color) > 24) visibleSamples++
            }
            if (visibleSamples > 40) {
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "SYNC_ISSUES_NATIVE_CAPTURE attempts=$attempts visibleSamples=$visibleSamples/400 " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - start}\n")
                })
                return bitmap
            }
            bitmap.recycle()
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() - start < 5_000)
        error("The unprotected sheet remained black in native screenshots: attempts=$attempts samples=$visibleSamples/400")
    }

    private fun setProtection(enabled: Boolean) {
        compose.runOnIdle {
            if (enabled) ScreenshotProtectionUtil.enableScreenshotProtection(compose.activity)
            else ScreenshotProtectionUtil.disableScreenshotProtection(compose.activity)
        }
        runBlocking {
            settings.updateScreenshotProtectionEnabled(enabled)
            withTimeout(5_000) { settings.settingsFlow.first { it.screenshotProtectionEnabled == enabled } }
        }
        compose.waitForIdle()
    }

    private fun showSheet() {
        val report = BitwardenSyncIssueReport(startedAt = 1L, running = false)
        visible = true
        compose.setContent {
            MonicaTheme {
                if (visible) BitwardenSyncIssuesSheet(
                    account = "synthetic-screenshot@example.invalid",
                    report = report,
                    onDismiss = { visible = false },
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag("bitwarden_sync_issues").assertIsDisplayed()
        compose.waitForIdle()
    }

    private data class Windows(val parentSecure: Boolean, val dialogs: List<Window>) {
        fun describe(): String = "parentSecure=$parentSecure; dialogs=${dialogs.size}; " +
            dialogs.joinToString { "dialogFlags=${it.attributes.flags}; secure=${isSecure(it)}" }
    }

    private fun readWindows(): Windows {
        var snapshot: Windows? = null
        instrumentation.runOnMainSync {
            val dialogs = WindowInspector.getGlobalWindowViews()
                .filter { it.isAttachedToWindow && it.isShown }
                .mapNotNull(::dialogWindow)
                .filter { it !== compose.activity.window }
                .distinct()
            snapshot = Windows(isSecure(compose.activity.window), dialogs)
        }
        return requireNotNull(snapshot)
    }

    private fun awaitWindowProtection(parentSecure: Boolean, sheetSecure: Boolean, phase: String): Window {
        var last = readWindows()
        try {
            compose.waitUntil(5_000) {
                last = readWindows()
                last.parentSecure == parentSecure && last.dialogs.singleOrNull()?.let(::isSecure) == sheetSecure
            }
        } finally {
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "SYNC_ISSUES_SCREENSHOT phase=$phase expectedParent=$parentSecure " +
                    "expectedSheet=$sheetSecure ${last.describe()}\n")
            })
        }
        assertEquals(parentSecure, last.parentSecure)
        return last.dialogs.single().also { assertEquals(sheetSecure, isSecure(it)) }
    }

    private fun dismissAndAssertParent(expectedSecure: Boolean) {
        compose.runOnIdle { visible = false }
        compose.waitUntil(5_000) { readWindows().dialogs.isEmpty() }
        assertEquals("Closing the sheet must not change its parent policy", expectedSecure, readWindows().parentSecure)
    }

    private fun dialogWindow(view: View): Window? {
        if (view is DialogWindowProvider) return view.window
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            dialogWindow(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    companion object {
        private const val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
        private fun isSecure(window: Window): Boolean = window.attributes.flags and secureFlag != 0
    }
}
