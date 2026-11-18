package takagi.ru.monica.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.util.function.Consumer

@Composable
internal fun BlurredDialogSurface(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val dialogDensity = LocalDensity.current
    val dialogContext = LocalContext.current
    val dialogDirection = LocalLayoutDirection.current
    val maximumHeight = (configuration.screenHeightDp.dp - 32.dp).coerceAtLeast(96.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.Inherit),
    ) {
        CompositionLocalProvider(
            LocalDensity provides dialogDensity,
            LocalConfiguration provides configuration,
            LocalContext provides dialogContext,
            LocalLayoutDirection provides dialogDirection,
        ) {
            DialogBackdropBlur()
            Surface(
                modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp)
                    .fillMaxWidth().heightIn(max = maximumHeight).then(modifier),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                content()
            }
        }
    }
}

/** System Gaussian blur without capturing or retaining any underlying vault pixels. */
@Composable
private fun DialogBackdropBlur() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (Build.VERSION.SDK_INT >= 31 && window != null) {
            val manager = context.getSystemService(WindowManager::class.java)
            val oldRadius = window.attributes.blurBehindRadius
            val oldDim = window.attributes.dimAmount
            val hadBlur = window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
            fun apply(enabled: Boolean) {
                val params = window.attributes
                params.blurBehindRadius = if (enabled) (24 * context.resources.displayMetrics.density).toInt() else 0
                params.dimAmount = if (enabled) 0.24f else 0.48f
                window.attributes = params
                if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
            val listener = Consumer<Boolean> { apply(it) }
            apply(manager.isCrossWindowBlurEnabled)
            manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
            onDispose {
                manager.removeCrossWindowBlurEnabledListener(listener)
                val params = window.attributes
                params.blurBehindRadius = oldRadius
                params.dimAmount = oldDim
                window.attributes = params
                if (hadBlur) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
        } else onDispose { }
    }
}
