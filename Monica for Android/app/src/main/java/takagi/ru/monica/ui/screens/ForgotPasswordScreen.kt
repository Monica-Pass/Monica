package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.viewmodel.PasswordViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(
    viewModel: PasswordViewModel,
    onNavigateBack: () -> Unit,
    onResetComplete: () -> Unit
) {
    val context = LocalContext.current
    var showConfirmDialog by remember { mutableStateOf(false) }
    var isResetting by remember { mutableStateOf(false) }
    
    // 数据类型选择状态
    var clearPasswords by remember { mutableStateOf(true) }
    var clearAuthenticators by remember { mutableStateOf(true) }
    var clearDocuments by remember { mutableStateOf(true) }
    var clearBankCards by remember { mutableStateOf(true) }
    var clearGeneratorHistory by remember { mutableStateOf(true) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.forgot_password_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = context.getString(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.weight(1f))
            
            // Warning Icon
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp)
            )
            
            // Title
            Text(
                text = context.getString(R.string.forgot_password_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            
            // Warning Message
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = context.getString(R.string.forgot_password_warning),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            // 数据类型选择
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = context.getString(R.string.select_data_types_to_clear),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    // 密码
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = clearPasswords,
                            onCheckedChange = { clearPasswords = it }
                        )
                        Text(
                            text = context.getString(R.string.clear_passwords),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    
                    // 验证器
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = clearAuthenticators,
                            onCheckedChange = { clearAuthenticators = it }
                        )
                        Text(
                            text = context.getString(R.string.clear_authenticators),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    
                    // 证件
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = clearDocuments,
                            onCheckedChange = { clearDocuments = it }
                        )
                        Text(
                            text = context.getString(R.string.clear_documents),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    
                    // 银行卡
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = clearBankCards,
                            onCheckedChange = { clearBankCards = it }
                        )
                        Text(
                            text = context.getString(R.string.clear_bank_cards),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    
                    // 生成历史记录 - 第5个选项！
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = clearGeneratorHistory,
                            onCheckedChange = { clearGeneratorHistory = it }
                        )
                        Text(
                            text = context.getString(R.string.clear_generator_history),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            
            // 至少选择一个数据类型的验证
            val hasSelection = clearPasswords || clearAuthenticators || clearDocuments || 
                              clearBankCards || clearGeneratorHistory
            
            // Reset Button
            Button(
                onClick = { showConfirmDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isResetting && hasSelection
            ) {
                if (isResetting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = context.getString(R.string.reset_all_data),
                    color = MaterialTheme.colorScheme.onError
                )
            }
            
            // Cancel Button
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isResetting
            ) {
                Text(context.getString(R.string.cancel))
            }
            
            Spacer(modifier = Modifier.weight(1f))
        }
    }
    
    // Confirmation Dialog with Password Input
    if (showConfirmDialog) {
        val manager = remember(context) { takagi.ru.monica.security.SecurityManager(context) }
        takagi.ru.monica.ui.components.SecondaryPasswordVerificationDialog(
            manager = manager,
            title = context.getString(R.string.forgot_password_confirm),
            message = context.getString(R.string.forgot_password_warning),
            confirmText = context.getString(R.string.reset_all_data),
            destructiveConfirm = true,
            isRequestCurrent = { showConfirmDialog && !isResetting },
            onDismiss = { showConfirmDialog = false },
            onSuccess = {
                showConfirmDialog = false
                isResetting = true
                viewModel.resetAllData(
                    clearPasswords = clearPasswords,
                    clearTotp = clearAuthenticators,
                    clearDocuments = clearDocuments,
                    clearBankCards = clearBankCards,
                    clearGeneratorHistory = clearGeneratorHistory,
                )
                isResetting = false
                onResetComplete()
            },
        )
    }
}
