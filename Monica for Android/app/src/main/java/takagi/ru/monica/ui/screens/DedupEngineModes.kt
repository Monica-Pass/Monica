package takagi.ru.monica.ui.screens

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.navigation.MonicaPageContent
import takagi.ru.monica.viewmodel.SingleVaultDedupViewModel

@Composable
internal fun DedupEngineModes(
    singleViewModel: SingleVaultDedupViewModel,
    mergeBusy: Boolean,
    onExitMerge: () -> Unit,
    onEnterMerge: () -> Unit,
    onNavigateBack: () -> Unit,
    mergeContent: @Composable (@Composable () -> Unit) -> Unit,
) {
    var single by remember { mutableStateOf(true) }
    val state by singleViewModel.uiState.collectAsState()
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lock by SessionManager.lockGeneration.collectAsState()
    val owner = LocalLifecycleOwner.current
    val exitMerge by rememberUpdatedState(onExitMerge)
    LaunchedEffect(unlocked, lock) {
        if (!unlocked) { singleViewModel.invalidate(); exitMerge() }
    }
    DisposableEffect(owner, singleViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { singleViewModel.invalidate(); exitMerge() }
                Lifecycle.Event.ON_START -> if (SessionManager.isUnlocked.value) singleViewModel.refresh()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); singleViewModel.invalidate(); exitMerge() }
    }
    val switch: @Composable () -> Unit = {
        DedupEngineModeSwitch(single, !state.busy && !mergeBusy) { next ->
            if (single != next) {
                singleViewModel.invalidate(); exitMerge(); single = next
                if (next && SessionManager.isUnlocked.value) singleViewModel.refresh()
                if (!next && SessionManager.isUnlocked.value) onEnterMerge()
            }
        }
    }
    MonicaPageContent(single) {
        if (single) SingleVaultDedupScreen(
            state, onNavigateBack = { singleViewModel.invalidate(); onNavigateBack() },
            onRefresh = singleViewModel::refresh, onSelectSource = singleViewModel::selectSource,
            onIncludeArchived = singleViewModel::setIncludeArchived, onAnalyze = singleViewModel::analyze,
            onToggleCandidate = singleViewModel::toggleCandidate, onReviewSelection = singleViewModel::reviewSelection,
            onDismissReview = singleViewModel::dismissReview, onConfirmCleanup = singleViewModel::confirmCleanup,
            onReviewRecovery = singleViewModel::reviewRecovery, onConfirmRecovery = singleViewModel::confirmRecovery,
            onCancel = singleViewModel::cancel, modeSwitch = switch,
        ) else mergeContent(switch)
    }
}
