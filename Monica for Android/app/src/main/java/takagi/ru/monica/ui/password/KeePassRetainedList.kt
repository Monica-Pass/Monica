package takagi.ru.monica.ui.password

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Implement at a component's value-parameter boundary, not around a mutable screen closure. */
internal interface KeePassListContent {
    @Composable fun Render()
}

internal val LocalKeePassListInteraction = compositionLocalOf<() -> Boolean> { { true } }

internal data class KeePassListSource(val databaseId: Long, val name: String, val lockVersion: Long)

/** One previously displayed list, owned only by the foreground composition. Never a write source. */
internal class KeePassListRetention<T> {
    private var source: KeePassListSource? = null
    private var content: T? = null

    fun update(source: KeePassListSource, content: T) {
        this.source = source
        this.content = content
    }

    fun previous(versions: Map<Long, Long>): T? {
        if (source?.let { it.lockVersion != (versions[it.databaseId] ?: 0L) } == true) clear()
        return content
    }

    fun clear() { source = null; content = null }
}

/** Reuses the original list subtree and its remembered row/scroll state during a pending read. */
@Composable
internal fun KeePassRetainedList(
    read: KeePassListRead?,
    transition: KeePassListTransition?,
    content: KeePassListContent,
    modifier: Modifier = Modifier,
) {
    val retained = remember(read?.generation) { KeePassListRetention<KeePassListContent>() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mounted = remember { booleanArrayOf(true) }
    DisposableEffect(retained, lifecycle) {
        mounted[0] = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) retained.clear()
        }
        lifecycle.addObserver(observer)
        onDispose { mounted[0] = false; lifecycle.removeObserver(observer); retained.clear() }
    }
    val blocked = transition != null
    val request = remember(read?.databaseId, read?.generation, read?.versions, blocked) { Any() }
    val latestRequest by rememberUpdatedState(request)
    val latestRead by rememberUpdatedState(read)
    val latestBlocked by rememberUpdatedState(blocked)
    val canInteract = remember(request) {
        {
            val current = latestRead
            mounted[0] && latestRequest === request && !latestBlocked &&
                (current?.databaseId == null || current.visible && !current.locked &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    takagi.ru.monica.security.SessionManager.isUnlocked.value &&
                    takagi.ru.monica.security.SessionManager.lockGeneration.value == current.generation &&
                    (takagi.ru.monica.utils.KeePassKdbxService.nativeLockVersions.value[current.databaseId] ?: 0L) ==
                        (current.versions[current.databaseId] ?: 0L))
        }
    }
    val shown = when {
        read?.databaseId == null -> { retained.clear(); content }
        !read.visible || read.locked -> { retained.clear(); null }
        !blocked -> {
            SideEffect {
                if (canInteract()) retained.update(KeePassListSource(read.databaseId, "", read.versions[read.databaseId] ?: 0L), content)
            }
            content
        }
        else -> retained.previous(read.versions)
    }
    val focusManager = LocalFocusManager.current
    var listHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(blocked, listHasFocus) {
        if (blocked && listHasFocus) focusManager.clearFocus(force = true)
    }
    // The same subtree stays mounted. Its callbacks cannot be reached through pointer,
    // keyboard or accessibility input until the selected source has completed preparation.
    val inputGuard = if (blocked) Modifier
        .clearAndSetSemantics { }
        .onPreviewKeyEvent { true }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        } else Modifier
    Box(modifier.fillMaxSize().testTag("keepass-retained-list")
        .onFocusChanged { listHasFocus = it.hasFocus }.focusGroup().then(inputGuard)) {
        CompositionLocalProvider(LocalKeePassListInteraction provides canInteract) { shown?.Render() }
    }
}
