package takagi.ru.monica.ui.components

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.OutlinedTextField

/**
 * 应用信息数据类
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?
)

private const val INSTALLED_APPS_CACHE_TTL_MS = 5 * 60 * 1000L
private val installedAppsCacheLock = Any()
private var installedAppsCache: List<AppInfo>? = null
private var installedAppsCacheTimestamp = 0L

/**
 * 应用选择器组件
 * 用于在密码条目中关联特定应用
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectorField(
    selectedPackageName: String,
    selectedAppName: String,
    onAppSelected: (packageName: String, appName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // 显示选择器按钮
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showDialog = true },
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.linked_app),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (selectedAppName.isNotBlank()) {
                            selectedAppName
                        } else {
                            stringResource(R.string.no_app_selected)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selectedAppName.isNotBlank()) FontWeight.Medium else FontWeight.Normal,
                        color = if (selectedAppName.isNotBlank()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (selectedPackageName.isNotBlank()) {
                        Text(
                            text = selectedPackageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 清除按钮
                if (selectedPackageName.isNotBlank()) {
                    IconButton(
                        onClick = { onAppSelected("", "") }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.clear_app_selection)
                        )
                    }
                }
                
                // 选择按钮
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    // 应用选择对话框
    if (showDialog) {
        AppSelectorDialog(
            onDismiss = { showDialog = false },
            onAppSelected = { packageName, appName ->
                onAppSelected(packageName, appName)
                showDialog = false
            }
        )
    }
}

/**
 * 应用选择对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectorDialog(
    onDismiss: () -> Unit,
    onAppSelected: (packageName: String, appName: String) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showManualInputDialog by remember { mutableStateOf(false) }
    
    // 加载已安装的应用列表
    LaunchedEffect(Unit) {
        installedApps = loadInstalledApps(context)
        isLoading = false
    }
    
    // 手动输入对话框
    if (showManualInputDialog) {
        ManualInputDialog(
            onDismiss = { showManualInputDialog = false },
            onConfirm = { packageName, appName ->
                showManualInputDialog = false
                onAppSelected(packageName, appName)
                onDismiss()
            }
        )
    }
    
    // 优化的搜索过滤
    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            val query = searchQuery.trim().lowercase(java.util.Locale.getDefault())
            
            installedApps.filter { app ->
                val appNameLower = app.appName.lowercase(java.util.Locale.getDefault())
                val packageNameLower = app.packageName.lowercase(java.util.Locale.getDefault())
                
                // 支持多种搜索模式：
                // 1. 完整包含（最常用）
                // 2. 首字母缩写匹配（如"wc" 匹配 "WeChat"）
                
                appNameLower.contains(query) ||
                packageNameLower.contains(query)
            }.sortedWith(compareBy(
                // 排序优先级：
                // 1. 应用名称开头匹配（最相关）
                { !it.appName.lowercase(java.util.Locale.getDefault()).startsWith(query) },
                // 2. 应用名称包含匹配
                { !it.appName.lowercase(java.util.Locale.getDefault()).contains(query) },
                // 3. 包名匹配（最不相关）
                { !it.packageName.lowercase(java.util.Locale.getDefault()).contains(query) },
                // 4. 字母顺序
                { it.appName.lowercase(java.util.Locale.getDefault()) }
            ))
        }
    }
    

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.select_app))
                
                // 手动导入按钮
                IconButton(
                    onClick = { showManualInputDialog = true }
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.app_selector_manual_input_cd),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp)
            ) {
                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search_apps)) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 结果统计
                if (!isLoading && installedApps.isNotEmpty()) {
                    Text(
                        text = if (searchQuery.isBlank()) {
                            stringResource(R.string.app_selector_total_apps, installedApps.size)
                        } else {
                            stringResource(R.string.app_selector_found_apps, filteredApps.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // 应用列表
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.app_selector_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isBlank()) {
                                    stringResource(R.string.app_selector_no_installed_apps)
                                } else {
                                    stringResource(R.string.app_selector_no_match_apps, searchQuery)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Use the package name as the item identity. Without a stable
                        // key, changing the search query reuses a row by position and
                        // can leave the previous app's icon in the newly displayed row.
                        items(
                            items = filteredApps,
                            key = { it.packageName }
                        ) { app ->
                            AppListItem(
                                app = app,
                                onClick = {
                                    onAppSelected(app.packageName, app.appName)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * 应用列表项
 */
