package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.ui.screens.DatabaseManagementCard
import takagi.ru.monica.ui.screens.DatabaseManagementPanelShape
import takagi.ru.monica.ui.screens.settingsSectionItemShape

@Composable
private fun ContentTypeSwitch(
    label: String,
    count: Int?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    groupCount: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    val shape = settingsSectionItemShape(index, groupCount)
    Surface(
        modifier = modifier.fillMaxWidth().clip(shape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch,
                interactionSource = null, indication = ripple(color = MaterialTheme.colorScheme.primary),
                onValueChange = onCheckedChange),
        shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
                if (count != null) Text(stringResource(R.string.common_count_items, count),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f))
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

/** Shared full-backup choices. Provider-specific restrictions stay in the caller. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectiveBackupCard(
    preferences: BackupPreferences,
    onPreferencesChange: (BackupPreferences) -> Unit,
    passwordCount: Int,
    authenticatorCount: Int,
    documentCount: Int,
    bankCardCount: Int,
    noteCount: Int,
    trashCount: Int = 0,
    passkeyCount: Int = 0,
    localKeePassCount: Int = 0,
    isWebDavConfigured: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedCount = listOf(
        preferences.includePasswords, preferences.includeAuthenticators,
        preferences.includeDocuments && preferences.includeBankCards, preferences.includePasskeys,
        preferences.includeNotes, preferences.includeImages, preferences.includeTrashAndHistory,
        preferences.includeLocalKeePass,
    ).count { it }
    val extras = buildList {
        if (preferences.includeWebDavConfig && isWebDavConfigured) add("WebDAV")
        if (preferences.includeLocalKeePass && localKeePassCount > 0) add("KeePass")
    }
    val extrasText = if (extras.isNotEmpty()) " (+${extras.joinToString(", ")})" else ""
    Column(modifier.fillMaxWidth()) {
        DatabaseManagementCard(onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().testTag("cloud_backup_content_toggle"),
            shape = DatabaseManagementPanelShape) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Checklist, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.selective_backup_title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.selective_backup_summary, selectedCount, 8) + extrasText,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MonicaExpansionChevron(expanded, stringResource(if (expanded) R.string.collapse else R.string.expand))
            }
        }
        MonicaExpandableContent(expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.selective_backup_description),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = {
                        onPreferencesChange(
                            BackupPreferences(
                                includePasswords = true,
                                includeAuthenticators = true,
                                includeDocuments = true,
                                includeBankCards = true,
                                includePasskeys = passkeyCount > 0,  // ✅ 新增
                                includeNotes = true,
                                includeImages = true,
                                includeTrashAndHistory = true,  // ✅ 新增
                                includeGeneratorHistory = true,
                                includeTimeline = true,
                                includeTrash = true,
                                includeLocalKeePass = localKeePassCount > 0,
                                encryptionFormat = preferences.encryptionFormat
                            )
                        )
                    }) { Text(stringResource(R.string.select_all)) }
                    FilledTonalButton(onClick = {
                        onPreferencesChange(
                            BackupPreferences(
                                includePasswords = false,
                                includeAuthenticators = false,
                                includeDocuments = false,
                                includeBankCards = false,
                                includePasskeys = false,  // ✅ 新增
                                includeNotes = false,
                                includeImages = false,
                                includeTrashAndHistory = false,  // ✅ 新增
                                includeGeneratorHistory = false,
                                includeTimeline = false,
                                includeTrash = false,
                                includeLocalKeePass = false,
                                encryptionFormat = preferences.encryptionFormat
                            )
                        )
                    }) { Text(stringResource(R.string.deselect_all)) }
                }
                val groupCount = if (isWebDavConfigured) 9 else 8
                ContentTypeSwitch(
                    index = 0, groupCount = groupCount,
                    label = stringResource(R.string.backup_content_passwords),
                    count = passwordCount,
                    checked = preferences.includePasswords,
                    onCheckedChange = {
                        onPreferencesChange(preferences.copy(includePasswords = it))
                    }
                )
                ContentTypeSwitch(
                    index = 1, groupCount = groupCount,
                    label = stringResource(R.string.backup_content_authenticators),
                    count = authenticatorCount,
                    checked = preferences.includeAuthenticators,
                    onCheckedChange = {
                        onPreferencesChange(preferences.copy(includeAuthenticators = it))
                    }
                )
                ContentTypeSwitch(
                    index = 2, groupCount = groupCount,
                    label = stringResource(R.string.backup_content_passkeys),
                    count = if (passkeyCount > 0) passkeyCount else null,
                    checked = preferences.includePasskeys,
                    onCheckedChange = {
                        onPreferencesChange(preferences.copy(includePasskeys = it))
                    },
                    enabled = passkeyCount > 0
                )
                ContentTypeSwitch(
                    index = 3, groupCount = groupCount,
                    label = stringResource(R.string.backup_content_wallet),
                    count = documentCount + bankCardCount,
                    checked = preferences.includeDocuments && preferences.includeBankCards,
                    onCheckedChange = {
                        onPreferencesChange(preferences.copy(
                            includeDocuments = it,
                            includeBankCards = it
                        ))
                    }
                )
                ContentTypeSwitch(
                    index = 4, groupCount = groupCount,
                    label = stringResource(R.string.backup_content_notes),
                    count = noteCount,
                    checked = preferences.includeNotes,
                    onCheckedChange = {
                        onPreferencesChange(preferences.copy(includeNotes = it))
                    }
                )
                ContentTypeSwitch(
                    index = 5, groupCount = groupCount,
                    label = stringResource(R.string.backup_content_images),
                    count = null, // 图片不显示数量
                    checked = preferences.includeImages,
                    onCheckedChange = {
                        onPreferencesChange(preferences.copy(includeImages = it))
                    }
                )
                ContentTypeSwitch(
                    index = 6, groupCount = groupCount,
                    label = stringResource(R.string.backup_content_trash_and_history),
                    count = trashCount,
                    checked = preferences.includeTrashAndHistory,
                    onCheckedChange = {
                        onPreferencesChange(preferences.copy(
                            includeTrashAndHistory = it,
                            // 同时更新旧字段以保持兼容性
                            includeGeneratorHistory = it,
                            includeTimeline = it,
                            includeTrash = it
                        ))
                    },
                    subtitle = stringResource(R.string.backup_content_trash_and_history_hint)
                )
                ContentTypeSwitch(
                    index = 7, groupCount = groupCount,
                    subtitle = stringResource(if (localKeePassCount > 0) R.string.backup_content_local_keepass_hint
                        else R.string.backup_content_local_keepass_empty),
                    label = stringResource(R.string.backup_content_local_keepass),
                    count = if (localKeePassCount > 0) localKeePassCount else null,
                    checked = preferences.includeLocalKeePass,
                    onCheckedChange = {
                        onPreferencesChange(preferences.copy(includeLocalKeePass = it))
                    },
                    enabled = localKeePassCount > 0
                )
                if (isWebDavConfigured) {
                    ContentTypeSwitch(
                        index = 8, groupCount = groupCount,
                        subtitle = stringResource(R.string.backup_content_webdav_config_hint),
                        label = stringResource(R.string.backup_content_webdav_config),
                        count = null,
                        checked = preferences.includeWebDavConfig,
                        onCheckedChange = {
                            onPreferencesChange(preferences.copy(includeWebDavConfig = it))
                        }
                    )
                }
            }
        }
    }
}
