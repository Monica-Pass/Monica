package takagi.ru.monica.autofill_ng

import takagi.ru.monica.security.launchWithSecureStorage

import android.content.Context
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import takagi.ru.monica.ui.components.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.ui.components.MonicaPasswordDialogAuthScreen
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.DeveloperVerificationPolicy
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.AppLauncherIconManager
import takagi.ru.monica.utils.BiometricAuthHelper
import takagi.ru.monica.utils.SettingsManager

/**
 * 自动填充身份验证Activity
 * 
 * 在用户选择密码后,显示生物识别验证或密码输入对话框
 * 验证成功后返回包含密码数据的Dataset
 */
class AutofillAuthenticationActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "AutofillAuth"
        
        // Intent extras
        const val EXTRA_PASSWORD_ENTRY_ID = "password_entry_id"
        const val EXTRA_USERNAME_VALUE = "username_value"
        const val EXTRA_PASSWORD_VALUE = "password_value"
        const val EXTRA_AUTOFILL_IDS = "autofill_ids"
        const val EXTRA_FIELD_TYPES = "field_types"  // "username" 或 "password"
    }
    
    private lateinit var biometricAuthHelper: BiometricAuthHelper
    private lateinit var securityManager: SecurityManager
    private lateinit var settingsManager: SettingsManager
    
    // 自动填充数据
    private var passwordEntryId: Long = -1
    private var usernameValue: String? = null
    private var passwordValue: String? = null
    private var autofillIds: ArrayList<AutofillId>? = null
    private var fieldTypes: ArrayList<String>? = null
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchWithSecureStorage { onSecureCreate(savedInstanceState) }
    }

    private fun onSecureCreate(savedInstanceState: Bundle?) {
        
        Log.d(TAG, "AutofillAuthenticationActivity created")
        
        // 设置为透明背景的对话框样式
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        biometricAuthHelper = BiometricAuthHelper(this)
        securityManager = SecurityManager(this)
        settingsManager = SettingsManager(this)
        
        // 获取传递的数据
        passwordEntryId = intent.getLongExtra(EXTRA_PASSWORD_ENTRY_ID, -1)
        usernameValue = intent.getStringExtra(EXTRA_USERNAME_VALUE)
        passwordValue = intent.getStringExtra(EXTRA_PASSWORD_VALUE)
        autofillIds = intent.getParcelableArrayListExtra(EXTRA_AUTOFILL_IDS)
        fieldTypes = intent.getStringArrayListExtra(EXTRA_FIELD_TYPES)
        
        Log.d(TAG, "Password entry ID: $passwordEntryId")
        Log.d(TAG, "Autofill IDs count: ${autofillIds?.size}")
        Log.d(TAG, "Field types: $fieldTypes")

        lifecycleScope.launch {
            val settings = settingsManager.settingsFlow.first()
            if (DeveloperVerificationPolicy.bypassesIdentityVerification(settings)) {
                securityManager.unlockVaultForDeveloperBypass(settings.autoLockMinutes)
                onAuthenticationSuccess()
            } else if (settings.biometricEnabled && biometricAuthHelper.isBiometricAvailable()) {
                showBiometricAuthentication()
            } else {
                // 降级到密码验证
                showPasswordAuthentication()
            }
        }
    }
    
    /**
     * 显示生物识别验证
     */
    private fun showBiometricAuthentication() {
        Log.d(TAG, "Showing biometric authentication")
        
        val executor = ContextCompat.getMainExecutor(this)
        
        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    if (!takagi.ru.monica.utils.BiometricAuthenticationPolicy.acceptsResult(
                            result.authenticationType, android.os.Build.VERSION.SDK_INT)) {
                        showPasswordAuthentication()
                        return
                    }
                    Log.d(TAG, "Biometric authentication succeeded")
                    onAuthenticationSuccess()
                }
                
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.d(TAG, "Biometric authentication error: $errorCode - $errString")
                    
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                            showPasswordAuthentication()
                        }
                        BiometricPrompt.ERROR_USER_CANCELED -> {
                            onAuthenticationCancelled()
                        }
                        else -> {
                            showPasswordAuthentication()
                        }
                    }
                }
                
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.d(TAG, "Biometric authentication failed, can retry")
                }
            }
        )
        
        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.autofill_auth_title))
            .setSubtitle(getString(R.string.autofill_auth_subtitle))
            .setDescription(getString(R.string.autofill_auth_description))
            .setNegativeButtonText(getString(R.string.use_password))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
        AppLauncherIconManager.applyBiometricPromptBranding(this, promptInfoBuilder)
        val promptInfo = promptInfoBuilder.build()
        
        biometricPrompt.authenticate(promptInfo)
    }
    
    /**
     * 显示密码验证对话框
     */
    private fun showPasswordAuthentication() {
        Log.d(TAG, "Showing password authentication")
        setContent {
            MonicaPasswordDialogAuthScreen(
                settingsFlow = settingsManager.settingsFlow,
                appName = getString(R.string.app_name),
                title = getString(R.string.verify_identity),
                subtitle = getString(R.string.autofill_auth_subtitle),
                passwordLabel = getString(R.string.master_password),
                description = getString(R.string.enter_master_password),
                confirmText = getString(R.string.confirm),
                cancelText = getString(R.string.cancel),
                emptyError = getString(R.string.current_password_required),
                unsupportedCharacterError = getString(R.string.error_password_contains_unsupported_characters),
                minLengthError = getString(R.string.error_password_too_short),
                incorrectError = getString(R.string.password_incorrect),
                verifyPassword = { input, attempt -> securityManager.verifyMasterPassword(input, attempt) },
                onSuccess = { onAuthenticationSuccess() },
                onCancel = { onAuthenticationCancelled() }
            )
        }
    }
    
    /**
     * 验证成功,返回填充数据
     */
    private fun onAuthenticationSuccess() {
        Log.d(TAG, "Authentication success, creating dataset")
        
        try {
            if (autofillIds == null || fieldTypes == null || autofillIds!!.size != fieldTypes!!.size) {
                Log.e(TAG, "Invalid autofill data")
                onAuthenticationFailed("无效的填充数据")
                return
            }
            
            // 创建 Dataset
            val datasetBuilder = Dataset.Builder()
            
            for (i in autofillIds!!.indices) {
                val autofillId = autofillIds!![i]
                val fieldType = fieldTypes!![i]
                
                val value = when (fieldType) {
                    "username" -> usernameValue
                    "password" -> passwordValue
                    else -> null
                }
                
                if (value != null) {
                    datasetBuilder.setValue(autofillId, AutofillValue.forText(value))
                    Log.d(TAG, "Set value for field type: $fieldType")
                }
            }
            
            val dataset = datasetBuilder.build()
            
            // 返回结果
            val replyIntent = Intent().apply {
                putExtra(android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
            }
            
            setResult(Activity.RESULT_OK, replyIntent)
            Log.d(TAG, "Dataset created and result set")
            finish()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create dataset", e)
            onAuthenticationFailed("创建填充数据失败: ${e.message}")
        }
    }
    
    /**
     * 验证失败
     */
    private fun onAuthenticationFailed(message: String) {
        Log.d(TAG, "Authentication failed: $message")
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
    
    /**
     * 用户取消验证
     */
    private fun onAuthenticationCancelled() {
        Log.d(TAG, "Authentication cancelled by user")
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}

@Composable
private fun AutofillPasswordAuthScreen(
    settingsFlow: Flow<AppSettings>,
    verifyPassword: (String) -> Boolean,
    onSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    val settings by settingsFlow.collectAsState(initial = AppSettings(biometricEnabled = false))
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MonicaTheme(
        darkTheme = darkTheme,
        colorScheme = settings.colorScheme,
        customPrimaryColor = settings.customPrimaryColor,
        customSecondaryColor = settings.customSecondaryColor,
        customTertiaryColor = settings.customTertiaryColor,
        customNeutralColor = settings.customNeutralColor,
        customNeutralVariantColor = settings.customNeutralVariantColor
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            PasswordVerificationCard(
                verifyPassword = verifyPassword,
                onSuccess = onSuccess,
                onCancel = onCancel
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PasswordVerificationCard(
    verifyPassword: (String) -> Boolean,
    onSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    val appName = stringResource(id = R.string.app_name)
    val title = stringResource(id = R.string.verify_identity)
    val subtitle = stringResource(id = R.string.autofill_auth_subtitle)
    val passwordLabel = stringResource(id = R.string.master_password)
    val description = stringResource(id = R.string.enter_master_password)
    val confirmText = stringResource(id = R.string.confirm)
    val cancelText = stringResource(id = R.string.cancel)
    val emptyError = stringResource(id = R.string.current_password_required)
    val unsupportedCharacterError = stringResource(id = R.string.error_password_contains_unsupported_characters)
    val minLengthError = stringResource(id = R.string.error_password_too_short)
    val incorrectError = stringResource(id = R.string.password_incorrect)

    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .wrapContentHeight(),
        shape = RoundedCornerShape(28.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(
            defaultElevation = 8.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = appName,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            takagi.ru.monica.ui.components.MasterPasswordTextField(
                value = password,
                onValueChange = { input ->
                    password = input
                    errorMessage = null
                },
                onUnsupportedCharacterAttempt = {
                    errorMessage = unsupportedCharacterError
                },
                label = { Text(passwordLabel) },
                placeholder = { Text(description) },
                visible = passwordVisible,
                onVisibilityChange = { passwordVisible = it },
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                isError = errorMessage != null,
                supportingText = if (errorMessage != null) {
                    { Text(text = errorMessage ?: "") }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    when {
                        takagi.ru.monica.security.MasterPasswordPolicy.isEmpty(password) -> errorMessage = emptyError
                        !takagi.ru.monica.security.MasterPasswordPolicy.meetsMinLength(password) -> errorMessage = minLengthError
                        !verifyPassword(password) -> errorMessage = incorrectError
                        else -> {
                            keyboardController?.hide()
                            onSuccess()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = password.isNotEmpty(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(text = confirmText, modifier = Modifier.padding(vertical = 4.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(text = cancelText)
            }
        }
    }
}



