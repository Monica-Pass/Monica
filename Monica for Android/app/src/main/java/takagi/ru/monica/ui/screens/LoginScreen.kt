package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import takagi.ru.monica.ui.components.PasswordVerificationContent
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel
import takagi.ru.monica.ui.rememberUiSecurityManager

@Composable
fun LoginScreen(
    viewModel: PasswordViewModel,
    settingsViewModel: SettingsViewModel? = null,
    onFirstFrameRendered: (() -> Unit)? = null,
    onForgotPassword: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val passwordSecurityManager = rememberUiSecurityManager()
    
    val isFirstTime = !viewModel.isMasterPasswordSet()
    
    // 获取设置
    val settings = settingsViewModel?.settings?.collectAsState()?.value
    val disablePasswordVerification = settings?.disablePasswordVerification ?: false
    val biometricEnabled = settings?.biometricEnabled ?: false
    val autoLockMinutes = settings?.autoLockMinutes ?: 5

    LaunchedEffect(Unit) {
        withFrameNanos { }
        onFirstFrameRendered?.invoke()
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        PasswordVerificationContent(
            modifier = Modifier.fillMaxSize(),
            isFirstTime = isFirstTime,
            disablePasswordVerification = disablePasswordVerification,
            biometricEnabled = biometricEnabled,
            autoLockMinutes = autoLockMinutes,
            onVerifyPassword = { password -> 
                viewModel.authenticate(password)
            },
            onSetPassword = { password ->
                viewModel.setMasterPassword(password)
            },
            onSuccess = {
                viewModel.restoreAuthenticatedUiState()
            },
            onForgotPassword = onForgotPassword,
            passwordSecurityManager = passwordSecurityManager,
        )
    }
}
