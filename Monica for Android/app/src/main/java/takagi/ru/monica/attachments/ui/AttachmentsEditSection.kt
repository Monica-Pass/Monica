package takagi.ru.monica.attachments.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.facade.AttachmentSizeValidator
import takagi.ru.monica.attachments.facade.AttachmentUriMetadata
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.data.model.CardFaceAttachment

/**
 * 草稿模式下的附件记录：尚未真正入库的本地 Uri 引用。
 *
 * 页面保存密码后，调用方再统一把 [AttachmentPendingDraft.uri] 交给 [AttachmentFacade] 上传。
 */
data class AttachmentPendingDraft(
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long
)

/**
 * 密码编辑页的附件区块：支持 `ACTION_OPEN_DOCUMENT` 添加 + 删除。
 *
 * 两种工作模式：
 * - **已保存密码** (`passwordId > 0`)：选中的文件会立刻通过 [AttachmentFacade] 加密入库，UI 展示
 *   Room 中真实的 [Attachment] 列表，删除立即生效。
 * - **新建密码草稿** (`passwordId <= 0` 且提供了 [pendingDrafts])：选中的文件只缓存 Uri 到
 *   [pendingDrafts]，UI 展示草稿行，真正入库发生在父页面保存密码拿到新 id 之后
 *   （由调用方调用 [flushPendingDraftsTo] 完成）。
 */
@Composable
fun AttachmentsEditSection(
    passwordId: Long,
    isPlusActivated: Boolean,
    modifier: Modifier = Modifier,
    attachmentSource: AttachmentSource = AttachmentSource.LOCAL,
    bitwardenContext: AttachmentFacade.BitwardenContext? = null,
    bitwardenPremium: Boolean = true,
    keepassContext: AttachmentFacade.KeePassContext? = null,
    pendingDrafts: SnapshotStateList<AttachmentPendingDraft>? = null,
    excludedFileNames: Set<String> = emptySet(),
    hideManagedCardFaces: Boolean = false
) = AttachmentsEditSection(
    owner = passwordId.takeIf { it > 0L }?.let { AttachmentOwner.password(it) },
    isPlusActivated = isPlusActivated,
    modifier = modifier,
    attachmentSource = attachmentSource,
    bitwardenContext = bitwardenContext,
    bitwardenPremium = bitwardenPremium,
    keepassContext = keepassContext,
    pendingDrafts = pendingDrafts,
    excludedFileNames = excludedFileNames,
    hideManagedCardFaces = hideManagedCardFaces
)

