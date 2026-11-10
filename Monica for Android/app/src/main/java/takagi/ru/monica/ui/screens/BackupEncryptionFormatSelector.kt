package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.BackupEncryptionFormat

@Composable
internal fun BackupEncryptionFormatSelector(
    format: BackupEncryptionFormat,
    onFormatChange: (BackupEncryptionFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_kdf_title), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BackupEncryptionFormat.entries.forEachIndexed { index, option ->
                DatabaseManagementCard(Modifier.fillMaxWidth(), shape = settingsSectionItemShape(index, 2)) {
                    Row(Modifier.fillMaxWidth().testTag("backup_format_${option.name}")
                        .selectable(selected = format == option, role = Role.RadioButton,
                            onClick = { onFormatChange(option) }).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = format == option, onClick = null)
                        Text(stringResource(if (option == BackupEncryptionFormat.COMPATIBLE_V1)
                            R.string.backup_kdf_compatible else R.string.backup_kdf_enhanced),
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Text(stringResource(R.string.backup_kdf_scope), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Visible before opting in too; choosing V2 must not imply that other clients already support it.
        Text(stringResource(R.string.backup_kdf_compatibility_warning), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp).testTag("backup_format_compatibility"),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
