package takagi.ru.monica.passkey.hardware

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasskeyRegistrationTargetPicker(
    presence: HardwareKeyPresence, hardwareSelected: Boolean, busy: Boolean,
    onSoftware: () -> Unit, onHardware: () -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hardware_key_save_location), Modifier.padding(8.dp), style = MaterialTheme.typography.headlineSmall)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    ListItem(headlineContent = { Text(stringResource(R.string.hardware_key_software_location)) },
                        supportingContent = { Text(stringResource(R.string.hardware_key_software_hint)) },
                        leadingContent = { Icon(Icons.Default.Folder, null) }, trailingContent = { RadioButton(!hardwareSelected, null, enabled = !busy) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.testTag("passkey-target-software").selectable(!hardwareSelected, enabled = !busy, role = Role.RadioButton, onClick = onSoftware))
                    if (presence.available || hardwareSelected) ListItem(headlineContent = { Text(stringResource(R.string.hardware_key_title)) },
                        supportingContent = { Text(if (presence.available) stringResource(R.string.hardware_key_detected_via, presence.transport)
                            else stringResource(R.string.hardware_key_disconnected)) }, leadingContent = { Icon(Icons.Default.Usb, null) },
                        trailingContent = { RadioButton(hardwareSelected, null, enabled = presence.available && !busy) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.testTag("passkey-target-hardware").selectable(hardwareSelected, enabled = presence.available && !busy, role = Role.RadioButton, onClick = onHardware))
                }
            }
            if (!presence.available && !hardwareSelected) Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Usb, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.hardware_key_connect_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
