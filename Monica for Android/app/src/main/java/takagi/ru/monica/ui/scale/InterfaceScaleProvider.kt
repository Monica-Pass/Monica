package takagi.ru.monica.ui.scale

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import takagi.ru.monica.data.InterfaceScale
import takagi.ru.monica.ui.animation.AnimationUtils
import takagi.ru.monica.ui.components.LocalExpansionAnimationsEnabled

@Composable
fun ProvideMonicaInterfaceScale(
    scalePercent: Int,
    animationsEnabled: Boolean = LocalExpansionAnimationsEnabled.current,
    content: @Composable () -> Unit
) {
    val baseDensity = LocalDensity.current
    val targetDensity = InterfaceScale.calculateDensity(baseDensity.density, scalePercent)
    // Animate only the density value: retain the same composition and its current content.
    // Initial composition starts at the saved scale; disabling motion also ends an active tween.
    // Compose applies the system MotionDurationScale, including its zero-duration setting.
    val density by if (animationsEnabled) {
        animateFloatAsState(
            targetValue = targetDensity,
            animationSpec = tween(AnimationUtils.DURATION_MEDIUM, easing = AnimationUtils.standardEasing),
            label = "monicaInterfaceDensity"
        )
    } else {
        rememberUpdatedState(targetDensity)
    }
    val appDensity = remember(density, baseDensity.fontScale) {
        Density(density = density, fontScale = baseDensity.fontScale)
    }

    CompositionLocalProvider(LocalDensity provides appDensity) {
        content()
    }
}
