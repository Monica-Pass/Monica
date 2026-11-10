package takagi.ru.monica.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import takagi.ru.monica.R
import takagi.ru.monica.security.SecurityManager

/** A credential change in another process retires the whole graph that owns the old manager. */
@Composable
internal fun SecureStoreGenerationGate(
    manager: SecurityManager,
    onReload: () -> Unit,
    content: @Composable () -> Unit,
) {
    val generation by SecurityManager.dataReadGeneration.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foregroundCheck by remember { mutableIntStateOf(0) }
    var retired by remember(manager) { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) foregroundCheck++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val current = remember(manager, generation, foregroundCheck, retired) {
        !retired && manager.isPreparedStoreCurrent().also { if (!it) retired = true }
    }
    if (current) content()
    else {
        // Unmount sensitive children before recreating their repositories and ViewModels.
        Text(stringResource(R.string.loading))
        LaunchedEffect(manager) { onReload() }
    }
}
