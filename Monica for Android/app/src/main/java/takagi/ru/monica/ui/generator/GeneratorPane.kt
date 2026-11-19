package takagi.ru.monica.ui

import takagi.ru.monica.ui.common.layout.AdaptiveListDetailLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import takagi.ru.monica.ui.screens.GeneratorScreen
import takagi.ru.monica.viewmodel.GeneratorType
import takagi.ru.monica.viewmodel.GeneratorViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

@Composable
internal fun GeneratorPane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    generatorViewModel: GeneratorViewModel,
    passwordViewModel: PasswordViewModel,
    externalRefreshRequestKey: Int,
    onRefreshRequestConsumed: () -> Unit,
    selectedGenerator: GeneratorType,
    generatedValue: String,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {}
) {
    val hasAdaptiveDetail = false
    AdaptiveListDetailLayout(
        compact = isCompactWidth,
        listWidth = wideListPaneWidth,
        hasDetail = hasAdaptiveDetail,
        list = { GeneratorScreen(
                    onNavigateBack = {},
                    viewModel = generatorViewModel,
                    passwordViewModel = passwordViewModel,
                    externalRefreshRequestKey = externalRefreshRequestKey,
                    onRefreshRequestConsumed = onRefreshRequestConsumed,
                    useExternalRefreshFab = true,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                ) },
        detail = { GeneratorDetailPane(
                    selectedGenerator = selectedGenerator,
                    generatedValue = generatedValue,
                    modifier = Modifier.fillMaxSize()
                ) }
    )
}
