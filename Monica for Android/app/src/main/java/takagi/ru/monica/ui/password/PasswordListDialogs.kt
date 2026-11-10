package takagi.ru.monica.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.ui.common.dialog.DeleteConfirmDialog
import takagi.ru.monica.ui.password.PasswordBatchDeleteGlobalProgressState
import takagi.ru.monica.ui.password.PasswordBatchDeletePhase
import takagi.ru.monica.ui.password.PasswordBatchDeleteProgressTracker
import takagi.ru.monica.utils.BiometricHelper
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

private class PasswordDeleteKeePassDocumentContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }
}

internal enum class ManualStackDialogMode(
    val titleRes: Int,
    val descRes: Int
) {
    STACK(
        titleRes = R.string.batch_stack_mode_stack,
        descRes = R.string.batch_stack_mode_stack_desc
    ),
    AUTO_STACK(
        titleRes = R.string.batch_stack_mode_auto,
        descRes = R.string.batch_stack_mode_auto_desc
    ),
    NEVER_STACK(
        titleRes = R.string.batch_stack_mode_never,
        descRes = R.string.batch_stack_mode_never_desc
    )
}

internal data class PasswordBatchDeleteProgressUiState(
    val processed: Int,
    val total: Int,
    val phase: PasswordBatchDeletePhase = PasswordBatchDeletePhase.RUNNING,
    val successCount: Int? = null
) {
    val progressFraction: Float
        get() = if (total <= 0) 0f else processed.toFloat() / total.toFloat()

    val progressText: String
        get() = "$processed / $total"
}

internal fun PasswordBatchDeleteGlobalProgressState.toDialogUiState(): PasswordBatchDeleteProgressUiState =
    PasswordBatchDeleteProgressUiState(
        processed = processed,
        total = total.coerceAtLeast(processed.coerceAtLeast(1)),
        phase = phase,
        successCount = successCount
    )

