package takagi.ru.monica.ui.vaultv2

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

@Composable
internal fun VaultV2ScrollToTopEffect(
    requestKey: Int,
    showOverview: Boolean,
    waitingForList: Boolean,
    listState: LazyListState,
    overviewListState: LazyListState,
    onScrollingChange: (Boolean) -> Unit,
) {
    var lastHandled by rememberSaveable { mutableIntStateOf(0) }
    val scrollingChanged by rememberUpdatedState(onScrollingChange)
    LaunchedEffect(requestKey, showOverview, waitingForList) {
        // A preview has no live LazyColumn layout. Do not make publishing that
        // list depend on a scroll animation that needs the list to be mounted.
        if (waitingForList || requestKey <= lastHandled) return@LaunchedEffect
        if (showOverview) {
            overviewListState.animateScrollToItem(0)
            lastHandled = requestKey
            return@LaunchedEffect
        }
        scrollingChanged(true)
        try {
            try {
                listState.animateScrollToItem(0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Complete the pending reset without animation.
            }
            listState.scrollToItem(0)
            lastHandled = requestKey
        } finally {
            scrollingChanged(false)
        }
    }
}
