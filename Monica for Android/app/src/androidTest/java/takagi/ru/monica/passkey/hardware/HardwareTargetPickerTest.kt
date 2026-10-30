package takagi.ru.monica.passkey.hardware

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.theme.MonicaTheme

class HardwareTargetPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noDeviceOffersOnlySoftwareLocation() {
        compose.setContent { MonicaTheme { PasskeyRegistrationTargetPicker(HardwareKeyPresence(), false, false, {}, {}, {}) } }
        compose.onNodeWithTag("passkey-target-software").assertIsEnabled()
        compose.onNodeWithTag("passkey-target-hardware").assertDoesNotExist()
    }

    @Test fun removalKeepsChosenHardwareDisabledAndReconnectionRestoresIt() {
        val presence = mutableStateOf(HardwareKeyPresence(usbCount = 1))
        val selected = mutableStateOf(false)
        var hardwareClicks = 0
        compose.setContent { MonicaTheme { PasskeyRegistrationTargetPicker(presence.value, selected.value, false,
            { selected.value = false }, { selected.value = true; hardwareClicks++ }, {}) } }
        compose.onNodeWithTag("passkey-target-hardware").performClick()
        compose.runOnIdle { presence.value = HardwareKeyPresence() }
        compose.onNodeWithTag("passkey-target-hardware").assertExists().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, hardwareClicks); presence.value = HardwareKeyPresence(nfcDetected = true) }
        compose.onNodeWithTag("passkey-target-hardware").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, hardwareClicks) }
        compose.onNodeWithTag("passkey-target-software").performClick()
        compose.runOnIdle { presence.value = HardwareKeyPresence() }
        compose.onNodeWithTag("passkey-target-hardware").assertDoesNotExist()
    }

    @Test fun pendingRegistrationDisablesBothLocations() {
        var calls = 0
        compose.setContent { MonicaTheme { PasskeyRegistrationTargetPicker(HardwareKeyPresence(usbCount = 1), true, true,
            { calls++ }, { calls++ }, {}) } }
        compose.onNodeWithTag("passkey-target-software").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("passkey-target-hardware").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
