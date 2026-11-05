package takagi.ru.monica.autofill_ng.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.utils.ClipboardUtils

/**
 * 内嵌密码详情内容
 * 
 * 在自动填充场景中使用的简化版密码详情页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InlinePasswordDetailContent(
    password: PasswordEntry,
    securityManager: SecurityManager,
    onAutofill: () -> Unit,
    onAutofillAndSaveUri: () -> Unit,
    onBack: () -> Unit,
    authorizationGeneration: Long,
    canPublishCredentials: () -> Boolean,
    modifier: Modifier = Modifier,
    onAccessibilityFill: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var passwordVisible by remember(password, authorizationGeneration) { mutableStateOf(false) }
    val currentGeneration by SecurityManager.dataReadGeneration.collectAsState()
    var secrets by remember(password, securityManager, authorizationGeneration) { mutableStateOf<Pair<String, String>?>(null) }
    var loading by remember(password, securityManager, authorizationGeneration) { mutableStateOf(true) }
    LaunchedEffect(password, securityManager, authorizationGeneration, currentGeneration) {
        secrets = null
        loading = true
        if (currentGeneration == authorizationGeneration) {
            secrets = BoundedSecureDataRead.read(securityManager, authorizationGeneration) {
                val username = if (password.username.contains("==") && password.username.length > 20) {
                    securityManager.decryptData(password.username)
                } else password.username
                ensureActive()
                username to securityManager.decryptData(password.password)
            }
        }
        loading = false
    }
    val readable = currentGeneration == authorizationGeneration && secrets != null
    val decryptedUsername = if (readable) secrets!!.first else ""
    val decryptedPassword = if (readable) secrets!!.second else ""
    fun canUseSecrets() = readable && canPublishCredentials() && securityManager.isDataReadCurrent(authorizationGeneration)
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 顶部返回按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                text = stringResource(R.string.password_details),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium
            )
        }
        
        // 标题卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 首字母头像
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = password.title.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = password.title.ifEmpty { decryptedUsername },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (password.website.isNotEmpty()) {
                        Text(
                            text = password.website,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        
        // 用户名字段
        DetailField(
            label = stringResource(R.string.autofill_username),
            value = decryptedUsername,
            icon = Icons.Outlined.Person,
            onCopy = if (readable) ({ if (canUseSecrets()) copyToClipboard(context, context.getString(R.string.autofill_username), decryptedUsername) }) else null
        )
        
        // 密码字段
        DetailField(
            label = stringResource(R.string.autofill_password),
            value = decryptedPassword,
            icon = Icons.Outlined.Lock,
            isPassword = true,
            passwordVisible = passwordVisible,
            onToggleVisibility = if (readable) ({ if (canUseSecrets()) passwordVisible = !passwordVisible }) else null,
            onCopy = if (readable) ({ if (canUseSecrets()) copyToClipboard(context, context.getString(R.string.autofill_password), decryptedPassword) }) else null
        )
        if (!readable) {
            Text(stringResource(if (loading) R.string.loading else R.string.bitwarden_sync_issue_reason_decryption))
        }
        
        // 网站字段
        if (password.website.isNotEmpty()) {
            DetailField(
                label = stringResource(R.string.autofill_website_app),
                value = password.website,
                icon = Icons.Outlined.Language,
                onCopy = if (readable) ({ if (canUseSecrets()) copyToClipboard(context, context.getString(R.string.autofill_website_app), password.website) }) else null
            )
        }
        
        // App 信息
        if (!password.appPackageName.isNullOrEmpty()) {
            DetailField(
                label = stringResource(R.string.associated_app),
                value = password.appName ?: password.appPackageName,
                icon = Icons.Outlined.Android,
                onCopy = null
            )
        }
        
        // 备注
        if (password.notes.isNotEmpty()) {
            DetailField(
                label = stringResource(R.string.notes),
                value = password.notes,
                icon = Icons.Outlined.Notes,
                allowMultilineValue = true,
                onCopy = if (readable) ({ if (canUseSecrets()) copyToClipboard(context, context.getString(R.string.notes), password.notes) }) else null
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 操作按钮
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { if (canUseSecrets()) onAutofill() },
                enabled = readable,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.autofill))
            }
            
            if (onAccessibilityFill != null) {
                FilledTonalButton(onClick = { if (canUseSecrets()) onAccessibilityFill() }, enabled = readable, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AccessibilityNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.autofill_accessibility_fill))
                }
            }
            OutlinedButton(
                onClick = { if (canUseSecrets()) onAutofillAndSaveUri() },
                enabled = readable,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.autofill_and_save_uri))
            }
        }
    }
}

/**
 * 详情字段组件
 */
@Composable
private fun DetailField(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    allowMultilineValue: Boolean = false,
    onToggleVisibility: (() -> Unit)? = null,
    onCopy: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                val displayValue = if (isPassword && !passwordVisible) {
                    "••••••••"
                } else {
                    value
                }
                
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (allowMultilineValue) 5 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // 密码可见性切换
            if (isPassword && onToggleVisibility != null) {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (passwordVisible) {
                            stringResource(R.string.hide)
                        } else {
                            stringResource(R.string.show)
                        }
                    )
                }
            }
            
            // 复制按钮
            if (onCopy != null) {
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.copy)
                    )
                }
            }
        }
    }
}

/**
 * 复制到剪贴板
 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    ClipboardUtils.copyToClipboard(context, text, label)
    Toast.makeText(context, context.getString(R.string.copied, label), Toast.LENGTH_SHORT).show()
}



