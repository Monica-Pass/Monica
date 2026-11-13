package takagi.ru.monica.passkey.hardware

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.SettingsSubpageRow
import takagi.ru.monica.ui.components.SettingsPanelRow

/** Shared by the hardware page and the autofill/passkey settings page. */
@Composable
internal fun HardwarePasskeySetting(inSettingsGroup: Boolean = false) {
    val context = LocalContext.current
    val changes = remember(context) { HardwarePasskeyPreferences.observe(context) }
    val enabled by changes.collectAsState(initial = HardwarePasskeyPreferences.isEnabled(context))
    if (inSettingsGroup) {
        SettingsPanelRow(
            icon = Icons.Default.Usb,
            title = stringResource(R.string.hardware_key_enable),
            subtitle = stringResource(R.string.hardware_key_enable_description),
            checked = enabled,
            onCheckedChange = { HardwarePasskeyPreferences.setEnabled(context, it) },
            modifier = Modifier.testTag("hardware-passkey-enabled"),
        )
        return
    }
    SettingsSubpageRow(
        title = stringResource(R.string.hardware_key_enable),
        subtitle = stringResource(R.string.hardware_key_enable_description),
        icon = Icons.Default.Usb, index = 0, count = 1,
        checked = enabled,
        onCheckedChange = { HardwarePasskeyPreferences.setEnabled(context, it) },
        modifier = Modifier.testTag("hardware-passkey-enabled"),
    )
}