@Composable
fun AppListItem(
    app: AppInfo,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // `produceState` retains its state when a lazy-list slot is reused. Reset the
    // value whenever the app identity or the loaded icon changes and never keep
    // an old non-null value just because it came from the previous row.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用图标
            AppIcon(app = app, modifier = Modifier.size(40.dp))
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // 应用信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 手动输入应用信息对话框
 * 
 * 用于添加列表中未显示的应用
 */
@Composable
private fun ManualInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (packageName: String, appName: String) -> Unit
) {
    var packageName by remember { mutableStateOf("") }
    var appName by remember { mutableStateOf("") }
    var packageNameError by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.app_selector_manual_title))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_selector_manual_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // 包名输入
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { 
                        packageName = it.trim()
                        packageNameError = false
                    },
                    label = { Text(stringResource(R.string.app_selector_package_name_required)) },
                    placeholder = { Text(stringResource(R.string.app_selector_package_name_example)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = packageNameError,
                    supportingText = if (packageNameError) {
                        { Text(stringResource(R.string.app_selector_package_name_empty)) }
                    } else {
                        { Text(stringResource(R.string.app_selector_package_name_required_hint)) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    )
                )
                
                // 应用名称输入
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text(stringResource(R.string.app_selector_app_name_optional)) },
                    placeholder = { Text(stringResource(R.string.app_selector_app_name_example)) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.app_selector_app_name_optional_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (packageName.isNotBlank()) {
                                val finalAppName = appName.ifBlank { packageName }
                                onConfirm(packageName, finalAppName)
                            } else {
                                packageNameError = true
                            }
                        }
                    )
                )
                
                // 提示信息
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.app_selector_how_get_package_name),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.app_selector_how_get_package_name_steps),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (packageName.isBlank()) {
                        packageNameError = true
                    } else {
                        val finalAppName = appName.ifBlank { packageName }
                        onConfirm(packageName, finalAppName)
                    }
                }
            ) {
                Text(stringResource(R.string.confirm))
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
 * 加载已安装的应用列表
 * 
 * 优化版本（与 AppListScreen 逻辑一致）：
 * - 只加载有启动器图标的应用（用户可见的应用）
 * - 自动去重（同一应用的多个入口只保留第一个）
 * - 性能优化：限制最大数量，防止内存溢出
 * - 内存优化：仅加载可见的应用图标
 */
suspend fun loadInstalledApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    synchronized(installedAppsCacheLock) {
        installedAppsCache?.takeIf {
            now - installedAppsCacheTimestamp < INSTALLED_APPS_CACHE_TTL_MS
        }?.let { return@withContext it }
    }

    val packageManager = context.packageManager
    val appList = mutableListOf<AppInfo>()
    val maxApps = 1000 // 去重后的上限

    try {
        android.util.Log.d("AppSelector", "开始加载应用列表...")

        // 创建Intent，查询所有有启动器图标的应用
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }

        // 查询所有匹配的Activity
        val startTime = System.currentTimeMillis()
        val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
        val queryTime = System.currentTimeMillis() - startTime

        android.util.Log.d("AppSelector", "查询到 ${resolveInfoList.size} 个应用入口，耗时 ${queryTime}ms")

        // 先去重（同一个包名只保留第一个入口），再限制数量
        val seenPackages = mutableSetOf<String>()

        for (resolveInfo in resolveInfoList) {
            try {
                val activityInfo = resolveInfo.activityInfo
                val packageName = activityInfo.packageName

                // 跳过重复的包名（只保留第一个入口）
                if (!seenPackages.add(packageName)) {
                    continue
                }

                // 跳过隐藏的系统组件
                if (isSystemComponentToHide(packageName)) {
                    continue
                }
                
                // 达到上限时停止
                if (appList.size >= maxApps) break

                val appName = activityInfo.loadLabel(packageManager).toString()

                // Icons are resolved lazily by AppListItem for visible rows. Loading
                // every Drawable here makes the dialog wait on hundreds of Binder
                // calls and retains a large amount of memory.
                appList.add(AppInfo(packageName, appName, icon = null))
                
            } catch (e: Exception) {
                android.util.Log.w("AppSelector", "跳过无效应用: ${e.message}")
                // 继续处理下一个
            }
        }
        
        // 按应用名称排序
        appList.sortBy { it.appName.lowercase(java.util.Locale.getDefault()) }
        
        val totalTime = System.currentTimeMillis() - startTime
        android.util.Log.d("AppSelector", "应用列表加载完成：${appList.size} 个应用，总耗时 ${totalTime}ms")

        val result = appList.toList()
        synchronized(installedAppsCacheLock) {
            installedAppsCache = result
            installedAppsCacheTimestamp = System.currentTimeMillis()
        }
        return@withContext result
        
    } catch (e: OutOfMemoryError) {
        android.util.Log.e("AppSelector", "内存不足！", e)
        appList.clear()
        System.gc() // 建议 GC 回收
        throw Exception("Out of memory: too many apps")
    } catch (e: Exception) {
        android.util.Log.e("AppSelector", "加载应用列表时出错", e)
        throw e
    }
    
    return@withContext appList.toList()
}

