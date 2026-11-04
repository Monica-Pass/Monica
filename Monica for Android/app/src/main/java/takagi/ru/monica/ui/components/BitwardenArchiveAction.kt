package takagi.ru.monica.ui.components

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.service.BitwardenSecureItemArchiveStore
import takagi.ru.monica.data.SecureItem

@Composable
internal fun rememberBitwardenArchiveAction(item: SecureItem?, onUpdated: (SecureItem) -> Unit): ActionStripItem? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { BitwardenSecureItemArchiveStore(context) }
    var confirm by remember(item?.id) { mutableStateOf(false) }
    var saving by remember(item?.id) { mutableStateOf(false) }
    val errorText = stringResource(R.string.legacy_ui_operation_failed)
    fun applyArchive() {
        val expected = item ?: return
        if (saving) return
        saving = true
        confirm = false
        scope.launch {
            try { onUpdated(store.setArchived(expected, !expected.isArchived)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, errorText, Toast.LENGTH_LONG).show() }
            finally { saving = false }
        }
    }
    if (confirm && item != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.archive_password_confirmation_title)) },
        text = { Text(stringResource(R.string.archive_password_confirmation_message)) },
        confirmButton = { TextButton(onClick = ::applyArchive, enabled = !saving) { Text(stringResource(R.string.archive_action)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (item == null) return null
    val boundBitwarden = item.bitwardenVaultId != null && !item.bitwardenCipherId.isNullOrBlank()
    val restoredLocalArchive = item.isArchived && item.bitwardenVaultId == null && item.bitwardenCipherId == null &&
        item.keepassDatabaseId == null && item.mdbxDatabaseId == null
    if (!boundBitwarden && !restoredLocalArchive) return null
    return ActionStripItem(
        icon = if (item.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
        contentDescription = stringResource(if (item.isArchived) R.string.unarchive_action else R.string.archive_action),
        onClick = { if (!saving) { if (item.isArchived) applyArchive() else confirm = true } },
    )
}
