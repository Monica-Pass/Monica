package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import takagi.ru.monica.passkey.PasskeyValidationDiagnostics
import takagi.ru.monica.passkey.PasskeyValidationFlags
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase

/**
 * Passkey 设置页面
 * 
 * 允许用户管理 Passkey Provider 功能，包括：
 * - 查看是否已启用为系统 Passkey 提供者
 * - 导航到系统设置以启用/禁用
 * - 查看已保存的 Passkey 统计信息
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasskeySettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val database = remember { PasswordDatabase.getDatabase(context) }
    var shadowValidationEnabled by remember {
        mutableStateOf(PasskeyValidationFlags.isShadowValidationEnabled(context))
    }
    var strictValidationEnabled by remember {
        mutableStateOf(PasskeyValidationFlags.isStrictValidationEnabled(context))
    }
    var diagnosticsSummary by remember {
        mutableStateOf(PasskeyValidationDiagnostics.readSummary(context))
    }
    
    // Passkey 统计
    val passkeyCount by database.passkeyDao().getPasskeyCount().collectAsState(initial = 0)
    
    // 是否支持 Passkey (Android 14+)
    val isPasskeySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.passkey_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // 头部介绍
            PasskeyIntroSection(isSupported = isPasskeySupported)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (isPasskeySupported) {
                // 系统设置入口
                SystemSettingsCard(
                    onClick = {
                        // 打开系统凭据设置
                        try {
                            // Android 14+ 使用凭据提供者设置
                            val intent = Intent("android.settings.CREDENTIAL_PROVIDER_SETTINGS")
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // 如果特定设置不可用，打开通用设置
                            val intent = Intent(Settings.ACTION_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 统计信息
                PasskeyStatsCard(passkeyCount = passkeyCount)
                
                Spacer(modifier = Modifier.height(16.dp))

                PasskeyValidationCard(
                    shadowValidationEnabled = shadowValidationEnabled,
                    strictValidationEnabled = strictValidationEnabled,
                    onShadowValidationChanged = { enabled ->
                        shadowValidationEnabled = enabled
                        PasskeyValidationFlags.setShadowValidationEnabled(context, enabled)
                        if (!enabled && strictValidationEnabled) {
                            strictValidationEnabled = false
                            PasskeyValidationFlags.setStrictValidationEnabled(context, false)
                        }
                    },
                    onStrictValidationChanged = { enabled ->
                        strictValidationEnabled = enabled
                        PasskeyValidationFlags.setStrictValidationEnabled(context, enabled)
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                PasskeyDiagnosticsCard(
                    summary = diagnosticsSummary,
                    onRefresh = {
                        diagnosticsSummary = PasskeyValidationDiagnostics.readSummary(context)
                    },
                    onCopyReport = {
                        val report = PasskeyValidationDiagnostics.buildReport(context)
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("passkey_diagnostics", report))
                        Toast.makeText(context, context.getString(R.string.passkey_diagnostics_copied), Toast.LENGTH_SHORT).show()
                    },
                    onClear = {
                        PasskeyValidationDiagnostics.clear(context)
                        diagnosticsSummary = PasskeyValidationDiagnostics.readSummary(context)
                        Toast.makeText(context, context.getString(R.string.passkey_diagnostics_cleared), Toast.LENGTH_SHORT).show()
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 功能说明
                PasskeyFeaturesSection()
                
            } else {
                // 不支持的提示
                UnsupportedCard()
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PasskeyDiagnosticsCard(
    summary: takagi.ru.monica.passkey.PasskeyValidationSummary,
    onRefresh: () -> Unit,
    onCopyReport: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.passkey_diagnostics_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(
                    R.string.passkey_diagnostics_summary,
                    summary.total,
                    summary.suspicious,
                    summary.strictCandidates
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            val topBucket = summary.buckets.firstOrNull()
            if (topBucket != null) {
                Text(
                    text = stringResource(
                        R.string.passkey_diagnostics_top_bucket,
                        topBucket.packageName,
                        topBucket.rpId,
                        topBucket.suspicious
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onRefresh
                ) {
                    Text(stringResource(R.string.passkey_diagnostics_refresh))
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onCopyReport
                ) {
                    Text(stringResource(R.string.passkey_diagnostics_copy))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onClear
            ) {
                Text(
                    text = stringResource(R.string.passkey_diagnostics_clear),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PasskeyValidationCard(
    shadowValidationEnabled: Boolean,
    strictValidationEnabled: Boolean,
    onShadowValidationChanged: (Boolean) -> Unit,
    onStrictValidationChanged: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.passkey_validation_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.passkey_shadow_validation_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.passkey_shadow_validation_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = shadowValidationEnabled,
                    onCheckedChange = onShadowValidationChanged
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.passkey_strict_validation_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.passkey_strict_validation_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = strictValidationEnabled,
                    enabled = shadowValidationEnabled,
                    onCheckedChange = onStrictValidationChanged
                )
            }

            if (strictValidationEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.passkey_strict_validation_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

        }
    }
}

@Composable
private fun PasskeyIntroSection(isSupported: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSupported) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = if (isSupported) {
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            } else {
                                listOf(
                                    MaterialTheme.colorScheme.error,
                                    MaterialTheme.colorScheme.errorContainer
                                )
                            }
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSupported) Icons.Filled.Key else Icons.Filled.Block,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = if (isSupported) {
                    stringResource(R.string.passkey_settings_intro_title)
                } else {
                    stringResource(R.string.passkey_settings_unsupported_title)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = if (isSupported) {
                    stringResource(R.string.passkey_settings_intro_subtitle)
                } else {
                    stringResource(R.string.passkey_settings_unsupported_subtitle, Build.VERSION.RELEASE)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SystemSettingsCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.passkey_settings_system_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.passkey_settings_system_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PasskeyStatsCard(passkeyCount: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                icon = Icons.Outlined.Key,
                value = passkeyCount.toString(),
                label = stringResource(R.string.passkey_stats_saved)
            )
            
            // 可以添加更多统计项
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: String,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PasskeyFeaturesSection() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.passkey_settings_features_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            FeatureItem(
                icon = Icons.Outlined.Security,
                title = stringResource(R.string.passkey_feature_security_title),
                description = stringResource(R.string.passkey_feature_security_desc)
            )
            
            FeatureItem(
                icon = Icons.Outlined.Fingerprint,
                title = stringResource(R.string.passkey_feature_biometric_title),
                description = stringResource(R.string.passkey_feature_biometric_desc)
            )
            
            FeatureItem(
                icon = Icons.Outlined.Sync,
                title = stringResource(R.string.passkey_feature_sync_title),
                description = stringResource(R.string.passkey_feature_sync_desc)
            )
            
            FeatureItem(
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.passkey_feature_phishing_title),
                description = stringResource(R.string.passkey_feature_phishing_desc)
            )
        }
    }
}

@Composable
private fun FeatureItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UnsupportedCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Text(
                    text = stringResource(R.string.passkey_settings_requirement_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = stringResource(R.string.passkey_settings_requirement_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
