package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.BackupPreferences

@Composable
fun ZipBackupOptionsContent(
    backupPreferences: BackupPreferences,
    onBackupPreferencesChange: (BackupPreferences) -> Unit,
    localKeePassCount: Int,
    isWebDavConfigured: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.selective_backup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        )

        ExportContentCheckbox(
            label = stringResource(R.string.backup_content_passwords),
            checked = backupPreferences.includePasswords,
            onCheckedChange = { onBackupPreferencesChange(backupPreferences.copy(includePasswords = it)) }
        )
        ExportContentCheckbox(
            label = stringResource(R.string.backup_content_authenticators),
            checked = backupPreferences.includeAuthenticators,
            onCheckedChange = { onBackupPreferencesChange(backupPreferences.copy(includeAuthenticators = it)) }
        )
        ExportContentCheckbox(
            label = stringResource(R.string.backup_content_wallet),
            checked = backupPreferences.includeDocuments && backupPreferences.includeBankCards,
            onCheckedChange = {
                onBackupPreferencesChange(
                    backupPreferences.copy(
                        includeDocuments = it,
                        includeBankCards = it
                    )
                )
            }
        )
        ExportContentCheckbox(
            label = stringResource(R.string.backup_content_notes),
            checked = backupPreferences.includeNotes,
            onCheckedChange = { onBackupPreferencesChange(backupPreferences.copy(includeNotes = it)) }
        )
        ExportContentCheckbox(
            label = stringResource(R.string.backup_content_images),
            checked = backupPreferences.includeImages,
            onCheckedChange = { onBackupPreferencesChange(backupPreferences.copy(includeImages = it)) }
        )
        ExportContentCheckbox(
            label = stringResource(R.string.backup_content_generator_history),
            checked = backupPreferences.includeGeneratorHistory,
            onCheckedChange = { onBackupPreferencesChange(backupPreferences.copy(includeGeneratorHistory = it)) }
        )
        ExportContentCheckbox(
            label = stringResource(R.string.backup_content_trash),
            checked = backupPreferences.includeTrash,
            onCheckedChange = { onBackupPreferencesChange(backupPreferences.copy(includeTrash = it)) }
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
        )
        ExportContentCheckbox(
            label = if (localKeePassCount > 0)
                stringResource(R.string.backup_content_local_keepass) + " ($localKeePassCount)"
            else
                stringResource(R.string.backup_content_local_keepass),
            checked = backupPreferences.includeLocalKeePass,
            onCheckedChange = { onBackupPreferencesChange(backupPreferences.copy(includeLocalKeePass = it)) },
            enabled = localKeePassCount > 0
        )
        if (localKeePassCount == 0) {
            Text(
                text = stringResource(R.string.backup_content_local_keepass_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 32.dp)
            )
        } else {
            Text(
                text = stringResource(R.string.backup_content_local_keepass_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 32.dp)
            )
        }

        if (isWebDavConfigured) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )
            ExportContentCheckbox(
                label = stringResource(R.string.backup_content_webdav_config),
                checked = backupPreferences.includeWebDavConfig,
                onCheckedChange = { onBackupPreferencesChange(backupPreferences.copy(includeWebDavConfig = it)) }
            )
            Text(
                text = stringResource(R.string.backup_content_webdav_config_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 32.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    onBackupPreferencesChange(
                        BackupPreferences(
                            includePasswords = true,
                            includeAuthenticators = true,
                            includeDocuments = true,
                            includeBankCards = true,
                            includeNotes = true,
                            includeGeneratorHistory = true,
                            includeImages = true,
                            includeTrash = true
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.select_all), style = MaterialTheme.typography.labelMedium)
            }

            FilledTonalButton(
                onClick = {
                    onBackupPreferencesChange(
                        BackupPreferences(
                            includePasswords = false,
                            includeAuthenticators = false,
                            includeDocuments = false,
                            includeBankCards = false,
                            includeNotes = false,
                            includeGeneratorHistory = false,
                            includeImages = false,
                            includeTrash = false
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.deselect_all), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
