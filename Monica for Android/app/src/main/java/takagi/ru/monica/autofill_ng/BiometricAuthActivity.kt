package takagi.ru.monica.autofill_ng

import takagi.ru.monica.security.launchWithSecureStorage

import android.content.Context
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.DeveloperVerificationPolicy
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.BiometricHelper
import takagi.ru.monica.utils.SettingsManager

/**
 * Phase 8: 生物识别认证Activity
 * 
 * 用于在自动填充前进行生物识别验证
 * 验证成功后返回填充数据
 */
class BiometricAuthActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_PASSWORD_ID = "extra_password_id"
        const val EXTRA_USERNAME_FIELD_ID = "extra_username_field_id"
        const val EXTRA_PASSWORD_FIELD_ID = "extra_password_field_id"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_AUTH_INDEX = "extra_auth_index"
    }
    
    private lateinit var biometricHelper: BiometricHelper
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchWithSecureStorage { onSecureCreate(savedInstanceState) }
    }

    private fun onSecureCreate(savedInstanceState: Bundle?) {
        
        // 透明Activity，不显示UI
        setContentView(R.layout.activity_transparent)
        
        biometricHelper = BiometricHelper(this)
        val settingsManager = SettingsManager(this)

        lifecycleScope.launch {
            val settings = settingsManager.settingsFlow.first()
            if (DeveloperVerificationPolicy.bypassesIdentityVerification(settings)) {
                SecurityManager(applicationContext).unlockVaultForDeveloperBypass(settings.autoLockMinutes)
                handleAuthenticationSuccess()
                return@launch
            }
            val biometricEnabled = settings.biometricEnabled
            if (!biometricEnabled || !biometricHelper.isBiometricAvailable()) {
                Toast.makeText(
                    this@BiometricAuthActivity,
                    R.string.biometric_not_available,
                    Toast.LENGTH_SHORT
                ).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
                return@launch
            }
            startBiometricAuthentication()
        }
    }
    
    /**
     * 启动生物识别验证
     */
    private fun startBiometricAuthentication() {
        biometricHelper.authenticate(
            activity = this,
            onSuccess = {
                // 验证成功，返回填充数据
                android.util.Log.d("BiometricAuth", "Authentication successful")
                handleAuthenticationSuccess()
            },
            onError = { errorMessage ->
                // 验证错误
                android.util.Log.e("BiometricAuth", "Authentication error: $errorMessage")
                Toast.makeText(
                    this,
                    getString(R.string.biometric_auth_error, errorMessage),
                    Toast.LENGTH_SHORT
                ).show()
                
                setResult(Activity.RESULT_CANCELED)
                finish()
            },
            onFailed = {
                // 验证失败（生物识别不匹配）
                android.util.Log.w("BiometricAuth", "Authentication failed")
                Toast.makeText(
                    this,
                    R.string.biometric_auth_failed,
                    Toast.LENGTH_SHORT
                ).show()
                
                // 不立即关闭，允许用户重试
            }
        )
    }
    
    /**
     * 处理认证成功
     * 构建并返回填充数据
     */
    private fun handleAuthenticationSuccess() {
        lifecycleScope.launch {
            try {
                // 从Intent中获取密码ID和字段ID
                val passwordId = intent.getLongExtra(EXTRA_PASSWORD_ID, -1L)
                
                if (passwordId == -1L) {
                    android.util.Log.e("BiometricAuth", "Invalid password ID")
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                    return@launch
                }
                
                // 从数据库查询密码条目
                val database = PasswordDatabase.getDatabase(applicationContext)
                val repository = PasswordRepository(database.passwordEntryDao())
                val passwordEntry = repository.getPasswordEntryById(passwordId)
                
                if (passwordEntry == null) {
                    android.util.Log.e("BiometricAuth", "Password entry not found")
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                    return@launch
                }
                
                val usernameFieldId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_USERNAME_FIELD_ID, AutofillId::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<AutofillId>(EXTRA_USERNAME_FIELD_ID)
                }
                
                val passwordFieldId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PASSWORD_FIELD_ID, AutofillId::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<AutofillId>(EXTRA_PASSWORD_FIELD_ID)
                }
                
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                
                // 构建Dataset用于填充
                val datasetBuilder = Dataset.Builder()
                
                // 创建RemoteViews显示
                val presentation = RemoteViews(this@BiometricAuthActivity.packageName, android.R.layout.simple_list_item_1).apply {
                    setTextViewText(android.R.id.text1, passwordEntry.title.ifEmpty { passwordEntry.username })
                }
                
                // 填充用户名字段
                usernameFieldId?.let { id ->
                    datasetBuilder.setValue(
                        id,
                        AutofillValue.forText(passwordEntry.username),
                        presentation
                    )
                }
                
                // 填充密码字段
                passwordFieldId?.let { id ->
                    datasetBuilder.setValue(
                        id,
                        AutofillValue.forText(passwordEntry.password),
                        presentation
                    )
                }
                
                // 构建FillResponse
                val fillResponse = FillResponse.Builder()
                    .addDataset(datasetBuilder.build())
                    .build()
                
                // 返回结果
                val replyIntent = Intent().apply {
                    putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillResponse)
                }
                
                setResult(Activity.RESULT_OK, replyIntent)
                finish()
                
            } catch (e: Exception) {
                android.util.Log.e("BiometricAuth", "Error handling authentication success", e)
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // BiometricHelper会在Activity销毁时自动清理
    }
}



