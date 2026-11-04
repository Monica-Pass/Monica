package takagi.ru.monica.attachments.ui

import android.net.Uri
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.CancellationException
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
    hideManagedCardFaces: Boolean = false,
    persistAttachment: (suspend (Uri, Boolean) -> Unit)? = null,
    removeAttachment: (suspend (Long) -> Unit)? = null
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
    hideManagedCardFaces = hideManagedCardFaces,
    persistAttachment = persistAttachment,
    removeAttachment = removeAttachment
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
    hideManagedCardFaces: Boolean = false,
    persistAttachment: (suspend (Uri, Boolean) -> Unit)? = null,
    removeAttachment: (suspend (Long) -> Unit)? = null
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
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun performUpload(uri: Uri, acceptSoftLimit: Boolean) {
        if (busy) return
        busy = true
        errorMessage = null
        scope.launch {
            try {
                if (isDraftMode) {
                    if (attachmentSource == AttachmentSource.BITWARDEN && !bitwardenPremium) {
                        throw AttachmentError.PremiumRequired
                    }
                    val meta = AttachmentUriMetadata.resolve(context, uri)
                    pendingDrafts!!.add(AttachmentPendingDraft(uri, meta.fileName, meta.sizeBytes.coerceAtLeast(0)))
                } else if (persistAttachment != null) {
                    persistAttachment(uri, acceptSoftLimit)
                } else {
                    facade.addAttachment(
                        AttachmentFacade.UploadRequest(
                            owner = requireNotNull(owner), source = attachmentSource, uri = uri,
                            isPlusActivated = isPlusActivated, bitwardenPremium = bitwardenPremium,
                            bitwardenContext = bitwardenContext, keepassContext = keepassContext,
                            kdbxSoftLimitAccepted = acceptSoftLimit
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorMessage = resolveErrorMessage(context, error)
            } finally {
                busy = false
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
        val meta = try {
            AttachmentUriMetadata.resolve(context, uri)
        } catch (error: Exception) {
            errorMessage = resolveErrorMessage(context, error)
            return@rememberLauncherForActivityResult
        }
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
            is AttachmentSizeValidator.Result.TooLarge -> {
                if (validation.actualBytes < 0) performUpload(uri, acceptSoftLimit = false)
                else errorMessage = resolveErrorMessage(context, AttachmentError.TooLarge(validation.limitBytes, validation.actualBytes))
            }
            AttachmentSizeValidator.Result.Ok -> performUpload(uri, acceptSoftLimit = false)
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

    AttachmentEditorContent(
        items = visiblePersistedAttachments.map { attachment ->
            AttachmentEditorItem(
                key = "saved:${attachment.id}", title = attachment.fileName,
                secondary = formatSecondaryShort(attachment),
                onRemove = {
                    if (!busy) {
                        busy = true
                        errorMessage = null
                        scope.launch {
                            try {
                                if (removeAttachment != null) removeAttachment(attachment.id)
                                else facade.deleteAttachment(attachment.id, bitwardenContext, keepassContext)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                errorMessage = resolveErrorMessage(context, error)
                            } finally { busy = false }
                        }
                    }
                }
            )
        } + draftItems.mapIndexed { index, draft ->
            AttachmentEditorItem("draft:$index", draft.fileName, formatDraftSecondary(draft)) {
                pendingDrafts?.remove(draft)
            }
        },
        busy = busy,
        errorMessage = errorMessage,
        onDismissError = { errorMessage = null },
        onAdd = { picker.launch(arrayOf("*/*")) },
        modifier = modifier
    )
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
    kdbxSoftLimitAccepted: Boolean = true,
    persistAttachment: (suspend (Uri, Boolean) -> Unit)? = null
): Int = flushPendingDraftsTo(
    context = context,
    owner = AttachmentOwner.password(passwordId),
    pendingDrafts = pendingDrafts,
    isPlusActivated = isPlusActivated,
    attachmentSource = attachmentSource,
    bitwardenContext = bitwardenContext,
    bitwardenPremium = bitwardenPremium,
    keepassContext = keepassContext,
    kdbxSoftLimitAccepted = kdbxSoftLimitAccepted,
    persistAttachment = persistAttachment
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
    kdbxSoftLimitAccepted: Boolean = true,
    persistAttachment: (suspend (Uri, Boolean) -> Unit)? = null
): Int {
    if (pendingDrafts.isEmpty()) return 0
    val facade = AttachmentContainer.facade(context)
    var successCount = 0
    val snapshot = pendingDrafts.toList()
    snapshot.forEach { draft ->
        val result = runCatching {
            if (persistAttachment != null) persistAttachment(draft.uri, kdbxSoftLimitAccepted)
            else facade.addAttachment(
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
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            android.util.Log.w(
                "AttachmentsEditSection",
                "Pending attachment could not be saved (${result.exceptionOrNull()?.javaClass?.simpleName})"
            )
        }
    }
    return successCount
}

/** 软上限二次确认的挂起项：文件已挑选但等待用户确认。 */
private data class PendingUpload(
    val uri: Uri,
    val actualBytes: Long,
    val softLimitBytes: Long
)

@Composable
private fun formatSecondaryShort(attachment: Attachment): String {
    val sizeText = humanReadableSize(attachment.sizeBytes)
    val sourceLabel = when (attachment.sourceEnum) {
        AttachmentSource.LOCAL -> stringResource(R.string.legacy_ui_local)
        AttachmentSource.BITWARDEN -> "Bitwarden"
        AttachmentSource.KEEPASS -> "KeePass"
    }
    return if (sizeText.isBlank()) sourceLabel else "$sourceLabel · $sizeText"
}

@Composable
private fun formatDraftSecondary(draft: AttachmentPendingDraft): String {
    val sizeText = humanReadableSize(draft.sizeBytes)
    val label = stringResource(R.string.legacy_ui_attachment_pending)
    return if (sizeText.isBlank()) label else "$label · $sizeText"
}

private fun humanReadableSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = (bytes + 1023) / 1024
    return if (kb >= 1024) "${kb / 1024} MB" else "$kb KB"
}

private fun resolveErrorMessage(context: android.content.Context, e: Throwable): String {
    return attachmentErrorMessage(context, e)
}
