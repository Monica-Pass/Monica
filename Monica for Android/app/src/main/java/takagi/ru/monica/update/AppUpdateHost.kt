package takagi.ru.monica.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import takagi.ru.monica.BuildConfig
import takagi.ru.monica.R
import takagi.ru.monica.utils.UpdateChecker

val LocalAppUpdateController = staticCompositionLocalOf<AppUpdateController?> { null }

@Composable
fun rememberAppUpdateController(canInstall: () -> Boolean): AppUpdateController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val currentCanInstall = rememberUpdatedState(canInstall)
    return remember(context, scope) {
        val preferences = UpdatePreferences(context)
        AppUpdateController(
            scope = scope,
            readPreferences = preferences::read,
            writeEnabled = preferences::setAutoCheckEnabled,
            writeAttempt = preferences::recordAttempt,
            writeSkipped = preferences::skipRelease,
            fetch = { UpdateChecker.checkLatestRelease(BuildConfig.BASE_VERSION_NAME.ifBlank { BuildConfig.VERSION_NAME }) },
            download = { result, onProgress ->
                var file: File? = null
                try {
                    withContext(Dispatchers.IO) {
                        file = UpdateChecker.downloadApk(
                            requireNotNull(result.apkDownloadUrl), File(context.cacheDir, "update_apk"),
                            result.apkAssetName ?: "Monica-update.apk",
                        ) { progress -> withContext(Dispatchers.Main.immediate) { onProgress(progress) } }.getOrThrow()
                        UpdateChecker.validateDownloadedApk(context, requireNotNull(file)).getOrThrow()
                        ensureActive()
                        Result.success(requireNotNull(file))
                    }
                } catch (cancelled: CancellationException) {
                    file?.delete()
                    throw cancelled
                } catch (error: Exception) {
                    file?.delete()
                    Result.failure(error)
                }
            },
            install = { file ->
                if (!currentCanInstall.value()) throw CancellationException("Update installation is no longer foreground-authorized")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            },
        )
    }
}

@Composable
fun AppUpdateHost(controller: AppUpdateController, eligible: Boolean, allowAutomatic: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentEligible by rememberUpdatedState(eligible)
    val currentAutomatic by rememberUpdatedState(allowAutomatic)
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    SideEffect { controller.setEnvironment(eligible, foreground, allowAutomatic) }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            controller.setEnvironment(currentEligible, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), currentAutomatic)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.setEnvironment(false, false, false) }
    }
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val openUrl: (String) -> Unit = { raw ->
        val uri = Uri.parse(raw)
        if (uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure {
                Toast.makeText(context, R.string.cannot_open_browser, Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (eligible && foreground && state.dialogVisible) AppUpdateDialog(
        state = state,
        onDismiss = controller::dismiss,
        onSkip = controller::skipVersion,
        onDetails = { state.result?.let { openUrl(it.detailsUrl) } },
        onOpenLink = openUrl,
        onUpdate = {
            state.result?.let { result ->
                if (result.isFdroid || result.apkDownloadUrl.isNullOrBlank()) openUrl(result.releaseUrl)
                else if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, R.string.update_install_permission_required, Toast.LENGTH_LONG).show()
                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                } else controller.startDownload()
            }
        },
    )
}
