package takagi.ru.monica.ui.haptic

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.LocalHapticFeedbackEnabled
import takagi.ru.monica.ui.components.CustomizationToggle
import takagi.ru.monica.ui.components.SettingsPanelRow
import takagi.ru.monica.ui.components.SettingsSubpageRow
import takagi.ru.monica.ui.screens.Setting
import takagi.ru.monica.ui.screens.SettingsItemWithSwitch
import takagi.ru.monica.ui.theme.MonicaTheme

/** Real Compose input handlers; recording the View request does not measure a physical motor. */
class SwitchHapticsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var checked by mutableStateOf(false)
    private var enabled by mutableStateOf(true)
    private var feedback by mutableStateOf(true)
    private var updates = 0
    private var accepted = true
    private val requests = mutableListOf<Int>()
    private fun change(value: Boolean) { updates++; checked = value }

    private fun show(content: @Composable () -> Unit) {
        val host = object : View(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun performHapticFeedback(feedbackConstant: Int): Boolean {
                requests += feedbackConstant
                return accepted
            }
        }
        compose.setContent {
            Column {
            // Material's ripple must be able to find this recording View's real parent.
            AndroidView(factory = { host }, modifier = Modifier.size(1.dp))
            CompositionLocalProvider(LocalView provides host, LocalHapticFeedbackEnabled provides feedback) {
                MonicaTheme { Column(Modifier.width(360.dp)) { content() } }
            }
            }
        }
    }

    private fun assertTicks(count: Int) = compose.runOnIdle {
        assertEquals(List(count) { HapticFeedbackConstants.CONTEXT_CLICK }, requests)
    }

    @Test fun touchAndAccessibilityToggleOnceWhileExternalUpdatesStaySilent() {
        show { MonicaSwitch(checked, ::change, Modifier.testTag("switch")) }
        assertTicks(0)
        compose.onNodeWithTag("switch").performTouchInput { click() }
        compose.onNodeWithTag("switch").assertIsOn()
        assertTicks(1)
        compose.onNodeWithTag("switch").performClick()
        compose.onNodeWithTag("switch").assertIsOff()
        assertTicks(2)
        compose.runOnIdle { checked = true }
        compose.onNodeWithTag("switch").assertIsOn()
        assertTicks(2)
        compose.runOnIdle { assertEquals(2, updates) }
    }

    @Test fun disabledAndCancelledPressDoNotChangeOrVibrate() {
        enabled = false
        show { MonicaSwitch(checked, ::change, Modifier.testTag("switch"), enabled = enabled) }
        compose.onNodeWithTag("switch").assertIsNotEnabled().performTouchInput { click() }
        assertTicks(0)
        compose.runOnIdle { enabled = true }
        compose.onNodeWithTag("switch").performTouchInput { down(center); cancel() }
        assertTicks(0)
        compose.runOnIdle { assertEquals(0, updates) }
    }

    @Test fun preferenceChangesApplyLiveAndDeclinedHostRequestIsNotRepeated() {
        feedback = false
        show { MonicaSwitch(checked, ::change, Modifier.testTag("switch")) }
        compose.onNodeWithTag("switch").performTouchInput { click() }
        assertTicks(0)
        compose.runOnIdle { feedback = true; accepted = false }
        compose.onNodeWithTag("switch").performTouchInput { click() }
        assertTicks(1)
        compose.runOnIdle { feedback = false }
        compose.onNodeWithTag("switch").performClick()
        assertTicks(1)
        compose.runOnIdle { assertEquals(3, updates) }
    }

    @Test fun rowAndPassiveThumbShareOneInteraction() {
        show {
            Row(Modifier.fillMaxWidth().testTag("row")
                .hapticSwitchToggleable(checked, onValueChange = ::change).padding(16.dp)) {
                Text("Synthetic option", Modifier.weight(1f))
                MonicaSwitch(checked, null, Modifier.testTag("thumb"))
            }
        }
        compose.onNodeWithTag("row").performClick()
        assertTicks(1)
        compose.onNodeWithTag("thumb", useUnmergedTree = true).performTouchInput { click() }
        assertTicks(2)
        compose.runOnIdle { assertEquals(2, updates) }
    }

    @Test fun sharedSettingsRowsAndStandaloneSettingHaveSingleFeedback() {
        show {
            SettingsPanelRow(Icons.Default.Tune, Setting.HIDE_FAB_ON_SCROLL_TITLE,
                checked = checked, onCheckedChange = ::change, modifier = Modifier.testTag("panel"))
            SettingsSubpageRow(Setting.REDUCE_ANIMATIONS, icon = Icons.Default.Tune,
                index = 0, count = 1, checked = checked, onCheckedChange = ::change,
                modifier = Modifier.testTag("subpage"))
            SettingsItemWithSwitch(Icons.Default.Tune, Setting.HAPTIC_FEEDBACK,
                checked = checked, onCheckedChange = ::change)
        }
        compose.onNodeWithTag("panel").performTouchInput { click() }
        assertTicks(1)
        compose.onNodeWithTag("subpage").performClick()
        assertTicks(2)
        compose.onAllNodes(isToggleable()).onLast().performClick()
        assertTicks(3)
        compose.runOnIdle { assertEquals(3, updates) }
    }

    @Test fun existingClickableRowAndActiveChildDoNotBubbleOrDoubleVibrate() {
        show {
            Row(Modifier.fillMaxWidth().testTag("row")
                .clickable(enabled = enabled, onClick = switchClick(checked, enabled, ::change))
                .padding(16.dp)) {
                Text("Synthetic option", Modifier.weight(1f))
                MonicaSwitch(checked, ::change, Modifier.testTag("thumb"), enabled = enabled)
            }
        }
        compose.onNodeWithTag("row").performClick()
        assertTicks(1)
        compose.onNodeWithTag("thumb").performTouchInput { click() }
        assertTicks(2)
        compose.runOnIdle { assertEquals(2, updates); enabled = false }
        compose.onNodeWithTag("thumb").performTouchInput { click() }
        assertTicks(2)
    }

    @Test fun customizationFeedbackPreferenceAcknowledgesEnableAndSilencesDisable() {
        feedback = false
        show {
            CustomizationToggle(Setting.HAPTIC_FEEDBACK, checked = feedback,
                onCheckedChange = { feedback = it })
        }
        compose.onNode(isToggleable()).performTouchInput { click() }
        compose.onNode(isToggleable()).assertIsOn()
        assertTicks(1)
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOff()
        assertTicks(1)
    }

    @Test fun extensionFeedbackPreferenceCanReenableFeedback() {
        feedback = false
        show {
            SettingsPanelRow(Icons.Default.Tune, Setting.HAPTIC_FEEDBACK,
                checked = feedback, onCheckedChange = { feedback = it }, modifier = Modifier.testTag("panel"))
        }
        compose.onNodeWithTag("panel").performClick()
        assertTicks(1)
        compose.onNodeWithTag("panel").performClick()
        assertTicks(1)
    }
}
