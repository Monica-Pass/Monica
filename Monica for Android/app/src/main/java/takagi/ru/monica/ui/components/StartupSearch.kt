package takagi.ru.monica.ui.components

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

internal val LocalStartupSearchRequest = staticCompositionLocalOf<(() -> Boolean)?> { null }

/** Only the first main page can consume this request; navigation never rearms it. */
@Composable
internal fun StartupSearchProvider(
    enabled: Boolean,
    settingsLoaded: Boolean,
    tabKey: String,
    content: @Composable () -> Unit,
) {
    var initialized by rememberSaveable { mutableStateOf(false) }
    var consumed by rememberSaveable { mutableStateOf(false) }
    var startupTabKey by rememberSaveable { mutableStateOf<String?>(null) }
    val currentTabKey by rememberUpdatedState(tabKey)
    val currentEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(settingsLoaded) {
        if (settingsLoaded && !initialized) {
            // Let the persisted default-tab configuration settle before targeting a search bar.
            withFrameNanos { }
            startupTabKey = currentTabKey
            consumed = !currentEnabled
            initialized = true
        }
    }
    LaunchedEffect(tabKey, initialized) {
        if (initialized && tabKey != startupTabKey) consumed = true
    }
    val request: (() -> Boolean)? = remember(initialized, consumed, tabKey, startupTabKey) {
        if (initialized && !consumed && tabKey == startupTabKey) {
            {
                if (consumed) false else {
                    consumed = true
                    true
                }
            }
        } else null
    }
    CompositionLocalProvider(LocalStartupSearchRequest provides request, content = content)
}