@Composable
fun AttachmentsEditSection(
    owner: AttachmentOwner?,
    isPlusActivated: Boolean,
    modifier: Modifier = Modifier,
    attachmentSource: AttachmentSource = AttachmentSource.LOCAL,
    bitwardenContext: AttachmentFacade.BitwardenContext? = null,
    bitwardenPremium: Boolean = true,
    keepassContext: AttachmentFacade.KeePassContext? = null,
    pendingDrafts: SnapshotStateList<AttachmentPendingDraft>? = null,
    excludedFileNames: Set<String> = emptySet(),
    hideManagedCardFaces: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDraftMode = owner == null
    if (isDraftMode && pendingDrafts == null) {
        // 未提供草稿容器 → 直接隐藏（向后兼容旧调用点）
        return
    }
    val facade = remember(context) { AttachmentContainer.facade(context) }
    val persistedAttachments by if (isDraftMode) {
        remember { kotlinx.coroutines.flow.MutableStateFlow<List<Attachment>>(emptyList()) }
            .collectAsState()
    } else {
        facade.observe(requireNotNull(owner)).collectAsState(initial = emptyList())
    }

    var softLimitPending by remember { mutableStateOf<PendingUpload?>(null) }

    fun performUpload(uri: Uri, acceptSoftLimit: Boolean) {
        if (isDraftMode) {
            // 草稿模式：直接把 Uri 挂到 pendingDrafts（父页面保存后再真正上传）
            scope.launch {
                val meta = AttachmentUriMetadata.resolve(context, uri)
                pendingDrafts!!.add(
                    AttachmentPendingDraft(
                        uri = uri,
                        fileName = meta.fileName,
                        sizeBytes = meta.sizeBytes.coerceAtLeast(0)
                    )
                )
            }
            return
        }
        scope.launch {
            runCatching {
                facade.addAttachment(
                    AttachmentFacade.UploadRequest(
                        owner = requireNotNull(owner),
                        source = attachmentSource,
                        uri = uri,
                        isPlusActivated = isPlusActivated,
                        bitwardenPremium = bitwardenPremium,
                        bitwardenContext = bitwardenContext,
                        keepassContext = keepassContext,
                        kdbxSoftLimitAccepted = acceptSoftLimit
                    )
                )
            }.onFailure { e ->
                Toast.makeText(context, resolveErrorMessage(context, e), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 保持 URI 读权限，避免草稿阶段关闭 picker 后权限被收回
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val meta = AttachmentUriMetadata.resolve(context, uri)
        val validation = AttachmentSizeValidator.validate(
            sizeBytes = meta.sizeBytes,
            source = attachmentSource,
            userAcceptedSoftLimit = false
        )
        when (validation) {
            is AttachmentSizeValidator.Result.NeedsConfirm -> {
                softLimitPending = PendingUpload(
                    uri = uri,
                    actualBytes = validation.actualBytes,
                    softLimitBytes = validation.softLimitBytes
                )
            }
            else -> performUpload(uri, acceptSoftLimit = false)
        }
    }

    softLimitPending?.let { pending ->
        val actualMb = pending.actualBytes / (1024 * 1024)
        val limitMb = pending.softLimitBytes / (1024 * 1024)
        AlertDialog(
            onDismissRequest = { softLimitPending = null },
            title = { Text(stringResource(R.string.attachment_soft_limit_title)) },
            text = {
                Text(stringResource(R.string.attachment_soft_limit_message, actualMb, limitMb))
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pending.uri
                    softLimitPending = null
                    performUpload(uri, acceptSoftLimit = true)
                }) {
                    Text(stringResource(R.string.attachment_kdbx_soft_limit_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { softLimitPending = null }) {
                    Text(stringResource(R.string.attachment_kdbx_soft_limit_cancel))
                }
            }
        )
    }

    val visiblePersistedAttachments = persistedAttachments.filterNot {
        it.fileName in excludedFileNames ||
            (hideManagedCardFaces && CardFaceAttachment.isManagedFileName(it.fileName))
    }
    val draftItems = pendingDrafts ?: emptyList()
    val visibleCount = visiblePersistedAttachments.size + draftItems.size

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.attachments_section_title, visibleCount),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(stringResource(R.string.attachments_add))
                }
            }
            visiblePersistedAttachments.forEach { attachment ->
                EditRow(
                    title = attachment.fileName,
                    secondary = formatSecondaryShort(attachment),
                    onDelete = {
                        scope.launch {
                            runCatching {
                                facade.deleteAttachment(
                                    attachmentId = attachment.id,
                                    bitwardenContext = bitwardenContext,
                                    keepassContext = keepassContext
                                )
                            }
                                .onFailure { e ->
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
            draftItems.forEachIndexed { index, draft ->
                EditRow(
                    title = draft.fileName,
                    secondary = formatDraftSecondary(draft),
                    onDelete = {
                        pendingDrafts!!.removeAt(index)
                    }
                )
            }
        }
    }
}

/**
 * 把 [pendingDrafts] 里的草稿上传到 [AttachmentFacade]，挂到新创建的 [passwordId] 上。
 *
 * 调用方通常在保存密码拿到新 id 后调用。成功上传的草稿会从 [pendingDrafts] 中移除。
 * 任何一项失败都会继续处理剩余项，失败的草稿仍保留在列表里供 UI 展示。
 *
 * @return 成功上传的数量。
 */
suspend fun flushPendingDraftsTo(
    context: android.content.Context,
    passwordId: Long,
    pendingDrafts: SnapshotStateList<AttachmentPendingDraft>,
    isPlusActivated: Boolean,
    attachmentSource: AttachmentSource = AttachmentSource.LOCAL,
    bitwardenContext: AttachmentFacade.BitwardenContext? = null,
    bitwardenPremium: Boolean = true,
    keepassContext: AttachmentFacade.KeePassContext? = null,
    kdbxSoftLimitAccepted: Boolean = true
): Int = flushPendingDraftsTo(
    context = context,
    owner = AttachmentOwner.password(passwordId),
    pendingDrafts = pendingDrafts,
    isPlusActivated = isPlusActivated,
    attachmentSource = attachmentSource,
    bitwardenContext = bitwardenContext,
    bitwardenPremium = bitwardenPremium,
    keepassContext = keepassContext,
    kdbxSoftLimitAccepted = kdbxSoftLimitAccepted
)

suspend fun flushPendingDraftsTo(
    context: android.content.Context,
    owner: AttachmentOwner,
    pendingDrafts: SnapshotStateList<AttachmentPendingDraft>,
    isPlusActivated: Boolean,
    attachmentSource: AttachmentSource = AttachmentSource.LOCAL,
    bitwardenContext: AttachmentFacade.BitwardenContext? = null,
    bitwardenPremium: Boolean = true,
    keepassContext: AttachmentFacade.KeePassContext? = null,
    kdbxSoftLimitAccepted: Boolean = true
): Int {
    if (pendingDrafts.isEmpty()) return 0
    val facade = AttachmentContainer.facade(context)
    var successCount = 0
    val snapshot = pendingDrafts.toList()
    snapshot.forEach { draft ->
        val result = runCatching {
            facade.addAttachment(
                AttachmentFacade.UploadRequest(
                    owner = owner,
                    source = attachmentSource,
                    uri = draft.uri,
                    isPlusActivated = isPlusActivated,
                    bitwardenPremium = bitwardenPremium,
                    bitwardenContext = bitwardenContext,
                    keepassContext = keepassContext,
                    kdbxSoftLimitAccepted = kdbxSoftLimitAccepted
                )
            )
        }
        if (result.isSuccess) {
            pendingDrafts.remove(draft)
            successCount++
        } else {
            android.util.Log.w(
                "AttachmentsEditSection",
                "Failed to flush pending attachment ${draft.fileName}: ${result.exceptionOrNull()?.message}"
            )
        }
    }
    return successCount
}

@Composable
private fun EditRow(
    title: String,
    secondary: String,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.attachments_delete))
        }
    }
}

