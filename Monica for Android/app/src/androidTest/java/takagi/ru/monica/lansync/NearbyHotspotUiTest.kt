package takagi.ru.monica.lansync

import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.screens.NearbySyncActions
import takagi.ru.monica.ui.screens.NearbySyncContent
import takagi.ru.monica.ui.screens.NearbyHotspotInterruptedDialog
import takagi.ru.monica.ui.theme.MonicaTheme

/** Synthetic UI only; no system hotspot is requested. */
class NearbyHotspotUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun lock() { SessionManager.markLocked() }
    private fun text(id: Int) = compose.activity.getString(id)

    @Test fun hotspotRequiresAnExplicitTapAndDismissalCancelsOwnedSession() {
        SessionManager.markUnlocked()
        var starts = 0; var stops = 0
        compose.setContent { MonicaTheme { NearbySyncContent(DeviceSyncState(enabled = false),
            NearbySyncActions(createHotspot = { starts++ }, cancelHotspot = { stops++ })) } }
        compose.onNodeWithText(text(R.string.nearby_hotspot_create)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithText(text(R.string.nearby_hotspot_create)).performClick()
        compose.onNodeWithText(text(R.string.nearby_hotspot_help)).assertIsDisplayed()
        capture("create-light")
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, stops) }
        compose.onNodeWithText(text(R.string.nearby_hotspot_stop)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithText(text(R.string.nearby_hotspot_help)).assertDoesNotExist()
    }

    @Test fun hotspotFailuresOfferRetryWithoutClaimingReadyOrExposingPassword() {
        SessionManager.markUnlocked()
        val state = mutableStateOf(NearbyHotspotState(error = "permission_denied"))
        var starts = 0
        compose.setContent { MonicaTheme(darkTheme = true) { NearbySyncContent(DeviceSyncState(enabled = false),
            NearbySyncActions(createHotspot = { starts++ }), hotspot = state.value) } }
        compose.onNodeWithText(text(R.string.nearby_hotspot_create)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.nearby_hotspot_permissions)).assertIsDisplayed()
        capture("failure-dark")
        compose.onNodeWithText(text(R.string.nearby_hotspot_starting)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.nearby_regenerate)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, starts) }
    }

    @Test fun interruptedConnectionOffersManualSettingsAndCanBeDismissed() {
        val visible = mutableStateOf(true)
        var settings = 0
        compose.setContent { MonicaTheme {
            if (visible.value) NearbyHotspotInterruptedDialog(onDismiss = { visible.value = false },
                onOpenWifi = { settings++; visible.value = false })
        } }
        compose.onNodeWithText(text(R.string.nearby_hotspot_interrupted)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nearby_hotspot_open_wifi)).performClick()
        compose.runOnIdle { assertEquals(1, settings); visible.value = true }
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithText(text(R.string.nearby_hotspot_interrupted)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, settings) }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture") != "true") return
        compose.waitForIdle()
        // Only this synthetic test activity may clear secure-window capture protection.
        compose.runOnIdle { compose.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        val runner = InstrumentationRegistry.getInstrumentation()
        runner.waitForIdleSync()
        // Clearing the synthetic window flag can recreate its Surface; wait for
        // that compositor frame rather than recording a blank transition buffer.
        Thread.sleep(500)
        val bitmap = requireNotNull(runner.uiAutomation.takeScreenshot())
        try {
            java.io.File(runner.targetContext.cacheDir,"nearby-hotspot-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        } finally { bitmap.recycle() }
    }
}
