package takagi.ru.monica.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

/**
 * 权限帮助对话框组件
 * Permission help dialog component
 */
@Composable
fun PermissionHelpDialog(
    onDismiss: () -> Unit
) {
    val title = stringResource(R.string.permission_help_title)
    val content = stringResource(R.string.pm_inventory_hint)
    val confirm = stringResource(R.string.ok)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = title)
        },
        text = {
            Text(text = content, modifier = Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = confirm)
            }
        }
    )
}
