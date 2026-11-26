package takagi.ru.monica.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.model.ProjectCredentialGroup

/** The same filled controls as the primary credential; edits remain in the project draft. */
@Composable
fun ProjectCredentialEditor(group: ProjectCredentialGroup.Group,
    onChange: (ProjectCredentialGroup.Group) -> Unit, onRemove: () -> Unit,
    onGenerate: (passwordId: String?) -> Unit, settings: takagi.ru.monica.data.AppSettings) {
    var menu by remember(group.id) { mutableStateOf(false) }
    var confirm by remember(group.id) { mutableStateOf(false) }
    var options by remember(group.id) { mutableStateOf(false) }
    val drag = LocalContentDrag.current
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp).then(drag.modifier).animateContentSize().testTag("credential_group_${group.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
            color = if (drag.dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                Text(group.label.ifBlank { stringResource(R.string.project_credential) }, Modifier.weight(1f).padding(12.dp),
                    style = MaterialTheme.typography.titleSmall)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
                    EditorActionMenu(menu, { menu = false }, listOf(
                        EditorMenuAction(stringResource(R.string.advanced_options), Icons.Default.Tune, onClick = { options = !options }),
                        EditorMenuAction(stringResource(R.string.delete), Icons.Default.Delete, onClick = { confirm = true }, destructive = true)))
                }
            }
        }
        CompositionLocalProvider(LocalFilledEntryForm provides true) {
            if (options) {
                SuggestedOutlinedTextField(group.label, { onChange(group.copy(label = it)) }, saveTextState = false,
                    suggestionField = takagi.ru.monica.data.CommonSuggestionField.CREDENTIAL_LABEL,
                    label = { Text(stringResource(R.string.project_credential_label)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp))
            }
            OutlinedTextField(group.username, { onChange(group.copy(username = it)) }, saveTextState = false,
                label = { Text(stringResource(R.string.username)) }, leadingIcon = { Icon(Icons.Default.Person, null) },
                trailingIcon = { IconButton(onClick = { onGenerate(null) }) {
                    Icon(Icons.Default.AutoAwesome, stringResource(R.string.cg_username_title),
                        modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                } },
                modifier = Modifier.fillMaxWidth().testTag("credential_username_${group.id}"), shape = RoundedCornerShape(4.dp), singleLine = true)
            group.passwords.forEachIndexed { index, password -> key(password.id) {
                var visible by remember(password.id) { mutableStateOf(false) }
                val interaction = remember(password.id) { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                Surface(color = if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(4.dp), modifier = Modifier.testTag("credential_field_surface_${password.id}")) {
                Column {
                OutlinedTextField(password.value, { value -> onChange(group.copy(passwords = group.passwords.map {
                    if (it.id == password.id) it.copy(value = value) else it
                })) }, saveTextState = false,
                    label = { Text(if (group.passwords.size == 1) stringResource(R.string.password) else stringResource(R.string.project_credential_password_number, index + 1)) },
                    leadingIcon = { Icon(Icons.Default.Key, null) },
                    trailingIcon = {
                        CredentialPasswordActions(visible, { visible = !visible }, { onGenerate(password.id) },
                            onRemove = if (group.passwords.size > 1) ({
                                onChange(group.copy(passwords = group.passwords.filterNot { it.id == password.id }))
                            }) else null, tag = "credential_actions_${password.id}")
                    },
                    interactionSource = interaction,
                    textStyle = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("credential_password_${password.id}"),
                    shape = RoundedCornerShape(4.dp), singleLine = true)
                androidx.compose.animation.AnimatedVisibility(visible = password.value.isNotEmpty(),
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) {
                    Box(Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp)) {
                        PasswordStrengthBadge(takagi.ru.monica.utils.PasswordStrengthAnalyzer.calculateStrength(password.value))
                    }
                }
                } }
            } }
        }
        ProjectCredentialOtpField(group.otp, group.label, group.username, settings,
            onValueChange = { onChange(group.copy(otp = it)) },
            modifier = Modifier.testTag("credential_otp_${group.id}"),
            followingContent = {
                CredentialNoteField(group.note, { onChange(group.copy(note = it)) },
                    Modifier.testTag("credential_note_${group.id}"))
            })
        Spacer(Modifier.height(6.dp))
        Surface(onClick = { onChange(group.copy(passwords = group.passwords + ProjectCredentialGroup.Password())) },
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().testTag("credential_add_password_${group.id}")) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.add_password), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.delete)) },
        text = { Text(stringResource(R.string.project_credential_remove)) },
        confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
