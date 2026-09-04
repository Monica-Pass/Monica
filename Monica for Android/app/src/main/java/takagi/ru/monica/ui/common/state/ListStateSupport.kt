package takagi.ru.monica.ui.common.state

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable

internal enum class InitialListRenderState {
    Loading,
    Empty,
    Content,
}

internal fun resolveInitialListRenderState(
    isReady: Boolean,
    itemCount: Int,
): InitialListRenderState = when {
    itemCount > 0 -> InitialListRenderState.Content
    !isReady -> InitialListRenderState.Loading
    else -> InitialListRenderState.Empty
}

/**
 * 合并多个来源时，任一来源未就绪就先停在 Loading。
 * 否则先到的来源会立刻切进 Content，后到的来源只能往已渲染的列表里追加，出现卡片分批出现的效果。
 */
internal fun resolveMergedListRenderState(
    isReady: Boolean,
    itemCount: Int,
): InitialListRenderState = when {
    !isReady -> InitialListRenderState.Loading
    itemCount > 0 -> InitialListRenderState.Content
    else -> InitialListRenderState.Empty
}

@Composable
internal fun rememberSaveableLazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0
): LazyListState {
    return rememberSaveable(saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = initialFirstVisibleItemIndex,
            firstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset
        )
    }
}

@Composable
internal fun rememberSaveableLazyGridState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyGridState {
    val initialState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
    )
    return rememberSaveable(saver = LazyGridState.Saver) {
        initialState
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberSaveableLazyStaggeredGridState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyStaggeredGridState {
    val initialState = rememberLazyStaggeredGridState(
        initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
    )
    return rememberSaveable(saver = LazyStaggeredGridState.Saver) {
        initialState
    }
}
