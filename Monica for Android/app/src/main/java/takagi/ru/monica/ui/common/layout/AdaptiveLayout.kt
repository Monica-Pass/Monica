package takagi.ru.monica.ui.common.layout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/** Width of the usable app region, not the physical display or device category. */
val LocalAdaptiveWidth = staticCompositionLocalOf { Dp.Unspecified }
val LocalAdaptiveHeight = staticCompositionLocalOf { Dp.Unspecified }

data class AdaptivePanePolicy(val navigationRail: Boolean, val twoPanes: Boolean, val listWidth: Int)

fun adaptivePanePolicy(widthDp: Int, heightDp: Int = 900): AdaptivePanePolicy = AdaptivePanePolicy(
    navigationRail = widthDp >= 600,
    twoPanes = widthDp >= 840 && heightDp >= 480,
    listWidth = ((widthDp - 80) * 0.38f).toInt().coerceIn(320, 420)
)

@Composable
fun adaptiveWindowWidth(): Dp {
    val provided = LocalAdaptiveWidth.current
    return if (provided != Dp.Unspecified) provided
    else with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
}

@Composable
fun adaptiveWindowHeight(): Dp {
    val provided = LocalAdaptiveHeight.current
    return if (provided != Dp.Unspecified) provided
    else with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
}

/** Keeps the full window background while giving forms and reading surfaces a usable line length. */
fun Modifier.readableWidth(maxWidth: Dp = 840.dp): Modifier = layout { measurable, constraints ->
    val width = minOf(constraints.maxWidth, maxWidth.roundToPx())
    val child = measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, width), maxWidth = width))
    val outerWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else child.width
    layout(outerWidth, child.height) { child.placeRelative((outerWidth - child.width) / 2, 0) }
}

internal data class FoldRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** A separating/occluding hinge must never lie under a field or button. Prefer the larger usable panel. */
internal fun safeFoldRegion(width: Int, height: Int, fold: FoldRegion?): FoldRegion {
    val full = FoldRegion(0, 0, width, height)
    if (fold == null) return full
    return when {
        fold.top <= 0 && fold.bottom >= height && fold.left in 1 until width -> {
            val right = fold.right.coerceIn(fold.left, width)
            if (fold.left >= width - right) FoldRegion(0, 0, fold.left, height)
            else FoldRegion(right, 0, width, height)
        }
        fold.left <= 0 && fold.right >= width && fold.top in 1 until height -> {
            val bottom = fold.bottom.coerceIn(fold.top, height)
            if (fold.top >= height - bottom) FoldRegion(0, 0, width, fold.top)
            else FoldRegion(0, bottom, width, height)
        }
        else -> full
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
    else -> null
}

/** One stable composition across fold posture and route changes; no credentials are saved here. */
@Composable
fun AdaptiveAppLayout(wideContent: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val folds = remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it).map { info ->
            info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull {
                it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL
            }?.bounds?.let { FoldRegion(it.left, it.top, it.right, it.bottom) }
        } }
    }
    val fold = folds?.collectAsStateWithLifecycle(initialValue = null)?.value
    AdaptiveAppRegion(wideContent, fold, content)
}

@Composable
internal fun AdaptiveAppRegion(wideContent: Boolean, fold: FoldRegion?, content: @Composable () -> Unit) {
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    // The navigation graph must be composed in the same pass as its lifecycle observers.
    // BoxWithConstraints would defer it to measurement, after ON_START can navigate.
    val region = safeFoldRegion(windowSize.width, windowSize.height, fold)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val regionWidth = with(density) { (region.right - region.left).toDp() }
        CompositionLocalProvider(LocalAdaptiveWidth provides regionWidth,
            LocalAdaptiveHeight provides with(density) { (region.bottom - region.top).toDp() }) {
            Layout(content = {
                Box(Modifier.fillMaxSize().then(if (wideContent) Modifier else Modifier.readableWidth())) { content() }
            }) { measurables, constraints ->
                val measuredRegion = safeFoldRegion(constraints.maxWidth, constraints.maxHeight, fold)
                val child = measurables.single().measure(Constraints.fixed(measuredRegion.right - measuredRegion.left, measuredRegion.bottom - measuredRegion.top))
                layout(constraints.maxWidth, constraints.maxHeight) { child.place(measuredRegion.left, measuredRegion.top) }
            }
        }
    }
}

/**
 * Both slots keep their composition identity during resizing. An existing detail becomes
 * full width on a small window; the list stays mounted but is not placed, drawn or focusable.
 * No snapshot of item data is retained outside the existing authenticated composition.
 */
@Composable
fun AdaptiveListDetailLayout(
    compact: Boolean,
    listWidth: Dp,
    hasDetail: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable BoxScope.() -> Unit
) {
    Layout(modifier = Modifier.fillMaxSize(), content = {
        Box(Modifier.fillMaxSize().testTag("adaptive-list")) { list() }
        Box(Modifier.fillMaxSize().testTag("adaptive-detail").readableWidth()) { detail() }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val split = !compact && width >= 640.dp.roundToPx()
        val leftWidth = if (split) listWidth.roundToPx().coerceIn(280.dp.roundToPx(), width - 320.dp.roundToPx()) else width
        val rightWidth = if (split) width - leftWidth else width
        val left = measurables[0].measure(Constraints.fixed(leftWidth, height))
        val right = measurables[1].measure(Constraints.fixed(rightWidth, height))
        layout(width, height) {
            if (split || !hasDetail) left.placeRelative(0, 0)
            if (split || hasDetail) right.placeRelative(if (split) leftWidth else 0, 0)
        }
    }
}
