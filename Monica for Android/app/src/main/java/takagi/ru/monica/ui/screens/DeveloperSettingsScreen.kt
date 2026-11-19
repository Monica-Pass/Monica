package takagi.ru.monica.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import takagi.ru.monica.ui.components.SettingsPanelGroup
import takagi.ru.monica.ui.components.SettingsPanelRow
import takagi.ru.monica.ui.components.SettingsContentRow
import takagi.ru.monica.ui.components.SettingsSubpageTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.BuildConfig
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.core.AutofillLogger
import takagi.ru.monica.bitwarden.service.BitwardenDiagLogger
import takagi.ru.monica.bitwarden.service.BitwardenSyncForensicsLogger
import takagi.ru.monica.data.AppLauncherLabel
import takagi.ru.monica.mdbx.MdbxDiagLogger
import takagi.ru.monica.passkey.PasskeyValidationDiagnostics
import takagi.ru.monica.security.SecurityDiagLogger
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.steam.diagnostics.SteamDiagLogger
import takagi.ru.monica.viewmodel.SettingsViewModel

/**
 * 开发者设置页面
 * 包含日志查看、清除以及开发者专用功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToMdbx: () -> Unit = {},
    onNavigateToLogs: () -> Unit
) {
    val context = LocalContext.current
    val securityManager = remember(context) { SecurityManager(context.applicationContext) }
    val settings by viewModel.settings.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var confirmClearLogs by remember { mutableStateOf(false) }
    var clearingLogs by remember { mutableStateOf(false) }

    var disablePasswordVerification by remember { mutableStateOf(settings.disablePasswordVerification) }
    var showHyperOsCompatibility by rememberSaveable { mutableStateOf(false) }
    var showDefaultManager by remember { mutableStateOf(false) }
    if (showDefaultManager) DefaultPasswordManagerSheet { showDefaultManager = false }
    var bitwardenSyncForensicsEnabled by remember {
        mutableStateOf(settings.bitwardenSyncForensicsEnabled)
    }
    var bitwardenSyncForensicsDirectoryUri by remember {
        mutableStateOf(settings.bitwardenSyncForensicsDirectoryUri)
    }
    var bitwardenSyncForensicsRawCaptureEnabled by remember {
        mutableStateOf(settings.bitwardenSyncForensicsRawCaptureEnabled)
    }
    var appLauncherLabel by remember {
        mutableStateOf(settings.appLauncherLabel)
    }
    LaunchedEffect(
        settings.disablePasswordVerification,
        settings.bitwardenSyncForensicsEnabled,
        settings.bitwardenSyncForensicsDirectoryUri,
        settings.bitwardenSyncForensicsRawCaptureEnabled,
        settings.appLauncherLabel
    ) {
        disablePasswordVerification = settings.disablePasswordVerification
        bitwardenSyncForensicsEnabled = settings.bitwardenSyncForensicsEnabled
        bitwardenSyncForensicsDirectoryUri = settings.bitwardenSyncForensicsDirectoryUri
        bitwardenSyncForensicsRawCaptureEnabled = settings.bitwardenSyncForensicsRawCaptureEnabled
        appLauncherLabel = settings.appLauncherLabel
    }

    if (showHyperOsCompatibility) {
        takagi.ru.monica.ui.navigation.MonicaPageContent("hyperos-compatibility", animateInitial = true) {
            HyperOsCompatibilitySettingsScreen(onNavigateBack = { showHyperOsCompatibility = false })
        }
        return
    }

    val forensicsDirectoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            val permissionsResult = runCatching {
                val flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }

            val uriString = uri.toString()
            bitwardenSyncForensicsDirectoryUri = uriString
            viewModel.updateBitwardenSyncForensicsDirectoryUri(uriString)

            val toastMessage = if (permissionsResult.isSuccess) {
                context.getString(R.string.developer_bitwarden_forensics_dir_saved)
            } else {
                context.getString(R.string.developer_bitwarden_forensics_dir_permission_warning)
            }
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
        }
    }

    val clearTitle = stringResource(R.string.developer_clear_log_buffer)
    val clearMessage = stringResource(R.string.developer_logs_clear_confirmation)
    val clearAction = stringResource(R.string.clear)
    val cancelAction = stringResource(R.string.cancel)
    if (confirmClearLogs) AlertDialog(
        onDismissRequest = { confirmClearLogs = false },
        icon = { Icon(Icons.Default.DeleteSweep, null) },
        title = { Text(clearTitle) },
        text = { Text(clearMessage) },
        confirmButton = { TextButton(onClick = {
            confirmClearLogs = false
            clearingLogs = true
            scope.launch {
                try {
                    val result = DeveloperLogDebugHelper.clearLogs(context)
                    Toast.makeText(context, context.getString(
                        if (result.logcatCleared) R.string.developer_log_buffer_cleared
                        else R.string.developer_clear_failed, result.reason.orEmpty()), Toast.LENGTH_LONG).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(context, context.getString(R.string.developer_clear_failed,
                        error.message.orEmpty()), Toast.LENGTH_LONG).show()
                } finally { clearingLogs = false }
            }
        }) { Text(clearAction) } },
        dismissButton = { TextButton(onClick = { confirmClearLogs = false }) {
            Text(cancelAction)
        } })

    Scaffold(topBar = {
        SettingsSubpageTopBar(stringResource(R.string.developer_settings), onNavigateBack)
    }) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues).verticalScroll(scrollState)
            .padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsPanelGroup(stringResource(R.string.developer_log_debugging)) {
                SettingsPanelRow(Icons.Default.BugReport,
                    Setting.DEVELOPER_VIEW_LOGS,
                    Setting.DEVELOPER_VIEW_LOGS.summary(), onClick = onNavigateToLogs)
                SettingsPanelRow(Icons.Default.DeleteSweep,
                    Setting.DEVELOPER_CLEAR_LOG_BUFFER,
                    Setting.DEVELOPER_CLEAR_LOG_BUFFER.summary(),
                    onClick = { confirmClearLogs = true }, enabled = !clearingLogs)

            }
            SettingsPanelGroup(stringResource(R.string.developer_verification_group)) {
                SettingsPanelRow(
                    icon = Icons.Default.Lock,
                    setting = Setting.DEVELOPER_DISABLE_PASSWORD_VERIFICATION,
                    checked = disablePasswordVerification,
                    onCheckedChange = { enabled ->
                        android.util.Log.d("DeveloperSettings", "Toggling password verification: $enabled")
                        scope.launch {
                            val configured = securityManager.configureDeveloperVerificationBypass(enabled)
                            if (configured) {
                                disablePasswordVerification = enabled
                                viewModel.updateDisablePasswordVerification(enabled)
                                android.util.Log.d(
                                    "DeveloperSettings",
                                    "Password verification setting updated to: $enabled"
                                )
                            } else {
                                disablePasswordVerification = settings.disablePasswordVerification
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.developer_disable_password_verification_failed),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )

                SettingsPanelRow(
                    icon = Icons.Default.Lock,
                    setting = Setting.DEFAULT_MANAGER_TITLE,
                    onClick = { showDefaultManager = true }
                )
            }
            SettingsPanelGroup(stringResource(R.string.developer_forensics_group)) {
                SettingsPanelRow(
                    icon = Icons.Default.BugReport,
                    setting = Setting.DEVELOPER_BITWARDEN_FORENSICS_TOGGLE,
                    checked = bitwardenSyncForensicsEnabled,
                    onCheckedChange = { enabled ->
                        bitwardenSyncForensicsEnabled = enabled
                        scope.launch {
                            viewModel.updateBitwardenSyncForensicsEnabled(enabled)
                        }
                    }
                )

                SettingsPanelRow(
                    icon = Icons.Default.WarningAmber,
                    setting = Setting.DEVELOPER_BITWARDEN_FORENSICS_RAW_TOGGLE,
                    checked = bitwardenSyncForensicsRawCaptureEnabled,
                    onCheckedChange = { enabled ->
                        bitwardenSyncForensicsRawCaptureEnabled = enabled
                        scope.launch {
                            viewModel.updateBitwardenSyncForensicsRawCaptureEnabled(enabled)
                        }
                    }
                )

                val directorySubtitle = bitwardenSyncForensicsDirectoryUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let { rawUri ->
                        context.getString(
                            R.string.developer_bitwarden_forensics_dir_selected,
                            summarizeDocumentTreeUri(rawUri)
                        )
                    }
                    ?: stringResource(R.string.developer_bitwarden_forensics_dir_not_set)

                SettingsPanelRow(
                    icon = Icons.Default.Share,
                    setting = Setting.DEVELOPER_BITWARDEN_FORENSICS_DIR,
                    subtitle = directorySubtitle,
                    onClick = {
                        val initialUri = bitwardenSyncForensicsDirectoryUri
                            ?.takeIf { it.isNotBlank() }
                            ?.let { Uri.parse(it) }
                        forensicsDirectoryPickerLauncher.launch(initialUri)
                    }
                )

                SettingsPanelRow(
                    icon = Icons.Default.DeleteSweep,
                    setting = Setting.DEVELOPER_BITWARDEN_FORENSICS_CLEAR_DIR,
                    onClick = {
                        bitwardenSyncForensicsDirectoryUri = null
                        scope.launch {
                            viewModel.updateBitwardenSyncForensicsDirectoryUri(null)
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.developer_bitwarden_forensics_dir_cleared),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
            SettingsPanelGroup(stringResource(R.string.developer_functions)) {
                SettingsPanelRow(
                    icon = Icons.Default.Science,
                    setting = Setting.HYPEROS_COMPATIBILITY_TITLE,
                    onClick = { showHyperOsCompatibility = true },
                    modifier = Modifier.testTag("developer_hyperos_compatibility_entry")
                )
                SettingsPanelRow(
                    icon = Icons.Default.AutoAwesome,
                    setting = Setting.DEVELOPER_LAUNCHER_NAME_USE_PASS,
                    checked = appLauncherLabel == AppLauncherLabel.MONICA_PASS,
                    onCheckedChange = { enabled ->
                        val nextLabel = if (enabled) {
                            AppLauncherLabel.MONICA_PASS
                        } else {
                            AppLauncherLabel.MONICA
                        }
                        appLauncherLabel = nextLabel
                        scope.launch {
                            viewModel.updateAppLauncherLabel(nextLabel)
                        }
                    }
                )
                SettingsPanelRow(
                    icon = Icons.Default.Science,
                    setting = Setting.MDBX_FORMAT_TITLE,
                    onClick = onNavigateToMdbx
                )
                // 显示会话状态
                if (BuildConfig.DEBUG) {
                    val sessionUnlocked by SessionManager.isUnlocked.collectAsState()
                    val remainingMinutes = SessionManager.getRemainingMinutes()

                    SettingsContentRow(
                        icon = if (sessionUnlocked) Icons.Default.LockOpen else Icons.Default.Lock, title = stringResource(R.string.developer_session_status),
                        subtitle = if (sessionUnlocked) {
                            stringResource(R.string.developer_session_unlocked_remaining, remainingMinutes)
                        } else {
                            stringResource(R.string.developer_session_locked)
                        },
                        onClick = {
                            // 手动锁定/解锁会话（用于测试）
                            if (sessionUnlocked) {
                                SessionManager.markLocked()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.developer_session_locked_toast),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                SessionManager.markUnlocked()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.developer_session_unlocked_toast),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(stringResource(R.string.developer_warning), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun summarizeDocumentTreeUri(uriRaw: String): String {
    val parsed = runCatching { Uri.parse(uriRaw) }.getOrNull()
    val name = parsed?.lastPathSegment
        ?.substringAfterLast(':')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
    return name ?: uriRaw.take(64)
}

internal data class ClearLogsResult(
    val logcatCleared: Boolean,
    val reason: String?
)

internal object DeveloperLogDebugHelper {
    private const val LOG_LINE_LIMIT = 1200
    private const val SHARE_DIR = "temp_share"
    private const val SHARE_PREFIX = "monica_logs_"
    private val AUTOFILL_LOG_TAGS = arrayOf(
        "MonicaAutofill:V",
        "AutofillPicker:V",
        "AutofillPickerV2:V",
        "EnhancedParser:V",
        "EnhancedFieldParser:V",
        "SmartFieldDetector:V",
        "*:S"
    )
    private val timeFormatter get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileFormatter get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    suspend fun collectLogs(context: Context): DeveloperLogSnapshot = withContext(Dispatchers.IO) {
        runCatching { AutofillLogger.initialize(context.applicationContext) }
        runCatching { BitwardenDiagLogger.initialize(context.applicationContext) }
        runCatching { BitwardenSyncForensicsLogger.initialize(context.applicationContext) }
        runCatching { MdbxDiagLogger.initialize(context.applicationContext) }
        runCatching { takagi.ru.monica.lansync.DeviceSyncDiagLogger.initialize(context.applicationContext) }
        runCatching { SecurityDiagLogger.initialize(context.applicationContext) }
        runCatching { SteamDiagLogger.initialize(context.applicationContext) }
        val autofillTagLogs = readAutofillTagLogs()
        val appProcessLogs = readLogcat(
            arrayOf(
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "--pid",
                android.os.Process.myPid().toString(),
                "-t",
                "400",
                "*:V"
            )
        )
        val crashLogs = readLogcat(
            arrayOf(
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "-t",
                "300",
                "AndroidRuntime:E",
                "System.err:W",
                "libc:E",
                "*:S"
            )
        )
        val selectedLogs = buildString {
            val filteredAppProcessLogs = filterExpectedSystemNoise(appProcessLogs)
            val filteredCrashLogs = filterExpectedSystemNoise(crashLogs)
            if (autofillTagLogs.isNotBlank()) {
                appendLine("---- autofill-tags ----")
                appendLine(autofillTagLogs.trim())
            }
            if (filteredAppProcessLogs.isNotBlank()) {
                if (isNotBlank()) appendLine()
                appendLine("---- app-process ----")
                appendLine(filteredAppProcessLogs)
            }
            if (filteredCrashLogs.isNotBlank()) {
                if (isNotBlank()) appendLine()
                appendLine("---- crash/system ----")
                appendLine(filteredCrashLogs)
            }
        }.trim()

        val autofillLogs = runCatching {
            AutofillLogger.exportLogs(300)
        }.getOrElse {
            "AutofillLogger unavailable: ${it.message}"
        }
        val persistedAutofillLogs = runCatching {
            AutofillLogger.exportPersistedLogs(1200)
        }.getOrElse {
            "Autofill persisted logs unavailable: ${it.message}"
        }
        val persistedBitwardenLogs = runCatching {
            BitwardenDiagLogger.exportPersistedLogs(2000)
        }.getOrElse {
            "Bitwarden persisted logs unavailable: ${it.message}"
        }
        val persistedForensicsLogs = runCatching {
            BitwardenSyncForensicsLogger.exportPersistedLogs(context, 12)
        }.getOrElse {
            "Bitwarden sync forensics logs unavailable: ${it.message}"
        }
        val persistedMdbxLogs = runCatching {
            MdbxDiagLogger.exportPersistedLogs(2000)
        }.getOrElse {
            "MDBX persisted logs unavailable: ${it.message}"
        }
        val persistedSecurityLogs = runCatching {
            SecurityDiagLogger.exportPersistedLogs(2000)
        }.getOrElse {
            "Security persisted logs unavailable: ${it.message}"
        }
        val persistedSteamLogs = runCatching {
            SteamDiagLogger.exportPersistedLogs(2000)
        }.getOrElse {
            "Steam persisted logs unavailable: ${it.message}"
        }
        val persistedPasskeyLogs = runCatching {
            PasskeyValidationDiagnostics.buildReport(context)
        }.getOrElse {
            "Passkey diagnostics unavailable: ${it.message}"
        }

        val report = buildString {
            appendLine("=== Monica Developer Log Report ===")
            appendLine("exportedAt=${timeFormatter.format(Date())}")
            appendLine("package=${context.packageName}")
            appendLine("appVersion=${BuildConfig.FULL_VERSION_NAME}")
            appendLine("displayVersion=${BuildConfig.VERSION_NAME}")
            appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("=== System Logcat ===")
            if (selectedLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(selectedLogs.trim())
            }
            appendLine()
            appendLine("=== Autofill Structured Logs ===")
            appendLine(autofillLogs.trim())
            appendLine()
            appendLine("=== Autofill Persisted Logs ===")
            if (persistedAutofillLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(persistedAutofillLogs.trim())
            }
            appendLine()
            appendLine("=== Bitwarden Persisted Logs ===")
            if (persistedBitwardenLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(persistedBitwardenLogs.trim())
            }
            appendLine()
            appendLine("=== Bitwarden Sync Forensics ===")
            if (persistedForensicsLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(persistedForensicsLogs.trim())
            }
            appendLine()
            appendLine("=== MDBX Persisted Logs ===")
            if (persistedMdbxLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(persistedMdbxLogs.trim())
            }
            appendLine()
            appendLine("=== Nearby Device Sync Logs ===")
            appendLine(takagi.ru.monica.lansync.DeviceSyncDiagLogger.exportPersistedLogs())
            appendLine()
            appendLine("=== Security Persisted Logs ===")
            if (persistedSecurityLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(persistedSecurityLogs.trim())
            }
            appendLine()
            appendLine("=== Steam Persisted Logs ===")
            if (persistedSteamLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(persistedSteamLogs.trim())
            }
            appendLine()
            appendLine("=== Passkey Persisted Logs ===")
            if (persistedPasskeyLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(persistedPasskeyLogs.trim())
            }
        }

        val sources = linkedMapOf(
            DeveloperLogSource.SYSTEM to selectedLogs,
            DeveloperLogSource.AUTOFILL to persistedAutofillLogs,
            DeveloperLogSource.BITWARDEN to persistedBitwardenLogs,
            DeveloperLogSource.FORENSICS to persistedForensicsLogs,
            DeveloperLogSource.MDBX to persistedMdbxLogs,
            DeveloperLogSource.SECURITY to persistedSecurityLogs,
            DeveloperLogSource.STEAM to persistedSteamLogs,
            DeveloperLogSource.PASSKEY to persistedPasskeyLogs,
        )
        DeveloperLogSnapshot(report, sources.flatMap { (source, raw) ->
            val persisted = parseDeveloperLogEvents(raw, source)
            (if (source == DeveloperLogSource.AUTOFILL) mergeDeveloperAutofillEvents(
                persisted, parseDeveloperLogEvents(autofillLogs, source)
            ) else persisted).asReversed()
        }, System.currentTimeMillis())
    }

    suspend fun clearLogs(context: Context): ClearLogsResult = withContext(Dispatchers.IO) {
        runCatching {
            AutofillLogger.clear()
        }
        runCatching {
            BitwardenDiagLogger.clear()
        }
        runCatching {
            BitwardenSyncForensicsLogger.clear(context.applicationContext)
        }
        runCatching {
            MdbxDiagLogger.clear()
        }
        runCatching { takagi.ru.monica.lansync.DeviceSyncDiagLogger.clear() }
        runCatching {
            SecurityDiagLogger.clear()
        }
        runCatching {
            SteamDiagLogger.clear()
        }

        val process = runCatching {
            ProcessBuilder("logcat", "-c")
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            return@withContext ClearLogsResult(
                logcatCleared = false,
                reason = error.message
            )
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
        if (exitCode == 0) {
            ClearLogsResult(logcatCleared = true, reason = null)
        } else {
            ClearLogsResult(
                logcatCleared = false,
                reason = if (output.isNotBlank()) output else "exit=$exitCode"
            )
        }
    }

    suspend fun createShareIntent(context: Context, report: String): Intent = withContext(Dispatchers.IO) {
        val shareDir = File(context.cacheDir, SHARE_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
        cleanupOldFiles(shareDir)

        val fileName = "${SHARE_PREFIX}${fileFormatter.format(Date())}.txt"
        val file = File(shareDir, fileName)
        file.writeText(report)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.developer_share_subject))
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, buildDeveloperLogShareFallback(report))
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun cleanupOldFiles(dir: File) {
        val exported = dir.listFiles { file ->
            file.isFile && file.name.startsWith(SHARE_PREFIX) && file.name.endsWith(".txt")
        } ?: return
        if (exported.size <= 10) return
        exported.sortedByDescending { it.lastModified() }
            .drop(10)
            .forEach { stale ->
                runCatching { stale.delete() }
            }
    }

    private fun readLogcat(command: Array<String>): String {
        val process = runCatching {
            ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return ""

        val output = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")

        runCatching { process.waitFor() }
        return output.trim()
    }

    /**
     * Android and vendor ROMs may emit predictive-back capability probes even though Monica
     * explicitly disables that API. They are expected framework noise, not app failures.
     * Keep this list deliberately narrow so real navigation and crash diagnostics remain intact.
     */
    private fun filterExpectedSystemNoise(raw: String): String {
        if (raw.isBlank()) return raw
        val predictiveBackNoise = listOf(
            "OnBackInvokedCallback",
            "OnBackAnimationCallback",
            "OnBackInvokedDispatcher",
            "BackEvent",
            "predictive back",
            "predictiveBack",
            "enableOnBackInvokedCallback"
        )
        return raw.lineSequence()
            .filter { line ->
                predictiveBackNoise.none { marker -> line.contains(marker, ignoreCase = true) }
            }
            .joinToString("\n")
            .trim()
    }

    private fun readAutofillTagLogs(): String {
        val command = mutableListOf(
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "-t",
            LOG_LINE_LIMIT.toString()
        ).apply {
            addAll(AUTOFILL_LOG_TAGS)
        }.toTypedArray()
        return readLogcat(command)
    }

}

private const val DEVELOPER_LOG_SHARE_TEXT_LIMIT = 48_000
private const val DEVELOPER_LOG_SHARE_HEADER_LIMIT = 4_000

internal fun buildDeveloperLogShareFallback(
    report: String,
    maxChars: Int = DEVELOPER_LOG_SHARE_TEXT_LIMIT,
): String {
    val normalized = report.trim()
    if (normalized.length <= maxChars) return normalized

    val marker = "\n\n=== Share text truncated; full report is attached ===\n\n"
    val headerLength = minOf(DEVELOPER_LOG_SHARE_HEADER_LIMIT, maxChars / 3)
    val tailLength = (maxChars - headerLength - marker.length).coerceAtLeast(0)
    return buildString(maxChars) {
        append(normalized.take(headerLength))
        append(marker)
        append(normalized.takeLast(tailLength))
    }.take(maxChars)
}
