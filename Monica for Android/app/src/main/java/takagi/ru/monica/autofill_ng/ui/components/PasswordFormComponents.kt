package takagi.ru.monica.autofill_ng.ui.components

import takagi.ru.monica.ui.components.MonicaSlider as Slider
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.utils.PasswordGenerator
import java.security.SecureRandom
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import takagi.ru.monica.ui.components.OutlinedTextField

/**
 * 存储位置选择器卡片
 * 参考软件内部 VaultSelector 设计
 */
/**
 * 存储位置选择器卡片
 * 参考软件内部 VaultSelector 设计
 */
@Composable
internal fun VaultSelectorCard(
    selectedDatabase: LocalKeePassDatabase? = null,
    onClick: () -> Unit
) {
    val displayName = selectedDatabase?.name ?: stringResource(R.string.vault_monica_only)
    val isKeePass = selectedDatabase != null
    
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isKeePass) 
            MaterialTheme.colorScheme.primaryContainer 
        else 
            MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 图标
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isKeePass) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isKeePass) Icons.Default.Key else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isKeePass)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            
            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isKeePass)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = if (isKeePass) 
                        stringResource(R.string.vault_sync_to_keepass)
                    else 
                        stringResource(R.string.vault_monica_only_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isKeePass)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            
            // 展开图标
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = if (isKeePass)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                else
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * 凭据信息卡片
 * 参考软件内部 InfoCard 设计
 */
@Composable
internal fun CredentialsCard(
    title: String,
    onTitleChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: (Boolean) -> Unit,
    onGeneratePassword: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 卡片标题
            Text(
                text = stringResource(R.string.autofill_credentials),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            // 标题输入
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.title_required)) },
                leadingIcon = { 
                    Icon(
                        Icons.Default.Label, 
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    ) 
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = cardTextFieldColors()
            )
            
            // 用户名输入
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.username_email)) },
                leadingIcon = { 
                    Icon(
                        Icons.Default.Person, 
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    ) 
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = cardTextFieldColors()
            )
            
            // 密码输入
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.password_required)) },
                leadingIcon = { 
                    Icon(
                        Icons.Default.Lock, 
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    ) 
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 生成密码按钮
                        IconButton(onClick = onGeneratePassword) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.generate_password),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        // 显示/隐藏密码按钮
                        IconButton(onClick = { onPasswordVisibilityChange(!passwordVisible) }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) {
                                    stringResource(R.string.hide_password)
                                } else {
                                    stringResource(R.string.show_password)
                                },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = cardTextFieldColors()
            )
        }
    }
}

/**
 * 卡片内 TextField 颜色配置
 */
@Composable
internal fun cardTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary
)

/**
 * 生成安全密码
 * 16位，包含大小写字母、数字、特殊字符
 */
internal fun generateSecurePassword(length: Int = 16): String {
    val lowercase = "abcdefghijklmnopqrstuvwxyz"
    val uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val digits = "0123456789"
    val special = "!@#\$%^&*"
    val allChars = lowercase + uppercase + digits + special
    
    val random = SecureRandom()
    val password = StringBuilder(length)
    
    // 确保每种类型至少有一个
    password.append(lowercase[random.nextInt(lowercase.length)])
    password.append(uppercase[random.nextInt(uppercase.length)])
    password.append(digits[random.nextInt(digits.length)])
    password.append(special[random.nextInt(special.length)])
    
    // 填充剩余字符
    for (i in 4 until length) {
        password.append(allChars[random.nextInt(allChars.length)])
    }
    
    // 打乱顺序
    return password.toString().toList().shuffled(random).joinToString("")
}

/**
 * 从URL提取域名（用于显示）
 */
internal fun String?.extractDomain(): String? {
    if (this.isNullOrEmpty()) return null
    return try {
        this.removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .split("/").first()
    } catch (e: Exception) {
        null
    }
}

/**
 * 从URL提取域名作为标题
 */
internal fun String?.extractDomainName(): String? {
    val domain = this.extractDomain() ?: return null
    return try {
        val parts = domain.split(".")
        if (parts.size >= 2) {
            parts[parts.size - 2].replaceFirstChar { it.uppercase() }
        } else {
            domain
        }
    } catch (e: Exception) {
        domain
    }
}

/**
 * 密码生成器对话框
 * 提供完整的密码生成选项
 */
