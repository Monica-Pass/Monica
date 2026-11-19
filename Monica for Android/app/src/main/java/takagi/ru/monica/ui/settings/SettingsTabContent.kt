package takagi.ru.monica.ui

import takagi.ru.monica.ui.common.layout.readableWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import takagi.ru.monica.ui.screens.SettingsScreen
import takagi.ru.monica.viewmodel.SettingsViewModel

@Composable
internal fun SettingsTabContent(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    viewModel: SettingsViewModel,
    onResetPassword: () -> Unit,
    onSecurityQuestions: () -> Unit,
    onNavigateToMasterPasswordLocking: () -> Unit,
    onNavigateToSyncBackup: () -> Unit,
    onNavigateToAutofill: () -> Unit,
    onNavigateToPasskeySettings: () -> Unit,
    onNavigateToBottomNavSettings: () -> Unit,
    onNavigateToColorScheme: () -> Unit,
    onSecurityAnalysis: () -> Unit,
    onNavigateToDeveloperSettings: () -> Unit,
    onNavigateToPermissionManagement: () -> Unit,
    onNavigateToMonicaPlus: () -> Unit,
    onNavigateToExtensions: () -> Unit,
    onNavigateToPageCustomization: () -> Unit,
    onClearAllData: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit
) {
    val settingsListContent: @Composable () -> Unit = {
        SettingsScreen(
            viewModel = viewModel,
            onNavigateBack = {},
            onResetPassword = onResetPassword,
            onSecurityQuestions = onSecurityQuestions,
            onNavigateToMasterPasswordLocking = onNavigateToMasterPasswordLocking,
            onNavigateToSyncBackup = onNavigateToSyncBackup,
            onNavigateToAutofill = onNavigateToAutofill,
            onNavigateToPasskeySettings = onNavigateToPasskeySettings,
            onNavigateToBottomNavSettings = onNavigateToBottomNavSettings,
            onNavigateToColorScheme = onNavigateToColorScheme,
            onSecurityAnalysis = onSecurityAnalysis,
            onNavigateToDeveloperSettings = onNavigateToDeveloperSettings,
            onNavigateToPermissionManagement = onNavigateToPermissionManagement,
            onNavigateToMonicaPlus = onNavigateToMonicaPlus,
            onNavigateToExtensions = onNavigateToExtensions,
            onNavigateToPageCustomization = onNavigateToPageCustomization,
            onClearAllData = onClearAllData,
            showTopBar = false
        )
    }

    Box(Modifier.fillMaxSize().readableWidth()) { settingsListContent() }
}
