package takagi.ru.monica.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.service.BitwardenSendDraft
import takagi.ru.monica.bitwarden.service.BitwardenSendDraftStore
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SessionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditSendScreen(
    modifier: Modifier = Modifier,
    sendState: BitwardenViewModel.SendState,
    sendCreateSuccessVersion: Int = 0,
    vaults: List<BitwardenVault> = emptyList(),
    activeVault: BitwardenVault? = null,
    unlockStateByVault: Map<Long, BitwardenViewModel.UnlockState> = emptyMap(),
    initialTitle: String = "",
    initialText: String = "",
    initialNotes: String = "",
    onNavigateBack: () -> Unit,
    onCreate: (
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
    onCreateFile: (
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
    existingSend: BitwardenSend? = null,
    onSaveDraft: ((Long, BitwardenSendDraft, Uri?, String?) -> Unit)? = null,
    initialStoredDraft: BitwardenSendDraftStore.Draft? = null,
    onSubmitStoredDraft: ((BitwardenSendDraftStore.Draft) -> Unit)? = null,
    onLeaveForeground: () -> Unit = {},
    localDrafts: List<BitwardenSendDraftStore.Draft> = emptyList(),
    draftsUnavailable: Boolean = false,
    onRefreshDrafts: (() -> Unit)? = null,
    onDiscardDraft: ((BitwardenSendDraftStore.Draft) -> Unit)? = null
) {
    takagi.ru.monica.nfc.card.NfcCardBlockingEditor()
    var selectedDraft by remember(initialStoredDraft) { mutableStateOf(initialStoredDraft) }
    var showDrafts by remember { mutableStateOf(false) }
    var replaceChanges by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf<BitwardenSendDraftStore.Draft?>(null) }
    var closed by remember { mutableStateOf(false) }
    var cleanupDone by remember { mutableStateOf(false) }
    var draftOpenVersion by remember { mutableIntStateOf(0) }
    var editingDraftId by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val sheetDensity = LocalDensity.current
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val generation by SessionManager.lockGeneration.collectAsState()
    val admittedGeneration = remember { generation }
    val currentBack by rememberUpdatedState(onNavigateBack)
    val currentCleanup by rememberUpdatedState(onLeaveForeground)
    fun leaveForeground() {
        if (cleanupDone) return
        cleanupDone = true
        currentCleanup()
    }
    fun close() {
        if (closed) return
        closed = true; showDrafts = false; replacement = null; selectedDraft = null
        leaveForeground()
        currentBack()
    }
    fun openDraft(saved: BitwardenSendDraftStore.Draft) {
        selectedDraft = saved
        draftOpenVersion++
    }
    LaunchedEffect(unlocked, generation) {
        if (!unlocked || generation != admittedGeneration) close()
    }
    val knownVaults = vaults.ifEmpty { listOfNotNull(activeVault) }
    fun currentDraft(saved: BitwardenSendDraftStore.Draft): Boolean =
        SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == admittedGeneration &&
            knownVaults.any { vault -> vault.id == saved.vaultId &&
                saved.account == takagi.ru.monica.bitwarden.service.BitwardenSendAccountIdentity.from(vault) &&
                unlockStateByVault[vault.id] == BitwardenViewModel.UnlockState.Unlocked }
    val visibleDrafts = localDrafts.filter(::currentDraft)
    if (closed || !unlocked || generation != admittedGeneration) return
    key(selectedDraft?.vaultId, selectedDraft?.id, draftOpenVersion) {
        AddEditSendForm(
            modifier = modifier, sendState = sendState, sendCreateSuccessVersion = sendCreateSuccessVersion,
            vaults = vaults, activeVault = activeVault, unlockStateByVault = unlockStateByVault,
            initialTitle = initialTitle, initialText = initialText, initialNotes = initialNotes,
            onNavigateBack = ::close, onCreate = onCreate, onCreateFile = onCreateFile,
            existingSend = existingSend, onSaveDraft = onSaveDraft, initialStoredDraft = selectedDraft,
            onSubmitStoredDraft = onSubmitStoredDraft, onLeaveForeground = ::leaveForeground,
            onOpenDrafts = if (existingSend == null && onRefreshDrafts != null) { changed, editing ->
                replaceChanges = changed
                editingDraftId = editing?.let { it.vaultId to it.id }
                onRefreshDrafts()
                showDrafts = true
            } else null,
        )
    }
    if (showDrafts) ModalBottomSheet(
        onDismissRequest = { showDrafts = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("send_drafts_sheet"),
    ) {
        CompositionLocalProvider(LocalDensity provides sheetDensity) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.bw_send_drafts), style = MaterialTheme.typography.headlineSmall)
            SendLocalDraftList(visibleDrafts, draftsUnavailable, sendState is BitwardenViewModel.SendState.Creating,
                onResume = { saved ->
                    if (currentDraft(saved)) {
                        showDrafts = false
                        if (replaceChanges) replacement = saved else openDraft(saved)
                    }
                },
                onDiscard = { saved ->
                    if (currentDraft(saved) && editingDraftId != (saved.vaultId to saved.id)) onDiscardDraft?.invoke(saved)
                },
                canDiscard = { saved -> editingDraftId != (saved.vaultId to saved.id) },
            )
        }
        }
    }
    replacement?.let { saved ->
        AlertDialog(
            modifier = Modifier.testTag("send_draft_replace_confirm"),
            onDismissRequest = { replacement = null },
            title = { Text(stringResource(R.string.send_draft_replace_title)) },
            text = { Text(stringResource(R.string.send_draft_replace_message)) },
            dismissButton = { TextButton(onClick = { replacement = null }, modifier = Modifier.testTag("send_draft_replace_cancel")) {
                Text(stringResource(R.string.cancel))
            } },
            confirmButton = { TextButton(onClick = {
                if (currentDraft(saved)) openDraft(saved)
                replacement = null
            }, modifier = Modifier.testTag("send_draft_replace_apply")) {
                Text(stringResource(R.string.bw_send_draft_resume))
            } },
        )
    }
}