/** 软上限二次确认的挂起项：文件已挑选但等待用户确认。 */
private data class PendingUpload(
    val uri: Uri,
    val actualBytes: Long,
    val softLimitBytes: Long
)

private fun formatSecondaryShort(attachment: Attachment): String {
    val sizeText = humanReadableSize(attachment.sizeBytes)
    val sourceLabel = when (attachment.sourceEnum) {
        AttachmentSource.LOCAL -> "Local"
        AttachmentSource.BITWARDEN -> "Bitwarden"
        AttachmentSource.KEEPASS -> "KeePass"
    }
    return if (sizeText.isBlank()) sourceLabel else "$sourceLabel · $sizeText"
}

private fun formatDraftSecondary(draft: AttachmentPendingDraft): String {
    val sizeText = humanReadableSize(draft.sizeBytes)
    val label = "Pending"
    return if (sizeText.isBlank()) label else "$label · $sizeText"
}

private fun humanReadableSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = (bytes + 1023) / 1024
    return if (kb >= 1024) "${kb / 1024} MB" else "$kb KB"
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
        is AttachmentError.TooLarge -> {
            val mb = e.limitBytes / (1024 * 1024)
            context.getString(resId, "${mb} MB")
        }
        is AttachmentError.NetworkError -> context.getString(resId, e.httpStatus?.toString() ?: "-")
        else -> context.getString(resId)
    }
}
