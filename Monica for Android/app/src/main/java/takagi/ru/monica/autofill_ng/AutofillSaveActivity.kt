package takagi.ru.monica.autofill_ng

import android.content.Context
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.ui.AppInfo
import takagi.ru.monica.autofill_ng.ui.AutofillScaffold
import takagi.ru.monica.autofill_ng.ui.colorizePasswordString
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.addOrReplaceLinkedAppBinding
import takagi.ru.monica.data.isLinkedToApp
import takagi.ru.monica.mdbx.MdbxDiagLogger
import takagi.ru.monica.repository.MdbxRepository
import takagi.ru.monica.repository.MdbxRepositoryFactory
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.SettingsManager
import java.util.Date
import takagi.ru.monica.ui.components.OutlinedTextField

/**
 * 自动填充保存密码Activity
 * 当用户提交表单时，显示对话框询问是否保存密码
 */
class AutofillSaveActivity : ComponentActivity() {
    
    companion object {
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_WEBSITE = "extra_website"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }
    
    private lateinit var passwordRepository: PasswordRepository
    private lateinit var securityManager: SecurityManager
    private lateinit var database: PasswordDatabase
    private lateinit var settingsManager: SettingsManager
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化 Repository
        database = PasswordDatabase.getDatabase(applicationContext)
        securityManager = SecurityManager(applicationContext)
        settingsManager = SettingsManager(applicationContext)
        val mdbxRepository: MdbxRepository = MdbxRepositoryFactory.create(
            context = applicationContext,
            database = database,
            securityManager = securityManager
        )
        passwordRepository = PasswordRepository(
            passwordEntryDao = database.passwordEntryDao(),
            categoryDao = database.categoryDao(),
            bitwardenFolderDao = database.bitwardenFolderDao(),
            secureItemDao = database.secureItemDao(),
            passkeyDao = database.passkeyDao(),
            passwordArchiveSyncMetaDao = database.passwordArchiveSyncMetaDao(),
            passwordHistoryDao = database.passwordHistoryDao(),
            mdbxRepository = mdbxRepository,
        )
        