@Composable
internal fun PasswordGeneratorDialog(
    onDismiss: () -> Unit,
    onPasswordGenerated: (String) -> Unit
) {
    val passwordGenerator = remember { PasswordGenerator() }
    var length by remember { mutableStateOf(16) }
    var includeUppercase by remember { mutableStateOf(true) }
    var includeLowercase by remember { mutableStateOf(true) }
    var includeNumbers by remember { mutableStateOf(true) }
    var includeSymbols by remember { mutableStateOf(true) }
    var excludeSimilar by remember { mutableStateOf(true) }
    var generatedPassword by remember { mutableStateOf("") }
    
    fun generate() {
        try {
            generatedPassword = passwordGenerator.generatePassword(
                PasswordGenerator.PasswordOptions(
                    length = length,
                    includeUppercase = includeUppercase,
                    includeLowercase = includeLowercase,
                    includeNumbers = includeNumbers,
                    includeSymbols = includeSymbols,
                    excludeSimilar = excludeSimilar
                )
            )
        } catch (e: Exception) {
            generatedPassword = generateSecurePassword(length)
        }
    }
    
    LaunchedEffect(Unit) { generate() }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                stringResource(R.string.password_generator_title),
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 生成的密码预览
                OutlinedTextField(
                    value = generatedPassword,
                    onValueChange = { },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { generate() }) {
                            Icon(
                                Icons.Default.Refresh, 
                                contentDescription = stringResource(R.string.regenerate),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                
                // 长度滑块
                Column {
                    Text(
                        text = stringResource(R.string.length_value, length),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = length.toFloat(),
                        onValueChange = { length = it.toInt(); generate() },
                        valueRange = 8f..32f,
                        steps = 24
                    )
                }
                
                // 选项
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PasswordOptionRow(
                        label = stringResource(R.string.uppercase_az),
                        checked = includeUppercase,
                        onCheckedChange = { includeUppercase = it; generate() }
                    )
                    PasswordOptionRow(
                        label = stringResource(R.string.lowercase_az),
                        checked = includeLowercase,
                        onCheckedChange = { includeLowercase = it; generate() }
                    )
                    PasswordOptionRow(
                        label = stringResource(R.string.numbers_09),
                        checked = includeNumbers,
                        onCheckedChange = { includeNumbers = it; generate() }
                    )
                    PasswordOptionRow(
                        label = stringResource(R.string.symbols),
                        checked = includeSymbols,
                        onCheckedChange = { includeSymbols = it; generate() }
                    )
                    PasswordOptionRow(
                        label = stringResource(R.string.exclude_similar),
                        checked = excludeSimilar,
                        onCheckedChange = { excludeSimilar = it; generate() }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onPasswordGenerated(generatedPassword) }) {
                Text(stringResource(R.string.use_password))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * 密码选项行
 */
@Composable
internal fun PasswordOptionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * 存储位置选择对话框
 * 目前仅支持 Monica 本地存储
 */
/**
 * 存储位置选择对话框
 * 支持 Monica 本地存储和 KeePass 数据库
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultSelector(
    keepassDatabases: List<LocalKeePassDatabase>,
    selectedDatabaseId: Long?,
    onDatabaseSelected: (Long?) -> Unit,
    showBottomSheet: Boolean,
    onDismissRequest: () -> Unit
) {
    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 标题
                Text(
                    text = stringResource(R.string.vault_select_storage),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                // Monica 本地存储选项
                VaultOptionItem(
                    title = stringResource(R.string.vault_monica_only),
                    subtitle = stringResource(R.string.vault_monica_only_desc),
                    icon = Icons.Default.Shield,
                    isSelected = selectedDatabaseId == null,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    iconColor = MaterialTheme.colorScheme.secondary,
                    onClick = {
                        onDatabaseSelected(null)
                        onDismissRequest()
                    }
                )
                
                // KeePass 数据库选项
                keepassDatabases.forEach { database ->
                    val isSelected = selectedDatabaseId == database.id
                    val storageText = if (database.storageLocation == KeePassStorageLocation.EXTERNAL)
                        stringResource(R.string.external_storage)
                    else
                        stringResource(R.string.internal_storage)
                    
                    VaultOptionItem(
                        title = database.name,
                        subtitle = stringResource(
                            R.string.autofill_storage_sync_summary,
                            storageText,
                            stringResource(R.string.vault_sync_to_keepass)
                        ),
                        icon = Icons.Default.Key,
                        isSelected = isSelected,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        iconColor = MaterialTheme.colorScheme.primary,
                        onClick = {
                            onDatabaseSelected(database.id)
                            onDismissRequest()
                        }
                    )
                }
            }
        }
    }
}

/**
 * 保管库选项卡片
 */
@Composable
internal fun VaultOptionItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    containerColor: Color,
    contentColor: Color,
    iconColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) containerColor else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (isSelected) null else BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = if (isSelected) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 图标
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) iconColor else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(44.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) 
                            MaterialTheme.colorScheme.surface 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            
            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) contentColor else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) 
                        contentColor.copy(alpha = 0.7f) 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 选中指示
            if (isSelected) {
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) contentColor else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = if (isSelected) containerColor else MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}


