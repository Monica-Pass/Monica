package takagi.ru.monica.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import takagi.ru.monica.R

/** Page keys must be scalar phases, never a draft or a model containing secrets. */
@Composable
internal fun MonicaPageContent(
    pageKey: Any?,
    modifier: Modifier = Modifier,
    animateInitial: Boolean = false,
    animate: Boolean = true,
    content: @Composable () -> Unit,
) {
    var firstComposition by remember { mutableStateOf(true) }
    SideEffect { firstComposition = false }
    // Removing the previous key disposes its content immediately. Only the current
    // page enters; no outgoing password field or Send plaintext is retained to fade.
    key(pageKey, animate) {
        val visible = remember {
            MutableTransitionState(!animate || (firstComposition && !animateInitial)).apply { targetState = true }
        }
        AnimatedVisibility(visibleState = visible, modifier = modifier,
            enter = easyNotesScreenEnter(), exit = ExitTransition.None) {
            content()
        }
    }
}

/** Compose supplies entry motion; the window supplies exit without delaying dismissal. */
@Composable
internal fun MonicaPageDialogMotion() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.setWindowAnimations(R.style.MonicaPageDialogAnimation)
    }
}