        // 获取传递的数据
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val website = intent.getStringExtra(EXTRA_WEBSITE) ?: ""
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        
        setContent {
            MonicaTheme {
                SavePasswordDialog(
                    username = username,
                    password = password,
                    website = website,
                    packageName = packageName,
                    onSave = { title, updatedUsername, updatedPassword, updatedWebsite, note ->
                        savePassword(title, updatedUsername, updatedPassword, updatedWebsite, note)
                    },
                    onCancel = {
                        setResult(RESULT_CANCELED)
                        finish()
                    },
                    onNeverForThisSite = {
                        blockCurrentTarget(
                            packageName = packageName,
                            website = website,
                        )
                    }
                )
            }
        }
    }
    
    private fun savePassword(
        title: String,
        username: String,
        password: String,
        website: String,
        notes: String
    ) {
        lifecycleScope.launch {
            try {
                val initialTarget = resolveInitialTarget()
                // 获取包名
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                
                // 获取应用名称
                val appName = try {
                    if (packageName.isNotBlank()) {
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } else {
                        ""
                    }
                } catch (e: Exception) {
                    ""
                }
                
                // 检查是否已存在相同的密码
                val existingPasswords = passwordRepository.getAllPasswordEntries().first().filterNot { it.isKeyCredential() }
                val encryptedPassword = securityManager.encryptData(password)
                val existing = existingPasswords.firstOrNull { entry ->
                    // 优先匹配包名
                    if (packageName.isNotBlank() && entry.isLinkedToApp(packageName) && 
                        entry.username.equals(username, ignoreCase = true)) {
                        true
                    }
                    // 其次匹配website
                    else {
                        entry.website.equals(website, ignoreCase = true) && 
                        entry.username.equals(username, ignoreCase = true)
                    }
                }
                
                if (existing != null) {
                    // 更新现有密码
                    val updated = existing.copy(
                        password = encryptedPassword,
                        notes = notes,
                        appPackageName = if (packageName.isNotBlank()) {
                            addOrReplaceLinkedAppBinding(
                                existing.appPackageName,
                                existing.appName,
                                packageName,
                                appName
                            ).first
                        } else {
                            existing.appPackageName
                        },
                        appName = if (packageName.isNotBlank()) {
                            addOrReplaceLinkedAppBinding(
                                existing.appPackageName,
                                existing.appName,
                                packageName,
                                appName
                            ).second
                        } else {
                            existing.appName
                        },
                        updatedAt = Date()
                    )
                    passwordRepository.updatePasswordEntry(updated)
                    
                    // 记录更新操作
                    takagi.ru.monica.utils.OperationLogger.logUpdate(
                        itemType = takagi.ru.monica.data.OperationLogItemType.PASSWORD,
                        itemId = updated.id,
                        itemTitle = updated.title,
                        changes = listOf(takagi.ru.monica.utils.FieldChange("密码", "***", "***"))
                    )
                } else {
                    // 创建新密码条目
                    val newEntry = PasswordEntry(
                        title = title.ifBlank { appName.ifBlank { website } },
                        username = username,
                        password = encryptedPassword,
                        website = website,
                        notes = notes,
                        appPackageName = packageName,
                        appName = appName,
                        createdAt = Date(),
                        updatedAt = Date()
                    ).withAutofillSaveInitialTarget(initialTarget)
                    val newId = if (initialTarget.isMdbx) {
                        passwordRepository.insertPasswordEntries(listOf(newEntry)).singleOrNull()
                            ?: throw IllegalStateException("MDBX autofill save did not create exactly one password row")
                    } else {
                        passwordRepository.insertPasswordEntry(newEntry)
                    }
                    MdbxDiagLogger.append(
                        "[MDBX][autofill-save-complete] source=legacy target=${initialTarget.diagnosticLabel()} roomId=$newId"
                    )
                    
                    // 记录创建操作
                    takagi.ru.monica.utils.OperationLogger.logCreate(
                        itemType = takagi.ru.monica.data.OperationLogItemType.PASSWORD,
                        itemId = newId,
                        itemTitle = newEntry.title
                    )
                }
                
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                android.util.Log.e("AutofillSave", "Error saving password", e)
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    private suspend fun resolveInitialTarget(): AutofillSaveInitialTarget {
        val settingsSnapshot = settingsManager.settingsFlow.first()
        val mdbxDatabases = database.localMdbxDatabaseDao().getAvailableDatabasesSnapshot()
        return resolveAutofillSaveInitialTarget(settingsSnapshot, mdbxDatabases).also { target ->
            MdbxDiagLogger.append(
                "[MDBX][autofill-save-open] source=legacy target=${target.diagnosticLabel()} mdbxDatabases=${target.mdbxDatabasesFallback.size}"
            )
        }
    }

    private fun blockCurrentTarget(packageName: String, website: String) {
        lifecycleScope.launch {
            runCatching {
                val autofillPreferences = AutofillPreferences(applicationContext)
                autofillPreferences.addSaveBlockedTarget(
                    packageName = packageName.takeIf { it.isNotBlank() },
                    webDomain = website.takeIf { it.isNotBlank() },
                )
            }
            setResult(RESULT_CANCELED)
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavePasswordDialog(
    username: String,
    password: String,
    website: String,
    packageName: String,
    onSave: (title: String, username: String, password: String, website: String, notes: String) -> Unit,
    onCancel: () -> Unit,
    onNeverForThisSite: () -> Unit
) {
    val context = LocalContext.current
    val defaultNotes = stringResource(R.string.autofill_saved_via)
    
    // 获取应用名称
    val appName = remember(packageName) {
        try {
            if (packageName.isNotBlank()) {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
    
    // 优先使用应用名称，其次使用website
    val defaultTitle = appName.ifBlank { website.takeIf { it.isNotBlank() } ?: packageName }
    
    var title by remember { mutableStateOf(defaultTitle) }
    var editedUsername by remember { mutableStateOf(username) }
    var editedPassword by remember { mutableStateOf(password) }
    var editedWebsite by remember { mutableStateOf(website) }
    var notes by remember { mutableStateOf(defaultNotes) }
    var showAdvancedOptions by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    
    // 参考 Keyguard 的 AutofillScaffold 风格
    takagi.ru.monica.autofill_ng.ui.AutofillScaffold(
        topBar = {
            // 顶部信息头 - 参考 Keyguard 的 AutofillSaveActivity
            SavePasswordHeader(
                title = stringResource(R.string.autofill_save_password),
                appName = appName.ifBlank { defaultTitle },
                username = username,
                password = password,
                website = website,
                packageName = packageName,
                onClose = onCancel
            )
        }
    ) {
        // 内容区域
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 用户名输入
            OutlinedTextField(
                value = editedUsername,
                onValueChange = { editedUsername = it },
                label = { Text(stringResource(R.string.autofill_username)) },
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            
            // 密码输入 - 带彩色显示和可见性切换
            OutlinedTextField(
                value = editedPassword,
                onValueChange = { editedPassword = it },
                label = { Text(stringResource(R.string.autofill_password)) },
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null)
                },
                trailingIcon = {
                    Row {
                        // 可见性切换
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                        // 生成密码按钮
                        IconButton(onClick = { 
                            // TODO: 调用密码生成器
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.generate_password))
                        }
                    }
                },
                visualTransformation = if (passwordVisible) 
                    androidx.compose.ui.text.input.VisualTransformation.None 
                else 
                    androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            
            // 高级选项折叠面板
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                onClick = { showAdvancedOptions = !showAdvancedOptions }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (showAdvancedOptions) 
                            stringResource(R.string.autofill_hide_advanced)
                        else 
                            stringResource(R.string.autofill_show_advanced),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Icon(
                        imageVector = if (showAdvancedOptions) 
                            Icons.Default.ExpandLess 
                        else 
                            Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
            }
            
            // 高级选项内容
            androidx.compose.animation.AnimatedVisibility(visible = showAdvancedOptions) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 标题输入
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.autofill_title)) },
                        leadingIcon = {
                            Icon(Icons.Default.Title, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    // 网站输入
                    OutlinedTextField(
                        value = editedWebsite,
                        onValueChange = { editedWebsite = it },
                        label = { Text(stringResource(R.string.autofill_website_app)) },
                        leadingIcon = {
                            Icon(Icons.Default.Language, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    // 备注输入
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text(stringResource(R.string.autofill_notes)) },
                        leadingIcon = {
                            Icon(Icons.Default.Note, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 操作按钮区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 取消按钮
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.autofill_cancel))
                }
                
                // 保存按钮
                Button(
                    onClick = {
                        onSave(
                            title,
                            editedUsername,
                            editedPassword,
                            editedWebsite,
                            notes
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.autofill_save_button))
                }
            }
            
            // 从不为此网站保存
            TextButton(
                onClick = onNeverForThisSite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Block, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.autofill_never_for_site),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * 保存密码头部组件
 * 参考 Keyguard 的 AutofillSaveActivity 顶部设计
 */
@Composable
private fun SavePasswordHeader(
    title: String,
    appName: String,
    username: String,
    password: String,
    website: String,
    packageName: String,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // 标题行 + 关闭按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            
            TextButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
        Spacer(modifier = Modifier.height(8.dp))
        
        // 捕获的凭据信息预览
        if (username.isNotEmpty()) {
            SaveInfoRow(
                title = stringResource(R.string.autofill_username),
                value = username
            )
        }
        
        if (password.isNotEmpty()) {
            SaveInfoRow(
                title = stringResource(R.string.autofill_password),
                value = takagi.ru.monica.autofill_ng.ui.colorizePasswordString(password)
            )
        }
        
        // App/Website 信息 - 参考 Keyguard 使用更小的字体
        if (packageName.isNotEmpty() || website.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            
            if (packageName.isNotEmpty()) {
                SaveInfoRow(
                    title = stringResource(R.string.associated_app),
                    value = packageName,
                    isSecondary = true
                )
            }
            
            if (website.isNotEmpty()) {
                SaveInfoRow(
                    title = stringResource(R.string.autofill_website_app),
                    value = website,
                    isSecondary = true
                )
            }
        }
    }
}

@Composable
private fun SaveInfoRow(
    title: String,
    value: CharSequence,
    isSecondary: Boolean = false
) {
    val textColor = if (isSecondary) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    val textStyle = if (isSecondary) {
        MaterialTheme.typography.bodySmall
    } else {
        MaterialTheme.typography.bodyMedium
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = title,
            style = textStyle,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.widthIn(max = 80.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        when (value) {
            is String -> Text(
                text = value,
                style = textStyle,
                color = textColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            is androidx.compose.ui.text.AnnotatedString -> Text(
                text = value,
                style = textStyle,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}



