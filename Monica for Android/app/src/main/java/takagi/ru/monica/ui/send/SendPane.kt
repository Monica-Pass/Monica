package takagi.ru.monica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import android.net.Uri
import androidx.compose.ui.unit.Dp
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.ui.common.layout.DetailPane
import takagi.ru.monica.ui.common.layout.ListPane
import takagi.ru.monica.ui.screens.AddEditSendScreen
import takagi.ru.monica.ui.screens.SendScreen

@Composable
internal fun SendPane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    bitwardenViewModel: BitwardenViewModel,
    sendState: BitwardenViewModel.SendState,
    selectedSend: BitwardenSend?,
    isAddingSendInline: Boolean,
    onSendClick: (BitwardenSend) -> Unit,
    onInlineSendEditorBack: () -> Unit,
    onCreateSend: (
        vaultId: Long,
        title: String,
        text: String,
        notes: String?,
        password: String?,
        maxAccessCount: Int?,
        hideEmail: Boolean,
        hiddenText: Boolean,
        expireInDays: Int
    ) -> Unit,
    onCreateFileSend: (
        vaultId: Long,
        title: String,
        fileUri: Uri,
        fileName: String,
        notes: String?,
        password: String?,
        maxAccessCount: Int?,
        hideEmail: Boolean,
        expireInDays: Int
    ) -> Unit,
    onBitwardenEvent: ((BitwardenViewModel.BitwardenEvent) -> Boolean)? = null,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {}
) {
    val vaults by bitwardenViewModel.vaults.collectAsState()
    val activeVault by bitwardenViewModel.activeVault.collectAsState()
    val unlockStateByVault by bitwardenViewModel.unlockStateByVault.collectAsState()
    val sendCreateSuccessVersion by bitwardenViewModel.sendCreateSuccessVersion.collectAsState()

    if (isCompactWidth) {
        SendScreen(
            bitwardenViewModel = bitwardenViewModel,
            onBitwardenEvent = onBitwardenEvent,
            showStandaloneSettingsEntry = showStandaloneSettingsEntry,
            onOpenStandaloneSettings = onOpenStandaloneSettings
        )
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            ListPane(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(wideListPaneWidth)
            ) {
                SendScreen(
                    onSendClick = onSendClick,
                    selectedSendId = selectedSend?.bitwardenSendId,
                    bitwardenViewModel = bitwardenViewModel,
                    onBitwardenEvent = onBitwardenEvent,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            DetailPane(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                if (isAddingSendInline) {
                    AddEditSendScreen(
                        sendState = sendState,
                        sendCreateSuccessVersion = sendCreateSuccessVersion,
                        vaults = vaults,
                        activeVault = activeVault,
                        unlockStateByVault = unlockStateByVault,
                        onNavigateBack = onInlineSendEditorBack,
                        onCreate = onCreateSend,
                        onCreateFile = onCreateFileSend,
                        onSubmitStoredDraft = bitwardenViewModel::submitStoredSendDraft,
                    onLeaveForeground = bitwardenViewModel::leaveSendEditorForeground,
                onSaveDraft = { vaultId, draft, uri, fileName ->
                            bitwardenViewModel.saveSendDraft(vaultId, draft, fileUri = uri, fileName = fileName)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (selectedSend == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.select_send_preview_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val labelVault = vaults.firstOrNull { it.id == selectedSend.vaultId }
                        ?: activeVault?.takeIf { it.id == selectedSend.vaultId }
                    val vaultLabel = labelVault?.let {
                        it.displayName?.takeIf { name -> name.isNotBlank() } ?: it.email
                    }.orEmpty()
                    SendDetailPane(
                        send = selectedSend,
                        modifier = Modifier.fillMaxSize(),
                        vaultLabel = vaultLabel
                    )
                }
            }
        }
    }
}