@Composable
fun AppIcon(
    app: AppInfo,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val icon by produceState<Drawable?>(
        initialValue = app.icon,
        key1 = app.packageName,
        key2 = app.icon,
    ) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
            }
        }
    }
    icon?.let { drawable ->
        val bitmap = remember(app.packageName, drawable) { drawable.toBitmap(48, 48) }
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = modifier)
    } ?: Icon(
        imageVector = Icons.Default.Apps,
        contentDescription = null,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 判断是否是需要隐藏的系统组件
 * 
 * 隐藏不必要的底层系统组件，保留用户可能需要的应用
 */
 fun isSystemComponentToHide(packageName: String): Boolean {
    val hidePatterns = listOf(
        // 1. 系统核心组件
        "android",                              // Android系统
        "com.android.systemui",                 // 系统界面
        "com.android.internal",                 // 系统内部
        
        // 2. 包管理和安装器
        "com.android.packageinstaller",         // 包安装程序
        "com.android.defcontainer",             // 包访问助手
        "com.google.android.packageinstaller",  // Google包安装程序
        
        // 3. 系统服务
        "com.android.shell",                    // Shell
        "com.android.sharedstoragebackup",      // 共享存储备份
        "com.android.wallpaperbackup",          // 壁纸备份
        "com.android.printspooler",             // 打印假脱机程序
        "com.android.vpndialogs",               // VPN对话框
        "com.android.location.fused",           // 融合位置
        "com.android.externalstorage",          // 外部存储
        "com.android.htmlviewer",               // HTML查看器
        "com.android.mms.service",              // 短信服务
        "com.android.phone",                    // 电话服务
        
        // 4. 后台服务和提供程序
        "com.android.providers.",               // 各种内容提供程序
        "com.android.server.",                  // 服务器组件
        "com.android.backupconfirm",            // 备份确认
        
        // 5. 系统证书和密钥
        "com.android.certinstaller",            // 证书安装器
        "com.android.keychain",                 // 密钥链
        
        // 6. 壁纸和主题相关（系统级）
        "com.android.wallpaper.livepicker",     // 动态壁纸选择器
        "com.android.wallpapercropper",         // 壁纸裁剪器
        
        // 7. Google系统服务（底层）
        "com.google.android.gsf",               // Google服务框架
        "com.google.android.partnersetup",      // 合作伙伴设置
        "com.google.android.syncadapters.",     // 同步适配器
        "com.google.android.configupdater",     // 配置更新器
        "com.google.android.onetimeinitializer", // 一次性初始化器
        "com.google.android.backuptransport",   // 备份传输
        
        // 8. 无障碍和反馈服务
        "com.android.companiondevicemanager",   // 配套设备管理器
        "com.google.android.feedback",          // 反馈
        
        // 9. 测试和调试工具
        "com.android.cts.",                     // 兼容性测试
        "com.android.development",              // 开发工具
        "com.android.dreams.",                  // 屏幕保护程序
        
        // 10. 输入法框架（不是输入法应用本身）
        "com.android.inputmethod.latin",        // AOSP拉丁输入法（通常被替代）
        
        // 11. 其他系统组件
        "com.android.managedprovisioning",      // 托管配置
        "com.android.proxyhandler",             // 代理处理程序
        "com.android.statementservice",         // 意图过滤器验证
        "com.android.stk",                      // SIM卡工具包
        "com.android.nfc",                      // NFC服务（保留用户NFC应用）
        "com.qualcomm.qti.",                    // 高通系统组件
        "com.qualcomm.timeservice",             // 高通时间服务
        "com.qti.",                             // 高通组件
        "com.google.android.gms",               // Google Play 服务
        "com.google.android.tts",               // Google 文字转语音
        "com.google.android.webview",           // Android System WebView
        "com.google.android.marvin.talkback",   // TalkBack
        "com.google.android.projection.gearhead", // Android Auto
        "com.google.ar.core",                   // AR Core
        "com.google.android.printservice.recommendation", // 打印服务
        "com.google.android.inputmethod.latin", // Gboard 系统组件
        "com.android.bluetoothmidiservice",     // 蓝牙 MIDI
        "com.android.bluetoothkeepalive",       // 蓝牙保活
        "com.android.traceur",                  // Trace 记录
        "com.android.wallpaper",                // AOSP 壁纸服务
        "com.android.settings.intelligence"     // 设置智能推荐
    )
    
    // 检查包名是否匹配隐藏模式
    return hidePatterns.any { pattern ->
        packageName.startsWith(pattern, ignoreCase = true)
    }
}

/**
 * 导出应用列表到文件
 * 
 * 优化版本：
 * - 性能优化：减少不必要的包管理器调用
 * - UI优化：添加加载提示和进度反馈
 * - 错误处理：更详细的错误信息
 * - 文件优化：更清晰的报告格式
 */
private suspend fun exportAppListToFile(context: Context, currentList: List<AppInfo>) = withContext(Dispatchers.IO) {
    try {
        // 显示加载提示
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.app_selector_loading),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        
        val packageManager = context.packageManager
        val allPackages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        
        // 预处理数据，提升性能
        val hiddenPackages = allPackages.filter { isSystemComponentToHide(it.packageName) }
        
        val sb = StringBuilder()
        
        // ============ 头部信息 ============
        sb.appendLine("╔════════════════════════════════════════════════════════╗")
        sb.appendLine("║           Monica 应用列表诊断报告                      ║")
        sb.appendLine("╚════════════════════════════════════════════════════════╝")
        sb.appendLine()
        sb.appendLine("📅 生成时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("📱 设备品牌: ${android.os.Build.BRAND}")
        sb.appendLine("📱 设备型号: ${android.os.Build.MODEL}")
        sb.appendLine("🤖 Android版本: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine()
        
        // ============ 统计信息 ============
        val totalApps = allPackages.size
        val hiddenCount = hiddenPackages.size
        val visibleCount = currentList.size
        val systemApps = allPackages.count { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
        val userApps = totalApps - systemApps
        val updatedSystemApps = allPackages.count { (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
        
        sb.appendLine("【📊 统计摘要】")
        sb.appendLine("  • 总应用数量: $totalApps")
        sb.appendLine("  • 用户应用: $userApps")
        sb.appendLine("  • 系统应用: $systemApps (已更新: $updatedSystemApps)")
        sb.appendLine("  • ✅ 当前显示: $visibleCount (${String.format("%.1f", visibleCount * 100.0 / totalApps)}%)")
        sb.appendLine("  • ❌ 被隐藏: $hiddenCount (${String.format("%.1f", hiddenCount * 100.0 / totalApps)}%)")
        sb.appendLine()
        
        // 健康度评估
        val healthScore = when {
            hiddenCount < 30 -> "Warning: blacklist may be too loose"
            hiddenCount > 100 -> "Warning: blacklist may be too strict"
            else -> "Blacklist health looks good"
        }
        sb.appendLine("  💡 健康度评估: $healthScore")
        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine()
        
        // ============ 当前显示的应用 ============
        sb.appendLine("【✅ 当前显示的应用 ($visibleCount 个)】")
        sb.appendLine()
        
        // 按类型分组显示
        val (userVisibleApps, systemVisibleApps) = currentList.partition { app ->
            allPackages.find { it.packageName == app.packageName }?.let {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            } ?: false
        }
        
        if (userVisibleApps.isNotEmpty()) {
            sb.appendLine("┌─ 用户安装的应用 (${userVisibleApps.size} 个) ─┐")
            userVisibleApps.take(20).forEach { app ->
                sb.appendLine("  📱 ${app.appName}")
                sb.appendLine("     ${app.packageName}")
            }
            if (userVisibleApps.size > 20) {
                sb.appendLine("  ... 还有 ${userVisibleApps.size - 20} 个应用")
            }
            sb.appendLine()
        }
        
        if (systemVisibleApps.isNotEmpty()) {
            sb.appendLine("┌─ 系统应用 (${systemVisibleApps.size} 个) ─┐")
            systemVisibleApps.take(20).forEach { app ->
                sb.appendLine("  ⚙️ ${app.appName}")
                sb.appendLine("     ${app.packageName}")
            }
            if (systemVisibleApps.size > 20) {
                sb.appendLine("  ... 还有 ${systemVisibleApps.size - 20} 个应用")
            }
            sb.appendLine()
        }
        
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine()
        
        // ============ 被隐藏的系统组件 ============
        sb.appendLine("【❌ 被隐藏的系统组件 ($hiddenCount 个)】")
        sb.appendLine("💡 如果发现有用的应用被误隐藏，请记录包名并反馈")
        sb.appendLine()
        
        // 按包名前缀分组
        val hiddenByPrefix = hiddenPackages
            .groupBy { 
                when {
                    it.packageName.startsWith("android") -> "Android core"
                    it.packageName.startsWith("com.android.") -> "Android system"
                    it.packageName.startsWith("com.google.android.") -> "Google services"
                    it.packageName.startsWith("com.qualcomm.") || it.packageName.startsWith("com.qti.") -> "Chip vendor"
                    else -> "Other"
                }
            }
        
        hiddenByPrefix.forEach { (category, apps) ->
            sb.appendLine("┌─ $category (${apps.size} 个) ─┐")
            apps.sortedBy { it.packageName }.take(10).forEach { app ->
                val appName = try {
                    packageManager.getApplicationLabel(app).toString()
                } catch (e: Exception) {
                    "Unknown"
                }
                sb.appendLine("  🚫 $appName")
                sb.appendLine("     ${app.packageName}")
            }
            if (apps.size > 10) {
                sb.appendLine("  ... 还有 ${apps.size - 10} 个组件")
            }
            sb.appendLine()
        }
        
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine()
        
        // ============ 完整应用列表 ============
        sb.appendLine("【📋 完整应用列表（所有 $totalApps 个应用）】")
        sb.appendLine("💡 格式: [状态] 应用名称")
        sb.appendLine("       包名 | 类型")
        sb.appendLine()
        
        allPackages.sortedBy { it.packageName }.forEach { app ->
            val appName = try {
                packageManager.getApplicationLabel(app).toString()
            } catch (e: Exception) {
                "Unknown app"
            }
            
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdated = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isHidden = isSystemComponentToHide(app.packageName)
            
            val statusIcon = if (isHidden) "❌" else "✅"
            val typeLabel = when {
                !isSystem -> "User app"
                isUpdated -> "Updated system app"
                else -> "System app"
            }
            
            sb.appendLine("$statusIcon $appName")
            sb.appendLine("   ${app.packageName} | $typeLabel")
        }
        
        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine()
        sb.appendLine("【🔧 黑名单规则信息】")
        sb.appendLine("当前黑名单规则数量: ${getBlacklistPatterns().size}")
        sb.appendLine()
        sb.appendLine("如需调整黑名单，请访问:")
        sb.appendLine("https://github.com/Monica-Pass/Monica-for-Android/issues")
        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine()
        sb.appendLine("报告生成完成 ✅")
        
        // ============ 保存文件 ============
        val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val fileName = "monica_apps_${dateStr}.txt"
        val file = java.io.File(context.getExternalFilesDir(null), fileName)
        file.writeText(sb.toString())
        
        // ============ 成功提示 ============
        withContext(Dispatchers.Main) {
            val message = buildString {
                appendLine(context.getString(R.string.export_data_success))
                appendLine()
                appendLine(context.getString(R.string.app_selector_total_apps, totalApps))
                appendLine(context.getString(R.string.app_selector_found_apps, visibleCount))
                appendLine("Hidden: $hiddenCount")
                appendLine()
                appendLine("Path:")
                appendLine(file.absolutePath)
            }
            
            android.widget.Toast.makeText(
                context,
                message,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        
    } catch (e: Exception) {
        android.util.Log.e("AppSelector", "导出失败", e)
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                context.getString(
                    R.string.save_failed_with_error,
                    e.message ?: context.getString(R.string.import_data_unknown_error)
                ),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}

/**
 * 获取黑名单规则列表（用于统计）
 */
private fun getBlacklistPatterns(): List<String> {
    return listOf(
        "android",
        "com.android.systemui",
        "com.android.internal",
        "com.android.packageinstaller",
        "com.android.defcontainer",
        "com.google.android.packageinstaller",
        "com.android.shell",
        "com.android.sharedstoragebackup",
        "com.android.wallpaperbackup",
        "com.android.printspooler",
        "com.android.vpndialogs",
        "com.android.location.fused",
        "com.android.externalstorage",
        "com.android.htmlviewer",
        "com.android.mms.service",
        "com.android.phone",
        "com.android.providers.",
        "com.android.server.",
        "com.android.backupconfirm",
        "com.android.certinstaller",
        "com.android.keychain",
        "com.android.wallpaper.livepicker",
        "com.android.wallpapercropper",
        "com.google.android.gsf",
        "com.google.android.gms",
        "com.google.android.partnersetup",
        "com.google.android.syncadapters.",
        "com.google.android.configupdater",
        "com.google.android.onetimeinitializer",
        "com.google.android.backuptransport",
        "com.google.android.tts",
        "com.google.android.webview",
        "com.google.android.marvin.talkback",
        "com.google.android.projection.gearhead",
        "com.google.ar.core",
        "com.android.companiondevicemanager",
        "com.google.android.feedback",
        "com.google.android.printservice.recommendation",
        "com.android.cts.",
        "com.android.development",
        "com.android.dreams.",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.android.managedprovisioning",
        "com.android.proxyhandler",
        "com.android.statementservice",
        "com.android.stk",
        "com.android.nfc",
        "com.android.bluetoothmidiservice",
        "com.android.bluetoothkeepalive",
        "com.android.traceur",
        "com.android.wallpaper",
        "com.android.settings.intelligence",
        "com.qualcomm.qti.",
        "com.qualcomm.timeservice",
        "com.qti.",
    )
}
