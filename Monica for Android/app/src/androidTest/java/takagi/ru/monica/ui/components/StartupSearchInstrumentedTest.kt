package takagi.ru.monica.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StartupSearchInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var enabled by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private var tab by mutableStateOf("vault")
    private var expanded by mutableStateOf(false)
    private var expansionCount = 0

    // The bare test activity is not edge-to-edge, so read the IME from the window itself.
    private fun imeShown() = compose.runOnUiThread {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun show() {
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        compose.setContent {
            MaterialTheme {
                StartupSearchProvider(enabled, loaded, tab) {
                    Column(Modifier.statusBarsPadding()) {
                        ExpressiveTopBar("Vault", "", {}, expanded, {
                            expanded = it
                            if (it) expansionCount++
                        })
                        Text("Page: $tab")
                    }
                }
            }
        }
    }

    @Test fun waitsForSettingsThenFocusesAndShowsKeyboardOnce() {
        enabled = true
        show()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle { loaded = true }
        compose.waitUntil(10_000) { expanded && imeShown() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(1, expansionCount)
        compose.runOnIdle { expanded = false }
        compose.waitForIdle()
        compose.runOnIdle { tab = "notes" }
        compose.waitForIdle()
        compose.runOnIdle { tab = "vault" }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(1, expansionCount)
    }

    @Test fun disabledStartupDoesNotArmWhenSettingChangesLater() {
        loaded = true
        show()
        compose.waitForIdle()
        compose.runOnIdle { enabled = true }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(0, expansionCount)
    }

    @Test fun persistedEnableAndDefaultTabLoadedTogetherTargetTheFinalPage() {
        show()
        compose.runOnIdle {
            enabled = true
            tab = "authenticator"
            loaded = true
        }
        compose.waitUntil(10_000) { expanded }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(1, expansionCount)
    }
}
