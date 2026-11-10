package takagi.ru.monica.ui.password

import takagi.ru.monica.ui.components.SecondaryPasswordVerificationDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

internal data class KeePassListRead(
    val databaseId: Long?,
    val snapshot: KeePassMonicaPasswordStore.Snapshot?,
    val versions: Map<Long, Long>,
    val generation: Long,
    val visible: Boolean,
    val locked: Boolean,
    val failed: Boolean,
    val retry: () -> Unit,
    val unlock: () -> Unit,
)

/** A selection owns its read. A previous selection can never satisfy this one's readiness. */
@Composable
internal fun rememberKeePassListRead(
    databaseId: Long?, viewModel: PasswordViewModel, localViewModel: LocalKeePassViewModel,
): KeePassListRead {
    val store = viewModel.monicaKeePassStore
    val versions by KeePassKdbxService.nativeLockVersions.collectAsState()
    val generation by SessionManager.lockGeneration.collectAsState()
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val visible = unlocked && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var retry by remember { mutableIntStateOf(0) }
    val request = remember(databaseId, versions[databaseId], generation, visible, retry) { Any() }
    var completed by remember(request) { mutableStateOf(false) }
    var failed by remember(request) { mutableStateOf(false) }
    var showUnlock by remember(request) { mutableStateOf(false) }
    val snapshots = store?.presentationSnapshots?.collectAsState()?.value.orEmpty()
    val locked = databaseId != null && store?.isListAuthenticationRequired(databaseId) == true
    val currentRequest by rememberUpdatedState(request)
    LaunchedEffect(request, locked) {
        if (databaseId == null || store == null || !visible || locked) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) { store.refresh(databaseId) }
            ensureActive()
            completed = true
            localViewModel.refreshGroups(databaseId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    if (showUnlock && visible && databaseId != null && store != null) {
        SecondaryPasswordVerificationDialog(
            manager = takagi.ru.monica.ui.rememberUiSecurityManager(),
            title = stringResource(R.string.kp_list_unlock),
            message = stringResource(R.string.kp_list_unlock_help),
            onDismiss = { showUnlock = false; retry++ },
            isRequestCurrent = {
                currentRequest === request && showUnlock &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation &&
                    KeePassKdbxService.nativeLockVersions.value[databaseId] == versions[databaseId]
            },
            onSuccess = {
                runCatching { store.authorizeListRead(databaseId, versions[databaseId] ?: 0L, generation) }
                    .onSuccess { showUnlock = false; retry++ }
                    .onFailure { showUnlock = false; failed = true }
            },
            confirmText = stringResource(R.string.kp_list_unlock),
            icon = Icons.Default.Lock,
        )
    }
    return KeePassListRead(databaseId,
        snapshots[databaseId]?.takeIf { completed && visible && !locked && it.lockVersion == (versions[databaseId] ?: 0L) },
        versions, generation, visible, locked, failed,
        retry = { retry++ }, unlock = { showUnlock = true })
}

internal data class KeePassListTransition(
    val target: String,
    val previousSource: KeePassListSource?,
    val locked: Boolean,
    val failed: Boolean,
    val visible: Boolean,
    val retry: () -> Unit,
    val unlock: () -> Unit,

)

@Composable
internal fun rememberKeePassListTransition(
    read: KeePassListRead, name: String, ready: Boolean,
): KeePassListTransition? {
    val cache = remember(read.generation) { KeePassListRetention<KeePassListSource>() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(cache, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cache.clear() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cache.clear() }
    }
    if (!read.visible || read.locked || read.databaseId == null) cache.clear()
    val previous = cache.previous(read.versions)
    val id = read.databaseId
    val usable = read.visible && !read.locked && read.snapshot != null && ready
    SideEffect {
        if (id != null && usable) {
            val source = KeePassListSource(id, name, read.versions[id] ?: 0L)
            cache.update(source, source)
        }
    }
    if (id == null || usable) return null
    return KeePassListTransition(name, previous, read.locked, read.failed, read.visible, read.retry, read.unlock)
}
