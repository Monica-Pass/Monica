package takagi.ru.monica.update

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import java.util.function.Consumer
import takagi.ru.monica.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onSkip: () -> Unit,
    onDetails: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val result = state.result
    val available = result?.isUpdateAvailable == true
    val configuration = LocalConfiguration.current
    val dialogDensity = LocalDensity.current
    val dialogContext = LocalContext.current
    val dialogDirection = LocalLayoutDirection.current
    val maximumHeight = (configuration.screenHeightDp.dp - 32.dp).coerceAtLeast(96.dp)
    val scrollWholeDialog = configuration.screenHeightDp < 520 ||
        (LocalDensity.current.fontScale >= 1.3f && configuration.screenHeightDp < 700)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.Inherit),
    ) {
        CompositionLocalProvider(
            LocalDensity provides dialogDensity,
            LocalConfiguration provides configuration,
            LocalContext provides dialogContext,
            LocalLayoutDirection provides dialogDirection,
        ) {
        UpdateBackdropBlur()
        Surface(
            modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp)
                .fillMaxWidth().heightIn(max = maximumHeight).testTag("app-update-dialog"),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.let {
                if (scrollWholeDialog) it.testTag("app-update-scroll-container").verticalScroll(rememberScrollState()) else it
            }.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Default.Update, null, Modifier.padding(12.dp).size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(
                        stringResource(when {
                            state.error != null && result == null -> R.string.update_check_failed_title
                            available -> R.string.update_check_update_available_title
                            else -> R.string.update_check_no_update_title
                        }), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (result != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        VersionBadge(result.currentVersion, false)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.align(Alignment.CenterVertically).size(20.dp))
                        VersionBadge(result.latestVersion, available)
                    }
                }
                var truncated by remember(result?.releaseIdentity) { mutableStateOf(false) }
                Column(
                    Modifier.fillMaxWidth().testTag("app-update-notes").let {
                        if (scrollWholeDialog) it else it.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    },
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.error != null) Text(
                        stringResource(R.string.update_check_failed_message, state.error),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    )
                    if (result != null) {
                        if (!available) Text(stringResource(R.string.update_check_no_update_message))
                        if (result.isFdroid) Text(stringResource(R.string.update_fdroid_source), style = MaterialTheme.typography.bodySmall)
                        if (!result.releaseNotes.isNullOrBlank()) {
                            Text(stringResource(R.string.update_whats_new), style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary)
                            ReleaseNotesMarkdown(result.releaseNotes, onOpenLink = onOpenLink, onTruncated = { truncated = it })
                        } else if (available) Text(stringResource(R.string.update_notes_unavailable))
                        if (truncated) Text(stringResource(R.string.update_notes_truncated), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.downloading) {
                    Column(Modifier.fillMaxWidth().testTag("app-update-progress"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val progress = state.progress
                        Text(stringResource(R.string.update_download_downloading), style = MaterialTheme.typography.labelLarge)
                        if (progress?.hasTotal == true) {
                            LinearProgressIndicator(progress = { progress.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${(progress.fraction * 100).toInt().coerceIn(0, 100)}%", style = MaterialTheme.typography.bodySmall)
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (available) {
                    Button(onClick = onUpdate, enabled = !state.downloading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("app-update-primary")) {
                        Text(stringResource(if (result!!.isFdroid) R.string.update_open_fdroid else R.string.update_now))
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = onSkip, enabled = !state.downloading, modifier = Modifier.testTag("app-update-skip")) {
                            Text(stringResource(R.string.update_skip_version))
                        }
                        TextButton(onClick = onDetails, enabled = !state.downloading, modifier = Modifier.testTag("app-update-details")) {
                            Text(stringResource(R.string.update_view_details))
                        }
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("app-update-later")) {
                            Text(stringResource(if (state.downloading) R.string.cancel else R.string.update_later))
                        }
                    }
                } else {
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.ok)) }
                }
            }
        }
        }
    }
}

@Composable
private fun VersionBadge(version: String, highlighted: Boolean) {
    Surface(shape = RoundedCornerShape(10.dp), color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(version, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.titleSmall)
    }
}

/** System Gaussian blur without capturing or retaining any underlying vault pixels. */
@Composable
private fun UpdateBackdropBlur() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (Build.VERSION.SDK_INT >= 31 && window != null) {
            val manager = context.getSystemService(WindowManager::class.java)
            val oldRadius = window.attributes.blurBehindRadius
            val oldDim = window.attributes.dimAmount
            val hadBlur = window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
            fun apply(enabled: Boolean) {
                val params = window.attributes
                params.blurBehindRadius = if (enabled) (24 * context.resources.displayMetrics.density).toInt() else 0
                params.dimAmount = if (enabled) 0.24f else 0.48f
                window.attributes = params
                if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
            val listener = Consumer<Boolean> { apply(it) }
            apply(manager.isCrossWindowBlurEnabled)
            manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
            onDispose {
                manager.removeCrossWindowBlurEnabledListener(listener)
                val params = window.attributes
                params.blurBehindRadius = oldRadius
                params.dimAmount = oldDim
                window.attributes = params
                if (hadBlur) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
        } else onDispose { }
    }
}
