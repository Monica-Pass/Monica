package takagi.ru.monica.passkey

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.ui.components.*
import takagi.ru.monica.ui.theme.MonicaTheme

class PasskeyTransferPreflightTest {
    @get:Rule val compose = createComposeRule()
    private val entry = PasskeyEntry(credentialId="fixture", rpId="example.invalid", rpName="Example",
        userId="user", userName="Eligible account", userDisplayName="Eligible", publicKey="", privateKeyAlias="")
    @Test fun cancelDoesNotExecuteAndSkipOnlyProcessesEligibleRecords() {
        var visible by mutableStateOf(true)
        var copied = emptyList<PasskeyEntry>()
        val plan = PasskeyTransferPlan(listOf(entry), listOf(PasskeyMoveIssue("Unavailable account", "example.invalid", PasskeyMoveIssueReason.KEY_UNAVAILABLE)))
        compose.setContent { MonicaTheme { if (visible) PasskeyTransferPreflightDialog(plan, UnifiedMoveAction.COPY,
            onCancel = { visible = false }, onSkip = { copied = plan.eligible; visible = false }) } }
        compose.onNodeWithTag("passkey_transfer_cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(copied.isEmpty()); visible = true }
        compose.onNodeWithText("Unavailable account").assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("passkey_transfer_preflight").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "passkey-transfer-preflight.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        compose.onNodeWithTag("passkey_transfer_skip").performClick()
        compose.runOnIdle { assertEquals(listOf(entry), copied) }
    }
    @Test fun allBlockedDisablesSkip() {
        compose.setContent { MonicaTheme { PasskeyTransferPreflightDialog(PasskeyTransferPlan(emptyList(),
            listOf(PasskeyMoveIssue("Device-bound account", "example.invalid", PasskeyMoveIssueReason.TRANSFER_RESTRICTED))),
            UnifiedMoveAction.MOVE, {}, {}) } }
        compose.onNodeWithTag("passkey_transfer_skip").assertIsNotEnabled()
        compose.onNodeWithTag("passkey_transfer_cancel").assertIsDisplayed()
    }
}
