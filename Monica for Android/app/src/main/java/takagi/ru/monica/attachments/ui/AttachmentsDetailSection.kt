package takagi.ru.monica.attachments.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.data.model.CardFaceAttachment

/**
 * 密码详情页的附件区块（只读 + 下载/预览/保存）。
 *
 * - LOCAL 附件打开即预览/分享；
 * - Bitwarden / KeePass 附件在 PENDING 状态下可点击触发下载，下载完成后自动预览。
 * - 预览对话框中提供"保存到设备"按钮，通过系统文件选择器导出。
 * - 无附件时不渲染，避免给没有附件的密码造成视觉噪音。
 */
@Composable
fun AttachmentsDetailSection(
    passwordId: Long,
    modifier: Modifier = Modifier,
    bitwardenContext: AttachmentFacade.BitwardenContext? = null,
    keepassContext: AttachmentFacade.KeePassContext? = null,
    hideManagedCardFaces: Boolean = false
) = AttachmentsDetailSection(
    owner = AttachmentOwner.password(passwordId),
    modifier = modifier,
    bitwardenContext = bitwardenContext,
    keepassContext = keepassContext,
    hideManagedCardFaces = hideManagedCardFaces
)

@Composable
fun AttachmentsDetailSection(
    owner: AttachmentOwner,
    modifier: Modifier = Modifier,
    bitwardenContext: AttachmentFacade.BitwardenContext? = null,
    keepassContext: AttachmentFacade.KeePassContext? = null,
    excludedFileNames: Set<String> = emptySet(),
    hideManagedCardFaces: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val facade = remember(context) { AttachmentContainer.facade(context) }
    val allAttachments by facade.observe(owner).collectAsState(initial = emptyList())
    val attachments = allAttachments.filterNot {
        it.fileName in excludedFileNames ||
            (hideManagedCardFaces && CardFaceAttachment.isManagedFileName(it.fileName))
    }

    if (attachments.isEmpty()) return

    // 预览对话框状态
    var previewState by remember { mutableStateOf<PreviewState?>(null) }

    // 保存到设备的文件选择器
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { targetUri ->
        if (targetUri == null) return@rememberLauncherForActivityResult
        val sourceUri = previewState?.uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        context.contentResolver.openOutputStream(targetUri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.attachment_save_to_device) + " OK",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { e ->
                Toast.makeText(
                    context,
                    resolveErrorMessage(context, e),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 预览对话框
    previewState?.let { state ->
        AttachmentPreviewDialog(
            previewUri = state.uri,
            mimeType = state.mimeType,
            fileName = state.fileName,
            onDismiss = { previewState = null },
            onOpenExternally = {
                val uri = state.uri
                previewState = null
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, state.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            },
            onSaveToDevice = {
                saveLauncher.launch(state.fileName)
            }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.attachments_section_title, attachments.size),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            attachments.forEach { attachment ->
                val isDownloading = attachment.downloadStateEnum == AttachmentDownloadState.DOWNLOADING
                AttachmentRow(
                    attachment = attachment,
                    enabled = !isDownloading,
                    onClick = {
                        scope.launch {
                            handleAttachmentClick(
                                facade = facade,
                                attachment = attachment,
                                bitwardenContext = bitwardenContext,
                                keepassContext = keepassContext,
                                onPreviewReady = { uri, mimeType, fileName ->
                                    if (isPreviewable(mimeType)) {
                                        previewState = PreviewState(uri, mimeType, fileName)
                                    } else {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, mimeType)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                },
                                onError = { e ->
                                    Toast.makeText(
                                        context,
                                        resolveErrorMessage(context, e),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    },
                    onRetry = {
                        scope.launch {
                            runCatching {
                                facade.retryFailed(
                                    attachment.id,
                                    bitwardenContext = bitwardenContext,
                                    keepassContext = keepassContext
                                )
                            }.onFailure { e ->
                                Toast.makeText(
                                    context,
                                    resolveErrorMessage(context, e),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            }
        }
    }
}

private data class PreviewState(
    val uri: Uri,
    val mimeType: String,
    val fileName: String
)

private suspend fun handleAttachmentClick(
    facade: AttachmentFacade,
    attachment: Attachment,
    bitwardenContext: AttachmentFacade.BitwardenContext?,
    keepassContext: AttachmentFacade.KeePassContext?,
    onPreviewReady: (Uri, String, String) -> Unit,
    onError: (Throwable) -> Unit
) {
    runCatching {
        val ready = facade.ensureDownloaded(
            attachment.id,
            bitwardenContext = bitwardenContext,
            keepassContext = keepassContext
        )
        val uri = facade.openForPreview(
            ready.id,
            bitwardenContext = bitwardenContext,
            keepassContext = keepassContext
        )
        onPreviewReady(uri, ready.mimeType, ready.fileName)
    }.onFailure { e ->
        onError(e)
    }
}

private fun isPreviewable(mimeType: String): Boolean {
    return mimeType.startsWith("image/") ||
        mimeType == "application/pdf" ||
        mimeType.startsWith("text/")
}

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    enabled: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit
) {
    val isPending = attachment.downloadStateEnum == AttachmentDownloadState.PENDING
    val isDownloaded = attachment.downloadStateEnum == AttachmentDownloadState.DOWNLOADED
    val isDownloading = attachment.downloadStateEnum == AttachmentDownloadState.DOWNLOADING
    val isFailed = attachment.downloadStateEnum == AttachmentDownloadState.FAILED

    val clickable = (isPending || isDownloaded) && enabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = clickable) {
                onClick()
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatSecondary(attachment),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isPending) {
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "· ${stringResource(R.string.attachment_download)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isDownloading ->
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            isPending ->
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = stringResource(R.string.attachment_download),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            isFailed ->
                IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.attachment_retry)
                    )
                }
            isDownloaded -> Unit
        }
    }
}

private fun formatSecondary(attachment: Attachment): String {
    val sizeKb = (attachment.sizeBytes + 1023) / 1024
    val sizeText = when {
        attachment.sizeBytes <= 0 -> ""
        sizeKb >= 1024 -> "${sizeKb / 1024} MB"
        else -> "$sizeKb KB"
    }
    val sourceLabel = when (attachment.sourceEnum) {
        AttachmentSource.LOCAL -> "Local"
        AttachmentSource.BITWARDEN -> "Bitwarden"
        AttachmentSource.KEEPASS -> "KeePass"
    }
    return if (sizeText.isBlank()) sourceLabel else "$sourceLabel · $sizeText"
}

private fun resolveErrorMessage(context: android.content.Context, e: Throwable): String {
    val resId = when (e) {
        is AttachmentError.TooLarge -> R.string.attachment_error_too_large
        AttachmentError.QuotaExceeded -> R.string.attachment_error_quota_exceeded
        AttachmentError.PremiumRequired -> R.string.attachment_error_premium_required
        AttachmentError.Offline -> R.string.attachment_error_offline
        is AttachmentError.NetworkError -> R.string.attachment_error_network
        AttachmentError.CryptoError -> R.string.attachment_error_crypto
        AttachmentError.IoError -> R.string.attachment_error_io
        AttachmentError.KdbxLocked -> R.string.attachment_error_kdbx_locked
        AttachmentError.KdbxCapacityExceeded -> R.string.attachment_error_kdbx_capacity
        else -> R.string.attachment_error_io
    }
    return when (e) {
        is AttachmentError.TooLarge -> context.getString(resId, formatSizeHuman(e.limitBytes))
        is AttachmentError.NetworkError -> context.getString(resId, e.httpStatus?.toString() ?: "-")
        else -> context.getString(resId)
    }
}

private fun formatSizeHuman(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val mb = bytes / (1024 * 1024)
    return if (mb >= 1) "${mb} MB" else "${bytes / 1024} KB"
}
