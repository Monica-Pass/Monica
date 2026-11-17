package takagi.ru.monica.autofill_ng.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry

/** One centered dialog for suggested and ordinary picker entries, across all entry points. */
@Composable
internal fun PasswordActionDialog(
    password: PasswordEntry,
    displayUsername: String,
    credentialLabel: String?,
    showSmartCopyOptions: Boolean,
    onDismiss: () -> Unit,
    onAction: (PasswordItemAction) -> Unit,
) {
    val allowsUriBinding = password.mdbxDatabaseId == null
    val fillActionCount = if (allowsUriBinding) 3 else 2
    fun select(action: PasswordItemAction) { onDismiss(); onAction(action) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth()
                    .heightIn(max = maxHeight).testTag("autofill_password_actions"),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(password.title.ifBlank { displayUsername }.toSafeComposeText(),
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val subtitle = listOfNotNull(displayUsername.takeIf(String::isNotBlank), credentialLabel)
                                .joinToString(" · ").toSafeComposeText()
                            if (subtitle.isNotBlank()) Text(subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.testTag("autofill_actions_close")) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.close))
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp).padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionSection(stringResource(R.string.autofill_action_fill_methods)) {
                            ActionRow(R.string.autofill, Icons.Outlined.AutoAwesome, "autofill_action_default", 0, fillActionCount,
                                emphasized = true) { select(PasswordItemAction.Autofill(password)) }
                            ActionRow(R.string.autofill_accessibility_fill, Icons.Outlined.AccessibilityNew,
                                "autofill_action_accessibility", 1, fillActionCount,
                                supporting = stringResource(R.string.autofill_accessibility_fill_hint)) {
                                select(PasswordItemAction.AccessibilityFill(password))
                            }
                            if (allowsUriBinding) {
                                ActionRow(R.string.autofill_and_save_uri, Icons.Outlined.Link, "autofill_action_bind", 2, fillActionCount) {
                                    select(PasswordItemAction.AutofillAndSaveUri(password))
                                }
                            }
                        }
                        ActionSection(stringResource(R.string.autofill_action_copy)) {
                            ActionRow(R.string.copy_username, Icons.Outlined.Person, "autofill_action_copy_username", 0, 2) {
                                select(PasswordItemAction.CopyUsername(password))
                            }
                            ActionRow(R.string.copy_password, Icons.Outlined.Password, "autofill_action_copy_password", 1, 2) {
                                select(PasswordItemAction.CopyPassword(password))
                            }
                        }
                        if (showSmartCopyOptions) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                ActionRow(R.string.autofill_action_username_first, Icons.Outlined.ContentCopy,
                                    "autofill_action_smart_username", 0, 2,
                                    supporting = stringResource(R.string.autofill_action_password_next)) {
                                    select(PasswordItemAction.SmartCopyUsernameFirst(password))
                                }
                                ActionRow(R.string.autofill_action_password_first, Icons.Outlined.ContentCopy,
                                    "autofill_action_smart_password", 1, 2,
                                    supporting = stringResource(R.string.autofill_action_username_next)) {
                                    select(PasswordItemAction.SmartCopyPasswordFirst(password))
                                }
                            }
                        }
                        ActionRow(R.string.details, Icons.Outlined.Info, "autofill_action_details", 0, 1) {
                            select(PasswordItemAction.ViewDetails(password))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, Modifier.padding(start = 12.dp, bottom = 6.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun ActionRow(label: Int, icon: ImageVector, tag: String, index: Int, count: Int,
    supporting: String? = null, emphasized: Boolean = false, onClick: () -> Unit) {
    val outer = 20.dp
    val inner = 4.dp
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(topStart = if (index == 0) outer else inner,
            topEnd = if (index == 0) outer else inner,
            bottomStart = if (index == count - 1) outer else inner,
            bottomEnd = if (index == count - 1) outer else inner),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