// Isolates destructive-action dialogs so the main list composable stays focused on layout.
@Composable
internal fun PasswordListDialogs(
    showManualStackConfirmDialog: Boolean,
    onShowManualStackConfirmDialogChange: (Boolean) -> Unit,
    selectedItemKeys: Set<String>,
    selectedPasswords: Set<Long>,
    selectedCount: Int,
    selectedManualStackMode: ManualStackDialogMode,
    onSelectedManualStackModeChange: (ManualStackDialogMode) -> Unit,
    onApplyManualStackMode: suspend (ManualStackDialogMode, Set<String>, Set<Long>) -> Int,
    viewModel: PasswordViewModel,
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onDeleteSelection: suspend (
        targetKeys: Set<String>,
        targetPasswordIds: Set<Long>,
        onProgress: (processed: Int, total: Int) -> Unit,
    ) -> Int,
    onBatchDeleteStarted: () -> Unit = {},
    onSelectionCleared: () -> Unit,
    showBatchDeleteDialog: Boolean,
    onShowBatchDeleteDialogChange: (Boolean) -> Unit,
    canUseBiometric: Boolean,
    activity: FragmentActivity?,
    biometricHelper: BiometricHelper,
    itemToDelete: PasswordEntry?,
    onItemToDeleteChange: (PasswordEntry?) -> Unit,
    appSettings: AppSettings,
    localKeePassViewModel: LocalKeePassViewModel? = null
) {
    var batchDeleteProcessed by remember { mutableStateOf(0) }
    var batchDeleteTotal by remember { mutableStateOf(0) }
    var pendingPermissionRepair by remember { mutableStateOf<takagi.ru.monica.viewmodel.KeePassPermissionRepairRequest?>(null) }
    var showPermissionRepairDialog by remember { mutableStateOf(false) }
    val permissionRepairLauncher = rememberLauncherForActivityResult(
        contract = PasswordDeleteKeePassDocumentContract()
    ) { uri: Uri? ->
        val request = pendingPermissionRepair
        pendingPermissionRepair = null
        if (uri != null && request != null && localKeePassViewModel != null) {
            localKeePassViewModel.reauthorizeExternalDatabase(request.databaseId, uri) {
                viewModel.deletePasswordEntry(
                    request.entry
                )
            }
        }
    }

    LaunchedEffect(viewModel, localKeePassViewModel) {
        if (localKeePassViewModel == null) return@LaunchedEffect
        viewModel.keepassPermissionRepairRequests.collect { request ->
            pendingPermissionRepair = request
            showPermissionRepairDialog = true
        }
    }

    if (showPermissionRepairDialog && pendingPermissionRepair != null) {
        AlertDialog(
            onDismissRequest = {
                showPermissionRepairDialog = false
                pendingPermissionRepair = null
            },
            title = { Text(stringResource(R.string.keepass_permission_repair_dialog_title)) },
            text = { Text(stringResource(R.string.keepass_permission_repair_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRepairDialog = false
                    permissionRepairLauncher.launch(
                        arrayOf("application/x-keepass", "application/octet-stream", "*/*")
                    )
                }) {
                    Text(stringResource(R.string.keepass_file_permission_repair))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionRepairDialog = false
                    pendingPermissionRepair = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showManualStackConfirmDialog) {
        PasswordStackModeDialog(selectedCount, selectedManualStackMode, onSelectedManualStackModeChange,
            onDismiss = { onShowManualStackConfirmDialogChange(false) },
            onConfirm = { coroutineScope.launch {
                try {
                    val count = onApplyManualStackMode(selectedManualStackMode, selectedItemKeys, selectedPasswords)
                    if (count > 0) onSelectionCleared()
                    onShowManualStackConfirmDialogChange(false)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(context, R.string.entry_message_storage_save_failed, Toast.LENGTH_LONG).show() }
            } })
    }

    if (showBatchDeleteDialog) {
        val targetKeys = remember { selectedItemKeys.toSet() }
        val targetPasswords = remember { selectedPasswords.toSet() }
        suspend fun executeBatchDelete() {
            val shouldTrackProgress = selectedCount > 0
            val notificationId = if (shouldTrackProgress) {
                PasswordBatchDeleteNotificationHelper.createNotificationId()
            } else {
                null
            }
            if (shouldTrackProgress) {
                batchDeleteProcessed = 0
                batchDeleteTotal = selectedCount.coerceAtLeast(1)
                PasswordBatchDeleteProgressTracker.update(0, batchDeleteTotal)
                notificationId?.let {
                    PasswordBatchDeleteNotificationHelper.showProgress(
                        context = context,
                        notificationId = it,
                        processed = 0,
                        total = batchDeleteTotal
                    )
                }
            }
            onShowBatchDeleteDialogChange(false)

            var didFinishDeleteFlow = false
            var deletedCountResult = 0
            try {
                deletedCountResult = if (shouldTrackProgress) {
                    var started = false
                    onDeleteSelection(targetKeys, targetPasswords) { processed, total ->
                        if (!started) {
                            started = true
                            onBatchDeleteStarted()
                        }
                        val safeTotal = total.coerceAtLeast(1)
                        val safeProcessed = processed.coerceIn(0, safeTotal)
                        batchDeleteProcessed = safeProcessed
                        batchDeleteTotal = safeTotal
                        PasswordBatchDeleteProgressTracker.update(safeProcessed, safeTotal)
                        notificationId?.let {
                            PasswordBatchDeleteNotificationHelper.showProgress(
                                context = context,
                                notificationId = it,
                                processed = safeProcessed,
                                total = safeTotal
                            )
                        }
                    }
                } else {
                    onBatchDeleteStarted()
                    onDeleteSelection(targetKeys, targetPasswords) { _, _ -> }
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.deleted_items, deletedCountResult),
                    Toast.LENGTH_SHORT
                ).show()
                didFinishDeleteFlow = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, R.string.batch_delete_incomplete_title, Toast.LENGTH_LONG).show()
            } finally {
                if (shouldTrackProgress) {
                    val finalTotal = batchDeleteTotal.coerceAtLeast(selectedCount.coerceAtLeast(1))
                    val successCount = deletedCountResult.coerceIn(0, finalTotal)
                    val failedCount = (finalTotal - successCount).coerceAtLeast(0)

                    notificationId?.let {
                        if (didFinishDeleteFlow) {
                            PasswordBatchDeleteNotificationHelper.showCompleted(context, it, successCount, failedCount)
                        } else {
                            PasswordBatchDeleteNotificationHelper.showInterrupted(context, it, batchDeleteProcessed, finalTotal)
                        }
                    }
                    if (didFinishDeleteFlow) {
                        PasswordBatchDeleteProgressTracker.complete(successCount)
                    } else {
                        PasswordBatchDeleteProgressTracker.fail()
                    }
                }
            }
        }

        val deleteSelection: () -> Unit = {
            viewModel.viewModelScope.launch { executeBatchDelete() }
        }
        DeleteConfirmDialog(
            itemTitle = stringResource(R.string.selected_items, selectedCount),
            biometricEnabled = appSettings.biometricEnabled,
            skipIdentityVerification = appSettings.disablePasswordVerification,
            title = stringResource(R.string.verify_identity),
            message = stringResource(R.string.batch_delete_passwords_message, selectedCount),
            onDismiss = { onShowBatchDeleteDialogChange(false) },
            onConfirmWithoutVerification = deleteSelection,
            onPasswordVerified = deleteSelection,
            onConfirmWithBiometric = deleteSelection,
            isRequestCurrent = {
                showBatchDeleteDialog && selectedItemKeys == targetKeys && selectedPasswords == targetPasswords
            },
        )
    }

    itemToDelete?.let { item ->
        val targetItem = remember { item }
        DeleteConfirmDialog(
            itemTitle = item.title,
            itemType = stringResource(R.string.item_type_password),
            biometricEnabled = appSettings.biometricEnabled,
            skipIdentityVerification = appSettings.disablePasswordVerification,
            onDismiss = {
                onItemToDeleteChange(null)
            },
            onConfirmWithoutVerification = {
                coroutineScope.launch {
                    viewModel.deletePasswordEntry(item)
                    Toast.makeText(context, context.getString(R.string.deleted), Toast.LENGTH_SHORT).show()
                    onItemToDeleteChange(null)
                }
            },
            onPasswordVerified = {
                onItemToDeleteChange(null)
                coroutineScope.launch {
                    viewModel.deletePasswordEntry(targetItem)
                    Toast.makeText(context, context.getString(R.string.deleted), Toast.LENGTH_SHORT).show()
                }
            },
            isRequestCurrent = { itemToDelete == targetItem },
            onConfirmWithBiometric = {
                coroutineScope.launch {
                    viewModel.deletePasswordEntry(item)
                    Toast.makeText(
                        context,
                        context.getString(R.string.deleted),
                        Toast.LENGTH_SHORT
                    ).show()
                    onItemToDeleteChange(null)
                }
            }
        )
    }

}

@Composable
internal fun PasswordBatchDeleteProgressDialog(
    state: PasswordBatchDeleteProgressUiState,
    onMoveToBackground: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (state.phase != PasswordBatchDeletePhase.RUNNING) onMoveToBackground() },
        title = {
            Text(text = stringResource(if (state.phase == PasswordBatchDeletePhase.RUNNING)
                R.string.batch_delete_in_progress_title else if (state.phase == PasswordBatchDeletePhase.SUCCESS)
                R.string.batch_delete_notification_done else R.string.batch_delete_incomplete_title))
        },
        text = {
            Column {
                if (state.phase != PasswordBatchDeletePhase.RUNNING) {
                    Text(text = if (state.successCount != null) {
                        if (state.phase == PasswordBatchDeletePhase.SUCCESS)
                            stringResource(R.string.batch_delete_notification_done_detail_success, state.successCount)
                        else stringResource(R.string.batch_delete_notification_done_detail_partial, state.successCount, state.total - state.successCount)
                    } else stringResource(R.string.batch_delete_interrupted_count, state.processed, state.total))
                } else {
                Text(text = stringResource(R.string.batch_delete_in_progress_message))
                Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
                if (state.total > 0 && state.processed <= 0) {
                    LinearProgressIndicator(
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { state.progressFraction.coerceIn(0f, 1f) },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                Text(
                    text = if (state.total > 0 && state.processed <= 0) {
                        stringResource(R.string.batch_delete_in_progress_preparing)
                    } else {
                        state.progressText
                    }
                )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onMoveToBackground) {
                Text(text = stringResource(if (state.phase == PasswordBatchDeletePhase.RUNNING)
                    R.string.password_batch_transfer_continue_in_background else R.string.close))
            }
        }
    )
}
